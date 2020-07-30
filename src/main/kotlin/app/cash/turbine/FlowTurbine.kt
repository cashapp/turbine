/*
 * Copyright (C) 2018 Square, Inc.
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

import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Terminal flow operator that collects events from given flow and allows the [validate] lambda to
 * consume and assert properties on them in order. If any exception occurs during validation the
 * exception is rethrown from this method.
 *
 * ```kotlin
 * flowOf("one", "two").test {
 *   assertEquals("one", expectItem())
 *   assertEquals("two", expectItem())
 *   expectComplete()
 * }
 * ```
 *
 * @param timeout Duration each suspending function on [FlowTurbine] will wait before timing out.
 */
@ExperimentalTime
suspend fun <T> Flow<T>.test(
  timeout: Duration = 1.seconds,
  validate: suspend FlowTurbine<T>.() -> Unit
) {
  coroutineScope {
    val events = Channel<Event<T>>(UNLIMITED)
    val collectJob = launch {
      val terminalEvent = try {
        collect { item ->
          events.send(Event.Item(item))
        }
        Event.Complete
      } catch (_: CancellationException) {
        null
      } catch (t: Throwable) {
        Event.Error(t)
      }
      if (terminalEvent != null) {
        events.send(terminalEvent)
      }
      events.close()
    }
    val flowTurbine = ChannelBasedFlowTurbine(events, collectJob, timeout)
    val ensureConsumed = try {
      flowTurbine.validate()
      true
    } catch (e: CancellationException) {
      if (e !== ignoreRemainingEventsException) {
        throw e
      }
      false
    }
    if (ensureConsumed) {
      collectJob.cancel()
      flowTurbine.ensureAllEventsConsumed()
    }
  }
}

/**
 * Represents active collection on a source [Flow] which buffers item emissions, completion,
 * and/or errors as events for consuming.
 */
interface FlowTurbine<T> {
  /**
   * Duration that [expectItem], [expectComplete], and [expectError] will wait for an event before
   * throwing a timeout exception.
   */
  @ExperimentalTime
  val timeout: Duration

  /**
   * Cancel collecting events from the source [Flow]. Any events which have already been received
   * will still need consumed using the "expect" functions.
   */
  fun cancel()

  /**
   * Cancel collecting events from the source [Flow] and ignore any events which have already
   * been received. Calling this function will exit the [test] block.
   */
  fun cancelAndIgnoreRemainingEvents(): Nothing

  /**
   * Assert that there are no unconsumed events which have been received.
   *
   * @throws AssertionError if unconsumed events are found.
   */
  fun expectNoEvents()

  /**
   * Assert that the next event received was an item and return it.
   * If no events have been received, this function will suspend for up to [timeout].
   *
   * @throws AssertionError if the next event was completion or an error.
   * @throws TimeoutCancellationException if no event was received in time.
   */
  suspend fun expectItem(): T

  /**
   * Assert that the next event received was the flow completing.
   * If no events have been received, this function will suspend for up to [timeout].
   *
   * @throws AssertionError if the next event was an item or an error.
   * @throws TimeoutCancellationException if no event was received in time.
   */
  suspend fun expectComplete()

  /**
   * Assert that the next event received was an error terminating the flow.
   * If no events have been received, this function will suspend for up to [timeout].
   *
   * @throws AssertionError if the next event was an item or completion.
   * @throws TimeoutCancellationException if no event was received in time.
   */
  suspend fun expectError(): Throwable
}

private val ignoreRemainingEventsException = CancellationException("Ignore remaining events")

private sealed class Event<out T> {
  object Complete : Event<Nothing>() {
    override fun toString() = "Complete"
  }
  data class Error(val throwable: Throwable) : Event<Nothing>() {
    override fun toString() = "Error(${throwable.javaClass.simpleName})"
  }
  data class Item<T>(val value: T) : Event<T>() {
    override fun toString() = "Item($value)"
  }
}

@ExperimentalTime
private class ChannelBasedFlowTurbine<T>(
  private val events: Channel<Event<T>>,
  private val collectJob: Job,
  override val timeout: Duration
) : FlowTurbine<T> {
  private suspend fun <T> withTimeout(body: suspend () -> T): T {
    return if (timeout == Duration.ZERO) {
      body()
    } else {
      withTimeout(timeout) {
        body()
      }
    }
  }

  override fun cancel() {
    collectJob.cancel()
  }

  override fun cancelAndIgnoreRemainingEvents(): Nothing {
    cancel()
    throw ignoreRemainingEventsException
  }

  override fun expectNoEvents() {
    val event = events.poll()
    if (event != null) {
      throw unexpectedEvent(event, "no events")
    }
  }

  override suspend fun expectItem(): T {
    val event = withTimeout {
      events.receive()
    }
    if (event !is Event.Item<T>) {
      throw unexpectedEvent(event, "item")
    }
    return event.value
  }

  override suspend fun expectComplete() {
    val event = withTimeout {
      events.receive()
    }
    if (event != Event.Complete) {
      throw unexpectedEvent(event, "complete")
    }
  }

  override suspend fun expectError(): Throwable {
    val event = withTimeout {
      events.receive()
    }
    if (event !is Event.Error) {
      throw unexpectedEvent(event, "error")
    }
    return event.throwable
  }

  private fun unexpectedEvent(event: Event<*>, expected: String): AssertionError {
    val error = AssertionError("Expected $expected but found $event")
    if (event is Event.Error) {
      error.initCause(event.throwable)
    }
    return error
  }

  fun ensureAllEventsConsumed() {
    val unconsumed = mutableListOf<Event<T>>()
    var cause: Throwable? = null
    while (true) {
      val event = events.poll() ?: break
      unconsumed += event
      if (event is Event.Error) {
        check(cause == null)
        cause = event.throwable
      }
    }
    if (unconsumed.isNotEmpty()) {
      throw AssertionError(
        buildString {
          append("Unconsumed events found:")
          for (event in unconsumed) {
            append("\n - $event")
          }
        },
        cause
      )
    }
  }
}
