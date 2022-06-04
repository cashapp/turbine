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

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch


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

/**
 * Terminal flow operator that collects events from given flow and returns a [FlowTurbine] for
 * consuming and asserting properties on them in order. If any exception occurs during validation the
 * exception is rethrown from this method.
 *
 * ```kotlin
 * val turbine = flowOf("one", "two").testIn(this)
 * assertEquals("one", turbine.expectItem())
 * assertEquals("two", turbine.expectItem())
 * turbine.expectComplete()
 * ```
 *
 * Unlike [test] which automatically cancels the flow at the end of the lambda, the returned
 * [FlowTurbine] must either consume a terminal event (complete or error) or be explicitly canceled.
 */
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
  val events = Channel<Event<T>>(Channel.UNLIMITED)

  val collectJob = scope.launch(start = CoroutineStart.UNDISPATCHED, context = Dispatchers.Unconfined) {
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
