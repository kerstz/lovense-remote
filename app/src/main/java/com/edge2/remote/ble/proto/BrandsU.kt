package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Ports of Buttplug protocols U–V (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

/** Umove: vibration + stroker position (own movement loop) + adjustable heating (°C). */
class Umove(def: DeviceDefinition) : ProtocolHandler(def) {
    override val keepaliveMs: Long = 500
    @Volatile private var vibrate = 0
    @Volatile private var goal = 0
    @Volatile private var current = 0
    @Volatile private var duration = 0
    private var packetId = 0L

    @Synchronized
    private fun command(): ByteArray {
        val id = packetId
        packetId = (packetId + 1) and 0xffffffffL
        return bytes(0x5A, 0xA5, 0x55, 0x00) + le16(vibrate) + le16(1) + le32(id) + le32(current.toLong())
    }

    override fun start(io: DeviceIo, scope: CoroutineScope) {
        scope.launch {
            var lastGoal = 0
            var step = 0
            var pos = 0
            while (isActive) {
                val g = goal
                if (g != lastGoal) {
                    lastGoal = g
                    val steps = (duration / 100).coerceAtLeast(1)
                    val distance = g - pos
                    step = distance / steps
                    if (step == 0) step = Integer.signum(distance)
                }
                if (pos == lastGoal) { delay(100); continue }
                pos += step
                if (step < 0) { if (pos < lastGoal) pos = lastGoal } else if (pos > lastGoal) pos = lastGoal
                current = pos
                if (!io.write(Write(Ep.TX, command()))) return@launch
                delay(50)
            }
        }
    }

    override fun vibrate(index: Int, speed: Int): List<Write> {
        vibrate = speed and 0xffff
        if (current != goal) return emptyList()
        return listOf(Write(Ep.TX, command()))
    }

    override fun hwPosition(index: Int, position: Int, durationMs: Int): List<Write> {
        goal = position
        duration = durationMs
        return emptyList()
    }

    override fun position(index: Int, position: Int): List<Write> {
        goal = position
        current = position
        duration = 0
        return listOf(Write(Ep.TX, command()))
    }

    override fun temperature(index: Int, level: Int) =
        listOf(w(Ep.TX, 0x5a, 0xa5, 0x55, 0x06, 0xff, 0xff, 0x00, 0x00, 0xff, 0xff, 0xff, 0xff, level.u8(), 0xff))

    companion object {
        private fun le16(v: Int) = bytes(v and 0xff, (v shr 8) and 0xff)
        private fun le32(v: Long) = ByteArray(4) { ((v shr (8 * it)) and 0xff).toByte() }

        /** Identifier = characters 2..3 of the BLE name ("UMVxB…" → "Vx"). */
        val spec = object : ProtocolSpec {
            override val id = "umove"
            override suspend fun identify(io: DeviceIo) = Identified(io.name.takeIf { it.length >= 4 }?.substring(2, 4))
            override suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?) = Umove(def)
        }
    }
}

/**
 * VibCrafter / Vibio: AES-128-ECB text commands after an "Auth:" challenge
 * (the toy answers `xxxx:<text>;`, we answer with the first 2 SHA-256 bytes).
 */
class AesTextVibrator(def: DeviceDefinition, private val key: ByteArray) : ProtocolHandler(def) {
    private val speeds = IntArray(2)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, speed.u8())
        return listOf(Write(Ep.TX, aesEncrypt(key, "MtInt:%02d%02d;".format(speeds[0], speeds[1]).toByteArray())))
    }

    companion object {
        private val ALNUM = ('a'..'z') + ('A'..'Z') + ('0'..'9')

        private fun spec(id: String, keyText: String, authLen: Int, challenge: Regex, hashGroup: Int) =
            specInit(id) { def, io ->
                val key = keyText.toByteArray(Charsets.US_ASCII)
                io.subscribe(Ep.RX)
                val rng = SecureRandom()
                val auth = (1..authLen).map { ALNUM[rng.nextInt(ALNUM.size)] }.joinToString("")
                io.write(Write(Ep.TX, aesEncrypt(key, "Auth:$auth;".toByteArray())))
                repeat(5) {
                    val n = io.awaitNotification(Ep.RX, 5_000) ?: error("$id didn't answer the security handshake")
                    val decoded = aesDecrypt(key, n)?.toString(Charsets.UTF_8) ?: error("$id sent an invalid handshake")
                    if (decoded == "OK;") return@specInit AesTextVibrator(def, key)
                    val toHash = challenge.matchEntire(decoded)?.groupValues?.get(hashGroup)
                        ?: error("$id didn't provide a valid security handshake")
                    val h = MessageDigest.getInstance("SHA-256").digest(toHash.toByteArray())
                    io.write(Write(Ep.TX, aesEncrypt(key, "Auth:%02x%02x;".format(h[0], h[1]).toByteArray())))
                }
                error("$id handshake did not complete")
            }

        val vibCrafter = spec("vibcrafter", "jdk#Cra%f5Vib28r", 6, Regex("^[a-zA-Z0-9]{4}:([a-zA-Z0-9]+);$"), 1)
        val vibio = spec("vibio", "jdk#vib%y5fir21a", 8, Regex("^([0-9A-Fa-f]{4}):([^;]+);$"), 2)
    }
}

internal fun aesEncrypt(key: ByteArray, data: ByteArray): ByteArray =
    Cipher.getInstance("AES/ECB/PKCS5Padding").run { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(data) }

internal fun aesDecrypt(key: ByteArray, data: ByteArray): ByteArray? = runCatching {
    Cipher.getInstance("AES/ECB/PKCS5Padding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES")); doFinal(data) }
}.getOrNull()
