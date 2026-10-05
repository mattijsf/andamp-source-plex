// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import org.json.JSONObject

/**
 * What is asked of a server before and after a sign-in: who it is, what it is
 * called, which music libraries it has, and which of its connections answers.
 */
object PlexProbe {
    /** The server's `machineIdentifier`, asked without a token. [Probed.Rejected] with 200 when the answer is not a server's. */
    suspend fun identity(
        http: PlexHttp,
        base: HttpUrl?,
    ): Probed<String> =
        probed(http.get(base?.let { Plex.under(it, Plex.IDENTITY) })) { container ->
            container.text("machineIdentifier").takeIf { it.isNotEmpty() }
        }

    /** The server's `friendlyName`, or null when it did not say. */
    suspend fun name(
        http: PlexHttp,
        base: HttpUrl?,
        token: String,
    ): String? =
        (
            probed(
                http.get(
                    base?.let {
                        Plex.under(it, Plex.ROOT)
                    },
                    token,
                ),
            ) { it.text("friendlyName") } as? Probed.Found
        )?.value

    /** The server's music libraries, in its order; empty when it has none. */
    suspend fun sections(
        http: PlexHttp,
        base: HttpUrl?,
        token: String,
    ): Probed<List<PlexSection>> =
        probed(http.get(base?.let { Plex.under(it, Plex.SECTIONS) }, token)) { container ->
            container
                .objects("Directory")
                .filter { it.text("type") == MUSIC }
                .map { PlexSection(key = it.text("key"), title = it.text("title")) }
                .takeIf { container.has("Directory") || container.has("size") }
        }

    /**
     * The connection to use for the server called [machineId]: every one in
     * [connections] is asked for its identity at once, and the best that
     * answers with that id wins. Local beats remote, and remote beats the
     * relay; among equals, the first to answer. The wait ends as soon as a
     * local one answers. Null when none does within [timeoutMs].
     *
     * Beside a local https connection, the plain http URL of its address is
     * tried too: a router with DNS rebind protection does not resolve the
     * `plex.direct` name to a private address.
     */
    suspend fun reach(
        http: PlexHttp,
        connections: List<PlexConnection>,
        machineId: String,
        timeoutMs: Long = PROBE_MS,
    ): PlexConnection? =
        coroutineScope {
            val candidates = candidates(connections)
            if (candidates.isEmpty() || machineId.isEmpty()) return@coroutineScope null
            val answers = Channel<Pair<PlexConnection, Boolean>>(candidates.size)
            val probes =
                candidates.map { connection ->
                    launch {
                        val found = withTimeoutOrNull(timeoutMs) { identity(http, connection.base) } as? Probed.Found
                        answers.send(connection to (found?.value == machineId))
                    }
                }
            var best: PlexConnection? = null
            repeat(candidates.size) {
                val (connection, matched) = answers.receive()
                if (matched && (best == null || rank(connection) < rank(checkNotNull(best)))) best = connection
                if (best?.let(::rank) == LOCAL) {
                    probes.forEach { it.cancel() }
                    return@coroutineScope best
                }
            }
            best
        }

    /** [connections] with an http twin after each local https one, with no duplicates. */
    fun candidates(connections: List<PlexConnection>): List<PlexConnection> =
        buildList {
            for (connection in connections) {
                if (connection.base == null) continue
                add(connection)
                val twin = connection.plainTwin() ?: continue
                if (connections.none { it.uri == twin.uri }) add(twin)
            }
        }.distinctBy { it.uri }

    /** 0 for a local connection, 1 for a remote one, 2 for the relay. */
    fun rank(connection: PlexConnection): Int =
        when {
            connection.relay -> RELAY
            connection.local -> LOCAL
            else -> REMOTE
        }

    /** The plain http URL at a local https connection's address, or null when it is not one. */
    private fun PlexConnection.plainTwin(): PlexConnection? {
        if (!local || relay) return null
        if (base?.scheme != "https" || address.isEmpty() || port <= 0) return null
        val host = if (address.contains(':')) "[$address]" else address
        return PlexConnection("http://$host:$port", local = true, relay = false, address = address, port = port)
    }

    /** A reply as a probe: the `MediaContainer` read with [read], or why there is none. */
    private fun <T> probed(
        reply: PlexReply,
        read: (JSONObject) -> T?,
    ): Probed<T> =
        when (reply) {
            is PlexReply.Answered -> {
                val container = reply.obj?.optJSONObject("MediaContainer")
                val value = container?.let(read)
                if (value == null) Probed.Rejected(OK) else Probed.Found(value)
            }

            is PlexReply.Unreachable -> {
                Probed.Unreachable(reply.why)
            }

            PlexReply.Unauthorized -> {
                Probed.Unauthorized
            }

            is PlexReply.Rejected -> {
                Probed.Rejected(reply.status)
            }
        }

    /** How long one connection gets to answer its identity. */
    const val PROBE_MS = 5_000L

    private const val LOCAL = 0
    private const val REMOTE = 1
    private const val RELAY = 2
    private const val OK = 200

    /** The `type` of a music library. */
    private const val MUSIC = "artist"
}

/** What a probe found, or why it found nothing. */
sealed interface Probed<out T> {
    data class Found<T>(
        val value: T,
    ) : Probed<T>

    /** Nothing answered: no route, a timeout, or an answer that was not JSON. */
    data class Unreachable(
        val why: String,
    ) : Probed<Nothing>

    /** The server does not accept the token. */
    data object Unauthorized : Probed<Nothing>

    /** Something answered that is not a Plex server, with this status; 200 for JSON that is not a server's. */
    data class Rejected(
        val status: Int,
    ) : Probed<Nothing>
}
