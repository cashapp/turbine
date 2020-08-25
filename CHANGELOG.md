# Change Log

## [Unreleased]


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


[Unreleased]: https://github.com/cashapp/turbine/compare/0.2.1...HEAD
[0.2.1]: https://github.com/cashapp/turbine/releases/tag/0.2.1
[0.2.0]: https://github.com/cashapp/turbine/releases/tag/0.2.0
[0.1.1]: https://github.com/cashapp/turbine/releases/tag/0.1.1
[0.1.0]: https://github.com/cashapp/turbine/releases/tag/0.1.0
