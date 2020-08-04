# Turbine

Turbine is a small testing library for kotlinx.coroutines
[`Flow`](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-flow/).

```kotlin
flowOf("one", "two").test {
  assertEquals("one", expectItem())
  assertEquals("two", expectItem())
  expectComplete()
}
```

> A turbine is a rotary mechanical device that extracts energy from a fluid flow and converts it into useful work.
>
> – [Wikipedia](https://en.wikipedia.org/wiki/Turbine)

## Download

```groovy
repositories {
  mavenCentral()
}
dependencies {
  testImplementation 'app.cash.turbine:turbine:0.1.1'
}
```

<details>
<summary>Snapshots of the development version are available in Sonatype's snapshots repository.</summary>
<p>

```groovy
repositories {
  maven {
    url 'https://oss.sonatype.org/content/repositories/snapshots/'
  }
}
dependencies {
  testImplementation 'app.cash.turbine:turbine:0.2.0-SNAPSHOT'
}
```

</p>
</details>

## Usage

The entrypoint for the library is the `test` extension for `Flow<T>` which accepts a validation
block. Like `collect`, `test` is a suspending function that will not return until the flow is
complete or canceled.

```kotlin
someFlow.test {
  // Validation code here!
}
```

#### Consuming Events

Inside the `test` block you must consume all received events from the flow. Failing to consume all
events will fail your test.

```kotlin
flowOf("one", "two").test {
  assertEquals("one", expectItem())
}
```
```
Exception in thread "main" AssertionError:
  Unconsumed events found:
   - Item(two)
   - Complete
```

As the exception indicates, consuming the `"two"` item is not enough. The complete event must
also be consumed.

```kotlin
flowOf("one", "two").test {
  assertEquals("one", expectItem())
  assertEquals("two", expectItem())
  expectComplete()
}
```

Received events can be explicitly ignored, however.

```kotlin
flowOf("one", "two").test {
  assertEquals("one", expectItem())
  cancelAndIgnoreRemainingEvents()
}
```

#### Consuming Errors

Unlike `collect`, a flow which causes an exception will still be exposed as an event that you
must consume.

```kotlin
flow { throw RuntimeException("broken!") }.test {
  assertEquals("broken!", expectError().message)
}
```

Failure to consume an error will result in the same unconsumed event exception as above, but
with the exception added as the cause so that the full stacktrace is available.

```kotlin
flow { throw RuntimeException("broken!") }.test { }
```
```
java.lang.AssertionError: Unconsumed events found:
 - Error(RuntimeException)
    at app.cash.turbine.ChannelBasedFlowTurbine.ensureAllEventsConsumed(FlowTurbine.kt:240)
    ... 53 more
Caused by: java.lang.RuntimeException: broken!
    at example.MainKt$main$1.invokeSuspend(Main.kt:7)
    ... 32 more
```

#### Asynchronous Flows

Calls to `expectItem()`, `expectComplete()`, and `expectError()` are suspending and will wait
for events from asynchronous flows.

```kotlin
channelFlow {
  withContext(IO) {
    Thread.sleep(100)
    send("item")
  }
}.test {
  assertEquals("item", expectItem())
  expectComplete()
}
```

By default, when one of the "expect" methods suspends waiting for an event it will timeout after
one second.

```kotlin
channelFlow {
  withContext(IO) {
    Thread.sleep(2_000)
    send("item")
  }
}.test {
  assertEquals("item", expectItem())
  expectComplete()
}
```
```
Exception in thread "main" TimeoutCancellationException: Timed out waiting for 1000 ms
```

A longer timeout can be specified as an argument to `test`.

```kotlin
channelFlow {
  withContext(IO) {
    Thread.sleep(2_000)
    send("item")
  }
}.test(timeout = 3.seconds) {
  assertEquals("item", expectItem())
  expectComplete()
}
```

Asynchronous flows can be canceled at any time and will not require consuming a complete or
error event.

```kotlin
channelFlow {
  withContext(IO) {
    repeat(10) {
      Thread.sleep(200)
      send("item $it")
    }
  }
}.test {
  assertEquals("item 0", expectItem())
  assertEquals("item 1", expectItem())
  assertEquals("item 2", expectItem())
  cancel()
}
```

## Experimental API Usage

Turbine uses Kotlin experimental APIs:

 * `Duration` is used to declare the event timeout.
 * `launch(start=UNDISPATCHED)` is used to ensure we start collecting events from the `Flow` before
   invoking the test lambda.

Since the library targets test code, the impact and risk of any breaking changes to these APIs are
minimal and would likely only require a version bump.

Instead of sprinkling the experimental annotations or `@OptIn` all over your tests, opt-in at the
compiler level.

```groovy
compileTestKotlin {
  kotlinOptions {
    freeCompilerArgs += [
        '-Xopt-in=kotlin.time.ExperimentalTime',
        '-Xopt-in=kotlinx.coroutines.ExperimentalCoroutinesApi',
    ]
  }
}
```

For multiplatform projects:

```groovy
kotlin {
  sourceSets.matching { it.name.endsWith("Test") }.all {
    it.languageSettings {
      useExperimentalAnnotation('kotlin.time.ExperimentalTime')
      useExperimentalAnnotation('kotlinx.coroutines.ExperimentalCoroutinesApi')
    }
  }
}
```


# License

    Copyright 2018 Square, Inc.

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
