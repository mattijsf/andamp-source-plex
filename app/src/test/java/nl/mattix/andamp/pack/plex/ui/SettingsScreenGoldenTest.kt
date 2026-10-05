// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import nl.mattix.andamp.pack.plex.PinState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden images of the settings screen in five states: nobody signed in, the
 * typed way in, a code on screen, a library to pick, and signed in.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-mdpi")
class SettingsScreenGoldenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun draw(
        actions: PlexActions,
        dark: Boolean,
        shown: Boolean,
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) { PlexPage(actions, FakeAppList(shown)) }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a phone with nobody signed in`() {
        draw(FakePlexActions(), dark = true, shown = true)

        compose.onRoot().captureRoboImage("$SNAPSHOTS/plex_empty.png")
    }

    /** The typed way in, a token typed and shown, and a test that worked. */
    @Test
    fun `a typed address and token, tried`() {
        val actions = FakePlexActions()
        actions.tested.complete(Tried.Reached("Living room"))
        draw(actions, dark = false, shown = true)
        compose.onNodeWithTag("plex.mode.manual").performClick()
        compose.onNodeWithTag("plex.address").performTextInput("http://192.168.1.10:32400")
        compose.onNodeWithTag("plex.token").performTextInput("hunter2")
        compose.onNodeWithTag("plex.token.show").performClick()
        compose.onNodeWithTag("plex.test").performClick()
        compose.waitForIdle()

        compose.onRoot().captureRoboImage("$SNAPSHOTS/plex_manual.png")
    }

    @Test
    fun `a code waiting to be entered`() {
        draw(FakePlexActions(), dark = true, shown = true)
        compose.onNodeWithTag("plex.link").performClick()
        compose.waitForIdle()

        compose.onRoot().captureRoboImage("$SNAPSHOTS/plex_link_code.png")
    }

    @Test
    fun `a library to pick`() {
        val actions = FakePlexActions()
        actions.finished.complete(Tried.ChooseLibrary(listOf(Choice("1", "Music"), Choice("3", "Audiobooks"))))
        draw(actions, dark = false, shown = true)
        compose.onNodeWithTag("plex.link").performClick()
        compose.waitForIdle()
        actions.state = PinState.Approved("the-token")
        compose.mainClock.advanceTimeBy(POLL_MS + 100)
        compose.waitForIdle()
        compose.onNodeWithTag("plex.choice.3").performClick()

        compose.onRoot().captureRoboImage("$SNAPSHOTS/plex_pick_library.png")
    }

    /** Signed in, tested, with the icon taken out of the app list. */
    @Test
    fun `signed in, tried, with the icon taken out of the app list`() {
        val actions =
            FakePlexActions(
                Kept(
                    user = "listener",
                    serverName = "Living room",
                    address = "https://192-0-2-10.0123456789abcdef0123456789abcdef.plex.direct:32400",
                    libraryTitle = "Music",
                    signedIn = true,
                ),
            )
        actions.tested.complete(Tried.StillSignedIn)
        draw(actions, dark = false, shown = false)
        compose.onNodeWithTag("plex.test").performClick()
        compose.waitForIdle()

        compose.onRoot().captureRoboImage("$SNAPSHOTS/plex_signed_in.png")
    }

    private companion object {
        const val SNAPSHOTS = "src/test/snapshots"
    }
}
