package com.edge2.remote.pattern

import kotlinx.serialization.json.Json

/** Sérialisation JSON des patterns (partage / import / export). */
object PatternIO {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun encode(pattern: Pattern): String = json.encodeToString(Pattern.serializer(), pattern)

    /** Renvoie null si le JSON est invalide (au lieu de jeter). */
    fun decodeOrNull(raw: String): Pattern? =
        runCatching { json.decodeFromString(Pattern.serializer(), raw) }.getOrNull()
}

/** Quelques patterns intégrés en presets. */
object BuiltinPatterns {

    /** Va-et-vient entre les deux moteurs. */
    val wave = Pattern(
        name = "Vague",
        steps = listOf(
            PatternStep(m1 = 20, m2 = 0, durationMs = 450),
            PatternStep(m1 = 10, m2 = 10, durationMs = 250),
            PatternStep(m1 = 0, m2 = 20, durationMs = 450),
            PatternStep(m1 = 10, m2 = 10, durationMs = 250),
        ),
    )

    /** Pulsations courtes des deux moteurs. */
    val pulse = Pattern(
        name = "Pulse",
        steps = listOf(
            PatternStep(m1 = 20, m2 = 20, durationMs = 200),
            PatternStep(m1 = 0, m2 = 0, durationMs = 200),
        ),
    )

    /** Montée progressive puis coupure. */
    val ramp = Pattern(
        name = "Montée",
        steps = (0..20 step 2).map { lvl ->
            PatternStep(m1 = lvl, m2 = lvl, durationMs = 180)
        } + PatternStep(m1 = 0, m2 = 0, durationMs = 300),
    )

    val all = listOf(wave, pulse, ramp)
}
