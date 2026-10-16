package com.edge2.remote.pattern

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PatternPlayerTest {

    /** Records every call made by the player, in order. */
    private class FakeSink : PatternSink {
        val events = mutableListOf<String>()
        override fun apply(m1: Int, m2: Int) {
            events += "apply($m1,$m2)"
        }
        override fun stopAll() {
            events += "stopAll"
        }
    }

    private val threeSteps = listOf(
        PatternStep(m1 = 1, m2 = 2, durationMs = 100),
        PatternStep(m1 = 3, m2 = 4, durationMs = 100),
        PatternStep(m1 = 5, m2 = 6, durationMs = 50),
    )

    @Test fun playWithoutLoopAppliesAllStepsThenStopsAll() = runTest {
        val sink = FakeSink()
        val player = PatternPlayer(sink, backgroundScope)

        player.play(Pattern("Once", threeSteps, loop = false))
        // backgroundScope is not awaited by advanceUntilIdle: move time explicitly.
        advanceTimeBy(1_000)

        assertEquals(
            listOf("apply(1,2)", "apply(3,4)", "apply(5,6)", "stopAll"),
            sink.events,
        )
        assertNull(player.playing.value)
    }

    @Test fun cancelDoesNotStopMotors() = runTest {
        val sink = FakeSink()
        val player = PatternPlayer(sink, backgroundScope)

        player.play(Pattern("Loop", threeSteps, loop = true))
        advanceTimeBy(120)
        player.cancel()
        advanceUntilIdle()

        assertFalse(sink.events.contains("stopAll"))
        assertNull(player.playing.value)
    }

    @Test fun stopCancelsAndStopsMotors() = runTest {
        val sink = FakeSink()
        val player = PatternPlayer(sink, backgroundScope)

        player.play(Pattern("Loop", threeSteps, loop = true))
        advanceTimeBy(120)
        player.stop()
        advanceUntilIdle()

        assertEquals(1, sink.events.count { it == "stopAll" })
        assertEquals("stopAll", sink.events.last())
        assertNull(player.playing.value)
    }

    @Test fun emptyPatternIsIgnored() = runTest {
        val sink = FakeSink()
        val player = PatternPlayer(sink, backgroundScope)

        player.play(Pattern("Empty", emptyList()))
        advanceUntilIdle()

        assertEquals(emptyList<String>(), sink.events)
        assertNull(player.playing.value)
    }

    @Test fun playTeaseSetsPlayingToTeaseName() = runTest {
        val sink = FakeSink()
        val player = PatternPlayer(sink, backgroundScope)

        player.playTease()
        runCurrent()

        assertEquals(PatternPlayer.TEASE, player.playing.value)
        player.stop()
        assertNull(player.playing.value)
    }
}
