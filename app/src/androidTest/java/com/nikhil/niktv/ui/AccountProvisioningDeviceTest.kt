package com.nikhil.niktv.ui

import androidx.test.platform.app.InstrumentationRegistry
import com.nikhil.niktv.data.AccountSyncManager
import com.nikhil.niktv.data.UserSyncStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit developer provisioning, only in the separately installed instrumentation APK.
 * Input arrives over ADB stdin into private storage and is removed before authentication.
 * No token is embedded, passed as an instrumentation argument, or exposed in UI/logs. */
class AccountProvisioningDeviceTest {
    @Test fun connectExplicitLoopbackTokenInput(): Unit = runBlocking {
        assumeTrue("Explicit private provisioning only", InstrumentationRegistry.getArguments().getString("enablePrivateAccountProvisioning") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName.endsWith(".debug"))
        java.net.ServerSocket().use { server ->
            server.bind(java.net.InetSocketAddress(java.net.InetAddress.getByName("127.0.0.1"), 0), 1)
            server.soTimeout = 60_000
            instrumentation.sendStatus(2, android.os.Bundle().apply {
                putString("stream", "ACCOUNT_TOKEN_INPUT_READY port=${server.localPort}\n")
            })
            var token = server.accept().use { socket ->
                socket.soTimeout = 10_000
                val input = java.io.DataInputStream(socket.getInputStream())
                val count = input.readInt()
                check(count in 1..4096)
                val bytes = ByteArray(count)
                try {
                    input.readFully(bytes)
                    val value = bytes.toString(Charsets.UTF_8).trim()
                    socket.getOutputStream().write(1)
                    value
                } finally { bytes.fill(0) }
            }
            try {
                val manager = AccountSyncManager.get(context)
                withTimeout(120_000) { manager.connect(token) }
                val state = manager.state.value
                assertTrue(state.error ?: "Account initialization did not finish.", state.initialized && !state.reconnectRequired && state.error == null)
            } finally { token = "" }
        }
    }

    @Test fun connectExplicitPrivateTokenInput(): Unit = runBlocking {
        assumeTrue("Explicit private provisioning only", InstrumentationRegistry.getArguments().getString("enablePrivateAccountProvisioning") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName.endsWith(".debug")) { "Provision only a development installation." }
        val input = context.filesDir.resolve("local_account_token_import")
        check(input.isFile) { "Private token input was not supplied." }
        var token = try { input.readText(Charsets.UTF_8).trim() } finally {
            check(input.delete()) { "Could not remove private input." }
        }
        try {
            val manager = AccountSyncManager.get(context)
            withTimeout(120_000) { manager.connect(token) }
            val state = manager.state.value
            assertTrue(state.error ?: "Account initialization did not finish.", state.initialized && !state.reconnectRequired && state.error == null)
            assertTrue("No account selected after connection", UserSyncStore.get(context).selectedAccount.value != null)
        } finally { token = "" }
    }
}
