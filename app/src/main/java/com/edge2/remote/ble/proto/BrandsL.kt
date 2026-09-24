package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition

// Ports of Buttplug protocols K–L (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

class Kizuna(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun rotate(index: Int, speed: Int) = listOf(w(Ep.TX, (48 + speed).u8(), '\r'.code, '\n'.code))
}

/**
 * Lelo security handshake: the toy notifies a key on [endpoint], which must be
 * written back; all-zero means "tap the power button", 01 00… means authorised.
 */
private suspend fun leloAuthorize(io: DeviceIo, endpoint: String) {
    io.subscribe(endpoint)
    val deadline = System.currentTimeMillis() + 60_000
    while (System.currentTimeMillis() < deadline) {
        val n = io.awaitNotification(endpoint, deadline - System.currentTimeMillis())
            ?: break
        when {
            n.all { it.toInt() == 0 } -> io.hint(Hint.PRESS_POWER_BUTTON)
            n.isNotEmpty() && n[0].toInt() == 1 && n.drop(1).all { it.toInt() == 0 } -> { io.hint(null); return }
            else -> {
                io.unsubscribe(endpoint)
                io.write(Write(endpoint, n, true))
                io.subscribe(endpoint)
            }
        }
    }
    io.hint(null)
    error("Lelo didn't complete the security handshake (tap the toy's power button)")
}

class LeloF1s(def: DeviceDefinition, private val withResponse: Boolean) : ProtocolHandler(def) {
    private val speeds = IntArray(2)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, speed.u8())
        return listOf(w(Ep.TX, 0x01, speeds[0], speeds[1], withResponse = withResponse))
    }

    companion object {
        val spec = specInit("lelo-f1s") { def, io ->
            io.subscribe(Ep.RX)
            LeloF1s(def, false)
        }
        val specV2 = specInit("lelo-f1sv2") { def, io ->
            val harmony = !io.hasEndpoint(Ep.WHITELIST)
            leloAuthorize(io, if (harmony) Ep.GENERIC0 else Ep.WHITELIST)
            if (harmony) LeloHarmony(def) else LeloF1s(def, true)
        }
    }
}

class LeloHarmony(def: DeviceDefinition) : ProtocolHandler(def) {
    private fun cmd(index: Int, speed: Int) =
        listOf(w(Ep.TX, 0x0a, 0x12, index + 1, 0x08, 0, 0, 0, 0, speed.u8(), 0x00))
    override fun rotate(index: Int, speed: Int) = cmd(index, speed)
    override fun vibrate(index: Int, speed: Int) = cmd(index, speed)

    companion object {
        val spec = specInit("lelo-harmony") { def, io ->
            leloAuthorize(io, Ep.WHITELIST)
            LeloHarmony(def)
        }
    }
}

/** Leten: needs the last packet repeated every second. */
class Leten(def: DeviceDefinition) : ProtocolHandler(def) {
    override val keepaliveMs: Long = 1_000
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x02, speed.u8(), withResponse = true))

    companion object {
        val spec = specInit("leten") { def, io ->
            io.write(w(Ep.TX, 0x04, 0x01, withResponse = true))
            Leten(def)
        }
    }
}

class LiboElle(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int): List<Write> {
        val s = speed.u8()
        if (index == 1) {
            var data = 0
            if (s in 1..7) data = ((s - 1) shl 4) or 1
            else if (s > 7) data = ((s - 8) shl 4) or 4
            return listOf(w(Ep.TX, data.u8()))
        }
        return listOf(w(Ep.TX_MODE, s))
    }
}

class LiboShark(def: DeviceDefinition) : ProtocolHandler(def) {
    private val values = IntArray(2)
    override fun vibrate(index: Int, speed: Int): List<Write> {
        values.setSafe(index, speed.u8())
        return listOf(w(Ep.TX, ((values[0] shl 4) or values[1]).u8()))
    }
}

class LiboVibes(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int): List<Write> = when (index) {
        0 -> buildList {
            add(w(Ep.TX, speed.u8()))
            if (speed.u8() == 0) add(w(Ep.TX_MODE, 0))
        }
        1 -> listOf(w(Ep.TX_MODE, speed.u8()))
        else -> emptyList()
    }
}

class Lioness(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0x02, 0xAA, 0xBB, 0xCC, 0xCC, speed.u8()))

    companion object {
        val spec = specInit("lioness") { def, io ->
            io.subscribe(Ep.RX)
            if (!io.write(w(Ep.TX, 0x01, 0xAA, 0xAA, 0xBB, 0xCC, 0x10, withResponse = true))) {
                io.hint(Hint.PAIR_WITH_PIN_6496)
                error("Lioness needs OS pairing (PIN 6496 or 006496)")
            }
            Lioness(def)
        }
    }
}

class Loob(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun hwPosition(index: Int, position: Int, durationMs: Int): List<Write> {
        val pos = (position and 0xffff).coerceIn(1, 1000)
        val time = (durationMs and 0xffff).coerceAtLeast(1)
        return listOf(w(Ep.TX, pos shr 8, pos and 0xff, time shr 8, time and 0xff))
    }

    companion object {
        val spec = specInit("loob") { def, io ->
            io.write(w(Ep.TX, 0x00, 0x01, 0x01, 0xf4, withResponse = true))
            Loob(def)
        }
    }
}
