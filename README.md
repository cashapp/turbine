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

Coming soon.

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
Exception in thread "main" AssertionError: Expected no more events but found Item(two)
```

Consuming the `"two"` item is not enough, you also need to consume the complete event.

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

Flows which result in exceptions can be tested.

```kotlin
flow { throw RuntimeException("broken!") }.test {
  assertEquals("broken!", expectError().message)
}
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
  assertEquals("item0", expectItem())
  assertEquals("item1", expectItem())
  assertEquals("item2", expectItem())
  cancel()
}
```

## Experimental API Usage

Turbine uses Kotlin's experimental time API. Since the library targets test code, the
impact and risk of any breaking changes to the time API are minimal and would likely only
require a version bump.

Instead of sprinkling `@ExperimentalTime` or `@OptIn(ExperimentalTime::class)` all over your tests,
opt-in at the compiler level.

```groovy
compileTestKotlin {
  kotlinOptions {
    freeCompilerArgs += [
        '-Xopt-in=kotlin.time.ExperimentalTime',
    ]
  }
}
```
