// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.UNKNOWN_COUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The folders and the genres, answered from recordings and written answers; see [FakePlexHttp]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlexLibraryGroupsTest {
    @Test
    fun `the genres are the albums' genres in name order, and only those on the page are counted`() =
        runTest {
            val totals = mapOf("9" to 4, "6" to 10, "7" to 2)
            val http =
                FakePlexHttp { ask ->
                    if (ask.path ==
                        GENRES
                    ) {
                        recorded("genres.json")
                    } else {
                        total(totals.getValue(ask.param("album.genre").orEmpty()))
                    }
                }

            val answer = library(http).answer(PackQuestion(PackQuestion.GENRES, offset = 0, limit = 2))

            assertEquals(listOf("electronic", "Pop/Rock"), answer.playlists.map { it.name })
            assertEquals(listOf("9", "6"), answer.playlists.map { it.id })
            assertEquals(listOf(4, 10), answer.playlists.map { it.trackCount })
            assertEquals(listOf(null, null), answer.playlists.map { it.owner })
            assertTrue("two of three", answer.more)
            assertFalse(answer.failed)
            val list = http.asked.first()
            assertEquals("9", list.param("type"))
            assertEquals("the list comes whole", emptyMap<String, String>(), list.headers)
            val counts = http.asked.drop(1)
            assertEquals(setOf("9", "6"), counts.map { it.param("album.genre") }.toSet())
            for (count in counts) {
                assertEquals(ALL, count.path)
                assertEquals("10", count.param("type"))
                assertEquals(mapOf("X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to "0"), count.headers)
            }
        }

    @Test
    fun `a genre that could not be counted has an unknown count, and the others still answer`() =
        runTest {
            val http =
                FakePlexHttp { ask ->
                    when {
                        ask.path == GENRES -> recorded("genres.json")
                        ask.param("album.genre") == "6" -> PlexReply.Rejected(500)
                        else -> total(3)
                    }
                }

            val answer = library(http).answer(PackQuestion(PackQuestion.GENRES))

            assertEquals(listOf("electronic", "Pop/Rock", "Rock"), answer.playlists.map { it.name })
            assertEquals(listOf(3, UNKNOWN_COUNT, 3), answer.playlists.map { it.trackCount })
            assertFalse(answer.more)
            assertFalse(answer.failed)
        }

    @Test
    fun `the genres are counted four at a time`() =
        runTest {
            val many = (1..6).joinToString(",") { """{"key":"$it","title":"Genre $it","type":"genre"}""" }
            var asking = 0
            var most = 0
            val http =
                FakePlexHttp { ask ->
                    if (ask.path == GENRES) {
                        PlexReply.read(200, """{"MediaContainer":{"size":6,"Directory":[$many]}}""")
                    } else {
                        most = maxOf(most, ++asking)
                        delay(100)
                        asking--
                        total(1)
                    }
                }

            val answer = library(http).answer(PackQuestion(PackQuestion.GENRES))

            assertEquals(List(6) { 1 }, answer.playlists.map { it.trackCount })
            assertEquals(4, most)
        }

    @Test
    fun `a genre list that cannot be read is a failure`() =
        runTest {
            val http = FakePlexHttp(mapOf(GENRES to PlexReply.Rejected(500)))

            val answer = library(http).answer(PackQuestion(PackQuestion.GENRES))

            assertTrue(answer.failed)
            assertEquals("nothing is counted", 1, http.asked.size)
        }

    @Test
    fun `a genre's tracks are its albums' tracks in album order, a page at a time`() =
        runTest {
            val http = FakePlexHttp(mapOf(ALL to recorded("tracks.json")))

            val answer = library(http).answer(PackQuestion(PackQuestion.GENRE_TRACKS, id = "6", offset = 0, limit = 3))

            val asked = http.once()
            assertEquals("10", asked.param("type"))
            assertEquals("6", asked.param("album.genre"))
            assertEquals(ALBUM_ORDER, asked.param("sort"))
            assertEquals(mapOf("X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to "3"), asked.headers)
            assertEquals(listOf("The Dark Forest", "Nightshift Superstar", "Shimmering Scars"), answer.tracks.map { it.title })
            assertEquals("plex:track:3", answer.tracks.first().id)
            // three of ten
            assertTrue(answer.more)
        }

    @Test
    fun `the folders come from a walk over every track, named from the folder above the library's`() =
        runTest {
            val http = FakePlexHttp(::walking)

            val answer = library(http).answer(PackQuestion(PackQuestion.FOLDERS))

            assertEquals(listOf("music/Muse/Singles", "music/Muse/The Wow! Signal"), answer.playlists.map { it.name })
            assertEquals(listOf("/music/Muse/Singles", "/music/Muse/The Wow! Signal"), answer.playlists.map { it.id })
            // the track with no file is left out
            assertEquals(listOf(1, 3), answer.playlists.map { it.trackCount })
            assertFalse(answer.more)
            assertFalse(answer.failed)
            assertEquals(listOf(SECTIONS, ALL, ALL), http.asked.map { it.path })
            val pages = http.asked.drop(1)
            assertEquals(listOf("0", "2"), pages.map { it.headers["X-Plex-Container-Start"] })
            for (page in pages) {
                assertEquals("500", page.headers["X-Plex-Container-Size"])
                assertEquals("10", page.param("type"))
                assertEquals(ALBUM_ORDER, page.param("sort"))
            }
        }

    @Test
    fun `without the library's folders the folders are named from the one their files share`() =
        runTest {
            val http = FakePlexHttp { ask -> if (ask.path == SECTIONS) PlexReply.Rejected(500) else walking(ask) }

            val answer = library(http).answer(PackQuestion(PackQuestion.FOLDERS))

            assertEquals(listOf("Muse/Singles", "Muse/The Wow! Signal"), answer.playlists.map { it.name })
            assertFalse(answer.failed)
        }

    @Test
    fun `a later page of the folders and a folder's tracks come from the kept walk`() =
        runTest {
            val http = FakePlexHttp(::walking)
            val library = library(http)
            library.answer(PackQuestion(PackQuestion.FOLDERS))
            val walked = http.asked.size

            val later = library.answer(PackQuestion(PackQuestion.FOLDERS, offset = 1, limit = 1))
            val tracks = library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = "/music/Muse/The Wow! Signal", limit = 2))

            assertEquals("nothing more is asked", walked, http.asked.size)
            assertEquals(listOf("music/Muse/The Wow! Signal"), later.playlists.map { it.name })
            assertFalse(later.more)
            assertEquals(listOf("The Dark Forest", "Nightshift Superstar"), tracks.tracks.map { it.title })
            assertEquals(listOf("plex:track:3", "plex:track:4"), tracks.tracks.map { it.id })
            assertTrue("two of three", tracks.more)
        }

    @Test
    fun `a first page of the folders walks again`() =
        runTest {
            val http = FakePlexHttp(::walking)
            val library = library(http)

            library.answer(PackQuestion(PackQuestion.FOLDERS))
            library.answer(PackQuestion(PackQuestion.FOLDERS))

            assertEquals(listOf(SECTIONS, ALL, ALL, SECTIONS, ALL, ALL), http.asked.map { it.path })
        }

    @Test
    fun `two first pages asked together walk once`() =
        runTest {
            val http =
                FakePlexHttp { ask ->
                    delay(10)
                    walking(ask)
                }
            val library = library(http)

            val both = List(2) { async { library.answer(PackQuestion(PackQuestion.FOLDERS)) } }.awaitAll()

            assertEquals(listOf(SECTIONS, ALL, ALL), http.asked.map { it.path })
            assertEquals(both.first(), both.last())
        }

    @Test
    fun `a folder's tracks walk first when nothing is kept`() =
        runTest {
            val http = FakePlexHttp(::walking)

            val answer = library(http).answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = "/music/Muse/Singles"))

            assertEquals(listOf(SECTIONS, ALL, ALL), http.asked.map { it.path })
            assertEquals(listOf("Demo"), answer.tracks.map { it.title })
            assertFalse(answer.more)
            assertFalse(answer.failed)
        }

    @Test
    fun `a folder the walk did not find has no tracks`() =
        runTest {
            val answer = library(FakePlexHttp(::walking)).answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = "/music/Gone"))

            assertEquals(emptyList<Any>(), answer.tracks)
            assertFalse(answer.failed)
        }

    @Test
    fun `a page of the walk that fails fails the answer`() =
        runTest {
            val http =
                FakePlexHttp { ask ->
                    if (ask.headers["X-Plex-Container-Start"] == "2") PlexReply.Rejected(500) else walking(ask)
                }
            val library = library(http)

            val folders = library.answer(PackQuestion(PackQuestion.FOLDERS))
            val tracks = library.answer(PackQuestion(PackQuestion.FOLDER_TRACKS, id = "/music/Muse/Singles"))

            assertTrue(folders.failed)
            assertEquals(emptyList<Any>(), folders.playlists)
            assertTrue("nothing was kept, so it walks again and fails again", tracks.failed)
        }

    private fun library(http: FakePlexHttp): PlexLibrary = PlexLibrary(http, SERVER, sdk = MODERN)

    /** The server during a walk: the libraries, then every track in two pages. */
    private fun walking(ask: FakePlexHttp.Ask): PlexReply =
        when {
            ask.path == SECTIONS -> recorded("sections.json")
            ask.path != ALL -> PlexReply.Rejected(404)
            ask.headers["X-Plex-Container-Start"] == "0" -> recorded("all-tracks-1.json")
            ask.headers["X-Plex-Container-Start"] == "2" -> recorded("all-tracks-2.json")
            else -> PlexReply.Rejected(400)
        }

    /** What the server answers an empty page with: the total and no rows. */
    private fun total(tracks: Int): PlexReply =
        PlexReply.read(200, """{"MediaContainer":{"size":0,"totalSize":$tracks,"offset":0}}""")

    private companion object {
        const val MODERN = 36
        const val SECTIONS = "/library/sections"
        const val ALL = "/library/sections/1/all"
        const val GENRES = "/library/sections/1/genre"
        const val ALBUM_ORDER =
            "artist.titleSort,album.titleSort,album.year,track.absoluteIndex,track.index,track.titleSort,track.id"
    }
}
