package com.edge2.remote.ble

import com.edge2.remote.ble.db.DeviceDefinition
import com.edge2.remote.ble.db.FeatureDef
import com.edge2.remote.ble.db.OutputType
import kotlin.math.roundToInt

/**
 * Multi-brand / multi-toy model. A toy = a [ToyType] = a model name + a list of
 * [Actuator]s built from the device database. The UI adapts to the actuator
 * kinds; patterns and remote control drive the "motion" actuators.
 */

/** What an actuator does (drives which control the UI shows). */
enum class ActuatorKind {
    VIBRATE,
    ROTATE,
    /** Thrusting / oscillation speed. */
    OSCILLATE,
    /** Suction / constriction / pump. */
    CONSTRICT,
    /** Heating. */
    TEMPERATURE,
    LED,
    /** Lube pump / spray (momentary). */
    SPRAY,
    /** Absolute position (stroker). */
    POSITION,
    /** Stroker driven by position + duration: the app generates the strokes. */
    STROKE;

    /** Actuators that patterns, presets and remote control drive. */
    val isMotion: Boolean get() = this == VIBRATE || this == ROTATE || this == OSCILLATE || this == CONSTRICT || this == STROKE
}

/** A concrete actuator: kind + the toy's own value range. */
data class Actuator(
    /** Feature index, as the protocol expects it. */
    val featureIndex: Int,
    val kind: ActuatorKind,
    val min: Int,
    val max: Int,
    val description: String? = null,
) {
    /** Rotation with a signed range → the direction can be flipped. */
    val reversible: Boolean get() = kind == ActuatorKind.ROTATE && min < 0

    /** On/off only (e.g. most heaters, LEDs, pumps). */
    val isToggle: Boolean get() = max - maxOf(min, 0) <= 1

    /** Level for a UI fraction (0..1); null = nothing to send (range doesn't include 0). */
    fun levelFor(fraction: Float): Int? {
        val f = fraction.coerceIn(0f, 1f)
        return if (min > 0) {
            if (f <= 0f) null else min + (f * (max - min)).roundToInt()
        } else {
            (f * max).roundToInt()
        }
    }

    /** Fraction (0..1) for a level, for display. */
    fun fractionOf(level: Int): Float = when {
        max <= 0 -> 0f
        min > 0 -> if (level <= 0) 0f else (level - min).toFloat() / (max - min).coerceAtLeast(1)
        else -> kotlin.math.abs(level).toFloat() / max
    }
}

/** A toy model: protocol, brand, display name, actuators. */
data class ToyType(
    val protocolId: String,
    val brand: String,
    val displayName: String,
    val actuators: List<Actuator>,
    val battery: Boolean = false,
) {
    /** Only Lovense has been tested on hardware here; the rest is community protocol data. */
    val experimental: Boolean get() = protocolId != "lovense"

    /** Indices (in [actuators]) of the motion actuators, in order. */
    val motion: List<Int> get() = actuators.indices.filter { actuators[it].kind.isMotion }

    /** Exactly two vibrators as motion actuators → one-thumb XY pad. */
    val isDualVibrate: Boolean
        get() = motion.size == 2 && motion.all { actuators[it].kind == ActuatorKind.VIBRATE }

    companion object {
        fun from(def: DeviceDefinition, brand: String): ToyType = ToyType(
            protocolId = def.protocolId,
            brand = brand,
            displayName = def.name,
            actuators = def.features.mapNotNull { it.toActuator() },
            battery = def.features.any { it.battery },
        )

        /** Picks the output a feature is driven by (one control per feature). */
        private fun FeatureDef.toActuator(): Actuator? {
            val o = output(OutputType.OSCILLATE)
                ?: output(OutputType.HW_POSITION)
                ?: output(OutputType.POSITION)
                ?: outputs.firstOrNull()
                ?: return null
            val kind = when (o.type) {
                OutputType.VIBRATE -> ActuatorKind.VIBRATE
                OutputType.ROTATE -> ActuatorKind.ROTATE
                OutputType.OSCILLATE -> ActuatorKind.OSCILLATE
                OutputType.CONSTRICT -> ActuatorKind.CONSTRICT
                OutputType.TEMPERATURE -> ActuatorKind.TEMPERATURE
                OutputType.LED -> ActuatorKind.LED
                OutputType.SPRAY -> ActuatorKind.SPRAY
                OutputType.POSITION -> ActuatorKind.POSITION
                OutputType.HW_POSITION -> ActuatorKind.STROKE
            }
            return Actuator(index, kind, o.min, o.max, description)
        }
    }
}
