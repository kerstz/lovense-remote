# Edge2 Remote

Open-source Android remote for **Lovense** toys over **direct Bluetooth LE** —
no Lovense app, no account, no cloud.

> Experimental, community project. **Not affiliated with Lovense.**

## Features

- **Direct BLE** control (scan, connect, drive) — the toy never leaves your phone.
- **Multi-toy**: Lush, Hush, Edge 2, Nora, Max, Domi, Ferri, Gemini… The UI adapts
  to each toy's actuators (vibration, dual motor, rotation, suction). See
  [`docs/research/lovense-ble-protocol.md`](docs/research/lovense-ble-protocol.md).
- **One-hand XY pad** for two-motor toys (Edge): one thumb drives both motors.
- **Patterns**: built-ins, import Lovense `.ta`, a random **Tease** mode, and a
  **record** mode (perform → saved pattern).
- **Remote control by link**: share a link so a partner controls the toy.
  - Works on the **same Wi-Fi (LAN)** and **over the internet / 4G** via an SSH
    quick-tunnel ([localhost.run](https://localhost.run)).
  - **4-digit PIN**, accept/refuse prompt, auto-expiry, big STOP.
- **Background**: keeps the connection alive when the app is closed (foreground
  service) + a quick-settings **STOP** tile.
- **i18n**: French / English / Spanish, dark & light themes.
- **100 % AOSP**: no Google Play Services (GrapheneOS-friendly).

## Build

```bash
ANDROID_HOME=~/Android/Sdk ./gradlew :app:assembleDebug
```

Min SDK 26, target/compile SDK 35, Kotlin + Jetpack Compose.

## Architecture

- `ble/` — Lovense BLE layer (scan, GATT, per-actuator commands, toy registry).
- `RemoteEngine` — process-scoped core (BLE + server + tunnel + state), so control
  survives the Activity / app being closed.
- `remote/` — embedded HTTP+WS server (host), web control page
  (`assets/controller.html`), and the internet tunnel (`SshTunnel`).

## Privacy

LAN sharing stays on your network. Internet sharing routes traffic through
**localhost.run** (a third party). The control link is gated by a per-session
PIN and an accept/refuse prompt, and the session auto-expires.

## License

[GPLv3](LICENSE).
