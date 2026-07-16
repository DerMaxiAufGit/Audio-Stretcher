package de.maxihaaser.audioscratch.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
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

    private val lock = Object()

    private var samples: ShortArray = ShortArray(0)
    private var sampleRate: Int = 44_100

    private var track: AudioTrack? = null
    private var worker: Thread? = null

    @Volatile
    private var alive = false

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
     */
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

    /** Stop rendering and release the underlying [AudioTrack]. Reusable via [load]. */
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
    }
}
