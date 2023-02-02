/*
 * Copyright (C) 2020 Square, Inc.
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

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A flow that never emits anything. */
fun neverFlow(): Flow<Nothing> = flow { awaitCancellation() }

/**
 * Given a library entry point, ensure that the preceding stack frame is
 * the expected call site.
 *
 * Only works on the JVM.
 */
expect fun assertCallSitePresentInStackTraceOnJvm(
  throwable: Throwable,
  entryPoint: String,
  callSite: String,
)
