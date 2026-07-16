package de.maxihaaser.audioscratch.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.maxihaaser.audioscratch.R
import de.maxihaaser.audioscratch.audio.ScrubPlayer
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The single screen of the MVP: record from the mic, then scrub / play back the
 * captured audio. [hasPermission] and [onRequestPermission] are supplied by
 * [de.maxihaaser.audioscratch.MainActivity], which owns the RECORD_AUDIO
 * ActivityResult flow.
 */
@Composable
fun RecordScrubScreen(
    viewModel: RecorderViewModel,
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onImport: () -> Unit,
    onSetInstantReplay: (Boolean) -> Unit,
    onSetBufferSeconds: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val playhead by viewModel.playhead.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val isInstantReplayOn by viewModel.isInstantReplayOn.collectAsState()
    val bufferSeconds by viewModel.bufferSeconds.collectAsState()
    val speed by viewModel.speed.collectAsState()
    val pitchSemitones by viewModel.pitchSemitones.collectAsState()
    val pitchCents by viewModel.pitchCents.collectAsState()
    val playbackMode by viewModel.playbackMode.collectAsState()
    val volumePercent by viewModel.volumePercent.collectAsState()
    val muted by viewModel.muted.collectAsState()
    val durationSeconds by viewModel.durationSeconds.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "AudioScratch",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
        )
        Text(
            text = "Record, then scrub it like a turntable.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        if (!hasPermission) {
            PermissionCard(onRequestPermission = onRequestPermission)
            return@Column
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            when {
                isImporting -> ImportingIndicator()

                else -> when (val state = uiState) {
                    is UiState.Ready -> ReadyContent(
                        state = state,
                        playhead = playhead,
                        isPlaying = isPlaying,
                        speed = speed,
                        pitchSemitones = pitchSemitones,
                        pitchCents = pitchCents,
                        playbackMode = playbackMode,
                        volumePercent = volumePercent,
                        muted = muted,
                        durationSeconds = durationSeconds,
                        onScrub = viewModel::scrub,
                        onTogglePlay = viewModel::togglePlayback,
                        onSetSpeed = viewModel::setSpeed,
                        onResetSpeed = viewModel::resetSpeed,
                        onSetPitchSemitones = viewModel::setPitchSemitones,
                        onSetPitchCents = viewModel::setPitchCents,
                        onResetPitch = viewModel::resetPitch,
                        onSetPlaybackMode = viewModel::setPlaybackMode,
                        onSetVolumePercent = viewModel::setVolumePercent,
                        onSetMuted = viewModel::setMuted,
                    )

                    UiState.Recording -> RecordingIndicator()

                    UiState.Idle -> Text(
                        text = "Tap Record to capture audio from your microphone.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        errorMessage?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = viewModel::clearError) {
                Text(stringResource(R.string.dismiss))
            }
        }

        Spacer(Modifier.height(24.dp))

        // Import is available whenever we're not mid-capture, so a new file can
        // be loaded over a previous take.
        if (uiState !is UiState.Recording) {
            TextButton(
                onClick = onImport,
                enabled = !isImporting,
            ) {
                Text(stringResource(R.string.import_button))
            }
            Spacer(Modifier.height(4.dp))
        }

        RecordButton(
            isRecording = uiState is UiState.Recording,
            enabled = !isImporting,
            onClick = {
                if (uiState is UiState.Recording) viewModel.stopRecording()
                else viewModel.startRecording()
            },
        )

        Spacer(Modifier.height(16.dp))

        InstantReplayCard(
            isOn = isInstantReplayOn,
            bufferSeconds = bufferSeconds,
            // Mic exclusivity: can't arm / change the buffer while a manual
            // recording owns the microphone.
            enabled = uiState !is UiState.Recording,
            onToggle = onSetInstantReplay,
            onSelectSeconds = onSetBufferSeconds,
        )

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * Instant Replay ("shadowplay") controls: a switch to arm a rolling mic buffer
 * plus a 15 / 30 / 60 s length selector (locked while armed, since the ring is
 * sized at arm time). Clips are saved from the ongoing notification's "Clip now"
 * button. Only shown once RECORD_AUDIO is granted (the caller gates on that).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstantReplayCard(
    isOn: Boolean,
    bufferSeconds: Int,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onSelectSeconds: (Int) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.ir_switch_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                Switch(checked = isOn, onCheckedChange = onToggle, enabled = enabled)
            }

            Spacer(Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.ir_switch_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(12.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                BufferSecondOptions.forEachIndexed { index, sec ->
                    SegmentedButton(
                        selected = bufferSeconds == sec,
                        // Length is baked into the ring at arm time; lock it on while
                        // armed, and while a manual recording owns the mic.
                        enabled = enabled && !isOn,
                        onClick = { onSelectSeconds(sec) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = BufferSecondOptions.size,
                        ),
                    ) {
                        Text(stringResource(R.string.ir_seconds_format, sec))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = if (isOn) {
                    stringResource(R.string.ir_status_on, bufferSeconds)
                } else {
                    stringResource(R.string.ir_status_off)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private val BufferSecondOptions = listOf(15, 30, 60)

@Composable
private fun PermissionCard(onRequestPermission: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Microphone access needed",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "AudioScratch records from your microphone so you can scrub the " +
                    "sound afterwards. Nothing is uploaded — audio stays on this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Button(onClick = onRequestPermission) {
                Text("Grant microphone access")
            }
        }
    }
}

@Composable
private fun RecordingIndicator() {
    val transition = rememberInfiniteTransition(label = "recording")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recordingAlpha",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .graphicsLayer { this.alpha = alpha }
                .background(RecordRed, CircleShape),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Recording…",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ImportingIndicator() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.importing_label),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ReadyContent(
    state: UiState.Ready,
    playhead: Float,
    isPlaying: Boolean,
    speed: Float,
    pitchSemitones: Int,
    pitchCents: Int,
    playbackMode: ScrubPlayer.PlaybackMode,
    volumePercent: Int,
    muted: Boolean,
    durationSeconds: Float,
    onScrub: (Float) -> Unit,
    onTogglePlay: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onResetSpeed: () -> Unit,
    onSetPitchSemitones: (Int) -> Unit,
    onSetPitchCents: (Int) -> Unit,
    onResetPitch: () -> Unit,
    onSetPlaybackMode: (ScrubPlayer.PlaybackMode) -> Unit,
    onSetVolumePercent: (Int) -> Unit,
    onSetMuted: (Boolean) -> Unit,
) {
    // The transport + DSP controls make this taller than the viewport on small
    // screens, so the whole Ready pane scrolls.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Waveform(
            peaks = state.peaks,
            progress = playhead,
            waveColor = MaterialTheme.colorScheme.primary,
            playedColor = MaterialTheme.colorScheme.tertiary,
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
            onScrub = onScrub,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        )

        Spacer(Modifier.height(16.dp))

        Slider(
            value = playhead.coerceIn(0f, 1f),
            onValueChange = onScrub,
        )

        Text(
            text = stringResource(
                R.string.time_position,
                formatCentis((playhead.coerceIn(0f, 1f) * durationSeconds * 1000f).toLong()),
                formatCentis((durationSeconds * 1000f).toLong()),
            ),
            style = MaterialTheme.typography.labelMedium,
        )

        Spacer(Modifier.height(20.dp))

        PlayPauseButton(isPlaying = isPlaying, onClick = onTogglePlay)

        Spacer(Modifier.height(20.dp))

        PitchSpeedControls(
            speed = speed,
            pitchSemitones = pitchSemitones,
            pitchCents = pitchCents,
            playbackMode = playbackMode,
            onSetSpeed = onSetSpeed,
            onResetSpeed = onResetSpeed,
            onSetPitchSemitones = onSetPitchSemitones,
            onSetPitchCents = onSetPitchCents,
            onResetPitch = onResetPitch,
            onSetPlaybackMode = onSetPlaybackMode,
        )

        Spacer(Modifier.height(12.dp))

        VolumeControls(
            volumePercent = volumePercent,
            muted = muted,
            onSetVolumePercent = onSetVolumePercent,
            onSetMuted = onSetMuted,
        )
    }
}

/**
 * Speed / pitch / mode section. Speed is log-mapped over 0.25×..4× (so 1× sits at
 * the slider's midpoint); pitch is a coarse semitone slider plus a cents vernier.
 * The mode toggle picks between holding pitch while the tempo moves and letting
 * pitch ride the rate like a turntable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PitchSpeedControls(
    speed: Float,
    pitchSemitones: Int,
    pitchCents: Int,
    playbackMode: ScrubPlayer.PlaybackMode,
    onSetSpeed: (Float) -> Unit,
    onResetSpeed: () -> Unit,
    onSetPitchSemitones: (Int) -> Unit,
    onSetPitchCents: (Int) -> Unit,
    onResetPitch: () -> Unit,
    onSetPlaybackMode: (ScrubPlayer.PlaybackMode) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.speed_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.speed_value, speed),
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(onClick = onResetSpeed) {
                    Text(stringResource(R.string.speed_reset))
                }
            }
            Slider(
                value = speedToSlider(speed),
                onValueChange = { onSetSpeed(sliderToSpeed(it)) },
            )

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.pitch_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.pitch_value, pitchSemitones, pitchCents),
                    style = MaterialTheme.typography.labelMedium,
                )
                TextButton(onClick = onResetPitch) {
                    Text(stringResource(R.string.pitch_reset))
                }
            }
            Text(
                text = stringResource(R.string.pitch_semitones_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = pitchSemitones.toFloat(),
                onValueChange = { onSetPitchSemitones(it.roundToInt()) },
                valueRange = -36f..36f,
                // 73 integer stops (-36..+36) → 71 steps between the endpoints.
                steps = 71,
            )
            Text(
                text = stringResource(R.string.pitch_cents_label),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Slider(
                value = pitchCents.toFloat(),
                onValueChange = { onSetPitchCents(it.roundToInt()) },
                valueRange = -100f..100f,
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.mode_label),
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(Modifier.height(4.dp))

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                PlaybackModeOptions.forEachIndexed { index, (mode, labelRes) ->
                    SegmentedButton(
                        selected = playbackMode == mode,
                        onClick = { onSetPlaybackMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = PlaybackModeOptions.size,
                        ),
                    ) {
                        Text(stringResource(labelRes))
                    }
                }
            }
        }
    }
}

/** Master volume (0–200 %) plus a mute toggle, which forces the gain to zero. */
@Composable
private fun VolumeControls(
    volumePercent: Int,
    muted: Boolean,
    onSetVolumePercent: (Int) -> Unit,
    onSetMuted: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.volume_label),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.volume_value, volumePercent),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Slider(
                value = volumePercent.toFloat(),
                onValueChange = { onSetVolumePercent(it.roundToInt()) },
                valueRange = 0f..200f,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.mute_label),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.weight(1f))
                Switch(checked = muted, onCheckedChange = onSetMuted)
            }
        }
    }
}

@Composable
private fun Waveform(
    peaks: FloatArray,
    progress: Float,
    waveColor: Color,
    playedColor: Color,
    backgroundColor: Color,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .semantics { contentDescription = "Waveform. Drag to scrub." }
            .pointerInput(peaks) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        onScrub((offset.x / size.width).coerceIn(0f, 1f))
                    },
                ) { change, _ ->
                    onScrub((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(peaks) {
                detectTapGestures { offset ->
                    onScrub((offset.x / size.width).coerceIn(0f, 1f))
                }
            },
    ) {
        val n = peaks.size
        if (n == 0) return@Canvas

        val midY = size.height / 2f
        val slot = size.width / n
        val barWidth = (slot * 0.7f).coerceAtLeast(1f)
        val progressX = progress.coerceIn(0f, 1f) * size.width

        for (i in 0 until n) {
            val x = i * slot + (slot - barWidth) / 2f
            val half = (peaks[i].coerceIn(0f, 1f) * midY).coerceAtLeast(1f)
            drawRect(
                color = if (x <= progressX) playedColor else waveColor,
                topLeft = Offset(x, midY - half),
                size = Size(barWidth, half * 2f),
            )
        }

        drawRect(
            color = playedColor,
            topLeft = Offset(progressX - 1f, 0f),
            size = Size(2f, size.height),
        )
    }
}

@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    val glyphColor = MaterialTheme.colorScheme.onPrimary
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (isPlaying) "Pause" else "Play" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(30.dp)) {
            if (isPlaying) {
                val barWidth = size.width * 0.28f
                val gap = size.width * 0.20f
                drawRect(
                    color = glyphColor,
                    topLeft = Offset(size.width / 2f - gap / 2f - barWidth, 0f),
                    size = Size(barWidth, size.height),
                )
                drawRect(
                    color = glyphColor,
                    topLeft = Offset(size.width / 2f + gap / 2f, 0f),
                    size = Size(barWidth, size.height),
                )
            } else {
                val triangle = Path().apply {
                    moveTo(size.width * 0.1f, 0f)
                    lineTo(size.width, size.height / 2f)
                    lineTo(size.width * 0.1f, size.height)
                    close()
                }
                drawPath(triangle, glyphColor)
            }
        }
    }
}

@Composable
private fun RecordButton(isRecording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = if (isRecording) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) {
        Text(
            text = if (isRecording) "Stop recording" else "Record",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

private val RecordRed = Color(0xFFE53935)

private val PlaybackModeOptions = listOf(
    ScrubPlayer.PlaybackMode.PITCH_PRESERVE to R.string.mode_pitch_preserve,
    ScrubPlayer.PlaybackMode.VARISPEED to R.string.mode_turntable,
)

/** Slider position `[0,1]` → speed: `0.25 * 16^t`, so t=0.5 lands exactly on 1×. */
private fun sliderToSpeed(t: Float): Float =
    (0.25f * 16f.pow(t.coerceIn(0f, 1f))).coerceIn(0.25f, 4f)

/** Inverse of [sliderToSpeed]: speed → slider position `[0,1]`. */
private fun speedToSlider(rate: Float): Float =
    (ln(rate / 0.25f) / ln(16f)).coerceIn(0f, 1f)

/** Format a millisecond position as `M:SS.CC` (centiseconds). */
private fun formatCentis(ms: Long): String {
    val totalCentis = ms.coerceAtLeast(0) / 10
    val minutes = totalCentis / 6000
    val seconds = (totalCentis / 100) % 60
    val centis = totalCentis % 100
    return "%d:%02d.%02d".format(minutes, seconds, centis)
}
