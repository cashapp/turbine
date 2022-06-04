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

@Suppress("LeakingThis")
public sealed class Event<out T> {
  public object Complete : Event<Nothing>() {
    override fun toString(): String = "Complete"
  }
  public data class Error(val throwable: Throwable) : Event<Nothing>() {
    override fun toString(): String = "Error(${throwable::class.simpleName})"
  }
  public data class Item<T>(val value: T) : Event<T>() {
    override fun toString(): String = "Item($value)"
  }

  public val isTerminal: Boolean = this is Complete || this is Error
}
