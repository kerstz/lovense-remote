package com.edge2.remote.ble

/**
 * Modèle multi-toy Lovense (cf. docs/research/lovense-ble-protocol.md).
 *
 * Un toy = un [ToyType] = une liste d'[Actuator]. La couche BLE pilote chaque
 * actionneur indépendamment via sa commande ASCII propre, et l'UI s'adapte au
 * nombre/type d'actionneurs.
 */

/** Type d'actionneur + plage. Le libellé sert d'étiquette UI par défaut. */
enum class ActuatorKind(val label: String, val max: Int, val reversible: Boolean = false) {
    /** `Vibrate:n;` — vibreur unique. */
    VIBRATE("Vibration", 20),

    /** `Vibrate1:n;` — moteur 1 (base sur l'Edge). */
    VIBRATE1("Base", 20),

    /** `Vibrate2:n;` — moteur 2 (tige sur l'Edge). */
    VIBRATE2("Tige", 20),

    /** `Rotate:n;` (+ `RotateChange;` pour inverser) — rotation (Nora). */
    ROTATE("Rotation", 20, reversible = true),

    /** `Air:Level:n;` — succion / air (Max). Plage 0..5 (à confirmer, cf. doc). */
    AIR("Succion", 5),
}

/** Un actionneur concret d'un toy (kind + étiquette éventuellement spécialisée). */
data class Actuator(
    val kind: ActuatorKind,
    val label: String = kind.label,
) {
    val max: Int get() = kind.max
    val reversible: Boolean get() = kind.reversible
}

/** Un modèle de toy : code [DeviceType], nom affiché, liste d'actionneurs. */
data class ToyType(
    val code: String,
    val displayName: String,
    val actuators: List<Actuator>,
) {
    val isDualVibrate: Boolean
        get() = actuators.size == 2 && actuators.all { it.kind == ActuatorKind.VIBRATE1 || it.kind == ActuatorKind.VIBRATE2 }
}

/**
 * Registre des toys Lovense les plus utilisés + résolution depuis le code
 * `DeviceType` ou, à défaut, le nom BLE. Toy inconnu → fallback 1 vibreur.
 */
object ToyRegistry {

    private val vibrate = listOf(Actuator(ActuatorKind.VIBRATE))
    private val dualVibrate = listOf(
        Actuator(ActuatorKind.VIBRATE1, "Base"),
        Actuator(ActuatorKind.VIBRATE2, "Tige"),
    )

    /** Fallback : 1 vibreur (toute la famille mono-vibreur + toy inconnu). */
    val generic = ToyType("?", "Jouet Lovense", vibrate)

    // Indexé par code DeviceType. Les codes alternatifs pointent sur la même entrée.
    private val byCode: Map<String, ToyType> = buildMap {
        fun put(codes: List<String>, t: ToyType) = codes.forEach { put(it, t) }
        put(listOf("S", "AN"), ToyType("S", "Lush", vibrate))
        put(listOf("Z"), ToyType("Z", "Hush", vibrate))
        put(listOf("W"), ToyType("W", "Domi", vibrate))
        put(listOf("X"), ToyType("X", "Ferri", vibrate))
        put(listOf("L"), ToyType("L", "Ambi", vibrate))
        put(listOf("R"), ToyType("R", "Diamo", vibrate))
        put(listOf("T"), ToyType("T", "Calor", vibrate))
        put(listOf("O", "OC"), ToyType("O", "Osci", vibrate))
        put(listOf("ED", "EZ"), ToyType("ED", "Gush", vibrate))
        put(listOf("P", "PA", "PB"), ToyType("P", "Edge", dualVibrate))
        put(listOf("N"), ToyType("N", "Gemini", dualVibrate))
        put(listOf("EB"), ToyType("EB", "Hyphy", dualVibrate))
        put(listOf("A", "C"), ToyType("A", "Nora", listOf(
            Actuator(ActuatorKind.VIBRATE), Actuator(ActuatorKind.ROTATE),
        )))
        put(listOf("B"), ToyType("B", "Max", listOf(
            Actuator(ActuatorKind.VIBRATE), Actuator(ActuatorKind.AIR),
        )))
        // Gravity : son thrust n'a pas de commande ASCII fiable → vibreur seul.
        put(listOf("EA"), ToyType("EA", "Gravity", vibrate))
    }

    /** Résout par code `DeviceType` (insensible à la casse). */
    fun byDeviceCode(code: String): ToyType? = byCode[code.uppercase()]

    /**
     * Résout par nom BLE (`LVS-Edge2-…`). Heuristique de secours avant le
     * handshake `DeviceType;` : on matche le token modèle sur les noms connus.
     */
    fun byBleName(bleName: String): ToyType {
        val token = LovenseProtocol.prettyModelName(bleName).lowercase()
        val hit = byCode.values.firstOrNull { token.startsWith(it.displayName.lowercase()) }
        return hit ?: generic.copy(displayName = LovenseProtocol.prettyModelName(bleName))
    }
}
