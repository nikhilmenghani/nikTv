package com.nikhil.niktv.data

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, manifest = Config.NONE)
class PairingCryptoTest {
    @Test fun packageRequiresTheSeparateCode() {
        val code = PairingCrypto.newSecret()
        val encrypted = PairingCrypto.seal("{\"token\":\"sensitive-value\"}", code,
            System.currentTimeMillis() + 60_000)

        assertFalse(encrypted.contains("sensitive-value"))
        assertEquals("{\"token\":\"sensitive-value\"}", PairingCrypto.open(encrypted, code))
        assertThrows(Exception::class.java) { PairingCrypto.open(encrypted, PairingCrypto.newSecret()) }
    }

    @Test fun expiredPackageIsRejected() {
        val code = PairingCrypto.newSecret()
        val encrypted = JSONObject(PairingCrypto.seal("config", code, System.currentTimeMillis() + 60_000))
            .put("expiresAt", 1L).toString()
        assertThrows(IllegalArgumentException::class.java) { PairingCrypto.open(encrypted, code) }
    }

    @Test fun remoteInviteRequiresHttpsAndKeepsTheTransferCodeOutOfRelayUrl() {
        val session = RemotePairingSession("https://pair.example.com", "abcdefghijklmnopqrstuv", PairingCrypto.newSecret())
        val parsed = RemotePairing.parseInvite(session.inviteUri)
        assertEquals(session, parsed)
        assertFalse(parsed.relay.contains(parsed.secret))
        assertThrows(IllegalArgumentException::class.java) {
            RemotePairing.parseInvite(session.inviteUri.replace("https%3A", "http%3A"))
        }
    }
}
