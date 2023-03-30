# Change Log

## [Unreleased]


## [0.12.2]
### Changed
- The default wall-clock timeout used to wait for an event has been increased from 1s to 3s.

### Fixed
- The exception thrown when no events have been received now includes the original timeout exception as its cause. This ensures the stacktrace contains the caller file and line information.
- Ensure `expectNoEvents()` fails if a completion or exception event occurred.
- Differentiate timeout and cancellation exceptions thrown from Turbine's own event timeout (which are thrown) from those which occur within the flow under test (which are available via `awaitError()`).


## [0.12.1]
### Changed
- Build with Kotlin 1.7.20

### Fixed
- `takeItem()` no longer throws an exception when returning a `null` value.
- `await`-prefixed methods no longer interfere with virtual time control from a `TestScheduler` (such as inside `runTest`).


## [0.12.0]
### Added
- Support specifying a human-readable name for differentiating the failures of multiple Turbines

### Fixed
- Properly catch all `Throwable` subtypes from failures in flows and channels as events.


## [0.11.0]
### Added
- Restore timeout support. By default a 1-second timeout will be enforced when awaiting an event. This can be customized by supplying a `timeout` argument or by using the `withTurbineTimeout` wrapper function. Timeouts will always use wall clock time even when using a virtual time dispatcher.

### Changed
- When `runTest` (or any `TestCoroutineScheduler`) is in use, switch to the `UnconfinedTestScheduler` internally to ensure virtual time remains working.


## [0.10.0]
### Changed
- Remove `ReceiveTurbine.ignoreRemainingEvents` from public API.

### Fixed
- Restore usage of `Unconfined` dispatcher preventing value conflation (as much as possible) so that intermediate values can always be observed.


## [0.9.0]
- `FlowTurbine` is now called `ReceiveTurbine`. This is the consume-only type with which you assert on events it has seen (historically only from a `Flow`).
- New public `Turbine` type implements `ReceiveTurbine` but also allows you write events from a data source. Use this to implement fakes or collect events from non-`Flow` streams.
- Extension functions on `ReceiveChannel` provide `ReceiveTurbine`-like assertion capabilities.
- Support for legacy JS has been removed. Only JS IR is now supported.
- Removed some APIs deprecated in 0.8.x.


## [0.8.0]
### Added
- New `testIn` API allows testing multiple flows without nesting lambdas.
- New `skip(Int)` API can replace one or more calls to `awaitItem()` where the result is not needed.

### Changed
- Removed timeout parameter. The new `runTest` API from kotlinx.coroutines enforces a timeout automatically.
- Documented that flows are implicitly canceled at the end of the `test` lambda. This has been the behavior for a few versions by accident, but now it is explicit and documented.
- Cancel (and friends) are now suspending functions to ensure that non-canceleable coroutines complete and their effects are observed deterministically.


## [0.7.0]
### Changed
- Moved APIs using Kotlin's experimental time to separate extensions. You can now use the library
  without worrying about incompatibilities with Kotlin version or coroutine library version.
- Removed APIs deprecated in 0.6.x.

## [0.6.1]
### Added
- Support Apple silicon targets for native users.

## [0.6.0]
### Added
- `expectMostRecentItem()` function consumes all received items and returns the most recent item.

### Changed
- Functions which may suspend to wait for an event are now prefixed with 'await'.

## [0.5.2]
### Fixed
- Support running on a background thread with Kotlin/Native.

## [0.5.1]
### Added
- Support watchOS 64-bit.

## [0.5.0]
### Changed
- Upgrade to Kotlin 1.5.0.
- Upgrade to kotlinx.coroutines 1.5.0.

## [0.5.0-rc1]
### Changed
- Upgrade to Kotlin 1.5.0.
- Upgrade to kotlinx.coroutines 1.5.0-RC.

## [0.4.1]
### Changed
- Upgrade to kotlinx.coroutines 1.4.3.
- Removed requirement to opt-in to `@ExperimentalCoroutinesApi`.

## [0.4.0]
### Changed
- Upgrade to Kotlin 1.4.30.

## [0.3.0]
### Added
- `cancelAndConsumeRemainingEvents()` cancels the `Flow` and returns any unconsumed events which were already received.
- `expectEvent()` waits for an event (item, complete, or error) and returns it as a sealed type `Event`.

## [0.2.1]
### Added
- Support Javascript IR backend.

## [0.2.0] - 2020-08-17
### Changed
- Upgrade to Kotlin 1.4.

## [0.1.1] - 2020-08-03
### Fixed
- Use the [`Unconfined`](https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-dispatchers/-unconfined.html) dispatcher for the internal flow collection coroutine which should eliminate the need to use `yield()` in tests.

## [0.1.0] - 2020-08-03

Initial release


[Unreleased]: https://github.com/cashapp/turbine/compare/0.12.2...HEAD
[0.12.2]: https://github.com/cashapp/turbine/releases/tag/0.12.2
[0.12.1]: https://github.com/cashapp/turbine/releases/tag/0.12.1
[0.12.0]: https://github.com/cashapp/turbine/releases/tag/0.12.0
[0.11.0]: https://github.com/cashapp/turbine/releases/tag/0.11.0
[0.10.0]: https://github.com/cashapp/turbine/releases/tag/0.10.0
[0.9.0]: https://github.com/cashapp/turbine/releases/tag/0.9.0
[0.8.0]: https://github.com/cashapp/turbine/releases/tag/0.8.0
[0.7.0]: https://github.com/cashapp/turbine/releases/tag/0.7.0
[0.6.1]: https://github.com/cashapp/turbine/releases/tag/0.6.1
[0.6.0]: https://github.com/cashapp/turbine/releases/tag/0.6.0
[0.5.2]: https://github.com/cashapp/turbine/releases/tag/0.5.2
[0.5.1]: https://github.com/cashapp/turbine/releases/tag/0.5.1
[0.5.0]: https://github.com/cashapp/turbine/releases/tag/0.5.0
[0.5.0-rc1]: https://github.com/cashapp/turbine/releases/tag/0.5.0-rc1
[0.4.1]: https://github.com/cashapp/turbine/releases/tag/0.4.1
[0.4.0]: https://github.com/cashapp/turbine/releases/tag/0.4.0
[0.3.0]: https://github.com/cashapp/turbine/releases/tag/0.3.0
[0.2.1]: https://github.com/cashapp/turbine/releases/tag/0.2.1
[0.2.0]: https://github.com/cashapp/turbine/releases/tag/0.2.0
[0.1.1]: https://github.com/cashapp/turbine/releases/tag/0.1.1
[0.1.0]: https://github.com/cashapp/turbine/releases/tag/0.1.0
