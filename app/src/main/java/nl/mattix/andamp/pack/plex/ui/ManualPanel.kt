// SPDX-License-Identifier: GPL-3.0-or-later

package nl.mattix.andamp.pack.plex.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import nl.mattix.andamp.pack.plex.R

/**
 * The typed way in: the server's address and a token, with Test and Sign in.
 * It opens with the kept address and an empty token.
 */
@Composable
internal fun ManualPanel(
    kept: Kept,
    actions: PlexActions,
    tried: Tried?,
    onTried: (Tried?) -> Unit,
    onLanded: (Tried) -> Unit,
) {
    var address by remember { mutableStateOf(kept.address) }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val work = rememberCoroutineScope()
    val filled = address.isNotBlank() && token.isNotBlank()
    val idle = !busy && !testing

    Column {
        OutlinedTextField(
            value = address,
            onValueChange = {
                address = it
                onTried(null)
            },
            label = { Text("Server address") },
            placeholder = { Text("http://192.168.1.10:32400") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("plex.address"),
        )
        Spacer(Modifier.height(8.dp))
        TokenField(token, onValueChange = {
            token = it
            onTried(null)
        })
        tried?.let {
            Spacer(Modifier.height(12.dp))
            Outcome(it)
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                enabled = idle && filled,
                onClick = {
                    testing = true
                    onTried(null)
                    work.launch {
                        onTried(actions.testManual(address, token))
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
            Button(
                enabled = idle && filled,
                onClick = {
                    busy = true
                    onTried(null)
                    work.launch {
                        val said = actions.signInManual(address, token)
                        busy = false
                        onLanded(said)
                    }
                },
                modifier = Modifier.weight(1f).testTag("plex.signin"),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (busy) "Signing in…" else "Sign in")
            }
        }
    }
}

/** The token field, hidden until the eye button is pressed, with one line on where a token comes from. */
@Composable
private fun TokenField(
    value: String,
    onValueChange: (String) -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Token") },
        supportingText = { Text("The X-Plex-Token of your server, from its web app or your account.") },
        singleLine = true,
        visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { shown = !shown }, modifier = Modifier.testTag("plex.token.show")) {
                Icon(
                    painterResource(if (shown) R.drawable.ic_eye_off else R.drawable.ic_eye),
                    contentDescription = if (shown) "Hide token" else "Show token",
                )
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth().testTag("plex.token"),
    )
}
