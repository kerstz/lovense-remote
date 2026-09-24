package com.edge2.remote.ble.proto

import com.edge2.remote.ble.db.DeviceDefinition
import com.edge2.remote.ble.db.OutputType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lovense — ASCII commands terminated by `;` (confirmed on the Edge 2, see
 * PROTOCOL.md). Port of Buttplug's `lovense` protocol: the model is identified
 * with `DeviceType;` (falling back to the BLE name), then one of several
 * command styles is picked from the model's features.
 */
object LovenseSpec : ProtocolSpec {
    override val id = "lovense"

    override suspend fun identify(io: DeviceIo): Identified = Identified(identifyCode(io))

    private suspend fun identifyCode(io: DeviceIo): String? {
        io.subscribe(Ep.RX)
        repeat(6) {
            io.write(ascii(Ep.TX, "DeviceType;"))
            val reply = io.awaitNotification(Ep.RX, 500)
            if (reply != null) return modelFromDeviceType(String(reply, Charsets.US_ASCII))
        }
        return Regex("LVS-([A-Z]+)\\d+").find(io.name)?.groupValues?.get(1)
    }

    /** "P:02:0082059AD3BD;" → "P"; Flexer on firmware ≥ 3 → "EI-FW3". */
    fun modelFromDeviceType(reply: String): String? {
        val parts = reply.trim().trimEnd(';').split(':')
        if (parts.size < 2) return null
        val id = parts[0]
        val version = parts[1].toIntOrNull() ?: 0
        return if (id == "EI" && version >= 3) "EI-FW3" else id
    }

    override suspend fun create(def: DeviceDefinition, io: DeviceIo, identifier: String?): ProtocolHandler {
        val vibrators = def.features.count { f -> f.outputs.any { it.type == OutputType.VIBRATE || it.type == OutputType.OSCILLATE } }
        val outputs = def.outputFeatureCount
        val one = { t: OutputType -> def.features.count { f -> f.outputs.any { it.type == t } } == 1 }
        val vibratorRotator = outputs == 2 && one(OutputType.VIBRATE) && one(OutputType.ROTATE)
        val max = outputs == 2 && one(OutputType.VIBRATE) && one(OutputType.CONSTRICT)
        val mply = (vibrators == 2 && outputs > 2) || vibrators > 2
        val code = identifier.orEmpty()
        return when {
            code == "BA" || code == "H" -> LovenseStroker(def, needRangeZeroed = code == "H")
            outputs == 1 -> LovenseSingle(def)
            max -> LovenseMax(def)
            vibratorRotator -> LovenseRotateVibrator(def)
            mply -> LovenseMply(def, outputs)
            else -> LovenseDual(def)
        }
    }
}

/** Parses a Lovense battery reply ("85;" or "s85;"). */
internal fun lovenseBattery(data: ByteArray): Int? {
    val s = String(data, Charsets.US_ASCII).trim()
    if (!s.endsWith(";")) return null
    val start = if (s.contains('s')) 1 else 0
    return s.substring(start, s.length - 1).toIntOrNull()?.takeIf { it in 0..100 }
}

abstract class LovenseBase(def: DeviceDefinition) : ProtocolHandler(def) {
    override fun onNotification(endpoint: String, data: ByteArray): Int? = lovenseBattery(data)

    override suspend fun battery(io: DeviceIo): Int? {
        io.write(ascii(Ep.TX, "Battery;"))
        return null // the reply arrives as a notification (onNotification)
    }

    protected fun cmd(text: String) = listOf(ascii(Ep.TX, text))
}

class LovenseSingle(def: DeviceDefinition) : LovenseBase(def) {
    override fun vibrate(index: Int, speed: Int) = cmd("Vibrate:$speed;")
    override fun oscillate(index: Int, speed: Int) = cmd("Vibrate:$speed;")
}

class LovenseDual(def: DeviceDefinition) : LovenseBase(def) {
    override fun vibrate(index: Int, speed: Int) = cmd("Vibrate${index + 1}:$speed;")
    override fun oscillate(index: Int, speed: Int) = cmd("Vibrate${index + 1}:$speed;")
}

class LovenseMax(def: DeviceDefinition) : LovenseBase(def) {
    override fun vibrate(index: Int, speed: Int) = cmd("Vibrate:$speed;")
    override fun oscillate(index: Int, speed: Int) = cmd("Vibrate:$speed;")
    override fun constrict(index: Int, level: Int) = cmd("Air:Level:$level;")
}

/** Vibrator + rotator (Nora, Velvo, Ridge): `RotateChange;` flips the direction. */
class LovenseRotateVibrator(def: DeviceDefinition) : LovenseBase(def) {
    private var clockwise = true
    override fun vibrate(index: Int, speed: Int) = cmd("Vibrate:$speed;")
    override fun rotate(index: Int, speed: Int): List<Write> {
        val out = mutableListOf<Write>()
        // Only toggle when the requested direction differs from the current one.
        if (speed != 0 && (speed > 0) != clockwise) {
            clockwise = speed > 0
            out += ascii(Ep.TX, "RotateChange;")
        }
        out += ascii(Ep.TX, "Rotate:${kotlin.math.abs(speed)};")
        return out
    }
}

/** Three or more motors (Lapis, Flexer…): one `Mply:a:b:c;` packet. */
class LovenseMply(def: DeviceDefinition, count: Int) : LovenseBase(def) {
    private val values = IntArray(count)
    private fun packet() = cmd("Mply:" + values.joinToString(":") + ";")
    override fun vibrate(index: Int, speed: Int): List<Write> { values.setSafe(index, speed); return packet() }
    override fun oscillate(index: Int, speed: Int): List<Write> { values.setSafe(index, speed); return packet() }
    override fun rotate(index: Int, speed: Int): List<Write> { values.setSafe(index, kotlin.math.abs(speed)); return packet() }
}

/** Solace / Solace Pro: speed via `Mply`, position via a `FSetSite` loop. */
class LovenseStroker(def: DeviceDefinition, private val needRangeZeroed: Boolean) : LovenseBase(def) {
    @Volatile private var goal = 0
    @Volatile private var current = 0
    @Volatile private var duration = 0

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
                if (!io.write(ascii(Ep.TX, "FSetSite:$pos;"))) return@launch
                delay(100)
            }
        }
    }

    override fun hwPosition(index: Int, position: Int, durationMs: Int): List<Write> {
        duration = durationMs
        goal = position
        return emptyList()
    }

    override fun oscillate(index: Int, speed: Int): List<Write> {
        if (speed == 0) { duration = 0; goal = current }
        return cmd("Mply:$speed:${if (speed == 0 && needRangeZeroed) 0 else 20};")
    }
}

internal fun IntArray.setSafe(i: Int, v: Int) { if (i in indices) this[i] = v }
