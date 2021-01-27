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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.ExperimentalTime
import kotlin.time.seconds

private const val debug = false

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
@ExperimentalTime // For timeout.
@ExperimentalCoroutinesApi // For start=UNDISPATCHED
suspend fun <T> Flow<T>.test(
  timeout: Duration = 1.seconds,
  context: CoroutineContext = Unconfined,
  validate: suspend FlowTurbine<T>.() -> Unit
) {
  coroutineScope {
    val events = Channel<Event<T>>(UNLIMITED)

    val collectJob = launch(start = UNDISPATCHED, context = context) {
      val terminalEvent = try {
        if (debug) println("Collect starting!")
        collect { item ->
          if (debug) println("Collect got: $item")
          events.send(Event.Item(item))
        }

        if (debug) println("Collect complete!")
        Event.Complete
      } catch (_: CancellationException) {
        if (debug) println("Collect canceled!")
        null
      } catch (t: Throwable) {
        if (debug) println("Collect error! $t")
        Event.Error(t)
      }

      if (terminalEvent != null) {
        events.send(terminalEvent)
      }

      if (debug) println("Collect closing event channel")
      events.close()
    }

    val flowTurbine = ChannelBasedFlowTurbine(events, collectJob, timeout)
    val ensureConsumed = try {
      flowTurbine.validate()
      if (debug) println("Validate lambda returning normally")

      true
    } catch (e: CancellationException) {
      if (e !== ignoreRemainingEventsException) {
        if (debug) println("Validate ignoring remaining events")
        throw e
      }

      if (debug) println("Validate canceling $e")
      false
    }

    flowTurbine.cancel()

    if (ensureConsumed) {
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
  suspend fun cancel()

  /**
   * Cancel collecting events from the source [Flow] and ignore any events which have already
   * been received. Calling this function will exit the [test] block.
   */
  suspend fun cancelAndIgnoreRemainingEvents(): Nothing

  /**
   * Cancel collecting events from the source [Flow]. Any events which have already been received
   * will be returned.
   */
  suspend fun cancelAndConsumeRemainingEvents(): List<Event<T>>

  /**
   * Assert that there are no unconsumed events which have been received.
   *
   * @throws AssertionError if unconsumed events are found.
   */
  fun expectNoEvents()

  /**
   * Assert that an event was received and return it.
   * If no events have been received, this function will suspend for up to [timeout].
   *
   * @throws TimeoutCancellationException if no event was received in time.
   */
  suspend fun expectEvent(): Event<T>

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

sealed class Event<out T> {
  object Complete : Event<Nothing>() {
    override fun toString() = "Complete"
  }

  data class Error(val throwable: Throwable) : Event<Nothing>() {
    override fun toString() = "Error(${throwable::class.simpleName})"
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

  override suspend fun cancel() {
    collectJob.cancel()
  }

  override suspend fun cancelAndIgnoreRemainingEvents(): Nothing {
    cancel()
    throw ignoreRemainingEventsException
  }

  override suspend fun cancelAndConsumeRemainingEvents(): List<Event<T>> {
    cancel()
    return mutableListOf<Event<T>>().apply {
      while (true) {
        this += events.poll() ?: break
      }
    }
  }

  override fun expectNoEvents() {
    val event = events.poll()
    if (event != null) {
      unexpectedEvent(event, "no events")
    }
  }

  override suspend fun expectEvent(): Event<T> {
    return withTimeout {
      events.receive()
    }
  }

  override suspend fun expectItem(): T {
    val event = expectEvent()
    if (event !is Event.Item<T>) {
      unexpectedEvent(event, "item")
    }
    return event.value
  }

  override suspend fun expectComplete() {
    val event = expectEvent()
    if (event != Event.Complete) {
      unexpectedEvent(event, "complete")
    }
  }

  override suspend fun expectError(): Throwable {
    val event = expectEvent()
    if (event !is Event.Error) {
      unexpectedEvent(event, "error")
    }
    return event.throwable
  }

  private fun unexpectedEvent(event: Event<*>, expected: String): Nothing {
    val cause = (event as? Event.Error)?.throwable
    throw AssertionError("Expected $expected but found $event", cause)
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
    if (debug) println("Unconsumed events: $unconsumed")
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

/**
 * A plain [AssertionError] working around three bugs:
 *
 *  1. No two-arg constructor in common (https://youtrack.jetbrains.com/issue/KT-40728).
 *  2. No two-arg constructor in Java 6.
 *  3. Public exceptions with public constructors have referential equality broken by coroutines.
 */
private class AssertionError(
  message: String,
  override val cause: Throwable?
) : kotlin.AssertionError(message)
