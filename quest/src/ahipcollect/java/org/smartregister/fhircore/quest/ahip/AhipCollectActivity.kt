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

package org.smartregister.fhircore.quest.ahip

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.configuration.QuestionnaireConfig
import org.smartregister.fhircore.engine.domain.model.ActionParameter
import org.smartregister.fhircore.engine.domain.model.ActionParameterType
import org.smartregister.fhircore.engine.sync.SyncListenerManager
import org.smartregister.fhircore.engine.ui.base.BaseMultiLanguageActivity
import org.smartregister.fhircore.engine.util.extension.applyWindowInsetListener
import org.smartregister.fhircore.engine.util.extension.launchActivityWithNoBackStackHistory
import org.smartregister.fhircore.engine.util.extension.showToast
import org.smartregister.fhircore.quest.R
import org.smartregister.fhircore.quest.ui.login.LoginActivity
import org.smartregister.fhircore.quest.ui.questionnaire.QuestionnaireActivity

/**
 * Home screen of the AHIP Collect flavor, shown after login in place of AppMainActivity:
 * Study -> Assessment -> Tools -> Tool -> questionnaire -> Submission & sync.
 */
@AndroidEntryPoint
class AhipCollectActivity : BaseMultiLanguageActivity() {

  @Inject lateinit var syncListenerManager: SyncListenerManager


  private val viewModel: AhipCollectViewModel by viewModels()

  private var navController: NavHostController? = null

  /** Tool whose questionnaire is open; kept across activity recreation. */
  private var openToolNumber: Int? = null

  private val questionnaireLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      val tool = AhipCatalog.tools.firstOrNull { it.number == openToolNumber }
      openToolNumber = null
      if (result.resultCode == Activity.RESULT_OK && tool != null) {
        // Responses with photos or audio are too big to pass back, so only the id comes back; the
        // form screen has already saved the response (QUESTIONNAIRE_SAVE_SUBJECTLESS_RESPONSE).
        val data = result.data
        val responseId = data?.getStringExtra(QuestionnaireActivity.QUESTIONNAIRE_RESPONSE_ID)
        val status = data?.getStringExtra(QuestionnaireActivity.QUESTIONNAIRE_RESPONSE_STATUS)
        if (data?.getBooleanExtra(QuestionnaireActivity.QUESTIONNAIRE_SAVED_AS_DRAFT, false) == true) {
          showToast(getString(R.string.ahip_draft_saved))
          viewModel.loadDashboard()
        }
        // A draft saved on exit also returns RESULT_OK; only a completed form is a submission.
        if (
          !responseId.isNullOrEmpty() &&
            status == QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED.toCode()
        ) {
          viewModel.onToolSubmitted(tool, responseId)
          navController?.navigate(Routes.submission(tool.number)) {
            popUpTo(Routes.TOOL) { inclusive = true }
          }
        }
      }
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    AhipCatalog.loadRegistry(this)
    applyWindowInsetListener()
    openToolNumber = savedInstanceState?.getInt(OPEN_TOOL_NUMBER)?.takeIf { it > 0 }
    syncListenerManager.registerSyncListener(viewModel, lifecycle)
    if (savedInstanceState == null) viewModel.schedulePeriodicSync()

    setContent { AhipTheme { AhipNavHost() } }
  }

  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    openToolNumber?.let { outState.putInt(OPEN_TOOL_NUMBER, it) }
  }

  @Composable
  private fun AhipNavHost() {
    val controller = rememberNavController()
    navController = controller
    val selectedStudyId by viewModel.selectedStudyId.collectAsState()
    val selectedAssessmentId by viewModel.selectedAssessmentId.collectAsState()
    val toolDetails by viewModel.toolDetails.collectAsState()
    val submission by viewModel.submission.collectAsState()
    val syncStatus by viewModel.syncStatus.collectAsState()
    val dashboard by viewModel.dashboard.collectAsState()
    val drafts by viewModel.drafts.collectAsState()
    var showShareData by remember { mutableStateOf(false) }
    val aiMessages by viewModel.aiMessages.collectAsState()
    val aiBusy by viewModel.aiBusy.collectAsState()
    val shareState by viewModel.shareState.collectAsState()
    if (showShareData) {
      AhipShareDataDialog(
        state = shareState,
        onCreate = viewModel::createApiKey,
        onDismiss = {
          showShareData = false
          viewModel.resetShareState()
        },
        onShare = { text ->
          startActivity(
            android.content.Intent.createChooser(
              android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(android.content.Intent.EXTRA_SUBJECT, getString(R.string.ahip_share_subject))
                .putExtra(android.content.Intent.EXTRA_TEXT, text),
              getString(R.string.ahip_share_data),
            ),
          )
        },
      )
    }
    val serverMoved by viewModel.serverMoved.collectAsState()
    val accountRevoked by viewModel.accountRevoked.collectAsState()
    LaunchedEffect(accountRevoked) {
      accountRevoked?.let { reason ->
        val message =
          if (reason == "signed-out") R.string.ahip_signin_reset else R.string.ahip_account_revoked
        showToast(getString(message))
        launchActivityWithNoBackStackHistory<LoginActivity>()
      }
    }
    LaunchedEffect(serverMoved) {
      // The server was found at a new address: restart so sync uses it.
      if (serverMoved) AhipServerDiscovery.restartApp(this@AhipCollectActivity)
    }

    NavHost(navController = controller, startDestination = Routes.STUDY) {
      composable(Routes.STUDY) {
        AhipStudyScreen(
          selectedStudyId = selectedStudyId,
          onSelectStudy = viewModel::selectStudy,
          onContinue = { controller.navigate(Routes.ASSESSMENT) },
          menu = {
            IconButton(
              onClick = {
                viewModel.loadDashboard()
                controller.navigate(Routes.DASHBOARD)
              },
            ) {
              Icon(
                Icons.Outlined.Insights,
                contentDescription = stringResource(R.string.ahip_dashboard_open),
              )
            }
            AhipOverflowMenu(
              username = viewModel.displayName,
              serverHost = viewModel.serverHost,
              onSync = viewModel::syncNow,
              onLogout = ::logout,
              onShareData = { showShareData = true },
            )
          },
        )
      }
      composable(Routes.DASHBOARD) {
        AhipDashboardScreen(
          dashboard = dashboard,
          username = viewModel.displayName,
          syncStatus = syncStatus,
          onBack = { controller.popBackStack() },
          onSync = viewModel::syncNow,
          aiMessages = aiMessages,
          aiBusy = aiBusy,
          onAskAi = viewModel::askAi,
          onClearAi = viewModel::clearAi,
        )
      }
      composable(Routes.ASSESSMENT) {
        AhipAssessmentScreen(
          studyTitle = AhipCatalog.studies.first { it.id == selectedStudyId }.title,
          selectedAssessmentId = selectedAssessmentId,
          onSelectAssessment = viewModel::selectAssessment,
          onBack = { controller.popBackStack() },
          onContinue = { controller.navigate(Routes.TOOLS) },
        )
      }
      composable(Routes.TOOLS) {
        LaunchedEffect(Unit) { viewModel.loadDashboard() } // refresh the drafts list
        AhipToolsScreen(
          assessment = AhipCatalog.assessments.first { it.id == selectedAssessmentId },
          toolDetails = toolDetails,
          drafts = drafts,
          onBack = { controller.popBackStack() },
          onOpenTool = { tool -> controller.navigate(Routes.tool(tool.number)) },
          onContinueDraft = { draft ->
            launchQuestionnaire(
              draft.tool,
              selectedStudyId,
              draft.assessmentId ?: selectedAssessmentId,
              draftId = draft.responseId,
            )
          },
          onDeleteDraft = viewModel::deleteDraft,
          onReviewDraft = { draft -> controller.navigate(Routes.draft(draft.responseId)) },
        )
      }
      composable(Routes.DRAFT, arguments = listOf(navArgument(Routes.ARG_DRAFT) { type = NavType.StringType })) { entry ->
        val draft = drafts.firstOrNull { it.responseId == entry.arguments?.getString(Routes.ARG_DRAFT) }
        if (draft == null) {
          LaunchedEffect(Unit) { controller.popBackStack() }
          return@composable
        }
        val lines by viewModel.draftLines.collectAsState()
        LaunchedEffect(draft.responseId, draft.savedAt) { viewModel.loadDraftLines(draft) }
        AhipDraftReviewScreen(
          draft = draft,
          lines = lines,
          onBack = { controller.popBackStack() },
          onEdit = { line ->
            launchQuestionnaire(draft.tool, selectedStudyId, draft.assessmentId ?: selectedAssessmentId, draftId = draft.responseId, focusLinkId = line.linkId)
          },
          onContinue = {
            launchQuestionnaire(draft.tool, selectedStudyId, draft.assessmentId ?: selectedAssessmentId, draftId = draft.responseId)
          },
        )
      }
      composable(
        Routes.TOOL,
        arguments = listOf(navArgument(Routes.ARG_TOOL) { type = NavType.IntType }),
      ) { entry ->
        val tool = entry.toolArgument() ?: return@composable
        AhipToolDetailScreen(
          tool = tool,
          details = toolDetails[tool.questionnaireId],
          onBack = { controller.popBackStack() },
          onStart = { launchQuestionnaire(tool, selectedStudyId, selectedAssessmentId) },
        )
      }
      composable(
        Routes.SUBMISSION,
        arguments = listOf(navArgument(Routes.ARG_TOOL) { type = NavType.IntType }),
      ) { entry ->
        val tool = entry.toolArgument() ?: return@composable
        AhipSubmissionScreen(
          tool = tool,
          submission = submission,
          syncStatus = syncStatus,
          onSync = viewModel::syncNow,
          onSaveAndExit = { controller.popBackStack(Routes.TOOLS, inclusive = false) },
        )
      }
    }
  }

  private fun androidx.navigation.NavBackStackEntry.toolArgument(): AhipTool? {
    val number = arguments?.getInt(Routes.ARG_TOOL)
    return AhipCatalog.tools.firstOrNull { it.number == number }
  }

  /**
   * Opens a tool form, or continues [draftId]. Collectors can "Save draft" at any time (top bar or
   * back), and see every answer on a review page before the final Submit. Works offline.
   */
  private fun launchQuestionnaire(
    tool: AhipTool,
    studyId: String,
    assessmentId: String,
    draftId: String? = null,
    focusLinkId: String? = null,
  ) {
    val questionnaireConfig =
      QuestionnaireConfig(
        id = tool.questionnaireId,
        title = getString(R.string.ahip_tool_title, tool.number, tool.title),
        saveButtonText = getString(R.string.ahip_submit),
        saveDraft = true,
      )
    val actionParameters =
      listOf(
        prepopulate(AhipCatalog.STUDY_LINK_ID, studyId),
        prepopulate(AhipCatalog.ASSESSMENT_LINK_ID, assessmentId),
      )
    openToolNumber = tool.number
    questionnaireLauncher.launch(
      Intent(this, QuestionnaireActivity::class.java)
        .putExtras(QuestionnaireActivity.intentBundle(questionnaireConfig, actionParameters))
        .putExtra(QuestionnaireActivity.QUESTIONNAIRE_SHOW_REVIEW_PAGE, true)
        .putExtra(QuestionnaireActivity.QUESTIONNAIRE_SAVE_SUBJECTLESS_RESPONSE, true)
        .apply {
          // Who collected it: set before the first save (the server ignores tags added later).
          viewModel.username?.let {
            putStringArrayListExtra(
              QuestionnaireActivity.QUESTIONNAIRE_RESPONSE_TAGS,
              arrayListOf("${AhipCatalog.COLLECTOR_TAG_SYSTEM}|$it"),
            )
          }
        }
        .apply {
          draftId?.let { putExtra(QuestionnaireActivity.QUESTIONNAIRE_DRAFT_RESPONSE_ID, it) }
          focusLinkId?.let { putExtra(QuestionnaireActivity.QUESTIONNAIRE_FOCUS_LINK_ID, it) }
        },
    )
  }

  private fun prepopulate(linkId: String, value: String) =
    ActionParameter(
      key = linkId,
      paramType = ActionParameterType.PREPOPULATE,
      dataType = Enumerations.DataType.STRING,
      value = value,
      linkId = linkId,
    )

  /**
   * AHIP accounts live in AHIP Manage, not the OAuth server, so signing out just forgets the PIN
   * (the saved password still allows offline sign-in).
   */
  private fun logout() {
    viewModel.signOut()
    launchActivityWithNoBackStackHistory<LoginActivity>()
  }

  private object Routes {
    const val ARG_TOOL = "toolNumber"
    const val STUDY = "study"
    const val DASHBOARD = "dashboard"
    const val ASSESSMENT = "assessment"
    const val TOOLS = "tools"
    const val TOOL = "tool/{$ARG_TOOL}"
    const val SUBMISSION = "submission/{$ARG_TOOL}"
    const val ARG_DRAFT = "draftId"
    const val DRAFT = "draft/{$ARG_DRAFT}"

    fun draft(id: String) = "draft/$id"

    fun tool(number: Int) = "tool/$number"

    fun submission(number: Int) = "submission/$number"
  }

  companion object {
    private const val OPEN_TOOL_NUMBER = "openToolNumber"
  }
}
