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

import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout

/**
 * Returns the most recent item that has already been received.
 * If channel was closed with no item being received
 * previously, this function will throw an [AssertionError]. If channel
 * was closed with an exception, this function will throw the underlying exception.
 *
 * @throws AssertionError if no item was emitted.
 */
public fun <T> ReceiveChannel<T>.expectMostRecentItem(name: String? = null): T {
  var previous: ChannelResult<T>? = null
  while (true) {
    val current = tryReceive()
    current.exceptionOrNull()?.let { throw it }
    if (current.isFailure) {
      break
    }
    previous = current
  }

  if (previous?.isSuccess == true) return previous.getOrThrow()

  throw AssertionError("No item was found".qualifiedBy(name))
}

/**
 * Assert that there are no unconsumed events which have already been received.
 *
 * A channel in the closed state will always emit either [Event.Complete] or [Event.Error] when read, so
 * [expectNoEvents] will only succeed on an empty [ReceiveChannel] that is not closed.
 *
 * @throws AssertionError if unconsumed events are found.
 */
public fun <T> ReceiveChannel<T>.expectNoEvents(name: String? = null) {
  tryReceive().toEvent()?.let { unexpectedEvent(name, it, "no events") }
}

/**
 * Assert that an event was received and return it.
 * This function will suspend if no events have been received.
 *
 * This function will always return a terminal event on a closed [ReceiveChannel].
 */
public suspend fun <T> ReceiveChannel<T>.awaitEvent(name: String? = null): Event<T> {
  val timeout = contextTimeout()
  return try {
    withAppropriateTimeout(timeout) {
      receiveCatching().toEvent()!!
    }
  } catch (e: TimeoutCancellationException) {
    throw TurbineAssertionError("No ${"value produced".qualifiedBy(name)} in $timeout", e)
  } catch (e: TurbineTimeoutCancellationException) {
    throw TurbineAssertionError("No ${"value produced".qualifiedBy(name)} in $timeout", e)
  }
}

private suspend fun <T> withAppropriateTimeout(
  timeout: Duration,
  block: suspend CoroutineScope.() -> T,
): T {
  return withTimeout(timeout, block)
}

private suspend fun <T> withWallclockTimeout(
  timeout: Duration,
  block: suspend CoroutineScope.() -> T,
): T = coroutineScope {
  val blockDeferred = async(start = CoroutineStart.UNDISPATCHED, block = block)

  // Run the timeout on a scope separate from the caller. This ensures that the use of the
  // Default dispatcher does not affect the use of a TestScheduler and its fake time.
  @OptIn(DelicateCoroutinesApi::class)
  val timeoutJob = GlobalScope.launch(Dispatchers.Default) { delay(timeout) }

  select {
    blockDeferred.onAwait { result ->
      timeoutJob.cancel()
      result
    }
    timeoutJob.onJoin {
      blockDeferred.cancel()
      throw TurbineTimeoutCancellationException("Timed out waiting for $timeout")
    }
  }
}

internal class TurbineTimeoutCancellationException internal constructor(
  message: String,
) : CancellationException(message)

/**
 * Assert that the next event received was non-null and return it.
 * This function will not suspend. On JVM and Android, it will attempt to throw if invoked in a suspending context.
 *
 * @throws AssertionError if the next event was completion or an error.
 */
public fun <T> ReceiveChannel<T>.takeEvent(name: String? = null): Event<T> {
  assertCallingContextIsNotSuspended()
  return takeEventUnsafe()
    ?: unexpectedEvent(name, null, "an event")
}

internal fun <T> ReceiveChannel<T>.takeEventUnsafe(): Event<T>? {
  return tryReceive().toEvent()
}

/**
 * Assert that the next event received was an item and return it.
 * This function will not suspend. On JVM and Android, it will attempt to throw if invoked in a suspending context.
 *
 * @throws AssertionError if the next event was completion or an error, or no event.
 */
public fun <T> ReceiveChannel<T>.takeItem(name: String? = null): T {
  return when (val event = takeEvent()) {
    is Event.Item -> event.value
    else -> unexpectedEvent(name, event, "item")
  }
}

/**
 * Assert that the next event received is [Event.Complete].
 * This function will not suspend. On JVM and Android, it will attempt to throw if invoked in a suspending context.
 *
 * @throws AssertionError if the next event was completion or an error.
 */
public fun <T> ReceiveChannel<T>.takeComplete(name: String? = null) {
  val event = takeEvent()
  if (event !is Event.Complete) unexpectedEvent(name, event, "complete")
}

/**
 * Assert that the next event received is [Event.Error], and return the error.
 * This function will not suspend. On JVM and Android, it will attempt to throw if invoked in a suspending context.
 *
 * @throws AssertionError if the next event was completion or an error.
 */
public fun <T> ReceiveChannel<T>.takeError(name: String? = null): Throwable {
  val event = takeEvent()
  return (event as? Event.Error)?.throwable ?: unexpectedEvent(name, event, "error")
}

/**
 * Assert that the next event received was an item and return it.
 * This function will suspend if no events have been received.
 *
 * @throws AssertionError if the next event was completion or an error.
 */
public suspend fun <T> ReceiveChannel<T>.awaitItem(name: String? = null): T =
  when (val result = awaitEvent(name = name)) {
    is Event.Item -> result.value
    else -> unexpectedEvent(name, result, "item")
  }

/**
 * Assert that [count] item events were received and ignore them.
 * This function will suspend if no events have been received.
 *
 * @throws AssertionError if one of the events was completion or an error.
 */
public suspend fun <T> ReceiveChannel<T>.skipItems(count: Int, name: String? = null) {
  repeat(count) { index ->
    when (val event = awaitEvent()) {
      Event.Complete, is Event.Error -> {
        val cause = (event as? Event.Error)?.throwable
        throw TurbineAssertionError("Expected $count ${"items".qualifiedBy(name)} but got $index items and $event", cause)
      }
      is Event.Item<T> -> {
        // Success
      }
    }
  }
}

/**
 * Assert that attempting to read from the [ReceiveChannel] yields [ClosedReceiveChannelException], indicating
 * that it was closed without an exception.
 *
 * @throws AssertionError if the next event was an item or an error.
 */
public suspend fun <T> ReceiveChannel<T>.awaitComplete(name: String? = null) {
  val event = awaitEvent()
  if (event != Event.Complete) {
    unexpectedEvent(name, event, "complete")
  }
}

/**
 * Assert that attempting to read from the [ReceiveChannel] yields an exception, indicating
 * that it was closed with an exception.
 *
 * @throws AssertionError if the next event was an item or completion.
 */
public suspend fun <T> ReceiveChannel<T>.awaitError(name: String? = null): Throwable {
  val event = awaitEvent()
  return (event as? Event.Error)?.throwable
    ?: unexpectedEvent(name, event, "error")
}

internal fun <T> ChannelResult<T>.toEvent(): Event<T>? {
  val cause = exceptionOrNull()
  return when {
    isSuccess -> Event.Item(getOrThrow())
    cause != null -> Event.Error(cause)
    isClosed -> Event.Complete
    else -> null
  }
}

private fun unexpectedEvent(name: String?, event: Event<*>?, expected: String): Nothing {
  val cause = (event as? Event.Error)?.throwable
  val eventAsString = event?.toString() ?: "no items"
  throw TurbineAssertionError("Expected ${expected.qualifiedBy(name)} but found $eventAsString", cause)
}

internal fun String.qualifiedBy(name: String?) =
  if (name == null) {
    this
  } else {
    "$this for $name"
  }
