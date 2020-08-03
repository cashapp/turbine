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

import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.RENDEZVOUS
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FlowTurbineTest {
  @Test fun exceptionsPropagate() = suspendTest {
    // Use a custom subtype to prevent coroutines from breaking referential equality.
    val expected = object : RuntimeException("hello") {}

    val actual = assertThrows<RuntimeException> {
      neverFlow().test {
        throw expected
      }
    }
    assertSame(expected, actual)
  }

  @Test fun cancelStopsFlowCollection() = suspendTest {
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

  @Test fun endOfBlockStopsFlowCollection() = suspendTest {
    var collecting = false
    neverFlow()
      .onStart { collecting = true }
      .onCompletion { collecting = false }
      .test {
        assertTrue(collecting)
      }
    assertFalse(collecting)
  }

  @Test fun exceptionStopsFlowCollection() = suspendTest {
    var collecting = false
    assertThrows<RuntimeException> {
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

  @Test fun ignoreRemainingEventsStopsFlowCollection() = suspendTest {
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

  @Test fun expectNoEvents() = suspendTest {
    neverFlow().test {
      expectNoEvents()
      cancel()
    }
  }

  @Test fun unconsumedItemThrows() = suspendTest {
    val actual = assertThrows<AssertionError> {
      flowOf("item!").test { }
    }
    assertEquals(
      """
      |Unconsumed events found:
      | - Item(item!)
      | - Complete
      """.trimMargin(),
      actual.message
    )
  }

  @Test fun unconsumedCompleteThrows() = suspendTest {
    val actual = assertThrows<AssertionError> {
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

  @Test fun unconsumedErrorThrows() = suspendTest {
    val expected = RuntimeException()
    val actual = assertThrows<AssertionError> {
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

  @Test fun unconsumedItemThrowsWithCancel() = suspendTest {
    val actual = assertThrows<AssertionError> {
      flowOf("one", "two").test {
        // Expect one item to ensure we start collecting and receive both items.
        assertEquals("one", expectItem())
        cancel()
      }
    }
    assertEquals(
      """
      |Unconsumed events found:
      | - Item(two)
      | - Complete
      """.trimMargin(),
      actual.message
    )
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
      assertSame(item, expectItem())
      cancelAndIgnoreRemainingEvents()
    }
  }

  @Test fun expectItemButWasCloseThrows() = suspendTest {
    val actual = assertThrows<AssertionError> {
      emptyFlow<Unit>().test {
        expectItem()
      }
    }
    assertEquals("Expected item but found Complete", actual.message)
  }

  @Test fun expectItemButWasErrorThrows() = suspendTest {
    val error = RuntimeException()
    val actual = assertThrows<AssertionError> {
      flow<Unit> { throw error }.test {
        expectItem()
      }
    }
    assertEquals("Expected item but found Error(RuntimeException)", actual.message)
    assertSame(error, actual.cause)
  }

  @Test fun expectComplete() = suspendTest {
    emptyFlow<Nothing>().test {
      expectComplete()
    }
  }

  @Test fun expectCompleteButWasItemThrows() = suspendTest {
    val actual = assertThrows<AssertionError> {
      flowOf("item!").test {
        expectComplete()
      }
    }
    assertEquals("Expected complete but found Item(item!)", actual.message)
  }

  @Test fun expectCompleteButWasErrorThrows() = suspendTest {
    emptyFlow<Nothing>().test {
      expectComplete()
    }
  }

  @Test fun expectError() = suspendTest {
    val error = RuntimeException()
    flow<Nothing> { throw error }.test {
      assertSame(error, expectError())
    }
  }

  @Test fun expectErrorButWasItemThrows() = suspendTest {
    val actual = assertThrows<AssertionError> {
      flowOf("item!").test {
        expectError()
      }
    }
    assertEquals("Expected error but found Item(item!)", actual.message)
  }

  @Test fun expectErrorButWasCompleteThrows() = suspendTest {
    val actual = assertThrows<AssertionError> {
      emptyFlow<Nothing>().test {
        expectError()
      }
    }
    assertEquals("Expected error but found Complete", actual.message)
  }

  @Test fun expectWaitsForEvents() = suspendTest {
    val channel = Channel<String>(RENDEZVOUS)
    var position = 0

    // Start undispatched so we suspend inside the test{} block.
    launch(start = UNDISPATCHED, context = Unconfined) {
      channel.consumeAsFlow().test {
        position = 1
        assertEquals("one", expectItem())
        position = 2
        assertEquals("two", expectItem())
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
}
