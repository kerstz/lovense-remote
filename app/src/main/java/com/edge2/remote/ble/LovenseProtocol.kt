package com.edge2.remote.ble

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Protocole BLE Lovense — confirmé pour l'Edge 2 (cf. PROTOCOL.md).
 *
 * Résumé :
 *  - Commandes = chaînes ASCII terminées par `;`, écrites en WriteNoResponse
 *    sur la caractéristique TX (UUID se terminant par `…0002`).
 *  - Réponses = notifications ASCII sur la caractéristique RX (`…0003`).
 *  - Deux moteurs : `Vibrate1:n;` / `Vibrate2:n;`, n ∈ [0..20].
 *  - Handshake : `DeviceType;` → réponse `P:02:MAC;` (P = Edge). Pas d'auth
 *    requise pour piloter.
 *
 * On ne code pas en dur le service UUID exact (il varie selon la révision
 * firmware : `…0023…` vs `…0024…`). À la place on détecte TX/RX par leurs
 * propriétés GATT à la découverte (voir [findEndpoints]).
 */
object LovenseProtocol {

    /** Intensité maximale acceptée par le toy. */
    const val INTENSITY_MAX = 20

    /** Préfixe du nom BLE annoncé par tous les toys Lovense récents. */
    const val BLE_NAME_PREFIX = "LVS-"

    /** "LVS-Edge2-3A9F" → "Edge 2". Espace inséré entre lettres et chiffres. */
    fun prettyModelName(bleName: String): String {
        val token = bleName.removePrefix(BLE_NAME_PREFIX).substringBefore('-')
        if (token.isBlank()) return "Lovense"
        return token.replace(Regex("(?<=[A-Za-z])(?=[0-9])"), " ")
    }

    /** CCCD standard (Client Characteristic Configuration Descriptor). */
    val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // --- Construction des commandes (bytes ASCII) -------------------------

    /** `DeviceType;` — identifie le modèle (handshake). */
    fun deviceType(): ByteArray = "DeviceType;".toByteArray(Charsets.US_ASCII)

    /** `Battery;` — interroge le niveau de batterie. */
    fun battery(): ByteArray = "Battery;".toByteArray(Charsets.US_ASCII)

    /** `VibrateN:n;` — règle UN moteur. [intensity] est borné à [0..20]. */
    fun vibrate(motor: Motor, intensity: Int): ByteArray =
        "Vibrate${motor.index}:${clamp(intensity)};".toByteArray(Charsets.US_ASCII)

    /** `Vibrate:n;` — règle LES DEUX moteurs à la même valeur. */
    fun vibrateBoth(intensity: Int): ByteArray =
        "Vibrate:${clamp(intensity)};".toByteArray(Charsets.US_ASCII)

    /** `PowerOff;` — éteint le toy. */
    fun powerOff(): ByteArray = "PowerOff;".toByteArray(Charsets.US_ASCII)

    // --- Conversions / parsing -------------------------------------------

    /** Borne une intensité dans [0..20]. */
    fun clamp(intensity: Int): Int = intensity.coerceIn(0, INTENSITY_MAX)

    /** Convertit une fraction UI [0f..1f] en niveau Lovense [0..20]. */
    fun fractionToLevel(fraction: Float): Int =
        (fraction.coerceIn(0f, 1f) * INTENSITY_MAX).roundToInt()

    /**
     * Parse une notification du toy. Renvoie un [Reply] typé.
     * Exemples : `P:02:0082059AD3BD;`, `85;`, `OK;`.
     */
    fun parseReply(raw: ByteArray): Reply {
        val s = raw.toString(Charsets.US_ASCII).trim().trimEnd(';')
        return when {
            s.isEmpty() -> Reply.Unknown("")
            s == "OK" -> Reply.Ok
            // DeviceType : "P:02:MAC" (modèle:firmware:MAC)
            s.contains(':') && s.substringBefore(':').length == 1 && s.substringBefore(':')[0].isLetter() -> {
                val parts = s.split(':')
                Reply.DeviceType(
                    model = parts.getOrElse(0) { "?" },
                    firmware = parts.getOrElse(1) { "?" },
                    mac = parts.getOrElse(2) { "?" },
                )
            }
            // Batterie : un simple nombre, ex "85"
            s.toIntOrNull() != null -> Reply.Battery(s.toInt())
            else -> Reply.Unknown(s)
        }
    }

    sealed interface Reply {
        data object Ok : Reply
        data class DeviceType(val model: String, val firmware: String, val mac: String) : Reply
        data class Battery(val percent: Int) : Reply
        data class Unknown(val raw: String) : Reply
    }

    // --- Détection des endpoints à la découverte de services --------------

    /**
     * Parcourt les services GATT et retourne (TX write, RX notify) du toy.
     *
     * Heuristique robuste (indépendante de la révision d'UUID) : on cherche
     * le service qui contient à la fois une caractéristique NOTIFY (RX) et
     * une caractéristique inscriptible (TX). C'est la structure RX/TX type
     * « port série » de tous les Lovense.
     */
    fun findEndpoints(services: List<BluetoothGattService>): Endpoints? {
        for (service in services) {
            val notify = service.characteristics.firstOrNull {
                it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
            } ?: continue
            val write = service.characteristics.firstOrNull {
                val p = it.properties
                (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 ||
                    p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) &&
                    it.uuid != notify.uuid
            } ?: continue
            return Endpoints(tx = write, rx = notify)
        }
        return null
    }

    data class Endpoints(
        val tx: BluetoothGattCharacteristic,
        val rx: BluetoothGattCharacteristic,
    )
}
