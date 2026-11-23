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

@file:OptIn(ExperimentalFoundationApi::class)

package org.smartregister.fhircore.quest.ui.login

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.smartregister.fhircore.engine.configuration.app.ApplicationConfiguration
import org.smartregister.fhircore.quest.R

private val Muted = Color(0xFF5B6275)
private val FieldBorder = Color(0xFFDDE1EA)
private val ErrorRed = Color(0xFFC62828)

/**
 * Branded sign-in page used when the flavor sets `login_branded` to true: logo, a card with icon
 * fields and a pill button, and brand-coloured waves along the bottom. No "Powered by" footer.
 * Colours come from `login_primary` (button, focus), `login_title` and `login_accent`.
 */
@Composable
internal fun BrandedLoginPage(
  applicationConfiguration: ApplicationConfiguration,
  username: String,
  onUsernameChanged: (String) -> Unit,
  password: String,
  onPasswordChanged: (String) -> Unit,
  onForgotPassword: () -> Unit,
  onLoginButtonClicked: () -> Unit,
  errorMessage: String,
  showProgressBar: Boolean,
  versionText: String,
) {
  val primary = colorResource(R.color.login_primary)
  val titleColor = colorResource(R.color.login_title)
  val accent = colorResource(R.color.login_accent)
  var showPassword by remember { mutableStateOf(false) }
  val focusManager = LocalFocusManager.current
  val passwordFocus = remember { FocusRequester() }
  val coroutineScope = rememberCoroutineScope()
  val bringIntoView = remember { BringIntoViewRequester() }
  val canSignIn = !showProgressBar && username.isNotEmpty() && password.isNotEmpty()

  val fieldColors =
    TextFieldDefaults.outlinedTextFieldColors(
      backgroundColor = Color(0xFFF8F9FC),
      focusedBorderColor = primary,
      unfocusedBorderColor = FieldBorder,
      cursorColor = primary,
      leadingIconColor = titleColor,
      textColor = titleColor,
    )

  Box(
    Modifier.fillMaxSize()
      .background(Brush.verticalGradient(listOf(Color(0xFFF1F4FB), Color.White))),
  ) {
    LoginWaves(
      primary = primary,
      accent = accent,
      modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(120.dp),
    )
    Column(
      Modifier.fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 96.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
    ) {
      if (applicationConfiguration.loginConfig.showLogo) {
        Image(
          painter = painterResource(org.smartregister.fhircore.engine.R.drawable.ic_app_logo),
          contentDescription = stringResource(org.smartregister.fhircore.engine.R.string.app_logo),
          modifier = Modifier.width(200.dp).height(96.dp).testTag(APP_LOGO_TAG),
        )
      }
      if (applicationConfiguration.appTitle.isNotEmpty()) {
        Text(
          applicationConfiguration.appTitle,
          color = titleColor,
          fontSize = 28.sp,
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(top = 8.dp).testTag(APP_NAME_TEXT_TAG),
        )
      }
      val subtitle = stringResource(R.string.login_branded_subtitle)
      if (subtitle.isNotEmpty()) {
        Text(
          subtitle,
          color = Muted,
          fontSize = 15.sp,
          textAlign = TextAlign.Center,
          modifier = Modifier.padding(top = 4.dp),
        )
      }
      Spacer(Modifier.height(28.dp))

      Card(
        shape = RoundedCornerShape(24.dp),
        elevation = 6.dp,
        backgroundColor = Color.White,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 24.dp)) {
          Text(
            stringResource(R.string.login_branded_heading),
            color = titleColor,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
          )
          Text(
            stringResource(R.string.login_branded_hint),
            color = Muted,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp),
          )

          OutlinedTextField(
            value = username,
            onValueChange = onUsernameChanged,
            singleLine = true,
            label = { Text(stringResource(org.smartregister.fhircore.engine.R.string.username)) },
            leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
            modifier = Modifier.fillMaxWidth().testTag(USERNAME_FIELD_TAG),
          )
          Spacer(Modifier.height(14.dp))
          OutlinedTextField(
            value = password,
            onValueChange = onPasswordChanged,
            singleLine = true,
            label = { Text(stringResource(org.smartregister.fhircore.engine.R.string.password)) },
            leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
            trailingIcon = {
              IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                  if (showPassword) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                  contentDescription = null,
                  tint = Muted,
                )
              }
            },
            visualTransformation =
              if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            shape = RoundedCornerShape(14.dp),
            colors = fieldColors,
            keyboardOptions =
              KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions =
              KeyboardActions(
                onDone = {
                  focusManager.clearFocus()
                  if (canSignIn) onLoginButtonClicked()
                },
              ),
            modifier =
              Modifier.fillMaxWidth()
                .focusRequester(passwordFocus)
                .onFocusEvent {
                  if (it.isFocused) coroutineScope.launch { bringIntoView.bringIntoView() }
                }
                .testTag(PASSWORD_FIELD_TAG),
          )

          if (errorMessage.isNotEmpty()) {
            Row(
              Modifier.padding(top = 14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFFFDECEC))
                .padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = ErrorRed)
              Spacer(Modifier.width(10.dp))
              Text(
                errorMessage,
                color = ErrorRed,
                fontSize = 14.sp,
                modifier = Modifier.testTag(LOGIN_ERROR_TEXT_TAG),
              )
            }
          }

          Spacer(Modifier.height(22.dp))
          Button(
            onClick = onLoginButtonClicked,
            enabled = canSignIn,
            shape = RoundedCornerShape(28.dp),
            colors =
              ButtonDefaults.buttonColors(
                backgroundColor = primary,
                contentColor = Color.White,
                disabledBackgroundColor = primary.copy(alpha = 0.35f),
                disabledContentColor = Color.White,
              ),
            elevation = ButtonDefaults.elevation(defaultElevation = 2.dp, pressedElevation = 4.dp),
            modifier =
              Modifier.fillMaxWidth()
                .heightIn(min = 54.dp)
                .bringIntoViewRequester(bringIntoView)
                .testTag(LOGIN_BUTTON_TAG),
          ) {
            if (showProgressBar) {
              CircularProgressIndicator(
                color = Color.White,
                strokeWidth = 2.dp,
                modifier = Modifier.size(22.dp),
              )
            } else {
              Text(
                stringResource(org.smartregister.fhircore.engine.R.string.login_text),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
              )
              Spacer(Modifier.width(8.dp))
              Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
          }
          TextButton(
            onClick = onForgotPassword,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp),
          ) {
            Text(
              stringResource(org.smartregister.fhircore.engine.R.string.forgot_password),
              color = titleColor,
              fontWeight = FontWeight.Medium,
            )
          }
        }
      }

      Text(
        versionText,
        color = Muted,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 20.dp).testTag(LOGIN_FOOTER),
      )
    }
  }
}

/** Two overlapping waves along the bottom edge in the brand colours. */
@Composable
private fun LoginWaves(primary: Color, accent: Color, modifier: Modifier) {
  Canvas(modifier) {
    val w = size.width
    val h = size.height
    val back =
      Path().apply {
        moveTo(0f, h * 0.55f)
        cubicTo(w * 0.35f, h * 0.15f, w * 0.65f, h * 0.75f, w, h * 0.30f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
      }
    val front =
      Path().apply {
        moveTo(0f, h * 0.85f)
        cubicTo(w * 0.40f, h * 0.45f, w * 0.70f, h * 1.0f, w, h * 0.55f)
        lineTo(w, h)
        lineTo(0f, h)
        close()
      }
    drawPath(back, primary)
    drawPath(front, accent)
  }
}
