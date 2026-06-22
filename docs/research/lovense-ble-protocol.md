# Protocole BLE Lovense — référence multi-toy

Recherche croisée (config Buttplug `lovense.yml` + STPIHKAL + `lovesense-rs` + doc
Lovense Standard Solutions + gist communautaire). Sert de base au support multi-toy.

## Identification

- **Nom BLE** : préfixe `LVS-*` (parfois `LOVE-*`). Deux schémas : ancien
  `LVS-<code><nnn>` (1 lettre = type), moderne `LVS-<ProductName><fw>` (2 derniers
  chiffres = firmware). **Ne pas se fier au nom seul.**
- **Manufacturer data** : company id `620`, octets `[255, 33]` (filtre secondaire).
- **Fiable** : après connexion, envoyer `DeviceType;` → réponse `<code>:<fw>:<MAC>;`
  (ex. `C:11:0082059AD3BD;`). La/les lettre(s) de tête = code type.
- **Service UUID** : **varie selon le modèle** (Gen1 `fff0`, Gen2 Nordic UART
  `6e400001…`, Gen3 `XY30…` où les 2 premiers octets hex encodent le modèle, ex.
  `50300001…` = `P0` = Edge). Dans une paire, offset `…0002…` = TX (write),
  `…0003…` = RX (notify). → **ne pas coder l'UUID en dur** ; détecter TX/RX par
  propriétés GATT (déjà fait dans `LovenseProtocol.findEndpoints`).

## Codes type (DeviceType / UUID)

| Code | Modèle | Code | Modèle | Code | Modèle |
|------|--------|------|--------|------|--------|
| A, C | Nora | B | Max | P (PA/PB) | Edge |
| S | Lush | Z | Hush | W | Domi |
| L | Ambi | X | Ferri | R | Diamo |
| T | Calor | O/OC | Osci | N | Gemini |
| EA | Gravity | EB | Hyphy | ED/EZ | Gush |
| H | Solace | BA | Solace Pro | U | Lapis |

## Commandes (ASCII, terminées par `;`, niveaux 0..20 sauf indication)

| Commande | Syntaxe | Plage | Confiance |
|----------|---------|-------|-----------|
| Vibration (mono) | `Vibrate:N;` | 0..20 | Haute |
| Vibration moteur N | `Vibrate1:N;` / `Vibrate2:N;` | 0..20 | Haute (Edge 2 confirmé) |
| Rotation | `Rotate:N;` (+ `RotateChange;` inverse le sens) | 0..20 | Haute |
| Air / succion (Max) | `Air:Level:N;` (+ `Air:In:`/`Air:Out:`) | **0..5 vs 0..3 disputé** | Moyenne |
| Batterie | `Battery;` → `85;` | 0..100 | Haute |
| Type | `DeviceType;` → `code:fw:MAC;` | — | Haute |
| Éteindre | `PowerOff;` → `OK;` | — | Haute |

- **Pas de `Stop;`** : pour tout arrêter, envoyer `0` à chaque actionneur.
- **Stroker (Solace / Gravity thrust)** : ASCII brut non documenté publiquement de
  façon fiable (Buttplug l'abstrait en Oscillate + position/durée). **Confiance
  faible** → non supporté tel quel ; à confirmer en sniffant l'appareil.

## Archétypes UI (5)

1. 1 vibreur (Lush, Hush, Domi, Ferri, Ambi, Diamo, Calor, Osci, Gush…) → 1 slider.
2. 2 vibreurs (Edge 2, Gemini, Hyphy) → **pad XY**.
3. vibreur + rotation (Nora) → slider vibration + slider rotation + bouton sens.
4. vibreur + air (Max 2) → slider vibration + slider succion.
5. stroker (Solace/Gravity) → slider oscillation (+ profondeur Pro). *Non implémenté
   (commande incertaine).*

## À confirmer sur matériel
- `Air:Level` max (3 vs 5).
- Commande ASCII réelle du thrust/stroker (Solace, Gravity).
- Nom exact `Battery;` vs `BatteryLevel;` (consensus `Battery;`).
