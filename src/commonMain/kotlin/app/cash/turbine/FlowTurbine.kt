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
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

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
 */
@Deprecated("Timeout parameter removed. Use runTest which has a timeout or wrap in withTimeout.",
  ReplaceWith("this.test(validate)"),
  DeprecationLevel.ERROR,
)
@Suppress("UNUSED_PARAMETER")
public suspend fun <T> Flow<T>.test(
  timeoutMs: Long,
  validate: suspend FlowTurbine<T>.() -> Unit
) {
  test(validate)
}

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
 */
@Deprecated("Timeout parameter removed. Use runTest which has a timeout or wrap in withTimeout.",
  ReplaceWith("this.test(validate)"),
  DeprecationLevel.ERROR,
)
@Suppress("UNUSED_PARAMETER")
public suspend fun <T> Flow<T>.test(
  timeout: Duration = 1.seconds,
  validate: suspend FlowTurbine<T>.() -> Unit
) {
  test(validate)
}

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
 */
public suspend fun <T> Flow<T>.test(
  validate: suspend FlowTurbine<T>.() -> Unit
) {
  coroutineScope {
    collectTurbineIn(this).apply {
      validate()
      cancel()
      ensureAllEventsConsumed()
    }
  }
}

public fun <T> Flow<T>.testIn(scope: CoroutineScope): FlowTurbine<T> {
  val turbine = collectTurbineIn(scope)

  scope.coroutineContext.job.invokeOnCompletion { exception ->
    if (debug) println("Scope ending ${exception ?: ""}")

    // Only validate events were consumed if the scope is exiting normally.
    if (exception == null) {
      turbine.ensureAllEventsConsumed()
    }
  }

  return turbine
}

private fun <T> Flow<T>.collectTurbineIn(scope: CoroutineScope): ChannelBasedFlowTurbine<T> {
  val events = Channel<Event<T>>(UNLIMITED)

  val collectJob = scope.launch(start = UNDISPATCHED, context = Unconfined) {
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

      if (debug) println("Collect closing event channel")
      events.close()
    }
  }

  return ChannelBasedFlowTurbine(events, collectJob)
}

/**
 * Represents active collection on a source [Flow] which buffers item emissions, completion,
 * and/or errors as events for consuming.
 */
public interface FlowTurbine<T> {
  /**
   * Cancel collecting events from the source [Flow]. Any events which have already been received
   * will still need consumed using the "await" functions.
   */
  public suspend fun cancel()

  /**
   * Cancel collecting events from the source [Flow] and ignore any events which have already
   * been received. Calling this function will exit the [test] block.
   */
  public suspend fun cancelAndIgnoreRemainingEvents()

  /**
   * Cancel collecting events from the source [Flow]. Any events which have already been received
   * will be returned.
   */
  public suspend fun cancelAndConsumeRemainingEvents(): List<Event<T>>

  /**
   * Assert that there are no unconsumed events which have already been received.
   *
   * @throws AssertionError if unconsumed events are found.
   */
  public fun expectNoEvents()

  /**
   * Returns the most recent item that has already been received.
   * If a complete event has been received with no item being received
   * previously, this function will throw an [AssertionError]. If an error event
   * has been received, this function will throw the underlying exception.
   *
   * @throws AssertionError if no item was emitted.
   */
  public fun expectMostRecentItem(): T

  /**
   * Assert that an event was received and return it.
   * This function will suspend if no events have been received.
   */
  public suspend fun awaitEvent(): Event<T>

  /**
   * Assert that the next event received was an item and return it.
   * This function will suspend if no events have been received.
   *
   * @throws AssertionError if the next event was completion or an error.
   */
  public suspend fun awaitItem(): T

  /**
   * Assert that [count] item events were received and ignore them.
   * This function will suspend if no events have been received.
   *
   * @throws AssertionError if one of the events was completion or an error.
   */
  public suspend fun skipItems(count: Int)

  /**
   * Assert that the next event received was the flow completing.
   * This function will suspend if no events have been received.
   *
   * @throws AssertionError if the next event was an item or an error.
   */
  public suspend fun awaitComplete()

  /**
   * Assert that the next event received was an error terminating the flow.
   * This function will suspend if no events have been received.
   *
   * @throws AssertionError if the next event was an item or completion.
   */
  public suspend fun awaitError(): Throwable
}

public sealed class Event<out T> {
  public object Complete : Event<Nothing>() {
    override fun toString(): String = "Complete"
  }
  public data class Error(val throwable: Throwable) : Event<Nothing>() {
    override fun toString(): String = "Error(${throwable::class.simpleName})"
  }
  public data class Item<T>(val value: T) : Event<T>() {
    override fun toString(): String = "Item($value)"
  }
}

private class ChannelBasedFlowTurbine<T>(
  private val events: Channel<Event<T>>,
  private val collectJob: Job,
) : FlowTurbine<T> {
  private var ignoreRemainingEvents = false

  override suspend fun cancel() {
    collectJob.cancelAndJoin()
  }

  override suspend fun cancelAndIgnoreRemainingEvents() {
    cancel()
    ignoreRemainingEvents = true
  }

  override suspend fun cancelAndConsumeRemainingEvents(): List<Event<T>> {
    cancel()
    return buildList {
      while (true) {
        this += events.tryReceive().getOrNull() ?: break
      }
    }
  }

  override fun expectNoEvents() {
    val event = events.tryReceive().getOrNull()
    if (event != null) {
      unexpectedEvent(event, "no events")
    }
  }

  override suspend fun awaitEvent(): Event<T> {
    return events.receive()
  }

  override suspend fun awaitItem(): T {
    val event = awaitEvent()
    if (event !is Event.Item<T>) {
      unexpectedEvent(event, "item")
    }
    return event.value
  }

  override suspend fun skipItems(count: Int) {
    repeat(count) { index ->
      when (val event = awaitEvent()) {
        Event.Complete, is Event.Error -> {
          val cause = (event as? Event.Error)?.throwable
          throw AssertionError("Expected $count items but got $index items and $event", cause)
        }
        is Event.Item<T> -> { /* Success */ }
      }
    }
  }

  override suspend fun awaitComplete() {
    val event = awaitEvent()
    if (event != Event.Complete) {
      unexpectedEvent(event, "complete")
    }
  }

  override suspend fun awaitError(): Throwable {
    val event = awaitEvent()
    if (event !is Event.Error) {
      unexpectedEvent(event, "error")
    }
    return event.throwable
  }

  override fun expectMostRecentItem(): T {
    var recentItem: Event.Item<T>? = null
    while (true) {
      when (val event = events.tryReceive().getOrNull()) {
        null -> break
        Event.Complete -> break
        is Event.Error -> throw event.throwable
        is Event.Item -> recentItem = event
      }
    }

    return if (recentItem != null) {
      recentItem.value
    } else {
      throw AssertionError("No item was found")
    }
  }

  private fun unexpectedEvent(event: Event<*>, expected: String): Nothing {
    val cause = (event as? Event.Error)?.throwable
    throw AssertionError("Expected $expected but found $event", cause)
  }

  fun ensureAllEventsConsumed() {
    if (ignoreRemainingEvents) return

    val unconsumed = mutableListOf<Event<T>>()
    var cause: Throwable? = null
    while (true) {
      val event = events.tryReceive().getOrNull() ?: break
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

