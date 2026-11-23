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
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.smartregister.fhircore.quest.ServerAddress
import timber.log.Timber

/**
 * Finds the AHIP FHIR server on whatever Wi-Fi the phone is on, so the PC running it can change
 * networks or IP address. The server keeps its port and path (e.g. `:8089/fhir/`); only the host
 * is searched for, across the phone's local /24 network.
 */
object AhipServerDiscovery {

  sealed interface Result {
    /** The saved address answers. */
    object Reachable : Result

    /** The server answered at a new address, which has been saved; the app must restart. */
    data class Moved(val baseUrl: String) : Result

    /** The phone is not on Wi-Fi / a local network. */
    object NoLocalNetwork : Result

    /** On a local network, but no AHIP server answered. */
    object NotFound : Result
  }

  /** Checks the saved server and, if it does not answer, searches the local network for it. */
  suspend fun checkOrFind(context: Context): Result {
    val current = ServerAddress.fhirBaseUrl(context)
    if (isFhirServer(current, CHECK_TIMEOUT_MS)) return Result.Reachable
    val localAddress = localIpv4(context) ?: return Result.NoLocalNetwork
    val found = scan(localAddress, URL(current)) ?: return Result.NotFound
    if (found == current) return Result.Reachable
    ServerAddress.saveFhirBaseUrl(context, found)
    Timber.i("AHIP server moved: $current -> $found")
    return Result.Moved(found)
  }

  /** Host (IP address) of the saved server, for display. */
  fun currentHost(context: Context): String =
    runCatching { URL(ServerAddress.fhirBaseUrl(context)).host }.getOrDefault("")

  /** Restarts the app so the FHIR engine and network clients pick up the new server address. */
  fun restartApp(activity: Activity) {
    val restart =
      Intent.makeRestartActivityTask(ComponentName(activity, AhipWelcomeActivity::class.java))
        .putExtra(AhipWelcomeActivity.EXTRA_SERVER_FOUND, true)
    activity.startActivity(restart)
    Runtime.getRuntime().exit(0)
  }

  private suspend fun isFhirServer(baseUrl: String, timeoutMs: Int): Boolean =
    withContext(Dispatchers.IO) {
      runCatching {
          val connection =
            URL("${baseUrl.trimEnd('/')}/metadata?_summary=true").openConnection()
              as HttpURLConnection
          try {
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs * 2
            connection.setRequestProperty("Accept", "application/fhir+json")
            connection.responseCode == HttpURLConnection.HTTP_OK &&
              connection.inputStream.bufferedReader().use { it.readText() }
                .contains("CapabilityStatement")
          } finally {
            connection.disconnect()
          }
        }
        .getOrDefault(false)
    }

  /** Tries every host of the local /24 network in parallel; returns the first FHIR server. */
  private suspend fun scan(localAddress: Inet4Address, template: URL): String? = coroutineScope {
    val port = if (template.port != -1) template.port else template.defaultPort
    val ownHost = localAddress.hostAddress ?: return@coroutineScope null
    val network = ownHost.substringBeforeLast('.')
    val permits = Semaphore(PARALLEL_PROBES)
    val found = CompletableDeferred<String?>()

    val probes =
      (1..254)
        .map { "$network.$it" }
        .filter { it != ownHost }
        .map { host ->
          launch(Dispatchers.IO) {
            permits.withPermit {
              if (found.isCompleted || !isPortOpen(host, port)) return@withPermit
              val candidate = URL(template.protocol, host, port, template.path).toString()
              if (isFhirServer(candidate, PROBE_TIMEOUT_MS)) found.complete(candidate)
            }
          }
        }
    launch {
      probes.joinAll()
      found.complete(null)
    }
    found.await().also { probes.forEach { it.cancel() } }
  }

  private fun isPortOpen(host: String, port: Int): Boolean =
    runCatching { Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) } }
      .isSuccess

  private fun localIpv4(context: Context): Inet4Address? {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = connectivity.activeNetwork ?: return null
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
    val isLocal =
      capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    if (!isLocal) return null
    return connectivity
      .getLinkProperties(network)
      ?.linkAddresses
      ?.map { it.address }
      ?.filterIsInstance<Inet4Address>()
      ?.firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
  }

  private const val CHECK_TIMEOUT_MS = 2500
  private const val PROBE_TIMEOUT_MS = 1500
  private const val CONNECT_TIMEOUT_MS = 400
  private const val PARALLEL_PROBES = 48
}
