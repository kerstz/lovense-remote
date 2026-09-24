package com.edge2.remote.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteCommandTest {

    @Test fun parsesBasicCommands() {
        assertEquals(RemoteCommand.Stop(), RemoteCommand.parse("S"))
        assertEquals(RemoteCommand.SetBoth(12), RemoteCommand.parse("B:12"))
        assertEquals(RemoteCommand.SetMotor(1, 0), RemoteCommand.parse("M1:0"))
        assertEquals(RemoteCommand.SetMotor(2, 20), RemoteCommand.parse(" M2:20 "))
    }

    @Test fun parsesTargetPrefix() {
        assertEquals(RemoteCommand.SetMotor(2, 14, target = 1), RemoteCommand.parse("@1:M2:14"))
        assertEquals(RemoteCommand.Stop(target = 0), RemoteCommand.parse("@0:S"))
    }

    @Test fun rejectsOutOfRangeAndGarbage() {
        assertNull(RemoteCommand.parse("B:21"))
        assertNull(RemoteCommand.parse("M1:-1"))
        assertNull(RemoteCommand.parse("M3:5"))
        assertNull(RemoteCommand.parse("@99:S"))
        assertNull(RemoteCommand.parse("@x:S"))
        assertNull(RemoteCommand.parse("@:S"))
        assertNull(RemoteCommand.parse("B:" + "9".repeat(40)))
        assertNull(RemoteCommand.parse("AUTH:123456"))
    }

    @Test fun formatRoundTrips() {
        listOf(
            RemoteCommand.Stop(), RemoteCommand.Stop(2), RemoteCommand.SetBoth(7),
            RemoteCommand.SetMotor(1, 3, 0), RemoteCommand.SetMotor(2, 20),
        ).forEach { assertEquals(it, RemoteCommand.parse(RemoteCommand.format(it))) }
    }
}
