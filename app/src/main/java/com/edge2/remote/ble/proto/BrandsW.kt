package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import com.edge2.remote.ble.db.OutputType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// Ports of Buttplug protocols W–X (BSD-3-Clause, see THIRD_PARTY_NOTICES.md).

class WeToy(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(
        if (speed == 0) w(Ep.TX, 0x80, 0x03, withResponse = true) else w(Ep.TX, 0xb2, (speed - 1).u8(), withResponse = true),
    )

    companion object {
        val spec = specInit("wetoy") { def, io ->
            io.write(w(Ep.TX, 0x80, 0x03, withResponse = true))
            WeToy(def)
        }
    }
}

/** We-Vibe: [internal, external] motors; three frame layouts depending on the generation. */
class WeVibe(def: DeviceDefinition, private val layout: Layout) : ProtocolHandler(def) {
    enum class Layout { CLASSIC, EIGHT_BIT, CHORUS }

    private val speeds = IntArray(2)
    private val dual = def.count(OutputType.VIBRATE) > 1

    override fun vibrate(index: Int, speed: Int): List<Write> {
        speeds.setSafe(index, speed.u8())
        val int = speeds[0]
        val ext = speeds[if (dual) 1 else 0]
        if (int == 0 && ext == 0) return listOf(w(Ep.TX, 0x0f, 0, 0, 0, 0, 0, 0, 0, withResponse = true))
        val status = (if (ext == 0) 0 else 2) or (if (int == 0) 0 else 1)
        val data = when (layout) {
            Layout.CLASSIC -> listOf(0x0f, 0x03, 0x00, (ext or (int shl 4)).u8(), 0x00, 0x03, 0x00, 0x00)
            Layout.EIGHT_BIT -> listOf(0x0f, 0x03, 0x00, (ext + 3).u8(), (int + 3).u8(), status, 0x00, 0x00)
            Layout.CHORUS -> listOf(0x0f, 0x03, 0x00, int, ext, status, 0x00, 0x00)
        }
        return listOf(w(Ep.TX, data, withResponse = true))
    }

    companion object {
        val spec = specInit("wevibe") { def, io ->
            io.write(w(Ep.TX, 0x0f, 0x03, 0x00, 0x99, 0x00, 0x03, 0x00, 0x00, withResponse = true))
            io.write(w(Ep.TX, 0x0f, 0, 0, 0, 0, 0, 0, 0, withResponse = true))
            WeVibe(def, Layout.CLASSIC)
        }
        val spec8Bit = spec("wevibe-8bit") { WeVibe(it, Layout.EIGHT_BIT) }
        val specChorus = spec("wevibe-chorus") { WeVibe(it, Layout.CHORUS) }
    }
}

class Xibao(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun oscillate(index: Int, speed: Int) = listOf(
        w(Ep.TX, 0x66, 0x3a, 0x00, 0x06, 0x00, 0x06, 0x01, 0x02, 0x00, 0x02, 0x04, speed.u8(), (speed + 0xb5).u8()),
    )
}

class Xiuxiuda(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun vibrate(index: Int, speed: Int) = listOf(w(Ep.TX, 0, 0, 0, 0, 0x65, 0x3a, 0x30, speed.u8(), 0x64))
}

/** Xuanhuan: the speed must be re-sent every 300 ms while running. */
class Xuanhuan(def: DeviceDefinition) : ProtocolHandler(def) {
    @Volatile private var speed = 0

    override fun start(io: DeviceIo, scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                val s = speed
                if (s != 0 && !io.write(w(Ep.TX, 0x03, 0x02, 0x00, s, withResponse = true))) return@launch
                delay(300)
            }
        }
    }

    override fun vibrate(index: Int, speed: Int): List<Write> {
        this.speed = speed.u8()
        return listOf(w(Ep.TX, 0x03, 0x02, 0x00, speed.u8(), withResponse = true))
    }
}
