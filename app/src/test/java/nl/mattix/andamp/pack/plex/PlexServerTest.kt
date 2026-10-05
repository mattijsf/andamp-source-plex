// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import okhttp3.HttpUrl
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The headers, the URLs [PlexServer] builds, and the connections the store keeps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlexServerTest {
    @Test
    fun `before sign-in the headers name the device and nothing else`() {
        val server = PlexServer("http://192.0.2.10:32400", DEVICE)

        assertNull(server.headers()["X-Plex-Token"])
        assertEquals("7d3f2a9e-andamp-test", server.headers()["X-Plex-Client-Identifier"])
        assertFalse(server.signedIn)
    }

    @Test
    fun `after sign-in the token is one more header`() {
        assertEquals(SERVER_TOKEN, SERVER.headers()["X-Plex-Token"])
        assertTrue(SERVER.signedIn)
    }

    @Test
    fun `no URL the server builds carries the token unless it is asked for`() {
        val url = checkNotNull(SERVER.url(Plex.sectionAll(MUSIC), mapOf("type" to "10")))

        assertFalse(url.toString(), url.toString().contains(SERVER_TOKEN))
        assertNull(url.queryParameter("X-Plex-Token"))
    }

    @Test
    fun `the server never prints its token`() {
        val printed = SERVER.toString()

        assertFalse(printed, printed.contains(SERVER_TOKEN))
        assertTrue(printed, printed.contains(MACHINE))
        assertTrue(PlexServer("x", DEVICE).toString().contains("token=none"))
    }

    @Test
    fun `what somebody types becomes an address that works`() {
        // a missing scheme is read as http, which is what a server on the listener's own network answers
        assertEquals("http://192.168.1.20:32400/identity", where(PlexServer("  192.168.1.20:32400/ ", DEVICE).url(Plex.IDENTITY)))
        assertEquals("https://music.example:443/identity", where(PlexServer("https://music.example", DEVICE).url(Plex.IDENTITY)))
        // the root is the address itself
        assertEquals("http://192.168.1.20:32400/", where(PlexServer("http://192.168.1.20:32400", DEVICE).url(Plex.ROOT)))
    }

    @Test
    fun `an address that is not one has no URL rather than an exception`() {
        assertNull(PlexServer("", DEVICE).url(Plex.SECTIONS))
        assertNull(PlexServer("   ", DEVICE).url(Plex.SECTIONS))
        assertNull(PlexServer("gopher://nope", DEVICE).url(Plex.SECTIONS))
    }

    @Test
    fun `a segment of a path and a query value are escaped`() {
        val url = checkNotNull(SERVER.url(Plex.children("a b"), mapOf("query" to "AC/DC & 100%")))

        assertEquals("AC/DC & 100%", url.queryParameter("query"))
        assertEquals("/library/metadata/a%20b/children", url.encodedPath)
        // the transcoder's path has a colon in it
        assertEquals("/music/:/transcode/universal/start.mp3", checkNotNull(SERVER.url(Plex.TRANSCODE)).encodedPath)
    }

    @Test
    fun `a plex tv URL is under plex tv`() {
        val url = Plex.tv(Plex.pin(1234), mapOf("strong" to "false"))

        assertEquals("https://plex.tv:443/api/v2/pins/1234", where(url))
        assertEquals("false", url.queryParameter("strong"))
    }

    @Test
    fun `the same server at another address keeps its sign-in`() {
        val moved = SERVER.at("https://203.0.113.7:13936")

        assertEquals("https://203.0.113.7:13936", moved.address)
        assertEquals(SERVER_TOKEN, moved.token)
        assertEquals(MUSIC, moved.sectionKey)
        assertEquals(MACHINE, moved.machineId)
    }

    @Test
    fun `connections go into the store as JSON and come back the same`() {
        val listed = PlexConnection.list(JSONArray(fixture("resources.json")).getJSONObject(0).getJSONArray("connections"))

        assertEquals(4, listed.size)
        assertEquals(listed, PlexConnection.stored(PlexConnection.json(listed)))
        assertEquals(emptyList<PlexConnection>(), PlexConnection.stored(""))
        assertEquals(emptyList<PlexConnection>(), PlexConnection.stored("not json"))
        val local = listed.first()
        assertTrue(local.local)
        assertFalse(local.relay)
        assertEquals("192.0.2.10", local.address)
        assertEquals(32400, local.port)
    }

    private fun where(url: HttpUrl?): String = checkNotNull(url).let { "${it.scheme}://${it.host}:${it.port}${it.encodedPath}" }
}
