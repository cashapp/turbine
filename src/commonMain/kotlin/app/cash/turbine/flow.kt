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

import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.Dispatchers.Unconfined
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher

public interface TurbineTestContext<T> : ReceiveTurbine<T> {
  public fun <R> Flow<R>.testIn(
    scope: CoroutineScope,
    timeout: Duration? = null,
    name: String? = null,
  ): ReceiveTurbine<R>
}

internal class TurbineTestContextImpl<T>(
  turbine: Turbine<T>,
  turbineContext: CoroutineContext,
) : TurbineTestContext<T>, ReceiveTurbine<T> by turbine {
  private val turbineElements = (turbineContext[TurbineRegistryElement] ?: EmptyCoroutineContext) +
    (turbineContext[TurbineTimeoutElement] ?: EmptyCoroutineContext)
  override fun <R> Flow<R>.testIn(
    scope: CoroutineScope,
    timeout: Duration?,
    name: String?,
  ): ReceiveTurbine<R> =
    testInInternal(
      this@testIn,
      timeout = timeout,
      name = name,
      scope = scope + turbineElements,
    )
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
 *   awaitComplete()
 * }
 * ```
 *
 * @param timeout If non-null, overrides the current Turbine timeout inside [validate]. See also:
 * [withTurbineTimeout].
 */
public suspend fun <T> Flow<T>.test(
  timeout: Duration? = null,
  name: String? = null,
  validate: suspend TurbineTestContext<T>.() -> Unit,
) {
  val turbineRegistry = mutableListOf<ChannelTurbine<*>>()
  reportTurbines(turbineRegistry) {
    coroutineScope {
      collectTurbineIn(this, null, name).apply {
        try {
          val testContext = TurbineTestContextImpl(this@apply, currentCoroutineContext())
          if (timeout != null) {
            withTurbineTimeout(timeout) {
              testContext.validate()
            }
          } else {
            testContext.validate()
          }
          cancel()
          ensureAllEventsConsumed()
        } catch (e: Throwable) {
          // The exception needs to be reraised. However, if there are any unconsumed events
          // from other turbines (including this one), those may indicate an underlying problem.
          // So: create a report with all the registered turbines, and include exception as cause
          val reportsWithUnconsumedEvents = turbineRegistry.map {
            it.reportUnconsumedEvents()
              // The exception will have cancelled its job hierarchy, producing cancellation exceptions
              // in its wake. These aren't meaningful test feedback
              .stripCancellations()
          }
            .filter { it.unconsumed.isNotEmpty() }
          if (reportsWithUnconsumedEvents.isEmpty()) {
            throw e
          } else {
            throw TurbineAssertionError(
              buildString {
                reportsWithUnconsumedEvents.forEach { it.describe(this@buildString) }
              },
              e,
            )
          }
        }
      }
    }
  }
}

/**
 * Terminal flow operator that collects events from given flow and returns a [ReceiveTurbine] for
 * consuming and asserting properties on them in order. If any exception occurs during validation the
 * exception is rethrown from this method.
 *
 * ```kotlin
 * val turbine = flowOf("one", "two").testIn(this)
 * assertEquals("one", turbine.expectItem())
 * assertEquals("two", turbine.expectItem())
 * turbine.awaitComplete()
 * ```
 *
 * Unlike [test] which automatically cancels the flow at the end of the lambda, the returned
 * [ReceiveTurbine] must either consume a terminal event (complete or error) or be explicitly canceled.
 *
 * @param timeout If non-null, overrides the current Turbine timeout for this [Turbine]. See also:
 * [withTurbineTimeout].
 */
public fun <T> Flow<T>.testIn(
  scope: CoroutineScope,
  timeout: Duration? = null,
  name: String? = null,
): ReceiveTurbine<T> {
  return testInInternal(this, timeout, scope, name)
}

private fun <T> testInInternal(flow: Flow<T>, timeout: Duration?, scope: CoroutineScope, name: String?): Turbine<T> {
  if (timeout != null) {
    // Eager check to throw early rather than in a subsequent 'await' call.
    checkTimeout(timeout)
  }

  val turbine = flow.collectTurbineIn(scope, timeout, name)

  scope.coroutineContext.job.invokeOnCompletion { exception ->
    if (debug) println("Scope ending ${exception ?: ""}")

    // Only validate events were consumed if the scope is exiting normally.
    if (exception == null) {
      turbine.ensureAllEventsConsumed()
    }
  }

  return turbine
}

private fun <T> Flow<T>.collectTurbineIn(scope: CoroutineScope, timeout: Duration?, name: String?): Turbine<T> {
  lateinit var channel: Channel<T>

  // Use test-specific unconfined if test scheduler is in use to inherit its virtual time.
  @OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher is still experimental.
  val unconfined = scope.coroutineContext[TestCoroutineScheduler]
    ?.let(::UnconfinedTestDispatcher)
    ?: Unconfined

  val job = scope.launch(unconfined, start = UNDISPATCHED) {
    channel = collectIntoChannel(this)
  }

  return ChannelTurbine(channel, job, timeout, name).also {
    scope.reportTurbine(it)
  }
}

internal fun <T> Flow<T>.collectIntoChannel(scope: CoroutineScope): Channel<T> {
  val output = Channel<T>(UNLIMITED)
  val job = scope.launch(start = UNDISPATCHED) {
    try {
      collect { output.trySend(it) }
      output.close()
    } catch (e: Throwable) {
      output.close(e)
    }
  }

  return object : Channel<T> by output {
    override fun cancel(cause: CancellationException?) {
      job.cancel()
      output.close(cause)
    }

    override fun close(cause: Throwable?): Boolean {
      job.cancel()
      return output.close(cause)
    }
  }
}
