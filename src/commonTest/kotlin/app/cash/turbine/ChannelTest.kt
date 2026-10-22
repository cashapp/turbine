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
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class ChannelTest {
  @Test
  fun receiveChannelAsReceiveTurbineDelegatesAndPreservesIdentity() = runTest {
    val channel = channelOf(1)
    var cancelled = false
    val arbitraryChannel =
      object : ReceiveChannel<Int> by channel {
        override fun cancel(cause: CancellationException?) {
          cancelled = true
          channel.cancel(cause)
        }
      }
    val turbine = arbitraryChannel.asReceiveTurbine()

    assertSame(arbitraryChannel, turbine.asChannel())
    assertEquals(1, turbine.awaitItem())
    turbine.awaitComplete()
    turbine.cancel()
    assertTrue(cancelled)
    turbine.ensureAllEventsConsumed()
  }

  @Test
  fun receiveChannelAsReceiveTurbineIncludesNameInFailures() = runTest {
    val expected = CustomThrowable("hello")
    val turbine = channelOf<Nothing>(closeCause = expected).asReceiveTurbine(name = "error channel")

    val actual = assertFailsWith<AssertionError> { turbine.awaitComplete() }

    assertEquals(
      "Expected complete for error channel but found Error(CustomThrowable)",
      actual.message,
    )
    assertSame(expected, actual.cause)
  }

  @Test
  fun receiveChannelAsReceiveTurbineRepeatsTerminalEvent() = runTest {
    val expected = CustomThrowable("hello")
    val turbine = channelOf<Nothing>(closeCause = expected).asReceiveTurbine()

    assertSame(expected, turbine.awaitError())
    assertSame(expected, turbine.awaitError())
    turbine.ensureAllEventsConsumed()
  }

  @Test
  fun receiveChannelAsReceiveTurbineReportsUnconsumedEvents() = runTest {
    val turbine = channelOf("one", "two").asReceiveTurbine(name = "items")
    assertEquals("one", turbine.awaitItem())

    val actual = assertFailsWith<AssertionError> { turbine.ensureAllEventsConsumed() }

    assertEquals(
      """
      |Unconsumed events found for items:
      | - Item(two)
      | - Complete
      """
        .trimMargin(),
      actual.message,
    )
  }

  @Test
  fun receiveChannelAsReceiveTurbineCancelCancelsSuspendedRendezvousSender() = runTest {
    val channel = Channel<Int>()
    val turbine = channel.asReceiveTurbine()
    val sender = launch(start = CoroutineStart.UNDISPATCHED) { channel.send(1) }
    assertTrue(sender.isActive)

    turbine.cancel()

    sender.join()
    assertTrue(sender.isCancelled)
    assertFalse(channel.trySend(3).isSuccess)
    val cancellation = turbine.awaitError()
    assertTrue(cancellation is CancellationException)
    assertSame(cancellation, turbine.awaitError())
    turbine.ensureAllEventsConsumed()
  }

  @Test
  fun receiveChannelAsReceiveTurbineConsumingCancelDoesNotChaseActiveProducer() = runTest {
    val channel = Channel<Int>(capacity = 1)
    val turbine = channel.asReceiveTurbine()
    val producer =
      launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
        var item = 0
        while (true) channel.send(item++)
      }
    assertTrue(producer.isActive)

    val remaining = turbine.cancelAndConsumeRemainingEvents()

    assertEquals(emptyList(), remaining)
    producer.join()
    assertTrue(producer.isCancelled)
    turbine.ensureAllEventsConsumed()
  }

  @Test
  fun receiveChannelAsReceiveTurbineRepeatedConcurrentCancelPreservesCloseCause() = runTest {
    val expected = CustomThrowable("hello")
    val channel = Channel<Int>(UNLIMITED)
    channel.trySend(1).getOrThrow()
    channel.close(expected)
    val turbine = channel.asReceiveTurbine(name = "failed channel")

    val cancellations = List(10) { launch(Dispatchers.Default) { repeat(10) { turbine.cancel() } } }
    cancellations.forEach { it.join() }

    val actual = assertFailsWith<AssertionError> { turbine.ensureAllEventsConsumed() }
    assertEquals(
      "Unconsumed events found for failed channel:\n - Error(CustomThrowable)",
      actual.message,
    )
    assertSame(expected, actual.cause)
  }

  @Test
  fun receiveChannelAsReceiveTurbineConsumingCancelReturnsPreexistingTerminalEvent() = runTest {
    val expected = CancellationException("upstream cancellation")
    val turbine = channelOf(1, 2, closeCause = expected).asReceiveTurbine()

    val remaining = turbine.cancelAndConsumeRemainingEvents()

    assertEquals(listOf(Event.Error(expected)), remaining)
    assertSame(expected, (remaining.single() as? Event.Error)?.throwable)
    turbine.ensureAllEventsConsumed()
  }

  @Test
  fun receiveChannelAsReceiveTurbineCanIgnoreRemainingEvents() = runTest {
    val turbine = channelOf(1, 2, closeCause = CustomThrowable("ignored")).asReceiveTurbine()

    turbine.cancelAndIgnoreRemainingEvents()

    turbine.ensureAllEventsConsumed()
  }

  @Test
  fun exceptionsPropagateWhenExpectMostRecentItem() = runTest {
    val expected = CustomThrowable("hello")

    val actual =
      assertFailsWith<CustomThrowable> {
        val channel = channelOf(1, 2, 3, closeCause = expected)
        channel.expectMostRecentItem()
      }
    assertSame(expected, actual)
  }

  @Test
  fun expectMostRecentItemButNoItemWasFoundThrows() = runTest {
    val actual =
      assertFailsWith<AssertionError> {
        val channel = channelOf<Nothing>()
        channel.expectMostRecentItem()
      }
    assertEquals("No item was found", actual.message)
  }

  @Test
  fun expectMostRecentItem() = runTest {
    val channel = Channel<Int>(UNLIMITED)
    channel.trySend(1)
    channel.trySend(2)

    assertEquals(2, channel.expectMostRecentItem())

    channel.trySend(3)
    channel.trySend(4)
    channel.trySend(5)
    assertEquals(5, channel.expectMostRecentItem())
  }

  @Test
  fun assertNullValuesWithExpectMostRecentItem() = runTest {
    val channel = channelOf(1, 2, null)

    assertEquals(null, channel.expectMostRecentItem())
  }

  @Test
  fun awaitItemsAreSkipped() = runTest {
    val channel = channelOf(1, 2, 3)
    channel.skipItems(2)
    assertEquals(3, channel.awaitItem())
  }

  @Test
  fun skipItemsThrowsOnComplete() = runTest {
    val channel = channelOf(1, 2)
    val message = assertFailsWith<AssertionError> { channel.skipItems(3) }.message
    assertEquals("Expected 3 items but got 2 items and Complete", message)
  }

  @Test
  fun expectErrorOnCompletionBeforeAllItemsWereSkipped() = runTest {
    val channel = channelOf(1)
    assertFailsWith<AssertionError> { channel.skipItems(2) }
  }

  @Test
  fun expectErrorOnErrorReceivedBeforeAllItemsWereSkipped() = runTest {
    val error = CustomThrowable("hello")
    val channel = channelOf(1, closeCause = error)
    val actual = assertFailsWith<AssertionError> { channel.skipItems(2) }
    assertSame(error, actual.cause)
  }

  @Test
  fun expectNoEvents() = runTest {
    val channel = neverChannel()
    channel.expectNoEvents()
    channel.cancel()
  }

  @Test
  fun awaitItemEvent() = runTest {
    val item = Any()
    val channel = channelOf(item)
    val event = channel.awaitEvent()
    assertEquals(Event.Item(item), event)
  }

  @Test
  fun expectCompleteEvent() = runTest {
    val channel = emptyChannel()
    val event = channel.awaitEvent()
    assertEquals(Event.Complete, event)
  }

  @Test
  fun expectErrorEvent() = runTest {
    val exception = CustomThrowable("hello")
    val channel = channelOf<Nothing>(closeCause = exception)
    val event = channel.awaitEvent()
    assertEquals(Event.Error(exception), event)
  }

  @Test
  fun awaitItem() = runTest {
    val item = Any()
    val channel = channelOf(item)
    assertSame(item, channel.awaitItem())
  }

  @Test
  fun awaitItemButWasCloseThrows() = runTest {
    val actual = assertFailsWith<AssertionError> { emptyChannel().awaitItem() }
    assertEquals("Expected item but found Complete", actual.message)
  }

  @Test
  fun awaitItemButWasErrorThrows() = runTest {
    val error = CustomThrowable("hello")
    val actual =
      assertFailsWith<AssertionError> { channelOf<Nothing>(closeCause = error).awaitItem() }
    assertEquals("Expected item but found Error(CustomThrowable)", actual.message)
    assertSame(error, actual.cause)
  }

  @Test fun awaitComplete() = runTest { emptyChannel().awaitComplete() }

  @Test
  fun awaitCompleteButWasItemThrows() = runTest {
    val actual = assertFailsWith<AssertionError> { channelOf("item!").awaitComplete() }
    assertEquals("Expected complete but found Item(item!)", actual.message)
  }

  @Test
  fun awaitCompleteButWasErrorThrows() = runTest {
    val error = CustomThrowable("hello")
    val actual =
      assertFailsWith<AssertionError> { channelOf<Nothing>(closeCause = error).awaitComplete() }
    assertEquals("Expected complete but found Error(CustomThrowable)", actual.message)
    assertSame(error, actual.cause)
  }

  @Test
  fun awaitError() = runTest {
    val error = CustomThrowable("hello")
    val channel = channelOf<Nothing>(closeCause = error)
    assertSame(error, channel.awaitError())
  }

  @Test
  fun awaitErrorButWasItemThrows() = runTest {
    val actual = assertFailsWith<AssertionError> { channelOf("item!").awaitError() }
    assertEquals("Expected error but found Item(item!)", actual.message)
  }

  @Test
  fun awaitErrorButWasCompleteThrows() = runTest {
    val actual = assertFailsWith<AssertionError> { emptyChannel().awaitError() }
    assertEquals("Expected error but found Complete", actual.message)
  }

  @Test
  fun failsOnDefaultTimeout() = runTest {
    val actual = assertFailsWith<AssertionError> { neverChannel().awaitItem() }
    assertEquals("No value produced in 3s", actual.message)
    assertCallSitePresentInStackTraceOnJvm(
      throwable = actual,
      entryPoint = "ChannelKt.awaitItem",
      callSite = "ChannelTest\$failsOnDefaultTimeout",
    )
  }

  @Test
  fun awaitHonorsCoroutineContextTimeoutNoTimeout() = runTest {
    withTurbineTimeout(1500.milliseconds) {
      val job = launch { neverChannel().awaitItem() }

      withContext(Dispatchers.Default) { delay(1100) }
      job.cancel()
    }
  }

  @Test
  fun awaitHonorsCoroutineContextTimeoutTimeout() = runTest {
    val actual =
      assertFailsWith<AssertionError> {
        withTurbineTimeout(10.milliseconds) { neverChannel().awaitItem() }
      }
    assertEquals("No value produced in 10ms", actual.message)
  }

  @Test
  fun negativeTurbineTimeoutThrows() = runTest {
    val actual =
      assertFailsWith<IllegalStateException> { withTurbineTimeout((-10).milliseconds) {} }
    assertEquals("Turbine timeout must be greater than 0: -10ms", actual.message)
  }

  @Test
  fun zeroTurbineTimeoutThrows() = runTest {
    val actual = assertFailsWith<IllegalStateException> { withTurbineTimeout(0.milliseconds) {} }
    assertEquals("Turbine timeout must be greater than 0: 0s", actual.message)
  }

  @Test
  fun takeItem() = withTestScope {
    val item = Any()
    val channel = channelOf(item)
    assertSame(item, channel.takeItem())
  }

  @Test
  fun takeItemButWasCloseThrows() = withTestScope {
    val actual = assertFailsWith<AssertionError> { emptyChannel().takeItem() }
    assertEquals("Expected item but found Complete", actual.message)
  }

  @Test
  fun takeItemButWasErrorThrows() = withTestScope {
    val error = CustomThrowable("hello")
    val actual =
      assertFailsWith<AssertionError> { channelOf<Nothing>(closeCause = error).takeItem() }
    assertEquals("Expected item but found Error(CustomThrowable)", actual.message)
    assertSame(error, actual.cause)
  }

  @Test
  fun expectMostRecentItemButNoItemWasFoundThrowsWithName() = runTest {
    val actual =
      assertFailsWith<AssertionError> { emptyChannel().expectMostRecentItem(name = "empty flow") }
    assertEquals("No item was found for empty flow", actual.message)
  }

  @Test
  fun awaitItemButWasCloseThrowsWithName() = runTest {
    val actual = assertFailsWith<AssertionError> { emptyChannel().awaitItem(name = "closed flow") }
    assertEquals("Expected item for closed flow but found Complete", actual.message)
  }

  @Test
  fun awaitCompleteButWasItemThrowsWithName() = runTest {
    val actual =
      assertFailsWith<AssertionError> { channelOf("item!").awaitComplete(name = "item flow") }
    assertEquals("Expected complete for item flow but found Item(item!)", actual.message)
  }

  @Test
  fun awaitErrorButWasItemThrowsWithName() = runTest {
    val actual =
      assertFailsWith<AssertionError> { channelOf("item!").awaitError(name = "item flow") }
    assertEquals("Expected error for item flow but found Item(item!)", actual.message)
  }

  @Test
  fun awaitHonorsCoroutineContextTimeoutTimeoutWithName() = runTest {
    val actual =
      assertFailsWith<AssertionError> {
        withTurbineTimeout(10.milliseconds) { neverChannel().awaitItem(name = "never flow") }
      }
    assertEquals("No value produced for never flow in 10ms", actual.message)
  }

  @Test
  fun takeItemButWasCloseThrowsWithName() = withTestScope {
    val actual = assertFailsWith<AssertionError> { emptyChannel().takeItem(name = "empty flow") }
    assertEquals("Expected item for empty flow but found Complete", actual.message)
  }

  @Test
  fun skipItemsThrowsOnCompleteWithName() = runTest {
    val channel = channelOf(1, 2)
    val message =
      assertFailsWith<AssertionError> { channel.skipItems(3, name = "two item channel") }.message
    assertEquals("Expected 3 items for two item channel but got 2 items and Complete", message)
  }

  /** Used to run test code with a [TestScope], but still outside a suspending context. */
  private fun withTestScope(block: TestScope.() -> Unit) {
    val job = Job()

    TestScope(job).block()

    job.cancel()
  }
}
