# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) · [SemVer](https://semver.org/).

## [Unreleased]

### Security
- A refused or kicked controller can no longer reconnect auto-approved with its resume token.
- Toys stop when remote control ends: last approved controller gone or kicked, sharing stopped, expired or locked out. The STOP tile also stops sharing.
- A failed BLE write (including STOP) is retried with back-off; the keepalive re-sends the desired state and never an old non-zero level.
- Lockout is enforced by the server itself: after 5 wrong codes every attempt (right code included) and the page are refused until a new share; the host is told once.
- Resume tokens are single use: each reconnection burns the token and issues a new one, so a token copied in transit (e.g. by the relay) cannot be replayed.
- The `Origin` check also requires the same port: another local server on the phone is a different origin.
- Pending (not yet approved) controllers no longer receive the host state (toy names).
- The control page's fonts are only served behind the secret link; `/font/` is gone, so a LAN scanner can no longer detect the app.
- Controller mode: a resume token is never offered to a different host, and reconnection stops after ~1 minute without an answer.

### Privacy
- Internet sharing through localhost.run is now opt-in, with an explicit consent dialog.
- Lock-screen notification shows a neutral text; screenshots and Recents thumbnails are blocked (FLAG_SECURE).
- New [privacy policy](PRIVACY.md), linked from the READMEs, store descriptions and website.

### Changed
- Release workflow: fails without signing secrets, runs tests and lint, publishes `SHA256SUMS` and a build-provenance attestation; actions pinned by SHA.
- CI workflow: actions pinned by SHA, job timeouts, and an OSV vulnerability scan of the resolved release classpath (lockfile written by `tools/lockfile.init.gradle.kts`).
- `kotlinx-serialization-json` aligned to 1.11.0 (the version Ktor 3.6 already resolves).
- `CHANGE_NETWORK_STATE` declared (foreground-service prerequisite on API 34+).
- F-Droid metadata updated to 0.2.1.
- SECURITY.md documents the threat model and its known limits (cleartext LAN, relay sees traffic, TOFU first connection, custom-scheme deep link); READMEs and privacy policy aligned.

### Added
- Tests: controller mode end to end against the embedded server, trust-on-first-use host-key store, server-side lockout, single-use resume tokens, link-gated fonts, `Origin` port check.
- `.gitleaks.toml` allow-listing the public signing-certificate fingerprint used by F-Droid.

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
