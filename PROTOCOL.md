# Lovense Edge 2 — BLE Protocol (reverse-engineered)

> Status: **PHASE 1 — PROTOCOL CONFIRMED ✅** (see CONFIRMED section).
> Cross-verified on our HCI capture + Buttplug/Intiface public docs + source.
> Nothing below the "CONFIRMED" line is trusted until verified against
> our own HCI snoop log AND/OR the decompiled app. Everything in
> "HYPOTHESES" is from prior public knowledge of Lovense gear and MUST be
> validated, not assumed.

---

## Device
- Model: Lovense Edge 2
- Motors: 2 independent (base + shaft/tige)
- Official app used for capture: `com.lovense.wear` v7.95.1 (Lovense Remote)

---

## HYPOTHESES (to confirm — DO NOT code against these yet)

These come from older Lovense devices / public notes. Treat as a checklist
to confirm or refute in the capture.

### GATT layout (candidate A — "fff0" legacy)
| Role            | UUID (16-bit short) |
|-----------------|---------------------|
| Service         | `0000fff0`          |
| TX (write cmd)  | `0000fff2`  (Write / WriteNoResponse) |
| RX (notify)     | `0000fff1`  (Notify) |

### GATT layout (candidate B — newer Lovense 128-bit)
| Role            | UUID |
|-----------------|------|
| Service         | `5a300001-0023-4bd4-bbd5-a6920e4c5653` (pattern, confirm exact) |
| TX (write cmd)  | `5a300002-...` |
| RX (notify)     | `5a300003-...` |

> Action: confirm which family Edge 2 uses, and the EXACT 128-bit UUIDs.

### Command format (ASCII, semicolon-terminated)
| Command (guess)        | Meaning                          |
|------------------------|----------------------------------|
| `DeviceType;`          | query model/firmware (handshake reply) |
| `Battery;`             | query battery                    |
| `Vibrate:N;`           | both motors at N                 |
| `Vibrate1:N;`          | motor 1 (base?) at N             |
| `Vibrate2:N;`          | motor 2 (shaft?) at N            |
| `Stop;` or `Vibrate:0;`| stop                             |
| `PowerOff;`            | power off                        |

- Intensity range guess: **0–20** (classic Lovense). Could be 0–100. CONFIRM.
- Which physical motor = index 1 vs 2: **UNKNOWN — must map empirically.**

---

## CONFIRMED  ⟵ (write below only what the capture/decompile proves)

> Source so far: our own HCI capture extracted from `adb bugreport`
> (`btsnooz_hci.log`, btsnoop magic, datalink 1002 / HCI-H4). Device name
> advertised: **`LVS-Edge`**.
>
> ⚠️ CAPTURE LIMITATION: Pixel 9 / Android 16 has NO "Enable Bluetooth HCI
> snoop log" toggle (removed by Google) and no adb root, so only the
> **filtered btsnooz** snippet is available. It **truncates every ACL
> payload to ~3 bytes** → we see command PREFIXES only, never the full
> `Vibrate:n;`. Full payloads must come from jadx (decompile) or public
> docs. `settings put global bluetooth_hci_log 1` does NOT enable full
> snoop without root (needs `persist.bluetooth.btsnooplogmode=full`).

> **Cross-verified ✅ on 4 independent sources:** (1) our HCI capture, (2)
> Buttplug protocol spec + Metafetish stpihkal + lovesense-py docs, (3)
> Buttplug Rust source `lovense/mod.rs` + `lovense_dual_actuator.rs`,
> (4) Buttplug device test case `test_lovense_edge.yaml` (exact byte arrays).
> Confidence: **HIGH.** Edge/Edge 2 is fully supported by Buttplug/Intiface
> — this protocol is public, not novel.

### GATT — Edge model letter = `P` (0x50). First UUID byte = ASCII of letter.
| Role | UUID | Notes |
|------|------|-------|
| Service | `50300001-0024-4bd4-bbd5-a6920e4c5653` | Edge 2 (newer rev). Older rev: `50300001-0023-…` |
| TX / write cmd | `50300002-0024-4bd4-bbd5-a6920e4c5653` | **WriteNoResponse**, ASCII payload. Our capture: handle `0x1a`, op `0x52` ✓ |
| RX / notify | `50300003-0024-4bd4-bbd5-a6920e4c5653` | Notify. Our capture: handle `0x1c`, op `0x1b` ✓ |
| CCCD | `0x2902` on RX | write `0x0100` to enable notif. Our capture: handle `0x1d` ✓ |

> **App strategy:** scan by the Lovense service-UUID set (or BLE name prefix
> `LVS-`), connect, discover services, pick the service whose chars end in
> `…0002` (tx) / `…0003` (rx). Don't hardcode `-0023-` vs `-0024-`; discover.
> Full Lovense service-UUID list (11 families) saved below.

### Handshake / init sequence — NO auth required to control motors
From `lovense/mod.rs`:
1. Subscribe to RX (write `0x0100` to its CCCD)
2. Write `DeviceType;` to TX
3. Toy replies on RX with `model:firmware:MAC;` → for Edge: **`P:02:0082059AD3BD;`**
   (matches our captured `P:2…` notify ✓). Parse first field = model letter.
4. Fallback if no reply: regex BLE name `LVS-([A-Z]+)\d+`.

> Our capture also showed `Aut…` writes/replies — that's the **official
> app's optional account/telemetry auth, NOT required for motor control.**
> Buttplug controls Edge with zero auth. We skip it.

### Commands (exact ASCII) — write to TX, no response
| ASCII | Bytes (dec) | Effect | Reply |
|-------|-------------|--------|-------|
| `DeviceType;` | 68 101 118 105 99 101 84 121 112 101 59 | identify | `P:02:MAC;` |
| `Battery;` | — | query battery | `NN;` (e.g. `85;`) |
| `Vibrate1:n;` | `Vibrate1:` + n + `;` | **motor 1**, n=0–20 | `OK;` |
| `Vibrate2:n;` | `Vibrate2:` + n + `;` | **motor 2**, n=0–20 | `OK;` |
| `Vibrate:n;` | — | BOTH motors at n | `OK;` |
| Stop | `Vibrate1:0;` then `Vibrate2:0;` | stop both | `OK;` |
| `PowerOff;` | — | power off | `OK;` |
| `Status:1;` | — | status | `2;` |

- Construction (buttplug `lovense_dual_actuator.rs`): `format!("Vibrate{}:{};", feature_index + 1, speed)` → index 0 ⇒ `Vibrate1`, index 1 ⇒ `Vibrate2`.

### Intensity
- **Range: 0–20** (integer). Map UI 0.0–1.0 → `round(x * 20)`.
- Motor 1 (`Vibrate1`) vs Motor 2 (`Vibrate2`) = base vs tige: **mapping physique à confirmer empiriquement** une fois notre app capable d'émettre (envoyer `Vibrate1:20;` seul, voir quel moteur bouge).

### Notifications (replies from toy) — RX char, op 0x1b
| Reply | Meaning | Confirmed by |
|-------|---------|--------------|
| `P:02:…;` | DeviceType reply (Edge, fw 02) | capture `P:2…` + test case |
| `NN;` (`73;` `75;` `77;`) | battery % | capture + lovesense-py (`85;`) |
| `OK;` | command ack | docs/source |

### Full Lovense service-UUID set (for scan filter)
`fff0` legacy · `6e40xxxx` (Nordic UART) · and the `XX3000NN-002N-4bd4-bbd5-a6920e4c5653`
family where `XX` = ASCII of model letter (`50`=P/Edge, `53`=S/Lush, `5a`=Z/Hush, `4f`=O/Osci, `42`=B, `43`=C, `57`=W/Domi…), `NN` ∈ {0023, 0024} per firmware rev.

---

## Capture log (controlled test runs)

Record each run so packet timestamps map to known inputs.

### Run 1 — handshake
- t0: connect, no input, wait 5s
- Notes:

### Run 2 — base motor sweep
- Slider sequence (with wall-clock): base 0→5→10→20→0
- Notes:

### Run 3 — shaft motor sweep
- shaft 0→5→10→20→0
- Notes:

### Run 4 — both motors
- Notes:

### Run 5 — stop / poweroff
- Notes:
