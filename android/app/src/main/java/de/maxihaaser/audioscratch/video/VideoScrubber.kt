package de.maxihaaser.audioscratch.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Playhead-driven video frame provider — the Android counterpart of the desktop's
 * `as::VideoScrubber`. Decodes the frame at a requested media position on one
 * dedicated background thread and publishes it via [onFrame].
 *
 * Built on [MediaMetadataRetriever] alone (no ExoPlayer/Media3), which keeps the
 * app dependency-free at the cost of only being able to land on keyframes — see
 * [requestFrame].
 *
 * Lifecycle: [open] once, then [requestFrame] as the playhead moves, then
 * [release]. A single instance decodes at most one file; the ViewModel builds a
 * fresh one per import.
 */
class VideoScrubber {

    /**
     * Guards [requestedUs] / [alive] and parks the worker between requests.
     * `Object`, not `Any`, for the `wait`/`notifyAll` monitor — as in `ScrubPlayer`.
     */
    private val lock = Object()

    /** False once [release] has run; the worker checks it and self-terminates. */
    @Volatile
    private var alive = false

    /**
     * The pending request, in media µs, or [NO_REQUEST] when there is none.
     *
     * This is the whole coalescing story: a request *replaces* whatever hadn't been
     * picked up yet rather than joining a queue, so a drag that outruns the decoder
     * (each `getFrameAtTime` is tens of ms; touch events arrive far faster) always
     * decodes the position the finger is at now — never a backlog of stale ones.
     */
    private var requestedUs: Long = NO_REQUEST

    /** Decoded frame width in px, or 0 when the file has no video. */
    var width: Int = 0
        private set

    /** Decoded frame height in px, or 0 when the file has no video. */
    var height: Int = 0
        private set

    // Target size for the decode, derived from the source in [open] so the frame
    // fits [MAX_FRAME_EDGE] while keeping its aspect. 0 means "decode native"
    // (no video, unknown dimensions, or already small enough).
    private var scaledWidth = 0
    private var scaledHeight = 0

    /** Whether [open] found a video track. */
    @Volatile
    var hasVideo: Boolean = false
        private set

    /**
     * Invoked from the worker thread with each decoded frame (or `null` when a
     * decode fails). Nulled by [release] so a late frame can't reach a torn-down
     * owner. Sinks must be thread-safe — a `StateFlow` write is.
     */
    @Volatile
    var onFrame: ((Bitmap?) -> Unit)? = null

    /**
     * Point the scrubber at [uri] and report whether it has a video track. Blocking
     * (it opens and probes the file) — call it off the main thread.
     *
     * Returns `false` — having released the retriever — for an audio-only file, an
     * unreadable [Uri], or a malformed/unsupported one: every retriever call here
     * is guarded, since `setDataSource` throws a bare [RuntimeException] on a file
     * the device can't parse. A `false` return leaves the instance inert, and
     * [release] stays safe to call on it.
     */
    fun open(context: Context, uri: Uri): Boolean {
        // One file per instance, mirroring AudioRecorder.start()'s "already busy" contract.
        if (alive) return false

        val mmr = MediaMetadataRetriever()
        val probed = try {
            mmr.setDataSource(context, uri)
            val yes = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            if (yes) {
                width = mmr.metaInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                height = mmr.metaInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            }
            yes
        } catch (_: Throwable) {
            // Unreadable / unsupported / no permission — treat as "no video", never crash.
            false
        }

        if (!probed) {
            releaseQuietly(mmr)
            width = 0
            height = 0
            scaledWidth = 0
            scaledHeight = 0
            return false
        }

        // Decode no larger than the pane needs: a native 4K frame is ~33 MB, and
        // scrubbing re-decodes at drag rate. Aspect is preserved; a source already
        // within the cap (or of unknown size) decodes native.
        val longestEdge = maxOf(width, height)
        if (longestEdge > MAX_FRAME_EDGE) {
            val scale = MAX_FRAME_EDGE.toDouble() / longestEdge
            scaledWidth = (width * scale).toInt().coerceAtLeast(1)
            scaledHeight = (height * scale).toInt().coerceAtLeast(1)
        } else {
            scaledWidth = 0
            scaledHeight = 0
        }

        synchronized(lock) {
            requestedUs = NO_REQUEST
            alive = true
        }
        hasVideo = true
        // The worker takes ownership of `mmr` from here: it is released in the
        // worker's `finally`, never by release(), so a teardown never blocks the
        // caller on an in-flight decode.
        startWorker(mmr)
        return true
    }

    /**
     * Ask for the frame at [timeUs] (media µs). Non-blocking and safe from any
     * thread — including the audio worker that drives the playhead. Supersedes any
     * request not yet picked up; see [requestedUs].
     */
    fun requestFrame(timeUs: Long) {
        synchronized(lock) {
            if (!alive) return
            requestedUs = timeUs.coerceAtLeast(0L)
            lock.notifyAll()
        }
    }

    /**
     * Stop decoding and drop the callback. Idempotent, and safe whether [open]
     * succeeded, failed, or was never called.
     *
     * Deliberately does not join: the worker self-terminates on [alive] and
     * releases the retriever in its own `finally`, so a caller on the main thread
     * is never parked behind an in-flight `getFrameAtTime` (tens of ms). The
     * retriever therefore outlives this call by at most one decode.
     */
    fun release() {
        synchronized(lock) {
            alive = false
            requestedUs = NO_REQUEST
            lock.notifyAll()
        }
        // Cleared after the flag: the worker may be mid-decode and about to publish
        // one last frame, and that frame must not reach a released owner.
        onFrame = null
        hasVideo = false
    }

    private fun startWorker(mmr: MediaMetadataRetriever) {
        thread(name = "VideoScrubber") {
            // The last position actually handed to the decoder, or NO_REQUEST before
            // the first one. Standing in for the desktop's frame cache: a decode is
            // skipped when the new request is within CACHE_TOLERANCE_US of it, since
            // OPTION_CLOSEST_SYNC would only land on the same keyframe and re-publish
            // a bitmap identical to the one already on screen. Cheap, and it keeps a
            // slow drag from re-decoding the same picture dozens of times a second.
            var lastDecodedUs = NO_REQUEST
            try {
                while (true) {
                    val timeUs: Long
                    synchronized(lock) {
                        while (alive && requestedUs == NO_REQUEST) {
                            lock.wait()
                        }
                        if (!alive) return@thread
                        timeUs = requestedUs
                        // Consume it: anything arriving from here on is a *newer*
                        // request and parks us for another lap rather than being lost.
                        requestedUs = NO_REQUEST
                    }

                    if (lastDecodedUs != NO_REQUEST &&
                        abs(timeUs - lastDecodedUs) < CACHE_TOLERANCE_US
                    ) {
                        continue
                    }
                    lastDecodedUs = timeUs

                    // OPTION_CLOSEST_SYNC, not OPTION_CLOSEST: desktop parity, and the
                    // exact-frame options decode every frame from the preceding keyframe
                    // forward — hundreds of ms on a long GOP, far too slow to drag against.
                    //
                    // Scaled where possible: getFrameAtTime hands back the frame at native
                    // resolution, so dragging through a 4K clip churns ~33 MB bitmaps at
                    // decode rate. The pane is a few hundred dp, so a downscale costs
                    // nothing visible and takes the allocation with it.
                    val frame = try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1 &&
                            scaledWidth > 0 && scaledHeight > 0
                        ) {
                            mmr.getScaledFrameAtTime(
                                timeUs,
                                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                                scaledWidth,
                                scaledHeight,
                            )
                        } else {
                            // API 26: getScaledFrameAtTime is 27+, so take the native frame
                            // and downscale it ourselves. The full-size one is never
                            // published, so it can be freed immediately rather than left
                            // for the collector — it's ~33 MB on a 4K source, at drag rate.
                            val raw = mmr.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            if (raw != null && scaledWidth > 0 && scaledHeight > 0) {
                                val scaled = android.graphics.Bitmap.createScaledBitmap(
                                    raw, scaledWidth, scaledHeight, true,
                                )
                                if (scaled !== raw) raw.recycle()
                                scaled
                            } else {
                                raw
                            }
                        }
                    } catch (_: Throwable) {
                        // A failed decode (or an OOM on a 4K frame) drops one picture; it
                        // must not kill the thread and strand the pane.
                        null
                    }
                    if (!alive) return@thread
                    onFrame?.invoke(frame)
                }
            } finally {
                releaseQuietly(mmr)
            }
        }
    }

    /** [MediaMetadataRetriever.extractMetadata] as an Int, 0 when absent/garbage. */
    private fun MediaMetadataRetriever.metaInt(key: Int): Int =
        try {
            extractMetadata(key)?.toIntOrNull() ?: 0
        } catch (_: Throwable) {
            0
        }

    private fun releaseQuietly(mmr: MediaMetadataRetriever) {
        try {
            mmr.release()
        } catch (_: Throwable) {
            // Already released / never opened — nothing to do.
        }
    }

    private companion object {
        /** [requestedUs] sentinel: nothing pending. Real positions are >= 0. */
        const val NO_REQUEST = -1L

        /**
         * How close a new request must be to the last decoded one to be skipped
         * (~40 ms, a frame at 25 fps). See the worker's `lastDecodedUs`.
         */
        const val CACHE_TOLERANCE_US = 40_000L

        /**
         * Longest edge of a decoded frame, in px. The pane is a few hundred dp, so
         * anything beyond this is invisible detail bought with a large allocation on
         * every decode. Only applied on API 27+ (`getScaledFrameAtTime`).
         */
        const val MAX_FRAME_EDGE = 1280
    }
}
