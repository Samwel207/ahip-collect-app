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

package org.smartregister.fhircore.quest.ui.questionnaire

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.location.Location
import android.graphics.Color
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import ca.uhn.fhir.parser.IParser
import com.google.android.fhir.datacapture.QuestionnaireFragment
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import dagger.hilt.android.AndroidEntryPoint
import java.io.Serializable
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import com.google.android.fhir.datacapture.validation.QuestionnaireResponseValidator
import com.google.android.fhir.datacapture.extensions.logicalId
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.BooleanType
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.hl7.fhir.r4.model.StringType
import org.json.JSONObject
import org.hl7.fhir.r4.model.Resource
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.configuration.app.LocationLogOptions
import org.smartregister.fhircore.engine.domain.model.ActionParameter
import org.smartregister.fhircore.engine.domain.model.isReadOnly
import org.smartregister.fhircore.engine.domain.model.isSummary
import org.smartregister.fhircore.engine.ui.base.AlertDialogButton
import org.smartregister.fhircore.engine.ui.base.AlertDialogue
import org.smartregister.fhircore.engine.ui.base.AlertIntent
import org.smartregister.fhircore.engine.ui.base.BaseMultiLanguageActivity
import org.smartregister.fhircore.engine.util.DispatcherProvider
import org.smartregister.fhircore.engine.util.SharedPreferenceKey
import org.smartregister.fhircore.engine.util.SharedPreferencesHelper
import org.smartregister.fhircore.engine.util.extension.encodeResourceToString
import org.smartregister.fhircore.engine.util.extension.parcelable
import org.smartregister.fhircore.engine.util.extension.parcelableArrayList
import org.smartregister.fhircore.engine.util.extension.showToast
import org.smartregister.fhircore.engine.util.location.LocationUtils
import org.smartregister.fhircore.engine.util.location.PermissionUtils
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.databinding.QuestionnaireActivityBinding
import org.smartregister.fhircore.quest.ui.shared.ActivityOnResultType
import org.smartregister.fhircore.quest.ui.shared.ON_RESULT_TYPE
import org.smartregister.fhircore.quest.util.ResourceUtils
import timber.log.Timber

@AndroidEntryPoint
class QuestionnaireActivity : BaseMultiLanguageActivity() {

  @Inject lateinit var dispatcherProvider: DispatcherProvider

  @Inject lateinit var fhirParser: IParser

  @Inject lateinit var configurationRegistry: ConfigurationRegistry

  /** Draft this form saves into: the one opened, or the first auto-save. */
  private var autosavedDraftId: String? = null

  // Timed quiz (extension urn:ahip:quiz {"timeLimitMinutes": n}): one countdown for the whole form.
  private var quizTimer: CountDownTimer? = null
  private var quizStartedAt = 0L
  private var quizTimedOut = false
  val viewModel by viewModels<QuestionnaireViewModel>()
  private lateinit var questionnaireConfig: QuestionnaireConfig
  private lateinit var actionParameters: ArrayList<ActionParameter>
  private lateinit var viewBinding: QuestionnaireActivityBinding
  private var alertDialog: AlertDialog? = null
  private lateinit var fusedLocationClient: FusedLocationProviderClient
  private var currentLocation: Location? = null
  private val locationPermissionLauncher: ActivityResultLauncher<Array<String>> =
    registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
      permissions: Map<String, Boolean> ->
      PermissionUtils.getLocationPermissionLauncher(
        permissions = permissions,
        onFineLocationPermissionGranted = { fetchLocation() },
        onCoarseLocationPermissionGranted = { fetchLocation() },
        onLocationPermissionDenied = {
          showToast(
            getString(R.string.location_permissions_denied),
            Toast.LENGTH_SHORT,
          )
        },
      )
    }

  private val activityResultLauncher: ActivityResultLauncher<Intent> =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
      activityResult: ActivityResult ->
      if (activityResult.resultCode == Activity.RESULT_OK) {
        fetchLocation()
      }
    }

  private val recordAudioRequestPermissionLauncher =
    registerForActivityResult(
      ActivityResultContracts.RequestPermission(),
    ) {
      if (it) {
        viewModel.showSpeechToText()
      } else {
        viewModel.hideSpeechToText()
        showToast(
          getString(R.string.record_audio_denied),
          Toast.LENGTH_SHORT,
        )
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    setTheme(org.smartregister.fhircore.engine.R.style.AppTheme_Questionnaire)
    viewBinding = QuestionnaireActivityBinding.inflate(layoutInflater)
    setContentView(viewBinding.root)
    with(intent) {
      parcelable<QuestionnaireConfig>(QUESTIONNAIRE_CONFIG)?.also { questionnaireConfig = it }
      actionParameters = parcelableArrayList(QUESTIONNAIRE_ACTION_PARAMETERS) ?: arrayListOf()
    }

    if (!::questionnaireConfig.isInitialized) {
      showToast(getString(R.string.missing_questionnaire_config))
      finish()
      return
    }

    viewBinding.questionnaireToolbar.setNavigationIcon(R.drawable.ic_cancel)
    viewBinding.questionnaireToolbar.setNavigationOnClickListener { handleBackPress() }
    viewBinding.questionnaireTitle.text = questionnaireConfig.title
    viewBinding.clearAll.visibility =
      if (questionnaireConfig.showClearAll) View.VISIBLE else View.GONE
    if (questionnaireConfig.saveDraft && !questionnaireConfig.isReadOnly()) {
      viewBinding.saveDraft.visibility = View.VISIBLE
      viewBinding.saveDraft.setOnClickListener { saveDraftAndFinish() }
    }

    if (questionnaireConfig.enableSpeechToText) {
      viewBinding.recordSpeechActionButton.setOnClickListener {
        reviewRecordAudioPermissionToLaunchSpeechToText()
      }
      viewBinding.editFormActionButton.setOnClickListener { viewModel.hideSpeechToText() }
    }

    // A draft to continue: one passed in, or the one auto-saved before Android closed the app
    // (low-memory phones close it while the camera is open).
    autosavedDraftId =
      savedInstanceState?.getString(AUTOSAVED_DRAFT_ID)
        ?: intent.getStringExtra(QUESTIONNAIRE_DRAFT_RESPONSE_ID)

    lifecycleScope.launch {
      // After Android has closed the app, settings are not loaded yet: reload them first.
      if (configurationRegistry.configsJsonMap.isEmpty()) reloadConfigurations()
      // Fragment state is not kept (see onSaveInstanceState), so always (re)build the form.
      if (supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG) == null) {
        launchQuestionnaire()
      }
      setupLocationServices()
    }

    this.onBackPressedDispatcher.addCallback(
      this,
      object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
          handleBackPress()
        }
      },
    )
  }

  private fun reviewRecordAudioPermissionToLaunchSpeechToText() {
    when {
      PermissionUtils.checkPermissions(
        this@QuestionnaireActivity,
        listOf(Manifest.permission.RECORD_AUDIO),
      ) -> {
        viewModel.showSpeechToText()
      }
      ActivityCompat.shouldShowRequestPermissionRationale(
        this,
        Manifest.permission.RECORD_AUDIO,
      ) -> {
        showToast(
          getString(R.string.record_audio_denied),
          Toast.LENGTH_SHORT,
        )
      }
      else -> {
        recordAudioRequestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
      }
    }
  }

  private fun showProgressDialog(progressState: QuestionnaireProgressState?) {
    alertDialog =
      if (progressState?.active == false) {
        alertDialog?.dismiss()
        null
      } else {
        when (progressState) {
          is QuestionnaireProgressState.ExtractionInProgress ->
            AlertDialogue.showProgressAlert(this, R.string.extraction_in_progress)
          is QuestionnaireProgressState.QuestionnaireLaunch ->
            AlertDialogue.showProgressAlert(this, R.string.loading_questionnaire)
          else -> null
        }
      }
  }

  private fun setupLocationServices() {
    if (
      viewModel.applicationConfiguration.logGpsLocation.contains(LocationLogOptions.QUESTIONNAIRE)
    ) {
      fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

      if (!LocationUtils.isLocationEnabled(this)) {
        showLocationSettingsDialog(
          Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            putExtra(ON_RESULT_TYPE, ActivityOnResultType.LOCATION.name)
          },
        )
      }

      if (!PermissionUtils.hasLocationPermissions(this)) {
        locationPermissionLauncher.launch(
          arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
          ),
        )
      }

      if (
        currentLocation == null &&
          LocationUtils.isLocationEnabled(this) &&
          PermissionUtils.hasLocationPermissions(this)
      ) {
        fetchLocation()
      }
    }
  }

  private fun showLocationSettingsDialog(intent: Intent) {
    showProgressDialog(QuestionnaireProgressState.QuestionnaireLaunch(false))
    AlertDialog.Builder(this)
      .setMessage(getString(R.string.location_services_disabled))
      .setCancelable(true)
      .setPositiveButton(getString(R.string.yes)) { _, _ -> activityResultLauncher.launch(intent) }
      .setNegativeButton(getString(R.string.no)) { dialog, _ -> dialog.cancel() }
      .show()
  }

  fun fetchLocation(highAccuracy: Boolean = true) {
    lifecycleScope.launch {
      try {
        currentLocation =
          async(dispatcherProvider.io()) {
              if (highAccuracy) {
                LocationUtils.getAccurateLocation(fusedLocationClient)
              } else {
                LocationUtils.getApproximateLocation(fusedLocationClient)
              }
            }
            .await()
      } catch (e: Exception) {
        Timber.e(e, "Failed to get GPS location for questionnaire: ${questionnaireConfig.id}")
        showToast("Failed to get GPS location", Toast.LENGTH_LONG)
      }
    }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    outState.clear()
    // Only the auto-saved draft id is kept: the form is rebuilt from it if Android closes the app.
    autosavedDraftId?.let { outState.putString(AUTOSAVED_DRAFT_ID, it) }
  }

  /**
   * Auto-saves the answers as a draft whenever the form goes to the background (e.g. the camera
   * opens), so nothing is lost if Android closes the app meanwhile. Only for draft-enabled forms.
   */
  override fun onStop() {
    super.onStop()
    if (
      ::questionnaireConfig.isInitialized &&
        questionnaireConfig.saveDraft &&
        !questionnaireConfig.isReadOnly() &&
        !isFinishing
    ) {
      lifecycleScope.launch { autosaveDraft() }
    }
  }

  private suspend fun autosaveDraft() {
    val questionnaireResponse = retrieveQuestionnaireResponse() ?: return
    questionnaireResponse.useDraftId()
    viewModel.saveDraftQuestionnaire(questionnaireResponse, questionnaireConfig)
    if (questionnaireResponse.logicalId.isNotEmpty()) autosavedDraftId = questionnaireResponse.logicalId
  }

  /**
   * Tags requested by the caller ([QUESTIONNAIRE_RESPONSE_TAGS], "system|code"), added before the
   * first save: the server ignores tags added to a resource later.
   */
  private fun QuestionnaireResponse.applyRequestedTags() {
    intent.getStringArrayListExtra(QUESTIONNAIRE_RESPONSE_TAGS)?.forEach { tag ->
      val system = tag.substringBefore("|")
      val code = tag.substringAfter("|")
      if (meta.tag.none { it.system == system && it.code == code }) meta.addTag(system, code, code)
    }
  }

  /**
   * Prepares a response for saving: requested tags, large photos shrunk, and the same draft id
   * every time (so saving again or submitting updates the draft instead of creating copies).
   */
  private suspend fun QuestionnaireResponse.useDraftId() {
    applyRequestedTags()
    PhotoCompressor.compressPhotos(this)
    if (autosavedDraftId != null) {
      id = autosavedDraftId
    } else if (logicalId.isEmpty()) {
      id = java.util.UUID.randomUUID().toString()
    }
  }

  private suspend fun reloadConfigurations() {
    val appId = sharedPreferencesHelper.read(SharedPreferenceKey.APP_ID.name, null) ?: return
    runCatching { configurationRegistry.loadConfigurations(appId, this) }
      .onFailure { Timber.e(it, "Could not reload configurations") }
  }

  private fun renderSpeechToTextFragment() {
    supportFragmentManager.commit {
      setReorderingAllowed(true)
      replace(R.id.speechToTextContainer, SpeechToTextFragment(), SPEECH_TO_TEXT_FRAGMENT_TAG)
    }
  }

  private fun removeSpeechToTextFragment() {
    supportFragmentManager.commit {
      setReorderingAllowed(true)
      supportFragmentManager.findFragmentByTag(SPEECH_TO_TEXT_FRAGMENT_TAG)?.let { remove(it) }
    }
  }

  private suspend fun launchQuestionnaire() {
    showProgressDialog(QuestionnaireProgressState.QuestionnaireLaunch(true))

    val questionnaire = viewModel.retrieveQuestionnaire(questionnaireConfig)
    when {
      questionnaire == null -> {
        showToast(getString(R.string.questionnaire_not_found))
        finish()
      }
      questionnaire.subjectType.isNullOrEmpty() -> {
        val subjectRequiredMessage = getString(R.string.missing_subject_type)
        showToast(subjectRequiredMessage)
        Timber.e(subjectRequiredMessage)
        finish()
      }
      else -> {
        viewModel.setQuestionnaire(questionnaire)

        val (populatedResponse, launchContextResources) =
          viewModel.populateQuestionnaire(
            viewModel.currentQuestionnaire,
            questionnaireConfig,
            actionParameters,
          )
        // Reopening a saved draft: continue from its answers (and keep its id, so saving the
        // draft again or submitting updates it instead of creating a copy).
        val questionnaireResponse =
          autosavedDraftId?.let { viewModel.loadDraftQuestionnaireResponse(it) } ?: populatedResponse

        viewModel.showQuestionnaireResponse(questionnaireResponse)

        showProgressDialog(QuestionnaireProgressState.QuestionnaireLaunch(false))
        startQuizTimer(viewModel.currentQuestionnaire)

        // "Edit" on one question of a draft: open the form at that question.
        intent.getStringExtra(QUESTIONNAIRE_FOCUS_LINK_ID)?.let { linkId ->
          lifecycleScope.launch { focusOnQuestion(viewModel.currentQuestionnaire, linkId) }
        }

        listenForQuestionnaireFormUpdates(viewModel.currentQuestionnaire, launchContextResources)
      }
    }
  }

  private suspend fun listenForQuestionnaireFormUpdates(
    questionnaire: Questionnaire,
    launchContextResources: List<Resource>,
  ) {
    val handler = Handler(Looper.getMainLooper())
    viewModel.questionnaireFormUpdateStateflow.collect {
      when (it) {
        is QuestionnaireFormUpdate.ShowSpeechToTextSubView -> {
          viewBinding.recordSpeechActionButton.visibility = View.GONE
          viewBinding.editFormActionButton.visibility = View.VISIBLE
          viewBinding.speechToTextContainer.visibility = View.VISIBLE
          renderSpeechToTextFragment()
          val disabledQuestionnaire =
            questionnaire.copy().apply { item.forEach(viewModel::disableQuestionnaireItem) }

          renderQuestionnaire(
            disabledQuestionnaire,
            it.currentQuestionnaireResponse,
            launchContextResources,
          )

          // Disable form buttons - very hacky
          handler.postDelayed(
            {
              supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG)?.view?.let {
                fragmentView ->
                fragmentView
                  .findViewById<View>(com.google.android.fhir.datacapture.R.id.submit_questionnaire)
                  ?.isEnabled = false
                fragmentView
                  .findViewById<View>(com.google.android.fhir.datacapture.R.id.cancel_questionnaire)
                  ?.isEnabled = false
                fragmentView
                  .findViewById<View>(
                    com.google.android.fhir.datacapture.R.id.pagination_previous_button,
                  )
                  ?.isEnabled = false
                fragmentView
                  .findViewById<View>(
                    com.google.android.fhir.datacapture.R.id.pagination_next_button,
                  )
                  ?.isEnabled = false
              }
            },
            200,
          )
        }
        is QuestionnaireFormUpdate.ShowQuestionnaireResponse -> {
          viewBinding.speechToTextContainer.visibility = View.GONE
          viewBinding.editFormActionButton.visibility = View.GONE
          try {
            renderQuestionnaire(
              questionnaire,
              it.newQuestionnaireResponse,
              launchContextResources,
            )

            if (questionnaireConfig.enableSpeechToText) {
              handler.postDelayed(
                { viewBinding.recordSpeechActionButton.visibility = View.VISIBLE },
                200,
              )
            }
          } catch (e: IllegalArgumentException) {
            Timber.e(e)
            showToast(e.message.toString())
          } finally {
            removeSpeechToTextFragment()
          }
        }
      }
    }
  }

  /**
   * Pages forward (pressing the form's own Next button) to the page holding [linkId], then scrolls
   * the question into view and highlights it briefly. Gives up quietly after a few seconds.
   */
  private suspend fun focusOnQuestion(questionnaire: Questionnaire, linkId: String) {
    fun findItem(items: List<Questionnaire.QuestionnaireItemComponent>): Questionnaire.QuestionnaireItemComponent? =
      items.firstNotNullOfOrNull { if (it.linkId == linkId) it else findItem(it.item) }
    val page = questionnaire.item.firstOrNull { it.linkId == linkId || findItem(it.item) != null } ?: return
    val target = findItem(questionnaire.item)?.text?.trim()?.take(40) ?: return

    fun texts(view: android.view.View, out: MutableList<android.widget.TextView>) {
      if (view is android.widget.TextView && view.visibility == android.view.View.VISIBLE) out.add(view)
      if (view is android.view.ViewGroup) for (i in 0 until view.childCount) texts(view.getChildAt(i), out)
    }

    repeat(MAX_FOCUS_STEPS) {
      kotlinx.coroutines.delay(FOCUS_STEP_DELAY_MS)
      val root = window.decorView
      val list =
        root.findViewById<androidx.recyclerview.widget.RecyclerView>(
          com.google.android.fhir.datacapture.R.id.questionnaire_edit_recycler_view,
        )
      if (list == null || list.childCount == 0) {
        Timber.d("Focus: form not ready yet")
        return@repeat
      }
      val visible = mutableListOf<android.widget.TextView>().also { texts(list, it) }
      val onPage = page == questionnaire.item.firstOrNull() || visible.any { it.text.toString().trim() == page.text?.trim() }
      if (onPage) {
        // Scroll down until the question shows, then highlight its card.
        repeat(MAX_FOCUS_STEPS) {
          val views = mutableListOf<android.widget.TextView>().also { texts(list, it) }
          val match = views.firstOrNull { it.text.toString().trim().startsWith(target) }
          if (match != null) {
            var card: android.view.View = match
            while (card.parent != null && card.parent !== list) card = card.parent as android.view.View
            list.smoothScrollBy(0, card.top - list.height / 4)
            card.setBackgroundColor(android.graphics.Color.parseColor("#FFF4E0"))
            kotlinx.coroutines.delay(2500)
            card.background = null
            return
          }
          if (!list.canScrollVertically(1)) return
          list.scrollBy(0, list.height / 2)
          kotlinx.coroutines.delay(150)
        }
        return
      }
      val next = root.findViewById<android.view.View>(com.google.android.fhir.datacapture.R.id.pagination_next_button)
      Timber.d("Focus: not on page \"${page.text}\" yet; next button=${next != null} shown=${next?.isShown}")
      if (next == null || !next.isShown || !next.isEnabled) return@repeat
      next.performClick()
    }
  }

  private suspend fun renderQuestionnaire(
    questionnaire: Questionnaire,
    questionnaireResponse: QuestionnaireResponse?,
    launchContextResources: List<Resource>,
  ) {
    val questionnaireFragment =
      getQuestionnaireFragmentBuilder(
          questionnaire,
          questionnaireConfig,
          questionnaireResponse,
          launchContextResources,
        )
        .build()

    viewBinding.clearAll.setOnClickListener { questionnaireFragment.clearAllAnswers() }
    supportFragmentManager.commit {
      setReorderingAllowed(true)
      replace(R.id.container, questionnaireFragment, QUESTIONNAIRE_FRAGMENT_TAG)
    }

    registerFragmentResultListener(questionnaire)
  }

  private suspend fun getQuestionnaireFragmentBuilder(
    questionnaire: Questionnaire,
    questionnaireConfig: QuestionnaireConfig,
    questionnaireResponse: QuestionnaireResponse?,
    launchContextResources: List<Resource>,
  ): QuestionnaireFragment.Builder =
    withContext(dispatcherProvider.default()) {
      QuestionnaireFragment.builder()
        .setQuestionnaire(questionnaire.json())
        .setCustomQuestionnaireItemViewHolderFactoryMatchersProvider(
          OPENSRP_ITEM_VIEWHOLDER_FACTORY_MATCHERS_PROVIDER,
        )
        .setSubmitButtonText(
          questionnaireConfig.saveButtonText ?: getString(R.string.submit_questionnaire),
        )
        .showAsterisk(questionnaireConfig.showRequiredTextAsterisk)
        .showRequiredText(questionnaireConfig.showRequiredText)
        .setIsReadOnly(questionnaireConfig.isSummary())
        .setShowSubmitAnywayButton(
          questionnaireConfig.showSubmitAnywayButton.toBooleanStrict() ||
            questionnaire.hasExtension(QuestionnaireViewModel.ALLOW_INCOMPLETE_SUBMISSION_URL),
        )
        .showReviewPageBeforeSubmit(intent.getBooleanExtra(QUESTIONNAIRE_SHOW_REVIEW_PAGE, false))
        .apply {
          if (questionnaireResponse != null) {
            questionnaireResponse
              .takeIf {
                if (it.status == QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS) {
                  // A draft is unfinished by definition: required answers may be missing. Only
                  // check that its answers still fit the form's structure.
                  runCatching {
                      QuestionnaireResponseValidator.checkQuestionnaireResponse(questionnaire, it)
                    }
                    .onFailure { error -> Timber.e(error, "Draft does not match the form") }
                    .isSuccess
                } else {
                  viewModel.validateQuestionnaireResponse(
                    questionnaire,
                    it,
                    this@QuestionnaireActivity,
                  )
                }
              }
              ?.let { setQuestionnaireResponse(questionnaireResponse.json()) }
              ?: withContext(dispatcherProvider.main()) {
                showToast(getString(R.string.error_populating_questionnaire))
              }
          }

          launchContextResources
            .associate { Pair(it.resourceType.name.lowercase(), it.json()) }
            .takeIf { it.isNotEmpty() }
            ?.let { setQuestionnaireLaunchContextMap(it) }
        }
    }

  private fun Resource.json(): String = this.encodeResourceToString(fhirParser)

  /**
   * Puts the response in a result intent. Android refuses results over about 1 MB, and photos or
   * audio make a response far bigger, so large responses are passed by id only (callers then load
   * it from the database, so it must have been saved).
   */
  private fun Intent.putQuestionnaireResponse(questionnaireResponse: QuestionnaireResponse) {
    putExtra(QUESTIONNAIRE_RESPONSE_ID, questionnaireResponse.logicalId)
    putExtra(QUESTIONNAIRE_RESPONSE_STATUS, questionnaireResponse.status?.toCode())
    if (questionnaireResponse.json().length < MAX_RESPONSE_EXTRA_CHARS) {
      putExtra(QUESTIONNAIRE_RESPONSE, questionnaireResponse as Serializable)
    }
  }

  private fun registerFragmentResultListener(questionnaire: Questionnaire) {
    supportFragmentManager.setFragmentResultListener(
      QuestionnaireFragment.SUBMIT_REQUEST_KEY,
      this,
    ) { _, _ ->
      if (questionnaireConfig.showSubmissionConfirmationDialog.toBooleanStrict()) {
        AlertDialogue.showAlert(
          context = this,
          alertIntent = AlertIntent.CONFIRM,
          message = getString(R.string.questionnaire_submission_confirmation_message),
          title = getString(R.string.questionnaire_submission_confirmation_title),
          confirmButton =
            AlertDialogButton(
              listener = { processSubmission(questionnaire) },
            ),
          neutralButton =
            AlertDialogButton(
              text = R.string.no,
              listener = { it.dismiss() },
            ),
        )
      } else {
        processSubmission(questionnaire)
      }
    }
  }

  private fun processSubmission(questionnaire: Questionnaire) {
    lifecycleScope.launch {
      val questionnaireResponse = retrieveQuestionnaireResponse()

      // Close questionnaire if opened in read only mode or if experimental
      if (questionnaireConfig.isReadOnly() || questionnaire.experimental) {
        finish()
        return@launch
      }
      if (questionnaireResponse != null) {
        viewModel.run {
          showProgressDialog(QuestionnaireProgressState.ExtractionInProgress(true))

          if (currentLocation != null) {
            questionnaireResponse.contained.add(
              ResourceUtils.createFhirLocationFromGpsLocation(gpsLocation = currentLocation!!),
            )
          }

          val questionnaireResponseInvalid = {
            Timber.e("Invalid questionnaire response")
            showToast(getString(R.string.questionnaire_response_invalid))
            showProgressDialog(QuestionnaireProgressState.ExtractionInProgress(false))
          }

          questionnaireResponse.addQuizDetails(questionnaire)
          questionnaireResponse.useDraftId() // submit replaces the (auto-)saved draft
          handleQuestionnaireSubmission(
            questionnaire = questionnaire,
            currentQuestionnaireResponse = questionnaireResponse,
            questionnaireConfig = questionnaireConfig,
            actionParameters = actionParameters,
            context = this@QuestionnaireActivity,
            onQuestionnaireResponseInvalid = questionnaireResponseInvalid,
          ) { idTypes, questionnaireResponse ->
            lifecycleScope.launch {
              // Responses without a subject are not saved by the extraction step; save them here
              // when asked to, so the caller only needs the id (see putQuestionnaireResponse).
              if (
                intent.getBooleanExtra(QUESTIONNAIRE_SAVE_SUBJECTLESS_RESPONSE, false) &&
                  questionnaireResponse.subject.reference.isNullOrEmpty()
              ) {
                saveQuestionnaireResponse(questionnaireResponse)
              }
              finishQuiz(questionnaire)
              // Dismiss progress indicator dialog, submit result then finish activity
              // TODO Ensure this dialog is dismissed even when an exception is encountered
              showProgressDialog(QuestionnaireProgressState.ExtractionInProgress(false))
              setResult(
                Activity.RESULT_OK,
                Intent().apply {
                  putQuestionnaireResponse(questionnaireResponse)
                  putExtra(QUESTIONNAIRE_SUBMISSION_EXTRACTED_RESOURCE_IDS, idTypes as Serializable)
                  putExtra(QUESTIONNAIRE_CONFIG, questionnaireConfig as Parcelable)
                  putExtra(ON_RESULT_TYPE, ActivityOnResultType.QUESTIONNAIRE.name)
                },
              )
              finish()
            }
          }
        }
      }
    }
  }

  private fun quizTimeLimitMinutes(questionnaire: Questionnaire): Int =
    (questionnaire.getExtensionByUrl(QUIZ_URL)?.value as? StringType)?.value?.let {
      runCatching { JSONObject(it).optInt("timeLimitMinutes", 0) }.getOrDefault(0)
    } ?: 0

  private fun quizKey(questionnaire: Questionnaire) = "start_${questionnaire.idElement.idPart}"

  /**
   * Starts the quiz clock. A new attempt starts it at zero; a saved draft (or the form reopened
   * after Android closed the app) keeps counting from the first start, so leaving does not pause it.
   */
  private fun startQuizTimer(questionnaire: Questionnaire) {
    if (questionnaire.getExtensionByUrl(QUIZ_URL) == null || questionnaireConfig.isReadOnly()) return
    val prefs = getSharedPreferences(QUIZ_PREFS, MODE_PRIVATE)
    val now = System.currentTimeMillis()
    val minutes = quizTimeLimitMinutes(questionnaire)
    val limitMs = minutes * 60_000L
    val saved = prefs.getLong(quizKey(questionnaire), 0L)
    quizStartedAt =
      if (autosavedDraftId != null && saved > 0 && now - saved < limitMs + 6 * 3_600_000L) saved else now
    prefs.edit().putLong(quizKey(questionnaire), quizStartedAt).apply()
    if (minutes <= 0) return
    val title = viewBinding.questionnaireTitle
    val baseTitle = questionnaireConfig.title.orEmpty()
    val normalColor = title.currentTextColor
    quizTimer?.cancel()
    quizTimer =
      object : CountDownTimer(maxOf(0L, quizStartedAt + limitMs - now), 1000L) {
          override fun onTick(left: Long) {
            val seconds = (left + 999) / 1000
            title.text = String.format("%s  ⏱ %d:%02d", baseTitle, seconds / 60, seconds % 60)
            title.setTextColor(if (left < 60_000L) Color.rgb(255, 138, 128) else normalColor)
          }

          override fun onFinish() {
            title.text = String.format("%s  ⏱ 0:00", baseTitle)
            onQuizTimeUp(questionnaire)
          }
        }
        .start()
  }

  /** Time is up: submit the answers given so far (required questions left empty are allowed). */
  private fun onQuizTimeUp(questionnaire: Questionnaire) {
    if (isFinishing || isDestroyed || quizTimedOut) return
    quizTimedOut = true
    Toast.makeText(this, getString(R.string.quiz_time_up), Toast.LENGTH_LONG).show()
    viewModel.forceIncompleteSubmission = true
    processSubmission(questionnaire)
  }

  /** Records how long the quiz took and whether time ran out (shown with the score). */
  private fun QuestionnaireResponse.addQuizDetails(questionnaire: Questionnaire) {
    if (questionnaire.getExtensionByUrl(QUIZ_URL) == null || quizStartedAt == 0L) return
    extension.removeAll { it.url == QUIZ_SECONDS_URL || it.url == QUIZ_TIMED_OUT_URL }
    addExtension(QUIZ_SECONDS_URL, IntegerType(((System.currentTimeMillis() - quizStartedAt) / 1000).toInt()))
    if (quizTimedOut) addExtension(QUIZ_TIMED_OUT_URL, BooleanType(true))
  }

  private fun finishQuiz(questionnaire: Questionnaire) {
    quizTimer?.cancel()
    if (questionnaire.getExtensionByUrl(QUIZ_URL) != null) {
      getSharedPreferences(QUIZ_PREFS, MODE_PRIVATE).edit().remove(quizKey(questionnaire)).apply()
    }
  }

  override fun onDestroy() {
    quizTimer?.cancel()
    super.onDestroy()
  }

  /** Saves the answers so far as an in-progress (draft) response and closes the form. */
  private fun saveDraftAndFinish() {
    lifecycleScope.launch {
      retrieveQuestionnaireResponse()?.let { questionnaireResponse ->
        questionnaireResponse.useDraftId()
        viewModel.saveDraftQuestionnaire(questionnaireResponse, questionnaireConfig)
        setResult(
          Activity.RESULT_OK,
          Intent().apply {
            putQuestionnaireResponse(questionnaireResponse)
            putExtra(QUESTIONNAIRE_CONFIG, questionnaireConfig as Parcelable)
            putExtra(ON_RESULT_TYPE, ActivityOnResultType.QUESTIONNAIRE.name)
            putExtra(QUESTIONNAIRE_SAVED_AS_DRAFT, true)
          },
        )
        finish()
      }
    }
  }

  private fun handleBackPress() {
    if (questionnaireConfig.isReadOnly() || questionnaireConfig.isSummary()) {
      finish()
    } else if (questionnaireConfig.saveDraft) {
      AlertDialogue.showThreeButtonAlert(
        context = this,
        message =
          org.smartregister.fhircore.engine.R.string
            .questionnaire_in_progress_alert_back_pressed_message,
        title = org.smartregister.fhircore.engine.R.string.questionnaire_alert_back_pressed_title,
        confirmButton =
          AlertDialogButton(
            listener = { saveDraftAndFinish() },
            text =
              org.smartregister.fhircore.engine.R.string
                .questionnaire_alert_back_pressed_save_draft_button_title,
            color = org.smartregister.fhircore.engine.R.color.colorPrimary,
          ),
        neutralButton =
          AlertDialogButton(
            listener = {},
            text =
              org.smartregister.fhircore.engine.R.string.questionnaire_alert_neutral_button_title,
          ),
        negativeButton =
          AlertDialogButton(
            listener = {
              // Discard: remove the auto-save of a new form (a draft that was opened is kept).
              val autosaved = autosavedDraftId
              if (autosaved != null && autosaved != intent.getStringExtra(QUESTIONNAIRE_DRAFT_RESPONSE_ID)) {
                lifecycleScope.launch {
                  viewModel.deleteDraft(autosaved)
                  finish()
                }
              } else {
                finish()
              }
            },
            text =
              org.smartregister.fhircore.engine.R.string.questionnaire_alert_negative_button_title,
            color = org.smartregister.fhircore.engine.R.color.colorPrimary,
          ),
      )
    } else {
      AlertDialogue.showConfirmAlert(
        context = this,
        message =
          org.smartregister.fhircore.engine.R.string.questionnaire_alert_back_pressed_message,
        title = org.smartregister.fhircore.engine.R.string.questionnaire_alert_back_pressed_title,
        confirmButtonListener = { finish() },
        confirmButtonText =
          org.smartregister.fhircore.engine.R.string.questionnaire_alert_back_pressed_button_title,
      )
    }
  }

  private suspend fun retrieveQuestionnaireResponse(): QuestionnaireResponse? =
    (supportFragmentManager.findFragmentByTag(QUESTIONNAIRE_FRAGMENT_TAG) as QuestionnaireFragment?)
      ?.getQuestionnaireResponse()

  companion object {

    const val QUESTIONNAIRE_FRAGMENT_TAG = "questionnaireFragment"
    const val SPEECH_TO_TEXT_FRAGMENT_TAG = "speechToTextFragment"
    const val QUESTIONNAIRE_CONFIG = "questionnaireConfig"
    const val QUESTIONNAIRE_SUBMISSION_EXTRACTED_RESOURCE_IDS = "questionnaireExtractedResourceIds"
    const val QUESTIONNAIRE_RESPONSE = "questionnaireResponse"
    const val QUESTIONNAIRE_ACTION_PARAMETERS = "questionnaireActionParameters"

    /** Optional: id of an in-progress QuestionnaireResponse (draft) to continue. */
    const val QUESTIONNAIRE_DRAFT_RESPONSE_ID = "questionnaireDraftResponseId"

    /** Optional: show a read-only review of all answers before the final submit. */
    const val QUESTIONNAIRE_SHOW_REVIEW_PAGE = "questionnaireShowReviewPage"

    private const val AUTOSAVED_DRAFT_ID = "autosavedDraftId"
    const val QUIZ_URL = "urn:ahip:quiz"
    const val QUIZ_SECONDS_URL = "urn:ahip:quiz-seconds"
    const val QUIZ_TIMED_OUT_URL = "urn:ahip:quiz-timed-out"
    private const val QUIZ_PREFS = "ahip_quiz"

    /** Optional: open the form at this question (used by "Edit" on a draft). */
    const val QUESTIONNAIRE_FOCUS_LINK_ID = "questionnaireFocusLinkId"
    private const val MAX_FOCUS_STEPS = 25
    private const val FOCUS_STEP_DELAY_MS = 450L

    /** Optional: tags ("system|code") to put on the response, e.g. who collected it. */
    const val QUESTIONNAIRE_RESPONSE_TAGS = "questionnaireResponseTags"

    /** Optional: save a response that has no subject on submit (it is otherwise not saved). */
    const val QUESTIONNAIRE_SAVE_SUBJECTLESS_RESPONSE = "questionnaireSaveSubjectlessResponse"

    /** Result extras: response id and status, always set (unlike [QUESTIONNAIRE_RESPONSE]). */
    const val QUESTIONNAIRE_RESPONSE_ID = "questionnaireResponseId"
    const val QUESTIONNAIRE_RESPONSE_STATUS = "questionnaireResponseStatus"

    /** Larger responses are only passed by id: Binder transactions are limited to about 1 MB. */
    private const val MAX_RESPONSE_EXTRA_CHARS = 300_000

    /** Result extra: true when the form was closed with "Save draft" rather than submitted. */
    const val QUESTIONNAIRE_SAVED_AS_DRAFT = "questionnaireSavedAsDraft"

    fun intentBundle(
      questionnaireConfig: QuestionnaireConfig,
      actionParams: List<ActionParameter>,
    ): Bundle =
      bundleOf(
        Pair(QUESTIONNAIRE_CONFIG, questionnaireConfig),
        Pair(QUESTIONNAIRE_ACTION_PARAMETERS, actionParams),
      )
  }
}
