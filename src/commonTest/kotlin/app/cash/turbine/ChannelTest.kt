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

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class ChannelTest {
  @Test
  fun exceptionsPropagateWhenExpectMostRecentItem() = runTest {
    val expected = CustomRuntimeException("hello")

    val actual = assertFailsWith<RuntimeException> {
      val channel = flow {
        emit(1)
        emit(2)
        emit(3)
        throw expected
      }.collectIntoChannel(this)

      channel.expectMostRecentItem()
    }
    assertSame(expected, actual)
  }

  @Test
  fun expectMostRecentItemButNoItemWasFoundThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      val channel = emptyFlow<Any>().collectIntoChannel(this)
      channel.expectMostRecentItem()
    }
    assertEquals("No item was found", actual.message)
  }

  @Test
  fun expectMostRecentItem() = runTest {
    val onTwoSent = CompletableDeferred<Unit>()
    val onTwoContinue = CompletableDeferred<Unit>()
    val onCompleteSent = CompletableDeferred<Unit>()
    val onCompleteContinue = CompletableDeferred<Unit>()

    val channel = flowOf(1, 2, 3, 4, 5)
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
      .collectIntoChannel(this)

    onTwoSent.await()
    assertEquals(2, channel.expectMostRecentItem())
    onTwoContinue.complete(Unit)

    onCompleteSent.await()
    assertEquals(5, channel.expectMostRecentItem())
    onCompleteContinue.complete(Unit)

    channel.cancel()
  }

  @Test
  fun assertNullValuesWithExpectMostRecentItem() = runTest {
    val channel = flowOf(1, 2, null).collectIntoChannel(this)

    assertEquals(null, channel.expectMostRecentItem())
  }

  @Test fun awaitItemsAreSkipped() = runTest {
    val channel = flowOf(1, 2, 3).collectIntoChannel(this)
    channel.skipItems(2)
    assertEquals(3, channel.awaitItem())
  }

  @Test fun expectErrorOnCompletionBeforeAllItemsWereSkipped() = runTest {
    val channel = flowOf(1).collectIntoChannel(this)
    assertFailsWith<AssertionError> {
      channel.skipItems(2)
    }
  }

  @Test fun expectErrorOnErrorReceivedBeforeAllItemsWereSkipped() = runTest {
    val error = CustomRuntimeException("hello")
    val channel = flow {
      emit(1)
      throw error
    }.collectIntoChannel(this)
    val actual = assertFailsWith<AssertionError> {
      channel.skipItems(2)
    }
    assertSame(error, actual.cause)
  }

  @Test fun expectNoEvents() = runTest {
    val channel = neverFlow().collectIntoChannel(this)
    channel.expectNoEvents()
    channel.cancel()
  }

  @Test fun awaitItemEvent() = runTest {
    val item = Any()
    val channel = flowOf(item).collectIntoChannel(this)
    val event = channel.awaitEvent()
    assertEquals(Event.Item(item), event)
  }

  @Test fun expectCompleteEvent() = runTest {
    val channel = emptyFlow<Nothing>().collectIntoChannel(this)
    val event = channel.awaitEvent()
    assertEquals(Event.Complete, event)
  }

  @Test fun expectErrorEvent() = runTest {
    val exception = CustomRuntimeException("hello")
    val channel = flow<Nothing> { throw exception }.collectIntoChannel(this)
    val event = channel.awaitEvent()
    assertEquals(Event.Error(exception), event)
  }

  @Test fun awaitItem() = runTest {
    val item = Any()
    val channel = flowOf(item).collectIntoChannel(this)
    assertSame(item, channel.awaitItem())
  }

  @Test fun awaitItemButWasCloseThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      emptyFlow<Unit>().collectIntoChannel(this).awaitItem()
    }
    assertEquals("Expected item but found Complete", actual.message)
  }

  @Test fun awaitItemButWasErrorThrows() = runTest {
    val error = CustomRuntimeException("hello")
    val actual = assertFailsWith<AssertionError> {
      flow<Unit> { throw error }.collectIntoChannel(this)
        .awaitItem()
    }
    assertEquals("Expected item but found Error(CustomRuntimeException)", actual.message)
    assertSame(error, actual.cause)
  }

  @Test fun awaitComplete() = runTest {
    emptyFlow<Nothing>().collectIntoChannel(this).awaitComplete()
  }

  @Test fun awaitCompleteButWasItemThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      flowOf("item!").collectIntoChannel(this)
        .awaitComplete()
    }
    assertEquals("Expected complete but found Item(item!)", actual.message)
  }

  @Test fun awaitCompleteButWasErrorThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      flow<Unit> { throw RuntimeException() }.collectIntoChannel(this)
      .awaitComplete()
    }
    assertEquals("Expected complete but found Error(RuntimeException)", actual.message)
  }

  @Test fun awaitError() = runTest {
    val error = CustomRuntimeException("hello")
    val channel = flow<Nothing> { throw error }.collectIntoChannel(this)
    assertSame(error, channel.awaitError())
  }

  @Test fun awaitErrorButWasItemThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      flowOf("item!").collectIntoChannel(this).awaitError()
    }
    assertEquals("Expected error but found Item(item!)", actual.message)
  }

  @Test fun awaitErrorButWasCompleteThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      emptyFlow<Nothing>().collectIntoChannel(this).awaitError()
    }
    assertEquals("Expected error but found Complete", actual.message)
  }

  @Test fun failsOnDefaultTimeout() = runTest {
    val message = assertFailsWith<AssertionError> {
      coroutineScope {
        neverFlow().collectIntoChannel(this).awaitItem()
      }
    }.message
    assertEquals("No value produced in 1s", message)
  }

  @Test fun awaitHonorsCoroutineContextTimeoutNoTimeout() = runTest {
    withTurbineTimeout(1500.milliseconds) {
      val job = launch {
        neverFlow().collectIntoChannel(this).awaitItem()
      }

      withContext(Dispatchers.Default) {
        delay(1100)
      }
      job.cancel()
    }
  }

  @Test fun negativeTurbineTimeoutThrows() = runTest {
    val message = assertFailsWith<IllegalStateException> {
      withTurbineTimeout((-10).milliseconds) {

      }
    }.message
    assertEquals("Turbine timeout must be greater than 0.", message)
  }

  @Test fun zeroTurbineTimeoutThrows() = runTest {
    val message = assertFailsWith<IllegalStateException> {
      withTurbineTimeout(0.milliseconds) {

      }
    }.message
    assertEquals("Turbine timeout must be greater than 0.", message)
  }

  @Test fun awaitHonorsCoroutineContextTimeoutTimeout() = runTest {
    val message = assertFailsWith<AssertionError> {
      withTurbineTimeout(10.milliseconds) {
        neverFlow().collectIntoChannel(this).awaitItem()
      }
    }.message
    assertEquals("No value produced in 10ms", message)
  }

  @Test fun takeItem() = withTestScope {
    val item = Any()
    val channel = flowOf(item).collectIntoChannel(this)
    assertSame(item, channel.takeItem())
  }

  @Test fun takeItemButWasCloseThrows() = withTestScope {
    val actual = assertFailsWith<AssertionError> {
      emptyFlow<Unit>().collectIntoChannel(this).takeItem()
    }
    assertEquals("Expected item but found Complete", actual.message)
  }

  @Test fun takeItemButWasErrorThrows() = withTestScope {
    val error = CustomRuntimeException("hello")
    val actual = assertFailsWith<AssertionError> {
      flow<Unit> { throw error }.collectIntoChannel(this)
        .takeItem()
    }
    assertEquals("Expected item but found Error(CustomRuntimeException)", actual.message)
    assertSame(error, actual.cause)
  }

  /**
   * Used to run test code with a [TestScope], but still outside a suspending context.
   */
  private fun withTestScope(block: TestScope.()->Unit) {
    val job = Job()

    TestScope(job).block()

    job.cancel()
  }
}
