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

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.days
import kotlin.time.milliseconds
import kotlin.time.seconds

class FlowTurbineJvmTest {
  @Test fun timeoutEnforcedByDefault() = jvmSuspendTest {
    val subject = async {
      neverFlow().test {
        expectComplete()
      }
    }

    advanceTimeBy(999.milliseconds)
    assertTrue(subject.isActive)

    advanceTimeBy(1.milliseconds)
    assertFalse(subject.isActive)

    val actual = assertThrows<TimeoutCancellationException> {
      subject.await()
    }
    assertEquals("Timed out waiting for 1000 ms", actual.message)
  }

  @Test fun timeoutEnforcedCustomValue() = jvmSuspendTest {
    val subject = async {
      neverFlow().test(timeout = 10.seconds) {
        expectComplete()
      }
    }

    advanceTimeBy(9999.milliseconds)
    assertTrue(subject.isActive)

    advanceTimeBy(1.milliseconds)
    assertFalse(subject.isActive)

    val actual = assertThrows<TimeoutCancellationException> {
      subject.await()
    }
    assertEquals("Timed out waiting for 10000 ms", actual.message)
  }

  @Test fun timeoutCanBeZero() = jvmSuspendTest {
    val subject = async {
      neverFlow().test(timeout = Duration.ZERO) {
        expectComplete()
      }
    }

    advanceTimeBy(10.days)
    assertTrue(subject.isActive)

    subject.cancel()
  }
}
