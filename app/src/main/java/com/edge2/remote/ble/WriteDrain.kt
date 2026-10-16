package com.edge2.remote.ble

import kotlinx.coroutines.delay

/**
 * Pure decision logic of the write queue (no android.* dependency, unit-testable).
 *
 * Invariant: the DESIRED state (what the user/controller asked for) is the only
 * source of truth. A failed write is retried with back-off until it succeeds or
 * the link is gone, and the keepalive never replays an old packet: it only asks
 * the writer to re-send the current desired non-zero levels.
 */
internal object WriteDrain {
    const val RETRY_MIN_MS = 100L
    const val RETRY_MAX_MS = 1_000L

    /** Delay before retry number [failures] (1-based): 100, 200, 400, 800, then capped at 1000 ms. */
    fun backoffMs(failures: Int): Long {
        if (failures <= 1) return RETRY_MIN_MS
        val shift = (failures - 1).coerceAtMost(10)
        return (RETRY_MIN_MS shl shift).coerceAtMost(RETRY_MAX_MS)
    }

    /** Actuators to write: desired differs from the last acknowledged value, or a keepalive refresh is due. */
    fun pending(desired: Map<Int, Int>, lastSent: Map<Int, Int>, refresh: Set<Int>): List<Pair<Int, Int>> =
        desired.filter { (i, v) -> lastSent[i] != v || i in refresh }.toList()

    /** Keepalive: only actuators whose desired level is non-zero are refreshed; all-zero → nothing (no traffic). */
    fun keepaliveRefresh(desired: Map<Int, Int>): Set<Int> =
        desired.filter { (_, v) -> v != 0 }.keys.toSet()

    /**
     * Writes the pending desired levels until none is left. On failure it waits
     * [backoffMs] and recomputes from the CURRENT desired state (so a STOP queued
     * meanwhile wins), as long as [canRetry] holds; otherwise it gives up and the
     * next signal restarts it.
     *
     * [lock] guards [desired], [lastSent] and [refresh]. [send] writes all the packets of
     * one actuator; [onSent] runs under [lock] after success; [publish] runs after each round.
     */
    suspend fun drain(
        lock: Any,
        desired: Map<Int, Int>,
        lastSent: MutableMap<Int, Int>,
        refresh: MutableSet<Int>,
        send: suspend (index: Int, level: Int) -> Boolean,
        canRetry: () -> Boolean,
        onSent: (index: Int, level: Int) -> Unit = { _, _ -> },
        publish: () -> Unit = {},
        wait: suspend (Long) -> Unit = { delay(it) },
    ) {
        var failures = 0
        while (true) {
            val changes = synchronized(lock) { pending(desired, lastSent, refresh) }
            if (changes.isEmpty()) return
            var ok = true
            for ((i, v) in changes) {
                if (!send(i, v)) { ok = false; break }
                synchronized(lock) { lastSent[i] = v; refresh.remove(i); onSent(i, v) }
            }
            publish()
            if (ok) { failures = 0; continue }
            if (!canRetry()) return
            wait(backoffMs(++failures))
        }
    }
}
