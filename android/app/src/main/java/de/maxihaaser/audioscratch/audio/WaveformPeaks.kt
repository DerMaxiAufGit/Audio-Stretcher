package de.maxihaaser.audioscratch.audio

/**
 * Min/max peak envelope index over 16-bit PCM, used to draw the waveform.
 *
 * Desktop parity: the waveform is a min/max envelope with one column per pixel,
 * computed for the *visible* window rather than the whole clip. Two strategies
 * back that up, picked per draw by [columns]:
 *
 *  - **Zoomed out** (`framesPerPixel >= [bucketFrames]`): aggregate the
 *    precomputed per-bucket extremes, cutting the reads per draw by a factor of
 *    [bucketFrames] (a 10-minute clip at fit is ~413k bucket reads rather than
 *    ~26M sample reads — still the most expensive draw, so it is worth keeping
 *    the buckets coarse).
 *  - **Zoomed in**: scan the raw PCM, since a bucket would then be wider than a
 *    column and would smear the detail we zoomed in to see.
 *
 * The index is built once per load (cheap: one pass over the samples) and is
 * immutable afterwards, so drawing never touches the audio thread's data
 * beyond reading the shared [ShortArray].
 */
class WaveformPeaks(samples: ShortArray, bucketFrames: Int = DEFAULT_BUCKET_FRAMES) {

    // NB: inside these initializers a bare `bucketFrames` is the constructor
    // parameter, not the property below — hence the explicit `this.`.

    /** Frames summarised by one bucket. */
    val bucketFrames: Int = bucketFrames.coerceAtLeast(1)

    /** Frame count of the indexed audio. */
    val totalFrames: Int = samples.size

    /** Number of buckets; 0 for empty input, and the last may be a partial bucket. */
    val count: Int = (samples.size + this.bucketFrames - 1) / this.bucketFrames

    /** Per-bucket minimum, normalised to `-1f..1f`. */
    val min: FloatArray = FloatArray(count)

    /** Per-bucket maximum, normalised to `-1f..1f`. */
    val max: FloatArray = FloatArray(count)

    init {
        val bf = this.bucketFrames
        for (b in 0 until count) {
            val start = b * bf
            val end = minOf(start + bf, samples.size)
            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            var i = start
            while (i < end) {
                val v = samples[i].toInt()
                if (v < lo) lo = v
                if (v > hi) hi = v
                i++
            }
            min[b] = lo / SHORT_SCALE
            max[b] = hi / SHORT_SCALE
        }
    }

    /**
     * Fill [outMin] / [outMax] with the per-column envelope of the window
     * `[startFrame, startFrame + visibleFrames)` of [samples], one entry per
     * column for [columns] columns (i.e. one per pixel of the waveform).
     *
     * Results are written into the caller's arrays — this runs on every draw, so
     * it must not allocate. Cost is O(visibleFrames) on the direct path and
     * O(visibleFrames / [bucketFrames]) on the bucket path.
     *
     * [samples] must be the same array the index was built from. Indices are
     * clamped; columns that fall past the end of the audio come back as `0/0`.
     */
    fun columns(
        startFrame: Int,
        visibleFrames: Int,
        columns: Int,
        samples: ShortArray,
        outMin: FloatArray,
        outMax: FloatArray,
    ) {
        val n = minOf(columns, outMin.size, outMax.size)
        if (n <= 0) return
        for (c in 0 until n) {
            outMin[c] = 0f
            outMax[c] = 0f
        }

        val total = samples.size
        if (total == 0 || visibleFrames <= 0) return

        val start = startFrame.coerceIn(0, total)
        val visible = visibleFrames.coerceAtMost(total - start)
        if (visible <= 0) return

        // framesPerPixel >= bucketFrames, without the float round-trip.
        val useBuckets = count > 0 && visible >= n.toLong() * this.bucketFrames

        for (c in 0 until n) {
            // Long math: columns * frames overflows Int on long clips.
            val f0 = start + (c.toLong() * visible / n).toInt()
            if (f0 >= total) continue
            // At extreme zoom several columns share one frame; widen the empty
            // range to that single frame (sample-and-hold) instead of drawing a
            // comb of zero-height columns.
            val f1 = (start + ((c + 1).toLong() * visible / n).toInt())
                .coerceIn(f0 + 1, total)

            if (useBuckets) {
                // Buckets covering [f0, f1); ceil the end so a column never drops
                // the tail of its range.
                val b0 = (f0 / this.bucketFrames).coerceIn(0, count - 1)
                val b1 = ((f1 + this.bucketFrames - 1) / this.bucketFrames).coerceIn(b0 + 1, count)
                var loF = Float.MAX_VALUE
                var hiF = -Float.MAX_VALUE
                var b = b0
                while (b < b1) {
                    val mn = min[b]
                    val mx = max[b]
                    if (mn < loF) loF = mn
                    if (mx > hiF) hiF = mx
                    b++
                }
                outMin[c] = loF
                outMax[c] = hiF
                continue
            }

            var lo = Int.MAX_VALUE
            var hi = Int.MIN_VALUE
            var i = f0
            while (i < f1) {
                val v = samples[i].toInt()
                if (v < lo) lo = v
                if (v > hi) hi = v
                i++
            }
            outMin[c] = lo / SHORT_SCALE
            outMax[c] = hi / SHORT_SCALE
        }
    }

    companion object {
        /** Desktop parity: one bucket per 64 frames. */
        const val DEFAULT_BUCKET_FRAMES = 64

        /** `abs(Short.MIN_VALUE)`, so the result stays inside `-1f..1f`. */
        private const val SHORT_SCALE = 32768f
    }
}
