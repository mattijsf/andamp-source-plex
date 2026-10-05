// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex.ui

import android.content.ClipData
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import nl.mattix.andamp.pack.plex.Plex
import nl.mattix.andamp.pack.plex.PlexPin

/** One line on how the code works, and the button that asks plex.tv for one. */
@Composable
internal fun LinkPanel(
    actions: PlexActions,
    tried: Tried?,
    onTried: (Tried?) -> Unit,
    onCode: (PlexPin) -> Unit,
) {
    var starting by remember { mutableStateOf(false) }
    val work = rememberCoroutineScope()
    Column {
        Text(
            "Get a code here and enter it at plex.tv/link, signed in to your Plex account. Andamp then lists your servers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        tried?.let {
            Spacer(Modifier.height(12.dp))
            Outcome(it)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !starting,
            onClick = {
                starting = true
                onTried(null)
                work.launch {
                    val pin = actions.startLink()
                    starting = false
                    if (pin == null) onTried(Tried.Failed("plex.tv would not start a sign-in")) else onCode(pin)
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("plex.link"),
        ) {
            if (starting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (starting) "Asking plex.tv…" else "Get a code")
        }
    }
}

/**
 * The code in large, spaced, fixed-width type, with a copy button, a button
 * that opens plex.tv/link, a waiting line and Cancel.
 */
@Composable
internal fun CodeCard(
    pin: PlexPin,
    onCancel: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val copying = rememberCoroutineScope()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Enter this code at plex.tv/link",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // as wide as the copy button on the right, so the code is centered
                Spacer(Modifier.width(48.dp))
                Text(
                    pin.code,
                    style = MaterialTheme.typography.displayMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 6.sp),
                    modifier = Modifier.testTag("plex.code"),
                )
                IconButton(
                    onClick = {
                        copying.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Plex code", pin.code))) }
                    },
                    modifier = Modifier.testTag("plex.copy"),
                ) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy the code") }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Plex.LINK.toUri())) } },
                modifier = Modifier.fillMaxWidth().testTag("plex.open"),
            ) { Text("Open plex.tv/link") }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("Waiting for the code to be entered…", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onCancel, modifier = Modifier.testTag("plex.cancel")) { Text("Cancel") }
        }
    }
}
