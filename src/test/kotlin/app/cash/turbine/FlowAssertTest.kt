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
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.days
import kotlin.time.milliseconds
import kotlin.time.seconds

class FlowAssertTest {
  @Test fun cancelStopsFlow() = suspendTest {
    val collecting = AtomicBoolean()
    callbackFlow<Nothing> {
      collecting.set(true)
      awaitClose {
        collecting.set(false)
      }
    }.test {
      assertThat(collecting.get()).isTrue()
      cancel()
      assertThat(collecting.get()).isFalse()
    }
  }

  @Test fun expectNoEvents() = suspendTest {
    neverFlow().test {
      expectNoEvents()
      cancel()
    }
  }

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

  @Test fun unconsumedItemCanBeIgnored() = suspendTest {
    flowOf("item!").test {
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun unconsumedCompleteCanBeIgnored() = suspendTest {
    emptyFlow<Nothing>().test {
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun unconsumedErrorCanBeIgnored() = suspendTest {
    flow<Nothing> { throw RuntimeException() }.test {
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun expectItem() = suspendTest {
    val item = Any()
    flowOf(item).test {
      assertThat(expectItem()).isSameInstanceAs(item)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun expectItemButWasCloseThrows() = suspendTest {
    assertThrows<AssertionError> {
      emptyFlow<Unit>().test {
        expectItem()
      }
    }.hasMessageThat().isEqualTo("Expected item but found Complete")
  }

  @Test fun expectItemButWasErrorThrows() = suspendTest {
    val error = RuntimeException()
    assertThrows<AssertionError> {
      flow<Unit> { throw error }.test {
        expectItem()
      }
    }.apply {
      hasMessageThat().isEqualTo("Expected item but found Error(RuntimeException)")
      // Coroutine nonsense means our actual exception gets bumped down to the second cause.
      hasCauseThat().hasCauseThat().isSameInstanceAs(error)
    }
  }

  @Test fun expectComplete() = suspendTest {
    emptyFlow<Nothing>().test {
      expectComplete()
    }
  }

  @Test fun expectCompleteButWasItemThrows() = suspendTest {
    assertThrows<AssertionError> {
      flowOf("item!").test {
        expectComplete()
      }
    }.hasMessageThat().isEqualTo("Expected complete but found Item(item!)")
  }

  @Test fun expectCompleteButWasErrorThrows() = suspendTest {
    emptyFlow<Nothing>().test {
      expectComplete()
    }
  }

  @Test fun expectError() = suspendTest {
    val error = RuntimeException()
    flow<Nothing> { throw error }.test {
      assertThat(expectError()).isSameInstanceAs(error)
    }
  }

  @Test fun expectErrorButWasItemThrows() = suspendTest {
    assertThrows<AssertionError> {
      flowOf("item!").test {
        expectError()
      }
    }.hasMessageThat().isEqualTo("Expected error but found Item(item!)")
  }

  @Test fun expectErrorButWasCompleteThrows() = suspendTest {
    assertThrows<AssertionError> {
      emptyFlow<Nothing>().test {
        expectError()
      }
    }.hasMessageThat().isEqualTo("Expected error but found Complete")
  }

  @Test fun expectWaitsForEvents() = suspendTest {
    val channel = Channel<String>(UNLIMITED)
    val position = AtomicInteger(0)

    // Start undispatched so we suspend inside the test{} block.
    launch(start = UNDISPATCHED) {
      channel.consumeAsFlow().test {
        position.set(1)
        assertThat(expectItem()).isEqualTo("one")
        position.set(2)
        assertThat(expectItem()).isEqualTo("two")
        position.set(3)
        cancel()
      }
    }

    assertThat(position.get()).isEqualTo(1)
    channel.send("one")
    assertThat(position.get()).isEqualTo(2)
    channel.send("two")
    assertThat(position.get()).isEqualTo(3)
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
