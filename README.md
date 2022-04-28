# Turbine

Turbine is a small testing library for kotlinx.coroutines
[`Flow`](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-flow/).

```kotlin
flowOf("one", "two").test {
  assertEquals("one", awaitItem())
  assertEquals("two", awaitItem())
  awaitComplete()
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
  testImplementation 'app.cash.turbine:turbine:0.7.0'
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
  testImplementation 'app.cash.turbine:turbine:0.8.0-SNAPSHOT'
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

### Consuming Events

Inside the `test` block you must consume all received events from the flow. Failing to consume all
events will fail your test.

```kotlin
flowOf("one", "two").test {
  assertEquals("one", awaitItem())
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
  assertEquals("one", awaitItem())
  assertEquals("two", awaitItem())
  awaitComplete()
}
```

Received events can be explicitly ignored, however.

```kotlin
flowOf("one", "two").test {
  assertEquals("one", awaitItem())
  cancelAndIgnoreRemainingEvents()
}
```

Additionally, we can receive the most recent emitted item and ignore the previous ones.

```kotlin
flowOf("one", "two", "three")
  .map {
    delay(100)
    it
  }
  .test {
    // 0 - 100ms -> no emission yet
    // 100ms - 200ms -> "one" is emitted
    // 200ms - 300ms -> "two" is emitted
    // 300ms - 400ms -> "three" is emitted
    delay(250)
    assertEquals("two", expectMostRecentItem())
    cancelAndIgnoreRemainingEvents()
  }
```

### Consuming Errors

Unlike `collect`, a flow which causes an exception will still be exposed as an event that you
must consume.

```kotlin
flow { throw RuntimeException("broken!") }.test {
  assertEquals("broken!", awaitError().message)
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

### Asynchronous Flows

Calls to `awaitItem()`, `awaitComplete()`, and `awaitError()` are suspending and will wait
for events from asynchronous flows.

```kotlin
channelFlow {
  withContext(IO) {
    Thread.sleep(100)
    send("item")
  }
}.test {
  assertEquals("item", awaitItem())
  awaitComplete()
}
```

Asynchronous flows can be canceled at any time so long as you have consumed all emitted events.
Allowing the `test` lambda to complete will implicitly cancel the flow.

```kotlin
channelFlow {
  withContext(IO) {
    repeat(10) {
      Thread.sleep(200)
      send("item $it")
    }
  }
}.test {
  assertEquals("item 0", awaitItem())
  assertEquals("item 1", awaitItem())
  assertEquals("item 2", awaitItem())
}
```

Flows can also be explicitly canceled at any point.

```kotlin
channelFlow {
  withContext(IO) {
    repeat(10) {
      Thread.sleep(200)
      send("item $it")
    }
  }
}.test {
  Thread.sleep(700)
  cancel()

  assertEquals("item 0", awaitItem())
  assertEquals("item 1", awaitItem())
  assertEquals("item 2", awaitItem())
}
```

### Hot Flows

Emissions to hot flows that don't have active consumers are dropped. Call `test` on a flow _before_
emitting or the item will be missed.

```kotlin
val mutableSharedFlow = MutableSharedFlow<Int>(replay = 0)
mutableSharedFlow.emit(1)
mutableSharedFlow.test {
  assertEquals(awaitItem(), 1)
  cancelAndConsumeRemainingEvents()
}
```
```
kotlinx.coroutines.test.UncompletedCoroutinesError: After waiting for 60000 ms, the test coroutine is not completing, there were active child jobs: [ScopeCoroutine{Completing}@478db956]
	at app//kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTestCoroutine$3$3.invokeSuspend(TestBuilders.kt:304)
	at ???(Coroutine boundary.?(?)
	at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt.runTestCoroutine(TestBuilders.kt:288)
	at kotlinx.coroutines.test.TestBuildersKt__TestBuildersKt$runTest$1$1.invokeSuspend(TestBuilders.kt:167)
	at kotlinx.coroutines.test.TestBuildersJvmKt$createTestResult$1.invokeSuspend(TestBuildersJvm.kt:13)
```

Proper usage of Turbine with hot flows looks like the following.

```kotlin
val mutableSharedFlow = MutableSharedFlow<Int>(replay = 0)
mutableSharedFlow.test {
  mutableSharedFlow.emit(1)
  assertEquals(awaitItem(), 1)
  cancelAndConsumeRemainingEvents()
}
```

The hot flow types Kotlin currently provides are:
* `MutableStateFlow`
* `StateFlow`
* `MutableSharedFlow`
* `SharedFlow`
* Channels converted to flow with `Channel.consumeAsFlow`

### Multiple turbines

Multiple flows can be tested at once using the `testIn` function which returns the turbine test
object which would otherwise be used as a lambda receiver in the `test` function.

```kotlin
runTest {
  val turbine1 = flowOf(1).testIn(this)
  val turbine2 = flowOf(2).testIn(this)
  assertEquals(1, turbine1.awaitItem())
  assertEquals(2, turbine2.awaitItem())
  turbine1.awaitComplete()
  turbine2.awaitComplete()
}
```

Unconsumed events will throw an exception when the scope ends.

```kotlin
runTest {
  val turbine1 = flowOf(1).testIn(this)
  val turbine2 = flowOf(2).testIn(this)
  assertEquals(1, turbine1.awaitItem())
  assertEquals(2, turbine2.awaitItem())
  turbine1.awaitComplete()
  // turbine2.awaitComplete()   <-- NEWLY COMMENTED OUT
}
```
```
kotlinx.coroutines.CompletionHandlerException: Exception in completion handler InvokeOnCompletion@6d167f58[job@3403e2ac] for TestScope[test started]
	at app//kotlinx.coroutines.JobSupport.completeStateFinalization(JobSupport.kt:320)
	at app//kotlinx.coroutines.JobSupport.tryFinalizeSimpleState(JobSupport.kt:295)
	... 70 more
Caused by: app.cash.turbine.AssertionError: Unconsumed events found:
 - Complete
	at app//app.cash.turbine.ChannelBasedFlowTurbine.ensureAllEventsConsumed(FlowTurbine.kt:333)
	at app//app.cash.turbine.FlowTurbineKt$testIn$1.invoke(FlowTurbine.kt:115)
	at app//app.cash.turbine.FlowTurbineKt$testIn$1.invoke(FlowTurbine.kt:112)
	at app//kotlinx.coroutines.InvokeOnCompletion.invoke(JobSupport.kt:1391)
	at app//kotlinx.coroutines.JobSupport.completeStateFinalization(JobSupport.kt:318)
	... 72 more
```

Unlike the `test` lambda, flows are not automatically canceled. Long-running asynchronous or
infinite flows must be explicitly canceled.

```kotlin
runTest {
  val state = MutableStateFlow(1)
  val turbine = state.testIn(this)
  assertEquals(1, turbine.awaitItem())
  state.emit(2)
  assertEquals(2, turbine.awaitItem())
  turbine.cancel()
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
