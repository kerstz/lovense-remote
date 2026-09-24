# Other brands — how multi-brand support works (experimental)

Everything except Lovense is **experimental**: the protocols come from the
[Buttplug / Intiface](https://github.com/buttplugio/buttplug) community
(BSD-3-Clause, see [`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md)) and
have not been tested on hardware by this project. The full list of models is in
[`docs/SUPPORTED_TOYS.md`](../SUPPORTED_TOYS.md).

## Data: the device database

`app/src/main/assets/devices.json` is generated from Buttplug's
`buttplug-device-config-v5.json` by `tools/gen_devices.py`. For each protocol it
keeps:

- how to **recognise** the toy over BLE: exact names, name prefixes (`Foo*`),
  manufacturer data (company id + byte sequence) and advertised services;
- its **GATT endpoints**, by name (`tx`, `rx`, `command`, `firmware`,
  `whitelist`, `rxblebattery`, `rxblemodel`…);
- the **features** of the default model and of each known variant: output type
  (vibrate, rotate, oscillate, constrict, temperature, led, spray, position,
  hw_position_with_duration) and its value range, plus battery.

`ble/db/DeviceDatabase.kt` loads it and identifies an advertisement in this
order: exact name → name prefix → manufacturer data → advertised service.

## Code: one handler per protocol

`ble/proto/` holds one `ProtocolHandler` per Buttplug protocol (~128), ported
from the Rust implementation of `buttplug_server` 12.0.0:

- a `ProtocolSpec` can **identify** the exact model after connecting (read a
  model characteristic, ask `DeviceType;`…) and **initialise** it (handshakes,
  AES/SHA-256/MD5 key exchanges, firmware unlocks);
- the handler turns one feature value into GATT writes (checksums, CRCs, XOR or
  table-based obfuscation are all in there);
- keepalive, background loops (e.g. devices that need the speed re-sent) and
  battery parsing live in the handler too.

`ToyConnection` runs the session: MTU, service discovery, endpoint resolution,
identify → create (90 s timeout, enough for "press the power button" pairing),
then coalesced writes, keepalive, the stroke generator and battery polling.

## Safety rules

- Generic BLE names are only matched as listed in the database; a toy the app
  can't drive is shown dimmed as "not supported yet" and can't be connected.
- A handler only writes to endpoints its protocol declares; a unit test checks
  this for every model in the database.
- Unknown / unsupported protocols: The Handy, KGoal Boost, Muse, Cueme, SayberX,
  Kiiroo V1, Libo Karen, Twerking Butt.

## Adding or updating brands

1. Download a newer `buttplug_server_device_config` crate and run
   `python3 tools/gen_devices.py <path>/buttplug-device-config-v5.json`.
2. For a new protocol id, port its handler into `ble/proto/` and register it in
   `Protocols` (`ble/proto/Registry.kt`).
3. Add golden-byte tests in `ProtocolsTest`, then regenerate the list with
   `python3 tools/gen_supported.py`.
