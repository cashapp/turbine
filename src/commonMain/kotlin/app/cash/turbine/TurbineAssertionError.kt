/*
 * Copyright (C) 2022 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.turbine

/**
 * A custom [AssertionError] to work around the fact that exceptions with public constructors
 * have referential equality broken by coroutines.
 *
 * See https://github.com/Kotlin/kotlinx.coroutines/blob/5b64a1fcf36cbea6fbe3cf70966f4907a2a5f92f/docs/topics/debugging.md#stacktrace-recovery-machinery
 *
 * TODO Migrate to implementing `CopyThrowable` and returning `null` from `createCopy` once it is stable.
 *  https://github.com/Kotlin/kotlinx.coroutines/issues/2367
 */
internal class TurbineAssertionError private constructor(
  message: String,
  cause: Throwable?,
) : AssertionError(message, cause) {
  companion object {
    operator fun invoke(message: String, cause: Throwable?) = TurbineAssertionError(message, cause)
  }
}
