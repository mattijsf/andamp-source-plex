// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex.ui

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import kotlinx.coroutines.CompletableDeferred
import nl.mattix.andamp.pack.plex.PinState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The settings page over fake actions, asserted on what is on screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class PlexPageTest {
    @get:Rule
    val compose = createComposeRule()

    private fun page(actions: PlexActions) {
        compose.setContent { PlexPage(actions, FakeAppList()) }
    }

    /** The text in a field, without its label. */
    private fun holds(tag: String): String =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.EditableText]
            .text

    /** Switches to the typed way in and fills the fields. */
    private fun manual(
        address: String = ADDRESS,
        token: String = TOKEN,
    ) {
        compose.onNodeWithTag("plex.mode.manual").performClick()
        if (address.isNotEmpty()) compose.onNodeWithTag("plex.address").performTextInput(address)
        if (token.isNotEmpty()) compose.onNodeWithTag("plex.token").performTextInput(token)
    }

    @Test
    fun `nobody signed in is said, with the account way in first and no sign-out`() {
        page(FakePlexActions())

        compose.onNodeWithText("Not signed in").assertIsDisplayed()
        compose.onNodeWithTag("plex.signout").assertDoesNotExist()
        compose.onNodeWithTag("plex.link").assertIsDisplayed()
        compose.onNodeWithTag("plex.address").assertDoesNotExist()
    }

    @Test
    fun `the typed way in needs an address and a token before Test or Sign in`() {
        page(FakePlexActions())

        manual(token = "")
        compose.onNodeWithTag("plex.signin").assertIsNotEnabled()
        compose.onNodeWithTag("plex.test").assertIsNotEnabled()

        compose.onNodeWithTag("plex.token").performTextInput(TOKEN)
        compose.onNodeWithTag("plex.signin").assertIsEnabled()
        compose.onNodeWithTag("plex.test").assertIsEnabled()
    }

    @Test
    fun `the typed way in opens with the kept address`() {
        page(
            FakePlexActions(
                Kept(user = "", serverName = "", address = "http://192.168.1.10:32400", libraryTitle = "", signedIn = false),
            ),
        )

        compose.onNodeWithTag("plex.mode.manual").performClick()

        assertEquals("http://192.168.1.10:32400", holds("plex.address"))
    }

    @Test
    fun `Test sends what was typed, spins while it is out, and says the server took it without closing`() {
        val actions = FakePlexActions()
        page(actions)
        manual()

        compose.onNodeWithTag("plex.test").performClick()

        compose.onNodeWithText("Testing…").assertIsDisplayed()
        compose.onNodeWithTag("plex.signin").assertIsNotEnabled()
        assertEquals(listOf(listOf(ADDRESS, TOKEN)), actions.tests)

        actions.tested.complete(Tried.Reached("Living room"))
        compose.waitForIdle()

        compose.onNodeWithText("Connected to Living room. Sign in to use this server in Andamp.").assertIsDisplayed()
        compose.onNodeWithTag("plex.signin").assertIsEnabled()
        assertEquals("a test signs nobody in", emptyList<List<String>>(), actions.signedIn)
    }

    @Test
    fun `a typed sign-in sends exactly what was typed, spins while it is out, and shows the server when it works`() {
        val actions = FakePlexActions()
        page(actions)
        manual()

        compose.onNodeWithTag("plex.signin").performClick()

        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onNodeWithTag("plex.signin").assertIsNotEnabled()
        assertEquals(listOf(listOf(ADDRESS, TOKEN)), actions.signedIn)

        actions.answer.complete(Tried.SignedIn)
        compose.waitForIdle()

        compose.onNodeWithText("Signed in.").assertIsDisplayed()
        compose.onNodeWithText("Living room").assertIsDisplayed()
        compose.onNodeWithTag("plex.test").assertIsDisplayed()
    }

    @Test
    fun `each way a sign-in fails points at the thing to fix`() {
        val lines =
            mapOf(
                Tried.NoAddress to "That is not a server address.",
                Tried.NoServer("connection refused") to "Nothing answered: connection refused",
                Tried.NotPlex(404) to "Something answered with HTTP 404, but it was not a Plex server.",
                Tried.Refused to "The server does not accept that token.",
                Tried.NoMusic to "That server has no music library.",
                Tried.Failed("the server answered 500") to "Plex did not sign you in: the server answered 500",
            )
        val actions = FakePlexActions()
        page(actions)
        manual()
        for ((said, words) in lines) {
            actions.answer = CompletableDeferred(said)
            compose.onNodeWithTag("plex.signin").performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("plex.said").assertTextEquals(words)
        }
    }

    @Test
    fun `changing a field takes away what the last try said`() {
        val actions = FakePlexActions()
        actions.answer.complete(Tried.Refused)
        page(actions)
        manual()
        compose.onNodeWithTag("plex.signin").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("plex.said").assertIsDisplayed()

        compose.onNodeWithTag("plex.token").performTextInput("x")

        compose.onNodeWithTag("plex.said").assertDoesNotExist()
    }

    @Test
    fun `the token starts hidden and can be shown while it is typed`() {
        page(FakePlexActions())
        manual(address = "")

        compose.onNodeWithContentDescription("Show token").assertExists()
        compose.onNodeWithTag("plex.token.show").performClick()
        compose.onNodeWithContentDescription("Hide token").assertExists()
        assertEquals("showing the token keeps the typed text", TOKEN, holds("plex.token"))
    }

    @Test
    fun `a code shows with the way to it, a spinner and a way to open plex tv`() {
        linked(FakePlexActions())

        compose.onNodeWithTag("plex.code").assertTextEquals("ABCD")
        compose.onNodeWithText("Enter this code at plex.tv/link").assertIsDisplayed()
        compose.onNodeWithContentDescription("Copy the code").assertIsDisplayed()
        compose.onNodeWithTag("plex.open").assertIsDisplayed()
        compose.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertExists()
        compose.onNodeWithTag("plex.link").assertDoesNotExist()
    }

    @Test
    fun `a code is asked about on a timer until it is entered, then the sign-in goes on to the server`() {
        val actions = FakePlexActions()
        actions.finished.complete(Tried.SignedIn)
        linked(actions)

        compose.mainClock.advanceTimeBy(POLL_MS * 2 + 100)
        compose.waitForIdle()
        assertEquals(listOf(1234L, 1234L), actions.polled)

        actions.state = PinState.Approved("the-token")
        compose.mainClock.advanceTimeBy(POLL_MS + 100)
        compose.waitForIdle()

        assertEquals(listOf("the-token"), actions.finishedWith)
        compose.onNodeWithText("Signed in.").assertIsDisplayed()
        compose.onNodeWithText("Living room").assertIsDisplayed()
        compose.onNodeWithTag("plex.test").assertIsDisplayed()
    }

    @Test
    fun `canceling a code stops asking about it and brings the way in back`() {
        val actions = FakePlexActions()
        linked(actions)
        compose.mainClock.advanceTimeBy(POLL_MS + 100)
        compose.waitForIdle()

        compose.onNodeWithTag("plex.cancel").performClick()
        compose.waitForIdle()
        val before = actions.polled.size
        compose.mainClock.advanceTimeBy(POLL_MS * 3)
        compose.waitForIdle()

        assertEquals(before, actions.polled.size)
        compose.onNodeWithTag("plex.code").assertDoesNotExist()
        compose.onNodeWithTag("plex.link").assertIsDisplayed()
        assertEquals(emptyList<String>(), actions.finishedWith)
    }

    @Test
    fun `a code that runs out says so and offers a new one`() {
        val actions = FakePlexActions()
        linked(actions)

        actions.state = PinState.Expired
        compose.mainClock.advanceTimeBy(POLL_MS + 100)
        compose.waitForIdle()

        compose.onNodeWithText("The code expired. Get a new one.").assertIsDisplayed()
        compose.onNodeWithTag("plex.link").assertIsDisplayed()
    }

    @Test
    fun `plex tv that would not start a code says so`() {
        val actions = FakePlexActions(pin = null)
        page(actions)

        compose.onNodeWithTag("plex.link").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Plex did not sign you in: plex.tv would not start a sign-in").assertIsDisplayed()
    }

    @Test
    fun `several libraries are offered to pick from, and the pick is kept and shown`() {
        val actions = FakePlexActions()
        actions.finished.complete(Tried.ChooseLibrary(listOf(Choice("1", "Music"), Choice("3", "Audiobooks"))))
        linked(actions)
        actions.state = PinState.Approved("the-token")
        compose.mainClock.advanceTimeBy(POLL_MS + 100)
        compose.waitForIdle()

        compose.onNodeWithText("Which music library?").assertIsDisplayed()
        compose.onNodeWithTag("plex.continue").assertIsNotEnabled()
        compose.onNodeWithTag("plex.choice.3").performClick()
        compose.onNodeWithTag("plex.continue").assertIsEnabled().performClick()
        actions.chosen.complete(Tried.SignedIn)
        compose.waitForIdle()

        assertEquals(listOf("library:3"), actions.picked)
        compose.onNodeWithText("Signed in.").assertIsDisplayed()
        compose.onNodeWithText("Living room").assertIsDisplayed()
        compose.onNodeWithTag("plex.test").assertIsDisplayed()
    }

    @Test
    fun `several servers are offered to pick from, and canceling keeps nothing`() {
        val actions = FakePlexActions()
        actions.answer.complete(Tried.ChooseServer(listOf(Choice("a", "Living room"), Choice("b", "Office"))))
        page(actions)
        manual()
        compose.onNodeWithTag("plex.signin").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Which server?").assertIsDisplayed()
        compose.onNodeWithTag("plex.choose.cancel").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("plex.choice.a").assertDoesNotExist()
        compose.onNodeWithTag("plex.link").assertIsDisplayed()
        assertEquals(emptyList<String>(), actions.picked)
    }

    @Test
    fun `a kept sign-in opens as who and where, with the library, Test and sign-out`() {
        page(FakePlexActions(KEPT))

        compose.onNodeWithText("listener").assertIsDisplayed()
        compose.onNodeWithText("Living room").assertIsDisplayed()
        compose.onNodeWithText("Music").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertIsDisplayed()
        compose.onNodeWithTag("plex.test").assertIsEnabled()
        compose.onNodeWithTag("plex.address").assertDoesNotExist()
    }

    @Test
    fun `a sign-in with a typed token says so`() {
        page(FakePlexActions(KEPT.copy(user = "", manual = true)))

        compose.onNodeWithText("Signed in with a token").assertIsDisplayed()
    }

    @Test
    fun `testing the kept sign-in says whether the server still takes it`() {
        val actions = FakePlexActions(KEPT)
        page(actions)

        compose.onNodeWithTag("plex.test").performClick()
        actions.tested.complete(Tried.Revoked)
        compose.waitForIdle()

        assertEquals(1, actions.keptTests)
        compose.onNodeWithText("The server no longer accepts this sign-in. Sign in again.").assertIsDisplayed()
    }

    @Test
    fun `the library can be changed while signed in, without closing the page`() {
        val actions = FakePlexActions(KEPT, libraries = listOf(Choice("1", "Music"), Choice("3", "Audiobooks")))
        actions.chosen.complete(Tried.SignedIn)
        page(actions)

        compose.onNodeWithTag("plex.library").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("plex.choice.3").performClick()
        compose.onNodeWithTag("plex.continue").performClick()
        compose.waitForIdle()

        assertEquals(listOf("library:3"), actions.picked)
        compose.onNodeWithTag("plex.test").assertIsDisplayed()
    }

    @Test
    fun `a server with one library has nothing to change to`() {
        val actions = FakePlexActions(KEPT, libraries = listOf(Choice("1", "Music")))
        page(actions)

        compose.onNodeWithTag("plex.library").performClick()
        compose.waitForIdle()

        compose.onNodeWithTag("plex.said").assertTextEquals("This server has one music library: Music.")
    }

    @Test
    fun `signing out forgets the sign-in and offers the ways in again`() {
        val actions = FakePlexActions(KEPT)
        page(actions)

        compose.onNodeWithTag("plex.signout").performClick()
        compose.waitForIdle()

        assertEquals(1, actions.signedOut)
        compose.onNodeWithTag("plex.link").assertIsDisplayed()
    }

    @Test
    fun `the page carries the app list switch`() {
        val list = FakeAppList(shown = true)
        compose.setContent { PlexPage(FakePlexActions(), list) }

        compose
            .onNodeWithTag("pack.applist")
            .performScrollTo()
            .assertIsOn()
            .performClick()

        assertEquals(listOf(false), list.told)
        compose.onNodeWithTag("pack.applist").assertIsOff()
    }

    /** A page with a code on screen. */
    private fun linked(actions: FakePlexActions) {
        page(actions)
        compose.onNodeWithTag("plex.link").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("plex.code").assertExists()
    }

    private companion object {
        const val ADDRESS = "http://192.168.1.10:32400"
        const val TOKEN = "sesame"
        val KEPT =
            Kept(
                user = "listener",
                serverName = "Living room",
                address = "https://192-0-2-10.0123456789abcdef0123456789abcdef.plex.direct:32400",
                libraryTitle = "Music",
                signedIn = true,
            )
    }
}
