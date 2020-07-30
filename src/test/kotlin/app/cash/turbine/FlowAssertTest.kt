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

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Test
import kotlin.time.Duration
import kotlin.time.days
import kotlin.time.milliseconds
import kotlin.time.seconds

class FlowAssertTest {
  @Test fun unconsumedItemThrows() = suspendTest {
    assertThrows<AssertionError> {
      flowOf("item!").test { }
    }.hasMessageThat().isEqualTo("Expected no more events but found Item(item!)")
  }

  @Test fun unconsumedCompleteThrows() = suspendTest {
    assertThrows<AssertionError> {
      emptyFlow<Nothing>().test { }
    }.hasMessageThat().isEqualTo("Expected no more events but found Complete")
  }

  @Test fun unconsumedErrorThrows() = suspendTest {
    val expected = RuntimeException()
    assertThrows<AssertionError> {
      flow<Nothing> { throw expected }.test { }
    }.apply {
      hasMessageThat().isEqualTo("Expected no more events but found Error(RuntimeException)")
      // Coroutine nonsense means our actual exception gets bumped down to the second cause.
      hasCauseThat().hasCauseThat().isSameInstanceAs(expected)
    }
  }

  @Test fun timeoutEnforcedByDefault() = suspendTest {
    val subject = async {
      neverFlow().test {
        expectComplete()
      }
    }

    advanceTimeBy(999.milliseconds)
    assertThat(subject.isActive).isTrue()

    advanceTimeBy(1.milliseconds)
    assertThat(subject.isActive).isFalse()

    assertThrows<TimeoutCancellationException> {
      subject.await()
    }.hasMessageThat().isEqualTo("Timed out waiting for 1000 ms")
  }

  @Test fun timeoutEnforcedCustomValue() = suspendTest {
    val subject = async {
      neverFlow().test(timeout = 10.seconds) {
        expectComplete()
      }
    }

    advanceTimeBy(9999.milliseconds)
    assertThat(subject.isActive).isTrue()

    advanceTimeBy(1.milliseconds)
    assertThat(subject.isActive).isFalse()

    assertThrows<TimeoutCancellationException> {
      subject.await()
    }.hasMessageThat().isEqualTo("Timed out waiting for 10000 ms")
  }

  @Test fun timeoutCanBeZero() = suspendTest {
    val subject = async {
      neverFlow().test(timeout = Duration.ZERO) {
        expectComplete()
      }
    }

    advanceTimeBy(10.days)
    assertThat(subject.isActive).isTrue()

    subject.cancel()
  }
}
