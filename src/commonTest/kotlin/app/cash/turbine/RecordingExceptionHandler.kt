package app.cash.turbine

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler

class RecordingExceptionHandler : CoroutineExceptionHandler {
  val exceptions = ArrayDeque<Throwable>()

  override val key get() = CoroutineExceptionHandler.Key

  override fun handleException(context: CoroutineContext, exception: Throwable) {
    exceptions += exception
  }
}
