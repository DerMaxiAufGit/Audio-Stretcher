package de.maxihaaser.audioscratch.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Captures microphone input as 16-bit PCM mono and streams it into a WAV [File]
 * on a dedicated background thread. A single instance records at most one
 * session at a time; call [start] then [stop].
 */
class AudioRecorder(
    val sampleRate: Int = 44_100,
) {
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    private val recording = AtomicBoolean(false)
    private var worker: Thread? = null

    // Serialises the start()/stop() handshake so the "check recording + reset
    // stopRequested" in start() and the "set stopRequested + clear recording" in
    // stop() are each atomic relative to the other — never held across the blocking
    // MicGate.acquire()/join().
    private val startStopLock = Any()

    // Set by stop() on every call — including when it lands mid-start(), before
    // `recording` has been published. start() re-checks it after each blocking step
    // so a stop() racing an Instant-Replay → record hand-off can't orphan the mic.
    @Volatile
    private var stopRequested = false

    val isRecording: Boolean get() = recording.get()

    /**
     * Start recording into [outFile], overwriting any previous contents. The
     * RECORD_AUDIO permission must already be granted. Returns `true` if capture
     * started, `false` if the recorder could not be initialised.
     */
    @SuppressLint("MissingPermission")
    fun start(outFile: File): Boolean {
        synchronized(startStopLock) {
            if (recording.get()) return false
            stopRequested = false
        }

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            return false
        }
        // Give the read buffer a healthy cushion (~200 ms) over the minimum.
        val bufferBytes = maxOf(minBuffer, sampleRate * 2 / 5)

        // Same-process mic gate: wait for Instant Replay (or any other session) to
        // release the microphone before opening our own AudioRecord. Held until the
        // worker's finally releases it, so every exit path below frees it too.
        if (!MicGate.acquire(GATE_TIMEOUT_MS)) return false
        // stop() may have fired while we blocked on the gate — bail before opening the mic.
        if (stopRequested) {
            MicGate.release()
            return false
        }

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioEncoding,
                bufferBytes,
            )
        } catch (_: Exception) {
            // IllegalArgumentException, or a vendor SecurityException/etc. from mic-privacy enforcement.
            MicGate.release()
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            MicGate.release()
            return false
        }

        recording.set(true)
        // Final check now that `recording` is published: if stop() raced in before the
        // worker exists, tear down here so nothing is left holding the mic/gate.
        if (stopRequested) {
            recording.set(false)
            record.release()
            MicGate.release()
            return false
        }
        worker = thread(name = "AudioRecorder", priority = Thread.MAX_PRIORITY) {
            val buffer = ByteArray(bufferBytes)
            var writer: WavIo.Writer? = null
            try {
                writer = WavIo.Writer(outFile, sampleRate, channels = 1)
                record.startRecording()
                while (recording.get()) {
                    val read = record.read(buffer, 0, buffer.size)
                    when {
                        read > 0 -> writer.writeBytes(buffer, read)
                        read < 0 -> break // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT
                        // read == 0: no data available yet, loop and re-check flag.
                    }
                }
            } catch (_: Throwable) {
                // Stop cleanly; the file keeps whatever was captured so far.
            } finally {
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                    // Already stopped / never started — ignore.
                }
                record.release()
                MicGate.release()
                writer?.close()
            }
        }
        return true
    }

    /**
     * Stop the active session and block until the WAV file is fully flushed and
     * its header patched. Safe to call when not recording.
     */
    fun stop() {
        // Atomic with start()'s handshake: set the stop flag and clear `recording`
        // together, so an in-flight start() either sees the flag on one of its
        // re-checks (and tears itself down) or has already published a worker we join.
        val wasRecording = synchronized(startStopLock) {
            stopRequested = true
            recording.getAndSet(false)
        }
        if (!wasRecording) {
            worker = null
            return
        }
        worker?.join()
        worker = null
    }

    private companion object {
        /** Max wait for the mic gate during an Instant-Replay → record hand-off. */
        const val GATE_TIMEOUT_MS = 800L
    }
}
