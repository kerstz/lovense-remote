package com.edge2.remote.ble

/** État du scan BLE (écran d'ajout de jouet). */
sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    /** Échec (permission manquante, Bluetooth coupé, scanner indisponible…). */
    data class Error(val reason: String) : ScanState
}

/** État du lien BLE d'UN jouet. */
sealed interface LinkState {
    /** Connexion GATT + découverte services + handshake en cours. */
    data object Connecting : LinkState
    /** Prêt à piloter. */
    data object Connected : LinkState
    /** Lien perdu → reconnexion automatique en cours. */
    data object Reconnecting : LinkState
    /** Échec définitif (service introuvable, GATT error…). */
    data class Error(val reason: String) : LinkState
}

/**
 * Photo d'un jouet géré (connecté ou en cours) : exposé à l'UI, au partage et à
 * la notification. [levels] = niveau réellement envoyé à chaque actionneur.
 */
data class ToyStatus(
    val address: String,
    val toy: ToyType,
    val link: LinkState,
    val battery: Int? = null,
    val levels: List<Int> = List(toy.actuators.size) { 0 },
) {
    val isReady: Boolean get() = link is LinkState.Connected
    val displayName: String get() = toy.displayName
}

/**
 * Un jouet repéré pendant le scan (avant connexion). La liste alimente la
 * sélection sur l'écran d'ajout : on n'affiche que les jouets réellement visibles.
 */
data class DiscoveredToy(
    val address: String,
    val bleName: String,
    val rssi: Int,
    val brand: Brand,
    val displayName: String,
)
