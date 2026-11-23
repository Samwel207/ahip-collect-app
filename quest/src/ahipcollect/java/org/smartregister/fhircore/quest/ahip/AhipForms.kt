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
import ca.uhn.fhir.context.FhirContext
import com.google.android.fhir.FhirEngine
import com.google.android.fhir.get
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.Questionnaire
import org.smartregister.fhircore.engine.configuration.ConfigurationRegistry
import org.smartregister.fhircore.quest.ServerAddress
import timber.log.Timber

/**
 * The tool forms (T1–T6 and any form created there) are designed in AHIP Manage (Forms) and published to the FHIR server. The phone
 * downloads the latest versions on start and on Sync and keeps them, so they also work offline.
 * A copy built into the app (assets/ahip_forms) is installed only when the phone has none yet.
 */
object AhipForms {
  private const val BUNDLED_DIR = "ahip_forms"
  private const val TIMEOUT_MS = 8000

  /** New key: phones updated to the numbered-forms version download every form once again. */
  private const val LAST_UPDATED_KEY = "last_updated_v2"

  private val parser by lazy { FhirContext.forR4Cached().newJsonParser() }

  /** Installs bundled forms that are missing on the phone (first start without the server). */
  suspend fun installMissingBundled(
    context: Context,
    fhirEngine: FhirEngine,
    configurationRegistry: ConfigurationRegistry,
  ) =
    withContext(Dispatchers.IO) {
      val files = runCatching { context.assets.list(BUNDLED_DIR)?.toList() }.getOrNull().orEmpty()
      for (file in files.filter { it.endsWith(".json") }) {
        runCatching {
            val form =
              parser.parseResource(
                Questionnaire::class.java,
                context.assets.open("$BUNDLED_DIR/$file").bufferedReader().readText(),
              )
            val present = runCatching { fhirEngine.get<Questionnaire>(form.idPart) }.isSuccess
            if (!present) configurationRegistry.createOrUpdateRemote(form)
          }
          .onFailure { Timber.w(it, "Could not install bundled form $file") }
      }
    }

  /**
   * Downloads the AHIP tool forms from the server and saves them on the phone. Returns the forms,
   * or null when the server could not be reached (the saved forms keep working).
   */
  suspend fun refreshFromServer(
    context: Context,
    configurationRegistry: ConfigurationRegistry,
  ): List<Questionnaire>? =
    withContext(Dispatchers.IO) {
      val base = ServerAddress.fhirBaseUrl(context).trimEnd('/')
      // Only forms changed since the last download (server time), so a Sync stays fast.
      val prefs = context.getSharedPreferences("ahip_forms", Context.MODE_PRIVATE)
      val since = prefs.getString(LAST_UPDATED_KEY, null)
      val changed = since?.let { "&_lastUpdated=gt${java.net.URLEncoder.encode(it, "UTF-8")}" }.orEmpty()
      val bundle =
        try {
          val connection =
            // Every AHIP form carries the identifier urn:ahip:tool|<number>, new forms included.
            URL("$base/Questionnaire?identifier=urn%3Aahip%3Atool%7C&_count=200$changed").openConnection()
              as HttpURLConnection
          try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS * 3
            connection.setRequestProperty("Accept", "application/fhir+json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            parser.parseResource(Bundle::class.java, connection.inputStream.bufferedReader().readText())
          } finally {
            connection.disconnect()
          }
        } catch (exception: IOException) {
          Timber.w(exception, "AHIP forms: server not reachable")
          return@withContext null
        }
      val forms = bundle.entry.mapNotNull { it.resource as? Questionnaire }
      forms.forEach { form ->
        runCatching { configurationRegistry.createOrUpdateRemote(form) }
          .onFailure { Timber.e(it, "Could not save form ${form.idPart}") }
      }
      AhipCatalog.rememberForms(context, forms)
      forms
        .mapNotNull { it.meta?.lastUpdatedElement?.valueAsString }
        .maxOrNull()
        ?.let { prefs.edit().putString(LAST_UPDATED_KEY, it).apply() }
      forms
    }
}
