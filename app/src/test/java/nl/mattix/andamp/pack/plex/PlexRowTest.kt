// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [PlexRow] on recorded items: the tracks of one album, in mp3, FLAC and ALAC, and a playlist with a film on it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlexRowTest {
    @Test
    fun `a track is who and what the server says, with its length and rate`() {
        val track = checkNotNull(PlexRow.track(firstItem("tracks.json"), SERVER, MODERN))

        assertEquals("plex:track:3", track.id)
        assertEquals(track.id, track.uri)
        assertEquals("The Dark Forest", track.title)
        assertEquals("Muse", track.artist)
        assertEquals(315_794L, track.durationMs)
        assertEquals(279, track.bitrateKbps)
        // the listing does not say
        assertEquals(0, track.sampleRateKhz)
        assertFalse(track.isStream)
    }

    @Test
    fun `a track's own artist comes before the album's`() {
        val credited = JSONObject().put("originalTitle", "Muse feat. Somebody").put("grandparentTitle", "Muse")
        val plain = JSONObject().put("grandparentTitle", "Muse")

        assertEquals("Muse feat. Somebody", PlexRow.artist(credited))
        assertEquals("Muse", PlexRow.artist(plain))
    }

    @Test
    fun `a file this phone plays as it is carries its part key, so it can be sought in`() {
        val mp3 = checkNotNull(PlexRow.track(items("tracks-mixed.json").getJSONObject(0), SERVER, OREO))
        val flac = checkNotNull(PlexRow.track(items("tracks-mixed.json").getJSONObject(1), SERVER, MODERN))

        assertEquals("/library/parts/1/1788252574/file.mp3", mp3.defaultName)
        assertEquals("/library/parts/102/1788252577/file.flac", flac.defaultName)
    }

    @Test
    fun `a file the server has to transcode carries its name instead`() {
        // ALAC in an mp4, on any phone
        val alac = checkNotNull(PlexRow.track(items("tracks-mixed.json").getJSONObject(2), SERVER, MODERN))
        // FLAC below API 27
        val flac = checkNotNull(PlexRow.track(items("tracks-mixed.json").getJSONObject(1), SERVER, OREO))

        assertEquals("03 - Shimmering Scars.m4a", alac.defaultName)
        assertEquals("02 - Nightshift Superstar.flac", flac.defaultName)
    }

    @Test
    fun `a track with no media listed has no location, and the rest still reads`() {
        val bare = firstItem("tracks.json")
        bare.remove("Media")

        val track = checkNotNull(PlexRow.track(bare, SERVER, MODERN))

        assertNull(track.defaultName)
        assertEquals(0, track.bitrateKbps)
        assertEquals("The Dark Forest", track.title)
    }

    @Test
    fun `the cover is the track's own picture, with the token on it`() {
        val art = checkNotNull(PlexRow.track(firstItem("tracks.json"), SERVER, MODERN)?.artworkUri).toHttpUrl()

        assertEquals("/library/metadata/2/thumb/1791279566", art.encodedPath)
        assertEquals(SERVER_TOKEN, art.queryParameter("X-Plex-Token"))
        assertEquals("192.0.2.10", art.host)
    }

    @Test
    fun `a track with no picture of its own shows its album's`() {
        val art = checkNotNull(PlexRow.track(items("tracks-mixed.json").getJSONObject(1), SERVER, MODERN)?.artworkUri).toHttpUrl()

        assertEquals("/library/metadata/2/thumb/1791279566", art.encodedPath)
    }

    @Test
    fun `no picture anywhere is no artwork, not a URL that will 401`() {
        assertNull(PlexRow.track(firstItem("tracks-no-art.json"), SERVER, MODERN)?.artworkUri)
    }

    @Test
    fun `an address goes out and comes back as the same id`() {
        assertEquals("3", PlexRow.trackId(PlexRow.address("3")))
        assertEquals("plex:track:3", PlexRow.address("3"))
    }

    @Test
    fun `an address that is not this source's has no id in it`() {
        assertNull(PlexRow.trackId("jellyfin:track:3"))
        assertNull(PlexRow.trackId("3"))
        assertNull(PlexRow.trackId("plex:track:"))
    }

    @Test
    fun `a film on a playlist is not a track, and an item with no key is dropped`() {
        val rows = PlexRow.tracks(items("playlist-items.json"), SERVER, MODERN)
        assertEquals(listOf("The Dark Forest", "Nightshift Superstar", "Shimmering Scars"), rows.map { it.title })

        val tracks = items("tracks.json")
        tracks.getJSONObject(0).remove("ratingKey")
        assertEquals(listOf("Nightshift Superstar", "Shimmering Scars"), PlexRow.tracks(tracks, SERVER, MODERN).map { it.title })
        assertEquals(emptyList<Any>(), PlexRow.tracks(null, SERVER, MODERN))
    }

    private companion object {
        const val OREO = 26
        const val MODERN = 36
    }
}
