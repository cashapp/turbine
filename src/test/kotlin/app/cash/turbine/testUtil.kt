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

import com.google.common.truth.ThrowableSubject
import com.google.common.truth.Truth
import kotlin.time.Duration
import kotlin.time.milliseconds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.DelayController
import kotlinx.coroutines.test.TestCoroutineScope

/** A flow that never emits anything. */
fun neverFlow(): Flow<Nothing> = flow {
  suspendCancellableCoroutine {
    // Do nothing!
  }
}

fun suspendTest(body: suspend TestCoroutineScope.() -> Unit) {
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

inline fun <reified T : Throwable> assertThrows(body: () -> Unit): ThrowableSubject {
  try {
    body()
  } catch (t: Throwable) {
    if (t is T) {
      return Truth.assertThat(t)
    }
    throw t
  }
  throw AssertionError(
    "Expected body to throw ${T::class.java.simpleName} but it completed successfully"
  )
}
