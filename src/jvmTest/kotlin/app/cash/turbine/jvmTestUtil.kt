/*
 * Copyright (C) 2020 Square, Inc.
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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.DelayController
import kotlinx.coroutines.test.TestCoroutineScope
import kotlin.time.Duration
import kotlin.time.milliseconds

actual fun suspendTest(body: suspend CoroutineScope.() -> Unit) {
  runBlocking { body() }
}

fun jvmSuspendTest(body: suspend TestCoroutineScope.() -> Unit) {
  // We don't use runBlockingTest because it always advances time unconditionally.

  val scope = TestCoroutineScope()
  scope.launch {
    scope.body()
  }
  scope.cleanupTestCoroutines()
}

fun DelayController.advanceTimeBy(duration: Duration): Duration {
  return advanceTimeBy(duration.toLongMilliseconds()).milliseconds
}
