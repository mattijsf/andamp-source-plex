// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex

import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [PlexSignIn] against recorded answers from plex.tv: a code, its state, the account and its servers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlexSignInTest {
    private val pin = JSONObject(fixture("pins-new.json"))

    @Test
    fun `a code is started with a POST and is four characters to type`() =
        runTest {
            val http = FakePlexHttp(mapOf("/api/v2/pins" to PlexReply.read(201, fixture("pins-new.json"))))

            val started = checkNotNull(PlexSignIn(http).startPin())

            assertEquals("POST", http.once().method)
            assertEquals("plex.tv", http.once().host)
            assertEquals("false", http.once().param("strong"))
            assertEquals("", http.once().token)
            assertEquals(pin.getLong("id"), started.id)
            assertEquals(pin.getString("code"), started.code)
            assertEquals(4, started.code.length)
        }

    @Test
    fun `plex tv that is down starts no code`() =
        runTest {
            assertNull(PlexSignIn(FakePlexHttp(mapOf())).startPin())
            assertNull(PlexSignIn(FakePlexHttp(mapOf("/api/v2/pins" to PlexReply.Rejected(422)))).startPin())
        }

    @Test
    fun `a code is waiting until somebody enters it, then approved with the account's token`() =
        runTest {
            val path = "/api/v2/pins/${pin.getLong("id")}"
            val waiting = FakePlexHttp(mapOf(path to recorded("pins-waiting.json")))
            val approved = FakePlexHttp(mapOf(path to recorded("pins-approved.json")))
            val asked = PlexPin(pin.getLong("id"), pin.getString("code"))

            assertEquals(PinState.Waiting, PlexSignIn(waiting).pinState(asked))
            assertEquals("GET", waiting.once().method)
            val state = PlexSignIn(approved).pinState(asked)
            assertEquals(RECORDED_TOKEN, (state as PinState.Approved).token)
            // toString leaves the token out
            assertFalse(state.toString().contains(RECORDED_TOKEN))
        }

    @Test
    fun `a code plex tv no longer knows has expired, and plex tv that is down has not`() =
        runTest {
            val path = "/api/v2/pins/1"
            val gone = FakePlexHttp(mapOf(path to PlexReply.Rejected(404)))
            val down = FakePlexHttp(mapOf(path to PlexReply.Unreachable("timeout")))

            assertEquals(PinState.Expired, PlexSignIn(gone).pinState(PlexPin(1, "ABCD")))
            assertEquals(PinState.Failed, PlexSignIn(down).pinState(PlexPin(1, "ABCD")))
        }

    @Test
    fun `the account behind a token is named`() =
        runTest {
            val http = FakePlexHttp(mapOf("/api/v2/user" to recorded("user.json")))

            assertEquals("listener", PlexSignIn(http).user(RECORDED_TOKEN))
            assertEquals(RECORDED_TOKEN, http.once().token)
            assertNull(PlexSignIn(FakePlexHttp(mapOf())).user(RECORDED_TOKEN))
        }

    @Test
    fun `the account's servers are the resources that provide one, with their connections and their own token`() =
        runTest {
            val http = FakePlexHttp(mapOf("/api/v2/resources" to recorded("resources.json")))

            val servers = checkNotNull(PlexSignIn(http).servers(RECORDED_TOKEN))

            assertEquals("1", http.once().param("includeHttps"))
            assertEquals("1", http.once().param("includeRelay"))
            // the phone itself is listed too, and is not a server
            assertEquals(listOf("Living room"), servers.map { it.name })
            val living = servers.single()
            assertEquals(MACHINE, living.id)
            assertEquals(SERVER_TOKEN, living.accessToken)
            assertTrue(living.owned)
            assertEquals(4, living.connections.size)
            assertEquals(listOf(true, true, false, false), living.connections.map { it.local })
            assertEquals(listOf(false, false, false, true), living.connections.map { it.relay })
            assertFalse(living.toString().contains(SERVER_TOKEN))
        }

    @Test
    fun `a shared server comes with the token that opens it`() =
        runTest {
            val http = FakePlexHttp(mapOf("/api/v2/resources" to recorded("resources-two.json")))

            val servers = checkNotNull(PlexSignIn(http).servers(RECORDED_TOKEN))

            assertEquals(listOf("Living room", "Office"), servers.map { it.name })
            assertEquals("SHARED-TOKEN-PLACEHOLDER", servers.last().accessToken)
            assertFalse(servers.last().owned)
        }

    @Test
    fun `plex tv that answers with no list has listed no servers`() =
        runTest {
            assertNull(PlexSignIn(FakePlexHttp(mapOf())).servers(RECORDED_TOKEN))
            assertNull(PlexSignIn(FakePlexHttp(mapOf("/api/v2/resources" to recorded("user.json")))).servers(RECORDED_TOKEN))
        }

    @Test
    fun `signing out ends the session on plex tv`() =
        runTest {
            val http = FakePlexHttp(mapOf("/api/v2/users/signout" to PlexReply.read(204, "")))
            val down = FakePlexHttp(mapOf("/api/v2/users/signout" to PlexReply.Unreachable("timeout")))

            assertTrue(PlexSignIn(http).signOut(RECORDED_TOKEN))
            assertEquals("DELETE", http.once().method)
            assertEquals(RECORDED_TOKEN, http.once().token)
            assertFalse(PlexSignIn(down).signOut(RECORDED_TOKEN))
        }
}
