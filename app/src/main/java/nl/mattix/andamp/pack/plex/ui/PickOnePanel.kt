// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** A titled list of servers or libraries with one radio button each, Continue and Cancel. */
@Composable
internal fun PickOneCard(
    what: Pick,
    choices: List<Choice>,
    onPick: (Choice) -> Unit,
    onCancel: () -> Unit,
) {
    var picked by remember { mutableStateOf<Choice?>(null) }
    var busy by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text(
                when (what) {
                    Pick.SERVER -> "Which server?"
                    Pick.LIBRARY -> "Which music library?"
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            for (choice in choices) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = picked == choice, role = Role.RadioButton, enabled = !busy) { picked = choice }
                        .padding(vertical = 4.dp)
                        .testTag("plex.choice.${choice.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // no click handler: the row takes the press
                    RadioButton(selected = picked == choice, onClick = null)
                    Spacer(Modifier.width(12.dp))
                    Text(choice.title, style = MaterialTheme.typography.bodyLarge)
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    enabled = !busy,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f).testTag("plex.choose.cancel"),
                ) { Text("Cancel") }
                Button(
                    enabled = picked != null && !busy,
                    onClick = {
                        busy = true
                        picked?.let(onPick)
                    },
                    modifier = Modifier.weight(1f).testTag("plex.continue"),
                ) { Text(if (busy) "One moment…" else "Continue") }
            }
        }
    }
}
