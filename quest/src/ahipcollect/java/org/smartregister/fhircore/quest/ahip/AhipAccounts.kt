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

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import org.json.JSONObject
import org.smartregister.fhircore.quest.BuildConfig
import org.smartregister.fhircore.quest.ServerAddress
import org.smartregister.fhircore.quest.ui.login.LoginVerification
import org.smartregister.fhircore.quest.ui.login.LoginVerifier
import timber.log.Timber

/**
 * Accounts are managed in AHIP Manage (the dashboard on the same PC as the FHIR server, port
 * 8090): collectors are invited by email and choose their password there.
 */
object AhipAccounts {
  private const val MANAGE_PORT = 8090
  private const val PREFS = "ahip_profile"
  private const val CONNECT_TIMEOUT_MS = 5000
  private const val READ_TIMEOUT_MS = 10000

  /** AHIP Manage runs on the FHIR server's host. */
  private fun manageUrl(context: Context, path: String): URL {
    val fhir = URL(ServerAddress.fhirBaseUrl(context))
    return URL(fhir.protocol, fhir.host, MANAGE_PORT, path)
  }

  /** POSTs JSON; returns the HTTP status and parsed body, or null when the server is unreachable. */
  internal suspend fun post(
    context: Context,
    path: String,
    body: JSONObject,
    readTimeoutMs: Int = READ_TIMEOUT_MS,
  ): Pair<Int, JSONObject>? =
    withContext(Dispatchers.IO) {
      try {
        val connection = manageUrl(context, path).openConnection() as HttpURLConnection
        try {
          connection.requestMethod = "POST"
          connection.connectTimeout = CONNECT_TIMEOUT_MS
          connection.readTimeout = readTimeoutMs
          connection.doOutput = true
          connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
          connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
          val code = connection.responseCode
          val stream = if (code in 200..299) connection.inputStream else connection.errorStream
          val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
          code to (runCatching { JSONObject(text) }.getOrNull() ?: JSONObject())
        } finally {
          connection.disconnect()
        }
      } catch (exception: IOException) {
        Timber.w(exception, "AHIP Manage unreachable")
        null
      }
    }

  /** Whether an account may keep using the app, and if not, why (see `/api/mobile/status`). */
  data class AccountStatus(val active: Boolean, val reason: String?)

  /**
   * Whether [username] may still use the app according to AHIP Manage, or null when the server
   * cannot be reached (keep working offline).
   */
  suspend fun accountStatus(context: Context, username: String): AccountStatus? {
    val request = JSONObject().put("username", username).put("deviceId", deviceId(context))
    val (code, body) = post(context, "/api/mobile/status", request) ?: return null
    if (code != HttpURLConnection.HTTP_OK) return null
    return AccountStatus(
      active = body.optBoolean("active", false),
      reason = body.optString("reason").takeIf { it.isNotEmpty() && it != "null" },
    )
  }

  /**
   * Identifies this phone for the one-phone-per-account rule: Android's per-app device ID, which
   * survives app updates and reinstalls (same signing key); a stored random ID if unavailable.
   */
  @SuppressLint("HardwareIds")
  fun deviceId(context: Context): String {
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    if (!androidId.isNullOrBlank() && androidId != BROKEN_ANDROID_ID) return "android-$androidId"
    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return prefs.getString("device_id", null)
      ?: "app-${UUID.randomUUID()}".also { prefs.edit().putString("device_id", it).apply() }
  }

  /** Phone details shown to administrators in AHIP Manage. */
  fun deviceInfo(context: Context): JSONObject {
    val ownerName =
      runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
        .getOrNull()
    return JSONObject()
      .put("id", deviceId(context))
      .put("name", ownerName.orEmpty())
      .put("manufacturer", Build.MANUFACTURER.replaceFirstChar { it.uppercase() })
      .put("model", Build.MODEL)
      .put("android", Build.VERSION.RELEASE)
      .put("appVersion", BuildConfig.VERSION_NAME)
  }

  /** Emulator-era value that many old devices share; not usable as an ID. */
  private const val BROKEN_ANDROID_ID = "9774d56d682e549c"

  fun saveProfile(context: Context, profile: JSONObject) {
    context
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .edit()
      .putString("username", profile.optString("username"))
      .putString("name", profile.optString("name"))
      .putString("phone", profile.optString("phone"))
      .putString("email", profile.optString("email"))
      .apply()
  }

  /** Full name of the signed-in collector, if known. */
  fun displayName(context: Context, username: String?): String? =
    context
      .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      .takeIf { username != null && it.getString("username", null) == username }
      ?.getString("name", null)
      ?.takeIf { it.isNotBlank() }
}

/** Checks AHIP Data Collect sign-ins against the accounts in AHIP Manage. */
@Singleton
class AhipLoginVerifier @Inject constructor(@ApplicationContext private val context: Context) :
  LoginVerifier {

  override suspend fun verify(username: String, password: CharArray): LoginVerification {
    val body =
      JSONObject()
        .put("username", username)
        .put("password", String(password))
        .put("device", AhipAccounts.deviceInfo(context))
    val (code, response) =
      AhipAccounts.post(context, "/api/mobile/login", body) ?: return LoginVerification.Unreachable
    return when (code) {
      HttpURLConnection.HTTP_OK -> {
        AhipAccounts.saveProfile(context, response)
        LoginVerification.Verified
      }
      HttpURLConnection.HTTP_FORBIDDEN ->
        if (response.optString("code") in setOf("DEVICE_LOCKED", "DEVICE_BLOCKED")) {
          LoginVerification.WrongDevice
        } else {
          LoginVerification.Rejected
        }
      HttpURLConnection.HTTP_UNAUTHORIZED,
      429, -> LoginVerification.Rejected
      else -> LoginVerification.Unreachable
    }
  }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AhipLoginModule {
  @Binds abstract fun bindLoginVerifier(verifier: AhipLoginVerifier): LoginVerifier
}
