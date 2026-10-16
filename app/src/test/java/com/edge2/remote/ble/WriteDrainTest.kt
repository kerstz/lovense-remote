package com.edge2.remote.ble

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WriteDrainTest {
    private val desired = HashMap<Int, Int>()
    private val lastSent = HashMap<Int, Int>()
    private val refresh = HashSet<Int>()
    private val sent = ArrayList<Pair<Int, Int>>()
    private val waits = ArrayList<Long>()

    private suspend fun drain(
        canRetry: () -> Boolean = { true },
        send: suspend (Int, Int) -> Boolean,
    ) = WriteDrain.drain(desired, desired, lastSent, refresh, send, canRetry, wait = { waits += it })

    @Test fun failedStopIsRetriedWithZeroPayload() = runTest {
        desired[0] = 0; lastSent[0] = 12
        var failures = 2
        drain { i, v -> sent += i to v; if (failures > 0) { failures--; false } else true }
        assertEquals(listOf(0 to 0, 0 to 0, 0 to 0), sent)
        assertEquals(listOf(100L, 200L), waits)
        assertEquals(0, lastSent[0])
    }

    @Test fun retryUsesLatestDesiredState() = runTest {
        desired[0] = 5; lastSent[0] = 3
        var first = true
        WriteDrain.drain(desired, desired, lastSent, refresh,
            send = { i, v -> sent += i to v; if (first) { first = false; false } else true },
            canRetry = { true },
            wait = { desired[0] = 0 }, // STOP arrives during the back-off
        )
        assertEquals(listOf(0 to 5, 0 to 0), sent)
        assertEquals(0, lastSent[0])
    }

    @Test fun givesUpWhenLinkGone() = runTest {
        desired[0] = 0; lastSent[0] = 9
        drain(canRetry = { false }) { _, _ -> false }
        assertTrue(waits.isEmpty())
        assertEquals(9, lastSent[0]) // unchanged: next signal retries
    }

    @Test fun keepaliveNeverNonZeroAfterStop() {
        val d = mapOf(0 to 0, 1 to 0)
        assertTrue(WriteDrain.keepaliveRefresh(d).isEmpty())
        // even though the last acknowledged level was non-zero
        val pend = WriteDrain.pending(d, mapOf(0 to 15, 1 to 3), emptySet())
        assertEquals(listOf(0 to 0, 1 to 0), pend.sortedBy { it.first })
        assertTrue(pend.all { it.second == 0 })
    }

    @Test fun keepaliveRefreshesOnlyDesiredNonZero() = runTest {
        desired[0] = 7; desired[1] = 0; lastSent[0] = 7; lastSent[1] = 0
        refresh += WriteDrain.keepaliveRefresh(desired)
        drain { i, v -> sent += i to v; true }
        assertEquals(listOf(0 to 7), sent)
        assertTrue(refresh.isEmpty())
    }

    @Test fun backoffIsBounded() {
        assertEquals(100L, WriteDrain.backoffMs(1))
        assertEquals(listOf(100L, 200L, 400L, 800L, 1000L), (1..5).map(WriteDrain::backoffMs))
        assertEquals(1000L, WriteDrain.backoffMs(10_000))
        assertTrue((1..100_000).all { WriteDrain.backoffMs(it) in 100L..1000L })
    }
}
