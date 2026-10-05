// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex.pack

import android.content.Context
import nl.mattix.andamp.pack.plex.PlexConnection
import nl.mattix.andamp.pack.plex.PlexDevice
import nl.mattix.andamp.pack.plex.PlexServer
import java.util.UUID

/**
 * Keeps the sign-in in this app's private preferences: the server, its
 * token, the music library, and the account's token when the sign-in went
 * through plex.tv.
 *
 * What is kept is tokens, never a password. They are protected by the app's
 * private storage, which no other app can read, the player included, and
 * `allowBackup` is off. The account's token can be removed under the
 * account's devices on plex.tv.
 */
internal class PackStore(
    context: Context,
) {
    private val kept = context.applicationContext.getSharedPreferences("plex", Context.MODE_PRIVATE)

    /**
     * This install's client id, made on first use and kept through a
     * sign-out. plex.tv keys a sign-in code on it and lists it under the
     * account's devices; see [PlexDevice].
     */
    val clientId: String
        get() =
            kept.getString(CLIENT_ID, null) ?: UUID.randomUUID().toString().also { made ->
                kept.edit().putString(CLIENT_ID, made).apply()
            }

    /** The server's address in use: a plex.tv connection, or what the listener typed, trimmed; empty when none is kept. */
    var address: String
        get() = kept.getString(ADDRESS, "").orEmpty()
        set(value) {
            kept.edit().putString(ADDRESS, value.trim()).apply()
        }

    /** The account's token on plex.tv; empty after a sign-in with a typed token, and when signed out. */
    val accountToken: String get() = kept.getString(ACCOUNT_TOKEN, "").orEmpty()

    /** The account's name, shown on the settings screen; empty for a typed token, and when signed out. */
    val userName: String get() = kept.getString(USER_NAME, "").orEmpty()

    /** The token the server takes; empty when signed out. */
    val serverToken: String get() = kept.getString(SERVER_TOKEN, "").orEmpty()

    /** The server's name, shown on the settings screen. */
    val serverName: String get() = kept.getString(SERVER_NAME, "").orEmpty()

    /** The server's `machineIdentifier`, which a connection is checked against; empty for a typed address. */
    val machineId: String get() = kept.getString(MACHINE_ID, "").orEmpty()

    /** Every connection plex.tv listed for the server, so another can be tried when [address] stops answering. */
    val connections: List<PlexConnection> get() = PlexConnection.stored(kept.getString(CONNECTIONS, "").orEmpty())

    /** The music library the questions go to; empty until one is chosen. */
    val sectionKey: String get() = kept.getString(SECTION_KEY, "").orEmpty()

    /** The music library's title, shown on the settings screen. */
    val sectionTitle: String get() = kept.getString(SECTION_TITLE, "").orEmpty()

    /** Whether an address, a server token and a library are kept. The server may have revoked the token since. */
    val signedIn: Boolean get() = address.isNotBlank() && serverToken.isNotEmpty() && sectionKey.isNotEmpty()

    /** Keeps a sign-in, in one write. [accountToken] and [connections] are empty for a typed address and token. */
    fun signIn(
        accountToken: String,
        userName: String,
        serverToken: String,
        serverName: String,
        machineId: String,
        address: String,
        connections: List<PlexConnection>,
        sectionKey: String,
        sectionTitle: String,
    ) = synchronized(SIGN_IN) {
        kept
            .edit()
            .putString(ACCOUNT_TOKEN, accountToken)
            .putString(USER_NAME, userName)
            .putString(SERVER_TOKEN, serverToken)
            .putString(SERVER_NAME, serverName)
            .putString(MACHINE_ID, machineId)
            .putString(ADDRESS, address.trim())
            .putString(CONNECTIONS, PlexConnection.json(connections))
            .putString(SECTION_KEY, sectionKey)
            .putString(SECTION_TITLE, sectionTitle)
            .apply()
    }

    /** Changes the music library of the kept sign-in. */
    fun chooseLibrary(
        key: String,
        title: String,
    ) {
        kept
            .edit()
            .putString(SECTION_KEY, key)
            .putString(SECTION_TITLE, title)
            .apply()
    }

    /** Changes the address of the kept sign-in, after another connection answered. */
    fun moveTo(address: String) {
        this.address = address
    }

    /**
     * Signs out if [revoked] is still the kept server token, and says whether
     * it did.
     *
     * It holds the same lock as [signIn], across every store instance. The
     * service may find a token revoked on a binder thread while the settings
     * screen keeps the sign-in that replaced it, and that new sign-in must not
     * be removed.
     */
    fun signOutIf(revoked: String): Boolean =
        synchronized(SIGN_IN) {
            val still = revoked.isNotEmpty() && serverToken == revoked
            if (still) signOut()
            still
        }

    /**
     * Signs out: removes the tokens, the account, the server and the library.
     * The address stays, so a typed one can be signed in to again, and so
     * does the client id; see [clientId].
     */
    fun signOut() {
        kept
            .edit()
            .remove(ACCOUNT_TOKEN)
            .remove(USER_NAME)
            .remove(SERVER_TOKEN)
            .remove(SERVER_NAME)
            .remove(MACHINE_ID)
            .remove(CONNECTIONS)
            .remove(SECTION_KEY)
            .remove(SECTION_TITLE)
            .apply()
    }

    /** This phone as a device, called [deviceName], running [version] of this source. Parameters, so a test can set them. */
    fun device(
        deviceName: String,
        version: String,
    ): PlexDevice = PlexDevice(deviceName, clientId, version)

    /** The server as this store holds it, for the phone called [deviceName] running [version] of this source. */
    fun server(
        deviceName: String,
        version: String,
    ): PlexServer = PlexServer(address, device(deviceName, version), serverToken, machineId, sectionKey)

    private companion object {
        /** The lock for [signIn] and [signOutIf]. */
        val SIGN_IN = Any()

        const val CLIENT_ID = "clientId"
        const val ADDRESS = "address"
        const val ACCOUNT_TOKEN = "accountToken"
        const val USER_NAME = "userName"
        const val SERVER_TOKEN = "serverToken"
        const val SERVER_NAME = "serverName"
        const val MACHINE_ID = "machineId"
        const val CONNECTIONS = "connections"
        const val SECTION_KEY = "sectionKey"
        const val SECTION_TITLE = "sectionTitle"
    }
}
