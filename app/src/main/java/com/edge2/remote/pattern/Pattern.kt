package com.edge2.remote.pattern

import kotlinx.serialization.Serializable

/**
 * Une étape de pattern : intensités cibles des deux moteurs maintenues
 * pendant [durationMs] millisecondes.
 *
 * [m1] = moteur 1 (`Vibrate1`), [m2] = moteur 2 (`Vibrate2`), tous deux 0..20.
 */
@Serializable
data class PatternStep(
    val m1: Int,
    val m2: Int,
    val durationMs: Long,
)

/**
 * Un pattern = nom + séquence d'étapes, jouable en boucle.
 *
 * Format JSON simple, sérialisable/partageable (cf. [PatternIO]). Exemple :
 * ```json
 * {
 *   "name": "Vague",
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
