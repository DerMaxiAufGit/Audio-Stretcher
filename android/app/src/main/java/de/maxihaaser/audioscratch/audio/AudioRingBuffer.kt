package de.maxihaaser.audioscratch.audio

/**
 * Fixed-capacity ring buffer of mono 16-bit PCM samples. Callers keep pushing the
 * newest microphone frames in via [write]; once the buffer is full the oldest
 * samples are overwritten, so it always holds (at most) the last [capacity]
 * samples of audio — the "rolling buffer" behind Instant Replay.
 *
 * Thread-safe: the capture thread writes while the service/UI thread takes a
 * [snapshot]. All mutating state is guarded by intrinsic locking.
 */
class AudioRingBuffer(capacitySamples: Int) {

    /** Capacity in samples (= seconds * sampleRate). */
    val capacity: Int = capacitySamples.coerceAtLeast(1)

    private val buffer = ShortArray(capacity)

    // Index where the next written sample will go.
    private var writePos = 0

    // Number of valid samples currently stored (< capacity until first wrap).
    private var filled = 0

    /** Number of samples currently available (0..[capacity]). */
    val size: Int
        @Synchronized get() = filled

    /**
     * Append the first [len] samples of [data], overwriting the oldest samples
     * once the buffer is full. A chunk larger than [capacity] keeps only its last
     * [capacity] samples.
     */
    @Synchronized
    fun write(data: ShortArray, len: Int) {
        if (len <= 0) return

        var src = 0
        var count = len
        if (count > capacity) {
            // Only the tail can survive; skip the part that would be overwritten.
            src = count - capacity
            count = capacity
        }

        var remaining = count
        while (remaining > 0) {
            val space = capacity - writePos
            val n = minOf(space, remaining)
            System.arraycopy(data, src, buffer, writePos, n)
            writePos = (writePos + n) % capacity
            src += n
            remaining -= n
        }
        filled = minOf(filled + count, capacity)
    }

    /**
     * Return up to [capacity] valid samples in chronological (oldest-first)
     * order. Produces a fresh array each call; empty until the first [write].
     */
    @Synchronized
    fun snapshot(): ShortArray {
        val out = ShortArray(filled)
        if (filled == 0) return out
        // The oldest valid sample sits `filled` positions behind the write head.
        val start = (writePos - filled + capacity) % capacity
        val firstRun = minOf(filled, capacity - start)
        System.arraycopy(buffer, start, out, 0, firstRun)
        if (firstRun < filled) {
            System.arraycopy(buffer, 0, out, firstRun, filled - firstRun)
        }
        return out
    }

    /** Drop all buffered samples. */
    @Synchronized
    fun clear() {
        writePos = 0
        filled = 0
    }
}
