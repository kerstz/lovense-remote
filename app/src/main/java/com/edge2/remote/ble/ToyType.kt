package com.edge2.remote.ble

/**
 * Multi-brand / multi-toy model (see docs/research/lovense-ble-protocol.md and
 * docs/research/other-brands.md).
 *
 * A toy = a [ToyType] = a brand + a list of [Actuator]s. Each brand has its own
 * BLE encoding ([ToyDriver]); the UI, patterns and sharing only see generic
 * actuators driven with `0..max` levels.
 */

/** Supported brands. [experimental] = community protocol data, not tested on hardware here. */
enum class Brand(val displayName: String, val experimental: Boolean) {
    LOVENSE("Lovense", false),
    WEVIBE("We-Vibe", true),
    VORZE("Vorze", true),
    MAGIC_MOTION("Magic Motion", true),
}

/** Generic actuator type (the encoding depends on the brand). */
enum class ActuatorKind(val reversible: Boolean = false) {
    VIBRATE,
    ROTATE(reversible = true),
    SUCTION,
}

/** A concrete actuator: type + the toy's own `0..max` range. */
data class Actuator(
    val kind: ActuatorKind,
    val max: Int,
) {
    val reversible: Boolean get() = kind.reversible
}

/** A toy model: brand, internal code, display name, actuators. */
data class ToyType(
    val brand: Brand,
    val code: String,
    val displayName: String,
    val actuators: List<Actuator>,
) {
    /** 2 vibrators → XY pad (Edge, Gemini, We-Vibe Sync…). */
    val isDualVibrate: Boolean
        get() = actuators.size == 2 && actuators.all { it.kind == ActuatorKind.VIBRATE }
}

/**
 * Lovense registry + resolution from the `DeviceType` code or, failing that,
 * the BLE name. Unknown toy → single-vibrator fallback.
 */
object ToyRegistry {

    private val vibrate = listOf(Actuator(ActuatorKind.VIBRATE, 20))
    private val dualVibrate = listOf(Actuator(ActuatorKind.VIBRATE, 20), Actuator(ActuatorKind.VIBRATE, 20))

    /** Fallback: 1 vibrator (the whole single-vibrator family + unknown toys). */
    val generic = ToyType(Brand.LOVENSE, "?", "Lovense", vibrate)

    private fun lv(code: String, name: String, acts: List<Actuator>) = ToyType(Brand.LOVENSE, code, name, acts)

    // Indexed by DeviceType code. Alternative codes point to the same entry.
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
        // Air:Level — range 0..5 per the community (0..3 according to other sources).
        put(listOf("B"), lv("B", "Max", listOf(
            Actuator(ActuatorKind.VIBRATE, 20), Actuator(ActuatorKind.SUCTION, 5),
        )))
        // Gravity: its thrust has no reliable ASCII command → vibrator only.
        put(listOf("EA"), lv("EA", "Gravity", vibrate))
    }

    /** Resolves by `DeviceType` code (case-insensitive). */
    fun byDeviceCode(code: String): ToyType? = byCode[code.uppercase()]

    /**
     * Resolves by BLE name (`LVS-Edge2-…`). Fallback heuristic before the
     * `DeviceType;` handshake: the model token is matched against known names.
     */
    fun byBleName(bleName: String): ToyType {
        val pretty = LovenseProtocol.prettyModelName(bleName)
        val token = pretty.lowercase()
        val hit = byCode.values.firstOrNull { token.startsWith(it.displayName.lowercase()) }
        return hit ?: generic.copy(displayName = pretty)
    }
}
