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

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.ui.base.BaseMultiLanguageActivity
import org.smartregister.fhircore.engine.util.extension.applyWindowInsetListener
import org.smartregister.fhircore.quest.ui.appsetting.AppSettingActivity

/** Where the "Get Started" screen is in finding the AHIP server. */
enum class AhipConnectStatus {
  IDLE,
  SEARCHING,
  NOT_FOUND,
  NO_WIFI,
}

/**
 * AHIP Collect launch screen ("Get Started"). Finds the AHIP server on the current Wi-Fi (it may
 * have a new address), then hands over to the normal config + login flow. Working offline is
 * always allowed: data is kept on the phone and synced later.
 */
@AndroidEntryPoint
class AhipWelcomeActivity : BaseMultiLanguageActivity() {

  private var status by mutableStateOf(AhipConnectStatus.IDLE)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    applyWindowInsetListener()
    setContent {
      AhipTheme {
        AhipWelcomeScreen(
          status = status,
          serverHost = AhipServerDiscovery.currentHost(this),
          onGetStarted = ::connect,
          onContinueOffline = ::proceed,
        )
      }
    }
    // Restarted after the server was found at a new address: carry straight on.
    if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_SERVER_FOUND, false)) proceed()
  }

  private fun connect() {
    status = AhipConnectStatus.SEARCHING
    lifecycleScope.launch {
      when (AhipServerDiscovery.checkOrFind(this@AhipWelcomeActivity)) {
        AhipServerDiscovery.Result.Reachable -> proceed()
        is AhipServerDiscovery.Result.Moved -> AhipServerDiscovery.restartApp(this@AhipWelcomeActivity)
        AhipServerDiscovery.Result.NoLocalNetwork -> status = AhipConnectStatus.NO_WIFI
        AhipServerDiscovery.Result.NotFound -> status = AhipConnectStatus.NOT_FOUND
      }
    }
  }

  private fun proceed() {
    startActivity(Intent(this, AppSettingActivity::class.java))
    finish()
  }

  companion object {
    const val EXTRA_SERVER_FOUND = "ahipServerFound"
  }
}
