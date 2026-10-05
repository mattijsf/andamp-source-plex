// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [PlexProbe]: a server's identity, name and libraries, and which of its connections to use. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlexProbeTest {
    @Test
    fun `the identity is asked without a token and read off the container`() =
        runTest {
            val http = FakePlexHttp(mapOf("/identity" to recorded("identity.json")))

            assertEquals(Probed.Found(MACHINE), PlexProbe.identity(http, SERVER.base))
            assertEquals("", http.once().token)
        }

    @Test
    fun `an answer that is not a server's is rejected as such`() =
        runTest {
            assertEquals(
                Probed.Rejected(200),
                PlexProbe.identity(FakePlexHttp(mapOf("/identity" to PlexReply.read(200, "{}"))), SERVER.base),
            )
            assertEquals(
                Probed.Rejected(404),
                PlexProbe.identity(FakePlexHttp(mapOf("/identity" to PlexReply.Rejected(404))), SERVER.base),
            )
            assertEquals(
                Probed.Unreachable("x"),
                PlexProbe.identity(FakePlexHttp(mapOf("/identity" to PlexReply.Unreachable("x"))), SERVER.base),
            )
            assertEquals(
                Probed.Unauthorized,
                PlexProbe.identity(FakePlexHttp(mapOf("/identity" to PlexReply.Unauthorized)), SERVER.base),
            )
        }

    @Test
    fun `the name is on the root, and null when the server did not say`() =
        runTest {
            assertEquals(
                "Living room",
                PlexProbe.name(FakePlexHttp(mapOf("/" to recorded("root.json"))), SERVER.base, SERVER_TOKEN),
            )
            assertNull(PlexProbe.name(FakePlexHttp(mapOf()), SERVER.base, SERVER_TOKEN))
        }

    @Test
    fun `the music libraries are the sections of type artist`() =
        runTest {
            val one =
                PlexProbe.sections(
                    FakePlexHttp(mapOf("/library/sections" to recorded("sections.json"))),
                    SERVER.base,
                    SERVER_TOKEN,
                )
            val two =
                PlexProbe.sections(
                    FakePlexHttp(mapOf("/library/sections" to recorded("sections-two-music.json"))),
                    SERVER.base,
                    SERVER_TOKEN,
                )

            assertEquals(Probed.Found(listOf(PlexSection("1", "Music"))), one)
            assertEquals(Probed.Found(listOf(PlexSection("1", "Music"), PlexSection("3", "Audiobooks"))), two)
        }

    @Test
    fun `a server with no music has an empty list, and one that refuses the token says so`() =
        runTest {
            val none = PlexReply.read(200, "{\"MediaContainer\":{\"size\":0}}")

            assertEquals(
                Probed.Found(emptyList<PlexSection>()),
                PlexProbe.sections(FakePlexHttp(mapOf("/library/sections" to none)), SERVER.base, "t"),
            )
            assertEquals(
                Probed.Unauthorized,
                PlexProbe.sections(FakePlexHttp(mapOf("/library/sections" to PlexReply.Unauthorized)), SERVER.base, "t"),
            )
        }

    @Test
    fun `a local connection that answers with the right identity wins, and the rest are not waited for`() =
        runTest {
            val http = FakePlexHttp(mapOf("/identity" to recorded("identity.json")))

            val reached = PlexProbe.reach(http, CONNECTIONS, MACHINE, timeoutMs = 1_000)

            assertEquals(LOCAL_HTTPS, reached)
        }

    @Test
    fun `a connection that answers as another server does not count`() =
        runTest {
            val other = PlexReply.read(200, "{\"MediaContainer\":{\"machineIdentifier\":\"somebody-else\"}}")
            val http =
                FakePlexHttp { ask ->
                    if (ask.host.startsWith("192-0-2-10") || ask.host == "192.0.2.10") other else recorded("identity.json")
                }

            val reached = PlexProbe.reach(http, CONNECTIONS, MACHINE, timeoutMs = 1_000)

            assertEquals("the remote connection, which is the best that answered as this server", REMOTE, reached)
        }

    @Test
    fun `a connection that hangs is given up on, and the relay is last`() =
        runTest {
            val http =
                FakePlexHttp { ask ->
                    when {
                        ask.host.contains("plex.direct") && !ask.host.startsWith("0123") -> {
                            delay(10_000)
                            recorded("identity.json")
                        }

                        ask.host == "192.0.2.10" -> {
                            PlexReply.Unreachable("refused")
                        }

                        else -> {
                            recorded("identity.json")
                        }
                    }
                }

            val reached = PlexProbe.reach(http, CONNECTIONS, MACHINE, timeoutMs = 500)

            assertEquals(RELAY, reached)
        }

    @Test
    fun `when nothing answers there is no connection`() =
        runTest {
            assertNull(PlexProbe.reach(FakePlexHttp(mapOf()), CONNECTIONS, MACHINE, timeoutMs = 100))
            assertNull(PlexProbe.reach(FakePlexHttp(mapOf("/identity" to recorded("identity.json"))), emptyList(), MACHINE))
            assertNull(PlexProbe.reach(FakePlexHttp(mapOf("/identity" to recorded("identity.json"))), CONNECTIONS, ""))
        }

    @Test
    fun `a local https connection gets a plain http twin at its address, once`() {
        val twinned = PlexProbe.candidates(listOf(LOCAL_HTTPS))
        val already = PlexProbe.candidates(CONNECTIONS)

        assertEquals(listOf(LOCAL_HTTPS.uri, "http://192.0.2.10:32400"), twinned.map { it.uri })
        assertTrue(twinned.last().local)
        assertEquals(CONNECTIONS.map { it.uri }, already.map { it.uri })
        // a remote one and the relay get no twin, nor does one with no address
        assertEquals(listOf(REMOTE.uri, RELAY.uri), PlexProbe.candidates(listOf(REMOTE, RELAY)).map { it.uri })
        assertEquals(
            emptyList<PlexConnection>(),
            PlexProbe.candidates(listOf(PlexConnection("not a url", local = true, relay = false))),
        )
    }

    @Test
    fun `local beats remote beats relay`() {
        assertEquals(listOf(0, 1, 2), listOf(LOCAL_HTTPS, REMOTE, RELAY).map(PlexProbe::rank))
    }

    private companion object {
        val LOCAL_HTTPS =
            PlexConnection(
                "https://192-0-2-10.0123456789abcdef0123456789abcdef.plex.direct:32400",
                true,
                false,
                "192.0.2.10",
                32400,
            )
        val LOCAL_HTTP = PlexConnection("http://192.0.2.10:32400", true, false, "192.0.2.10", 32400)
        val REMOTE =
            PlexConnection(
                "https://203-0-113-7.0123456789abcdef0123456789abcdef.plex.direct:13936",
                false,
                false,
                "203.0.113.7",
                13936,
            )
        val RELAY =
            PlexConnection("https://0123456789abcdef0123456789abcdef.plex.direct:13936", false, true, "203.0.113.7", 13936)
        val CONNECTIONS = listOf(LOCAL_HTTPS, LOCAL_HTTP, REMOTE, RELAY)
    }
}
