package com.edge2.remote.pattern

import com.edge2.remote.ble.Edge2BleManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Joue un [Pattern] en envoyant les intensités au toy via [Edge2BleManager],
 * éventuellement en boucle. Une seule lecture à la fois.
 */
class PatternPlayer(
    private val ble: Edge2BleManager,
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
                    // m1/m2 → actionneurs 0/1 (ignoré si le toy en a moins).
                    ble.setActuator(0, step.m1)
                    ble.setActuator(1, step.m2)
                    delay(step.durationMs.coerceAtLeast(10))
                }
            } while (pattern.loop && isActive)
            _playing.value = null
            ble.stopAll()
        }
    }

    /** Stoppe la lecture ET coupe les moteurs. */
    fun stop() {
        cancelJob()
        ble.stopAll()
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
}
