package de.maxihaaser.audioscratch.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.maxihaaser.audioscratch.R
import de.maxihaaser.audioscratch.audio.ScrubPlayer
import de.maxihaaser.audioscratch.audio.WaveformPeaks
import kotlin.math.abs
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
    val totalFrames by viewModel.totalFrames.collectAsState()
    val viewWindow by viewModel.viewWindow.collectAsState()

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
                        totalFrames = totalFrames,
                        viewWindow = viewWindow,
                        onScrub = viewModel::scrub,
                        onScrubAtViewNorm = viewModel::scrubAtViewNorm,
                        onZoomBy = viewModel::zoomBy,
                        onZoomToFit = viewModel::zoomToFit,
                        onTransformView = viewModel::transformView,
                        onSetViewStartFrame = viewModel::setViewStartFrame,
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
    totalFrames: Int,
    viewWindow: ViewWindow,
    onScrub: (Float) -> Unit,
    onScrubAtViewNorm: (Float) -> Unit,
    onZoomBy: (Float, Float) -> Unit,
    onZoomToFit: () -> Unit,
    onTransformView: (Float, Float, Float) -> Unit,
    onSetViewStartFrame: (Int) -> Unit,
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
    // The ViewModel holds the window as one value; unpack it here for the widgets
    // that only care about a single bound.
    val viewStartFrame = viewWindow.startFrame
    val viewFrames = viewWindow.frames

    // The transport + DSP controls make this taller than the viewport on small
    // screens, so the whole Ready pane scrolls.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The ruler spans exactly the waveform's window, so both must be laid out
        // to the same width. Seconds are Double: Float would drift the ticks off
        // the waveform on a clip longer than ~379 s.
        TimeRuler(
            startSeconds = viewStartFrame.toDouble() / state.sampleRate,
            visibleSeconds = viewFrames.toDouble() / state.sampleRate,
            modifier = Modifier.fillMaxWidth(),
        )

        Waveform(
            peaks = state.peaks,
            samples = state.samples,
            totalFrames = totalFrames,
            viewStartFrame = viewStartFrame,
            viewFrames = viewFrames,
            progress = playhead,
            waveColor = MaterialTheme.colorScheme.primary,
            playedColor = MaterialTheme.colorScheme.tertiary,
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
            onScrubAtViewNorm = onScrubAtViewNorm,
            onTransformView = onTransformView,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        )

        ZoomControls(
            onZoomOut = { onZoomBy(1f / ZoomStep, 0.5f) },
            onZoomToFit = onZoomToFit,
            onZoomIn = { onZoomBy(ZoomStep, 0.5f) },
        )

        // Only reachable once zoomed in — at fit there is nothing to pan.
        val maxViewStart = (totalFrames - viewFrames).coerceAtLeast(0)
        if (maxViewStart > 0) {
            LabelledSlider(
                label = stringResource(R.string.pan_label),
                value = viewStartFrame.toFloat().coerceIn(0f, maxViewStart.toFloat()),
                valueRange = 0f..maxViewStart.toFloat(),
                onValueChange = { onSetViewStartFrame(it.roundToInt()) },
            )
        }

        Spacer(Modifier.height(8.dp))

        // Distinct from the pan slider above: this one spans the whole clip and
        // seeks, rather than moving the waveform's window.
        LabelledSlider(
            label = stringResource(R.string.position_label),
            value = playhead.coerceIn(0f, 1f),
            valueRange = 0f..1f,
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

/**
 * Min/max envelope of the clip's *visible* window — one column per pixel, drawn
 * from [peaks] (or straight from [samples] when zoomed in past a bucket).
 *
 * [progress] is a whole-clip norm and is re-projected onto the window, as is the
 * played/unplayed split. Scrub positions travel the other way, as window-relative
 * norms via [onScrubAtViewNorm].
 */
@Composable
private fun Waveform(
    peaks: WaveformPeaks,
    samples: ShortArray,
    totalFrames: Int,
    viewStartFrame: Int,
    viewFrames: Int,
    progress: Float,
    waveColor: Color,
    playedColor: Color,
    backgroundColor: Color,
    onScrubAtViewNorm: (Float) -> Unit,
    onTransformView: (Float, Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val columns = remember { WaveformColumns() }
    val description = stringResource(R.string.waveform_desc)

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .semantics { contentDescription = description }
            .pointerInput(peaks) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.toFloat()
                    if (width <= 0f) return@awaitEachGesture
                    val slop = viewConfiguration.touchSlop

                    // One pointer scrubs, two transform. The two are decided here
                    // rather than by stacking gesture detectors, which would race:
                    // whichever claimed the pointer first would swallow the other.
                    var transforming = false // a 2nd finger landed → pinch/pan
                    var scrubbing = false    // 1 finger, horizontal intent confirmed
                    var abandoned = false    // vertical intent → let the pane scroll
                    var dragged = false      // moved at all → not a tap

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        // Latch: once it's a pinch it stays one until every finger
                        // lifts. Falling back to scrub as a finger leaves would
                        // snap the playhead to the surviving finger.
                        if (pressed.size >= 2 && !abandoned) transforming = true

                        if (transforming) {
                            dragged = true
                            if (pressed.size >= 2) {
                                val centroid = event.calculateCentroid()
                                val zoom = event.calculateZoom()
                                val zoomFactor = if (zoom > 0f && zoom.isFinite()) zoom else 1f
                                val panX = event.calculatePan().x
                                if (zoomFactor != 1f || panX != 0f) {
                                    val center = if (centroid == Offset.Unspecified) {
                                        0.5f
                                    } else {
                                        (centroid.x / width).coerceIn(0f, 1f)
                                    }
                                    // Zoom and pan go over together as fractions of the
                                    // view: the ViewModel resolves the pan against its
                                    // own post-zoom window, so the two can't disagree
                                    // about how wide the view is mid-pinch. Content
                                    // follows the fingers — dragging right moves the
                                    // window towards the clip's start.
                                    onTransformView(zoomFactor, -panX / width, center)
                                }
                            }
                            // Claim it so the scrolling pane can't steal a pinch.
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        if (abandoned) continue

                        val change = pressed.first()
                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        if (!scrubbing) {
                            // Vertical drags belong to the Ready pane's scroll: bow
                            // out without consuming so the ancestor picks them up.
                            if (abs(dy) > slop && abs(dy) > abs(dx)) {
                                abandoned = true
                                continue
                            }
                            if (abs(dx) > slop) scrubbing = true
                        }
                        if (scrubbing && change.positionChanged()) {
                            dragged = true
                            onScrubAtViewNorm((change.position.x / width).coerceIn(0f, 1f))
                            change.consume()
                        }
                    }

                    // A tap that never became a drag scrubs where it landed.
                    if (!dragged && !transforming && !abandoned) {
                        onScrubAtViewNorm((down.position.x / width).coerceIn(0f, 1f))
                    }
                }
            },
    ) {
        val width = size.width
        if (width <= 0f || viewFrames <= 0 || totalFrames <= 0) return@Canvas

        val colCount = width.toInt().coerceAtLeast(1)
        columns.ensure(colCount)
        peaks.columns(viewStartFrame, viewFrames, colCount, samples, columns.min, columns.max)

        val midY = size.height / 2f
        // Whole-clip norm → window-relative pixels. The frame math is Double and the
        // window offset is subtracted before anything narrows to Float: Float holds
        // only ~16.7M exactly (~379 s at 44.1 kHz), so a half-hour import would drift
        // the playhead off the waveform when zoomed in near its tail.
        val playheadFrame = progress.coerceIn(0f, 1f).toDouble() * totalFrames
        val playheadX = ((playheadFrame - viewStartFrame) / viewFrames * width).toFloat()

        for (c in 0 until colCount) {
            val top = midY - columns.max[c] * midY
            val bottom = midY - columns.min[c] * midY
            drawRect(
                color = if (c <= playheadX) playedColor else waveColor,
                topLeft = Offset(c.toFloat(), top),
                // Silence would otherwise vanish; keep a 1px centre line.
                size = Size(1f, (bottom - top).coerceAtLeast(1f)),
            )
        }

        if (playheadX >= 0f && playheadX <= width) {
            drawRect(
                color = playedColor,
                topLeft = Offset(playheadX - 1f, 0f),
                size = Size(2f, size.height),
            )
        }
    }
}

/**
 * Scratch buffers for [Waveform]'s per-column envelope, grown to the canvas width
 * and reused across draws — a fresh pair of arrays every frame would hand the GC
 * a few hundred KB a second during a pan.
 */
private class WaveformColumns {
    var min: FloatArray = FloatArray(0)
        private set
    var max: FloatArray = FloatArray(0)
        private set

    fun ensure(size: Int) {
        if (min.size < size) {
            min = FloatArray(size)
            max = FloatArray(size)
        }
    }
}

/** Waveform zoom buttons: out / fit / in, matching the desktop's toolbar. */
@Composable
private fun ZoomControls(
    onZoomOut: () -> Unit,
    onZoomToFit: () -> Unit,
    onZoomIn: () -> Unit,
) {
    val zoomOutDesc = stringResource(R.string.zoom_out_desc)
    val zoomFitDesc = stringResource(R.string.zoom_fit_desc)
    val zoomInDesc = stringResource(R.string.zoom_in_desc)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onZoomOut,
            modifier = Modifier.semantics { contentDescription = zoomOutDesc },
        ) {
            Text(stringResource(R.string.zoom_out), style = MaterialTheme.typography.titleMedium)
        }
        TextButton(
            onClick = onZoomToFit,
            modifier = Modifier.semantics { contentDescription = zoomFitDesc },
        ) {
            Text(stringResource(R.string.zoom_fit))
        }
        TextButton(
            onClick = onZoomIn,
            modifier = Modifier.semantics { contentDescription = zoomInDesc },
        ) {
            Text(stringResource(R.string.zoom_in), style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** A slider with a leading caption — the pane has two, and they do different things. */
@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
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

/** Desktop parity: one zoom button press is 1.3×. */
private const val ZoomStep = 1.3f

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
