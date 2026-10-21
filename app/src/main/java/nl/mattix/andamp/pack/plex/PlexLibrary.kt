// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.model.BrowseCapabilities
import nl.mattix.andamp.core.packapi.PackAlbum
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackArtist
import nl.mattix.andamp.core.packapi.PackPlaylist
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.PackTrack
import nl.mattix.andamp.core.packapi.UNKNOWN_COUNT
import nl.mattix.andamp.core.packapi.toPack
import nl.mattix.andamp.pack.common.TrackFolders
import org.json.JSONObject

/**
 * One music library on the server, answered one [PackQuestion] at a time.
 * Most questions are one call to the server, and their answers are not kept.
 *
 * The server pages every query: `X-Plex-Container-Start` and
 * `X-Plex-Container-Size` go out as headers and `totalSize` comes back,
 * which [PackAnswer.more] is computed from.
 *
 * The server keeps genres on albums and artists, not on tracks, so a genre is
 * an album genre and its tracks are those of the albums tagged with it. The
 * list of genres comes whole and is cut into pages here, and each genre on a
 * page is counted with one more call.
 *
 * The server has no list of the folders that hold tracks, so the folders are
 * found by reading every track of the library a page at a time and grouping
 * the tracks by the folder of their file; see [TrackFolders]. The last such
 * walk is kept in memory with the rows of its tracks. A first page of
 * [PackQuestion.FOLDERS] walks again; a later page and
 * [PackQuestion.FOLDER_TRACKS] are answered from the kept walk, and walk
 * first when none is kept. One walk runs at a time.
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

    /** The last walk that read every track; see [walk]. Volatile, because questions arrive on several binder threads. */
    @Volatile
    private var kept: Walk? = null

    /** One walk at a time; a question that finds one running waits for it. */
    private val walking = Mutex()

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
            PackQuestion.GENRES -> genres(question)
            PackQuestion.GENRE_TRACKS -> genreTracks(question)
            PackQuestion.FOLDERS -> folders(question)
            PackQuestion.FOLDER_TRACKS -> folderTracks(question)
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

    /**
     * The library's album genres in name order, each with how many tracks it
     * has. Only the genres on the page are counted, [COUNTS_AT_ONCE] at a
     * time, and a count that cannot be read is [UNKNOWN_COUNT].
     */
    private suspend fun genres(question: PackQuestion): PackAnswer {
        val container =
            fetched(Plex.sectionGenres(server.sectionKey), mapOf(TYPE to Plex.ALBUM.toString())) ?: return FAILED
        val listed =
            container
                .objects(DIRECTORY)
                .map { PackPlaylist(id = it.text("key"), name = it.text("title")) }
                .filter { it.id.isNotEmpty() }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val (page, more) = listed.cut(question)
        val asking = Semaphore(COUNTS_AT_ONCE)
        val counted =
            coroutineScope {
                page.map { genre -> async { asking.withPermit { genre.copy(trackCount = genreSize(genre.id)) } } }.awaitAll()
            }
        return PackAnswer(playlists = counted, more = more)
    }

    /** How many tracks the genre [id] has: the `totalSize` the server answers an empty page of them with. */
    private suspend fun genreSize(id: String): Int =
        fetched(section(), tagged(id), NOTHING)?.let { count(it, TOTAL) } ?: UNKNOWN_COUNT

    /** The tracks of the genre [PackQuestion.id], by album, each album's in disc and track order. */
    private suspend fun genreTracks(question: PackQuestion): PackAnswer =
        asked(section(), tagged(question.id) + (SORT to ALBUM_ORDER), question) { container ->
            PackAnswer(tracks = PlexRow.tracks(container.optJSONArray(METADATA), server, sdk), more = more(container, question))
        }

    /** The query for the tracks of the albums tagged with the genre [id]. */
    private fun tagged(id: String): Map<String, String> = mapOf(TYPE to Plex.TRACK.toString(), ALBUM_GENRE to id)

    /** The folders that hold tracks, in name order, cut from a walk; a first page walks again. */
    private suspend fun folders(question: PackQuestion): PackAnswer {
        val walk = walk(fresh = question.from() == 0) ?: return FAILED
        val (page, more) = walk.folders.groups().cut(question)
        return PackAnswer(playlists = page.map { it.toPack() }, more = more)
    }

    /**
     * The tracks directly in the folder [PackQuestion.id], in album order, cut
     * from the kept walk. A folder the walk did not find has none.
     */
    private suspend fun folderTracks(question: PackQuestion): PackAnswer {
        val walk = walk(fresh = false) ?: return FAILED
        val (page, more) =
            walk.folders
                .trackIds(question.id)
                .mapNotNull(walk.rows::get)
                .cut(question)
        return PackAnswer(tracks = page, more = more)
    }

    /**
     * The kept walk, or a new one when [fresh] is set or none is kept. A walk
     * that ended while this question waited for [walking] is taken as it is,
     * so questions that arrive together walk once. Null when the walk failed,
     * which leaves the kept one as it was.
     */
    private suspend fun walk(fresh: Boolean): Walk? {
        val before = kept
        return walking.withLock {
            val now = kept
            if (now != null && (!fresh || now !== before)) now else walked()?.also { kept = it }
        }
    }

    /**
     * Every track of the library, [WALK_PAGE] at a time in album order,
     * grouped by the folder of its file. A track without a file is left out.
     * The folders are named from the folders the library reads, or from the
     * folder the files share when those cannot be read. Null when a page
     * fails.
     */
    private suspend fun walked(): Walk? {
        val roots = locations()
        val rows = HashMap<String, PackTrack>()
        val files = mutableListOf<Pair<String, String>>()
        var from = 0
        do {
            val page = PackQuestion(PackQuestion.FOLDERS, offset = from, limit = WALK_PAGE)
            val container = fetched(section(), EVERY_TRACK, paged(page)) ?: return null
            for (item in container.objects(METADATA)) {
                val file = PlexRow.file(item)
                val row = PlexRow.track(item, server, sdk)
                if (file.isNotEmpty() && row != null) {
                    val id = item.text(KEY)
                    rows[id] = row
                    files += id to file
                }
            }
            // the next page starts after every entry on this one, read or not
            val onPage = container.optJSONArray(METADATA)?.length() ?: 0
            from += onPage
        } while (onPage > 0 && more(container, page))
        return Walk(TrackFolders.of(files, roots), rows)
    }

    /** The folders this library reads, from the server's list of libraries; null when they cannot be read. */
    private suspend fun locations(): List<String>? =
        fetched(Plex.SECTIONS, emptyMap())
            ?.objects(DIRECTORY)
            ?.firstOrNull { it.text("key") == server.sectionKey }
            ?.objects("Location")
            ?.map { it.text("path") }
            ?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() }

    /** The path of everything in the library the questions go to. */
    private fun section(): String = Plex.sectionAll(server.sectionKey)

    /** The path of the library's search. */
    private fun searching(): String = Plex.sectionSearch(server.sectionKey)

    /** The paging headers: where the page starts and how long it is. */
    private fun paged(question: PackQuestion): Map<String, String> =
        mapOf(Plex.CONTAINER_START to question.from().toString(), Plex.CONTAINER_SIZE to question.size().toString())

    /** One `GET` for the page [question] asks for, read with [answer]; `failed` when [fetched] gives nothing. */
    private suspend fun asked(
        path: String,
        params: Map<String, String>,
        question: PackQuestion,
        answer: (JSONObject) -> PackAnswer,
    ): PackAnswer = fetched(path, params, paged(question))?.let(answer) ?: FAILED

    /**
     * One `GET`, and the `MediaContainer` it answered, or null when it failed.
     * A server that does not answer is asked once more on the connection
     * [moved] finds, when it finds one. When another question moved it
     * meanwhile, that connection is used without asking again.
     */
    private suspend fun fetched(
        path: String,
        params: Map<String, String>,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject? {
        val was = server
        val first = http.get(was.url(path, params), was.token, headers)
        if (first !is PlexReply.Unreachable) return read(first)
        if (server === was) server = moved() ?: return null
        val now = server
        return read(http.get(now.url(path, params), now.token, headers))
    }

    /** The `MediaContainer` of a reply, or null for anything else; a 401 also calls [signedOut]. */
    private fun read(reply: PlexReply): JSONObject? =
        when (reply) {
            is PlexReply.Answered -> {
                reply.obj?.optJSONObject(CONTAINER)
            }

            PlexReply.Unauthorized -> {
                signedOut()
                null
            }

            else -> {
                null
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

    /** One walk of the library: the folders that hold tracks, and the rows of those tracks by `ratingKey`. */
    private class Walk(
        val folders: TrackFolders,
        val rows: Map<String, PackTrack>,
    )

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
        private const val DIRECTORY = "Directory"
        private const val KEY = "ratingKey"
        private const val LEAF_COUNT = "leafCount"
        private const val TOTAL = "totalSize"
        private const val TYPE = "type"
        private const val QUERY = "query"
        private const val SORT = "sort"
        private const val TITLE = "titleSort"
        private const val ALBUM_GENRE = "album.genre"

        /**
         * The server's default order for tracks, as it lists it under
         * `library/sections/{key}/sorts?type=10`: by album artist and album,
         * and within an album in disc and track order. The server answers a
         * sort field it does not know with a 500.
         */
        private const val ALBUM_ORDER =
            "artist.titleSort,album.titleSort,album.year,track.absoluteIndex,track.index,track.titleSort,track.id"

        /** The query for every track of the library, in [ALBUM_ORDER]. */
        private val EVERY_TRACK = mapOf(TYPE to Plex.TRACK.toString(), SORT to ALBUM_ORDER)

        /** How many tracks one page of a walk asks for. */
        private const val WALK_PAGE = 500

        /** How many genres are counted at once. */
        private const val COUNTS_AT_ONCE = 4

        /** The paging headers of an empty page, which the server answers with `totalSize` and no rows. */
        private val NOTHING = mapOf(Plex.CONTAINER_START to "0", Plex.CONTAINER_SIZE to "0")
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

/**
 * The page of this list that [question] asks for, and whether more follows
 * it. An offset past the end gives an empty page.
 */
private fun <T> List<T>.cut(question: PackQuestion): Pair<List<T>, Boolean> {
    val from = question.from().coerceAtMost(size)
    val to = from + minOf(size - from, question.size())
    return subList(from, to).toList() to (to < size)
}

/** Where this question starts; a negative offset is read as 0. */
private fun PackQuestion.from(): Int = offset.coerceAtLeast(0)

/** The page size sent; a limit of 0 or less becomes [PackQuestion.PAGE]. */
private fun PackQuestion.size(): Int = if (limit <= 0) PackQuestion.PAGE else limit
