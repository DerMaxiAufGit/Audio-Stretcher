package de.maxihaaser.audioscratch.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Minimal reader/writer for canonical RIFF/WAVE files containing 16-bit signed
 * little-endian PCM. That is the only format this app records and plays back.
 *
 * The writer streams samples to disk with a placeholder header that is patched
 * with the real sizes when the stream is closed, so we never need to buffer the
 * whole recording in memory while capturing.
 */
object WavIo {

    /** Size of a canonical WAV header (RIFF + fmt + data descriptors). */
    private const val HEADER_SIZE = 44
    private const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

    /** Decoded PCM together with the format needed to play it back. */
    class WavData(
        val samples: ShortArray,
        val sampleRate: Int,
        val channels: Int,
    )

    /**
     * Streaming writer for 16-bit PCM. Writes a 44-byte placeholder header on
     * construction, appends raw little-endian PCM bytes as they arrive, then
     * patches the RIFF/data size fields on [close]. Not thread-safe: use from a
     * single thread.
     */
    class Writer(
        private val file: File,
        private val sampleRate: Int,
        private val channels: Int = 1,
    ) : AutoCloseable {

        private val out = BufferedOutputStream(FileOutputStream(file))
        private var dataBytes = 0L
        private var closed = false

        init {
            // Reserve space for the header; real values written in close().
            out.write(ByteArray(HEADER_SIZE))
        }

        /** Append [length] bytes of little-endian 16-bit PCM from [buffer]. */
        fun writeBytes(buffer: ByteArray, length: Int) {
            if (length <= 0) return
            out.write(buffer, 0, length)
            dataBytes += length
        }

        override fun close() {
            if (closed) return
            closed = true
            out.flush()
            out.close()
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(0)
                raf.write(buildHeader(sampleRate, channels, dataBytes))
            }
        }
    }

    /**
     * Read a 16-bit PCM WAV [file] fully into memory. Scans the chunk list so
     * files with extra chunks (e.g. a LIST/INFO block) still parse. Throws
     * [IllegalArgumentException] for anything that is not 16-bit PCM WAVE.
     */
    fun read(file: File): WavData {
        val bytes = file.readBytes()
        require(bytes.size >= HEADER_SIZE) { "File too small to be a WAV: ${file.name}" }

        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(tagAt(bb, 0) == "RIFF") { "Not a RIFF file: ${file.name}" }
        require(tagAt(bb, 8) == "WAVE") { "Not a WAVE file: ${file.name}" }

        var sampleRate = 44100
        var channels = 1
        var bitsPerSample = BITS_PER_SAMPLE
        var dataOffset = -1
        var dataLength = 0

        var pos = 12 // first chunk after "RIFF<size>WAVE"
        while (pos + 8 <= bytes.size) {
            val chunkId = tagAt(bb, pos)
            val chunkSize = bb.getInt(pos + 4)
            val body = pos + 8
            when (chunkId) {
                "fmt " -> if (body + 16 <= bytes.size) {
                    channels = bb.getShort(body + 2).toInt()
                    sampleRate = bb.getInt(body + 4)
                    bitsPerSample = bb.getShort(body + 14).toInt()
                }
                "data" -> {
                    dataOffset = body
                    dataLength = chunkSize
                }
            }
            if (dataOffset >= 0) break
            // A negative (or absurd) chunk size from a crafted file would seek
            // backward or spin forever; a valid WAV always has size >= 0. Stop.
            if (chunkSize < 0) break
            // Chunks are word-aligned: an odd size is followed by a pad byte.
            pos = body + chunkSize + (chunkSize and 1)
        }

        require(dataOffset >= 0) { "No data chunk in WAV: ${file.name}" }
        require(bitsPerSample == BITS_PER_SAMPLE) {
            "Only 16-bit PCM is supported, got $bitsPerSample-bit"
        }

        // Clamp to what is actually present in case the header over-reports.
        val available = (bytes.size - dataOffset).coerceAtLeast(0)
        val usableBytes = minOf(dataLength, available)
        val sampleCount = usableBytes / BYTES_PER_SAMPLE
        val samples = ShortArray(sampleCount)
        val sb = ByteBuffer.wrap(bytes, dataOffset, sampleCount * BYTES_PER_SAMPLE)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            samples[i] = sb.short
        }
        return WavData(samples, sampleRate, channels.coerceAtLeast(1))
    }

    private fun buildHeader(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val byteRate = sampleRate * channels * BYTES_PER_SAMPLE
        val blockAlign = channels * BYTES_PER_SAMPLE
        val riffChunkSize = dataBytes + HEADER_SIZE - 8
        return ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            putTag("RIFF")
            putInt(riffChunkSize.toInt())
            putTag("WAVE")
            putTag("fmt ")
            putInt(16)                       // fmt chunk body size for PCM
            putShort(1)                      // audio format 1 = PCM
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(BITS_PER_SAMPLE.toShort())
            putTag("data")
            putInt(dataBytes.toInt())
        }.array()
    }

    private fun ByteBuffer.putTag(tag: String) {
        for (c in tag) put(c.code.toByte())
    }

    private fun tagAt(bb: ByteBuffer, offset: Int): String {
        val chars = CharArray(4)
        for (i in 0 until 4) chars[i] = (bb.get(offset + i).toInt() and 0xFF).toChar()
        return String(chars)
    }
}
