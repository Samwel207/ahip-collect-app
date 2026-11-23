/*
 * Copyright 2021-2024 Ona Systems, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.smartregister.fhircore.quest.ui.sdc.audio

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.fhir.datacapture.extensions.itemControlCode
import com.google.android.fhir.datacapture.extensions.tryUnwrapContext
import com.google.android.fhir.datacapture.views.HeaderView
import com.google.android.fhir.datacapture.views.QuestionnaireViewItem
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemAndroidViewHolderDelegate
import com.google.android.fhir.datacapture.views.factories.QuestionnaireItemAndroidViewHolderFactory
import com.google.android.material.button.MaterialButton
import java.io.File
import java.util.Date
import kotlin.math.sqrt
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Attachment
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import org.smartregister.fhircore.quest.R
import timber.log.Timber

/**
 * Records an audio answer on the phone for an `attachment` item with item control
 * `audio-recorder`: a round record button, a timer and live sound waves while recording; the
 * saved recording shows its waveform with Play / Delete. The recording (AAC .m4a, mono, about
 * 0.2 MB per minute) is stored in the answer as an Attachment, so it is saved with the draft /
 * response and synced with it. Works fully offline.
 */
object AudioRecorderViewHolderFactory :
  QuestionnaireItemAndroidViewHolderFactory(R.layout.audio_recorder_view) {

  const val AUDIO_RECORDER_UI_CONTROL_CODE = "audio-recorder"
  private const val DEFAULT_MAX_MINUTES = 5
  private const val MAX_MINUTES_URL = "https://smartregister.org/audio-max-duration-minutes"
  private const val WAVEFORM_URL = "https://smartregister.org/audio-waveform"
  private const val DURATION_URL = "https://smartregister.org/audio-duration-ms"
  private const val CONTENT_TYPE = "audio/mp4"
  private const val PERMISSION_REQUEST_CODE = 4711
  private const val TICK_MS = 80L

  fun matcher(questionnaireItem: Questionnaire.QuestionnaireItemComponent): Boolean =
    questionnaireItem.itemControlCode == AUDIO_RECORDER_UI_CONTROL_CODE

  override fun getQuestionnaireItemViewHolderDelegate() =
    object : QuestionnaireItemAndroidViewHolderDelegate {
      override lateinit var questionnaireViewItem: QuestionnaireViewItem

      private lateinit var activity: AppCompatActivity
      private lateinit var header: HeaderView
      private lateinit var recordButton: MaterialButton
      private lateinit var playButton: MaterialButton
      private lateinit var deleteButton: MaterialButton
      private lateinit var timer: TextView
      private lateinit var status: TextView
      private lateinit var waveform: WaveformView

      private val handler = Handler(Looper.getMainLooper())
      private var recorder: MediaRecorder? = null
      private var recordingFile: File? = null
      private var recordingStartedAt = 0L
      private var player: MediaPlayer? = null
      private var boundLinkId: String? = null
      private var readOnly = false

      private val recordingTicker =
        object : Runnable {
          override fun run() {
            val active = recorder ?: return
            timer.text = formatDuration(SystemClock.elapsedRealtime() - recordingStartedAt)
            // Square root makes quiet speech visible without clipping loud sounds.
            val amplitude = runCatching { active.maxAmplitude }.getOrDefault(0)
            waveform.addLevel(sqrt(amplitude / 32767f))
            handler.postDelayed(this, TICK_MS)
          }
        }

      private val playbackTicker =
        object : Runnable {
          override fun run() {
            val active = player ?: return
            if (active.duration > 0) {
              waveform.progress = active.currentPosition.toFloat() / active.duration
              timer.text = formatDuration(active.currentPosition.toLong())
            }
            handler.postDelayed(this, TICK_MS)
          }
        }

      override fun init(itemView: View) {
        activity = itemView.context.tryUnwrapContext()!!
        header = itemView.findViewById(com.google.android.fhir.datacapture.R.id.header)
        recordButton = itemView.findViewById(R.id.audio_record_button)
        playButton = itemView.findViewById(R.id.audio_play_button)
        deleteButton = itemView.findViewById(R.id.audio_delete_button)
        timer = itemView.findViewById(R.id.audio_timer)
        status = itemView.findViewById(R.id.audio_status)
        waveform = itemView.findViewById(R.id.audio_waveform)
        waveform.activeColor = ContextCompat.getColor(activity, R.color.audio_wave_active)
        waveform.inactiveColor = ContextCompat.getColor(activity, R.color.audio_wave_inactive)

        recordButton.setOnClickListener {
          if (recorder != null) stopRecording(save = true) else startRecording()
        }
        playButton.setOnClickListener { togglePlayback() }
        deleteButton.setOnClickListener {
          stopPlayback()
          activity.lifecycleScope.launch {
            questionnaireViewItem.clearAnswer()
            showState()
          }
        }
      }

      override fun bind(questionnaireViewItem: QuestionnaireViewItem) {
        // The view is being reused for another question: never carry a recording across.
        if (boundLinkId != null && boundLinkId != questionnaireViewItem.questionnaireItem.linkId) {
          stopRecording(save = false)
          stopPlayback()
        }
        boundLinkId = questionnaireViewItem.questionnaireItem.linkId
        header.bind(questionnaireViewItem)
        if (recorder == null && player == null) showState()
      }

      override fun setReadOnly(isReadOnly: Boolean) {
        readOnly = isReadOnly
        recordButton.isEnabled = !isReadOnly
        deleteButton.isEnabled = !isReadOnly
      }

      /** Longest recording allowed for this item (extension [MAX_MINUTES_URL], default 5 min). */
      private fun maxMinutes(): Int =
        (questionnaireViewItem.questionnaireItem.getExtensionByUrl(MAX_MINUTES_URL)?.value
            as? IntegerType)
          ?.value
          ?.takeIf { it > 0 } ?: DEFAULT_MAX_MINUTES

      private fun currentAttachment(): Attachment? =
        questionnaireViewItem.answers
          .firstOrNull()
          ?.takeIf { it.hasValueAttachment() }
          ?.valueAttachment

      private fun showState() {
        val attachment = currentAttachment()
        val hasAudio = attachment?.hasData() == true
        setRecordButton(recording = false)
        recordButton.isEnabled = !readOnly
        playButton.visibility = if (hasAudio) View.VISIBLE else View.GONE
        deleteButton.visibility = if (hasAudio && !readOnly) View.VISIBLE else View.GONE
        waveform.live = false
        waveform.progress = 0f
        if (hasAudio) {
          waveform.setLevels(
            WaveformView.decode(
              (attachment!!.getExtensionByUrl(WAVEFORM_URL)?.value as? StringType)?.value,
            ),
          )
          val duration =
            (attachment.getExtensionByUrl(DURATION_URL)?.value as? IntegerType)?.value ?: 0
          timer.text = formatDuration(duration.toLong())
          status.text =
            activity.getString(
              R.string.audio_recorded,
              Formatter.formatShortFileSize(activity, attachment.data.size.toLong()),
            )
        } else {
          waveform.clear()
          timer.text = formatDuration(0)
          status.text = activity.getString(R.string.audio_hint, maxMinutes())
        }
      }

      private fun setRecordButton(recording: Boolean) {
        recordButton.setIconResource(if (recording) R.drawable.ic_audio_stop else R.drawable.ic_audio_mic)
        recordButton.backgroundTintList =
          ColorStateList.valueOf(
            ContextCompat.getColor(
              activity,
              if (recording) R.color.audio_recording else R.color.audio_record_idle,
            ),
          )
        recordButton.contentDescription =
          activity.getString(if (recording) R.string.audio_stop else R.string.audio_record)
      }

      private fun startRecording() {
        if (
          ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
          ActivityCompat.requestPermissions(
            activity,
            arrayOf(Manifest.permission.RECORD_AUDIO),
            PERMISSION_REQUEST_CODE,
          )
          status.text = activity.getString(R.string.audio_permission_needed)
          return
        }
        stopPlayback()
        val file = File(activity.cacheDir, "audio-${System.currentTimeMillis()}.m4a")
        val newRecorder =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(activity)
          } else {
            @Suppress("DEPRECATION") MediaRecorder()
          }
        try {
          newRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(16000)
            // Long interview recordings use a lower bit rate to keep the form small enough to sync.
            setAudioEncodingBitRate(if (maxMinutes() > 10) 24000 else 32000)
            setMaxDuration(maxMinutes() * 60 * 1000)
            setOutputFile(file.absolutePath)
            setOnInfoListener { _, what, _ ->
              if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                stopRecording(save = true)
              }
            }
            prepare()
            start()
          }
        } catch (exception: Exception) {
          Timber.e(exception, "Audio recording failed to start")
          newRecorder.release()
          file.delete()
          status.text = activity.getString(R.string.audio_failed)
          return
        }
        recorder = newRecorder
        recordingFile = file
        recordingStartedAt = SystemClock.elapsedRealtime()
        setRecordButton(recording = true)
        playButton.visibility = View.GONE
        deleteButton.visibility = View.GONE
        waveform.clear()
        waveform.live = true
        status.text = activity.getString(R.string.audio_recording)
        handler.post(recordingTicker)
      }

      private fun stopRecording(save: Boolean) {
        val active = recorder ?: return
        recorder = null
        handler.removeCallbacks(recordingTicker)
        val file = recordingFile
        recordingFile = null
        val stopped = runCatching { active.stop() }.isSuccess // fails if stopped too soon
        active.release()
        if (!save || !stopped || file == null || !file.exists() || file.length() == 0L) {
          file?.delete()
          showState()
          return
        }
        val elapsed = SystemClock.elapsedRealtime() - recordingStartedAt
        val bytes = file.readBytes()
        file.delete()
        val attachment =
          Attachment().apply {
            contentType = CONTENT_TYPE
            data = bytes
            size = bytes.size
            title =
              "${questionnaireViewItem.questionnaireItem.linkId} (${formatDuration(elapsed)}).m4a"
            creation = Date()
            addExtension(WAVEFORM_URL, StringType(WaveformView.encode(waveform.getLevels())))
            addExtension(DURATION_URL, IntegerType(elapsed.toInt()))
          }
        activity.lifecycleScope.launch {
          questionnaireViewItem.setAnswer(
            QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent().setValue(attachment),
          )
          showState()
        }
      }

      private fun togglePlayback() {
        if (player != null) {
          stopPlayback()
          showState()
          return
        }
        val data = currentAttachment()?.data ?: return
        val file = File(activity.cacheDir, "audio-playback.m4a").apply { writeBytes(data) }
        try {
          player =
            MediaPlayer().apply {
              setDataSource(file.absolutePath)
              setOnCompletionListener {
                stopPlayback()
                showState()
              }
              prepare()
              start()
            }
          playButton.text = activity.getString(R.string.audio_pause)
          playButton.setIconResource(R.drawable.ic_audio_stop)
          handler.post(playbackTicker)
        } catch (exception: Exception) {
          Timber.e(exception, "Audio playback failed")
          stopPlayback()
        }
      }

      private fun stopPlayback() {
        handler.removeCallbacks(playbackTicker)
        player?.let { runCatching { it.stop() } }
        player?.release()
        player = null
        if (::playButton.isInitialized) {
          playButton.text = activity.getString(R.string.audio_play)
          playButton.setIconResource(R.drawable.ic_audio_play)
        }
      }

      private fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        return "%d:%02d".format(seconds / 60, seconds % 60)
      }
    }
}
