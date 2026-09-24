package com.edge2.remote.ble

import com.edge2.remote.ble.db.DeviceDefinition
import com.edge2.remote.ble.proto.FMachine
import com.edge2.remote.ble.proto.Fredorch
import com.edge2.remote.ble.proto.Galaku
import com.edge2.remote.ble.proto.HoneyPlayBox
import com.edge2.remote.ble.proto.JoyHub
import com.edge2.remote.ble.proto.LovenseDual
import com.edge2.remote.ble.proto.LovenseMply
import com.edge2.remote.ble.proto.LovenseRotateVibrator
import com.edge2.remote.ble.proto.LovenseSpec
import com.edge2.remote.ble.proto.MizzZeeV3
import com.edge2.remote.ble.proto.Protocols
import com.edge2.remote.ble.proto.Satisfyer
import com.edge2.remote.ble.proto.TryFun
import com.edge2.remote.ble.proto.TryFunBlackHole
import com.edge2.remote.ble.proto.WeVibe
import com.edge2.remote.ble.proto.lovenseBattery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolsTest {
    private val db get() = TestDb.db
    private fun def(id: String, ident: String? = null): DeviceDefinition = db.definition(id, ident)!!
    private fun txt(w: List<com.edge2.remote.ble.proto.Write>) = w.map { String(it.data, Charsets.US_ASCII) }

    /** Protocols present in the database that are intentionally not implemented. */
    private val notImplemented = setOf(
        "thehandy", "thehandy-v3", // protobuf/cloud stack
        "kgoal-boost", // sensor only
        "cueme", "muse", "sayberx", "twerkingbutt", "libo-karen", "kiiroo-v1", // no implementation upstream either
    )

    @Test fun everyDatabaseProtocolIsImplementedOrListed() {
        val missing = db.protocols.map { it.id }.filter { Protocols.get(it) == null && it !in notImplemented }
        assertEquals(emptyList<String>(), missing)
        assertTrue(Protocols.ids.size >= 125)
    }

    /**
     * Builds every handler for every model and drives each actuator: writes must
     * only target endpoints that exist for that protocol in the database.
     */
    @Test fun everyHandlerWritesOnlyToKnownEndpoints() = runBlocking {
        var models = 0
        for (p in db.protocols) {
            val spec = Protocols.get(p.id) ?: continue
            val endpoints = p.btle.services.values.flatMap { it.keys }.toSet() + if (p.id == "lovense") setOf("tx", "rx") else emptySet()
            val ids = listOf<String?>(null) + p.configs.flatMap { it.ids }
            for (ident in ids) {
                val d = db.definition(p.id, ident) ?: continue
                val io = FakeIo(name = ident ?: "x", endpointNames = endpoints)
                val h = runCatching { spec.create(d, io, ident) }.getOrNull() ?: continue // handshake-only protocols
                models++
                val toy = ToyType.from(d, "x")
                for (a in toy.actuators) {
                    val hi = a.levelFor(1f) ?: a.max
                    val writes = when (a.kind) {
                        ActuatorKind.VIBRATE -> h.vibrate(a.featureIndex, hi) + h.vibrate(a.featureIndex, 0)
                        ActuatorKind.ROTATE -> h.rotate(a.featureIndex, hi) + h.rotate(a.featureIndex, if (a.min < 0) -hi else 0)
                        ActuatorKind.OSCILLATE -> h.oscillate(a.featureIndex, hi) + h.oscillate(a.featureIndex, 0)
                        ActuatorKind.CONSTRICT -> h.constrict(a.featureIndex, hi)
                        ActuatorKind.TEMPERATURE -> h.temperature(a.featureIndex, hi)
                        ActuatorKind.LED -> h.led(a.featureIndex, hi)
                        ActuatorKind.SPRAY -> h.spray(a.featureIndex, hi)
                        ActuatorKind.POSITION -> h.position(a.featureIndex, hi)
                        ActuatorKind.STROKE -> h.hwPosition(a.featureIndex, hi, 500)
                    }
                    for (w in writes + io.writes) {
                        assertTrue("${p.id}/$ident ${a.kind} → ${w.endpoint} not in $endpoints", w.endpoint in endpoints)
                    }
                }
            }
        }
        assertTrue("only $models models exercised", models > 700)
    }

    @Test fun lovenseCommands() {
        val edge = LovenseDual(def("lovense", "P"))
        assertEquals(listOf("Vibrate1:5;"), txt(edge.vibrate(0, 5)))
        assertEquals(listOf("Vibrate2:20;"), txt(edge.vibrate(1, 20)))
        val lapis = LovenseMply(def("lovense", "U"), 3)
        lapis.vibrate(0, 3); lapis.vibrate(2, 9)
        assertEquals(listOf("Mply:3:4:9;"), txt(lapis.vibrate(1, 4)))
        val nora = LovenseRotateVibrator(def("lovense", "A"))
        assertEquals(listOf("Rotate:10;"), txt(nora.rotate(1, 10)))
        assertEquals(listOf("RotateChange;", "Rotate:10;"), txt(nora.rotate(1, -10)))
        assertEquals(listOf("Rotate:12;"), txt(nora.rotate(1, -12))) // same direction: no toggle
        assertEquals("P", LovenseSpec.modelFromDeviceType("P:02:0082059AD3BD;"))
        assertEquals("EI-FW3", LovenseSpec.modelFromDeviceType("EI:03:AA;"))
        assertEquals(85, lovenseBattery("85;".toByteArray()))
        assertEquals(64, lovenseBattery("s64;".toByteArray()))
    }

    @Test fun lovenseIdentifiesThenFallsBackToName() = runBlocking {
        val io = FakeIo(name = "LVS-B011")
        io.pendingNotifications += "B:11:0082059AD3BD;".toByteArray()
        assertEquals("B", LovenseSpec.identify(io).identifier)
        assertEquals("DeviceType;", String(io.writes.first().data))
        assertEquals("Z", LovenseSpec.identify(FakeIo(name = "LVS-Z001")).identifier)
    }

    @Test fun joyHubHeatingLightAndPump() {
        val h = JoyHub(def("joyhub"))
        assertEquals(listOf(0xa0, 0x03, 50, 0, 0, 0, 0xaa), ints(h.vibrate(0, 50).single().data))
        assertEquals(listOf(0xa0, 0x03, 50, 7, 0, 0, 0xaa), ints(h.oscillate(1, 7).single().data))
        assertEquals(listOf(0xa0, 0x04, 1, 0, 1, 0xff), ints(h.temperature(0, 1).single().data))
        assertEquals(listOf(0xa0, 0x04, 0, 0, 0, 0), ints(h.temperature(0, 0).single().data))
        assertEquals(listOf(0xa0, 0x14, 1, 0, 1, 0xff), ints(h.led(0, 1).single().data))
        assertEquals(listOf(0xa0, 0x24, 1, 0, 1, 0xff), ints(h.spray(0, 1).single().data))
    }

    @Test fun goldenBytesFromReferenceImplementation() {
        // Values computed by an independent re-implementation of the Rust code.
        assertEquals(listOf(35, 129, 187, 171, 210, 236, 49, 195, 187, 163, 59, 154),
            ints(Galaku.sendBytes(intArrayOf(90, 0, 0, 1, 49, 10, 0, 0, 0, 0))))
        assertEquals(listOf(35, 129, 187, 171, 210, 123, 68, 56, 38, 59, 59, 238),
            ints(Galaku.sendBytes(intArrayOf(90, 0, 0, 1, 64, 3, 5, 7, 0, 0))))
        assertEquals(listOf(2, 165, 90, 85, 170, 240, 0, 177, 1, 0, 2, 0, 16, 3, 54, 134),
            ints(HoneyPlayBox.buildFrame(0xB1, 0x01, byteArrayOf(0, 0x10), 0)))
        assertEquals(listOf(170, 2, 7, 50, 202), ints(TryFun(def("tryfun")).oscillate(0, 50).single().data))
        assertEquals(listOf(0, 2, 0, 3, 9, 30, 231), ints(TryFunBlackHole(def("tryfun-blackhole")).vibrate(0, 30).single().data))
        assertEquals(listOf(3, 18, 243, 0, 252, 0, 254, 64, 1, 60, 166, 0, 252, 0, 254, 64, 1, 60, 166, 0),
            ints(MizzZeeV3.vector(500)))
    }

    @Test fun satisfyerAndWeVibeFrames() {
        val s = Satisfyer(def("satisfyer", "10006")) // Heated Affair: 2 motors
        s.vibrate(0, 40)
        assertEquals(listOf(40, 40, 40, 40, 70, 70, 70, 70), ints(s.vibrate(1, 70).single().data))
        val wv = WeVibe(def("wevibe", "Sync"), WeVibe.Layout.CLASSIC)
        wv.vibrate(0, 15)
        assertEquals(listOf(0x0f, 0x03, 0x00, 0xF3, 0x00, 0x03, 0x00, 0x00), ints(wv.vibrate(1, 3).single().data))
    }

    @Test fun checksumsMatchUpstream() {
        // Fredorch: Buttplug's table CRC is CRC-16/MODBUS, low byte first.
        val w = Fredorch.withCrc(listOf(0x01, 0x06, 0x00, 0x64, 0x00, 0x01))
        assertEquals(listOf(0x01, 0x06, 0x00, 0x64, 0x00, 0x01, 0x09, 0xd5), ints(w.data))
        assertEquals(18, FMachine.makeCmd(0x01).size)
    }
}
