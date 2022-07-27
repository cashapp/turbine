package app.cash.turbine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.measureTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletionHandlerException
import kotlinx.coroutines.Dispatchers.Default
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class FlowInScopeTest {
  @Test fun multipleFlows() = runTest {
    val turbine1 = flowOf(1).testIn(this)
    val turbine2 = flowOf(2).testIn(this)
    assertEquals(1, turbine1.awaitItem())
    assertEquals(2, turbine2.awaitItem())
    turbine1.awaitComplete()
    turbine2.awaitComplete()
  }

  @Test
  fun channelCancellation() = runTest {
    kotlin.runCatching {
      coroutineScope {
        val channel = Channel<Unit>()
        val job = launch {
          for (item in channel) {
            println("got something!")
          }
        }

        channel.cancel()

        println("job join result: ${runCatching { job.join() }}")
        println("job cancelled: ${job.isCancelled}")
      }
    }.let { println("result: $it")}
    kotlin.runCatching {
      coroutineScope {
        val channel = Channel<Unit>()
        val job = launch {
          for (item in channel) {
            println("got something!")
          }
        }

        channel.close(CancellationException("it's me"))

        println("job join result: ${runCatching { job.join() }}")
        println("job cancelled: ${job.isCancelled}")
      }
    }.let { println("result: $it")}

  }

  @Test fun cancelMustBeCalled() = runTest {
    val job = launch {
      coroutineScope {
        neverFlow().testIn(this)
      }
    }
    // Wait on real dispatcher for wall clock time. This almost certainly means we'd wait forever.
    withContext(Default) {
      delay(1.seconds)
    }
    assertTrue(job.isActive)
    job.cancel()
  }

  @Test fun cancelStopsFlowCollection() = runTest {
    var collecting = false
    val turbine = neverFlow()
      .onStart { collecting = true }
      .onCompletion { collecting = false }
      .testIn(this)

    assertTrue(collecting)
    turbine.cancel()
    assertFalse(collecting)
  }

  @Test fun unconsumedItemThrows() = runTest {
    // We have to use an exception handler rather than assertFailsWith because runTest also uses
    // one which defers throwing until its block completes.
    val exceptionHandler = RecordingExceptionHandler()
    withContext(exceptionHandler) {
        flow {
          emit("item!")
          emitAll(neverFlow()) // Avoid emitting complete
        }.testIn(this).cancel()
    }
    val exception = exceptionHandler.exceptions.removeFirst()
    assertTrue(exception is CompletionHandlerException)
    val cause = exception.cause
    assertTrue(cause is AssertionError)
    assertEquals(
      """
      |Unconsumed events found:
      | - Item(item!)
      """.trimMargin(),
      cause.message,
    )
  }

  @Test fun unconsumedCompleteThrows() = runTest {
    // We have to use an exception handler rather than assertFailsWith because runTest also uses
    // one which defers throwing until its block completes.
    val exceptionHandler = RecordingExceptionHandler()
    withContext(exceptionHandler) {
      emptyFlow<Nothing>().testIn(this)
    }
    val exception = exceptionHandler.exceptions.removeFirst()
    assertTrue(exception is CompletionHandlerException)
    val cause = exception.cause
    assertTrue(cause is AssertionError)
    assertEquals(
      """
      |Unconsumed events found:
      | - Complete
      """.trimMargin(),
      cause.message,
    )
  }

  @Test fun unconsumedErrorThrows() = runTest {
    val expected = RuntimeException()
    // We have to use an exception handler rather than assertFailsWith because runTest also uses
    // one which defers throwing until its block completes.
    val exceptionHandler = RecordingExceptionHandler()
    withContext(exceptionHandler) {
        flow<Nothing> { throw expected }.testIn(this)
    }
    val exception = exceptionHandler.exceptions.removeFirst()
    assertTrue(exception is CompletionHandlerException)
    val cause = exception.cause
    assertTrue(cause is AssertionError)
    assertEquals(
      """
      |Unconsumed events found:
      | - Error(RuntimeException)
      """.trimMargin(),
      cause.message,
    )
    assertSame(expected, cause.cause)
  }

  @OptIn(ExperimentalTime::class)
  @Test fun awaitHonorsCoroutineContextTimeout() = runTest {
    val took = measureTime {
      assertFailsWith<AssertionError> {
        coroutineScope {
          val turbine = neverFlow().testIn(this, 1.milliseconds)
          turbine.awaitComplete()
        }
      }
    }
    assertTrue(took < 100.milliseconds, "$took > 100ms")
  }

}
