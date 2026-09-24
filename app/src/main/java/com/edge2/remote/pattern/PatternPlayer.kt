package com.edge2.remote.pattern

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Destination of a pattern's levels (m1/m2 on the 0..20 scale). */
interface PatternSink {
    fun apply(m1: Int, m2: Int)
    fun stopAll()
}

/**
 * Plays a [Pattern] by sending its intensities to the toys through [sink],
 * optionally looping. One playback at a time.
 */
class PatternPlayer(
    private val sink: PatternSink,
    private val scope: CoroutineScope,
) {
    /** Name of the pattern being played, or null when stopped. */
    private val _playing = MutableStateFlow<String?>(null)
    val playing: StateFlow<String?> = _playing.asStateFlow()

    private var job: Job? = null

    fun play(pattern: Pattern) {
        if (pattern.steps.isEmpty()) return
        cancelJob()
        _playing.value = pattern.name
        job = scope.launch {
            do {
                for (step in pattern.steps) {
                    if (!isActive) break
                    // m1/m2 → actuators 0/1 (ignored if the toy has fewer).
                    sink.apply(step.m1, step.m2)
                    delay(step.durationMs.coerceAtLeast(10))
                }
            } while (pattern.loop && isActive)
            _playing.value = null
            sink.stopAll()
        }
    }

    /**
     * Tease mode: random intensities with surprise pauses and progressive
     * build-ups — never the same twice. Runs until [stop].
     */
    fun playTease() {
        cancelJob()
        _playing.value = TEASE
        job = scope.launch {
            var ceiling = 8 // ceiling that rises over time
            while (isActive) {
                if (Random.nextInt(6) == 0) {
                    // Teasing pause.
                    sink.apply(0, 0)
                    delay(Random.nextLong(400, 1400))
                } else {
                    val a = Random.nextInt(4, ceiling.coerceAtMost(20) + 1)
                    val b = if (Random.nextBoolean()) a else Random.nextInt(4, ceiling.coerceAtMost(20) + 1)
                    sink.apply(a, b)
                    delay(Random.nextLong(250, 1100))
                }
                if (ceiling < 20) ceiling++
            }
            sink.stopAll()
        }
    }

    /** Stops playback AND the motors. */
    fun stop() {
        cancelJob()
        sink.stopAll()
    }

    /** Cancels playback WITHOUT stopping the motors (instant manual takeover). */
    fun cancel() {
        cancelJob()
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
        _playing.value = null
    }

    companion object {
        /** Internal name of Tease mode (procedural, not a [Pattern]). */
        const val TEASE = "Tease"
    }
}
