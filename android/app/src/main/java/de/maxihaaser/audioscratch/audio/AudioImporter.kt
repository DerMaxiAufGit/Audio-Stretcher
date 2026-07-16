package de.maxihaaser.audioscratch.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * Decodes an arbitrary audio (or video-with-audio) file, referenced by a
 * `content://` [Uri], into the canonical format the rest of the app works in:
 * mono, 44_100 Hz, 16-bit signed PCM ([ShortArray]).
 *
 * Built entirely on the platform [MediaExtractor] / [MediaCodec] stack, so it
 * handles every container/codec the device can decode — mp3, m4a/aac, ogg, flac,
 * wav, and the audio track of mp4/mkv/webm/mov videos — with no extra deps. The
 * first track whose MIME starts with `audio/` wins, so a picked video decodes
 * to just its soundtrack.
 */
object AudioImporter {

    /** Target sample rate of the whole app's audio pipeline. */
    private const val TARGET_SAMPLE_RATE = 44_100

    /**
     * Hard ceiling on decoded length: ~10 min of mono@44.1k. Applied to both the
     * source-rate accumulation and the resampled result so a huge file can never
     * OOM — decoding simply stops once the cap is reached.
     */
    private const val MAX_SAMPLES = 10 * 60 * TARGET_SAMPLE_RATE // 26_460_000

    /** How long to block waiting on a codec buffer before re-checking, in µs. */
    private const val DEQUEUE_TIMEOUT_US = 10_000L

    private const val MAX_16BIT = 32_767f
    private const val MIN_16BIT = -32_768f

    /**
     * Decode [uri] to mono 44_100 Hz 16-bit PCM. Blocking and CPU-heavy — call it
     * off the main thread. Throws [IllegalArgumentException] when the file has no
     * decodable audio track, or [IOException]/[IllegalStateException] (with a
     * descriptive message) when the source cannot be opened or the codec fails.
     *
     * [isActive] is polled once per decode iteration; return `false` from it (e.g.
     * the coroutine's `isActive`) to abort a long decode — a [CancellationException]
     * is thrown so the caller can distinguish cancellation from a real failure.
     */
    fun decodeToMono44100(
        context: Context,
        uri: Uri,
        isActive: () -> Boolean = { true },
    ): ShortArray {
        val afd = context.contentResolver.openAssetFileDescriptor(uri, "r")
            ?: throw IOException("Cannot open the selected file")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)

            val trackIndex = firstAudioTrack(extractor)
                ?: throw IllegalArgumentException("No audio track found in file")
            extractor.selectTrack(trackIndex)

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("No audio track found in file")

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            // Real PCM format the codec emits; the input format is the starting
            // guess, refined once INFO_OUTPUT_FORMAT_CHANGED arrives.
            var sampleRate = inputFormat.getIntOr(MediaFormat.KEY_SAMPLE_RATE, TARGET_SAMPLE_RATE)
            var channels = inputFormat.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT

            val mono = ShortAccumulator()
            val bufferInfo = MediaCodec.BufferInfo()
            var sawInputEos = false
            var sawOutputEos = false

            while (!sawOutputEos && mono.size < MAX_SAMPLES) {
                // Cooperative cancellation: bail out of a long decode promptly.
                if (!isActive()) throw CancellationException("Decode cancelled")
                if (!sawInputEos) {
                    val inIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inBuffer = decoder.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(inBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            sawInputEos = true
                        } else {
                            decoder.queueInputBuffer(
                                inIndex, 0, sampleSize, extractor.sampleTime, 0,
                            )
                            extractor.advance()
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)
                when {
                    outIndex >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outBuffer = decoder.getOutputBuffer(outIndex)!!
                            outBuffer.position(bufferInfo.offset)
                            outBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            appendMono(
                                outBuffer, bufferInfo.size, channels, pcmEncoding, mono, MAX_SAMPLES,
                            )
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            sawOutputEos = true
                        }
                    }

                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFormat = decoder.outputFormat
                        sampleRate = outFormat.getIntOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = outFormat.getIntOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        pcmEncoding = outFormat.getIntOr(
                            MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT,
                        )
                    }
                    // INFO_TRY_AGAIN_LATER / deprecated INFO_OUTPUT_BUFFERS_CHANGED:
                    // nothing ready this pass, just loop.
                }
            }

            return resampleLinear(mono.backing, mono.size, sampleRate, TARGET_SAMPLE_RATE, MAX_SAMPLES)
        } finally {
            codec?.release()
            extractor.release()
            afd.close()
        }
    }

    /** Index of the first track whose MIME starts with `audio/`, or `null` if none. */
    private fun firstAudioTrack(extractor: MediaExtractor): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return i
        }
        return null
    }

    /**
     * Downmix one PCM output buffer to mono and append it to [out], stopping early
     * once [cap] samples have been collected. Reads 16-bit or float PCM, chosen by
     * [pcmEncoding]; floats are scaled to 16-bit with clamping.
     */
    private fun appendMono(
        buffer: ByteBuffer,
        size: Int,
        channels: Int,
        pcmEncoding: Int,
        out: ShortAccumulator,
        cap: Int,
    ) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val ch = channels.coerceAtLeast(1)
        if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val floats = buffer.asFloatBuffer()
            val frames = (size / 4) / ch
            var f = 0
            while (f < frames && out.size < cap) {
                var sum = 0f
                val base = f * ch
                for (c in 0 until ch) sum += floats.get(base + c)
                val scaled = (sum / ch) * MAX_16BIT
                out.add(scaled.coerceIn(MIN_16BIT, MAX_16BIT).roundToInt().toShort())
                f++
            }
        } else {
            val shorts = buffer.asShortBuffer()
            val frames = (size / 2) / ch
            var f = 0
            while (f < frames && out.size < cap) {
                var sum = 0
                val base = f * ch
                for (c in 0 until ch) sum += shorts.get(base + c).toInt()
                // Symmetric rounding, matching the float branch (not truncation).
                out.add((sum.toFloat() / ch).roundToInt().toShort())
                f++
            }
        }
    }

    /**
     * Resample the first [srcCount] samples of [src] from [srcRate] to [dstRate]
     * by linear interpolation, capping the result at [maxOut] samples. Returns a
     * trimmed copy unchanged when the rates already match.
     */
    private fun resampleLinear(
        src: ShortArray,
        srcCount: Int,
        srcRate: Int,
        dstRate: Int,
        maxOut: Int,
    ): ShortArray {
        if (srcCount <= 0) return ShortArray(0)
        if (srcRate == dstRate || srcRate <= 0) return src.copyOf(srcCount)

        // srcCount > 0 here, so clamp the length to at least 1: even a single input
        // sample (e.g. heavy downsampling) must yield one output sample, never 0.
        val outLen = ((srcCount.toLong() * dstRate) / srcRate).toInt().coerceIn(1, maxOut)
        val out = ShortArray(outLen)
        val step = srcRate.toDouble() / dstRate
        var pos = 0.0
        for (i in 0 until outLen) {
            val idx = pos.toInt()
            val frac = pos - idx
            val a = src[idx].toInt()
            val b = if (idx + 1 < srcCount) src[idx + 1].toInt() else a
            out[i] = (a + (b - a) * frac).roundToInt().toShort()
            pos += step
        }
        return out
    }

    /** [MediaFormat.getInteger] but tolerant of a missing key (pre-API-29 safe). */
    private fun MediaFormat.getIntOr(key: String, default: Int): Int =
        try {
            getInteger(key)
        } catch (_: Exception) {
            default
        }

    /**
     * A minimal growable `short[]`, since the stdlib has no primitive-short list.
     * Grows by 1.5x to keep re-allocations amortised while decoding large files.
     */
    private class ShortAccumulator(initialCapacity: Int = 1 shl 18) {
        var backing = ShortArray(initialCapacity)
            private set
        var size = 0
            private set

        fun add(value: Short) {
            if (size == backing.size) {
                var cap = backing.size + (backing.size shr 1)
                if (cap <= size) cap = size + 1
                backing = backing.copyOf(cap)
            }
            backing[size++] = value
        }
    }
}
