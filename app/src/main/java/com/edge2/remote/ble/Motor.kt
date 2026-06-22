package com.edge2.remote.ble

/**
 * Les deux moteurs indépendants du Lovense Edge 2.
 *
 * [index] est l'index Lovense utilisé dans la commande `VibrateN:`.
 * Protocole confirmé : `Vibrate1:` et `Vibrate2:` (cf. PROTOCOL.md).
 *
 * ⚠️ Le mapping physique (index 1 = base ou tige ?) reste à confirmer
 * empiriquement : envoyer `Vibrate1:20;` seul et observer quel moteur
 * réagit. Les libellés [BASE]/[SHAFT] sont une hypothèse à valider.
 */
enum class Motor(val index: Int) {
    BASE(1),
    SHAFT(2),
}
