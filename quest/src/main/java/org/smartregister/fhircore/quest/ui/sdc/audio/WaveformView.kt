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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Sound-wave bars. While recording, new levels scroll in from the right ([live] = true); a saved
 * recording shows its whole waveform, with the played part highlighted ([progress]).
 */
class WaveformView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

  private val density = resources.displayMetrics.density
  private val barWidth = 3f * density
  private val barGap = 2f * density
  private val minBar = 2f * density
  private val rect = RectF()
  private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

  private val levels = ArrayList<Float>()

  /** Colour of played / live bars. */
  var activeColor: Int = Color.parseColor("#1E9B2E")
    set(value) {
      field = value
      invalidate()
    }

  /** Colour of bars not yet played. */
  var inactiveColor: Int = Color.parseColor("#C9CEDB")
    set(value) {
      field = value
      invalidate()
    }

  /** True while recording: show the latest levels, newest on the right. */
  var live: Boolean = false
    set(value) {
      field = value
      invalidate()
    }

  /** Playback position 0..1 (bars before it use [activeColor]). */
  var progress: Float = 0f
    set(value) {
      field = value.coerceIn(0f, 1f)
      invalidate()
    }

  fun clear() {
    levels.clear()
    progress = 0f
    invalidate()
  }

  /** Adds one level, 0..1. */
  fun addLevel(level: Float) {
    levels.add(level.coerceIn(0f, 1f))
    if (levels.size > MAX_LEVELS) levels.subList(0, levels.size - MAX_LEVELS).clear()
    invalidate()
  }

  fun setLevels(values: List<Float>) {
    levels.clear()
    levels.addAll(values.map { it.coerceIn(0f, 1f) })
    invalidate()
  }

  fun getLevels(): List<Float> = levels.toList()

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val slots = max(1, ((width + barGap) / (barWidth + barGap)).toInt())
    val bars: List<Float> =
      when {
        levels.isEmpty() -> List(slots) { 0f }
        live -> List(max(0, slots - levels.size)) { 0f } + levels.takeLast(slots)
        else -> resample(levels, slots)
      }
    val centre = height / 2f
    val played = (bars.size * progress).toInt()
    bars.forEachIndexed { index, level ->
      val half = max(minBar, level * height) / 2f
      val left = index * (barWidth + barGap)
      rect.set(left, centre - half, left + barWidth, centre + half)
      paint.color =
        when {
          live -> activeColor
          index < played -> activeColor
          levels.isEmpty() -> inactiveColor
          else -> if (progress > 0f) inactiveColor else activeColor
        }
      canvas.drawRoundRect(rect, barWidth / 2, barWidth / 2, paint)
    }
  }

  private fun resample(values: List<Float>, count: Int): List<Float> =
    List(count) { i ->
      val start = i * values.size / count
      val end = max(start + 1, min(values.size, (i + 1) * values.size / count))
      values.subList(start, end).maxOrNull() ?: 0f
    }

  companion object {
    private const val MAX_LEVELS = 4000

    /** Compact text form of a waveform (64 digits 0-9), stored with the recording. */
    fun encode(values: List<Float>, count: Int = 64): String {
      if (values.isEmpty()) return ""
      val view = List(count) { i ->
        val start = i * values.size / count
        val end = max(start + 1, min(values.size, (i + 1) * values.size / count))
        values.subList(min(start, values.size - 1), end).maxOrNull() ?: 0f
      }
      return view.joinToString("") { (it * 9).toInt().coerceIn(0, 9).toString() }
    }

    fun decode(text: String?): List<Float> =
      text.orEmpty().filter { it.isDigit() }.map { (it - '0') / 9f }
  }
}
