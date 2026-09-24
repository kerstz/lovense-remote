package com.edge2.remote.pattern

import kotlinx.serialization.json.Json

/** JSON (de)serialization of patterns (share / import / export). */
object PatternIO {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun encode(pattern: Pattern): String = json.encodeToString(Pattern.serializer(), pattern)

    /** Returns null if the JSON is invalid (instead of throwing). */
    fun decodeOrNull(raw: String): Pattern? =
        runCatching { json.decodeFromString(Pattern.serializer(), raw) }.getOrNull()
}

/** A few built-in preset patterns. */
object BuiltinPatterns {

    /** Back and forth between the two motors. */
    val wave = Pattern(
        name = "Wave",
        steps = listOf(
            PatternStep(m1 = 20, m2 = 0, durationMs = 450),
            PatternStep(m1 = 10, m2 = 10, durationMs = 250),
            PatternStep(m1 = 0, m2 = 20, durationMs = 450),
            PatternStep(m1 = 10, m2 = 10, durationMs = 250),
        ),
    )

    /** Short pulses on both motors. */
    val pulse = Pattern(
        name = "Pulse",
        steps = listOf(
            PatternStep(m1 = 20, m2 = 20, durationMs = 200),
            PatternStep(m1 = 0, m2 = 0, durationMs = 200),
        ),
    )

    /** Progressive ramp-up, then cut. */
    val ramp = Pattern(
        name = "Ramp",
        steps = (0..20 step 2).map { lvl ->
            PatternStep(m1 = lvl, m2 = lvl, durationMs = 180)
        } + PatternStep(m1 = 0, m2 = 0, durationMs = 300),
    )

    val all = listOf(wave, pulse, ramp)
}
