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

import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

class TurbineTest {
  @Test
  fun exceptionsPropagateWhenExpectMostRecentItem() = runTest {
    // Use a custom subtype to prevent coroutines from breaking referential equality.
    val expected = object : RuntimeException("hello") {}

    val actual = assertFailsWith<RuntimeException> {
      val channel = Turbine<Int>()

      channel.add(1)
      channel.add(2)
      channel.add(3)

      channel.cancel(expected)

      channel.expectMostRecentItem()
    }
    assertSame(expected, actual)
  }

  @Test
  fun expectMostRecentItemButNoItemWasFoundThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      Turbine<Any>().expectMostRecentItem()
    }
    assertEquals("No item was found", actual.message)
  }

  @Test
  fun expectMostRecentItem() = runTest {
    val onTwoSent = CompletableDeferred<Unit>()
    val onTwoContinue = CompletableDeferred<Unit>()
    val onCompleteSent = CompletableDeferred<Unit>()
    val onCompleteContinue = CompletableDeferred<Unit>()

    val channel = Turbine<Int>()

    launch {
      listOf(1, 2, 3, 4, 5).forEach {
        if (it == 3) {
          onTwoSent.complete(Unit)
          onTwoContinue.await()
        }
        channel.add(it)
      }

      onCompleteSent.complete(Unit)
      onCompleteContinue.await()
    }

    onTwoSent.await()
    assertEquals(2, channel.expectMostRecentItem())
    onTwoContinue.complete(Unit)

    onCompleteSent.await()
    assertEquals(5, channel.expectMostRecentItem())
    onCompleteContinue.complete(Unit)
  }

  @Test
  fun assertNullValuesWithExpectMostRecentItem() = runTest {
    val channel = Turbine<Int?>()

    listOf(1, 2, null).forEach { channel.add(it) }

    assertEquals(null, channel.expectMostRecentItem())
  }

  @Test
  fun awaitItemsAreSkipped() = runTest {
    val channel = Turbine<Int>()
    listOf(1, 2, 3).forEach { channel.add(it) }

    channel.skipItems(2)
    assertEquals(3, channel.awaitItem())
  }

  @Test
  fun expectErrorOnCompletionBeforeAllItemsWereSkipped() = runTest {
    val channel = Turbine<Int>()
    channel.add(1)
    channel.cancel()
    assertFailsWith<AssertionError> {
      channel.skipItems(2)
    }
  }

  @Test
  fun expectErrorOnErrorReceivedBeforeAllItemsWereSkipped() = runTest {
    val error = object : RuntimeException("hello") {}
    val channel = Turbine<Int>()
    channel.add(1)
    channel.cancel(error)
    val actual = assertFailsWith<AssertionError> {
      channel.skipItems(2)
    }
    assertSame(error, actual.cause)
  }

  @Test
  fun expectNoEvents() = runTest {
    Turbine<Any>().expectNoEvents()
  }

  @Test
  fun awaitItemEvent() = runTest {
    val item = Any()
    val channel = Turbine<Any>()
    channel.add(item)
    val event = channel.awaitEvent()
    assertEquals(Event.Item(item), event)
  }

  @Test
  fun expectCompleteEvent() = runTest {
    val channel = Turbine<Any>()
    channel.cancel()
    val event = channel.awaitEvent()
    assertEquals(Event.Complete, event)
  }

  @Test
  fun expectErrorEvent() = runTest {
    val exception = object : RuntimeException("hello") {}
    val channel = Turbine<Any>()
    channel.cancel(exception)
    val event = channel.awaitEvent()
    assertEquals(Event.Error(exception), event)
  }

  @Test
  fun awaitItem() = runTest {
    val item = Any()
    val channel = Turbine<Any>()
    channel.add(item)
    assertSame(item, channel.awaitItem())
  }

  @Test
  fun awaitItemButWasCloseThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<Unit>()
      channel.cancel()
      channel.awaitItem()
    }
    assertEquals("Expected item but found Complete", actual.message)
  }

  @Test
  fun awaitItemButWasErrorThrows() = runTest {
    val error = object : RuntimeException("hello") {}
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<Any>()
      channel.cancel(error)
      channel.awaitItem()
    }
    assertContains(
      listOf("Expected item but found Error(null)", "Expected item but found Error(undefined)"),
      actual.message,
    )
    assertSame(error, actual.cause)
  }

  @Test
  fun awaitComplete() = runTest {
    val channel = Turbine<Any>()
    channel.cancel()
    channel.awaitComplete()
  }

  @Test
  fun awaitCompleteButWasItemThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<String>()
      channel.add("item!")
      channel.awaitComplete()
    }
    assertEquals("Expected complete but found Item(item!)", actual.message)
  }

  @Test
  fun awaitCompleteButWasErrorThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<Any>()
      channel.cancel(RuntimeException())
      channel.awaitComplete()
    }
    assertEquals("Expected complete but found Error(RuntimeException)", actual.message)
  }

  @Test
  fun awaitError() = runTest {
    val error = object : RuntimeException("hello") { }
    val channel = Turbine<Any>()
    channel.cancel(error)
    assertSame(error, channel.awaitError())
  }

  @Test
  fun awaitErrorButWasItemThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<String>()
      channel.add("item!")
      channel.awaitError()
    }
    assertEquals("Expected error but found Item(item!)", actual.message)
  }

  @Test
  fun awaitErrorButWasCompleteThrows() = runTest {
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<Any>()
      channel.cancel()
      channel.awaitError()
    }
    assertEquals("Expected error but found Complete", actual.message)
  }

  @Test
  fun takeItem() = withTestScope {
    val item = Any()
    val channel = Turbine<Any>()
    channel.add(item)
    assertSame(item, channel.takeItem())
  }

  @Test
  fun takeItemButWasCloseThrows() = withTestScope {
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<Any>()
      // JS
      CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
        channel.cancel()
      }

      channel.takeItem()
    }
    assertEquals("Expected item but found Complete", actual.message)
  }

  @Test
  fun takeItemButWasErrorThrows() = withTestScope {
    val error = object : RuntimeException("hello") {}
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<Any>()
      // JS
      CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
        channel.cancel(error)
      }
      channel.takeItem()
    }
    assertContains(
      listOf("Expected item but found Error(null)", "Expected item but found Error(undefined)"),
      actual.message,
    )
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
