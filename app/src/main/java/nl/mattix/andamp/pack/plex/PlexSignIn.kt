// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import org.json.JSONArray
import org.json.JSONObject

/**
 * The account side of a sign-in, on plex.tv: a code the listener enters at
 * plex.tv/link, the account behind the token that gives, the servers the
 * account can reach, and the sign-out.
 *
 * The [http] carries this phone's device headers; plex.tv keys the code on
 * the `X-Plex-Client-Identifier`, so the same device asks about it.
 */
class PlexSignIn(
    private val http: PlexHttp,
) {
    /** A new code to show the listener. Null on any failure. */
    suspend fun startPin(): PlexPin? {
        val json = http.post(Plex.tv(Plex.PINS, mapOf("strong" to "false"))).obj ?: return null
        val id = json.optLong("id", -1)
        val code = json.text("code")
        return if (id < 0 || code.isEmpty()) null else PlexPin(id, code, json.text("expiresAt"))
    }

    /**
     * Whether the code has been entered. The caller polls this; plex.tv sends
     * no notice. A 404 means plex.tv no longer knows the code, which is
     * [PinState.Expired].
     */
    suspend fun pinState(pin: PlexPin): PinState =
        when (val reply = http.get(Plex.tv(Plex.pin(pin.id)))) {
            is PlexReply.Answered -> {
                val token = reply.obj?.text("authToken").orEmpty()
                if (reply.obj == null) {
                    PinState.Failed
                } else if (token.isEmpty()) {
                    PinState.Waiting
                } else {
                    PinState.Approved(token)
                }
            }

            is PlexReply.Rejected -> {
                if (reply.status == NOT_FOUND) PinState.Expired else PinState.Failed
            }

            else -> {
                PinState.Failed
            }
        }

    /** The name of the account behind [token], or null when plex.tv did not say. */
    suspend fun user(token: String): String? {
        val json = http.get(Plex.tv(Plex.USER), token).obj ?: return null
        return json.text("username").ifEmpty { json.text("title") }.takeIf { it.isNotEmpty() }
    }

    /**
     * The servers the account behind [token] can reach, owned and shared,
     * each with its connections and the token that opens it. Null when
     * plex.tv did not answer with a list.
     */
    suspend fun servers(token: String): List<PlexServerCandidate>? {
        val reply = http.get(Plex.tv(Plex.RESOURCES, Plex.EVERY_CONNECTION), token) as? PlexReply.Answered ?: return null
        val devices = reply.body as? JSONArray ?: return null
        return (0 until devices.length())
            .mapNotNull(devices::optJSONObject)
            .filter { it.text("provides").split(',').contains(SERVER) }
            .mapNotNull(PlexServerCandidate::of)
    }

    /**
     * Ends the account's session for [token] on plex.tv. True when plex.tv
     * confirmed it. A token that is only forgotten on the phone stays valid
     * until it is removed under the account's devices.
     */
    suspend fun signOut(token: String): Boolean = http.delete(Plex.tv(Plex.SIGN_OUT), token) is PlexReply.Answered

    private companion object {
        const val NOT_FOUND = 404
        const val SERVER = "server"
    }
}

/**
 * A code in progress. [code] is shown to the listener; [id] is what plex.tv
 * is asked about. [expiresAt] is plex.tv's own timestamp and is only shown.
 */
data class PlexPin(
    val id: Long,
    val code: String,
    val expiresAt: String = "",
)

/** The state of a code. */
sealed interface PinState {
    /** Not yet entered. */
    data object Waiting : PinState

    /** Entered: [token] is the account's token. Not a data class, so [toString] leaves the token out. */
    class Approved(
        val token: String,
    ) : PinState {
        override fun toString(): String = "Approved(token=…)"
    }

    /** plex.tv no longer knows the code; a new one is needed. */
    data object Expired : PinState

    /** plex.tv gave no usable answer; asking again may work. */
    data object Failed : PinState
}

/**
 * A server as plex.tv lists it for an account.
 *
 * [accessToken] opens this server; for a server somebody else shares it
 * differs from the account's token. Not a data class, so [toString] leaves it
 * out.
 *
 * @param id the server's `clientIdentifier`, which is its `machineIdentifier`
 */
class PlexServerCandidate(
    val id: String,
    val name: String,
    val accessToken: String,
    val owned: Boolean,
    val connections: List<PlexConnection>,
) {
    override fun toString(): String = "PlexServerCandidate(id=$id, name=$name, owned=$owned, connections=${connections.size})"

    companion object {
        /** One resource as plex.tv lists it, or null without an id. */
        fun of(json: JSONObject): PlexServerCandidate? {
            val id = json.text("clientIdentifier")
            if (id.isEmpty()) return null
            return PlexServerCandidate(
                id = id,
                name = json.text("name"),
                accessToken = json.text("accessToken"),
                owned = json.optBoolean("owned"),
                connections = PlexConnection.list(json.optJSONArray("connections")),
            )
        }
    }
}
