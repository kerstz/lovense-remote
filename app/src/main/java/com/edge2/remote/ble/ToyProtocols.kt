package com.edge2.remote.ble

import java.util.UUID

/**
 * Identification d'un toy au scan (nom BLE + UUID de services annoncés) →
 * marque, modèle présumé et fabrique du [ToyDriver] adapté.
 *
 * Les noms des marques autres que Lovense sont génériques (« Sync », « Nova »…) :
 * on exige donc une correspondance EXACTE du nom, ou l'annonce du service
 * propriétaire. À la connexion, l'absence du service attendu fait échouer
 * proprement (aucune écriture n'est faite sur un appareil non reconnu).
 */
object ToyProtocols {

    class Match(
        val guess: ToyType,
        private val factory: () -> ToyDriver,
    ) {
        fun newDriver(): ToyDriver = factory()
    }

    private val vib15 = Actuator(ActuatorKind.VIBRATE, WeVibeDriver.MAX)

    /** We-Vibe à 2 moteurs (interne/externe) ; les autres n'en ont qu'un. */
    private val weVibeDual = setOf("4 Plus", "4plus", "classic", "Sync", "Nova", "NovaV2")
    private val weVibeSingle = setOf("Bloom", "Ditto", "Gala", "Jive", "Pivot", "Rave", "Verge", "Wish", "Cougar")

    private val vorze = mapOf(
        "CycSA" to Triple("A10 Cyclone SA", VorzeDriver.TYPE_CYCLONE, ActuatorKind.ROTATE),
        "UFOSA" to Triple("UFO SA", VorzeDriver.TYPE_UFO, ActuatorKind.ROTATE),
        "Bach smart" to Triple("Bach", VorzeDriver.TYPE_BACH, ActuatorKind.VIBRATE),
    )

    private val magicMotion = setOf("Smart Mini Vibe", "Flamingo", "Magic Cell", "Magic Wand", "Fugu")

    /** Identifie un appareil annoncé ; null = pas un toy géré (ignoré au scan). */
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
