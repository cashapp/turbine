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

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import org.junit.Test

class FlowTurbineJvmTest {
  @Test fun timeoutEnforcedByDefault() = jvmSuspendTest {
    val subject = async {
      neverFlow().test {
        awaitComplete()
      }
    }

    advanceTimeBy(999.milliseconds)
    assertTrue(subject.isActive)

    advanceTimeBy(1.milliseconds)
    assertFalse(subject.isActive)

    val actual = assertFailsWith<TimeoutCancellationException> {
      subject.await()
    }
    assertEquals("Timed out waiting for 1000 ms", actual.message)
  }

  @Test fun timeoutEnforcedCustomLong() = jvmSuspendTest {
    val subject = async {
      neverFlow().test(timeoutMs = 10_000) {
        awaitComplete()
      }
    }

    advanceTimeBy(9999.milliseconds)
    assertTrue(subject.isActive)

    advanceTimeBy(1.milliseconds)
    assertFalse(subject.isActive)

    val actual = assertFailsWith<TimeoutCancellationException> {
      subject.await()
    }
    assertEquals("Timed out waiting for 10000 ms", actual.message)
  }

  @Test fun timeoutEnforcedCustomDuration() = jvmSuspendTest {
    val subject = async {
      neverFlow().test(timeout = 10.seconds) {
        awaitComplete()
      }
    }

    advanceTimeBy(9999.milliseconds)
    assertTrue(subject.isActive)

    advanceTimeBy(1.milliseconds)
    assertFalse(subject.isActive)

    val actual = assertFailsWith<TimeoutCancellationException> {
      subject.await()
    }
    assertEquals("Timed out waiting for 10000 ms", actual.message)
  }

  @Test fun timeoutCanBeZero() = jvmSuspendTest {
    val subject = async {
      neverFlow().test(timeoutMs = 0) {
        awaitComplete()
      }
    }

    advanceTimeBy(10.days)
    assertTrue(subject.isActive)

    subject.cancel()
  }
}
