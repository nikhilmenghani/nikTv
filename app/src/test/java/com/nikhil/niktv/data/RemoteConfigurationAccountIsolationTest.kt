package com.nikhil.niktv.data

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, manifest = Config.NONE)
class RemoteConfigurationAccountIsolationTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        listOf("github_configuration_accounts", "github_remote_credentials", "github_remote_credentials_account_101", "github_remote_credentials_account_202", "github_remote_credentials_account_disconnected").forEach {
            context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit()
        }
        context.getSharedPreferences("github_remote_credentials", Context.MODE_PRIVATE).edit()
            .putString("owner", "alice").putString("repository", "private-config").putString("file_path", ".env").commit()
    }

    @Test fun sameOwnerUpgradePreservesConfigurationAndAnotherAccountStartsEmpty() {
        RemoteCredentials.activateAccount(context, "101", "alice")
        assertEquals("alice", RemoteCredentials.owner(context))
        assertEquals("private-config", RemoteCredentials.repository(context))
        RemoteCredentials.activateAccount(context, "202", "bob")
        val other = context.getSharedPreferences("github_remote_credentials_account_202", 0)
        assertTrue("Other account inherits no local configuration", other.all.isEmpty())
        assertTrue(RemoteCredentials.values.isEmpty())
        RemoteCredentials.activateAccount(context, "101", "alice")
        assertEquals("alice", RemoteCredentials.owner(context))
        assertEquals("private-config", RemoteCredentials.repository(context))
        RemoteCredentials.disconnect(context)
        assertTrue(RemoteCredentials.values.isEmpty())
        assertFalse(RemoteCredentials.configured(context))
    }

    @Test fun unrelatedFirstAccountCannotAdoptLegacyPrivateConfiguration() {
        RemoteCredentials.activateAccount(context, "202", "bob")
        assertTrue(context.getSharedPreferences("github_remote_credentials_account_202", 0).all.isEmpty())
        assertTrue("Unassigned legacy remains available for explicit import", context.getSharedPreferences("github_remote_credentials", 0).contains("repository"))
    }
}
