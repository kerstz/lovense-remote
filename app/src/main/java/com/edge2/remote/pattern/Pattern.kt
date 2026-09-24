package com.edge2.remote.pattern

import kotlinx.serialization.Serializable

/**
 * One pattern step: target intensities of the two motors, held for
 * [durationMs] milliseconds.
 *
 * [m1] = actuator 1, [m2] = actuator 2, both on the shared 0..20 scale.
 */
@Serializable
data class PatternStep(
    val m1: Int,
    val m2: Int,
    val durationMs: Long,
)

/**
 * A pattern = a name + a sequence of steps, optionally looped.
 *
 * Simple JSON format, serializable/shareable (see [PatternIO]). Example:
 * ```json
 * {
 *   "name": "Wave",
 *   "loop": true,
 *   "steps": [
 *     { "m1": 0,  "m2": 20, "durationMs": 400 },
 *     { "m1": 20, "m2": 0,  "durationMs": 400 }
 *   ]
 * }
 * ```
 */
@Serializable
data class Pattern(
    val name: String,
    val steps: List<PatternStep>,
    val loop: Boolean = true,
)
