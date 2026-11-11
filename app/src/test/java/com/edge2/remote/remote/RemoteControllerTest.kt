package com.edge2.remote.remote

import com.edge2.remote.remote.RemoteController.Phase
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** CONTROLLER mode end to end: a real [RemoteController] against a real [RemoteServer] on 127.0.0.1. */
class RemoteControllerTest {

    private val port = 28787 + (System.nanoTime() % 1000).toInt()
    private val commands = CopyOnWriteArrayList<RemoteCommand>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var server: RemoteServer
    private lateinit var controller: RemoteController

    @Before fun setUp() {
        server = RemoteServer(
            loadHtml = { "<html><script>1</script></html>" },
            loadFont = { ByteArray(0) },
            onCommand = { _, c -> commands += c },
            onLockout = {},
            port = port,
        )
        assertTrue(server.start(lanIp = null))
        controller = RemoteController(scope, reconnectDelayMs = 100, maxReconnects = 5)
    }

    @After fun tearDown() {
        controller.release()
        server.stop()
        scope.cancel()
    }

    private val ws get() = "ws://127.0.0.1:$port/ws/${server.sessionId}"

    private suspend fun awaitPhase(c: RemoteController, p: Phase, ms: Long = 5_000) {
        withTimeout(ms) { c.phase.first { it == p } }
    }

    @Test fun waitsForApprovalDrivesWhenLiveAndStaysAwayOnceKicked() = runBlocking {
        controller.connect(ws, server.pin)
        awaitPhase(controller, Phase.WAITING)
        withTimeout(3_000) { while (server.controllers.value.isEmpty()) delay(20) }
        server.pushState("{\"toys\":[{\"name\":\"Edge\",\"acts\":2},{\"name\":\"Lush\",\"acts\":1}]}")

        // Pending: nothing goes through, and the host's toys are not revealed.
        controller.send(RemoteCommand.SetBoth(10))
        delay(300)
        assertTrue(commands.isEmpty())
        assertTrue(controller.toys.value.isEmpty())

        server.approve(server.controllers.value.single().id)
        awaitPhase(controller, Phase.LIVE)
        withTimeout(3_000) { controller.toys.first { it.size == 2 } }
        assertEquals(listOf("Edge", "Lush"), controller.toys.value)

        controller.send(RemoteCommand.SetMotor(2, 14, target = 1))
        withTimeout(3_000) { while (commands.isEmpty()) delay(20) }
        assertEquals(RemoteCommand.SetMotor(2, 14, target = 1), commands.first())

        server.kick(server.controllers.value.single().id)
        awaitPhase(controller, Phase.DENIED)
        delay(500) // several reconnect delays: a refused controller must not knock again
        assertTrue(server.controllers.value.isEmpty())
    }

    @Test fun wrongPinEndsInDenied() = runBlocking {
        controller.connect(ws, if (server.pin == "000000") "111111" else "000000")
        awaitPhase(controller, Phase.DENIED, 8_000)
        assertTrue(server.controllers.value.isEmpty())
    }

    @Test fun givesUpAfterRepeatedConnectionFailures() = runBlocking {
        val dead = ws
        server.stop() // nobody listens any more
        controller.connect(dead, "123456")
        awaitPhase(controller, Phase.DENIED, 10_000)
    }

    @Test fun deepLinkValidationRejectsAnythingButOurWebSocketPath() {
        val id = ShareSecurity.token(16)
        assertEquals("wss://abc.lhr.life/ws/$id", RemoteController.validateWsUrl("wss://abc.lhr.life/ws/$id"))
        assertEquals(null, RemoteController.validateWsUrl("wss://abc.lhr.life/ws/$id/../../admin"))
        assertEquals(null, RemoteController.validateWsUrl("wss://abc.lhr.life/ws/$id#frag"))
        assertEquals(null, RemoteController.validateWsUrl("ws://" + "a".repeat(600) + "/ws/$id"))
        assertEquals(null, RemoteController.validatePin("123"))
        assertEquals(null, RemoteController.validatePin("123456789"))
    }
}
