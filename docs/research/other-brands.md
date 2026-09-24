# Autres marques — protocoles BLE (expérimental)

Encodages repris de la documentation communautaire (config d'appareils
[Buttplug / Intiface](https://github.com/buttplugio/buttplug)). **Non testés sur
matériel dans ce projet** → marqués « expérimental » dans l'app. Implémentés dans
`ble/ToyDriver.kt`, identifiés au scan par `ble/ToyProtocols.kt`.

Règle de sûreté : les noms BLE de ces marques sont génériques (« Sync », « Nova »…),
donc on exige un nom **exact** ou l'annonce du service propriétaire ; à la
connexion, si le service attendu est absent, on s'arrête **sans rien écrire**.

## We-Vibe (Standard Innovation) — `wevibe`

- Service `f000bb03-0451-4000-b000-000000000000`, TX `f000c000-0451-4000-b000-000000000000`.
- Trame 8 octets, 2 moteurs 0..15 packés dans l'octet 3 :
  `0f 03 00 (m1<<4 | m2) 00 03 00 00` ; arrêt : `0f 00 00 00 00 00 00 00`.
- Noms : 2 moteurs `4 Plus`, `4plus`, `classic`, `Sync`, `Nova`, `NovaV2` ;
  1 moteur `Bloom`, `Ditto`, `Gala`, `Jive`, `Pivot`, `Rave`, `Verge`, `Wish`, `Cougar`.
- Non gérés : modèles récents « 8 bits » (Chorus, Melt, Moxie…).

## Vorze — `vorze-sa`

- Service `40ee1111-63ec-4b7f-8ce7-712efd55b90e`, TX `40ee2222-63ec-4b7f-8ce7-712efd55b90e`.
- 3 octets `[type, action, valeur]` : type `01` A10 Cyclone SA (`CycSA`),
  `02` UFO SA (`UFOSA`), `06` Bach (`Bach smart`).
- Rotation (action `01`) : valeur = `sens<<7 | vitesse` (0..99) ; « ⟲ sens » bascule le bit 7.
- Vibration Bach (action `03`) : valeur 0..99.

## Magic Motion v1 — `magic-motion-1`

- Service `78667579-7b48-43db-b8c5-7928a6b0a335`, TX `78667579-a914-49a4-8333-aa3c0cd8fedc`.
- Trame 12 octets : `0b ff 04 0a 32 32 00 04 08 <niveau 0..100> 64 00`.
- Noms : `Smart Mini Vibe`, `Flamingo`, `Magic Cell`, `Magic Wand`, `Fugu`.

## Ajouter une marque

1. Un `ToyDriver` (endpoints GATT + `encode(target, last)`), pur et testable.
2. Une règle d'identification dans `ToyProtocols.identify`.
3. Un test d'encodage dans `ToyProtocolsTest`.
