package de.maxihaaser.audioscratch.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.maxihaaser.audioscratch.R
import de.maxihaaser.audioscratch.settings.AppSettings
import kotlin.math.roundToInt

/**
 * Capture settings, mirroring the desktop's modal `SettingsDialog`: edits are held
 * locally and only committed by OK, so Cancel (or a tap outside) discards them.
 *
 * Two of the desktop's four rows are deliberately absent:
 *  - **Capture source** (desktop / desktop+mic) — this app is microphone-only by
 *    product decision; it never captures device audio, so there is nothing to pick.
 *  - **Instant Replay hotkey** — a touch device has no hardware keyboard to bind.
 *    The ongoing notification's "Clip now" action is the equivalent.
 *
 * [devices] is `id → label`, as built by [RecorderViewModel.inputDevices];
 * [bufferLocked] mirrors the ViewModel refusing a buffer change while Instant
 * Replay is armed (the ring is sized at arm time), so the control doesn't lie
 * about what it can do.
 */
@Composable
fun SettingsDialog(
    bufferSeconds: Int,
    micDeviceId: Int,
    devices: List<Pair<Int, String>>,
    bufferLocked: Boolean,
    onConfirm: (bufferSeconds: Int, micDeviceId: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Desktop parity: the buffer spin box's range.
    val minSeconds = AppSettings.MIN_BUFFER_SECONDS
    val maxSeconds = AppSettings.MAX_BUFFER_SECONDS

    // Keyed on the incoming values so reopening the dialog starts from what is
    // actually in force, rather than from the last edit this composable saw.
    var seconds by remember(bufferSeconds) { mutableIntStateOf(bufferSeconds) }
    var deviceId by remember(micDeviceId) { mutableIntStateOf(micDeviceId) }
    var menuOpen by remember { mutableStateOf(false) }

    // A persisted device that isn't in the list right now (unplugged since it was
    // picked) reads as the system default — which is what capture would really use.
    val selectedLabel = devices.firstOrNull { it.first == deviceId }?.second
        ?: stringResource(R.string.settings_mic_default)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_buffer_label),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.ir_seconds_format, seconds),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                // Desktop parity: 1..120 s. Continuous rather than stepped — 120 tick
                // marks across a phone's width would be noise; the value is rounded to
                // whole seconds and the label above reads it back.
                Slider(
                    value = seconds.toFloat(),
                    onValueChange = { seconds = it.roundToInt().coerceIn(minSeconds, maxSeconds) },
                    valueRange = minSeconds.toFloat()..maxSeconds.toFloat(),
                    enabled = !bufferLocked,
                )
                if (bufferLocked) {
                    Text(
                        text = stringResource(R.string.settings_buffer_locked),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.settings_mic_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(4.dp))
                // The menu is anchored to this Box, so it drops over the button.
                Box {
                    OutlinedButton(
                        onClick = { menuOpen = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = selectedLabel,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        devices.forEach { (id, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    deviceId = id
                                    menuOpen = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_mic_helper),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // A locked buffer commits the value it came in with, so OK can't
                    // smuggle through an edit the ViewModel would refuse anyway.
                    val committed = if (bufferLocked) bufferSeconds else seconds
                    onConfirm(committed, deviceId)
                },
            ) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
