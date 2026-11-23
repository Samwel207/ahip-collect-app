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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import org.hl7.fhir.r4.model.Enumerations
import org.hl7.fhir.r4.model.IntegerType
import org.hl7.fhir.r4.model.Questionnaire
import org.hl7.fhir.r4.model.StringType
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber

/** A study (programme) that data collectors can select. */
data class AhipStudy(
  val id: String,
  val title: String,
  val subtitle: String,
  val enabled: Boolean = true,
)

/** An assessment round within a study; rounds that are not [available] show "Coming soon". */
data class AhipAssessment(
  val id: String,
  val title: String,
  val period: String,
  val available: Boolean = true,
)

/** One of the AHIP data collection tools; [questionnaireId] is the FHIR Questionnaire id. */
data class AhipTool(
  val number: Int,
  val title: String,
  val questionnaireId: String,
  val target: String,
  val estimatedMinutes: Int,
  val icon: ImageVector,
)

/**
 * Catalogue for the AHIP Collect flow. Studies and assessments are fixed for the Afya-Tek Phase III
 * baseline; tools are the six original ones plus every form created in AHIP Manage.
 */
object AhipCatalog {

  val studies =
    listOf(
      AhipStudy(
        id = "afya-tek-phase-3",
        title = "Afya-Tek Phase III",
        subtitle = "AHIP Baseline",
      ),
      AhipStudy(
        id = "other-studies",
        title = "Other Studies",
        subtitle = "e.g. Midline, Endline",
        enabled = false,
      ),
    )

  val assessments =
    listOf(
      AhipAssessment(id = "baseline-2025", title = "Baseline Assessment", period = "2025"),
      AhipAssessment(
        id = "midline-2026",
        title = "Midline Assessment",
        period = "2026",
        available = false,
      ),
      AhipAssessment(
        id = "endline-2027",
        title = "Endline Assessment",
        period = "2027",
        available = false,
      ),
      AhipAssessment(
        id = "rapid-adhoc",
        title = "Rapid Assessment",
        period = "Ad-hoc",
        available = false,
      ),
    )

  /** The six original tools; forms created in AHIP Manage are added from [extraTools]. */
  private val builtInTools =
    listOf(
      AhipTool(
        number = 1,
        title = "CPP Provider Assessment",
        questionnaireId = "ahip-t1-cpp-provider",
        target = "CPP providers (ADDOs / pharmacies)",
        estimatedMinutes = 25,
        icon = Icons.Outlined.Storefront,
      ),
      AhipTool(
        number = 2,
        title = "Healthcare Worker Assessment",
        questionnaireId = "ahip-t2-healthcare-worker",
        target = "Healthcare workers at health facilities",
        estimatedMinutes = 25,
        icon = Icons.Outlined.LocalHospital,
      ),
      AhipTool(
        number = 3,
        title = "CPP Exit Client Assessment",
        questionnaireId = "ahip-t3-exit-client",
        target = "Clients leaving a CPP",
        estimatedMinutes = 15,
        icon = Icons.Outlined.Groups,
      ),
      AhipTool(
        number = 4,
        title = "High-Level Routine Indicators",
        questionnaireId = "ahip-t4-routine-indicators",
        target = "Council routine (HMIS) data",
        estimatedMinutes = 30,
        icon = Icons.Outlined.Assessment,
      ),
      AhipTool(
        number = 5,
        title = "CPP Digital Sustainability (Kibaha)",
        questionnaireId = "ahip-t5-cpp-digital-sustainability",
        target = "CPPs in Kibaha",
        estimatedMinutes = 30,
        icon = Icons.Outlined.Computer,
      ),
      AhipTool(
        number = 6,
        title = "Facility Digital Sustainability (Kibaha)",
        questionnaireId = "ahip-t6-facility-digital-sustainability",
        target = "Health facilities in Kibaha",
        estimatedMinutes = 20,
        icon = Icons.Outlined.Settings,
      ),
    )

  /** Forms created in AHIP Manage (Forms > New form), remembered so they also work offline. */
  private val extraTools = mutableStateOf<List<AhipTool>>(emptyList())

  /** Every tool on this phone, in tool-number order (read from Compose: updates when forms change). */
  val tools: List<AhipTool>
    get() = (builtInTools + extraTools.value).sortedBy { it.number }

  /** Identifier system that numbers every AHIP form ("urn:ahip:tool|7"). */
  const val TOOL_SYSTEM = "urn:ahip:tool"
  private const val TARGET_EXT = "urn:ahip:tool-target"
  private const val MINUTES_EXT = "urn:ahip:tool-minutes"
  private const val PREFS = "ahip_forms"
  private const val REGISTRY_KEY = "registry"

  /** Loads the forms created in AHIP Manage that this phone already knows. */
  fun loadRegistry(context: Context) {
    val text = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(REGISTRY_KEY, null) ?: return
    extraTools.value =
      runCatching {
          val array = JSONArray(text)
          (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            AhipTool(
              number = o.getInt("number"),
              title = o.getString("title"),
              questionnaireId = o.getString("id"),
              target = o.optString("target"),
              estimatedMinutes = o.optInt("minutes", 20),
              icon = Icons.Outlined.Description,
            )
          }
        }
        .onFailure { Timber.w(it, "AHIP forms: could not read the saved form list") }
        .getOrDefault(emptyList())
  }

  /**
   * Updates the list with forms just downloaded: new forms are added, archived (retired) forms are
   * removed from the phone's tool list (their answers already sent stay on the server).
   */
  fun rememberForms(context: Context, forms: List<Questionnaire>) {
    val builtInIds = builtInTools.map { it.questionnaireId }.toSet()
    val byId = extraTools.value.associateBy { it.questionnaireId }.toMutableMap()
    forms.forEach { form ->
      val id = form.idElement.idPart
      if (id in builtInIds) return@forEach
      val number = form.identifier.firstOrNull { it.system == TOOL_SYSTEM }?.value?.toIntOrNull()
      if (number == null || form.status == Enumerations.PublicationStatus.RETIRED) {
        byId.remove(id)
        return@forEach
      }
      byId[id] =
        AhipTool(
          number = number,
          title = form.title.orEmpty().replace(Regex("^Tool\\s*\\d+\\s*[:.\\-]\\s*", RegexOption.IGNORE_CASE), "").ifBlank { id },
          questionnaireId = id,
          target = (form.getExtensionByUrl(TARGET_EXT)?.value as? StringType)?.value.orEmpty(),
          estimatedMinutes = (form.getExtensionByUrl(MINUTES_EXT)?.value as? IntegerType)?.value ?: 20,
          icon = Icons.Outlined.Description,
        )
    }
    val list = byId.values.sortedBy { it.number }
    extraTools.value = list
    val array = JSONArray()
    list.forEach {
      array.put(
        JSONObject()
          .put("id", it.questionnaireId)
          .put("number", it.number)
          .put("title", it.title)
          .put("target", it.target)
          .put("minutes", it.estimatedMinutes),
      )
    }
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(REGISTRY_KEY, array.toString()).apply()
  }

  /** Hidden Questionnaire items that record which study and assessment a response belongs to. */
  const val STUDY_LINK_ID = "ahip-study"
  const val ASSESSMENT_LINK_ID = "ahip-assessment"

  /** Photo items the questionnaire generator appends to every tool (see lib.js `photos`). */
  val PHOTO_LINK_IDS = setOf("photo_1", "photo_2")

  /** Audio answers the generator adds after open-ended questions (`<question>_audio`). */
  fun isAudio(linkId: String) = linkId.contains("_audio")

  /** Latitude field filled by the tool's "Record GPS Location" button. */
  const val GPS_LATITUDE_LINK_ID = "gps_latitude"

  /** Meta tag system recording which data collector (username) submitted a response. */
  const val COLLECTOR_TAG_SYSTEM = "urn:ahip:data-collector"
}
