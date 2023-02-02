package app.cash.turbine

actual fun assertCallSitePresentInStackTraceOnJvm(
  throwable: Throwable,
  entryPoint: String,
  callSite: String,
) {
  // Do nothing :(
}
