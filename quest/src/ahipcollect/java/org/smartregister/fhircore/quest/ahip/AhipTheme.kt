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

import androidx.compose.material.MaterialTheme
import androidx.compose.material.lightColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** AHIP brand colours, taken from the AHIP Collect logo and mockups. */
object AhipColors {
  val Navy = Color(0xFF14246B)
  val Purple = Color(0xFF6F1A78)
  val Green = Color(0xFF1E9B2E)
  val GreenTint = Color(0xFFE9F6EA)
  val PurpleTint = Color(0xFFF3E8F5)
  val Background = Color(0xFFF6F7FB)
  val Border = Color(0xFFDDE1EA)
  val TextMuted = Color(0xFF5B6275)
}

@Composable
fun AhipTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colors =
      lightColors(
        primary = AhipColors.Navy,
        primaryVariant = AhipColors.Navy,
        secondary = AhipColors.Green,
        background = AhipColors.Background,
        surface = Color.White,
        onPrimary = Color.White,
        onSecondary = Color.White,
      ),
    content = content,
  )
}
