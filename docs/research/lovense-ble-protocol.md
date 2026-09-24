# Lovense BLE protocol — multi-toy reference

Cross-checked research (Buttplug `lovense.yml` config + STPIHKAL + `lovesense-rs` +
Lovense Standard Solutions docs + a community gist). Basis for multi-toy support.

## Identification

- **BLE name**: `LVS-*` prefix (sometimes `LOVE-*`). Two schemes: legacy
  `LVS-<code><nnn>` (1 letter = type), modern `LVS-<ProductName><fw>` (last 2
  digits = firmware). **Do not rely on the name alone.**
- **Manufacturer data**: company id `620`, bytes `[255, 33]` (secondary filter).
- **Reliable**: after connecting, send `DeviceType;` → reply `<code>:<fw>:<MAC>;`
  (e.g. `C:11:0082059AD3BD;`). The leading letter(s) = type code.
- **Service UUID**: **varies per model** (Gen1 `fff0`, Gen2 Nordic UART
  `6e400001…`, Gen3 `XY30…` where the first 2 hex bytes encode the model, e.g.
  `50300001…` = `P0` = Edge). Within a pair, offset `…0002…` = TX (write),
  `…0003…` = RX (notify). → **do not hard-code the UUID**; detect TX/RX by GATT
  properties (done in `LovenseProtocol.findEndpoints`).

## Type codes (DeviceType / UUID)

| Code | Model | Code | Model | Code | Model |
|------|-------|------|-------|------|-------|
| A, C | Nora | B | Max | P (PA/PB) | Edge |
| S | Lush | Z | Hush | W | Domi |
| L | Ambi | X | Ferri | R | Diamo |
| T | Calor | O/OC | Osci | N | Gemini |
| EA | Gravity | EB | Hyphy | ED/EZ | Gush |
| H | Solace | BA | Solace Pro | U | Lapis |

## Commands (ASCII, `;`-terminated, levels 0..20 unless stated)

| Command | Syntax | Range | Confidence |
|---------|--------|-------|------------|
| Vibration (single) | `Vibrate:N;` | 0..20 | High |
| Vibration motor N | `Vibrate1:N;` / `Vibrate2:N;` | 0..20 | High (Edge 2 confirmed) |
| Rotation | `Rotate:N;` (+ `RotateChange;` reverses direction) | 0..20 | High |
| Air / suction (Max) | `Air:Level:N;` (+ `Air:In:`/`Air:Out:`) | **0..5 vs 0..3 disputed** | Medium |
| Battery | `Battery;` → `85;` | 0..100 | High |
| Type | `DeviceType;` → `code:fw:MAC;` | — | High |
| Power off | `PowerOff;` → `OK;` | — | High |

- **No `Stop;`**: to stop everything, send `0` to each actuator.
- **Stroker (Solace / Gravity thrust)**: the raw ASCII isn't reliably documented
  publicly (Buttplug abstracts it as Oscillate + position/duration). **Low
  confidence** → not supported as is; to be confirmed by sniffing the device.

## UI archetypes (5)

1. 1 vibrator (Lush, Hush, Domi, Ferri, Ambi, Diamo, Calor, Osci, Gush…) → 1 slider.
2. 2 vibrators (Edge 2, Gemini, Hyphy) → **XY pad**.
3. vibrator + rotation (Nora) → vibration slider + rotation slider + direction button.
4. vibrator + air (Max 2) → vibration slider + suction slider.
5. stroker (Solace/Gravity) → oscillation slider (+ Pro depth). *Not implemented
   (uncertain command).*

## To confirm on hardware
- `Air:Level` max (3 vs 5).
- Actual ASCII command for thrust/stroker (Solace, Gravity).
- Exact name `Battery;` vs `BatteryLevel;` (consensus: `Battery;`).
