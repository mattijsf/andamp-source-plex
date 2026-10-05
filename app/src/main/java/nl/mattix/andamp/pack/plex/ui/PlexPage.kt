// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.mattix.andamp.pack.common.AppListEntry
import nl.mattix.andamp.pack.plex.PlexPin
import nl.mattix.andamp.pack.plex.R

/**
 * The settings page: what this app is, who is signed in, the way in or the
 * server in use, and the app list switch.
 *
 * Signed out, the page offers two ways in: a code to enter at plex.tv/link,
 * and a typed server address and token. While a code is shown it replaces
 * the sign-in card; the poll for approval is a [LaunchedEffect] keyed on the
 * code, so leaving the page or pressing Cancel stops it. When the account
 * reaches several servers, or the server has several music libraries, a
 * picker replaces the card until one is chosen.
 *
 * Signed in, the page shows the server and the library, with Test and a way
 * to change the library.
 */
@Composable
internal fun PlexPage(
    actions: PlexActions,
    appList: AppListEntry,
    padding: PaddingValues = PaddingValues(),
) {
    var kept by remember { mutableStateOf(actions.kept()) }
    var signingOut by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf<Stage>(Stage.Form) }
    var tried by remember { mutableStateOf<Tried?>(null) }
    val work = rememberCoroutineScope()

    /** Where a finished try leaves the page. A sign-in shows the server card, with what was kept. */
    fun landed(said: Tried) {
        when (said) {
            Tried.SignedIn -> {
                kept = actions.kept()
                stage = Stage.Form
                tried = said
            }

            is Tried.ChooseServer -> {
                stage = Stage.Choosing(Pick.SERVER, said.choices)
            }

            is Tried.ChooseLibrary -> {
                stage = Stage.Choosing(Pick.LIBRARY, said.choices)
            }

            else -> {
                stage = Stage.Form
                tried = said
            }
        }
    }

    Column(
        Modifier
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Identity()
        Spacer(Modifier.height(20.dp))
        Standing(kept, signingOut, onSignOut = {
            signingOut = true
            work.launch {
                actions.signOut()
                kept = actions.kept()
                tried = null
                signingOut = false
            }
        })
        Spacer(Modifier.height(12.dp))
        when (val shown = stage) {
            is Stage.Linking -> {
                LaunchedEffect(shown.pin) { landed(actions.awaitApproval(shown.pin)) }
                CodeCard(shown.pin, onCancel = { stage = Stage.Form })
            }

            is Stage.Choosing -> {
                PickOneCard(shown.what, shown.choices, onCancel = { stage = Stage.Form }, onPick = { choice ->
                    work.launch { landed(actions.choose(shown.what, choice)) }
                })
            }

            Stage.Form -> {
                if (kept.signedIn) {
                    ServerCard(kept, actions, tried, onTried = { tried = it }, onChoose = { stage = it })
                } else {
                    SignInCard(kept, actions, tried, onTried = { tried = it }, onCode = {
                        stage =
                            Stage.Linking(
                                it,
                            )
                    }, onLanded = ::landed)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        AppListRow(appList)
        Spacer(Modifier.height(32.dp))
    }
}

/** What the middle of the page shows. */
internal sealed interface Stage {
    /** The sign-in card, or the server card once signed in. */
    data object Form : Stage

    /** A code on screen, polled until it is entered. */
    data class Linking(
        val pin: PlexPin,
    ) : Stage

    /** A list of servers or libraries to pick from. */
    data class Choosing(
        val what: Pick,
        val choices: List<Choice>,
    ) : Stage
}

/** The icon, the name and one line on what this app is. */
@Composable
private fun Identity() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(
            painterResource(R.drawable.ic_pack),
            contentDescription = null,
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text("Plex for Andamp", style = MaterialTheme.typography.titleLarge)
            Text(
                "Play the music on your Plex Media Server in Andamp.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Who this phone is signed in as, and on which server. "Signed in" means a
 * token and a library are kept; the server is not asked when the page opens.
 */
@Composable
private fun Standing(
    kept: Kept,
    signingOut: Boolean,
    onSignOut: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (kept.signedIn) Icons.Filled.AccountCircle else Icons.Outlined.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (kept.signedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !kept.signedIn -> "Not signed in"
                        kept.user.isNotEmpty() -> kept.user
                        else -> "Signed in with a token"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (kept.signedIn) {
                        kept.serverName.ifEmpty {
                            host(
                                kept.address,
                            )
                        }
                    } else {
                        "Andamp skips Plex tracks until you sign in"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (kept.signedIn) {
                TextButton(
                    onClick = onSignOut,
                    enabled = !signingOut,
                    modifier = Modifier.testTag("plex.signout"),
                ) { Text(if (signingOut) "Signing out…" else "Sign out") }
            }
        }
    }
}

/** The two ways in, one at a time: a code from the account, or a typed address and token. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignInCard(
    kept: Kept,
    actions: PlexActions,
    tried: Tried?,
    onTried: (Tried?) -> Unit,
    onCode: (PlexPin) -> Unit,
    onLanded: (Tried) -> Unit,
) {
    var manual by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Sign in", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !manual,
                    onClick = {
                        manual = false
                        onTried(null)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    modifier = Modifier.testTag("plex.mode.link"),
                ) { Text("Plex account") }
                SegmentedButton(
                    selected = manual,
                    onClick = {
                        manual = true
                        onTried(null)
                    },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    modifier = Modifier.testTag("plex.mode.manual"),
                ) { Text("Server and token") }
            }
            Spacer(Modifier.height(16.dp))
            if (manual) {
                ManualPanel(kept, actions, tried, onTried, onLanded)
            } else {
                LinkPanel(actions, tried, onTried, onCode)
            }
        }
    }
}

/** The server in use: its name, address and library, Test, and a way to change the library. */
@Composable
private fun ServerCard(
    kept: Kept,
    actions: PlexActions,
    tried: Tried?,
    onTried: (Tried?) -> Unit,
    onChoose: (Stage) -> Unit,
) {
    var testing by remember { mutableStateOf(false) }
    var listing by remember { mutableStateOf(false) }
    val work = rememberCoroutineScope()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Server", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Line("Address", host(kept.address))
            Line("Library", kept.libraryTitle)
            tried?.let {
                Spacer(Modifier.height(12.dp))
                Outcome(it)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    enabled = !testing && !listing,
                    onClick = {
                        testing = true
                        onTried(null)
                        work.launch {
                            onTried(actions.test())
                            testing = false
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("plex.test"),
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(if (testing) "Testing…" else "Test")
                }
                OutlinedButton(
                    enabled = !testing && !listing,
                    onClick = {
                        listing = true
                        onTried(null)
                        work.launch {
                            val choices = actions.libraries()
                            listing = false
                            when (choices.size) {
                                0 -> onTried(Tried.NoServer("the server did not list its libraries"))
                                1 -> onTried(Tried.OneLibrary(choices.single().title))
                                else -> onChoose(Stage.Choosing(Pick.LIBRARY, choices))
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("plex.library"),
                ) { Text(if (listing) "Asking…" else "Change library") }
            }
        }
    }
}

/** One labeled line of the server card. */
@Composable
private fun Line(
    label: String,
    value: String,
) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(value.ifEmpty { "—" }, style = MaterialTheme.typography.bodyMedium)
    }
}

/** What the last test or sign-in said, in the error colors when it failed. */
@Composable
internal fun Outcome(tried: Tried) {
    val good = tried is Tried.Reached || tried == Tried.StillSignedIn || tried == Tried.SignedIn || tried is Tried.OneLibrary
    Surface(
        color = if (good) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (good) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (good) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                words(tried),
                style = MaterialTheme.typography.bodyMedium,
                color = if (good) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.testTag("plex.said"),
            )
        }
    }
}

/** The address without its scheme or trailing slashes. */
internal fun host(address: String): String = address.substringAfter("://").trimEnd('/')

/** The sentence the page shows for each outcome. */
internal fun words(tried: Tried): String =
    when (tried) {
        Tried.SignedIn -> "Signed in."
        is Tried.Reached -> "Connected to ${tried.serverName}. Sign in to use this server in Andamp."
        Tried.StillSignedIn -> "Connected. You are signed in."
        Tried.Revoked -> "The server no longer accepts this sign-in. Sign in again."
        Tried.NoAddress -> "That is not a server address."
        is Tried.NoServer -> "Nothing answered: ${tried.why}"
        is Tried.NotPlex -> "Something answered with HTTP ${tried.status}, but it was not a Plex server."
        Tried.Refused -> "The server does not accept that token."
        Tried.Expired -> "The code expired. Get a new one."
        Tried.NoMusic -> "That server has no music library."
        is Tried.OneLibrary -> "This server has one music library: ${tried.title}."
        Tried.NoServers -> "Your Plex account reaches no server."
        is Tried.ChooseServer, is Tried.ChooseLibrary -> "Choose one."
        is Tried.Failed -> "Plex did not sign you in: ${tried.why}"
    }
