# Contributing

Thanks for helping! The most valuable contribution is **testing a toy on real
hardware** — open a [toy report](https://github.com/kerstz/lovense-remote/issues/new?template=toy_report.yml).

## Setup

```bash
git clone https://github.com/kerstz/lovense-remote
cd lovense-remote
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest :app:lintDebug
```

JDK 17, Android SDK 35.

## Adding or fixing a toy

1. Device data lives in `app/src/main/assets/devices.json`, generated from the
   Buttplug config by `tools/gen_devices.py` — don't hand-edit it.
2. Protocol handlers are in `ble/proto/` and registered in `Registry.kt`.
3. Add a test in `app/src/test/…/ProtocolsTest.kt`.
4. Run `python3 tools/gen_supported.py && python3 tools/gen_site_data.py` to refresh
   `docs/SUPPORTED_TOYS.md` and the website's `docs/toys.json` (CI fails if stale).
5. Untested brands stay marked **experimental** until someone confirms on a real toy.

## Rules

- **100 % FOSS, no network surprise**: no Google services, trackers, analytics
  or non-free libraries (the app ships on F-Droid).
- Strings in `values`, `values-fr` and `values-es`.
- Conventional commits (`feat:`, `fix:`, `docs:`, `ci:`…).
- Security-sensitive code (`remote/`) needs a test.
- Docs: protocol notes in [`docs/dev/`](docs/dev), research in [`docs/research/`](docs/research).

By contributing you agree your work is licensed under GPLv3.
