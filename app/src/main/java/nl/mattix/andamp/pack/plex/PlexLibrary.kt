// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.packapi.PackAlbum
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackArtist
import nl.mattix.andamp.core.packapi.PackPlaylist
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.UNKNOWN_COUNT
import org.json.JSONObject

/**
 * One music library on the server, answered one [PackQuestion] at a time.
 * Each question is one call to the server; nothing is cached.
 *
 * The server pages every query: `X-Plex-Container-Start` and
 * `X-Plex-Container-Size` go out as headers and `totalSize` comes back,
 * which [PackAnswer.more] is computed from.
 *
 * A call that fails in any way is answered with `failed = true`, never as an
 * empty page. A 401 also calls [signedOut], because it means the token is not
 * valid and asking again will not help. A server that cannot be reached is
 * asked once more through [moved], which may find it on another of its
 * connections.
 *
 * A count the server does not give is [UNKNOWN_COUNT].
 */
class PlexLibrary(
    private val http: PlexHttp,
    server: PlexServer,
    /** Called on every 401. */
    private val signedOut: () -> Unit = {},
    /** Called when the server does not answer; the server on another connection, or null when none answers. */
    private val moved: suspend () -> PlexServer? = { null },
    /** The phone's API level, which decides which rows play as they are; a parameter so a JVM test can choose it. */
    private val sdk: Int = android.os.Build.VERSION.SDK_INT,
) {
    /**
     * The server the questions go to; replaced by [moved]. Volatile, because questions
     * arrive on several binder threads and one of them may move it.
     */
    @Volatile
    var server: PlexServer = server
        private set

    /** One page of one question. An unknown kind is a failure. */
    suspend fun answer(question: PackQuestion): PackAnswer =
        when (question.kind) {
            PackQuestion.ARTISTS -> artists(question)
            PackQuestion.ALBUMS -> if (question.id.isEmpty()) everyAlbum(question) else artistAlbums(question)
            PackQuestion.TRACKS -> tracks(question)
            PackQuestion.PLAYLISTS -> playlists(question)
            PackQuestion.PLAYLIST_TRACKS -> playlistTracks(question)
            PackQuestion.SEARCH -> search(question)
            PackQuestion.FIND_ARTISTS -> findArtists(question)
            else -> FAILED
        }

    /** The library's artists, in the server's title order. */
    private suspend fun artists(question: PackQuestion): PackAnswer =
        asked(section(), mapOf(TYPE to Plex.ARTIST.toString(), SORT to TITLE), question) { container ->
            PackAnswer(artists = container.objects(METADATA).map(::artistOf), more = more(container, question))
        }

    /** Every album, a page at a time, in title order. */
    private suspend fun everyAlbum(question: PackQuestion): PackAnswer =
        asked(section(), mapOf(TYPE to Plex.ALBUM.toString(), SORT to TITLE), question) { container ->
            PackAnswer(albums = container.objects(METADATA).map(::albumOf), more = more(container, question))
        }

    /** An artist's albums, oldest first. */
    private suspend fun artistAlbums(question: PackQuestion): PackAnswer =
        asked(Plex.children(question.id), mapOf(SORT to "year,$TITLE"), question) { container ->
            PackAnswer(albums = container.objects(METADATA).map(::albumOf), more = more(container, question))
        }

    /** An album's tracks, in the server's disc and track order. */
    private suspend fun tracks(question: PackQuestion): PackAnswer =
        asked(Plex.children(question.id), emptyMap(), question) { container ->
            PackAnswer(tracks = PlexRow.tracks(container.optJSONArray(METADATA), server, sdk), more = more(container, question))
        }

    /** The server's audio playlists, in its order. No owner is given. */
    private suspend fun playlists(question: PackQuestion): PackAnswer =
        asked(Plex.PLAYLISTS, mapOf("playlistType" to "audio"), question) { container ->
            PackAnswer(playlists = container.objects(METADATA).map(::playlistOf), more = more(container, question))
        }

    /**
     * One playlist's entries, in the playlist's order, repeats included.
     * Entries that are not tracks are dropped from the page but still count
     * toward where the next page starts.
     */
    private suspend fun playlistTracks(question: PackQuestion): PackAnswer =
        asked(Plex.playlistItems(question.id), emptyMap(), question) { container ->
            PackAnswer(tracks = PlexRow.tracks(container.optJSONArray(METADATA), server, sdk), more = more(container, question))
        }

    /**
     * The tracks whose title holds the query, from the library's search
     * endpoint, which matches anywhere in a title. A blank query is answered
     * empty with no call.
     */
    private suspend fun search(question: PackQuestion): PackAnswer {
        val terms = question.query.trim()
        if (terms.isEmpty()) return PackAnswer()
        return asked(searching(), mapOf(TYPE to Plex.TRACK.toString(), QUERY to terms), question) { container ->
            PackAnswer(tracks = PlexRow.tracks(container.optJSONArray(METADATA), server, sdk), more = more(container, question))
        }
    }

    /** The artists whose name holds the query. A blank query is answered empty with no call. */
    private suspend fun findArtists(question: PackQuestion): PackAnswer {
        val terms = question.query.trim()
        if (terms.isEmpty()) return PackAnswer()
        return asked(searching(), mapOf(TYPE to Plex.ARTIST.toString(), QUERY to terms), question) { container ->
            PackAnswer(artists = container.objects(METADATA).map(::artistOf), more = more(container, question))
        }
    }

    /** The path of everything in the library the questions go to. */
    private fun section(): String = Plex.sectionAll(server.sectionKey)

    /** The path of the library's search. */
    private fun searching(): String = Plex.sectionSearch(server.sectionKey)

    /** The paging headers: where the page starts and how long it is. */
    private fun paged(question: PackQuestion): Map<String, String> =
        mapOf(Plex.CONTAINER_START to question.from().toString(), Plex.CONTAINER_SIZE to question.size().toString())

    /**
     * One `GET` for the page [question] asks for, read with [read]. A server
     * that does not answer is asked once more on the connection [moved]
     * finds, when it finds one. When another question moved it meanwhile,
     * that connection is used without asking again.
     */
    private suspend fun asked(
        path: String,
        params: Map<String, String>,
        question: PackQuestion,
        answer: (JSONObject) -> PackAnswer,
    ): PackAnswer {
        val was = server
        val first = http.get(was.url(path, params), was.token, paged(question))
        if (first !is PlexReply.Unreachable) return read(first, answer)
        if (server === was) server = moved() ?: return FAILED
        val now = server
        return read(http.get(now.url(path, params), now.token, paged(question)), answer)
    }

    /** A reply as an answer. Anything other than a `MediaContainer` is `failed`; a 401 also calls [signedOut]. */
    private fun read(
        reply: PlexReply,
        answer: (JSONObject) -> PackAnswer,
    ): PackAnswer =
        when (reply) {
            is PlexReply.Answered -> {
                reply.obj?.optJSONObject(CONTAINER)?.let(answer) ?: FAILED
            }

            PlexReply.Unauthorized -> {
                signedOut()
                FAILED
            }

            else -> {
                FAILED
            }
        }

    private fun artistOf(json: JSONObject): PackArtist = PackArtist(id = json.text(KEY), name = json.text("title"))

    private fun albumOf(json: JSONObject): PackAlbum =
        PackAlbum(
            id = json.text(KEY),
            title = json.text("title"),
            artist = json.text("parentTitle"),
            year = count(json, "year"),
            trackCount = count(json, LEAF_COUNT),
            // no compilation flag is read from an album
            kind = AlbumKind.ALBUM.name,
        )

    private fun playlistOf(json: JSONObject): PackPlaylist =
        PackPlaylist(id = json.text(KEY), name = json.text("title"), trackCount = count(json, LEAF_COUNT))

    companion object {
        /**
         * What this library can be asked, for `PackIdentity.descriptor`. A
         * constant, because the source describes itself before anybody is
         * signed in and a library exists.
         */
        val SHELVES =
            BrowseCapabilities(
                hasArtists = true,
                hasAlbums = true,
                canSearch = true,
                hasPlaylists = true,
                hasCatalogue = false,
            )

        private val FAILED = PackAnswer(failed = true)

        private const val CONTAINER = "MediaContainer"
        private const val METADATA = "Metadata"
        private const val KEY = "ratingKey"
        private const val LEAF_COUNT = "leafCount"
        private const val TYPE = "type"
        private const val QUERY = "query"
        private const val SORT = "sort"
        private const val TITLE = "titleSort"
    }
}

/**
 * Whether there are rows after this page: the page's start plus the number of
 * entries on it is below `totalSize`.
 *
 * The entries are counted in the raw array, so an entry that was dropped when
 * read still counts. Without a total, a full page is taken to mean more.
 */
private fun more(
    container: JSONObject,
    question: PackQuestion,
): Boolean {
    val onPage = container.optJSONArray("Metadata")?.length() ?: 0
    val start = question.from()
    return if (container.has("totalSize")) {
        start + onPage < container.optInt("totalSize")
    } else {
        onPage >= question.size()
    }
}

/** A count the server gave, or [UNKNOWN_COUNT] when the key is absent. */
private fun count(
    json: JSONObject,
    name: String,
): Int = if (json.has(name)) json.optInt(name, UNKNOWN_COUNT) else UNKNOWN_COUNT

/** Where this question starts; a negative offset is read as 0. */
private fun PackQuestion.from(): Int = offset.coerceAtLeast(0)

/** The page size sent; a limit of 0 or less becomes [PackQuestion.PAGE]. */
private fun PackQuestion.size(): Int = if (limit <= 0) PackQuestion.PAGE else limit
