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
import kotlinx.coroutines.CoroutineStart.UNDISPATCHED
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
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
public suspend fun <T> Flow<T>.test(
  timeout: Duration,
  validate: suspend ReceiveTurbine<T>.() -> Unit,
) {
  test(timeoutMs = timeout.inWholeMilliseconds, validate)
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
  timeoutMs: Long? = null,
  validate: suspend ReceiveTurbine<T>.() -> Unit,
) {
  coroutineScope {
    collectTurbineIn(this, timeoutMs).apply {
      validate()
      cancel()
      ensureAllEventsConsumed()
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
 * turbine.expectComplete()
 * ```
 *
 * Unlike [test] which automatically cancels the flow at the end of the lambda, the returned
 * [ReceiveTurbine] must either consume a terminal event (complete or error) or be explicitly canceled.
 */
public fun <T> Flow<T>.testIn(scope: CoroutineScope, timeoutMs: Long? = null): ReceiveTurbine<T> {
  val turbine = collectTurbineIn(scope, timeoutMs)

  scope.coroutineContext.job.invokeOnCompletion { exception ->
    if (debug) println("Scope ending ${exception ?: ""}")

    // Only validate events were consumed if the scope is exiting normally.
    if (exception == null) {
      turbine.ensureAllEventsConsumed()
    }
  }

  return turbine
}

private fun <T> Flow<T>.collectTurbineIn(scope: CoroutineScope, timeoutMs: Long?): Turbine<T> {
  lateinit var outputBox: Channel<T>

  val job = scope.launch(start = UNDISPATCHED) {

    val block: suspend CoroutineScope.() -> Unit = {
      outputBox = collectIntoChannel(this)
    }
    if (timeoutMs != null) {
      withTurbineTimeout(timeoutMs, block)
    } else {
      block()
    }
  }

  return TurbineImpl(outputBox, job, timeoutMs = null)
}

internal fun <T> Flow<T>.collectIntoChannel(scope: CoroutineScope): Channel<T> {
  val output = Channel<T>(UNLIMITED)
  val job = scope.launch(start = UNDISPATCHED) {
    try {
      collect { output.trySend(it) }
      output.close()
    } catch (e: Exception) {
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
