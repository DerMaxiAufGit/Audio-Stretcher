package de.maxihaaser.audioscratch.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.maxihaaser.audioscratch.R

/**
 * The frame at the playhead, aspect-fit onto black — shown only for a source that
 * has a video track (the caller gates on
 * [de.maxihaaser.audioscratch.ui.RecorderViewModel.hasVideo]).
 *
 * [frame] is whatever the [de.maxihaaser.audioscratch.video.VideoScrubber] last
 * decoded, or `null` before the first one lands, which draws the bare black box
 * rather than collapsing the pane's height.
 *
 * Dragging the picture scrubs by *relative* motion at [SecondsPerPixel] — desktop
 * parity (`VideoView`), and deliberately unlike the waveform, whose x axis maps
 * absolutely onto the clip. The picture is a record to push, not a timeline.
 */
@Composable
fun VideoPane(
    frame: Bitmap?,
    onScrubBySeconds: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(R.string.video_desc)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    // Consume it, or the Ready pane's vertical scroll can steal the
                    // gesture mid-drag and drop the scrub under the finger.
                    change.consume()
                    onScrubBySeconds(dragAmount.toDouble() * SecondsPerPixel)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null) {
            Image(
                bitmap = frame.asImageBitmap(),
                // The Box carries the pane's description; the picture itself has no
                // separate meaning to announce.
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Desktop parity (`VideoView::kSecondsPerPixel`): scratch sensitivity over the picture. */
private const val SecondsPerPixel = 0.006
