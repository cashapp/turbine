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
    val expected = CustomThrowable("hello")

    val actual = assertFailsWith<CustomThrowable> {
      val channel = Turbine<Int>()

      channel.add(1)
      channel.add(2)
      channel.add(3)

      channel.close(expected)

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
    channel.close()
    assertFailsWith<AssertionError> {
      channel.skipItems(2)
    }
  }

  @Test
  fun expectErrorOnErrorReceivedBeforeAllItemsWereSkipped() = runTest {
    val error = CustomThrowable("hello")
    val channel = Turbine<Int>()
    channel.add(1)
    channel.close(error)
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
    channel.close()
    val event = channel.awaitEvent()
    assertEquals(Event.Complete, event)
  }

  @Test
  fun expectErrorEvent() = runTest {
    val exception = CustomThrowable("hello")
    val channel = Turbine<Any>()
    channel.close(exception)
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
      channel.close()
      channel.awaitItem()
    }
    assertEquals("Expected item but found Complete", actual.message)
  }

  @Test
  fun awaitItemButWasErrorThrows() = runTest {
    val error = CustomThrowable("hello")
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<Any>()
      channel.close(error)
      channel.awaitItem()
    }
    assertEquals("Expected item but found Error(CustomThrowable)", actual.message)
    assertSame(error, actual.cause)
  }

  @Test
  fun awaitComplete() = runTest {
    val channel = Turbine<Any>()
    channel.close()
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
      channel.close(RuntimeException())
      channel.awaitComplete()
    }
    assertEquals("Expected complete but found Error(RuntimeException)", actual.message)
  }

  @Test
  fun awaitError() = runTest {
    val error = CustomThrowable("hello")
    val channel = Turbine<Any>()
    channel.close(error)
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
      channel.close()
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
        channel.close()
      }

      channel.takeItem()
    }
    assertEquals("Expected item but found Complete", actual.message)
  }

  @Test
  fun takeItemButWasErrorThrows() = withTestScope {
    val error = CustomThrowable("hello")
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<Any>()
      // JS
      CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
        channel.close(error)
      }
      channel.takeItem()
    }
    assertEquals("Expected item but found Error(CustomThrowable)", actual.message)
    assertSame(error, actual.cause)
  }

  @Test
  fun expectMostRecentItemButNoItemWasFoundThrowsWithName() = runTest {
    val actual = assertFailsWith<AssertionError> {
      Turbine<Any>(name = "empty turbine").expectMostRecentItem()
    }
    assertEquals("No item was found for empty turbine", actual.message)
  }

  @Test
  fun awaitItemButWasCloseThrowsWithName() = runTest {
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<Unit>(name = "closed turbine")
      channel.close()
      channel.awaitItem()
    }
    assertEquals("Expected item for closed turbine but found Complete", actual.message)
  }

  @Test
  fun awaitCompleteButWasItemThrowsWithName() = runTest {
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<String>(name = "item turbine")
      channel.add("item!")
      channel.awaitComplete()
    }
    assertEquals("Expected complete for item turbine but found Item(item!)", actual.message)
  }

  @Test
  fun awaitErrorButWasItemThrowsWithName() = runTest {
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<String>(name = "item turbine")
      channel.add("item!")
      channel.awaitError()
    }
    assertEquals("Expected error for item turbine but found Item(item!)", actual.message)
  }

  @Test
  fun takeItemButWasCloseThrowsWithName() = withTestScope {
    val actual = assertFailsWith<AssertionError> {
      val channel = Turbine<Any>(name = "closed turbine")
      // JS
      CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
        channel.close()
      }

      channel.takeItem()
    }
    assertEquals("Expected item for closed turbine but found Complete", actual.message)
  }

  @Test fun skipItemsThrowsOnCompleteWithName() = runTest {
    val channel = Turbine<Int>(name = "two item channel")
    channel.add(1)
    channel.add(2)
    channel.close()
    val message = assertFailsWith<AssertionError> {
      channel.skipItems(3)
    }.message

    assertEquals("Expected 3 items for two item channel but got 2 items and Complete", message)
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
