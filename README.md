<div align="center">

<img src="docs/logo.svg" width="104" alt="Lovense Remote logo">

# Lovense Remote

**Drive your toys over direct Bluetooth — no app, no account, no cloud.**

Open-source Android remote. Several toys at once, several brands, the UI adapts to each one.

**English** · [Français](README.fr.md)

<p>
  <img src="https://img.shields.io/badge/License-GPLv3-8B6BFF?style=flat-square" alt="GPLv3">
  <img src="https://img.shields.io/badge/Android-8.0%2B-FF5C7A?style=flat-square" alt="Android 8+">
  <img src="https://img.shields.io/badge/100%25-AOSP%20·%20no%20Google-46E0A0?style=flat-square" alt="AOSP">
  <img src="https://img.shields.io/badge/i18n-EN%20·%20FR%20·%20ES-A98BFF?style=flat-square" alt="i18n">
  <img src="https://img.shields.io/github/v/release/kerstz/lovense-remote?style=flat-square&color=FF8BA1" alt="Release">
</p>

<img src="docs/screenshots/01-connection.png" width="232">&nbsp;
<img src="docs/screenshots/02-main.png" width="232">&nbsp;
<img src="docs/screenshots/03-sharing.png" width="232">

</div>

> ⚠️ Experimental community project. **Not affiliated with Lovense.**
>
> 🤖 **Vibe coded** — this app was largely written with an AI coding assistant,
> then reviewed and tested by hand. Read the code (and the Security section)
> before trusting it with anything that matters.

---

## ✨ Features

- **🔵 Direct BLE** — scan, connect and drive toys straight from your phone.
- **🧸 Several toys at once** — connect up to 5 toys, drive each one or **all**
  together; patterns, presets and remote control follow the selected target.
- **🏷 800+ models, 120+ brands** — Lovense (tested) plus **experimental**
  Satisfyer, We-Vibe, Svakom, Lelo, Kiiroo, Magic Motion, JoyHub, Galaku, Hismith,
  Vorze, Foreo, Sistalk and many more — see the
  [**full list of supported toys**](docs/SUPPORTED_TOYS.md).
- **🎚 UI that adapts to each toy** — one control per actuator, built from the
  device database: vibration (one or several motors), rotation with direction
  flip, thrusting, suction / pump, **heating** (on/off or °C), **lights**,
  **lube pump** (momentary *Spray* button) and **strokers** (the app generates
  the strokes). Battery level when the toy reports it.
- **👍 One-hand XY pad** — one thumb drives both motors of two-motor toys.
- **🎛 Patterns** — built-ins, Lovense `.ta` import, a random **Tease** mode, and
  a **record** mode (perform → saved pattern).
- **🔗 Remote by link** — let a partner control your toys from the **same Wi-Fi** or
  **over the internet / 4G**, gated by a secret link, a **6-digit PIN**, your
  approval **per controller**, and auto-expiry (see Security below).
- **🌙 Background** — keeps the link alive when the app is closed, plus a
  quick-settings **STOP** tile.
- **🌍 i18n & themes** — English (default) / French / Spanish, dark & light.
- **🟢 100 % AOSP** — no Google Play Services (GrapheneOS-friendly).

## 🧸 Supported toys

**803 models** across **127 brand families** — the complete, generated list is in
[`docs/SUPPORTED_TOYS.md`](docs/SUPPORTED_TOYS.md) (regenerate it with
`python3 tools/gen_supported.py`).

| | Brands (models) |
|---|---|
| ✅ Tested | **Lovense** (36): Lush, Hush, Edge, Nora, Max, Domi, Ferri, Gemini, Gravity, Solace, Flexer, Tenera, Calor… |
| 🧪 Experimental | JoyHub (159), Galaku (97), Satisfyer (84), Foreo (26), WeVibe (23), Honey Play Box (21), Hismith (20), Svakom (51), Kiiroo (30+), Sexverse (22), Magic Motion (29), Lelo (15), Sistalk MonsterPub (13), Libo (14), Sensee (12), Love Distance (9), Vorze (7), MysteryVibe (8), Vibio, VibCrafter, Fluffer, Motorbunny, OSSM, Lioness, Aneros, Je Joue, Lovehoney, Picobong, Pink Punch… |
| 🚫 Not yet | The Handy, KGoal Boost, Muse, Cueme, SayberX, Kiiroo V1, Libo Karen, Twerking Butt |

Toys with extra functions: 🔥 **22** heating · 💨 **52** suction / pump ·
🎚️ **20** strokers · 💡 **3** lights · 💧 **3** lube pumps.

## 📲 Install

Grab the signed APK from the [latest release](https://github.com/kerstz/lovense-remote/releases/latest),
or build it yourself.

> Heads up: if you use a firewall / network-control app, allow **network access**
> for the app, otherwise link-sharing can't open its socket.

## 🛠 Build

```bash
ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleDebug
```

Kotlin + Jetpack Compose · minSdk 26 · target/compile SDK 35.

<details>
<summary>🧩 Architecture</summary>

- `ble/` — multi-toy BLE layer: scan and identification (`ToyManager`), one
  `ToyConnection` per toy (GATT with named endpoints, serialized operations,
  coalesced writes, keepalive, stroke generator, battery, reconnection).
- `ble/db/` — the device database (`assets/devices.json`, generated from the
  Buttplug device config by `tools/gen_devices.py`): BLE names, manufacturer
  data, services and each model's features.
- `ble/proto/` — one `ProtocolHandler` per protocol (~128), ported from
  Buttplug: handshakes, encryption, checksums and per-feature commands (see
  [`docs/research/lovense-ble-protocol.md`](docs/research/lovense-ble-protocol.md)
  and [`docs/research/other-brands.md`](docs/research/other-brands.md)).
- `RemoteEngine` — process-scoped core (BLE + server + tunnel + state) so control
  survives the Activity / the app being closed.
- `remote/` — embedded HTTP+WS server, web control page
  (`assets/controller.html`), and the internet tunnel (`SshTunnel`,
  via [localhost.run](https://localhost.run)).

</details>

## 🔒 Security & privacy

Remote control is layered so that knowing the link is never enough:

- **Secret link** — 128-bit random session id (SecureRandom), new on every share;
  old links die immediately.
- **6-digit PIN**, sent inside the WebSocket (never in a URL, so never in relay
  logs), compared in constant time. **5 wrong codes burn the session.**
- **Your approval, per controller** — an authenticated controller can't drive
  anything until you accept *that* controller; you can refuse/kick one without
  stopping the share. Accepted controllers get a resume token for network blips.
- **Web hardening** — strict CSP (script pinned by hash), no referrer,
  anti-framing, `Origin` check against cross-site WebSocket hijacking, 256-byte
  frames, rate limiting, max 3 sockets, 10 s to authenticate.
- **Minimal network surface** — listens only on 127.0.0.1 (for the tunnel) and
  the Wi-Fi IP, never on cellular/VPN interfaces. Auto-expiry after 30 min.
- **Relay host key pinned** (trust on first use) — an attacker can no longer
  impersonate localhost.run; a changed key blocks the internet link.
- **No backups** of app data (tunnel key, settings); deep links are validated
  and need your confirmation.

Limits you should know: LAN sharing is plain HTTP (use a trusted Wi-Fi only).
Internet sharing goes through **localhost.run** (a third party) which terminates
TLS and can see the control traffic — the PIN + your approval remain the gate.
Non-Lovense brands are experimental (community protocol data, untested here);
a few need a pairing step (e.g. press the power button on Lelo, PIN 6496 on
Lioness) — the app tells you when.

## ⚖️ License

[GPLv3](LICENSE). Not affiliated with, or endorsed by, Lovense or any other
brand listed here.

The device database and the protocol ports come from
[Buttplug](https://github.com/buttplugio/buttplug) (BSD-3-Clause, © Nonpolynomial
Labs) — see [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). Thanks to the
Buttplug / Intiface community for years of reverse engineering.
