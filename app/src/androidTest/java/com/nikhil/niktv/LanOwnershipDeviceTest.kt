package com.nikhil.niktv

import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.*
import org.junit.Assert.*
import org.junit.Test

/** A separate test installation pairs while the real phone's movie is already playing. */
class LanOwnershipDeviceTest {
    @Test fun secondSenderCannotReplacePauseOrStopActiveMovie() {
        val args = InstrumentationRegistry.getArguments()
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".regression"))
        val host = requireNotNull(args.getString("receiverHost"))
        val port = requireNotNull(args.getString("receiverPort")).toInt()
        fun rpc(command: LanCommand) = sendLanCommand(host, port, command)
        val paired = rpc(LanCommand("pair", requireNotNull(args.getString("receiverPin")),
            sender = "Second sender test", senderId = "01010101-0101-0101-0101-010101010101"))
        assertTrue(paired.message, paired.ok)
        val token = requireNotNull(paired.credential)
        val before = rpc(LanCommand("status", token))
        assertTrue(before.message, before.videoReady)
        for (action in listOf("play", "pause", "resume", "stop")) {
            val reply = rpc(LanCommand(action, token,
                video = if (action == "play") RemoteVideo("https://not-requested.invalid/blocked.mp4", "Must not replace") else null))
            assertFalse("Second sender $action must be rejected", reply.ok)
            assertTrue(reply.message, reply.message.contains("controlled by"))
        }
        Thread.sleep(1500)
        val after = rpc(LanCommand("status", token))
        assertTrue(after.videoReady)
        assertEquals(before.message, after.message)
        assertTrue("Original movie continues advancing", after.positionMillis > before.positionMillis)
    }
}
