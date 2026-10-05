// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex.pack

import android.content.Context
import android.os.Build
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.mattix.andamp.core.network.SystemNetworkWatch
import nl.mattix.andamp.core.packapi.PackAccount
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackDescriptor
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.playback.NetworkWatch
import nl.mattix.andamp.core.playback.PlaybackBackend
import nl.mattix.andamp.pack.common.PackServiceBase
import nl.mattix.andamp.pack.common.stream.StreamPlayback
import nl.mattix.andamp.pack.plex.BuildConfig
import nl.mattix.andamp.pack.plex.OkHttpPlex
import nl.mattix.andamp.pack.plex.PlexHttp
import nl.mattix.andamp.pack.plex.PlexLibrary
import nl.mattix.andamp.pack.plex.PlexPlayback
import nl.mattix.andamp.pack.plex.PlexProbe
import nl.mattix.andamp.pack.plex.PlexServer

/**
 * The service the player binds. It supplies the server and sign-in kept on
 * this phone, the music library of that server, and the backend that plays
 * its rows; the binder and the service lifetime are [PackServiceBase].
 *
 * Questions go to [PlexLibrary] directly; the server does the paging.
 *
 * A token can be revoked on plex.tv, which shows as a 401 on the next
 * question. The service then forgets the sign-in and tells every bound
 * player; see [revoked]. A server that stops answering on its address is
 * looked for on its other connections; see [moved].
 */
class PackService : PackServiceBase() {
    /** The server and the sign-in on this phone. Its values are read from the preferences on every call. */
    private val store by lazy { PackStore(this) }

    /**
     * Whether the phone has a network, so a song whose connection dropped
     * waits for one. It can be made in a field initializer because it looks
     * up the connectivity service only when first used.
     */
    internal val network: NetworkWatch = SystemNetworkWatch(this)

    /** One search for the server at a time; a second question that found it gone waits for the first's answer. */
    private val moving = Mutex()

    /** The library as it was last built, and for which address, token and section; see [library]. */
    private var shelves: PlexLibrary? = null
    private var shelvesFor: Triple<String, String, String>? = null

    override val launcherAlias: String = PackIdentity.LAUNCHER_ALIAS

    override fun makeBackend(): PlaybackBackend =
        PlexPlayback.backend(
            server = ::server,
            tracks = emptyList(),
            startIndex = 0,
            scope = scope,
            out = audio,
            network = network,
        )

    override fun descriptor(): PackDescriptor =
        PackIdentity.descriptor(
            version = BuildConfig.VERSION_NAME,
            playback = StreamPlayback.RENDERING,
            browse = PlexLibrary.SHELVES,
        )

    /** Signed in means a server token and a library are kept. The server is not asked; see [revoked]. */
    override fun whoIsHere(): PackAccount =
        if (store.signedIn) {
            PackAccount(signedIn = true, name = store.userName.ifEmpty { store.serverName })
        } else {
            PackAccount(signedIn = false)
        }

    override fun answer(question: PackQuestion): PackAnswer? {
        val shelf = library() ?: return null
        return runBlocking { shelf.answer(question) }
    }

    /** Drops the library. Synchronized with [library], which a binder thread may be reading. */
    @Synchronized
    override fun forgetAccount() {
        shelves = null
        shelvesFor = null
    }

    /**
     * Signs out after the server answered 401 to a question asked with [token],
     * but only when [token] is still the kept one. A new sign-in replaces the
     * token, so a question still out with the old token gets a 401 that must
     * not sign out the new sign-in.
     *
     * Called on a binder thread; the account is dropped on the main thread.
     */
    private fun revoked(token: String) {
        if (store.signOutIf(token)) scope.launch { dropAccount() }
    }

    /**
     * The server on another of its connections, after the kept address did
     * not answer: the one that answers best is kept as the address from then
     * on. Null when none answers, or when the sign-in has no connections (a
     * typed address).
     */
    private suspend fun moved(): PlexServer? =
        moving.withLock {
            val found = PlexProbe.reach(http(), store.connections, store.machineId) ?: return@withLock null
            store.moveTo(found.uri)
            server()
        }

    /** The client for this phone's device. */
    private fun http(): PlexHttp =
        OkHttpPlex(store.device(deviceName = Build.MODEL.orEmpty(), version = BuildConfig.VERSION_NAME))

    /**
     * The server and sign-in as this phone holds them, or null when nobody is
     * signed in. Read from the store on every call, so a token or address
     * replaced since is used by the next request.
     */
    private fun server(): PlexServer? =
        if (store.signedIn) store.server(deviceName = Build.MODEL.orEmpty(), version = BuildConfig.VERSION_NAME) else null

    /**
     * The library, kept while the address, the token and the library stay the
     * same and built again when any changes. It holds no cache.
     */
    @Synchronized
    private fun library(): PlexLibrary? {
        val now = server() ?: return null
        val key = Triple(now.address, now.token, now.sectionKey)
        shelves?.takeIf { shelvesFor == key }?.let { return it }
        shelvesFor = key
        val token = now.token
        return PlexLibrary(http(), now, signedOut = { revoked(token) }, moved = ::moved).also { shelves = it }
    }

    internal companion object {
        /**
         * Tells the service that the account on this phone changed. The
         * settings screen passes this to `SettingsActions`. Whether it was a
         * sign-in or a sign-out is read from the store.
         */
        fun announce(context: Context) {
            if (PackStore(context).signedIn) {
                signedIn(context, PackService::class.java)
            } else {
                signedOut(context, PackService::class.java)
            }
        }
    }
}
