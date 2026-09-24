# Other brands — BLE protocols (experimental)

Encodings taken from community documentation (the
[Buttplug / Intiface](https://github.com/buttplugio/buttplug) device config).
**Not tested on hardware in this project** → flagged "experimental" in the app.
Implemented in `ble/ToyDriver.kt`, identified at scan time by `ble/ToyProtocols.kt`.

Safety rule: these brands use generic BLE names ("Sync", "Nova"…), so an **exact**
name match or the advertisement of the proprietary service is required; on
connection, if the expected service is missing, the app stops **without writing
anything**.

## We-Vibe (Standard Innovation) — `wevibe`

- Service `f000bb03-0451-4000-b000-000000000000`, TX `f000c000-0451-4000-b000-000000000000`.
- 8-byte frame, 2 motors 0..15 packed into byte 3:
  `0f 03 00 (m1<<4 | m2) 00 03 00 00`; stop: `0f 00 00 00 00 00 00 00`.
- Names: 2 motors `4 Plus`, `4plus`, `classic`, `Sync`, `Nova`, `NovaV2`;
  1 motor `Bloom`, `Ditto`, `Gala`, `Jive`, `Pivot`, `Rave`, `Verge`, `Wish`, `Cougar`.
- Not supported: recent "8-bit" models (Chorus, Melt, Moxie…).

## Vorze — `vorze-sa`

- Service `40ee1111-63ec-4b7f-8ce7-712efd55b90e`, TX `40ee2222-63ec-4b7f-8ce7-712efd55b90e`.
- 3 bytes `[type, action, value]`: type `01` A10 Cyclone SA (`CycSA`),
  `02` UFO SA (`UFOSA`), `06` Bach (`Bach smart`).
- Rotation (action `01`): value = `direction<<7 | speed` (0..99); "⟲" flips bit 7.
- Bach vibration (action `03`): value 0..99.

## Magic Motion v1 — `magic-motion-1`

- Service `78667579-7b48-43db-b8c5-7928a6b0a335`, TX `78667579-a914-49a4-8333-aa3c0cd8fedc`.
- 12-byte frame: `0b ff 04 0a 32 32 00 04 08 <level 0..100> 64 00`.
- Names: `Smart Mini Vibe`, `Flamingo`, `Magic Cell`, `Magic Wand`, `Fugu`.

## Adding a brand

1. A `ToyDriver` (GATT endpoints + `encode(target, last)`), pure and testable.
2. An identification rule in `ToyProtocols.identify`.
3. An encoding test in `ToyProtocolsTest`.
