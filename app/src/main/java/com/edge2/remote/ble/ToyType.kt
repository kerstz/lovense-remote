package com.edge2.remote.ble

/**
 * Modèle multi-marque / multi-toy (cf. docs/research/lovense-ble-protocol.md et
 * docs/research/other-brands.md).
 *
 * Un toy = un [ToyType] = une marque + une liste d'[Actuator]. Chaque marque a son
 * propre encodage BLE ([ToyDriver]) ; l'UI, les patterns et le partage ne voient
 * que des actionneurs génériques pilotés en niveaux `0..max`.
 */

/** Marques gérées. [experimental] = protocole issu de la communauté, non testé sur matériel ici. */
enum class Brand(val displayName: String, val experimental: Boolean) {
    LOVENSE("Lovense", false),
    WEVIBE("We-Vibe", true),
    VORZE("Vorze", true),
    MAGIC_MOTION("Magic Motion", true),
}

/** Type générique d'actionneur (l'encodage dépend de la marque). */
enum class ActuatorKind(val reversible: Boolean = false) {
    VIBRATE,
    ROTATE(reversible = true),
    SUCTION,
}

/** Un actionneur concret : type + plage `0..max` propre au toy. */
data class Actuator(
    val kind: ActuatorKind,
    val max: Int,
) {
    val reversible: Boolean get() = kind.reversible
}

/** Un modèle de toy : marque, code interne, nom affiché, actionneurs. */
data class ToyType(
    val brand: Brand,
    val code: String,
    val displayName: String,
    val actuators: List<Actuator>,
) {
    /** 2 vibreurs → pad XY (Edge, Gemini, We-Vibe Sync…). */
    val isDualVibrate: Boolean
        get() = actuators.size == 2 && actuators.all { it.kind == ActuatorKind.VIBRATE }
}

/**
 * Registre Lovense + résolution depuis le code `DeviceType` ou, à défaut, le
 * nom BLE. Toy inconnu → fallback 1 vibreur.
 */
object ToyRegistry {

    private val vibrate = listOf(Actuator(ActuatorKind.VIBRATE, 20))
    private val dualVibrate = listOf(Actuator(ActuatorKind.VIBRATE, 20), Actuator(ActuatorKind.VIBRATE, 20))

    /** Fallback : 1 vibreur (toute la famille mono-vibreur + toy inconnu). */
    val generic = ToyType(Brand.LOVENSE, "?", "Lovense", vibrate)

    private fun lv(code: String, name: String, acts: List<Actuator>) = ToyType(Brand.LOVENSE, code, name, acts)

    // Indexé par code DeviceType. Les codes alternatifs pointent sur la même entrée.
    private val byCode: Map<String, ToyType> = buildMap {
        fun put(codes: List<String>, t: ToyType) = codes.forEach { put(it, t) }
        put(listOf("S", "AN"), lv("S", "Lush", vibrate))
        put(listOf("Z"), lv("Z", "Hush", vibrate))
        put(listOf("W"), lv("W", "Domi", vibrate))
        put(listOf("X"), lv("X", "Ferri", vibrate))
        put(listOf("L"), lv("L", "Ambi", vibrate))
        put(listOf("R"), lv("R", "Diamo", vibrate))
        put(listOf("T"), lv("T", "Calor", vibrate))
        put(listOf("O", "OC"), lv("O", "Osci", vibrate))
        put(listOf("ED", "EZ"), lv("ED", "Gush", vibrate))
        put(listOf("P", "PA", "PB"), lv("P", "Edge", dualVibrate))
        put(listOf("N"), lv("N", "Gemini", dualVibrate))
        put(listOf("EB"), lv("EB", "Hyphy", dualVibrate))
        put(listOf("A", "C"), lv("A", "Nora", listOf(
            Actuator(ActuatorKind.VIBRATE, 20), Actuator(ActuatorKind.ROTATE, 20),
        )))
        // Air:Level — plage 0..5 d'après la communauté (0..3 selon d'autres sources).
        put(listOf("B"), lv("B", "Max", listOf(
            Actuator(ActuatorKind.VIBRATE, 20), Actuator(ActuatorKind.SUCTION, 5),
        )))
        // Gravity : son thrust n'a pas de commande ASCII fiable → vibreur seul.
        put(listOf("EA"), lv("EA", "Gravity", vibrate))
    }

    /** Résout par code `DeviceType` (insensible à la casse). */
    fun byDeviceCode(code: String): ToyType? = byCode[code.uppercase()]

    /**
     * Résout par nom BLE (`LVS-Edge2-…`). Heuristique de secours avant le
     * handshake `DeviceType;` : on matche le token modèle sur les noms connus.
     */
    fun byBleName(bleName: String): ToyType {
        val pretty = LovenseProtocol.prettyModelName(bleName)
        val token = pretty.lowercase()
        val hit = byCode.values.firstOrNull { token.startsWith(it.displayName.lowercase()) }
        return hit ?: generic.copy(displayName = pretty)
    }
}
