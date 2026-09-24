package com.edge2.remote.ble

import java.util.UUID

/**
 * Identifies a toy at scan time (BLE name + advertised service UUIDs) →
 * brand, presumed model and a factory for the matching [ToyDriver].
 *
 * Non-Lovense names are generic ("Sync", "Nova"…), so an EXACT name match or
 * the advertisement of the proprietary service is required. On connection, a
 * missing expected service fails cleanly (nothing is ever written to an
 * unrecognized device).
 */
object ToyProtocols {

    class Match(
        val guess: ToyType,
        private val factory: () -> ToyDriver,
    ) {
        fun newDriver(): ToyDriver = factory()
    }

    private val vib15 = Actuator(ActuatorKind.VIBRATE, WeVibeDriver.MAX)

    /** Two-motor We-Vibe models (internal/external); the others have one. */
    private val weVibeDual = setOf("4 Plus", "4plus", "classic", "Sync", "Nova", "NovaV2")
    private val weVibeSingle = setOf("Bloom", "Ditto", "Gala", "Jive", "Pivot", "Rave", "Verge", "Wish", "Cougar")

    private val vorze = mapOf(
        "CycSA" to Triple("A10 Cyclone SA", VorzeDriver.TYPE_CYCLONE, ActuatorKind.ROTATE),
        "UFOSA" to Triple("UFO SA", VorzeDriver.TYPE_UFO, ActuatorKind.ROTATE),
        "Bach smart" to Triple("Bach", VorzeDriver.TYPE_BACH, ActuatorKind.VIBRATE),
    )

    private val magicMotion = setOf("Smart Mini Vibe", "Flamingo", "Magic Cell", "Magic Wand", "Fugu")

    /** Identifies an advertised device; null = not a supported toy (ignored). */
    fun identify(name: String?, serviceUuids: List<UUID> = emptyList()): Match? {
        val n = name?.trim().orEmpty()

        if (n.startsWith(LovenseProtocol.BLE_NAME_PREFIX)) {
            return Match(ToyRegistry.byBleName(n)) { LovenseDriver(n) }
        }

        if (n in weVibeDual || n in weVibeSingle || WeVibeDriver.SERVICE in serviceUuids) {
            val acts = if (n in weVibeSingle) listOf(vib15) else listOf(vib15, vib15)
            val t = ToyType(Brand.WEVIBE, n.ifEmpty { "WV" }, "We-Vibe ${n.ifEmpty { "?" }}".trim(), acts)
            return Match(t) { WeVibeDriver(t) }
        }

        vorze[n]?.let { (display, type, kind) ->
            val t = ToyType(Brand.VORZE, n, "Vorze $display", listOf(Actuator(kind, VorzeDriver.MAX)))
            return Match(t) { VorzeDriver(t, type) }
        }

        if (n in magicMotion || MagicMotionDriver.SERVICE in serviceUuids) {
            val t = ToyType(
                Brand.MAGIC_MOTION, n.ifEmpty { "MM" }, "Magic Motion ${n.ifEmpty { "?" }}".trim(),
                listOf(Actuator(ActuatorKind.VIBRATE, MagicMotionDriver.MAX)),
            )
            return Match(t) { MagicMotionDriver(t) }
        }
        return null
    }
}
