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

package org.smartregister.fhircore.quest

import android.content.Context

/**
 * The FHIR server address the app talks to. Defaults to [BuildConfig.FHIR_BASE_URL]; a flavor can
 * save a different one at runtime (e.g. a server found on the local network). The FHIR engine and
 * network clients read it once per process, so a change takes effect after the app restarts.
 */
object ServerAddress {
  private const val PREFS = "server_address"
  private const val KEY_FHIR_BASE_URL = "fhir_base_url"

  fun fhirBaseUrl(context: Context): String =
    context
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .getString(KEY_FHIR_BASE_URL, null)
      ?.takeIf { it.isNotBlank() } ?: BuildConfig.FHIR_BASE_URL

  /** Saves synchronously so the address survives an immediate process restart. */
  fun saveFhirBaseUrl(context: Context, url: String): Boolean =
    context
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit()
      .putString(KEY_FHIR_BASE_URL, url)
      .commit()
}
