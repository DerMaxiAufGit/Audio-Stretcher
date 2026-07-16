package de.maxihaaser.audioscratch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.floor

/**
 * Adaptive time ruler for the waveform's visible window, spanning
 * `[startSeconds, startSeconds + visibleSeconds)` across its full width — so it
 * must be laid out with the same width as the waveform it labels.
 *
 * The bounds are [Double] rather than [Float] because they are derived from frame
 * counts: Float carries only ~16.7M exactly, i.e. ~379 s at 44.1 kHz, so a
 * half-hour clip would drift the ticks visibly off the waveform near its tail.
 *
 * Desktop parity (mirrors the Qt app's `TimeRulerBar`): the tick interval is the
 * smallest "nice" step that keeps labels at least [LabelSpacing] apart, each
 * major tick is labelled and subdivided by [MinorTicksPerMajor] minor ticks, and
 * the label gains decimals as you zoom in, plus an hours field once the position
 * passes an hour.
 */
@Composable
fun TimeRuler(
    startSeconds: Double,
    visibleSeconds: Double,
    modifier: Modifier = Modifier,
) {
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val labelSpacingPx = with(density) { LabelSpacing.toPx() }
    val labelSizePx = with(density) { LabelSize.toPx() }
    val labelArgb = tickColor.toArgb()

    // One Paint for the whole ruler: allocating per draw (let alone per label)
    // would churn on every pan frame.
    val labelPaint = remember(labelArgb, labelSizePx) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = labelArgb
            textSize = labelSizePx
            isSubpixelText = true
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(RulerHeight),
    ) {
        val width = size.width
        if (width <= 0f || visibleSeconds <= 0.0) return@Canvas

        val secondsPerPx = visibleSeconds / width
        val minStep = secondsPerPx * labelSpacingPx
        val step = NiceSteps.firstOrNull { it >= minStep } ?: NiceSteps.last()
        if (step <= 0.0) return@Canvas
        val decimals = decimalsFor(step)
        val minor = step / MinorTicksPerMajor

        val start = startSeconds
        val end = start + visibleSeconds
        val baseline = size.height
        val majorTop = size.height - MajorTickPx
        val minorTop = size.height - MinorTickPx
        val minorColor = tickColor.copy(alpha = MinorTickAlpha)

        // Minor ticks first, so a major tick always draws over its own subdivision.
        var m = floor(start / minor)
        while (true) {
            val t = m * minor
            if (t > end) break
            if (t >= start) {
                val x = ((t - start) / secondsPerPx).toFloat()
                drawLine(
                    color = minorColor,
                    start = Offset(x, minorTop),
                    end = Offset(x, baseline),
                    strokeWidth = 1f,
                )
            }
            m += 1.0
        }

        var k = floor(start / step)
        while (true) {
            val t = k * step
            if (t > end) break
            if (t >= start) {
                val x = ((t - start) / secondsPerPx).toFloat()
                drawLine(
                    color = tickColor,
                    start = Offset(x, majorTop),
                    end = Offset(x, baseline),
                    strokeWidth = 1f,
                )
                // Nudge the first label in from the edge so it isn't clipped.
                val label = formatTime(t, decimals)
                val labelWidth = labelPaint.measureText(label)
                val labelX = (x + LabelPadPx).coerceAtMost(width - labelWidth)
                if (labelX >= 0f) {
                    drawContext.canvas.nativeCanvas.drawText(
                        label,
                        labelX,
                        labelSizePx,
                        labelPaint,
                    )
                }
            }
            k += 1.0
        }
    }
}

/** Ruler height; the waveform sits directly below it. */
private val RulerHeight = 22.dp

/** Desktop parity: pick the step that keeps labels at least this far apart. */
private val LabelSpacing = 72.dp

private val LabelSize = 10.sp
private const val MajorTickPx = 8f
private const val MinorTickPx = 4f
private const val MinorTickAlpha = 0.45f
private const val LabelPadPx = 3f
private const val MinorTicksPerMajor = 5

/** Candidate tick intervals in seconds, mirroring the desktop's `kNiceSteps`. */
private val NiceSteps = listOf(
    0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0,
    60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0, 7200.0, 10800.0, 21600.0,
)

/** Sub-second steps get centiseconds, sub-minute steps tenths, coarser steps none. */
private fun decimalsFor(step: Double): Int = if (step < 0.1) 2 else if (step < 1.0) 1 else 0

/**
 * Format [seconds] as `M:SS`, `M:SS.C` or `M:SS.CC` by [decimals], gaining an
 * `H:MM:SS` hours field once past the hour mark.
 */
private fun formatTime(seconds: Double, decimals: Int): String {
    val s = seconds.coerceAtLeast(0.0)
    val totalMinutes = (s / 60.0).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val secs = s - totalMinutes * 60.0
    // Zero-padded seconds field: "SS" or "SS." plus the decimals.
    val secWidth = if (decimals > 0) decimals + 3 else 2
    return if (hours > 0) {
        "%d:%02d:%0${secWidth}.${decimals}f".format(hours, minutes, secs)
    } else {
        "%d:%0${secWidth}.${decimals}f".format(minutes, secs)
    }
}
