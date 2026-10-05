// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex.pack

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import nl.mattix.andamp.pack.plex.PlexConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What [PackStore] keeps: two tokens, a server, a library, a client id that outlives them, and no password. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PackStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a sign-in keeps the account, the server, its connections and the library, and nothing that looks like a password`() {
        val store = PackStore(context)

        keep(store)

        assertTrue(store.signedIn)
        assertEquals("account", store.accountToken)
        assertEquals("listener", store.userName)
        assertEquals("server", store.serverToken)
        assertEquals("Living room", store.serverName)
        assertEquals("machine", store.machineId)
        assertEquals("https://192.0.2.10:32400", store.address)
        assertEquals(CONNECTIONS, store.connections)
        assertEquals("1", store.sectionKey)
        assertEquals("Music", store.sectionTitle)
        val kept = context.getSharedPreferences("plex", Context.MODE_PRIVATE).all.keys
        assertFalse(kept.toString(), kept.any { it.contains("pass", ignoreCase = true) || it == "pw" })
    }

    @Test
    fun `a sign-in with a typed token has no account and no connections`() {
        val store = PackStore(context)

        store.signIn("", "", "server", "Living room", "machine", "192.0.2.10:32400", emptyList(), "1", "Music")

        assertTrue(store.signedIn)
        assertEquals("", store.accountToken)
        assertEquals(emptyList<PlexConnection>(), store.connections)
    }

    @Test
    fun `the client id is made once and survives a sign-out`() {
        val store = PackStore(context)
        val first = store.clientId
        keep(store)

        store.signOut()

        assertEquals(first, PackStore(context).clientId)
        assertFalse(store.signedIn)
        assertEquals("", store.serverToken)
        assertEquals("", store.accountToken)
        assertEquals("", store.sectionKey)
        assertEquals(emptyList<PlexConnection>(), store.connections)
    }

    @Test
    fun `a sign-out keeps the address`() {
        val store = PackStore(context)
        keep(store)

        store.signOut()

        assertEquals("https://192.0.2.10:32400", store.address)
    }

    @Test
    fun `two installs are two clients`() {
        val made = PackStore(context).clientId
        context
            .getSharedPreferences("plex", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        assertNotEquals(made, PackStore(context).clientId)
    }

    @Test
    fun `the server it describes is the one it keeps`() {
        val store = PackStore(context)
        keep(store)

        val server = store.server(deviceName = "Pixel 9", version = "0.1.0")

        assertEquals("https://192.0.2.10:32400", server.address)
        assertEquals("server", server.token)
        assertEquals("machine", server.machineId)
        assertEquals("1", server.sectionKey)
        assertEquals(store.clientId, server.device.id)
        assertEquals("Pixel 9", server.device.name)
    }

    @Test
    fun `the library can be changed and the address moved without a new sign-in`() {
        val store = PackStore(context)
        keep(store)

        store.chooseLibrary("3", "Audiobooks")
        store.moveTo("https://203.0.113.7:13936")

        assertEquals("3", store.sectionKey)
        assertEquals("Audiobooks", store.sectionTitle)
        assertEquals("https://203.0.113.7:13936", store.address)
        assertEquals("server", store.serverToken)
    }

    @Test
    fun `a revoked token signs out only the sign-in it belongs to`() {
        val store = PackStore(context)
        keep(store)

        assertFalse(store.signOutIf("old"))
        assertTrue(store.signedIn)
        assertFalse("an empty token signs nobody out", store.signOutIf(""))

        assertTrue(store.signOutIf("server"))
        assertFalse(store.signedIn)
    }

    private fun keep(store: PackStore) {
        store.signIn(
            accountToken = "account",
            userName = "listener",
            serverToken = "server",
            serverName = "Living room",
            machineId = "machine",
            address = " https://192.0.2.10:32400 ",
            connections = CONNECTIONS,
            sectionKey = "1",
            sectionTitle = "Music",
        )
    }

    private companion object {
        val CONNECTIONS =
            listOf(
                PlexConnection("https://192.0.2.10:32400", local = true, relay = false, address = "192.0.2.10", port = 32400),
                PlexConnection("https://203.0.113.7:13936", local = false, relay = false, address = "203.0.113.7", port = 13936),
            )
    }
}
