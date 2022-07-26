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

import kotlinx.coroutines.channels.ReceiveChannel

public interface ReceiveTurbine<T> {
  /**
   * Returns the underlying [ReceiveChannel].
   */
  public fun asChannel(): ReceiveChannel<T>

  /**
   * True if remaining events for this test channel have been ignored.
   */
  public val ignoreRemainingEvents: Boolean

  /**
   * Cancel this [ReceiveTurbine].
   *
   * If it is backed by an underlying coroutine (e.g. the coroutine run
   * by [test]), that coroutine will also be cancelled.
   *
   * If [cancel] is invoked before the underlying coroutine or channel has been closed, [ensureAllEventsConsumed]
   * will succeed even if the terminal event is not consumed.
   */
  public suspend fun cancel()

  /**
   * Cancel this [ReceiveTurbine] and ignore any events which have already been received.
   *
   * If it is backed by an underlying coroutine (e.g. the coroutine run
   * by [test]), that coroutine will also be cancelled. If called within a [test] block, the [test] block
   * will exit.
   */
  public suspend fun cancelAndIgnoreRemainingEvents()

  /**
   * Cancel this [ReceiveTurbine] and return any events which have already been received.
   *
   * If it is backed by an underlying coroutine (e.g. the coroutine run
   * by [test]), that coroutine will also be cancelled. If called within a [test] block, the [test] block
   * will exit.
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
   *
   * When this [ReceiveTurbine] is in a terminal state ([Event.Complete] or [Event.Error], this method
   * will yield the same result every time it is called.
   */
  public suspend fun awaitEvent(): Event<T>

  /**
   * Assert that the next event received was an item and return it.
   * This function will suspend if no events have been received.
   *
   * When this [ReceiveTurbine] is in a terminal state ([Event.Complete] or [Event.Error], this method
   * will yield the same result every time it is called.
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
   * When this [ReceiveTurbine] is in a terminal state ([Event.Complete] or [Event.Error], this method
   * will yield the same result every time it is called.
   *
   * @throws AssertionError if the next event was an item or an error.
   */
  public suspend fun awaitComplete()

  /**
   * Assert that the next event received was an error terminating the flow.
   * This function will suspend if no events have been received.
   *
   * When this [ReceiveTurbine] is in a terminal state ([Event.Complete] or [Event.Error], this method
   * will yield the same result every time it is called.
   *
   * @throws AssertionError if the next event was an item or completion.
   */
  public suspend fun awaitError(): Throwable

  /**
   * Assert that all events that have occurred so far on this [ReceiveTurbine] have been consumed.
   *
   * [ensureAllEventsConsumed] will execute the same assertion performed internally by [test] and [testIn]
   * on the completion of the validation block.
   */
  public fun ensureAllEventsConsumed()
}
