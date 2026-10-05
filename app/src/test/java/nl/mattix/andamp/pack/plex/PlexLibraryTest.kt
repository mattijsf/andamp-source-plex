// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.core.model.AlbumKind
import nl.mattix.andamp.core.packapi.PackAnswer
import nl.mattix.andamp.core.packapi.PackQuestion
import nl.mattix.andamp.core.packapi.UNKNOWN_COUNT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every question the browse contract asks, answered from recordings; see [FakePlexHttp]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlexLibraryTest {
    @Test
    fun `the artists are asked from the library a page at a time, in title order`() =
        runTest {
            val http = FakePlexHttp(mapOf(ALL to recorded("artists.json")))

            val answer =
                PlexLibrary(
                    http,
                    SERVER,
                    sdk = MODERN,
                ).answer(PackQuestion(PackQuestion.ARTISTS, offset = 0, limit = 50))

            assertEquals("8", http.once().param("type"))
            assertEquals("titleSort", http.once().param("sort"))
            assertEquals(mapOf("X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to "50"), http.once().headers)
            assertEquals(SERVER_TOKEN, http.once().token)
            assertEquals(listOf("Muse"), answer.artists.map { it.name })
            assertEquals("1", answer.artists.first().id)
            assertEquals(UNKNOWN_COUNT, answer.artists.first().albumCount)
            assertFalse(answer.more)
            assertFalse(answer.failed)
        }

    @Test
    fun `more comes from the server's total and not from a full page`() =
        runTest {
            // three asked for, three answered, ten in total
            val first =
                library(
                    CHILDREN_ALBUM,
                    "tracks.json",
                ).answer(PackQuestion(PackQuestion.TRACKS, id = "2", offset = 0, limit = 3))
            // one past the start: nothing answered, one in total
            val later = library(ALL, "artists-page.json").answer(PackQuestion(PackQuestion.ARTISTS, offset = 1, limit = 2))

            assertEquals(3, first.tracks.size)
            assertTrue(first.more)
            assertFalse(later.more)
        }

    @Test
    fun `the catalog is every album in title order, with its artist and its year`() =
        runTest {
            val http = FakePlexHttp(mapOf(ALL to recorded("albums.json")))

            val answer =
                PlexLibrary(
                    http,
                    SERVER,
                    sdk = MODERN,
                ).answer(PackQuestion(PackQuestion.ALBUMS, offset = 20, limit = 10))

            assertEquals("9", http.once().param("type"))
            assertEquals("titleSort", http.once().param("sort"))
            assertEquals("20", http.once().headers["X-Plex-Container-Start"])
            val album = answer.albums.single()
            assertEquals("2", album.id)
            assertEquals("The Wow! Signal", album.title)
            assertEquals("Muse", album.artist)
            assertEquals(2026, album.year)
            // the listing does not count an album's tracks
            assertEquals(UNKNOWN_COUNT, album.trackCount)
            assertEquals(AlbumKind.ALBUM.name, album.kind)
        }

    @Test
    fun `a named artist's records are its children, oldest first`() =
        runTest {
            val http = FakePlexHttp(mapOf("/library/metadata/1/children" to recorded("albums-by-artist.json")))

            val answer = PlexLibrary(http, SERVER, sdk = MODERN).answer(PackQuestion(PackQuestion.ALBUMS, id = "1"))

            assertEquals("year,titleSort", http.once().param("sort"))
            assertEquals(listOf("The Wow! Signal"), answer.albums.map { it.title })
        }

    @Test
    fun `an album's tracks are its children in the server's order, with their media`() =
        runTest {
            val http = FakePlexHttp(mapOf(CHILDREN_ALBUM to recorded("tracks.json")))

            val answer = PlexLibrary(http, SERVER, sdk = MODERN).answer(PackQuestion(PackQuestion.TRACKS, id = "2"))

            assertEquals(
                mapOf("X-Plex-Container-Start" to "0", "X-Plex-Container-Size" to PackQuestion.PAGE.toString()),
                http.once().headers,
            )
            assertEquals(listOf("The Dark Forest", "Nightshift Superstar", "Shimmering Scars"), answer.tracks.map { it.title })
            assertEquals("plex:track:3", answer.tracks.first().id)
            assertEquals("/library/parts/1/1788252574/file.mp3", answer.tracks.first().defaultName)
        }

    @Test
    fun `the playlists are the audio ones, with their lengths`() =
        runTest {
            val http = FakePlexHttp(mapOf("/playlists" to recorded("playlists.json")))

            val answer = PlexLibrary(http, SERVER, sdk = MODERN).answer(PackQuestion(PackQuestion.PLAYLISTS))

            assertEquals("audio", http.once().param("playlistType"))
            assertEquals(listOf("Driving", "Empty"), answer.playlists.map { it.name })
            assertEquals("41", answer.playlists.first().id)
            assertEquals(3, answer.playlists.first().trackCount)
            assertEquals(0, answer.playlists.last().trackCount)
            assertFalse(answer.more)
        }

    @Test
    fun `a playlist's tracks come from the playlist, in its order, and a film on it is not a track`() =
        runTest {
            val http = FakePlexHttp(mapOf("/playlists/41/items" to recorded("playlist-items.json")))

            val answer =
                PlexLibrary(
                    http,
                    SERVER,
                    sdk = MODERN,
                ).answer(PackQuestion(PackQuestion.PLAYLIST_TRACKS, id = "41", limit = 4))

            assertEquals(listOf("The Dark Forest", "Nightshift Superstar", "Shimmering Scars"), answer.tracks.map { it.title })
            // four entries on the page and a total of four
            assertFalse(answer.more)
            assertFalse(answer.failed)
        }

    @Test
    fun `a search asks the library's search for tracks and pages by the server's total`() =
        runTest {
            val http = FakePlexHttp(mapOf(SEARCH to recorded("search-tracks.json")))

            val answer =
                PlexLibrary(
                    http,
                    SERVER,
                    sdk = MODERN,
                ).answer(PackQuestion(PackQuestion.SEARCH, query = " s ", offset = 0, limit = 2))

            assertEquals("s", http.once().param("query"))
            assertEquals("10", http.once().param("type"))
            assertEquals("2", http.once().headers["X-Plex-Container-Size"])
            assertEquals(listOf("The Dark Forest", "Nightshift Superstar"), answer.tracks.map { it.title })
            // two of seven
            assertTrue(answer.more)
        }

    @Test
    fun `a search for artists asks the same search for artists`() =
        runTest {
            val http = FakePlexHttp(mapOf(SEARCH to recorded("search-artists.json")))

            val answer =
                PlexLibrary(
                    http,
                    SERVER,
                    sdk = MODERN,
                ).answer(PackQuestion(PackQuestion.FIND_ARTISTS, query = "u", limit = 2))

            assertEquals("8", http.once().param("type"))
            assertEquals("u", http.once().param("query"))
            assertEquals(listOf("Muse"), answer.artists.map { it.name })
            assertFalse(answer.more)
        }

    @Test
    fun `an empty search asks the server nothing`() =
        runTest {
            val http = FakePlexHttp(mapOf(SEARCH to recorded("search-tracks.json")))

            val tracks = PlexLibrary(http, SERVER, sdk = MODERN).answer(PackQuestion(PackQuestion.SEARCH, query = "   "))
            val artists = PlexLibrary(http, SERVER, sdk = MODERN).answer(PackQuestion(PackQuestion.FIND_ARTISTS, query = ""))

            assertEquals(emptyList<Any>(), http.asked)
            assertEquals(PackAnswer(), tracks)
            assertEquals(PackAnswer(), artists)
        }

    @Test
    fun `a limit of nought is asked as the contract's page, and a negative offset as nought`() =
        runTest {
            val http = FakePlexHttp(mapOf(ALL to recorded("artists.json")))

            PlexLibrary(http, SERVER, sdk = MODERN).answer(PackQuestion(PackQuestion.ARTISTS, offset = -3, limit = 0))

            assertEquals(PackQuestion.PAGE.toString(), http.once().headers["X-Plex-Container-Size"])
            assertEquals("0", http.once().headers["X-Plex-Container-Start"])
        }

    @Test
    fun `a revoked token is a failure, and signs the listener out`() =
        runTest {
            var signedOut = 0
            val http = FakePlexHttp(mapOf(ALL to PlexReply.read(401, fixture("unauthorized.html"))))

            val answer =
                PlexLibrary(
                    http,
                    SERVER,
                    signedOut = { signedOut++ },
                    sdk = MODERN,
                ).answer(PackQuestion(PackQuestion.ARTISTS))

            assertTrue(answer.failed)
            assertEquals(emptyList<Any>(), answer.artists)
            assertEquals(1, signedOut)
        }

    @Test
    fun `a status that is not success is a failure and does not sign anybody out`() =
        runTest {
            var signedOut = 0
            val http = FakePlexHttp(mapOf(CHILDREN_ALBUM to PlexReply.Rejected(500)))

            val answer =
                PlexLibrary(http, SERVER, signedOut = {
                    signedOut++
                }, sdk = MODERN).answer(PackQuestion(PackQuestion.TRACKS, id = "2"))

            assertTrue(answer.failed)
            assertEquals(0, signedOut)
        }

    @Test
    fun `a server that cannot be reached anywhere is a failure and never an empty shelf`() =
        runTest {
            val http = FakePlexHttp(mapOf("/playlists" to PlexReply.Unreachable("no route to host")))

            val answer = PlexLibrary(http, SERVER, sdk = MODERN).answer(PackQuestion(PackQuestion.PLAYLISTS))

            assertTrue(answer.failed)
            assertEquals(emptyList<Any>(), answer.playlists)
            assertFalse(answer.more)
            assertEquals("asked once, because nobody moved it", 1, http.asked.size)
        }

    @Test
    fun `a server that cannot be reached is asked again where it moved to`() =
        runTest {
            val elsewhere = SERVER.at("https://203.0.113.7:13936")
            val http =
                FakePlexHttp { ask ->
                    if (ask.host == "203.0.113.7") recorded("artists.json") else PlexReply.Unreachable("no route to host")
                }
            val library = PlexLibrary(http, SERVER, moved = { elsewhere }, sdk = MODERN)

            val answer = library.answer(PackQuestion(PackQuestion.ARTISTS))

            assertFalse(answer.failed)
            assertEquals(listOf("Muse"), answer.artists.map { it.name })
            assertEquals(listOf("192.0.2.10", "203.0.113.7"), http.asked.map { it.host })
            assertEquals("the next question goes to the new address at once", elsewhere.address, library.server.address)
        }

    @Test
    fun `a server that cannot be reached anywhere it moved to is a failure after one more try`() =
        runTest {
            val http = FakePlexHttp { PlexReply.Unreachable("timeout") }

            val answer =
                PlexLibrary(http, SERVER, moved = {
                    SERVER.at("https://203.0.113.7:13936")
                }, sdk = MODERN).answer(PackQuestion(PackQuestion.ARTISTS))

            assertTrue(answer.failed)
            assertEquals(2, http.asked.size)
        }

    @Test
    fun `an answer that is not a container is a failure`() =
        runTest {
            val http = FakePlexHttp(mapOf(ALL to PlexReply.read(200, "{\"not\":\"a container\"}")))

            assertTrue(PlexLibrary(http, SERVER, sdk = MODERN).answer(PackQuestion(PackQuestion.ALBUMS)).failed)
        }

    @Test
    fun `a question this source has never heard of fails rather than answering nothing`() =
        runTest {
            assertTrue(library(ALL, "albums.json").answer(PackQuestion("sonnets")).failed)
        }

    @Test
    fun `what the library can be asked is what the descriptor says`() {
        assertTrue(PlexLibrary.SHELVES.hasArtists)
        assertTrue(PlexLibrary.SHELVES.hasAlbums)
        assertTrue(PlexLibrary.SHELVES.canSearch)
        assertTrue(PlexLibrary.SHELVES.hasPlaylists)
        assertFalse(PlexLibrary.SHELVES.hasCatalogue)
    }

    private fun library(
        path: String,
        recording: String,
    ): PlexLibrary = PlexLibrary(FakePlexHttp(mapOf(path to recorded(recording))), SERVER, sdk = MODERN)

    private companion object {
        const val MODERN = 36
        const val ALL = "/library/sections/1/all"
        const val SEARCH = "/library/sections/1/search"
        const val CHILDREN_ALBUM = "/library/metadata/2/children"
    }
}
