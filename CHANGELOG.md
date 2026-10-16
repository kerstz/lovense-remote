# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) · [SemVer](https://semver.org/).

## [Unreleased]

## [0.2.1] - 2026-10-16

### Added
- CI on every push/PR (unit tests, lint, release build, generated-docs check).
- R8 minification + resource shrinking for release builds.
- Community files: contributing guide, security policy, issue/PR templates, Dependabot.
- Scan errors now offer a one-tap fix (app settings / Bluetooth settings).
- Unit tests for pattern player and Lovense importer.

### Changed
- Dependencies: Kotlin 2.4.21, Gradle 9.8.1, sshj 0.41.2, slf4j-simple 2.0.20, GitHub Actions bumped.

### Fixed
- Lint error: `ACCESS_COARSE_LOCATION` declared alongside `ACCESS_FINE_LOCATION` (API ≤ 30).
- Compose stability annotations on UI state classes to reduce recomposition.

## [0.2.0]
- 800+ toys from 120+ brands with an adaptive per-toy UI.

## [0.1.1]
- F-Droid release preparation.

## [0.1.0]
- First public release: Lovense control over BLE, patterns, remote control by link.

[Unreleased]: https://github.com/kerstz/lovense-remote/compare/v0.2.1...HEAD
[0.2.1]: https://github.com/kerstz/lovense-remote/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/kerstz/lovense-remote/compare/v0.1.1...v0.2.0
[0.1.1]: https://github.com/kerstz/lovense-remote/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/kerstz/lovense-remote/releases/tag/v0.1.0
