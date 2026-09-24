<div align="center">

<img src="docs/logo.svg" width="104" alt="Lovense Remote logo">

# Lovense Remote

**Drive your toys over direct Bluetooth — no app, no account, no cloud.**

Open-source Android remote. Several toys at once, several brands, the UI adapts to each one.

<p>
  <img src="https://img.shields.io/badge/License-GPLv3-8B6BFF?style=flat-square" alt="GPLv3">
  <img src="https://img.shields.io/badge/Android-8.0%2B-FF5C7A?style=flat-square" alt="Android 8+">
  <img src="https://img.shields.io/badge/100%25-AOSP%20·%20no%20Google-46E0A0?style=flat-square" alt="AOSP">
  <img src="https://img.shields.io/badge/i18n-FR%20·%20EN%20·%20ES-A98BFF?style=flat-square" alt="i18n">
  <img src="https://img.shields.io/github/v/release/kerstz/lovense-remote?style=flat-square&color=FF8BA1" alt="Release">
</p>

<img src="docs/screenshots/01-connexion.png" width="232">&nbsp;
<img src="docs/screenshots/02-principal.png" width="232">&nbsp;
<img src="docs/screenshots/03-partage.png" width="232">

</div>

> ⚠️ Experimental community project. **Not affiliated with Lovense.**

---

## ✨ Features

- **🔵 Direct BLE** — scan, connect and drive toys straight from your phone.
- **🧸 Several toys at once** — connect up to 5 toys, drive each one or **all**
  together; patterns, presets and remote control follow the selected target.
- **🏷 Multi-brand** — Lovense (Lush, Hush, Edge, Nora, Max, Domi, Ferri,
  Gemini…) plus **experimental** We-Vibe, Vorze and Magic Motion support (see
  [`docs/research/other-brands.md`](docs/research/other-brands.md)). The UI
  adapts to each toy's actuators (vibration, dual motor, rotation, suction).
- **👍 One-hand XY pad** — one thumb drives both motors of two-motor toys.
- **🎛 Patterns** — built-ins, Lovense `.ta` import, a random **Tease** mode, and
  a **record** mode (perform → saved pattern).
- **🔗 Remote by link** — let a partner control your toys from the **same Wi-Fi** or
  **over the internet / 4G**, gated by a secret link, a **6-digit PIN**, your
  approval **per controller**, and auto-expiry (see Security below).
- **🌙 Background** — keeps the link alive when the app is closed, plus a
  quick-settings **STOP** tile.
- **🌍 i18n & themes** — French / English / Spanish, dark & light.
- **🟢 100 % AOSP** — no Google Play Services (GrapheneOS-friendly).

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

- `ble/` — Lovense BLE layer: scan, GATT, per-actuator commands, toy registry
  (see [`docs/research/lovense-ble-protocol.md`](docs/research/lovense-ble-protocol.md)).
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
Non-Lovense brands are experimental (community protocol data, untested here).

## ⚖️ License

[GPLv3](LICENSE). Not affiliated with, or endorsed by, Lovense.
