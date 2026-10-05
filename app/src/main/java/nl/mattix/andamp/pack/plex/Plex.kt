// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The product name, the headers and the paths of Plex's API that this source
 * uses: plex.tv for the sign-in and the list of servers, and the Plex Media
 * Server for the library and the audio.
 *
 * Every call carries the `X-Plex-*` headers of [PlexDevice] and asks for JSON
 * with `Accept`, because a server answers XML without it.
 */
object Plex {
    /** The product name in `X-Plex-Product`; plex.tv lists it under the account's devices. */
    const val PRODUCT = "Andamp"

    /** The platform name in `X-Plex-Platform`. */
    const val PLATFORM = "Android"

    /** The address of plex.tv, where the account signs in. */
    const val PLEX_TV = "https://plex.tv"

    /** The header a token goes in. The same name is the query parameter a cover URL carries; see `PlexRow.artwork`. */
    const val TOKEN = "X-Plex-Token"

    /** A new code for the listener to enter at plex.tv/link. `POST`. */
    const val PINS = "api/v2/pins"

    /** Where the listener enters the code. */
    const val LINK = "https://plex.tv/link"

    /** The state of a code: its `authToken` is set once the listener entered it. */
    fun pin(id: Long): String = "api/v2/pins/$id"

    /** The account behind a token: its `username`. */
    const val USER = "api/v2/user"

    /** The devices an account can reach, servers among them, each with its connections and its own token. */
    const val RESOURCES = "api/v2/resources"

    /** Ends the account's session for this token. `DELETE`. */
    const val SIGN_OUT = "api/v2/users/signout"

    /** The server's `machineIdentifier`, answered without a token. */
    const val IDENTITY = "identity"

    /** The server's root, with its `friendlyName`. */
    const val ROOT = ""

    /** The server's libraries; a music library has `type` `artist`. */
    const val SECTIONS = "library/sections"

    /** Everything of one `type` in a library, sorted by the query. */
    fun sectionAll(key: String): String = "library/sections/$key/all"

    /** Everything of one `type` in a library whose title holds the `query`. */
    fun sectionSearch(key: String): String = "library/sections/$key/search"

    /** An artist's albums or an album's tracks. */
    fun children(id: String): String = "library/metadata/$id/children"

    /** The server's playlists. */
    const val PLAYLISTS = "playlists"

    /** One playlist's entries, in its order. */
    fun playlistItems(id: String): String = "playlists/$id/items"

    /** The transcoder, which sends a track as mp3 from the `offset` asked for. */
    const val TRANSCODE = "music/:/transcode/universal/start.mp3"

    /** The `type` of an artist, an album and a track in a library query. */
    const val ARTIST = 8
    const val ALBUM = 9
    const val TRACK = 10

    /** The paging headers: where the page starts and how long it is. The search endpoint reads them only as headers. */
    const val CONTAINER_START = "X-Plex-Container-Start"
    const val CONTAINER_SIZE = "X-Plex-Container-Size"

    /** The parameters plex.tv needs to list every connection of a server, relays and IPv6 included. */
    val EVERY_CONNECTION = mapOf("includeHttps" to "1", "includeRelay" to "1", "includeIPv6" to "1")

    /** A URL under plex.tv. */
    fun tv(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): HttpUrl = checkNotNull(under(checkNotNull(PLEX_TV.toHttpUrlOrNull()), path, params))

    /**
     * [path] under [root], with [params] on the query. The path is added one
     * segment at a time, so a slash inside an id is escaped. An empty path is
     * the root itself.
     */
    fun under(
        root: HttpUrl,
        path: String,
        params: Map<String, String> = emptyMap(),
    ): HttpUrl {
        val url = root.newBuilder()
        if (path.isNotEmpty()) for (segment in path.split('/')) url.addPathSegment(segment)
        for ((name, value) in params) url.addQueryParameter(name, value)
        return url.build()
    }
}

/**
 * How this phone identifies itself to plex.tv and to a server. Every request
 * carries it, signed in or not.
 *
 * plex.tv lists [id] under the account's devices and keys the sign-in code on
 * it, so it has to stay the same for one install and differ between installs.
 * `pack/PackStore` makes one and keeps it.
 *
 * @param name the phone's model, shown as the device
 * @param id stable for this install; the `X-Plex-Client-Identifier`
 * @param version this source's version
 */
data class PlexDevice(
    val name: String,
    val id: String,
    val version: String,
) {
    /**
     * The headers of one request: who this phone is, that it reads JSON, and
     * [token] when there is one.
     *
     * A header value may only hold printable ASCII, so a model name with
     * anything else is cleaned; see [ascii].
     */
    fun headers(token: String = ""): Map<String, String> =
        buildMap {
            put("X-Plex-Client-Identifier", ascii(id))
            put("X-Plex-Product", Plex.PRODUCT)
            put("X-Plex-Version", ascii(version))
            put("X-Plex-Platform", Plex.PLATFORM)
            put("X-Plex-Device", ascii(name))
            put("X-Plex-Device-Name", ascii(name))
            put("Accept", "application/json")
            if (token.isNotEmpty()) put(Plex.TOKEN, ascii(token))
        }

    private companion object {
        /** [value] with every character outside printable ASCII replaced by a question mark. */
        fun ascii(value: String): String = value.map { if (it in ' '..'~') it else '?' }.joinToString("")
    }
}

/**
 * One way to reach a server, as plex.tv lists it: a URL, and whether it is on
 * the listener's own network or through Plex's relay.
 *
 * A local connection is a `plex.direct` name that resolves to the server's
 * network address; [address] and [port] are that address, so a plain http
 * URL can be tried beside it on a router that blocks such names.
 */
data class PlexConnection(
    val uri: String,
    val local: Boolean,
    val relay: Boolean,
    val address: String = "",
    val port: Int = 0,
) {
    /** The URL, or null when [uri] is not one. */
    val base: HttpUrl? get() = uri.toHttpUrlOrNull()

    /** This connection as JSON, for the store. */
    fun json(): JSONObject =
        JSONObject()
            .put("uri", uri)
            .put("local", local)
            .put("relay", relay)
            .put("address", address)
            .put("port", port)

    companion object {
        /** One connection as plex.tv lists it, or null without a `uri`. */
        fun of(json: JSONObject): PlexConnection? {
            val uri = json.text("uri")
            if (uri.isEmpty()) return null
            return PlexConnection(
                uri = uri,
                local = json.optBoolean("local"),
                relay = json.optBoolean("relay"),
                address = json.text("address"),
                port = json.optInt("port"),
            )
        }

        /** Every connection in [array] that reads as one. */
        fun list(array: JSONArray?): List<PlexConnection> {
            if (array == null) return emptyList()
            return (0 until array.length()).mapNotNull { at -> array.optJSONObject(at)?.let(::of) }
        }

        /** [connections] as a JSON array, for the store. */
        fun json(connections: List<PlexConnection>): String = JSONArray(connections.map { it.json() }).toString()

        /** The connections in a stored array; empty for anything that is not one. */
        fun stored(json: String): List<PlexConnection> = list(runCatching { JSONArray(json) }.getOrNull())
    }
}

/** A music library on a server: the `key` the queries go under and the title the listener sees. */
data class PlexSection(
    val key: String,
    val title: String,
)

/**
 * A server, the device, and the sign-in when there is one.
 *
 * A malformed address does not throw: [base] and [url] are null for it, and
 * the client answers `Unreachable`.
 *
 * Not a data class, so the token is not in the generated `toString`.
 *
 * @param address the connection in use: a plex.tv connection's URL, or what
 *   somebody typed, with or without a scheme
 * @param device who this phone is; see [PlexDevice]
 * @param token the server's token; empty before a sign-in
 * @param machineId the server's `machineIdentifier`, which a connection is
 *   checked against; empty when it was never read
 * @param sectionKey the music library the questions go to; empty before one
 *   is chosen
 */
class PlexServer(
    val address: String,
    val device: PlexDevice,
    val token: String = "",
    val machineId: String = "",
    val sectionKey: String = "",
) {
    /**
     * The address as a URL, or null when it is not one. A missing scheme is
     * read as http, because a server on the listener's own network is reached
     * over plain http on port 32400 unless plex.tv hands out its https name.
     */
    val base: HttpUrl? by lazy(LazyThreadSafetyMode.PUBLICATION) { root(address) }

    /** Whether a token is held. The server may have revoked it since. */
    val signedIn: Boolean get() = token.isNotEmpty()

    /** The headers of one request to this server, the token among them. */
    fun headers(): Map<String, String> = device.headers(token)

    /**
     * The URL of one call: [path] under the server's address, with [params] on
     * the query. Null when [base] is. The token goes in the header and not
     * here, except on a cover URL; see `PlexRow.artwork`.
     */
    fun url(
        path: String,
        params: Map<String, String> = emptyMap(),
    ): HttpUrl? = base?.let { Plex.under(it, path, params) }

    /** The same server with another address. */
    fun at(address: String): PlexServer = PlexServer(address, device, token, machineId, sectionKey)

    override fun toString(): String =
        "PlexServer(address=$address, device=${device.id}, machineId=$machineId, section=$sectionKey, " +
            "token=${if (token.isEmpty()) "none" else "…"})"

    private companion object {
        /** The address trimmed of spaces and trailing slashes, with `http://` put in front when it has no scheme. */
        fun root(address: String): HttpUrl? {
            val typed = address.trim().trimEnd('/')
            if (typed.isEmpty()) return null
            val whole = if (typed.contains(SCHEME_MARK)) typed else "http://$typed"
            return whole.toHttpUrlOrNull()
        }

        const val SCHEME_MARK = "://"
    }
}

/** The string under [key], or empty when it is absent or JSON `null`; `optString` would read `null` as the word. */
internal fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

/** The objects in the array under [key], skipping anything that is not one. */
internal fun JSONObject.objects(key: String): List<JSONObject> {
    val array: JSONArray = optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull(array::optJSONObject)
}
