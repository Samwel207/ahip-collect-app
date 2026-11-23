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

package org.smartregister.fhircore.quest.ui.main

import android.content.Context
import android.content.Intent

/**
 * Intent action a product flavor can declare on its own activity (in the flavor's
 * AndroidManifest.xml) to replace [AppMainActivity] as the screen shown after login.
 */
const val ACTION_OPEN_HOME_SCREEN = "org.smartregister.fhircore.quest.action.OPEN_HOME_SCREEN"

/**
 * Returns an intent for the home screen: the flavor's own home activity if it declares
 * [ACTION_OPEN_HOME_SCREEN], otherwise [AppMainActivity].
 */
fun Context.homeScreenIntent(): Intent {
  val flavorHome = Intent(ACTION_OPEN_HOME_SCREEN).setPackage(packageName)
  return if (flavorHome.resolveActivity(packageManager) != null) {
    flavorHome
  } else {
    Intent(this, AppMainActivity::class.java)
  }
}
