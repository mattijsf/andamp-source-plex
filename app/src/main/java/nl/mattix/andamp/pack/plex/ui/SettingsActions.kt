// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex.ui

import android.content.Context
import android.os.Build
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nl.mattix.andamp.pack.plex.BuildConfig
import nl.mattix.andamp.pack.plex.OkHttpPlex
import nl.mattix.andamp.pack.plex.PinState
import nl.mattix.andamp.pack.plex.PlexConnection
import nl.mattix.andamp.pack.plex.PlexHttp
import nl.mattix.andamp.pack.plex.PlexPin
import nl.mattix.andamp.pack.plex.PlexProbe
import nl.mattix.andamp.pack.plex.PlexSection
import nl.mattix.andamp.pack.plex.PlexServer
import nl.mattix.andamp.pack.plex.PlexServerCandidate
import nl.mattix.andamp.pack.plex.PlexSignIn
import nl.mattix.andamp.pack.plex.Probed
import nl.mattix.andamp.pack.plex.pack.PackStore

/**
 * What the settings page does to the store, plex.tv and the server. An
 * interface, so the page is tested with a fake in its place.
 *
 * There are two ways in. Through the account: a code from [startLink],
 * entered at plex.tv/link, gives the account's token; [finishLink] then
 * lists the account's servers, reaches the chosen one and reads its music
 * libraries. With a typed address and token: [signInManual] asks the server
 * directly. Both end in a library being kept, through [chooseLibrary] when
 * the server has several.
 */
internal interface PlexActions {
    /** What is kept on this phone now, for the page to open with. */
    fun kept(): Kept

    /** A new code to show, or null when plex.tv gave none. */
    suspend fun startLink(): PlexPin?

    /** The state of [pin]; the page polls this. */
    suspend fun linkState(pin: PlexPin): PinState

    /** Continues with the account's [token] once the code was entered: the servers, the connection, the libraries. */
    suspend fun finishLink(token: String): Tried

    /**
     * Continues with [choice]: a server from [Tried.ChooseServer], or a
     * library from [Tried.ChooseLibrary] or [libraries], which is kept.
     */
    suspend fun choose(
        what: Pick,
        choice: Choice,
    ): Tried

    /** The music libraries of the server under way or kept; empty when it cannot be asked. */
    suspend fun libraries(): List<Choice>

    /** Tries a typed address and token without keeping anything, and says what happened. */
    suspend fun testManual(
        address: String,
        token: String,
    ): Tried

    /** Signs in with a typed address and token, and says what happened. */
    suspend fun signInManual(
        address: String,
        token: String,
    ): Tried

    /**
     * Asks the kept server whether it still takes the kept token, looking for
     * it on its other connections when it does not answer.
     */
    suspend fun test(): Tried

    /** Signs out on plex.tv first when the sign-in went through it, then on the phone whether or not plex.tv answered. */
    suspend fun signOut()
}

/** What the phone holds, without the tokens. [manual] is a sign-in with a typed token, which has no account. */
internal data class Kept(
    val user: String,
    val serverName: String,
    val address: String,
    val libraryTitle: String,
    val signedIn: Boolean,
    val manual: Boolean = false,
)

/** One server or one library to choose from. */
internal data class Choice(
    val id: String,
    val title: String,
)

/** What a choice is of. */
internal enum class Pick { SERVER, LIBRARY }

/** The outcome of a test or a sign-in. */
internal sealed interface Tried {
    data object SignedIn : Tried

    /** A test: the server called [serverName] answered and has music. Nothing was kept. */
    data class Reached(
        val serverName: String,
    ) : Tried

    /** A test of the kept sign-in: the server accepts its token. */
    data object StillSignedIn : Tried

    /** A test of the kept sign-in: the server answered 401 for its token. */
    data object Revoked : Tried

    /** What was typed is not a URL; nothing was asked. */
    data object NoAddress : Tried

    /** Nothing answered: no route, a timeout, a refused connection, or none of a server's connections. */
    data class NoServer(
        val why: String,
    ) : Tried

    /** Something answered that is not a Plex server, such as a router's page or a proxy asking for its own login. */
    data class NotPlex(
        val status: Int,
    ) : Tried

    /** The server does not accept the typed token. */
    data object Refused : Tried

    /** The code expired before it was entered. */
    data object Expired : Tried

    /** The server answered, and has no music library. */
    data object NoMusic : Tried

    /** The server has one music library, called [title], so there is nothing to change to. */
    data class OneLibrary(
        val title: String,
    ) : Tried

    /** The account can reach no server. */
    data object NoServers : Tried

    /** The account can reach several servers; [PlexActions.choose] continues with one. */
    data class ChooseServer(
        val choices: List<Choice>,
    ) : Tried

    /** The server has several music libraries; [PlexActions.choose] keeps one. */
    data class ChooseLibrary(
        val choices: List<Choice>,
    ) : Tried

    /** Plex answered, and did not sign anybody in. */
    data class Failed(
        val why: String,
    ) : Tried
}

/** [PlexActions] over the store on disk, plex.tv and a real server. */
internal class SettingsActions(
    context: Context,
    private val store: PackStore,
    /** Tells every bound player that the account changed. A parameter, so a test starts no service. */
    private val announce: (Context) -> Unit = {},
    /** The client; a parameter, so a test can answer with recorded replies. Null makes the real one for this phone. */
    http: PlexHttp? = null,
    /** The phone's name, which plex.tv lists under the account's devices. */
    private val deviceName: String = Build.MODEL.orEmpty(),
    private val version: String = BuildConfig.VERSION_NAME,
    /** How long one connection gets to answer when a server is looked for. */
    private val probeMs: Long = PlexProbe.PROBE_MS,
) : PlexActions {
    private val app = context.applicationContext
    private val http: PlexHttp = http ?: OkHttpPlex(store.device(deviceName, version))
    private val account = PlexSignIn(this.http)

    /** A sign-in under way: everything learned so far, kept here until a library is chosen and the store is written. */
    private var pending: Pending? = null

    private data class Pending(
        val accountToken: String = "",
        val user: String = "",
        val servers: List<PlexServerCandidate> = emptyList(),
        val address: String = "",
        val connections: List<PlexConnection> = emptyList(),
        val serverToken: String = "",
        val serverName: String = "",
        val machineId: String = "",
        val libraries: List<PlexSection> = emptyList(),
    )

    override fun kept(): Kept =
        Kept(
            user = store.userName,
            serverName = store.serverName,
            address = store.address,
            libraryTitle = store.sectionTitle,
            signedIn = store.signedIn,
            manual = store.signedIn && store.accountToken.isEmpty(),
        )

    override suspend fun startLink(): PlexPin? = account.startPin()

    override suspend fun linkState(pin: PlexPin): PinState = account.pinState(pin)

    override suspend fun finishLink(token: String): Tried {
        pending = null
        val user = account.user(token).orEmpty()
        val servers = account.servers(token) ?: return Tried.Failed("plex.tv did not list your servers")
        if (servers.isEmpty()) return Tried.NoServers
        val begun = Pending(accountToken = token, user = user, servers = servers)
        pending = begun
        return if (servers.size ==
            1
        ) {
            connect(begun, servers.single())
        } else {
            Tried.ChooseServer(servers.map { Choice(it.id, it.name) })
        }
    }

    override suspend fun choose(
        what: Pick,
        choice: Choice,
    ): Tried =
        when (what) {
            Pick.SERVER -> chooseServer(choice.id)
            Pick.LIBRARY -> chooseLibrary(choice.id, choice.title)
        }

    private suspend fun chooseServer(id: String): Tried {
        val begun = pending ?: return Tried.Failed("no sign-in is under way")
        val candidate = begun.servers.firstOrNull { it.id == id } ?: return Tried.Failed("that is not one of your servers")
        return connect(begun, candidate)
    }

    /** Reaches [candidate] on one of its connections, reads its libraries, and goes on to the library step. */
    private suspend fun connect(
        begun: Pending,
        candidate: PlexServerCandidate,
    ): Tried {
        val connection =
            PlexProbe.reach(http, candidate.connections, candidate.id, probeMs)
                ?: return Tried.NoServer("none of the server's addresses answered")
        val token = candidate.accessToken.ifEmpty { begun.accountToken }
        val probed = PlexProbe.sections(http, connection.base, token)
        val stopped = probed.stopped(unauthorized = Tried.Failed("the server did not accept the sign-in"))
        if (stopped != null) return stopped
        val reached =
            begun.copy(
                address = connection.uri,
                connections = candidate.connections,
                serverToken = token,
                serverName = candidate.name,
                machineId = candidate.id,
                libraries = (probed as Probed.Found).value,
            )
        pending = reached
        return libraryStep(reached)
    }

    /** Keeps the one library, or asks which of several. */
    private fun libraryStep(reached: Pending): Tried =
        when (reached.libraries.size) {
            0 -> Tried.NoMusic
            1 -> keep(reached, reached.libraries.single())
            else -> Tried.ChooseLibrary(reached.libraries.map { Choice(it.key, it.title) })
        }

    /** Writes the sign-in with [library] and announces it. The store is written before the announce, which reads it. */
    private fun keep(
        reached: Pending,
        library: PlexSection,
    ): Tried {
        store.signIn(
            accountToken = reached.accountToken,
            userName = reached.user,
            serverToken = reached.serverToken,
            serverName = reached.serverName,
            machineId = reached.machineId,
            address = reached.address,
            connections = reached.connections,
            sectionKey = library.key,
            sectionTitle = library.title,
        )
        pending = null
        announce(app)
        return Tried.SignedIn
    }

    /**
     * The kept sign-in comes first: a sign-in left pending by a canceled picker must
     * not be kept in its place.
     */
    private fun chooseLibrary(
        key: String,
        title: String,
    ): Tried {
        if (store.signedIn) {
            pending = null
            store.chooseLibrary(key, title)
            announce(app)
            return Tried.SignedIn
        }
        val reached = pending ?: return Tried.Failed("no sign-in is under way")
        return keep(reached, reached.libraries.firstOrNull { it.key == key } ?: PlexSection(key, title))
    }

    /** The kept server's libraries when signed in, otherwise those of the sign-in under way. */
    override suspend fun libraries(): List<Choice> {
        val begun = pending
        val libraries =
            when {
                store.signedIn -> {
                    val server = store.server(deviceName, version)
                    (PlexProbe.sections(http, server.base, server.token) as? Probed.Found)?.value.orEmpty()
                }

                begun != null -> {
                    begun.libraries
                }

                else -> {
                    emptyList()
                }
            }
        return libraries.map { Choice(it.key, it.title) }
    }

    override suspend fun testManual(
        address: String,
        token: String,
    ): Tried {
        val asked = manual(address, token)
        return when {
            asked !is Pending -> asked as Tried
            asked.libraries.isEmpty() -> Tried.NoMusic
            else -> Tried.Reached(asked.serverName)
        }
    }

    override suspend fun signInManual(
        address: String,
        token: String,
    ): Tried {
        pending = null
        val asked = manual(address, token)
        if (asked !is Pending) return asked as Tried
        pending = asked
        return libraryStep(asked)
    }

    /**
     * Asks the server at a typed [address] with a typed [token]: its identity
     * without the token, then its libraries with it. A [Pending] with what it
     * said, or the [Tried] that stopped it.
     */
    private suspend fun manual(
        address: String,
        token: String,
    ): Any {
        val server = PlexServer(address, store.device(deviceName, version), token)
        val base = server.base ?: return Tried.NoAddress
        val identity = PlexProbe.identity(http, base)
        val sections = if (identity is Probed.Found) PlexProbe.sections(http, base, token) else null
        // a 401 for the anonymous identity call is a proxy that wants its own login, not a wrong token
        val stopped =
            identity.stopped(unauthorized = Tried.NotPlex(UNAUTHORIZED)) ?: sections?.stopped(unauthorized = Tried.Refused)
        if (stopped != null) return stopped
        val name = PlexProbe.name(http, base, token).orEmpty().ifEmpty { base.host }
        return Pending(
            address = address.trim(),
            serverToken = token,
            serverName = name,
            machineId = (identity as Probed.Found).value,
            libraries = (checkNotNull(sections) as Probed.Found).value,
        )
    }

    /** Why a probe stopped a sign-in, or null when it found what it asked for. [unauthorized] is what a 401 means here. */
    private fun Probed<*>.stopped(unauthorized: Tried): Tried? =
        when (this) {
            is Probed.Found -> null
            is Probed.Unreachable -> Tried.NoServer(why)
            Probed.Unauthorized -> unauthorized
            is Probed.Rejected -> Tried.NotPlex(status)
        }

    override suspend fun test(): Tried {
        if (!store.signedIn) return Tried.Failed("nobody is signed in")
        val server = store.server(deviceName, version)
        val first = PlexProbe.sections(http, server.base, server.token)
        if (first !is Probed.Unreachable || store.connections.isEmpty()) return tested(first)
        val elsewhere = PlexProbe.reach(http, store.connections, store.machineId, probeMs) ?: return tested(first)
        store.moveTo(elsewhere.uri)
        return tested(PlexProbe.sections(http, elsewhere.base, server.token))
    }

    /** What the libraries probe says about the kept sign-in. */
    private fun tested(probed: Probed<List<PlexSection>>): Tried =
        when (probed) {
            is Probed.Found -> Tried.StillSignedIn
            Probed.Unauthorized -> Tried.Revoked
            is Probed.Unreachable -> Tried.NoServer(probed.why)
            is Probed.Rejected -> Tried.NotPlex(probed.status)
        }

    /**
     * Not cancellable, so the tokens are removed from the phone even when the
     * page closes before plex.tv answers. plex.tv gets [LOGOUT_MS].
     */
    override suspend fun signOut() =
        withContext(NonCancellable) {
            val token = store.accountToken
            if (token.isNotEmpty()) withTimeoutOrNull(LOGOUT_MS) { account.signOut(token) }
            store.signOut()
            pending = null
            announce(app)
        }

    private companion object {
        /** How long a sign-out waits for plex.tv before signing out on the phone. */
        const val LOGOUT_MS = 5_000L

        const val UNAUTHORIZED = 401
    }
}

/**
 * Polls until [pin] is entered and then continues the sign-in, or says why
 * it was not.
 *
 * The wait ends with [Tried.NoServer] after [MISSES] failed polls in a row and
 * with [Tried.Expired] when plex.tv reports the code expired. While plex.tv
 * answers "waiting", the poll continues until the caller cancels it.
 */
internal suspend fun PlexActions.awaitApproval(pin: PlexPin): Tried {
    var misses = 0
    while (true) {
        delay(POLL_MS)
        when (val state = linkState(pin)) {
            is PinState.Approved -> return finishLink(state.token)
            PinState.Expired -> return Tried.Expired
            PinState.Waiting -> misses = 0
            PinState.Failed -> if (++misses >= MISSES) return Tried.NoServer("plex.tv stopped answering")
        }
    }
}

/** The interval between polls for a shown code. */
internal const val POLL_MS = 2_000L

/** How many failed asks in a row end the wait for a code. */
private const val MISSES = 4
