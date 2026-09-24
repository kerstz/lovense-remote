package com.edge2.remote.ble

import com.edge2.remote.ble.db.Advertisement
import com.edge2.remote.ble.db.DeviceDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceDatabaseTest {
    private val db get() = TestDb.db

    @Test fun loadsHundredsOfModels() {
        assertTrue(db.protocols.size > 120)
        assertTrue(db.protocols.sumOf { it.configs.size + 1 } > 800)
    }

    @Test fun identifiesByNameWildcardAndManufacturerData() {
        assertEquals("lovense", db.identify(Advertisement("LVS-Edge01"))?.id)
        assertEquals("joyhub", db.identify(Advertisement("J-Ringstar"))?.id)
        assertEquals("satisfyer", db.identify(Advertisement("SF Curvy 2+"))?.id) // "SF *"
        assertEquals("satisfyer", db.identify(Advertisement(null, mapOf(93 to byteArrayOf(0, 0, 39, 21))))?.id)
        assertNull(db.identify(Advertisement("JBL Flip 5")))
        assertNull(db.identify(Advertisement(null)))
    }

    @Test fun manufacturerDataRuleMatchesButtplug() {
        assertTrue(DeviceDatabase.mfrMatches(listOf(0, 0, 39), byteArrayOf(0, 0, 39, 21)))
        assertTrue(DeviceDatabase.mfrMatches(listOf(0, 0, 39), byteArrayOf(7, 0, 0, 39)))
        assertTrue(!DeviceDatabase.mfrMatches(listOf(0, 0, 40), byteArrayOf(0, 0, 39, 21)))
    }

    @Test fun resolvesLovenseModelsAndFeatures() {
        val edge = ToyType.from(db.definition("lovense", "P")!!, "Lovense")
        assertEquals("Lovense Edge", edge.displayName)
        assertTrue(edge.isDualVibrate)
        val max = ToyType.from(db.definition("lovense", "B")!!, "Lovense")
        assertEquals(listOf(ActuatorKind.VIBRATE, ActuatorKind.CONSTRICT), max.actuators.map { it.kind })
        val nora = ToyType.from(db.definition("lovense", "A")!!, "Lovense")
        assertTrue(nora.actuators[1].reversible)
        val lush = ToyType.from(db.definition("lovense", "S")!!, "Lovense")
        assertEquals(1, lush.actuators.size) // no features in config → defaults
        val unknown = db.definition("lovense", "ZZZ")!!
        assertEquals(1, unknown.features.count { it.outputs.isNotEmpty() })
    }

    @Test fun heatingLightAndPumpAreExposed() {
        val kinds = db.protocols.flatMap { p ->
            (p.configs.map { db.definition(p.id, it.ids.firstOrNull()) } + db.definition(p.id, null))
                .filterNotNull().flatMap { d -> ToyType.from(d, "x").actuators.map { it.kind } }
        }.toSet()
        assertTrue(ActuatorKind.TEMPERATURE in kinds)
        assertTrue(ActuatorKind.LED in kinds)
        assertTrue(ActuatorKind.SPRAY in kinds)
        assertTrue(ActuatorKind.STROKE in kinds)
        val fatima = ToyType.from(db.definition("svakom-fatima", "SL278B")!!, "Svakom")
        assertNotNull(fatima.actuators.firstOrNull { it.kind == ActuatorKind.TEMPERATURE })
    }

    @Test fun actuatorLevels() {
        val v = Actuator(0, ActuatorKind.VIBRATE, 0, 20)
        assertEquals(10, v.levelFor(0.5f))
        assertEquals(0, v.levelFor(0f))
        val heat = Actuator(0, ActuatorKind.TEMPERATURE, 37, 42)
        assertNull(heat.levelFor(0f)) // range without 0: nothing to send
        assertEquals(42, heat.levelFor(1f))
        assertTrue(Actuator(0, ActuatorKind.TEMPERATURE, 0, 1).isToggle)
        assertTrue(Actuator(0, ActuatorKind.ROTATE, -20, 20).reversible)
    }
}
