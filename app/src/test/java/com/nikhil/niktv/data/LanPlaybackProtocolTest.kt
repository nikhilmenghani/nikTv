package com.nikhil.niktv.data

import org.junit.Assert.*
import org.junit.Test
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class LanPlaybackProtocolTest {
    @Test fun remoteInputRequiresPairingAndRejectsInvalidPayloads() {
        val received = mutableListOf<LanCommand>()
        LanPlaybackServer(InetAddress.getByName("127.0.0.1")) { received += it; LanReply(true, "Accepted") }.use { server ->
            fun command(action: String, credential: String, key: String? = null, text: String? = null) =
                sendLanCommand("127.0.0.1", server.port, LanCommand(action, credential, key = key, text = text))
            assertFalse(command("navigate", "unpaired", "ok").ok)
            val token = requireNotNull(sendLanCommand("127.0.0.1", server.port, LanCommand("pair", server.pin)).credential)
            assertFalse(command("navigate", token, "power").ok)
            assertFalse(command("text", token, text = "x".repeat(1025)).ok)
            assertTrue(command("navigate", token, "down").ok)
            assertTrue(command("text", token, text = "remote-entry-fixture").ok)
            assertTrue(command("clear_text", token).ok)
            assertEquals(listOf("navigate", "text", "clear_text"), received.map { it.action })
            assertEquals("remote-entry-fixture", received[1].text)
        }
    }
    private val host = InetAddress.getByName("127.0.0.1")
    private val movie = RemoteMovie(remotePortalIdentity("XTREAM", "https://provider.test"), "42", "An old movie")
    private fun send(server: LanPlaybackServer, action: String, credential: String, item: RemoteMovie? = null) =
        sendLanCommand("127.0.0.1", server.port, LanCommand(action, credential, item))

    @Test fun pairingTokenControlsSelectedStreamPlayback() {
        val commands = mutableListOf<LanCommand>()
        LanPlaybackServer(host) { commands += it; LanReply(true, "Played") }.use { server ->
            val paired = send(server, "pair", server.pin)
            assertTrue(paired.ok)
            val token = requireNotNull(paired.credential)
            assertEquals(64, token.length)
            val selected = RemoteVideo("https://stream.test/selected.mp4", "Selected movie")
            assertTrue(sendLanCommand("127.0.0.1", server.port, LanCommand("play", token, video = selected)).ok)
            assertEquals(selected, commands.single().video)
            assertTrue(send(server, "pause", token).ok)
            assertTrue(send(server, "resume", token).ok)
            assertTrue(send(server, "stop", token).ok)
        }
    }
    @Test fun preReleaseIdentityRequestsAreRejectedWithoutSearchingOrStartingPlayer() {
        LanPlaybackServer(host) { fail("Identity-only request reached receiver player"); LanReply(true, "") }.use { server ->
            val token = requireNotNull(send(server, "pair", server.pin).credential)
            val reply = send(server, "play", token, movie)
            assertFalse(reply.ok)
            assertTrue(reply.message.contains("Update both devices"))
        }
    }
    @Test fun unpairedCommandsNeverReachPlayer() {
        val calls = AtomicInteger()
        LanPlaybackServer(host) { calls.incrementAndGet(); LanReply(true, "") }.use { server ->
            assertFalse(send(server, "play", "not paired", movie).ok)
            assertFalse(send(server, "stop", server.pin).ok)
            assertEquals(0, calls.get())
        }
    }
    @Test fun wrongPinAndBruteForceAttemptsAreRejected() {
        LanPlaybackServer(host) { LanReply(true, "") }.use { server ->
            val wrong = if (server.pin == "000000") "111111" else "000000"
            repeat(5) { assertFalse(send(server, "pair", wrong).ok) }
            assertFalse(send(server, "pair", server.pin).ok)
        }
    }
    @Test fun oldTokenDoesNotSurviveReceiverRestart() {
        val token = LanPlaybackServer(host) { LanReply(true, "") }.use { server ->
            requireNotNull(send(server, "pair", server.pin).credential)
        }
        LanPlaybackServer(host) { fail("Unpaired command reached player"); LanReply(true, "") }.use { server ->
            assertFalse(send(server, "status", token).ok)
        }
    }
    @Test fun receiverRestartsOnItsPreviousPortEvenAfterAClientConnection() {
        val port = LanPlaybackServer(host) { LanReply(true, "") }.use { first ->
            assertTrue(send(first, "pair", first.pin).ok)
            first.port
        }
        LanPlaybackServer(host, port) { LanReply(true, "") }.use { restarted ->
            assertEquals(port, restarted.port)
            assertTrue(send(restarted, "pair", restarted.pin).ok)
        }
    }
    @Test fun malformedSocketDoesNotKillListener() {
        LanPlaybackServer(host) { LanReply(true, "") }.use { server ->
            Socket(host, server.port).use { socket ->
                DataOutputStream(socket.getOutputStream()).apply { writeInt(Int.MAX_VALUE); flush() }
            }
            assertTrue(send(server, "pair", server.pin).ok)
        }
    }
    @Test fun invalidMovieAndUnknownCommandsNeverReachPlayer() {
        LanPlaybackServer(host) { fail("Invalid command reached player"); LanReply(true, "") }.use { server ->
            val token = requireNotNull(send(server, "pair", server.pin).credential)
            assertFalse(send(server, "delete", token).ok)
            assertFalse(send(server, "play", token, movie.copy(portal = "invalid")).ok)
            assertFalse(send(server, "play", token).ok)
            assertFalse(send(server, "play", token, movie.copy(title = "a".repeat(513))).ok)
            assertFalse(send(server, "play", token, movie.copy(id = "https://account:secret@provider/movie")).ok)
        }
    }
    @Test fun portalIdentityContainsNoAccountAndNormalizesTrailingSlash() {
        assertEquals(remotePortalIdentity("STALKER", "https://portal.test/"), remotePortalIdentity("STALKER", "https://portal.test"))
        assertNotEquals(remotePortalIdentity("STALKER", "https://portal.test"), remotePortalIdentity("XTREAM", "https://portal.test"))
        assertEquals(64, movie.portal.length)
    }
    @Test fun publicInternetTargetsAreRejectedBeforeConnecting() {
        assertThrows(IllegalArgumentException::class.java) { sendLanCommand("8.8.8.8", 80, LanCommand("pair", "123456")) }
    }
    @Test fun pairedExternalVideoCarriesSourceHeadersAndResumePosition() {
        var received: RemoteVideo? = null
        val video = RemoteVideo("https://video.test/movie.mp4", "Shared movie", "mp4", mapOf("Referer" to "https://video.test/"), 42_000)
        LanPlaybackServer(host) { received = it.video; LanReply(true, "Playing") }.use { server ->
            val token = requireNotNull(send(server, "pair", server.pin).credential)
            val result = sendLanCommand("127.0.0.1", server.port, LanCommand("play", token, video = video))
            assertTrue(result.ok); assertEquals(video, received)
        }
    }
    @Test fun rawAndroidFileUrisAndInjectedHeadersAreRejectedByReceiver() {
        LanPlaybackServer(host) { fail("Invalid video reached player"); LanReply(true, "") }.use { server ->
            val token = requireNotNull(send(server, "pair", server.pin).credential)
            for (video in listOf(RemoteVideo("content://private/movie", "Video"), RemoteVideo("file:///private/movie.mp4", "Video"),
                RemoteVideo("https://video.test/movie", "Video", headers = mapOf("Referer" to "bad\r\nInjected: yes")))) {
                assertFalse(sendLanCommand("127.0.0.1", server.port, LanCommand("play", token, video = video)).ok)
            }
        }
    }
}
