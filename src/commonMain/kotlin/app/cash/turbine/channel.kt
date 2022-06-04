/*
 * Copyright (C) 202 Square, Inc.
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
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ReceiveChannel

/**
 * Returns the most recent item that has already been received.
 * If channel was closed with no item being received
 * previously, this function will throw an [AssertionError]. If channel
 * was closed with an exception, this function will throw the underlying exception.
 *
 * @throws AssertionError if no item was emitted.
 */
public fun <T> ReceiveChannel<T>.expectMostRecentItem(): T {
  var result: ChannelResult<T>? = null
  var prevResult: ChannelResult<T>?
  while (true) {
    prevResult = result
    result = tryReceive()
    result.exceptionOrNull()?.let { throw it }
    if (!result.isSuccess) {
      break
    }
  }

  if (prevResult?.isSuccess == true) return prevResult!!.getOrThrow()

  throw TurbineAssertionError("No item was found", cause = null)
}

/**
 * Assert that there are no unconsumed events which have already been received.
 *
 * A channel in the closed state will always emit either [Event.Complete] or [Event.Error] when read, so
 * [expectNoEvents] will only succeed on an empty [ReceiveChannel] that is not closed.
 *
 * @throws AssertionError if unconsumed events are found.
 */
public fun <T> ReceiveChannel<T>.expectNoEvents() {
  val result = tryReceive()
  if (result.isSuccess || result.isClosed) result.unexpectedResult("no events")
}

/**
 * Assert that an event was received and return it.
 * This function will suspend if no events have been received.
 *
 * This function will always return a terminal event on a closed [ReceiveChannel].
 */
public suspend fun <T> ReceiveChannel<T>.awaitEvent(): Event<T> =
  try {
    Event.Item(receive())
  } catch (e: CancellationException) {
    throw e
  } catch (e: ClosedReceiveChannelException) {
    Event.Complete
  } catch (e: Exception) {
    Event.Error(e)
  }

/**
 * Assert that the next event received was non-null and return it.
 * This function will not suspend, and will throw if invoked in a suspending context.
 *
 * @throws AssertionError if the next event was completion or an error.
 */
public fun <T> ReceiveChannel<T>.takeEvent(): Event<T> {
  return takeEventUnsafe()
    ?: unexpectedEvent(null, "an event")
}

internal fun <T> ReceiveChannel<T>.takeEventUnsafe(): Event<T>? {
  return tryReceive().toEvent()
}

/**
 * Assert that the next event received was an item and return it.
 * This function will not suspend, and will throw if invoked in a suspending context.
 *
 * @throws AssertionError if the next event was completion or an error, or no event.
 */
public fun <T> ReceiveChannel<T>.takeItem(): T {
  val event = takeEvent()
  return (event as? Event.Item)?.value ?: unexpectedEvent(event, "item")
}

/**
 * Assert that the next event received is [Event.Complete].
 * This function will not suspend, and will throw if invoked in a suspending context.
 *
 * @throws AssertionError if the next event was completion or an error.
 */
public fun <T> ReceiveChannel<T>.takeComplete() {
  val event = takeEvent()
  if (event !is Event.Complete) unexpectedEvent(event, "complete")
}

/**
 * Assert that the next event received is [Event.Error], and return the error.
 * This function will not suspend, and will throw if invoked in a suspending context.
 *
 * @throws AssertionError if the next event was completion or an error.
 */
public fun <T> ReceiveChannel<T>.takeError(): Throwable {
  val event = takeEvent()
  return (event as? Event.Error)?.throwable ?: unexpectedEvent(event, "error")
}

/**
 * Assert that the next event received was an item and return it.
 * This function will suspend if no events have been received.
 *
 * @throws AssertionError if the next event was completion or an error.
 */
public suspend fun <T> ReceiveChannel<T>.awaitItem(): T =
  when (val result = awaitEvent()) {
    is Event.Item -> result.value
    else -> unexpectedEvent(result, "item")
  }

/**
 * Assert that [count] item events were received and ignore them.
 * This function will suspend if no events have been received.
 *
 * @throws AssertionError if one of the events was completion or an error.
 */
public suspend fun <T> ReceiveChannel<T>.skipItems(count: Int) {
  repeat(count) { index ->
    when (val event = awaitEvent()) {
      Event.Complete, is Event.Error -> {
        val cause = (event as? Event.Error)?.throwable
        throw TurbineAssertionError("Expected $count items but got $index items and $event", cause)
      }
      is Event.Item<T> -> { /* Success */ }
    }
  }
}

/**
 * Assert that attempting to read from the [ReceiveChannel] yields [ClosedReceiveChannelException], indicating
 * that it was closed without an exception.
 *
 * @throws AssertionError if the next event was an item or an error.
 */
public suspend fun <T> ReceiveChannel<T>.awaitComplete() {
  val event = awaitEvent()
  if (event != Event.Complete) {
    unexpectedEvent(event, "complete")
  }
}

/**
 * Assert that attempting to read from the [ReceiveChannel] yields an exception, indicating
 * that it was closed with an exception.
 *
 * @throws AssertionError if the next event was an item or completion.
 */
public suspend fun  <T> ReceiveChannel<T>.awaitError(): Throwable {
  val event = awaitEvent()
  return (event as? Event.Error)?.throwable
    ?: unexpectedEvent(event, "error")
}

internal fun <T> ChannelResult<T>.toEvent(): Event<T>? {
  val cause = exceptionOrNull()

  return if (isSuccess) Event.Item(getOrThrow())
  else if (cause != null) Event.Error(cause)
  else if (isClosed) Event.Complete
  else null
}
private fun <T> ChannelResult<T>.unexpectedResult(expected: String): Nothing = unexpectedEvent(toEvent(), expected)

private fun unexpectedEvent(event: Event<*>?, expected: String): Nothing {
  val cause = (event as? Event.Error)?.throwable
  val eventAsString = event?.toString() ?: "no items"
  throw TurbineAssertionError("Expected $expected but found $eventAsString", cause)
}
