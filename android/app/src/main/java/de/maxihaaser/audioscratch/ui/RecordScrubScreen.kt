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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

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
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()
    val playhead by viewModel.playhead.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()

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
            when (val state = uiState) {
                is UiState.Ready -> ReadyContent(
                    state = state,
                    playhead = playhead,
                    isPlaying = isPlaying,
                    onScrub = viewModel::scrub,
                    onTogglePlay = viewModel::togglePlayback,
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

        Spacer(Modifier.height(24.dp))

        RecordButton(
            isRecording = uiState is UiState.Recording,
            onClick = {
                if (uiState is UiState.Recording) viewModel.stopRecording()
                else viewModel.startRecording()
            },
        )
        Spacer(Modifier.height(8.dp))
    }
}

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
private fun ReadyContent(
    state: UiState.Ready,
    playhead: Float,
    isPlaying: Boolean,
    onScrub: (Float) -> Unit,
    onTogglePlay: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatMillis((playhead * state.durationMs).toLong()),
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                text = formatMillis(state.durationMs),
                style = MaterialTheme.typography.labelMedium,
            )
        }

        Spacer(Modifier.height(20.dp))

        PlayPauseButton(isPlaying = isPlaying, onClick = onTogglePlay)
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
private fun RecordButton(isRecording: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
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

private fun formatMillis(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
