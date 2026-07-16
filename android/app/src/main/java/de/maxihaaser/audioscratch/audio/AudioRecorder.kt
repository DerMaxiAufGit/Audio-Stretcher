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

    val isRecording: Boolean get() = recording.get()

    /**
     * Start recording into [outFile], overwriting any previous contents. The
     * RECORD_AUDIO permission must already be granted. Returns `true` if capture
     * started, `false` if the recorder could not be initialised.
     */
    @SuppressLint("MissingPermission")
    fun start(outFile: File): Boolean {
        if (recording.get()) return false

        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioEncoding)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            return false
        }
        // Give the read buffer a healthy cushion (~200 ms) over the minimum.
        val bufferBytes = maxOf(minBuffer, sampleRate * 2 / 5)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioEncoding,
                bufferBytes,
            )
        } catch (_: IllegalArgumentException) {
            return false
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        recording.set(true)
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
        if (!recording.getAndSet(false)) {
            worker = null
            return
        }
        worker?.join()
        worker = null
    }
}
