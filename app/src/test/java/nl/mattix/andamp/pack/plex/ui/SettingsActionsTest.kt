// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import nl.mattix.andamp.pack.plex.FakePlexHttp
import nl.mattix.andamp.pack.plex.MACHINE
import nl.mattix.andamp.pack.plex.PinState
import nl.mattix.andamp.pack.plex.PlexConnection
import nl.mattix.andamp.pack.plex.PlexPin
import nl.mattix.andamp.pack.plex.PlexReply
import nl.mattix.andamp.pack.plex.RECORDED_TOKEN
import nl.mattix.andamp.pack.plex.SERVER_TOKEN
import nl.mattix.andamp.pack.plex.fixture
import nl.mattix.andamp.pack.plex.pack.PackStore
import nl.mattix.andamp.pack.plex.recorded
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SettingsActions] over the real store and recorded answers: what is written,
 * when the announce fires, and what is sent to plex.tv and the server.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsActionsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var store: PackStore
    private var announced = 0
    private lateinit var http: FakePlexHttp

    @Before
    fun clean() {
        store = PackStore(context)
        store.signOut()
        store.address = ""
    }

    /** Actions over the real store, counting announces, with every call answered by [replies]. */
    private fun actions(replies: Map<String, PlexReply> = EVERYTHING_UP): SettingsActions = actions(FakePlexHttp(replies))

    private fun actions(fake: FakePlexHttp): SettingsActions {
        http = fake
        return SettingsActions(context, store, announce = {
            announced++
        }, http = fake, deviceName = "Pixel 9", version = "0.1.0", probeMs = 500)
    }

    @Test
    fun `a code is started and asked about, and once entered the sign-in goes through the account`() =
        runTest {
            val actions = actions()

            val pin = checkNotNull(actions.startLink())
            val state = actions.linkState(pin)
            val tried = actions.finishLink((state as PinState.Approved).token)

            assertEquals(JSONObject(fixture("pins-new.json")).getString("code"), pin.code)
            assertEquals(Tried.SignedIn, tried)
            assertTrue(store.signedIn)
            assertEquals(RECORDED_TOKEN, store.accountToken)
            assertEquals("listener", store.userName)
            assertEquals("the server is opened with its own token, not the account's", SERVER_TOKEN, store.serverToken)
            assertEquals("Living room", store.serverName)
            assertEquals(MACHINE, store.machineId)
            assertEquals(
                "a local connection that answered",
                "https://192-0-2-10.0123456789abcdef0123456789abcdef.plex.direct:32400",
                store.address,
            )
            assertEquals(4, store.connections.size)
            assertEquals("1", store.sectionKey)
            assertEquals("Music", store.sectionTitle)
            assertEquals(1, announced)
        }

    /** The announce reads the store to tell a sign-in from a sign-out, so the store is written first. */
    @Test
    fun `what is kept is on disk by the time the announce reads it`() =
        runTest {
            val seen = mutableListOf<Boolean>()
            val fake = FakePlexHttp(EVERYTHING_UP)
            SettingsActions(context, store, announce = {
                seen += PackStore(it).signedIn
            }, http = fake, probeMs = 500).finishLink(RECORDED_TOKEN)

            assertEquals(listOf(true), seen)
        }

    @Test
    fun `an account that reaches no server says so, and one that plex tv will not list fails`() =
        runTest {
            val none = PlexReply.read(200, "[]")

            assertEquals(Tried.NoServers, actions(EVERYTHING_UP + ("/api/v2/resources" to none)).finishLink(RECORDED_TOKEN))
            val said = actions(EVERYTHING_UP + ("/api/v2/resources" to PlexReply.Unauthorized)).finishLink(RECORDED_TOKEN)
            assertTrue(said.toString(), said is Tried.Failed)
            assertFalse(store.signedIn)
            assertEquals(0, announced)
        }

    @Test
    fun `an account with several servers is asked which, and the pick goes on to the libraries`() =
        runTest {
            val actions = actions(EVERYTHING_UP + ("/api/v2/resources" to recorded("resources-two.json")))

            val asked = actions.finishLink(RECORDED_TOKEN)
            val tried = actions.choose(Pick.SERVER, Choice(MACHINE, "Living room"))

            assertEquals(Tried.ChooseServer(listOf(Choice(MACHINE, "Living room"), Choice(OFFICE, "Office"))), asked)
            assertEquals(Tried.SignedIn, tried)
            assertEquals("Living room", store.serverName)
            assertEquals(1, announced)
        }

    @Test
    fun `a server with several music libraries is asked which, and nothing is kept until one is picked`() =
        runTest {
            val actions = actions(EVERYTHING_UP + ("/library/sections" to recorded("sections-two-music.json")))

            val asked = actions.finishLink(RECORDED_TOKEN)

            assertEquals(Tried.ChooseLibrary(listOf(Choice("1", "Music"), Choice("3", "Audiobooks"))), asked)
            assertFalse(store.signedIn)
            assertEquals(0, announced)
            assertEquals(listOf(Choice("1", "Music"), Choice("3", "Audiobooks")), actions.libraries())

            assertEquals(Tried.SignedIn, actions.choose(Pick.LIBRARY, Choice("3", "Audiobooks")))

            assertTrue(store.signedIn)
            assertEquals("3", store.sectionKey)
            assertEquals("Audiobooks", store.sectionTitle)
            assertEquals(SERVER_TOKEN, store.serverToken)
            assertEquals(1, announced)
        }

    @Test
    fun `a server with no music library is said to have none`() =
        runTest {
            val none = PlexReply.read(200, "{\"MediaContainer\":{\"size\":0}}")

            assertEquals(Tried.NoMusic, actions(EVERYTHING_UP + ("/library/sections" to none)).finishLink(RECORDED_TOKEN))
            assertFalse(store.signedIn)
        }

    @Test
    fun `a server none of whose addresses answer is no server`() =
        runTest {
            val fake =
                FakePlexHttp { ask ->
                    if (ask.host ==
                        "plex.tv"
                    ) {
                        EVERYTHING_UP[ask.path] ?: PlexReply.Rejected(404)
                    } else {
                        PlexReply.Unreachable("timeout")
                    }
                }

            val tried = actions(fake).finishLink(RECORDED_TOKEN)

            assertTrue(tried.toString(), tried is Tried.NoServer)
            assertFalse(store.signedIn)
        }

    @Test
    fun `a typed address and token are kept with the server's name, with no account and no connections`() =
        runTest {
            val tried = actions().signInManual("http://192.0.2.10:32400", "typed-token")

            assertEquals(Tried.SignedIn, tried)
            assertTrue(store.signedIn)
            assertEquals("", store.accountToken)
            assertEquals("", store.userName)
            assertEquals("typed-token", store.serverToken)
            assertEquals("Living room", store.serverName)
            assertEquals(MACHINE, store.machineId)
            assertEquals("http://192.0.2.10:32400", store.address)
            assertEquals(0, store.connections.size)
            assertEquals("1", store.sectionKey)
            assertEquals(1, announced)
            assertEquals(
                Kept("", "Living room", "http://192.0.2.10:32400", "Music", signedIn = true, manual = true),
                actions().kept(),
            )
        }

    @Test
    fun `the identity is asked without the token, and the libraries with it`() =
        runTest {
            actions().signInManual("http://192.0.2.10:32400", "typed-token")

            val identity = http.asked.first { it.path == "/identity" }
            val sections = http.asked.first { it.path == "/library/sections" }
            assertEquals("", identity.token)
            assertEquals("typed-token", sections.token)
        }

    @Test
    fun `a test of a typed address and token keeps nothing and names the server`() =
        runTest {
            assertEquals(Tried.Reached("Living room"), actions().testManual("http://192.0.2.10:32400", "typed-token"))
            assertFalse(store.signedIn)
            assertEquals(0, announced)
        }

    @Test
    fun `each way a typed sign-in fails is told apart`() =
        runTest {
            assertEquals(Tried.NoAddress, actions().signInManual("gopher://nope", "t"))
            assertEquals(
                Tried.NoServer("connection refused"),
                actions(mapOf("/identity" to PlexReply.Unreachable("connection refused"))).signInManual(LOCAL, "t"),
            )
            assertEquals(Tried.NotPlex(404), actions(mapOf("/identity" to PlexReply.Rejected(404))).signInManual(LOCAL, "t"))
            assertEquals(Tried.NotPlex(200), actions(mapOf("/identity" to PlexReply.read(200, "{}"))).signInManual(LOCAL, "t"))
            // a 401 for the anonymous identity call, such as from a proxy that wants its own login
            assertEquals(Tried.NotPlex(401), actions(mapOf("/identity" to PlexReply.Unauthorized)).signInManual(LOCAL, "t"))
            // the server does not take the token
            assertEquals(
                Tried.Refused,
                actions(EVERYTHING_UP + ("/library/sections" to PlexReply.Unauthorized)).signInManual(LOCAL, "wrong"),
            )
            assertFalse(store.signedIn)
            assertEquals(0, announced)
        }

    @Test
    fun `testing the kept sign-in asks the libraries with its token`() =
        runTest {
            keep()

            assertEquals(Tried.StillSignedIn, actions().test())
            assertEquals(SERVER_TOKEN, http.once().token)
            assertEquals(Tried.Revoked, actions(mapOf("/library/sections" to PlexReply.Unauthorized)).test())
            assertTrue("a test does not sign out", store.signedIn)
            assertEquals(0, announced)
        }

    @Test
    fun `a kept server that stopped answering is looked for on its other connections, and the one that answers is kept`() =
        runTest {
            keep()
            val fake =
                FakePlexHttp { ask ->
                    when {
                        ask.host.startsWith("203-0-113-7") -> EVERYTHING_UP[ask.path] ?: PlexReply.Rejected(404)
                        else -> PlexReply.Unreachable("no route to host")
                    }
                }

            val tried = actions(fake).test()

            assertEquals(Tried.StillSignedIn, tried)
            assertEquals("https://203-0-113-7.0123456789abcdef0123456789abcdef.plex.direct:13936", store.address)
            assertTrue(store.signedIn)
        }

    @Test
    fun `a kept server that answers nowhere is no server, and the address stays`() =
        runTest {
            keep()

            val tried = actions(mapOf()).test()

            assertTrue(tried.toString(), tried is Tried.NoServer)
            assertEquals(LOCAL, store.address)
        }

    @Test
    fun `signing out ends the account's session on plex tv with its token, and then forgets everything`() =
        runTest {
            keep()
            val kept = mutableListOf<String>()
            val fake =
                FakePlexHttp { ask ->
                    if (ask.path == "/api/v2/users/signout") kept += store.accountToken
                    PlexReply.read(204, "")
                }

            actions(fake).signOut()

            assertEquals(listOf("/api/v2/users/signout"), http.asked.map { it.path })
            assertEquals("DELETE", http.once().method)
            assertEquals("the sign-out is sent while the token is still kept", listOf(RECORDED_TOKEN), kept)
            assertEquals(RECORDED_TOKEN, http.once().token)
            assertFalse(store.signedIn)
            assertEquals("", store.accountToken)
            assertEquals("", store.serverToken)
            assertEquals("signing out keeps the address", LOCAL, store.address)
            assertEquals(1, announced)
        }

    @Test
    fun `a sign-in with a typed token signs out on the phone alone`() =
        runTest {
            store.signIn("", "", "typed", "Living room", MACHINE, LOCAL, emptyList(), "1", "Music")

            actions().signOut()

            assertEquals(emptyList<Any>(), http.asked)
            assertFalse(store.signedIn)
            assertEquals(1, announced)
        }

    @Test
    fun `plex tv that does not hear the sign-out does not keep the listener signed in`() =
        runTest {
            keep()

            actions(mapOf("/api/v2/users/signout" to PlexReply.Unreachable("timeout"))).signOut()

            assertFalse(store.signedIn)
            assertEquals(1, announced)
        }

    @Test
    fun `the library of a kept sign-in can be changed, and the change is announced`() =
        runTest {
            keep()

            assertEquals(
                listOf(Choice("1", "Music"), Choice("3", "Audiobooks")),
                actions(EVERYTHING_UP + ("/library/sections" to recorded("sections-two-music.json"))).libraries(),
            )
            assertEquals(Tried.SignedIn, actions().choose(Pick.LIBRARY, Choice("3", "Audiobooks")))

            assertEquals("3", store.sectionKey)
            assertEquals(SERVER_TOKEN, store.serverToken)
            assertEquals(1, announced)
        }

    /** A picker canceled part way leaves a sign-in pending; a signed-in listener's choices are still the kept server's. */
    @Test
    fun `a sign-in left pending by a canceled picker does not stand in for the kept one`() =
        runTest {
            val actions = actions(EVERYTHING_UP + ("/library/sections" to recorded("sections-two-music.json")))
            assertTrue(actions.finishLink(RECORDED_TOKEN) is Tried.ChooseLibrary)
            // the listener cancels, and signs in by hand instead
            store.signIn("", "", "typed", "Office", OFFICE, LOCAL, emptyList(), "1", "Music")

            val listed = actions.libraries()
            val tried = actions.choose(Pick.LIBRARY, Choice("3", "Audiobooks"))

            assertEquals(listOf(Choice("1", "Music"), Choice("3", "Audiobooks")), listed)
            assertEquals(Tried.SignedIn, tried)
            assertEquals("the kept sign-in's library changed, nothing else", "typed", store.serverToken)
            assertEquals("Office", store.serverName)
            assertEquals("3", store.sectionKey)
        }

    @Test
    fun `a pick with no sign-in under way and nobody signed in fails`() =
        runTest {
            assertTrue(actions().choose(Pick.SERVER, Choice(MACHINE, "Living room")) is Tried.Failed)
            assertTrue(actions().choose(Pick.LIBRARY, Choice("1", "Music")) is Tried.Failed)
            assertEquals(emptyList<Choice>(), actions().libraries())
        }

    @Test
    fun `the wait for a code polls until it is entered, and gives up on plex tv that stops answering`() =
        runTest {
            val entered = actions(EVERYTHING_UP + ("/api/v2/pins/$PIN" to recorded("pins-approved.json")))
            val down = actions(EVERYTHING_UP + ("/api/v2/pins/$PIN" to PlexReply.Unreachable("timeout")))
            val gone = actions(EVERYTHING_UP + ("/api/v2/pins/$PIN" to PlexReply.Rejected(404)))

            assertEquals(Tried.SignedIn, entered.awaitApproval(PlexPin(PIN, "ABCD")))
            assertEquals(Tried.NoServer("plex.tv stopped answering"), down.awaitApproval(PlexPin(PIN, "ABCD")))
            assertEquals(Tried.Expired, gone.awaitApproval(PlexPin(PIN, "ABCD")))
        }

    private fun keep() {
        store.signIn(
            accountToken = RECORDED_TOKEN,
            userName = "listener",
            serverToken = SERVER_TOKEN,
            serverName = "Living room",
            machineId = MACHINE,
            address = LOCAL,
            connections = CONNECTIONS,
            sectionKey = "1",
            sectionTitle = "Music",
        )
    }

    private companion object {
        const val LOCAL = "http://192.0.2.10:32400"
        const val OFFICE = "ffffffffffffffffffffffffffffffffffffffff"
        val PIN: Long = JSONObject(fixture("pins-new.json")).getLong("id")
        val CONNECTIONS by lazy {
            PlexConnection.list(JSONArray(fixture("resources.json")).getJSONObject(0).getJSONArray("connections"))
        }

        /** The recorded answer for each call the actions make, on plex.tv and on the server. */
        val EVERYTHING_UP: Map<String, PlexReply> by lazy {
            mapOf(
                "/api/v2/pins" to PlexReply.read(201, fixture("pins-new.json")),
                "/api/v2/pins/$PIN" to recorded("pins-approved.json"),
                "/api/v2/user" to recorded("user.json"),
                "/api/v2/resources" to recorded("resources.json"),
                "/api/v2/users/signout" to PlexReply.read(204, ""),
                "/identity" to recorded("identity.json"),
                "/" to recorded("root.json"),
                "/library/sections" to recorded("sections.json"),
            )
        }
    }
}
