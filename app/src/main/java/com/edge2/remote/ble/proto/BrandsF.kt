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

// Ports of Buttplug protocols F (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

class FeelingSo(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(2)
    private fun cmd() = listOf(w(Ep.TX, 0xaa, 0x40, 0x03, speeds[0], speeds[1], 0x14, 0x19))
    override fun oscillate(index: Int, speed: Int): List<Write> { speeds[1] = speed.u8(); return cmd() }
    override fun vibrate(index: Int, speed: Int): List<Write> { speeds[0] = speed.u8(); return cmd() }
}

class FleshyThrust(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun hwPosition(index: Int, position: Int, durationMs: Int) =
        listOf(w(Ep.TX, position.u8(), (durationMs and 0xff00) shr 8, durationMs and 0xff))
}

/** Fluffer: AES-128-ECB encrypted packets, with an advertisement-derived handshake. */
class Fluffer(def: DeviceDefinition) : ProtocolHandler(def) {
    private val speeds = IntArray(2)
    private fun send() = listOf(Write(Ep.TX, encrypt(bytes(0x82, 0x0F, 0x05, 0x00, speeds[0], speeds[1], 0x00, 0x00))))
    override fun vibrate(index: Int, speed: Int): List<Write> { speeds.setSafe(index, speed.u8()); return send() }
    override fun oscillate(index: Int, speed: Int): List<Write> { speeds.setSafe(index, speed.u8()); return send() }
    override fun rotate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, (if (speed < 0) kotlin.math.abs(speed) + 100 else speed).u8())
        return send()
    }

    companion object {
        private val KEY = "jdk#Flu%y6fer32f".toByteArray(Charsets.US_ASCII)
        private const val COMPANY = 0x684a // le bytes [0x4a, 0x68]

        fun encrypt(data: ByteArray): ByteArray =
            Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY, "AES")); doFinal(data)
            }

        fun decrypt(data: ByteArray): ByteArray? = runCatching {
            Cipher.getInstance("AES/ECB/PKCS5Padding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY, "AES")); doFinal(data)
            }
        }.getOrNull()

        /** Decodes the 4 advertisement bytes used by the handshake. */
        fun extractAdv(adv: ByteArray): ByteArray {
            val key = (adv[2].toInt() xor adv[1].toInt()) and 0xff
            val out = ArrayList<Byte>()
            var off = 0
            while (out.size < 4 && 3 + off < adv.size) {
                var b = adv[3 + off].toInt() and 0xff
                if (b != 0 && b != key) b = b xor key
                out += b.toByte()
                off++
            }
            return out.toByteArray()
        }

        val spec = specInit("fluffer") { def, io ->
            io.subscribe(Ep.RX)
            val md = io.manufacturerData[COMPANY]
            if (md != null) {
                val adv = byteArrayOf(0x4a, 0x68) + md
                val rand = ByteArray(4).also { SecureRandom().nextBytes(it) }
                val digest = MessageDigest.getInstance("SHA-256").apply { update(rand); update(extractAdv(adv)) }.digest()
                io.write(Write(Ep.TX, encrypt(bytes(0xa5, 0x01, 0x08) + rand + digest.copyOfRange(0, 4))))
                val reply = io.awaitNotification(Ep.RX, 5_000)?.let { decrypt(it) }
                if (reply == null || !reply.contentEquals(bytes(0xa5, 0x01, 0x01, 0x00))) {
                    error("Fluffer didn't provide a valid security handshake")
                }
                io.write(Write(Ep.TX, encrypt(bytes(0x82, 0x0E, 0x02, 0x00, 0x01))))
            }
            Fluffer(def)
        }
    }
}

/**
 * F-Machine: no speed command — speed and on/off are driven by simulated
 * button presses, tracked internally (the device gives no feedback).
 */
class FMachine(def: DeviceDefinition) : ProtocolHandler(def) {
    @Volatile private var target = 0

    override fun start(io: DeviceIo, scope: CoroutineScope) {
        scope.launch {
            var running = false
            var current = 0
            while (isActive) {
                val tp = target
                if (running == (tp == 0)) {
                    if (!press(io, ON_OFF_PRESS, ON_OFF_RELEASE)) return@launch
                    running = !running
                }
                if (tp != current && (tp > 1 || current > 1)) {
                    if (!press(io, if (tp > current) SPEED_UP else SPEED_DOWN, SPEED_RELEASE)) return@launch
                    current += if (tp > current) 1 else -1
                }
                delay(200)
            }
        }
    }

    override fun oscillate(index: Int, speed: Int): List<Write> {
        if (index == 0) target = speed.u8()
        return emptyList()
    }

    companion object {
        private const val ON_OFF_PRESS = 0x01
        private const val ON_OFF_RELEASE = 0x02
        private const val SPEED_RELEASE = 0x03
        private const val SPEED_UP = 0x05
        private const val SPEED_DOWN = 0x06

        fun crc8(data: ByteArray): Int {
            val bits = data.sumOf { Integer.bitCount(it.toInt() and 0xff) }
            return when (bits % 3) {
                0 -> 222 - bits
                1 -> bits / 2 + 111
                else -> bits / 3 + 177
            } and 0xff
        }

        fun makeCmd(command: Int): ByteArray {
            val d = bytes(command, 0x64, 0, 0, 0, 0, 0x31, 0x32, 0x33, 0x34, 0, 0, 0, 0, 0, 0, 0)
            return d + crc8(d).toByte()
        }

        suspend fun press(io: DeviceIo, press: Int, release: Int): Boolean =
            io.write(Write(Ep.TX, makeCmd(press), true)) && io.write(Write(Ep.TX, makeCmd(release), true))

        val spec = specInit("fmachine") { def, io ->
            io.subscribe(Ep.RX)
            // Bring the speed down to its minimum so the internal tracking starts in sync.
            repeat(55) {
                press(io, SPEED_DOWN, SPEED_RELEASE)
                delay(60)
            }
            FMachine(def)
        }
    }
}

class Foreo(def: DeviceDefinition, private val mode: Int) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x01, mode, speed.u8(), withResponse = true))

    companion object {
        val spec = specInit("foreo") { def, io ->
            val n = io.name.lowercase()
            Foreo(def, if (n.contains("smart") && n.contains("2")) 3 else if (n.contains("fofo") || n.contains("ufo")) 1 else 0)
        }
    }
}

class Fox(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x03, 0x01, 0x01, 0xfe, speed.u8()))
}

internal fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
