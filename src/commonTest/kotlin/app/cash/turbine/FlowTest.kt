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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class FlowTest {
  @Test fun exceptionsPropagate() = runTest {
    val expected = CustomRuntimeException("hello")

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

  @Test fun cancelAwaitsFlowCompletion() = runTest {
    var collecting = false
    neverFlow()
      .onStart { collecting = true }
      .onCompletion {
        withContext(NonCancellable) {
          yield()
          collecting = false
        }
      }
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
    val error = CustomRuntimeException("hi")
    val actual = assertFailsWith<AssertionError> {
      flow<Unit> { throw error }.test {
        awaitItem()
      }
    }
    assertEquals("Expected item but found Error(CustomRuntimeException)", actual.message)
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
    val error = CustomRuntimeException("hi")
    val actual = assertFailsWith<AssertionError> {
      flow<Nothing> { throw error }.test {
        awaitComplete()
      }
    }
    assertEquals("Expected complete but found Error(CustomRuntimeException)", actual.message)
    assertSame(error, actual.cause)
  }

  @Test fun expectError() = runTest {
    val error = CustomRuntimeException("hi")
    flow<Nothing> { throw error }.test {
      assertSame(error, awaitError())
    }
  }

  @Test fun terminalErrorAfterExpectMostRecentItemThrows() = runTest {
    val error = RuntimeException("hi")
    val throwBarrier = Job()
    val message = assertFailsWith<AssertionError> {
      flow {
        emit("item!")
        throwBarrier.join()
        throw error
      }.test {
        expectMostRecentItem()
        throwBarrier.complete()
      }
    }.message

    assertEquals("""
        |Unconsumed events found:
        | - Error(RuntimeException)
        """.trimMargin(), message)
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
    val exception = CustomRuntimeException("hi")
    flow<Nothing> { throw exception }.test {
      val event = awaitEvent()
      assertEquals(Event.Error(exception), event)
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun expectWaitsForEvents() = runTest {
    val flow = MutableSharedFlow<String>()
    val position = Channel<Int>(RENDEZVOUS)

    // Start undispatched so we suspend inside the test{} block.
    launch(start = UNDISPATCHED, context = Unconfined) {
      flow.test {
        position.send(1)
        assertEquals("one", awaitItem())
        position.send(2)
        assertEquals("two", awaitItem())
        position.send(3)
        cancel()
      }
    }

    assertEquals(1, position.receive())

    flow.emit("one")
    assertEquals(2, position.receive())

    flow.emit("two")
    assertEquals(3, position.receive())
  }

  @Test fun exceptionsPropagateWhenExpectMostRecentItem() = runTest {
    val expected = CustomRuntimeException("hello")

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

  @Test fun valuesDoNotConflate() = runTest {
    val flow = MutableStateFlow(0)
    flow.test {
      flow.value = 1
      flow.value = 2
      flow.value = 3
      assertEquals(0, awaitItem())
      assertEquals(1, awaitItem())
      assertEquals(2, awaitItem())
      assertEquals(3, awaitItem())
    }
  }

  @Test fun assertNullValuesWithExpectMostRecentItem() = runTest {
    flowOf(1, 2, null).test {
      assertEquals(null, expectMostRecentItem())
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun expectItemsAreSkipped() = runTest {
    flowOf(1, 2, 3).test {
      skipItems(2)
      assertEquals(3, awaitItem())
      awaitComplete()
    }
  }

  @Test fun expectErrorOnCompletionBeforeAllItemsWereSkipped() = runTest {
    flowOf(1).test {
      assertFailsWith<AssertionError> {
        skipItems(2)
      }
    }
  }

  @Test fun expectErrorOnErrorReceivedBeforeAllItemsWereSkipped() = runTest {
    val error = CustomRuntimeException("hi")
    flow {
      emit(1)
      throw error
    }.test {
      val actual = assertFailsWith<AssertionError> {
        skipItems(2)
      }
      assertSame(error, actual.cause)
    }
  }

  @OptIn(ExperimentalTime::class)
  @Test fun turbineSkipsDelaysInRunTest() = runTest {
    val took = measureTime {
      flow<Nothing> {
        delay(5.seconds)
      }.test {
        awaitComplete()
      }
    }
    assertTrue(took < 5.seconds, "$took > 5s")
  }

  @Test fun failsOnDefaultTimeout() = runTest {
    neverFlow().test {
      val actual = assertFailsWith<AssertionError> {
        awaitItem()
      }
      assertEquals("No value produced in 1s", actual.message)
    }
  }

  @Test fun awaitHonorsTestTimeoutNoTimeout() = runTest {
    flow<Nothing> {
      withContext(Default) {
        delay(1100.milliseconds)
      }
    }.test(timeout = 1500.milliseconds) {
      awaitComplete()
    }
  }

  @Test fun awaitHonorsCoroutineContextTimeoutTimeout() = runTest {
    neverFlow().test(timeout = 10.milliseconds) {
      val actual = assertFailsWith<AssertionError> {
        awaitItem()
      }
      assertEquals("No value produced in 10ms", actual.message)
    }
  }

  @Test fun negativeTurbineTimeoutThrows() = runTest {
    val actual = assertFailsWith<IllegalStateException> {
      neverFlow().test(timeout = (-10).milliseconds) {
      }
    }
    assertEquals("Turbine timeout must be greater than 0: -10ms", actual.message)
  }

  @Test fun zeroTurbineTimeoutThrows() = runTest {
    val actual = assertFailsWith<IllegalStateException> {
      neverFlow().test(timeout = 0.milliseconds) {
      }
    }
    assertEquals("Turbine timeout must be greater than 0: 0s", actual.message)
  }
}
