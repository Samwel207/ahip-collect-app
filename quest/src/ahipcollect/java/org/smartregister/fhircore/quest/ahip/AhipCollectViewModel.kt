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

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.fhir.sync.CurrentSyncJobStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import com.google.android.fhir.datacapture.extensions.logicalId
import com.google.android.fhir.search.search
import java.util.Calendar
import java.util.Date
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.hl7.fhir.r4.model.Coding
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.QuestionnaireResponse
import org.smartregister.fhircore.engine.configuration.ConfigType
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.engine.configuration.app.ApplicationConfiguration
import org.smartregister.fhircore.engine.data.local.DefaultRepository
import org.smartregister.fhircore.engine.sync.OnSyncListener
import org.smartregister.fhircore.engine.sync.SyncBroadcaster
import org.smartregister.fhircore.engine.sync.SyncState
import org.smartregister.fhircore.engine.util.SecureSharedPreference
import timber.log.Timber

/** Question count and section titles read from a tool's Questionnaire. */
data class AhipToolDetails(val questionCount: Int, val sections: List<String>)

/** Result of the last completed tool, shown on the submission screen. */
data class AhipSubmission(
  val toolNumber: Int,
  val answeredCount: Int,
  val saved: Boolean,
  val photoCount: Int = 0,
  val gpsCaptured: Boolean = false,
  val audioCount: Int = 0,
  val quiz: AhipQuizScore? = null,
)

/** One question of a draft on the draft review screen. */
data class AhipDraftLine(
  val section: String,
  val linkId: String,
  val label: String,
  val answer: String?,
  val required: Boolean,
)

/** A tool form saved part-way ("Save draft"), to review and finish later. */
data class AhipDraft(
  val responseId: String,
  val tool: AhipTool,
  val site: String?,
  val assessmentId: String?,
  val savedAt: Date?,
  val answeredCount: Int,
)

enum class AhipSyncStatus {
  IDLE,
  RUNNING,
  SUCCEEDED,
  FAILED,

  /** No AHIP server answered on the current Wi-Fi. */
  NO_SERVER,
}

@HiltViewModel
class AhipCollectViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val defaultRepository: DefaultRepository,
  private val syncBroadcaster: SyncBroadcaster,
  private val configurationRegistry: ConfigurationRegistry,
  private val secureSharedPreference: SecureSharedPreference,
) : ViewModel(), OnSyncListener {

  private val _selectedStudyId = MutableStateFlow(AhipCatalog.studies.first { it.enabled }.id)
  val selectedStudyId: StateFlow<String> = _selectedStudyId.asStateFlow()

  private val _selectedAssessmentId = MutableStateFlow(AhipCatalog.assessments.first().id)
  val selectedAssessmentId: StateFlow<String> = _selectedAssessmentId.asStateFlow()

  private val _toolDetails = MutableStateFlow<Map<String, AhipToolDetails>>(emptyMap())
  val toolDetails: StateFlow<Map<String, AhipToolDetails>> = _toolDetails.asStateFlow()

  /** AI assistant chat about the collector's own data (answers come from AHIP Manage / Gemini). */
  private val _aiMessages = MutableStateFlow<List<AhipAiMessage>>(emptyList())
  val aiMessages: StateFlow<List<AhipAiMessage>> = _aiMessages.asStateFlow()
  private val _aiBusy = MutableStateFlow(false)
  val aiBusy: StateFlow<Boolean> = _aiBusy.asStateFlow()

  fun clearAi() {
    _aiMessages.value = emptyList()
  }

  /** Asks the AI about this collector's own submitted forms (needs the office Wi-Fi). */
  fun askAi(question: String) {
    val user = username ?: return
    val text = question.trim()
    if (text.isEmpty() || _aiBusy.value) return
    val history = _aiMessages.value.filter { !it.error }.takeLast(8)
    _aiMessages.value = _aiMessages.value + AhipAiMessage(fromUser = true, text = text)
    _aiBusy.value = true
    viewModelScope.launch {
      val body =
        org.json.JSONObject()
          .put("username", user)
          .put("deviceId", AhipAccounts.deviceId(context))
          .put("question", text)
          .put(
            "history",
            org.json.JSONArray(
              history.map {
                org.json.JSONObject().put("role", if (it.fromUser) "user" else "model").put("text", it.text)
              },
            ),
          )
      val result = AhipAccounts.post(context, "/api/mobile/ai", body, readTimeoutMs = 120_000)
      val reply =
        when {
          result == null ->
            AhipAiMessage(false, context.getString(org.smartregister.fhircore.quest.R.string.ahip_ai_offline), error = true)
          result.first == 200 -> AhipAiMessage(false, result.second.optString("answer").cleanMarkdown())
          else -> AhipAiMessage(false, result.second.optString("error").ifBlank { "Error ${result.first}" }, error = true)
        }
      _aiMessages.value = _aiMessages.value + reply
      _aiBusy.value = false
    }
  }

  /** Plain text for the phone: no markdown symbols, bullets as "•". */
  private fun String.cleanMarkdown(): String =
    lines()
      .joinToString("\n") { line ->
        line
          .replace(Regex("^\\s*#{1,6}\\s*"), "")
          .replace(Regex("^(\\s*)[-*]\\s+"), "$1• ")
          .replace("**", "")
          .replace(Regex("(?<!\\w)\\*(\\S[^*]*)\\*(?!\\w)"), "$1")
      }
      .trim()

  /** "Share my data": the collector's personal API key once created. */
  private val _shareState = MutableStateFlow<AhipShareState>(AhipShareState.Idle)
  val shareState: StateFlow<AhipShareState> = _shareState.asStateFlow()

  fun resetShareState() {
    _shareState.value = AhipShareState.Idle
  }

  /** Creates a personal API key in AHIP Manage (password needed; the old key stops working). */
  fun createApiKey(password: String) {
    val user = username ?: return
    _shareState.value = AhipShareState.Loading
    viewModelScope.launch {
      val result =
        AhipAccounts.post(
          context,
          "/api/mobile/api-key",
          org.json.JSONObject().put("username", user).put("password", password),
        )
      _shareState.value =
        when {
          result == null -> AhipShareState.Error(context.getString(org.smartregister.fhircore.quest.R.string.ahip_share_offline))
          result.first == 200 ->
            AhipShareState.Ready(
              apiKey = result.second.optString("apiKey"),
              jsonUrl = result.second.optString("jsonUrl"),
              csvUrl = result.second.optString("csvUrl"),
            )
          else ->
            AhipShareState.Error(
              result.second.optString("error").ifBlank { "Error ${result.first}" },
            )
        }
    }
  }

  private val _submission = MutableStateFlow<AhipSubmission?>(null)
  val submission: StateFlow<AhipSubmission?> = _submission.asStateFlow()

  private val _syncStatus = MutableStateFlow(AhipSyncStatus.IDLE)
  val syncStatus: StateFlow<AhipSyncStatus> = _syncStatus.asStateFlow()

  /** This collector's unfinished forms on this phone, newest first. */
  private val _drafts = MutableStateFlow<List<AhipDraft>>(emptyList())
  val drafts: StateFlow<List<AhipDraft>> = _drafts.asStateFlow()

  /** Questions and answers of the draft being reviewed (null while loading). */
  private val _draftLines = MutableStateFlow<List<AhipDraftLine>?>(null)
  val draftLines: StateFlow<List<AhipDraftLine>?> = _draftLines.asStateFlow()

  /** Lists every question of a draft with its current answer, section by section. */
  fun loadDraftLines(draft: AhipDraft) {
    _draftLines.value = null
    viewModelScope.launch {
      _draftLines.value =
        runCatching {
            val form = defaultRepository.loadResourceFromCache<Questionnaire>(draft.tool.questionnaireId)!!
            val response = defaultRepository.loadResource<QuestionnaireResponse>(draft.responseId)!!
            val answers = mutableMapOf<String, List<QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent>>()
            fun collect(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>) {
              items.forEach { item ->
                if (item.hasAnswer()) answers[item.linkId] = item.answer
                collect(item.item)
                item.answer.forEach { collect(it.item) }
              }
            }
            collect(response.item)
            val lines = mutableListOf<AhipDraftLine>()
            fun walk(items: List<Questionnaire.QuestionnaireItemComponent>, section: String) {
              items.forEach { item ->
                val hidden = item.extension.any { it.url.endsWith("questionnaire-hidden") }
                val helper = item.linkId.endsWith("_hint") || item.linkId.endsWith("_other") && item.linkId !in answers
                when {
                  hidden || helper -> Unit
                  item.type == Questionnaire.QuestionnaireItemType.DISPLAY -> Unit
                  item.type == Questionnaire.QuestionnaireItemType.GROUP ->
                    walk(item.item, if (item.extension.any { (it.value as? org.hl7.fhir.r4.model.CodeableConcept)?.codingFirstRep?.code == "page" }) item.text else section)
                  else ->
                    lines.add(
                      AhipDraftLine(
                        section = section,
                        linkId = item.linkId,
                        label = listOfNotNull(item.prefix, item.text).joinToString(" "),
                        answer = answers[item.linkId]?.joinToString(", ") { answerText(it) }?.takeIf { it.isNotBlank() },
                        required = item.required,
                      ),
                    )
                }
              }
            }
            walk(form.item, "")
            lines.toList()
          }
          .onFailure { Timber.e(it, "Could not open draft ${draft.responseId}") }
          .getOrDefault(emptyList())
    }
  }

  private fun answerText(answer: QuestionnaireResponse.QuestionnaireResponseItemAnswerComponent): String {
    val value = answer.value
    return when {
      answer.hasValueAttachment() ->
        if (answer.valueAttachment.contentType.orEmpty().startsWith("audio")) "🎙 Recording saved" else "📷 Photo saved"
      value is Coding -> value.display ?: value.code
      else -> value?.primitiveValue().orEmpty()
    }
  }

  /** Deletes a draft the collector no longer needs (also from the server once synced). */
  fun deleteDraft(draft: AhipDraft) {
    viewModelScope.launch {
      runCatching {
          defaultRepository.fhirEngine.delete(
            org.hl7.fhir.r4.model.ResourceType.QuestionnaireResponse,
            draft.responseId,
          )
        }
        .onFailure { Timber.e(it, "Could not delete draft ${draft.responseId}") }
      loadDashboard()
    }
  }

  /** Null until first loaded. */
  private val _dashboard = MutableStateFlow<AhipDashboard?>(null)
  val dashboard: StateFlow<AhipDashboard?> = _dashboard.asStateFlow()

  val username: String? = secureSharedPreference.retrieveSessionUsername()

  /** Full name from AHIP Manage, shown instead of the username where available. */
  val displayName: String?
    get() = AhipAccounts.displayName(context, username) ?: username

  /**
   * Set when AHIP Manage says this phone must sign out: the server's reason ("signed-out" when an
   * administrator used "Reset app sign-in", "inactive", "device-blocked", ...).
   */
  private val _accountRevoked = MutableStateFlow<String?>(null)
  val accountRevoked: StateFlow<String?> = _accountRevoked.asStateFlow()

  init {
    AhipCatalog.loadRegistry(context)
    viewModelScope.launch {
      AhipForms.installMissingBundled(context, defaultRepository.fhirEngine, configurationRegistry)
      loadToolDetails()
      refreshForms()
    }
    checkAccount()
  }

  /**
   * Gets the latest T1–T6 forms published from AHIP Manage (Forms). Offline, the forms already on
   * the phone are used.
   */
  private suspend fun refreshForms() {
    val forms = AhipForms.refreshFromServer(context, configurationRegistry) ?: return
    forms.forEach { defaultRepository.contentCache.saveResource(it) }
    if (forms.isNotEmpty()) loadToolDetails()
  }

  /**
   * Signs out accounts that AHIP Manage no longer allows (deactivated, blocked, or sign-in reset
   * by an administrator); forgets the PIN and saved password so only a full sign-in works.
   * Offline, nothing changes.
   */
  private fun checkAccount() {
    val current = username ?: return
    viewModelScope.launch {
      val status = AhipAccounts.accountStatus(context, current) ?: return@launch
      if (!status.active) {
        secureSharedPreference.deleteSessionPin()
        secureSharedPreference.deleteCredentials()
        _accountRevoked.value = status.reason ?: "inactive"
      }
    }
  }

  /** Sign out: forget the PIN so the next start asks for username and password. */
  fun signOut() {
    secureSharedPreference.deleteSessionPin()
  }

  fun selectStudy(studyId: String) {
    _selectedStudyId.value = studyId
  }

  fun selectAssessment(assessmentId: String) {
    if (AhipCatalog.assessments.none { it.id == assessmentId && it.available }) return
    _selectedAssessmentId.value = assessmentId
  }

  fun loadToolDetails() {
    viewModelScope.launch {
      _toolDetails.value =
        AhipCatalog.tools
          .mapNotNull { tool ->
            val questionnaire =
              try {
                defaultRepository.loadResource<Questionnaire>(tool.questionnaireId)
              } catch (exception: Exception) {
                Timber.e(exception, "Could not load Questionnaire ${tool.questionnaireId}")
                null
              }
            questionnaire?.let { tool.questionnaireId to it.toToolDetails() }
          }
          .toMap()
    }
  }

  /**
   * Saves a completed tool response so it syncs to the server. QuestionnaireActivity only saves
   * responses that have a subject, and AHIP responses have none, so they are saved here.
   */
  fun onToolSubmitted(tool: AhipTool, responseId: String) {
    _syncStatus.value = AhipSyncStatus.IDLE
    _submission.value = null
    viewModelScope.launch {
      // Saved by the form screen; load it and add the data collector tag.
      val response =
        runCatching { defaultRepository.loadResource<QuestionnaireResponse>(responseId) }
          .onFailure { Timber.e(it, "AHIP tool ${tool.number} response $responseId not found") }
          .getOrNull()
      val saved =
        response != null &&
          runCatching {
              val collector = username
              if (
                collector != null &&
                  response.meta.tag.none { it.system == AhipCatalog.COLLECTOR_TAG_SYSTEM }
              ) {
                response.meta.addTag(Coding(AhipCatalog.COLLECTOR_TAG_SYSTEM, collector, collector))
                defaultRepository.addOrUpdate(true, response)
              }
            }
            .onFailure { Timber.e(it, "Failed to tag AHIP tool ${tool.number} response") }
            .isSuccess
      _submission.value =
        AhipSubmission(
          toolNumber = tool.number,
          answeredCount = response?.item?.countAnswered() ?: 0,
          saved = saved,
          photoCount =
            response?.item?.countAnswered { it.linkId in AhipCatalog.PHOTO_LINK_IDS } ?: 0,
          gpsCaptured =
            (response?.item?.countAnswered { it.linkId == AhipCatalog.GPS_LATITUDE_LINK_ID } ?: 0) >
              0,
          audioCount = response?.item?.countAnswered { AhipCatalog.isAudio(it.linkId) } ?: 0,
          quiz =
            response?.let { r ->
              runCatching {
                  AhipQuiz.score(defaultRepository.loadResource<Questionnaire>(tool.questionnaireId)!!, r)
                }
                .onFailure { Timber.w(it, "Could not score quiz ${tool.questionnaireId}") }
                .getOrNull()
            },
        )
      loadDashboard()
    }
  }

  /** Builds the data collector's summary from completed AHIP responses stored on this device. */
  fun loadDashboard() {
    viewModelScope.launch {
      try {
        val fhirEngine = defaultRepository.fhirEngine
        val toolsByRef = AhipCatalog.tools.associateBy { "Questionnaire/${it.questionnaireId}" }
        val mine =
          fhirEngine
            .search<QuestionnaireResponse> {}
            .map { it.resource }
            .filter { toolsByRef.containsKey(it.questionnaire) && it.isByCollector(username) }
        val responses =
          mine
            .filter { it.status == QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED }
            .sortedByDescending { it.authored }
        _drafts.value =
          mine
            .filter { it.status == QuestionnaireResponse.QuestionnaireResponseStatus.INPROGRESS }
            // Auto-saves of a form that was only opened (nothing filled in but the default
            // "how will you collect" choice) are not real drafts.
            .filter { draft ->
              draft.item.countAnswered { it.linkId !in EMPTY_DRAFT_LINK_IDS } > 0 ||
                draft.item.countAnswered { AhipCatalog.isAudio(it.linkId) || it.linkId in AhipCatalog.PHOTO_LINK_IDS } > 0
            }
            .sortedByDescending { it.meta.lastUpdated }
            .map { draft ->
              AhipDraft(
                responseId = draft.logicalId,
                tool = toolsByRef.getValue(draft.questionnaire),
                site = draft.siteName(),
                assessmentId = draft.answerOf(AhipCatalog.ASSESSMENT_LINK_ID),
                savedAt = draft.meta.lastUpdated,
                answeredCount = draft.item.countAnswered(),
              )
            }
        val unsyncedIds =
          fhirEngine
            .getUnsyncedLocalChanges()
            .filter { it.resourceType == "QuestionnaireResponse" }
            .map { it.resourceId }
            .toSet()

        val startOfToday =
          Calendar.getInstance()
            .apply {
              set(Calendar.HOUR_OF_DAY, 0)
              set(Calendar.MINUTE, 0)
              set(Calendar.SECOND, 0)
              set(Calendar.MILLISECOND, 0)
            }
            .time
        val weekAgo = Date(startOfToday.time - 6 * DAY_MILLIS)

        _dashboard.value =
          AhipDashboard(
            total = responses.size,
            today = responses.count { it.authored?.before(startOfToday) == false },
            thisWeek = responses.count { it.authored?.before(weekAgo) == false },
            pendingSync = responses.count { it.logicalId in unsyncedIds },
            perTool =
              AhipCatalog.tools.map { tool ->
                val forTool = responses.filter { toolsByRef[it.questionnaire] == tool }
                AhipToolCount(tool, forTool.size, forTool.firstOrNull()?.authored)
              },
            recent =
              responses.take(RECENT_LIMIT).map { response ->
                AhipRecentSubmission(
                  tool = toolsByRef.getValue(response.questionnaire),
                  site = response.siteName(),
                  assessment =
                    AhipCatalog.assessments
                      .firstOrNull { it.id == response.answerOf(AhipCatalog.ASSESSMENT_LINK_ID) }
                      ?.let { "${it.title} (${it.period})" },
                  authored = response.authored,
                  synced = response.logicalId !in unsyncedIds,
                )
              },
          )
      } catch (exception: Exception) {
        Timber.e(exception, "Could not load the AHIP dashboard")
        _dashboard.value = AhipDashboard.EMPTY
      }
    }
  }

  /** Responses saved before collector tagging (no tag) are treated as this device's user's. */
  private fun QuestionnaireResponse.isByCollector(collector: String?): Boolean {
    val tag = meta.tag.firstOrNull { it.system == AhipCatalog.COLLECTOR_TAG_SYSTEM }
    return tag == null || collector == null || tag.code == collector
  }

  private fun QuestionnaireResponse.answerOf(linkId: String): String? {
    fun find(items: List<QuestionnaireResponse.QuestionnaireResponseItemComponent>): String? {
      for (item in items) {
        if (item.linkId == linkId) {
          val value = item.answer.firstOrNull()?.value ?: return null
          return if (value is Coding) value.display ?: value.code else value.primitiveValue()
        }
        find(item.item)?.let {
          return it
        }
      }
      return null
    }
    return find(item)
  }

  private fun QuestionnaireResponse.siteName(): String? =
    SITE_LINK_IDS.firstNotNullOfOrNull { linkId -> answerOf(linkId)?.takeIf { it.isNotBlank() } }

  /** Set when the server was found at a new address; the activity must restart the app. */
  private val _serverMoved = MutableStateFlow(false)
  val serverMoved: StateFlow<Boolean> = _serverMoved.asStateFlow()

  val serverHost: String
    get() = AhipServerDiscovery.currentHost(context)

  /** Syncs with the AHIP server, first finding it if it has moved to a new address. */
  fun syncNow() {
    checkAccount()
    _syncStatus.value = AhipSyncStatus.RUNNING
    viewModelScope.launch {
      when (AhipServerDiscovery.checkOrFind(context)) {
        AhipServerDiscovery.Result.Reachable -> {
          // Start the upload but don't wait on its status: when a background sync is already
          // queued, the one-time sync never reports back. Watch the waiting forms instead.
          // REPLACE: a stuck or backed-off earlier sync would otherwise make this one be ignored.
          launch {
            runCatching {
              com.google.android.fhir.sync.Sync.oneTimeSync<org.smartregister.fhircore.engine.sync.AppSyncWorker>(
                context,
                existingWorkPolicy = androidx.work.ExistingWorkPolicy.REPLACE,
              )
            }
              .onFailure { Timber.e(it, "Could not start sync") }
          }
          refreshForms()
          waitForUpload()
        }
        is AhipServerDiscovery.Result.Moved -> _serverMoved.value = true
        AhipServerDiscovery.Result.NoLocalNetwork,
        AhipServerDiscovery.Result.NotFound, -> _syncStatus.value = AhipSyncStatus.NO_SERVER
      }
    }
  }

  /**
   * Waits (at most [SYNC_WAIT_MS]) until no submitted or draft form is waiting to upload. Shows
   * "synced" as soon as they are all on the server; otherwise the forms stay safely on the phone
   * and are sent by the next (background) sync.
   */
  private suspend fun waitForUpload() {
    val deadline = System.currentTimeMillis() + SYNC_WAIT_MS
    while (System.currentTimeMillis() < deadline) {
      kotlinx.coroutines.delay(SYNC_POLL_MS)
      val waiting =
        runCatching {
            val pending =
              defaultRepository.fhirEngine
                .getUnsyncedLocalChanges()
                .filter { it.resourceType == "QuestionnaireResponse" }
                .map { it.resourceId }
                .toSet()
            // Only submitted forms count; unfinished drafts upload too but do not block "synced".
            pending.count { id ->
              runCatching { defaultRepository.loadResource<QuestionnaireResponse>(id) }.getOrNull()?.status ==
                QuestionnaireResponse.QuestionnaireResponseStatus.COMPLETED
            }
          }
          .getOrDefault(1)
      if (waiting == 0) {
        _syncStatus.value = AhipSyncStatus.SUCCEEDED
        loadDashboard()
        return
      }
    }
    if (_syncStatus.value == AhipSyncStatus.RUNNING) _syncStatus.value = AhipSyncStatus.FAILED
    loadDashboard()
  }

  /** Schedules background sync, which AppMainActivity would otherwise do after login. */
  fun schedulePeriodicSync() {
    viewModelScope.launch {
      val interval =
        configurationRegistry
          .retrieveConfiguration<ApplicationConfiguration>(ConfigType.Application)
          .syncInterval
      syncBroadcaster.schedulePeriodicSync(interval)
    }
  }

  /**
   * Background (periodic) syncs only refresh the numbers. They must not drive the Sync button:
   * a background job that is retried or stuck reports "running" and never "finished", which
   * left the button on "Syncing…" forever. The button's status comes from [syncNow] only, which
   * always ends within [SYNC_WAIT_MS].
   */
  override fun onSync(syncState: SyncState) {
    if (syncState.currentSyncJobStatus is CurrentSyncJobStatus.Succeeded) loadDashboard()
  }

  private fun Questionnaire.toToolDetails(): AhipToolDetails {
    val sections = mutableListOf<String>()
    fun collectSections(items: List<Questionnaire.QuestionnaireItemComponent>) {
      items
        .filter { it.type == Questionnaire.QuestionnaireItemType.GROUP }
        .forEach { group ->
          val hasQuestions = group.item.any { it.type != Questionnaire.QuestionnaireItemType.GROUP }
          if (hasQuestions && group.text !in sections) sections.add(group.text)
          collectSections(group.item)
        }
    }
    collectSections(item)
    return AhipToolDetails(questionCount = item.countQuestions(), sections = sections)
  }

  /**
   * Counts the tool's numbered questions (items with a `prefix`, matching the paper tools), or,
   * for a form without numbering, every visible non-group item.
   */
  private fun List<Questionnaire.QuestionnaireItemComponent>.countQuestions(): Int {
    fun List<Questionnaire.QuestionnaireItemComponent>.numbered(): Int = sumOf {
      if (it.hasPrefix()) 1 else it.item.numbered()
    }
    fun List<Questionnaire.QuestionnaireItemComponent>.all(): Int = sumOf {
      when {
        it.type == Questionnaire.QuestionnaireItemType.GROUP -> it.item.all()
        it.type == Questionnaire.QuestionnaireItemType.DISPLAY -> 0
        it.isHiddenAhipItem() -> 0
        else -> 1
      }
    }
    return numbered().takeIf { it > 0 } ?: all()
  }

  private fun Questionnaire.QuestionnaireItemComponent.isHiddenAhipItem() =
    linkId == AhipCatalog.STUDY_LINK_ID || linkId == AhipCatalog.ASSESSMENT_LINK_ID

  /** Counts answered items; by default the questions (not hidden study fields or photos). */
  private fun List<QuestionnaireResponse.QuestionnaireResponseItemComponent>.countAnswered(
    counts: (QuestionnaireResponse.QuestionnaireResponseItemComponent) -> Boolean = {
      it.linkId != AhipCatalog.STUDY_LINK_ID &&
        it.linkId != AhipCatalog.ASSESSMENT_LINK_ID &&
        it.linkId !in AhipCatalog.PHOTO_LINK_IDS &&
        !AhipCatalog.isAudio(it.linkId)
    },
  ): Int =
    sumOf { responseItem ->
      val ownAnswer = if (responseItem.hasAnswer() && counts(responseItem)) 1 else 0
      ownAnswer + responseItem.item.countAnswered(counts)
    }

  private companion object {
    const val DAY_MILLIS = 24L * 60 * 60 * 1000
    const val RECENT_LIMIT = 10
    const val SYNC_WAIT_MS = 60_000L
    const val SYNC_POLL_MS = 3_000L

    /** Answers a form has as soon as it is opened. */
    val EMPTY_DRAFT_LINK_IDS =
      setOf(AhipCatalog.STUDY_LINK_ID, AhipCatalog.ASSESSMENT_LINK_ID, "collection_mode")

    /** Answers that name where an assessment took place, in order of preference. */
    val SITE_LINK_IDS = listOf("cpp_name_other", "cpp_name", "facility_name", "council")
  }
}

/** State of the "Share my data (API key)" dialog. */
sealed class AhipShareState {
  object Idle : AhipShareState()

  object Loading : AhipShareState()

  data class Error(val message: String) : AhipShareState()

  data class Ready(val apiKey: String, val jsonUrl: String, val csvUrl: String) : AhipShareState()
}

/** One message in the phone AI assistant chat. */
data class AhipAiMessage(val fromUser: Boolean, val text: String, val error: Boolean = false)
