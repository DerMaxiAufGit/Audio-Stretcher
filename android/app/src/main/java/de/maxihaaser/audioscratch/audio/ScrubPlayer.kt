package de.maxihaaser.audioscratch.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import kotlin.concurrent.thread
import kotlin.math.roundToInt

/**
 * In-memory PCM player built on [AudioTrack] in streaming mode. It holds the
 * whole recording as a [ShortArray] and renders from a movable playhead, which
 * lets us play/pause, seek, and "scrub" — play a short grain around a dragged
 * position so the recording reacts like a turntable under the finger.
 *
 * Public methods are safe to call from the main thread. All rendering happens on
 * one dedicated worker thread; the playhead callback is invoked from there.
 */
class ScrubPlayer {

    private enum class Mode { IDLE, PLAYING, SCRUBBING }

    /**
     * How pitch behaves when the transport rate changes. [PITCH_PRESERVE] holds
     * pitch while tempo shifts; [VARISPEED] lets pitch ride the rate like a
     * turntable / vinyl.
     */
    enum class PlaybackMode { PITCH_PRESERVE, VARISPEED }

    private val lock = Object()

    private var samples: ShortArray = ShortArray(0)
    private var sampleRate: Int = 44_100

    private var track: AudioTrack? = null
    private var worker: Thread? = null

    @Volatile
    private var alive = false

    /** Software output gain applied in the render loop; `1f` = unity. */
    @Volatile
    private var gain = 1f

    // Last-requested transport params. Written from the main thread and re-applied
    // whenever [load] rebuilds the track, so settings survive a new recording/clip.
    @Volatile
    private var baseRate = 1f

    @Volatile
    private var pitchRatio = 1f

    @Volatile
    private var playbackMode = PlaybackMode.PITCH_PRESERVE

    // What was last pushed to the track, so redundant applies are skipped. NaN
    // forces the next apply — [load] resets these for the freshly built track.
    private var lastAppliedSpeed = Float.NaN
    private var lastAppliedPitch = Float.NaN

    // --- state guarded by [lock] ---
    private var mode = Mode.IDLE
    private var positionFrames = 0
    private var grainFramesRemaining = 0

    /** Invoked on the worker thread with the current playhead in `[0f, 1f]`. */
    var onPlayhead: ((Float) -> Unit)? = null

    /** Invoked on the worker thread when continuous playback reaches the end. */
    var onCompletion: (() -> Unit)? = null

    val isPlaying: Boolean
        get() = synchronized(lock) { mode == Mode.PLAYING }

    /**
     * Load PCM into the player, replacing any previous data and resetting the
     * playhead to the start. Builds a fresh [AudioTrack] and starts the render
     * thread (which idles silently until [play] or [scrub] is called).
     *
     * Mutually exclusive with [release]: loading runs off the main thread, so a
     * teardown from `onCleared` can land mid-load. Serialising the two stops the
     * fresh track from being built behind a release that has already run (which
     * would leak an [AudioTrack]). The lock is reentrant, so the [release] call
     * below is fine.
     */
    @Synchronized
    fun load(pcm: ShortArray, sampleRate: Int) {
        release()
        this.samples = pcm
        this.sampleRate = sampleRate

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Keep the buffer small so scrubbing has low latency, but never below
        // the hardware minimum.
        val bufferBytes = maxOf(minBuffer, MIN_BUFFER_BYTES)

        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        synchronized(lock) {
            mode = Mode.IDLE
            positionFrames = 0
            grainFramesRemaining = 0
        }
        track = newTrack
        // Re-apply the last-requested transport params to the fresh track before
        // the worker starts rendering, so a reload inherits the current settings.
        // The cache is invalidated first: this is a different track, so the params
        // must be pushed even if they match what the previous track had.
        lastAppliedSpeed = Float.NaN
        lastAppliedPitch = Float.NaN
        applyPlaybackParams()
        startWorker(newTrack)
    }

    /** Start (or resume) continuous playback from the current playhead. */
    fun play() {
        synchronized(lock) {
            if (samples.isEmpty()) return
            if (positionFrames >= samples.size) positionFrames = 0
            mode = Mode.PLAYING
            lock.notifyAll()
        }
    }

    /** Pause playback, leaving the playhead where it is. */
    fun pause() {
        synchronized(lock) {
            if (mode != Mode.IDLE) {
                mode = Mode.IDLE
                lock.notifyAll()
            }
        }
    }

    /** Set the software output gain (`0f`..`2f`; `0f` = muted). Applied live. */
    fun setGain(g: Float) {
        gain = g.coerceIn(0f, 2f)
    }

    /**
     * Set the transport speed and pitch. In [PlaybackMode.PITCH_PRESERVE] pitch is
     * held while the base rate changes tempo; in [PlaybackMode.VARISPEED] pitch
     * rides the base rate like a turntable. The request is stored and re-applied
     * after [load] rebuilds the track.
     */
    fun setPlaybackParams(baseRate: Float, pitchRatio: Float, mode: PlaybackMode) {
        this.baseRate = baseRate.coerceIn(MIN_RATE, MAX_RATE)
        this.pitchRatio = pitchRatio
        this.playbackMode = mode
        applyPlaybackParams()
    }

    private fun applyPlaybackParams() {
        val t = track ?: return
        val trackSpeed = baseRate
        // Pitch gets its own bounds: the ±36 semitone / ±100 cent range reaches a
        // ratio of ~0.117..8.51, far outside the transport's 0.25..4 rate limits.
        // Clamping pitch to the rate bounds would silently stall the slider at ±24 st.
        val trackPitch = when (playbackMode) {
            PlaybackMode.PITCH_PRESERVE -> pitchRatio
            PlaybackMode.VARISPEED -> baseRate * pitchRatio
        }.coerceIn(MIN_PITCH, MAX_PITCH)
        // Skip redundant native reconfigures: a continuous slider drag emits many
        // ticks that resolve to identical params, and each apply is a JNI call into
        // the audio framework that can zipper the time-stretcher mid-sweep.
        if (trackSpeed == lastAppliedSpeed && trackPitch == lastAppliedPitch) return
        try {
            // Build a fresh instance so a shared PlaybackParams is never mutated.
            t.playbackParams = PlaybackParams()
                .allowDefaults()
                .setSpeed(trackSpeed)
                .setPitch(trackPitch)
            lastAppliedSpeed = trackSpeed
            lastAppliedPitch = trackPitch
        } catch (_: IllegalArgumentException) {
            // Device rejected this speed/pitch — keep the previous params.
        } catch (_: IllegalStateException) {
            // Track uninitialised (e.g. mid-release) — keep the previous params.
        }
    }

    /** Move the playhead to [norm] in `[0f, 1f]` without starting playback. */
    fun seekTo(norm: Float) {
        val emit: Float
        synchronized(lock) {
            if (samples.isEmpty()) return
            positionFrames = frameFor(norm)
            emit = norm.coerceIn(0f, 1f)
        }
        onPlayhead?.invoke(emit)
    }

    /**
     * Jump to [norm] and play a short grain from there. Repeated calls while a
     * finger drags produce a scratching feel. Cancels continuous playback.
     */
    fun scrub(norm: Float) {
        synchronized(lock) {
            if (samples.isEmpty()) return
            positionFrames = frameFor(norm)
            grainFramesRemaining = (sampleRate * GRAIN_SECONDS).roundToInt().coerceAtLeast(1)
            mode = Mode.SCRUBBING
            lock.notifyAll()
        }
    }

    /**
     * Stop rendering and release the underlying [AudioTrack]. Reusable via [load].
     * Serialised against [load] so a teardown can't interleave with a load that is
     * building a new track on another thread.
     */
    @Synchronized
    fun release() {
        val toJoin: Thread?
        synchronized(lock) {
            alive = false
            mode = Mode.IDLE
            toJoin = worker
            lock.notifyAll()
        }
        toJoin?.join()
        worker = null

        track?.let { t ->
            try {
                t.pause()
                t.flush()
                t.stop()
            } catch (_: IllegalStateException) {
                // Uninitialised track — nothing to stop.
            }
            t.release()
        }
        track = null

        synchronized(lock) {
            samples = ShortArray(0)
            positionFrames = 0
            grainFramesRemaining = 0
        }
    }

    private fun startWorker(t: AudioTrack) {
        alive = true
        worker = thread(name = "ScrubPlayer", priority = Thread.MAX_PRIORITY) {
            t.play()
            val chunk = ShortArray(CHUNK_FRAMES)
            while (alive) {
                var toWrite = 0
                var emitPos = 0f
                var completed = false

                synchronized(lock) {
                    while (alive && mode == Mode.IDLE) {
                        lock.wait()
                    }
                    if (!alive) return@thread

                    val total = samples.size
                    if (total == 0) {
                        mode = Mode.IDLE
                    } else {
                        val remaining = total - positionFrames
                        if (remaining <= 0) {
                            if (mode == Mode.PLAYING) completed = true
                            mode = Mode.IDLE
                            positionFrames = total
                        } else {
                            var n = minOf(chunk.size, remaining)
                            if (mode == Mode.SCRUBBING) {
                                n = minOf(n, grainFramesRemaining)
                            }
                            System.arraycopy(samples, positionFrames, chunk, 0, n)
                            positionFrames += n
                            if (mode == Mode.SCRUBBING) {
                                grainFramesRemaining -= n
                                if (grainFramesRemaining <= 0) mode = Mode.IDLE
                            }
                            toWrite = n
                            emitPos = (positionFrames.toFloat() / total).coerceIn(0f, 1f)
                        }
                    }
                }

                if (toWrite > 0) {
                    val g = gain
                    if (g != 1f) {
                        // Apply output gain in place; soft-clip to the 16-bit range.
                        for (i in 0 until toWrite) {
                            chunk[i] = (chunk[i] * g).toInt().coerceIn(-32768, 32767).toShort()
                        }
                    }
                    // Blocking write in MODE_STREAM paces playback to real time.
                    t.write(chunk, 0, toWrite)
                    onPlayhead?.invoke(emitPos)
                }
                if (completed) {
                    onCompletion?.invoke()
                }
            }
        }
    }

    private fun frameFor(norm: Float): Int {
        val total = samples.size
        if (total == 0) return 0
        return (norm.coerceIn(0f, 1f) * total).roundToInt().coerceIn(0, total)
    }

    private companion object {
        const val CHUNK_FRAMES = 1024
        const val MIN_BUFFER_BYTES = 4096
        const val GRAIN_SECONDS = 0.09f
        const val MIN_RATE = 0.25f
        const val MAX_RATE = 4f

        // Pitch bounds cover the full ±36 semitone / ±100 cent range
        // (2^(±3700/1200) ≈ 0.117 .. 8.51) — deliberately wider than the rate
        // bounds. A device that rejects an extreme value trips the catch below
        // and keeps the previous params.
        const val MIN_PITCH = 0.11f
        const val MAX_PITCH = 8.6f
    }
}
