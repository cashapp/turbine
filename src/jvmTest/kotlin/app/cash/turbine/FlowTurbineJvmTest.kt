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
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
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

    advanceTimeBy(Duration.milliseconds(999))
    assertTrue(subject.isActive)

    advanceTimeBy(Duration.milliseconds(1))
    assertFalse(subject.isActive)

    val actual = assertThrows<TimeoutCancellationException> {
      subject.await()
    }
    assertEquals("Timed out waiting for 1000 ms", actual.message)
  }

  @Test fun timeoutEnforcedCustomValue() = jvmSuspendTest {
    val subject = async {
      neverFlow().test(timeout = Duration.seconds(10)) {
        awaitComplete()
      }
    }

    advanceTimeBy(Duration.milliseconds(9999))
    assertTrue(subject.isActive)

    advanceTimeBy(Duration.milliseconds(1))
    assertFalse(subject.isActive)

    val actual = assertThrows<TimeoutCancellationException> {
      subject.await()
    }
    assertEquals("Timed out waiting for 10000 ms", actual.message)
  }

  @Test fun timeoutCanBeZero() = jvmSuspendTest {
    val subject = async {
      neverFlow().test(timeout = Duration.ZERO) {
        awaitComplete()
      }
    }

    advanceTimeBy(Duration.days(10))
    assertTrue(subject.isActive)

    subject.cancel()
  }
}
