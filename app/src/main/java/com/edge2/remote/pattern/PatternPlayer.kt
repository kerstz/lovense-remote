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

/** Destination des niveaux d'un pattern (m1/m2 sur l'échelle 0..20). */
interface PatternSink {
    fun apply(m1: Int, m2: Int)
    fun stopAll()
}

/**
 * Joue un [Pattern] en envoyant les intensités aux jouets via [sink],
 * éventuellement en boucle. Une seule lecture à la fois.
 */
class PatternPlayer(
    private val sink: PatternSink,
    private val scope: CoroutineScope,
) {
    /** Nom du pattern en cours de lecture, ou null si arrêté. */
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
                    // m1/m2 → actionneurs 0/1 (ignoré si le jouet en a moins).
                    sink.apply(step.m1, step.m2)
                    delay(step.durationMs.coerceAtLeast(10))
                }
            } while (pattern.loop && isActive)
            _playing.value = null
            sink.stopAll()
        }
    }

    /**
     * Mode Tease : intensités aléatoires avec pauses surprises et montées
     * progressives — jamais deux fois pareil. Tourne jusqu'à [stop].
     */
    fun playTease() {
        cancelJob()
        _playing.value = TEASE
        job = scope.launch {
            var ceiling = 8 // plafond qui monte au fil du temps
            while (isActive) {
                if (Random.nextInt(6) == 0) {
                    // Pause taquine.
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

    /** Stoppe la lecture ET coupe les moteurs. */
    fun stop() {
        cancelJob()
        sink.stopAll()
    }

    /** Annule la lecture SANS couper les moteurs (reprise manuelle immédiate). */
    fun cancel() {
        cancelJob()
    }

    private fun cancelJob() {
        job?.cancel()
        job = null
        _playing.value = null
    }

    companion object {
        /** Nom interne du mode Tease (procédural, pas un [Pattern]). */
        const val TEASE = "Tease"
    }
}
