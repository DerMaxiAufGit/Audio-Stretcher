package de.maxihaaser.audioscratch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.maxihaaser.audioscratch.R
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The A/B loop region and the clip's markers, drawn over the waveform's *visible*
 * window — so it must be laid out with the same width as the waveform and the
 * [TimeRuler], and fed the same [viewWindow]. Sits directly below the waveform.
 *
 * Everything here maps frames to pixels through [viewWindow] in [Double], for the
 * same reason the ruler does: Float carries only ~16.7M exactly (~379 s at
 * 44.1 kHz), so a long clip would slide the loop shading off the waveform it
 * describes.
 *
 * Desktop parity (mirrors the Qt app's `LoopMarkerBar`): the region is shaded
 * green while armed and grey while not, both edges are draggable handles, and
 * markers are golden flags labelled to their right. Touch replaces the desktop's
 * double-click-to-rename / right-click-menu with a long-press menu.
 */
@Composable
fun LoopMarkerBar(
    viewWindow: ViewWindow,
    loop: LoopRegion,
    markers: List<Marker>,
    onSetLoopBeginFrame: (Int) -> Unit,
    onSetLoopEndFrame: (Int) -> Unit,
    onSeekToFrame: (Int) -> Unit,
    onJumpToMarker: (Long) -> Unit,
    onRenameMarker: (Long, String) -> Unit,
    onRemoveMarker: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.loop_marker_bar_desc)
    val density = LocalDensity.current
    val labelSizePx = with(density) { LabelSize.toPx() }

    // One Paint for the whole bar, like the ruler's: a fresh one per label would
    // churn on every pan frame.
    val labelPaint = remember(labelSizePx) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = MarkerGold.toArgb()
            textSize = labelSizePx
            isSubpixelText = true
        }
    }
    val handleLabelPaint = remember(labelSizePx) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = labelSizePx
            isSubpixelText = true
        }
    }

    // The long-press menu's target: the marker's id plus where to hang the menu.
    var menu by remember { mutableStateOf<MenuAnchor?>(null) }
    // The marker being renamed, addressed by id — an index would rot the moment a
    // marker is inserted before it, and a captured Marker would go stale on edit.
    var renamingId by remember { mutableStateOf<Long?>(null) }

    // The gesture handler outlives any single value it reads, so the inputs are
    // taken through rememberUpdatedState rather than captured. Keying pointerInput
    // on them instead would cancel an in-flight handle drag every time that drag
    // pushed a new loop back down.
    val currentWindow by rememberUpdatedState(viewWindow)
    val currentLoop by rememberUpdatedState(loop)
    val currentMarkers by rememberUpdatedState(markers)
    val currentOnSetBegin by rememberUpdatedState(onSetLoopBeginFrame)
    val currentOnSetEnd by rememberUpdatedState(onSetLoopEndFrame)
    val currentOnSeek by rememberUpdatedState(onSeekToFrame)
    val currentOnJump by rememberUpdatedState(onJumpToMarker)

    Box(modifier = modifier.height(BarHeight)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val width = size.width.toFloat()
                        if (width <= 0f) return@awaitEachGesture
                        val window = currentWindow
                        if (window.frames <= 0) return@awaitEachGesture
                        val slop = viewConfiguration.touchSlop

                        // What this gesture is about is decided once, from where it
                        // started: a later wobble must not hand a half-finished handle
                        // drag over to a flag it happened to pass under.
                        val target = targetAt(
                            x = down.position.x,
                            window = window,
                            loop = currentLoop,
                            markers = currentMarkers,
                            width = width,
                            handleSlopPx = HandleSlop.toPx(),
                            markerSlopPx = MarkerSlop.toPx(),
                        ) ?: return@awaitEachGesture // empty bar — let the pane scroll

                        if (target is BarTarget.Flag) {
                            // Flags aren't draggable, so the only question is tap vs
                            // hold. withTimeoutOrNull here is AwaitPointerEventScope's
                            // own overload: it yields null when the finger neither
                            // moved nor lifted before the long-press timeout.
                            val lifted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                awaitTapOrMove(down, slop)
                            }
                            when (lifted) {
                                null -> {
                                    menu = MenuAnchor(
                                        markerId = target.id,
                                        x = down.position.x.toDp(),
                                    )
                                    // The menu now owns this gesture; swallow the rest
                                    // so the lift doesn't also register as a tap.
                                    consumeUntilUp()
                                }
                                // A tap on a flag jumps the playhead to it.
                                true -> currentOnJump(target.id)
                                // Moved off: not a tap, and nothing here drags.
                                false -> Unit
                            }
                            return@awaitEachGesture
                        }

                        val isBegin = target is BarTarget.LoopA
                        var dragging = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val dx = change.position.x - down.position.x
                            val dy = change.position.y - down.position.y
                            if (!dragging) {
                                // Vertical intent belongs to the Ready pane's scroll:
                                // bow out without consuming so the ancestor gets it.
                                if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture
                                if (abs(dx) > slop) dragging = true
                            }
                            if (dragging && change.positionChanged()) {
                                // The *live* window, not the one the press hit-tested
                                // against: playhead-follow can re-centre the view
                                // mid-drag, and the finger must keep meaning the frame
                                // that is under it now.
                                val frame = frameForX(change.position.x, currentWindow, width)
                                if (isBegin) currentOnSetBegin(frame) else currentOnSetEnd(frame)
                                // Claim it so the scrolling pane can't steal the drag.
                                change.consume()
                            }
                        }
                        // A handle that was pressed but never dragged jumps the
                        // playhead to itself.
                        if (!dragging) {
                            val l = currentLoop
                            currentOnSeek(if (isBegin) l.beginFrame else l.endFrame)
                        }
                    }
                },
        ) {
            val width = size.width
            if (width <= 0f || viewWindow.frames <= 0) return@Canvas

            val pad = StripPad.toPx()
            val top = pad
            val bottom = size.height - pad
            if (bottom <= top) return@Canvas

            // Desktop parity: a degenerate region has no handles to grab and nothing
            // to shade, so it isn't drawn at all — which is also exactly when
            // [targetAt] refuses to hit-test them.
            if (loop.endFrame > loop.beginFrame) {
                val ax = xForFrame(loop.beginFrame, viewWindow, width)
                val bx = xForFrame(loop.endFrame, viewWindow, width)
                // Only the part of the region that intersects the window is painted;
                // either bound may sit far outside it while zoomed in.
                val left = ax.coerceIn(0f, width)
                val right = bx.coerceIn(0f, width)
                if (right > left) {
                    drawRect(
                        color = if (loop.enabled) LoopFillEnabled else LoopFillDisabled,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                    )
                }
                val edge = if (loop.enabled) LoopEdgeEnabled else LoopEdgeDisabled
                handleLabelPaint.color = edge.toArgb()
                val knobWidth = HandleKnobWidth.toPx()
                // Each edge is drawn only when it's actually on screen.
                if (ax >= 0f && ax <= width) {
                    drawRect(
                        color = edge,
                        topLeft = Offset(ax - EdgeWidthPx / 2f, top),
                        size = Size(EdgeWidthPx, bottom - top),
                    )
                    // A grab affordance: the desktop's 2px line is a fine mouse
                    // target, but a finger needs something it can see it has hit.
                    drawRect(
                        color = edge,
                        topLeft = Offset(ax - knobWidth / 2f, bottom - knobWidth),
                        size = Size(knobWidth, knobWidth),
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        "A",
                        ax + HandleLabelPadPx,
                        top + labelSizePx,
                        handleLabelPaint,
                    )
                }
                if (bx >= 0f && bx <= width) {
                    drawRect(
                        color = edge,
                        topLeft = Offset(bx - EdgeWidthPx / 2f, top),
                        size = Size(EdgeWidthPx, bottom - top),
                    )
                    drawRect(
                        color = edge,
                        topLeft = Offset(bx - knobWidth / 2f, bottom - knobWidth),
                        size = Size(knobWidth, knobWidth),
                    )
                    drawContext.canvas.nativeCanvas.drawText(
                        "B",
                        bx - HandleLabelPadPx - handleLabelPaint.measureText("B"),
                        top + labelSizePx,
                        handleLabelPaint,
                    )
                }
            }

            val flagWidth = FlagWidth.toPx()
            val flagHeight = FlagHeight.toPx()
            val flagLabelPad = FlagLabelPad.toPx()
            for (marker in markers) {
                val mx = xForFrame(marker.frame, viewWindow, width)
                if (mx < 0f || mx > width) continue
                // A pole with the flag hanging off its right, like the desktop's.
                drawRect(
                    color = MarkerGold,
                    topLeft = Offset(mx, top),
                    size = Size(1f, bottom - top),
                )
                drawRect(
                    color = MarkerGold,
                    topLeft = Offset(mx, top),
                    size = Size(flagWidth, flagHeight),
                )
                if (marker.label.isNotEmpty()) {
                    drawContext.canvas.nativeCanvas.drawText(
                        marker.label,
                        mx + flagLabelPad,
                        bottom - FlagLabelBaselinePx,
                        labelPaint,
                    )
                }
            }
        }

        menu?.let { anchor ->
            DropdownMenu(
                expanded = true,
                onDismissRequest = { menu = null },
                offset = DpOffset(anchor.x, 0.dp),
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.marker_rename)) },
                    onClick = {
                        renamingId = anchor.markerId
                        menu = null
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.marker_delete)) },
                    onClick = {
                        onRemoveMarker(anchor.markerId)
                        menu = null
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.marker_jump)) },
                    onClick = {
                        onJumpToMarker(anchor.markerId)
                        menu = null
                    },
                )
            }
        }
    }

    // Resolved from the live list, so a marker deleted from under the dialog simply
    // closes it rather than renaming a ghost.
    val renaming = markers.firstOrNull { it.id == renamingId }
    if (renaming != null) {
        MarkerRenameDialog(
            initialLabel = renaming.label,
            onConfirm = { label ->
                onRenameMarker(renaming.id, label)
                renamingId = null
            },
            onDismiss = { renamingId = null },
        )
    }
}

/** Where a long-press menu hangs: which marker it acts on, and its x in the bar. */
private data class MenuAnchor(val markerId: Long, val x: Dp)

/** What a press on the bar landed on. */
private sealed interface BarTarget {
    data object LoopA : BarTarget
    data object LoopB : BarTarget
    data class Flag(val id: Long) : BarTarget
}

/**
 * Hit-test a press at [x]. Whichever candidate is genuinely nearest the finger
 * wins — handles and flags are weighed against each other rather than handles
 * taking precedence. Preferring a handle whenever one was merely *within* slop
 * made a marker sitting inside that radius impossible to tap at all (there was
 * no x that resolved to it), so it could no longer be jumped to, renamed, or
 * deleted. Handles are only testable when the region is non-degenerate — i.e.
 * exactly when it's drawn — so a cleared loop's two invisible handles, stacked
 * at frame 0, can't swallow presses meant for a marker there.
 */
private fun targetAt(
    x: Float,
    window: ViewWindow,
    loop: LoopRegion,
    markers: List<Marker>,
    width: Float,
    handleSlopPx: Float,
    markerSlopPx: Float,
): BarTarget? {
    var best: BarTarget? = null
    var bestDistance = Float.MAX_VALUE
    if (loop.endFrame > loop.beginFrame) {
        val da = abs(x - xForFrame(loop.beginFrame, window, width))
        val db = abs(x - xForFrame(loop.endFrame, window, width))
        // Nearest of the two when both are in reach, so a tight loop stays editable.
        if (da <= handleSlopPx && da < bestDistance) {
            best = BarTarget.LoopA
            bestDistance = da
        }
        if (db <= handleSlopPx && db < bestDistance) {
            best = BarTarget.LoopB
            bestDistance = db
        }
    }
    for (marker in markers) {
        val distance = abs(x - xForFrame(marker.frame, window, width))
        if (distance <= markerSlopPx && distance < bestDistance) {
            best = BarTarget.Flag(marker.id)
            bestDistance = distance
        }
    }
    return best
}

/** Absolute [frame] → x within the visible window. See the class note on Double. */
private fun xForFrame(frame: Int, window: ViewWindow, width: Float): Float {
    if (window.frames <= 0) return 0f
    return ((frame.toDouble() - window.startFrame) / window.frames * width).toFloat()
}

/** x within the visible window → absolute frame, clamped to what the bar shows. */
private fun frameForX(x: Float, window: ViewWindow, width: Float): Int {
    if (width <= 0f || window.frames <= 0) return window.startFrame
    val clamped = x.coerceIn(0f, width).toDouble()
    val frame = window.startFrame + clamped / width * window.frames
    return frame.roundToLong()
        .coerceIn(window.startFrame.toLong(), (window.startFrame + window.frames).toLong())
        .toInt()
}

/**
 * Wait out a press that hasn't moved yet: `true` once it lifts (a tap), `false`
 * as soon as it travels past [slop] (so it was never a tap). Callers race this
 * against the long-press timeout.
 */
private suspend fun AwaitPointerEventScope.awaitTapOrMove(
    down: PointerInputChange,
    slop: Float,
): Boolean {
    while (true) {
        val event = awaitPointerEvent()
        val change = event.changes.firstOrNull { it.id == down.id } ?: return false
        if (!change.pressed) return true
        if ((change.position - down.position).getDistance() > slop) return false
    }
}

/** Swallow the rest of the gesture, so a claimed press can't also read as a tap. */
private suspend fun AwaitPointerEventScope.consumeUntilUp() {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        if (event.changes.none { it.pressed }) return
    }
}

/** Rename dialog for a marker. An empty label is allowed — it just draws no text. */
@Composable
private fun MarkerRenameDialog(
    initialLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Keyed on the label so reopening the dialog on a different marker starts from
    // that marker's text rather than the last one's.
    var text by remember(initialLabel) { mutableStateOf(initialLabel) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.marker_rename)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(stringResource(R.string.marker_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
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

/** Desktop parity: the bar's strip is 64px tall there. */
private val BarHeight = 64.dp
private val StripPad = 2.dp

/**
 * Touch targets, widened from the desktop's mouse tolerances (6px handles / 5px
 * flags) — a fingertip can't aim at a 2px line.
 */
private val HandleSlop = 24.dp
private val MarkerSlop = 16.dp
private val HandleKnobWidth = 10.dp

private val FlagWidth = 6.dp
private val FlagHeight = 8.dp
private val FlagLabelPad = 8.dp
private val LabelSize = 10.sp
private const val EdgeWidthPx = 2f
private const val HandleLabelPadPx = 3f
private const val FlagLabelBaselinePx = 3f

// Desktop parity, straight from the Qt bar's painter: the alphas are Qt's 0..255
// (60 and 40), i.e. ~24 % and ~16 %, not 60 % and 40 %.
private val LoopFillEnabled = Color(0x3C5AC878)
private val LoopFillDisabled = Color(0x288C8C96)
private val LoopEdgeEnabled = Color(0xFF78E696)
private val LoopEdgeDisabled = Color(0xFF9696A0)
private val MarkerGold = Color(0xFFF0C85A)
