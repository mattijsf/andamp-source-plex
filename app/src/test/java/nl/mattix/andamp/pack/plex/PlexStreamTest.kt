// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import nl.mattix.andamp.core.model.Track
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The requests [PlexStream] and [PlexPlayback] build. */
class PlexStreamTest {
    @Test
    fun `a row with a part key is asked for as the file itself, and can be sought in`() {
        val request = checkNotNull(PlexStream.request(SERVER, "plex:track:3", PART, positionMs = 61_500, session = "s1"))
        val url = request.url.toHttpUrl()

        assertEquals("/library/parts/1/1788252574/file.mp3", url.encodedPath)
        assertNull(url.queryParameter("offset"))
        assertTrue(request.seekable)
        assertEquals(SERVER.headers(), request.headers)
    }

    @Test
    fun `a row without a part key goes through the transcoder, from where it was asked for`() {
        val request =
            checkNotNull(
                PlexStream.request(SERVER, "plex:track:5", "03 - Shimmering Scars.m4a", positionMs = 61_500, session = "s1"),
            )
        val url = request.url.toHttpUrl()

        assertEquals("/music/:/transcode/universal/start.mp3", url.encodedPath)
        assertEquals("/library/metadata/5", url.queryParameter("path"))
        assertEquals("0", url.queryParameter("mediaIndex"))
        assertEquals("0", url.queryParameter("partIndex"))
        assertEquals("http", url.queryParameter("protocol"))
        assertEquals("0", url.queryParameter("directPlay"))
        assertEquals("0", url.queryParameter("directStream"))
        assertEquals("mp3", url.queryParameter("audioCodec"))
        // seconds
        assertEquals("61", url.queryParameter("offset"))
        assertEquals("s1", url.queryParameter("session"))
        assertFalse(request.seekable)
    }

    @Test
    fun `a transcode from the top carries no offset, and a row with no location transcodes too`() {
        val top = checkNotNull(PlexStream.request(SERVER, "plex:track:5", "a.m4a"))
        val lost = checkNotNull(PlexStream.request(SERVER, "plex:track:5", null))

        assertNull(top.url.toHttpUrl().queryParameter("offset"))
        assertNull(top.url.toHttpUrl().queryParameter("session"))
        assertEquals("/music/:/transcode/universal/start.mp3", lost.url.toHttpUrl().encodedPath)
    }

    @Test
    fun `the token travels in the header and never in the URL`() {
        for (located in listOf(PART, "a.m4a")) {
            val request = checkNotNull(PlexStream.request(SERVER, "plex:track:3", located))
            assertFalse(request.url, request.url.contains(SERVER_TOKEN))
            assertEquals(SERVER_TOKEN, request.headers["X-Plex-Token"])
            assertFalse(request.toString(), request.toString().contains(SERVER_TOKEN))
        }
    }

    @Test
    fun `what this phone plays as it is, by container and codec`() {
        assertTrue(PlexStream.playsDirectly("mp3", "mp3", OREO))
        assertTrue(PlexStream.playsDirectly("mp4", "aac", OREO))
        assertTrue(PlexStream.playsDirectly("m4a", "aac", OREO))
        assertTrue(PlexStream.playsDirectly("ogg", "vorbis", OREO))
        assertTrue(PlexStream.playsDirectly("ogg", "opus", OREO))
        assertTrue(PlexStream.playsDirectly("wav", "pcm_s16le", OREO))
        assertTrue(PlexStream.playsDirectly("MP3", "MP3", OREO))
        // an m4a that holds ALAC
        assertFalse(PlexStream.playsDirectly("mp4", "alac", MODERN))
        assertFalse(PlexStream.playsDirectly("asf", "wmav2", MODERN))
        assertFalse(PlexStream.playsDirectly("", "mp3", MODERN))
        assertFalse(PlexStream.playsDirectly("mp3", "", MODERN))
    }

    @Test
    fun `FLAC plays as it is from API 27 and is transcoded below it`() {
        assertFalse(PlexStream.playsDirectly("flac", "flac", OREO))
        assertTrue(PlexStream.playsDirectly("flac", "flac", OREO_MR1))
        assertTrue(PlexStream.playsDirectly("flac", "flac", MODERN))
        // FLAC in an ogg
        assertFalse(PlexStream.playsDirectly("ogg", "flac", MODERN))
    }

    @Test
    fun `a row is handed to the shared playback with its location, position and session`() {
        val row = Track("abc", "Muse", "The Dark Forest", 0, uri = "plex:track:3", defaultName = "a.m4a")

        val request = checkNotNull(PlexPlayback.request(SERVER, row, positionMs = 2_000, session = "s2"))

        assertEquals("2", request.url.toHttpUrl().queryParameter("offset"))
        assertEquals("s2", request.url.toHttpUrl().queryParameter("session"))
        assertNull(PlexPlayback.request(null, row))
        assertNull(PlexPlayback.request(SERVER, row.copy(uri = null)))
        assertNull(PlexPlayback.request(SERVER, row.copy(uri = "jellyfin:track:abc")))
    }

    @Test
    fun `an address from another source never reaches this listener's server`() {
        assertNull(PlexStream.request(SERVER, "jellyfin:track:3", PART))
        assertNull(PlexStream.request(SERVER, "3", PART))
    }

    @Test
    fun `nobody signed in, or no address, has nothing to stream`() {
        assertNull(PlexStream.request(PlexServer(SERVER.address, DEVICE), "plex:track:3", PART))
        assertNull(PlexStream.request(PlexServer("", DEVICE, SERVER_TOKEN), "plex:track:3", PART))
    }

    private companion object {
        const val PART = "/library/parts/1/1788252574/file.mp3"
        const val OREO = 26
        const val OREO_MR1 = 27
        const val MODERN = 36
    }
}
