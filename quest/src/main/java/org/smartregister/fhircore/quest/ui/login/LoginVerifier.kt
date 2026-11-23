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

package org.smartregister.fhircore.quest.ui.login

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Lets a flavor check sign-ins against its own account server instead of the OAuth/Keycloak
 * flow. When a flavor binds one, [LoginViewModel] uses it for online sign-in and falls back to
 * the credentials saved on the device when the server cannot be reached.
 */
interface LoginVerifier {
  suspend fun verify(username: String, password: CharArray): LoginVerification
}

sealed interface LoginVerification {
  /** The account exists, is active and the password is right. */
  object Verified : LoginVerification

  /** The server answered and refused the sign-in. */
  object Rejected : LoginVerification

  /** The password is right but this phone may not be used (account linked elsewhere, or blocked). */
  object WrongDevice : LoginVerification

  /** The server could not be reached; offline sign-in may be used. */
  object Unreachable : LoginVerification
}

@Module
@InstallIn(SingletonComponent::class)
abstract class LoginVerifierModule {
  /** No verifier by default; a flavor may bind one with `@Binds`. */
  @BindsOptionalOf abstract fun optionalLoginVerifier(): LoginVerifier
}
