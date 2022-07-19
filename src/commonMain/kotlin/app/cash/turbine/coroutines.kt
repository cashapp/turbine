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

/**
 * Invoke this method to throw an error when your method is not being called by a suspend fun.
 *
 * This is usually used to prevent the usage of shared memory to communicate with code under
 * test in coroutines tests. [Communicating with shared memory is a bad idea](https://go.dev/blog/codelab-share).
 *
 * Concrete example:
 *
 * ```
 * fun takeLastScreen(): Screen {
 *   assertCallingContextIsNotSuspended()
 *
 *   return screens.takeValue()
 * }
 *
 * @Test
 * fun myTest() = runBlocking {
 *   assertCallingContextIsNotSuspended() // fine
 *   takeLastScreen() // boom!
 * }
 * ```
 */
public fun assertCallingContextIsNotSuspended() {
  val stackTrace = Exception().stackTraceToString()
  // TODO: support non-JVM
  if (stackTrace.contains("invokeSuspend")) {
    error("Calling context is suspending; use a suspending method instead")
  }
}
