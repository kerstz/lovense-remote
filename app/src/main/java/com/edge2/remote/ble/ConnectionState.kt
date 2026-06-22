package com.edge2.remote.ble

/**
 * États exposés à l'UI via StateFlow. Volontairement simple et exhaustif
 * pour que Compose puisse couvrir chaque cas (loading / connected / error).
 */
sealed interface ConnectionState {
    /** Rien en cours. État initial et après [Edge2BleManager.disconnect]. */
    data object Disconnected : ConnectionState

    /** Scan BLE en cours, à la recherche d'un toy `LVS-…`. */
    data object Scanning : ConnectionState

    /** Toy trouvé, connexion GATT + découverte services + handshake en cours. */
    data object Connecting : ConnectionState

    /**
     * Prêt à piloter. [toy] = modèle détecté (→ actionneurs + UI adaptée),
     * [battery] = % batterie (null si inconnu).
     */
    data class Connected(
        val toy: ToyType,
        val battery: Int? = null,
    ) : ConnectionState {
        val deviceName: String get() = toy.displayName
    }

    /** Échec (timeout scan, GATT error, permission manquante…). */
    data class Error(val reason: String) : ConnectionState
}

/**
 * Un toy Lovense repéré pendant le scan (avant connexion). La liste de ces
 * objets alimente la sélection sur l'écran de connexion : on n'affiche que les
 * jouets réellement visibles à proximité.
 */
data class DiscoveredToy(
    val address: String,
    val bleName: String,
    val rssi: Int,
) {
    /** Nom lisible dérivé du nom BLE `LVS-<modèle>-<id>` (ex. "Edge 2"). */
    val displayName: String get() = LovenseProtocol.prettyModelName(bleName)
}
