package app.cash.turbine

actual fun assertCallSitePresentInStackTraceOnJvm(
  throwable: Throwable,
  entryPoint: String,
  callSite: String,
) {
  val lines = throwable.stackTraceToString().lines()

  val awaitItemIndex = lines.indexOfFirst { entryPoint in it }
  if (awaitItemIndex == -1) {
    throw AssertionError("'$entryPoint' not found in stacktrace\n\n${lines.joinToString("\n")}")
  }

  if (callSite !in lines[awaitItemIndex + 1]) {
    throw AssertionError(
      "Expected '$callSite' immediately precede '$entryPoint', but it did not\n\n${lines.joinToString("\n")}",
    )
  }
}
