## What & why

<!-- Short description. Link the issue if any. -->

## Checklist

- [ ] `./gradlew :app:testDebugUnitTest :app:lintDebug` passes
- [ ] New/changed protocol → tested on real hardware, or marked experimental
- [ ] Changed `devices.json`? Re-ran `python3 tools/gen_supported.py`
- [ ] User-facing strings added to `values`, `values-fr` **and** `values-es`
- [ ] No new network call, tracker or non-free dependency
