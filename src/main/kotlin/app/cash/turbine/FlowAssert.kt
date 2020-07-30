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

@ExperimentalTime
suspend fun <T> Flow<T>.test(
  timeout: Duration = 1.seconds,
  validate: suspend FlowAssert<T>.() -> Unit
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
    val flowAssert = ChannelBasedFlowAssert(events, collectJob, timeout)
    val ensureConsumed = try {
      flowAssert.validate()
      true
    } catch (e: CancellationException) {
      if (e !== ignoreRemainingEventsException) {
        throw e
      }
      false
    }
    if (ensureConsumed) {
      flowAssert.expectNoMoreEvents()
    }
  }
}

interface FlowAssert<T> {
  fun cancel()
  fun cancelAndIgnoreRemainingEvents(): Nothing
  fun expectNoEvents()
  suspend fun expectNoMoreEvents()
  suspend fun expectItem(): T
  suspend fun expectComplete()
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
private class ChannelBasedFlowAssert<T>(
  private val events: Channel<Event<T>>,
  private val collectJob: Job,
  private val timeout: Duration
) : FlowAssert<T> {
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

  override suspend fun expectNoMoreEvents() {
    // TODO get all available
    val event = withTimeout {
      events.receiveOrNull()
    }
    if (event != null) {
      throw unexpectedEvent(event, "no more events")
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
}
