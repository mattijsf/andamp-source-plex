// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import okhttp3.HttpUrl
import org.json.JSONArray
import org.json.JSONObject

/**
 * A [PlexHttp] that answers from a function or a map and records what it
 * was asked.
 *
 * The answers in `src/test/resources` were recorded from a Plex Media Server
 * (1.43.4) and from plex.tv, one call each, with the queries this source
 * sends and `Accept: application/json`. Tokens are replaced by
 * `PLEX-TOKEN-PLACEHOLDER` (the account's) and `SERVER-TOKEN-PLACEHOLDER`
 * (the server's), file paths by ones under `/music`, and the server's name
 * by "Living room". `playlists.json`, `playlist-items.json`,
 * `tracks-mixed.json`, `resources*.json` and `user.json` are written in the
 * same shape, because the recording server had no playlists, no FLAC or ALAC
 * and one connection. `unauthorized.html` is the body the server sent with a
 * 401, and `identity.xml` is what it answers without `Accept`.
 */
internal class FakePlexHttp(
    private val answer: suspend (Ask) -> PlexReply,
) : PlexHttp {
    /** Answers by path; any other path is unreachable. */
    constructor(replies: Map<String, PlexReply>) : this({ ask ->
        replies[ask.path] ?: PlexReply.Unreachable("nothing was recorded for ${ask.path}")
    })

    val asked = mutableListOf<Ask>()

    override suspend fun get(
        url: HttpUrl?,
        token: String,
        headers: Map<String, String>,
    ): PlexReply = Ask("GET", url, token, headers, null).also { asked += it }.let { answer(it) }

    override suspend fun post(
        url: HttpUrl?,
        token: String,
        body: JSONObject?,
    ): PlexReply = Ask("POST", url, token, emptyMap(), body?.toString()).also { asked += it }.let { answer(it) }

    override suspend fun delete(
        url: HttpUrl?,
        token: String,
    ): PlexReply = Ask("DELETE", url, token, emptyMap(), null).also { asked += it }.let { answer(it) }

    /** The one call this question made; it fails the test if there was more than one. */
    fun once(): Ask = asked.single()

    data class Ask(
        val method: String,
        val url: HttpUrl?,
        val token: String,
        val headers: Map<String, String>,
        val body: String?,
    ) {
        /** The path of the URL, such as `/library/sections/1/all`; empty when there is no URL. */
        val path: String get() = url?.encodedPath.orEmpty()

        val host: String get() = url?.host.orEmpty()

        fun param(name: String): String? = url?.queryParameter(name)
    }
}

/** A recorded body as a 200, read with [PlexReply.read]. */
internal fun recorded(name: String): PlexReply = PlexReply.read(200, fixture(name))

/** A recorded body, verbatim. */
internal fun fixture(name: String): String =
    checkNotNull(FakePlexHttp::class.java.getResourceAsStream("/$name")) { "no recording called $name" }
        .use { stream -> stream.readBytes().decodeToString() }

/** The `Metadata` array of a recording's container. */
internal fun items(name: String): JSONArray = JSONObject(fixture(name)).getJSONObject("MediaContainer").getJSONArray("Metadata")

/** The first object in a recording's `Metadata`. */
internal fun firstItem(name: String): JSONObject = items(name).getJSONObject(0)

/** The account's token as the recordings carry it. */
internal const val RECORDED_TOKEN = "PLEX-TOKEN-PLACEHOLDER"

/** The server's own token as `resources.json` carries it. */
internal const val SERVER_TOKEN = "SERVER-TOKEN-PLACEHOLDER"

/** The recording server's `machineIdentifier`. */
internal const val MACHINE = "5fa1ae225c2847ada51100e18c49d1f6f5122682"

/** The music library's key on the recording server. */
internal const val MUSIC = "1"

internal val DEVICE = PlexDevice(name = "Pixel 9", id = "7d3f2a9e-andamp-test", version = "0.1.0")

/** The recording server, signed in, on its local address. */
internal val SERVER = PlexServer("http://192.0.2.10:32400", DEVICE, SERVER_TOKEN, MACHINE, MUSIC)
