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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class FlowTurbineTest {
  @Test fun exceptionsPropagate() = runTest {
    // Use a custom subtype to prevent coroutines from breaking referential equality.
    val expected = object : RuntimeException("hello") {}

    val actual = assertFailsWith<RuntimeException> {
      neverFlow().test {
        throw expected
      }
    }
    assertSame(expected, actual)
  }

  @Test fun cancelStopsFlowCollection() = runTest {
    var collecting = false
    neverFlow()
      .onStart { collecting = true }
      .onCompletion { collecting = false }
      .test {
        assertTrue(collecting)
        cancel()
        assertFalse(collecting)
      }
  }

  @Test fun endOfBlockStopsFlowCollection() = runTest {
    var collecting = false
    neverFlow()
      .onStart { collecting = true }
      .onCompletion { collecting = false }
      .test {
        assertTrue(collecting)
      }
    assertFalse(collecting)
  }

  @Test fun exceptionStopsFlowCollection() = runTest {
    var collecting = false
    assertFailsWith<RuntimeException> {
      neverFlow()
        .onStart { collecting = true }
        .onCompletion { collecting = false }
        .test {
          assertTrue(collecting)
          throw RuntimeException()
        }
    }
    assertFalse(collecting)
  }

  @Test fun ignoreRemainingEventsStopsFlowCollection() = runTest {
    var collecting = false
    neverFlow()
      .onStart { collecting = true }
      .onCompletion { collecting = false }
      .test {
        assertTrue(collecting)
        cancelAndIgnoreRemainingEvents()
      }
    assertFalse(collecting)
  }

  @Test fun expectNoEvents() = runTest {
    neverFlow().test {
      expectNoEvents()
      cancel()
    }
  }

  @Test fun unconsumedItemThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      flow {
        emit("item!")
        emitAll(neverFlow()) // Avoid emitting complete
      }.test { }
    }
    assertEquals(
      """
      |Unconsumed events found:
      | - Item(item!)
      """.trimMargin(),
      actual.message
    )
  }

  @Test fun unconsumedCompleteThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      emptyFlow<Nothing>().test { }
    }
    assertEquals(
      """
      |Unconsumed events found:
      | - Complete
      """.trimMargin(),
      actual.message
    )
  }

  @Test fun unconsumedErrorThrows() = runTest {
    val expected = RuntimeException()
    val actual = assertFailsWith<AssertionError> {
      flow<Nothing> { throw expected }.test { }
    }
    assertEquals(
        """
        |Unconsumed events found:
        | - Error(RuntimeException)
        """.trimMargin(),
      actual.message
    )
    assertSame(expected, actual.cause)
  }

  @Test fun unconsumedItemThrowsWithCancel() = runTest {
    val actual = assertFailsWith<AssertionError> {
      flow {
        emit("one")
        emit("two")
        emitAll(neverFlow()) // Avoid emitting complete
      }.test {
        // Expect one item to ensure we start collecting and receive both items.
        assertEquals("one", awaitItem())
        cancel()
      }
    }
    assertEquals(
      """
      |Unconsumed events found:
      | - Item(two)
      """.trimMargin(),
      actual.message
    )
  }

  @Test fun unconsumedCompleteThrowsWithCancel() = runTest {
    val actual = assertFailsWith<AssertionError> {
      flowOf("one").test {
        // Expect one item to ensure we start collecting and receive complete.
        assertEquals("one", awaitItem())
        cancel()
      }
    }
    assertEquals(
      """
      |Unconsumed events found:
      | - Complete
      """.trimMargin(),
      actual.message
    )
  }

  @Test fun unconsumedErrorThrowsWithCancel() = runTest {
    val expected = RuntimeException()
    val actual = assertFailsWith<AssertionError> {
      flow {
        emit("one")
        throw expected
      }.test {
        // Expect one item to ensure we start collecting and receive the exception.
        assertEquals("one", awaitItem())
        cancel()
      }
    }
    assertEquals(
      """
      |Unconsumed events found:
      | - Error(RuntimeException)
      """.trimMargin(),
      actual.message
    )
    assertSame(expected, actual.cause)
  }

  @Test fun unconsumedItemReturnedWithConsumingCancel() = runTest {
    flow {
      emit("one")
      emit("two")
      emitAll(neverFlow()) // Avoid emitting complete
    }.test {
      // Expect one item to ensure we start collecting and receive both items.
      assertEquals("one", awaitItem())

      val remaining = cancelAndConsumeRemainingEvents()
      assertEquals(listOf(Event.Item("two")), remaining)
    }
  }

  @Test fun unconsumedCompleteReturnedWithConsumingCancel() = runTest {
    flowOf("one").test {
      // Expect one item to ensure we start collecting and receive complete.
      assertEquals("one", awaitItem())

      val remaining = cancelAndConsumeRemainingEvents()
      assertEquals(listOf(Event.Complete), remaining)
    }
  }

  @Test fun unconsumedErrorReturnedWithConsumingCancel() = runTest {
    val expected = RuntimeException()
    flow {
      emit("one")
      throw expected
    }.test {
      // Expect one item to ensure we start collecting and receive the exception.
      assertEquals("one", awaitItem())

      val remaining = cancelAndConsumeRemainingEvents()
      assertEquals(listOf(Event.Error(expected)), remaining)
    }
  }

  @Test fun unconsumedItemCanBeIgnored() = runTest {
    flowOf("item!").test {
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun unconsumedCompleteCanBeIgnored() = runTest {
    emptyFlow<Nothing>().test {
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun unconsumedErrorCanBeIgnored() = runTest {
    flow<Nothing> { throw RuntimeException() }.test {
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun expectItem() = runTest {
    val item = Any()
    flowOf(item).test {
      assertSame(item, awaitItem())
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun expectItemButWasCloseThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      emptyFlow<Unit>().test {
        awaitItem()
      }
    }
    assertEquals("Expected item but found Complete", actual.message)
  }

  @Test fun expectItemButWasErrorThrows() = runTest {
    val error = RuntimeException()
    val actual = assertFailsWith<AssertionError> {
      flow<Unit> { throw error }.test {
        awaitItem()
      }
    }
    assertEquals("Expected item but found Error(RuntimeException)", actual.message)
    assertSame(error, actual.cause)
  }

  @Test fun expectComplete() = runTest {
    emptyFlow<Nothing>().test {
      awaitComplete()
    }
  }

  @Test fun expectCompleteButWasItemThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      flowOf("item!").test {
        awaitComplete()
      }
    }
    assertEquals("Expected complete but found Item(item!)", actual.message)
  }

  @Test fun expectCompleteButWasErrorThrows() = runTest {
    emptyFlow<Nothing>().test {
      awaitComplete()
    }
  }

  @Test fun expectError() = runTest {
    val error = RuntimeException()
    flow<Nothing> { throw error }.test {
      assertSame(error, awaitError())
    }
  }

  @Test fun expectErrorButWasItemThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      flowOf("item!").test {
        awaitError()
      }
    }
    assertEquals("Expected error but found Item(item!)", actual.message)
  }

  @Test fun expectErrorButWasCompleteThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      emptyFlow<Nothing>().test {
        awaitError()
      }
    }
    assertEquals("Expected error but found Complete", actual.message)
  }

  @Test fun expectItemEvent() = runTest {
    val item = Any()
    flowOf(item).test {
      val event = awaitEvent()
      assertEquals(Event.Item(item), event)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun expectCompleteEvent() = runTest {
    emptyFlow<Nothing>().test {
      val event = awaitEvent()
      assertEquals(Event.Complete, event)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun expectErrorEvent() = runTest {
    val exception = RuntimeException()
    flow<Nothing> { throw exception }.test {
      val event = awaitEvent()
      assertEquals(Event.Error(exception), event)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun expectWaitsForEvents() = runTest {
    val channel = Channel<String>(RENDEZVOUS)
    var position = 0

    // Start undispatched so we suspend inside the test{} block.
    launch(start = UNDISPATCHED, context = Unconfined) {
      channel.consumeAsFlow().test {
        position = 1
        assertEquals("one", awaitItem())
        position = 2
        assertEquals("two", awaitItem())
        position = 3
        cancel()
      }
    }

    assertEquals(1, position)

    channel.send("one")
    assertEquals(2, position)

    channel.send("two")
    assertEquals(3, position)
  }

  @Test fun exceptionsPropagateWhenExpectMostRecentItem() = runTest {
    // Use a custom subtype to prevent coroutines from breaking referential equality.
    val expected = object : RuntimeException("hello") {}

    val actual = assertFailsWith<RuntimeException> {
      flow {
        emit(1)
        emit(2)
        emit(3)
        throw expected
      }.test {
        expectMostRecentItem()
      }
    }
    assertSame(expected, actual)
  }

  @Test fun expectMostRecentItemButNoItemWasFoundThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      emptyFlow<Any>().test {
        expectMostRecentItem()
      }
    }
    assertEquals("No item was found", actual.message)
  }

  @Test fun expectMostRecentItem() = runTest {
    val onTwoSent = CompletableDeferred<Unit>()
    val onTwoContinue = CompletableDeferred<Unit>()
    val onCompleteSent = CompletableDeferred<Unit>()
    val onCompleteContinue = CompletableDeferred<Unit>()

    flowOf(1, 2, 3, 4, 5)
      .map {
        if (it == 3) {
          onTwoSent.complete(Unit)
          onTwoContinue.await()
        }
        it
      }
      .onCompletion {
        onCompleteSent.complete(Unit)
        onCompleteContinue.await()
      }
      .test {
        onTwoSent.await()
        assertEquals(2, expectMostRecentItem())
        onTwoContinue.complete(Unit)

        onCompleteSent.await()
        assertEquals(5, expectMostRecentItem())
        onCompleteContinue.complete(Unit)
        awaitComplete()
      }
  }

  @Test fun assertNullValuesWithExpectMostRecentItem() = runTest {
    flowOf(1, 2, null).test {
      assertEquals(null, expectMostRecentItem())
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun timeoutEnforcedByDefault() = runTest {
    val job = async {
      neverFlow().test {
        awaitComplete()
      }
    }

    advanceTimeBy(999.milliseconds)
    runCurrent()
    assertTrue(job.isActive)
    advanceTimeBy(1.milliseconds)
    runCurrent()
    assertFalse(job.isActive)

    val actual = assertFailsWith<TimeoutCancellationException> {
      job.await()
    }
    assertEquals("Timed out waiting for 1000 ms", actual.message)
  }

  @Test fun timeoutEnforcedCustom() = runTest {
    val job = async {
      neverFlow().test(timeout = 10.seconds) {
        awaitComplete()
      }
    }

    advanceTimeBy(9.seconds + 999.milliseconds)
    runCurrent()
    assertTrue(job.isActive)
    advanceTimeBy(1.milliseconds)
    runCurrent()
    assertFalse(job.isActive)

    val actual = assertFailsWith<TimeoutCancellationException> {
      job.await()
    }
    assertEquals("Timed out waiting for 10000 ms", actual.message)
  }

  @Suppress("DEPRECATION")
  @Test fun timeoutEnforcedCustomLong() = runTest {
    val job = async {
      neverFlow().test(timeoutMs = 10_000) {
        awaitComplete()
      }
    }

    advanceTimeBy(9_999)
    runCurrent()
    assertTrue(job.isActive)
    advanceTimeBy(1)
    runCurrent()
    assertFalse(job.isActive)

    val actual = assertFailsWith<TimeoutCancellationException> {
      job.await()
    }
    assertEquals("Timed out waiting for 10000 ms", actual.message)
  }

  @Test fun timeoutCanBeZero() = runTest {
    val job = async {
      neverFlow().test(timeout = ZERO) {
        awaitComplete()
      }
    }

    advanceTimeBy(10.days)
    runCurrent()
    assertTrue(job.isActive)

    job.cancel()
  }
}
