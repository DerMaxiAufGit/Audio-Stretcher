package de.maxihaaser.audioscratch.ui

import android.app.Application
import android.graphics.Bitmap
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.maxihaaser.audioscratch.R
import de.maxihaaser.audioscratch.audio.AudioImporter
import de.maxihaaser.audioscratch.audio.AudioRecorder
import de.maxihaaser.audioscratch.audio.InputDevices
import de.maxihaaser.audioscratch.audio.ScrubPlayer
import de.maxihaaser.audioscratch.audio.WavIo
import de.maxihaaser.audioscratch.audio.WaveformPeaks
import de.maxihaaser.audioscratch.service.ClipEvents
import de.maxihaaser.audioscratch.service.InstantReplayService
import de.maxihaaser.audioscratch.service.InstantReplayState
import de.maxihaaser.audioscratch.settings.AppSettings
import de.maxihaaser.audioscratch.video.VideoScrubber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToLong

/** High-level state driving [RecordScrubScreen]. */
sealed interface UiState {
    /** No recording yet — waiting for the user to hit record. */
    data object Idle : UiState

    /** Microphone capture is in progress. */
    data object Recording : UiState

    /** A recording is loaded and ready to scrub / play. */
    class Ready(
        val file: File?,
        /** Min/max envelope index over [samples], for drawing the waveform. */
        val peaks: WaveformPeaks,
        /**
         * The loaded PCM — the same array the player renders from. Held so the
         * waveform can scan it directly when zoomed in past [WaveformPeaks.bucketFrames].
         * Never mutated after load.
         */
        val samples: ShortArray,
        val sampleRate: Int,
    ) : UiState
}

/**
 * The half-open frame range `[startFrame, startFrame + frames)` of the clip that
 * the waveform shows.
 *
 * The two fields are one immutable value rather than two [StateFlow]s because the
 * window is written from both the main thread (gestures, buttons, sliders) and the
 * player's worker thread (playhead follow). Split state could publish a
 * `startFrame` computed against a `frames` another thread had already replaced,
 * breaking the invariants below and visibly sliding the ruler off the waveform.
 *
 * Invariants, re-established by every mutator (see [RecorderViewModel.clampWindow]):
 * `frames` in `minVisibleFrames..totalFrames` and `startFrame` in
 * `0..(totalFrames - frames)`. "Zoomed out" / fit is `frames == totalFrames`.
 * Nothing loaded is `ViewWindow(0, 0)`.
 */
data class ViewWindow(val startFrame: Int, val frames: Int)

/**
 * The A/B loop over `[beginFrame, endFrame)`, plus whether it is armed.
 *
 * One immutable value rather than three [StateFlow]s for the same reason as
 * [ViewWindow], and because the whole thing is pushed to
 * [ScrubPlayer.setLoop] as a unit: a reader (or the player's worker) must never
 * see a `beginFrame` paired with an `endFrame` from a different edit.
 *
 * Invariant, re-established by [RecorderViewModel.clampLoop] on every mutation:
 * `0 <= beginFrame <= endFrame <= totalFrames`. Bounds are clamped, never
 * swapped, so a drag past the opposite handle stalls there instead of the two
 * silently trading places under the finger. `beginFrame == endFrame` is a
 * degenerate region: it may be [enabled], but the player won't act on it.
 */
data class LoopRegion(val beginFrame: Int, val endFrame: Int, val enabled: Boolean)

/**
 * Owns the record → scrub flow: wires [AudioRecorder] and [ScrubPlayer] together
 * and exposes their state as [StateFlow]s for Compose. Uses [AndroidViewModel]
 * (a [androidx.lifecycle.ViewModel]) because it needs the app's `filesDir`, which
 * requires an [Application] context.
 */
class RecorderViewModel(app: Application) : AndroidViewModel(app) {

    private val recorder = AudioRecorder(SAMPLE_RATE)
    private val player = ScrubPlayer()

    /** Persisted capture settings — the source of the seeds below. */
    private val settings = AppSettings(app)

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _playhead = MutableStateFlow(0f)
    val playhead: StateFlow<Float> = _playhead.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    /** True while an imported file is being decoded off the main thread. */
    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    /** Last user-facing error (e.g. a failed import), or `null` when cleared. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** Whether the Instant Replay foreground service is currently armed. */
    private val _isInstantReplayOn = MutableStateFlow(false)
    val isInstantReplayOn: StateFlow<Boolean> = _isInstantReplayOn.asStateFlow()

    /**
     * Rolling-buffer length (seconds) used when Instant Replay is armed. Seeded
     * from the persisted setting, so the choice survives a restart.
     */
    private val _bufferSeconds = MutableStateFlow(settings.bufferSeconds)
    val bufferSeconds: StateFlow<Int> = _bufferSeconds.asStateFlow()

    /**
     * The input device both capture paths prefer, or [InputDevices.SYSTEM_DEFAULT_ID]
     * for the system's own choice. Also seeded from the persisted setting.
     */
    private val _micDeviceId = MutableStateFlow(settings.micDeviceId)
    val micDeviceId: StateFlow<Int> = _micDeviceId.asStateFlow()

    /** Playback speed / base rate, 0.25×..4×; 1× is normal tempo. */
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed.asStateFlow()

    /** Coarse pitch offset in semitones, -36..+36. */
    private val _pitchSemitones = MutableStateFlow(0)
    val pitchSemitones: StateFlow<Int> = _pitchSemitones.asStateFlow()

    /** Fine pitch offset in cents, -100..+100. */
    private val _pitchCents = MutableStateFlow(0)
    val pitchCents: StateFlow<Int> = _pitchCents.asStateFlow()

    /** Pitch-preserve (default) vs turntable / varispeed. */
    private val _playbackMode = MutableStateFlow(ScrubPlayer.PlaybackMode.PITCH_PRESERVE)
    val playbackMode: StateFlow<ScrubPlayer.PlaybackMode> = _playbackMode.asStateFlow()

    /** Master volume as a percentage, 0..200; 100 is unity gain. */
    private val _volumePercent = MutableStateFlow(100)
    val volumePercent: StateFlow<Int> = _volumePercent.asStateFlow()

    /** Whether output is muted (gain forced to 0). */
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /** Duration of the loaded audio in seconds (0 when nothing is loaded). */
    private val _durationSeconds = MutableStateFlow(0f)
    val durationSeconds: StateFlow<Float> = _durationSeconds.asStateFlow()

    // --- video ---
    // Optional: only an imported file can carry a video track (a recording and an
    // Instant-Replay clip are mic audio). Everything here is torn down by
    // [loadSamplesIntoPlayer] and re-attached, if there is video, by [importFromUri].

    /** Whether the loaded file has a video track — gates the video pane. */
    private val _hasVideo = MutableStateFlow(false)
    val hasVideo: StateFlow<Boolean> = _hasVideo.asStateFlow()

    /** The decoded frame at the playhead, or `null` before the first one lands. */
    private val _videoFrame = MutableStateFlow<Bitmap?>(null)
    val videoFrame: StateFlow<Bitmap?> = _videoFrame.asStateFlow()

    /**
     * Decodes frames for [videoFrame]. Published from the main thread on load and
     * read from the player's worker thread in the playhead callback, hence
     * `@Volatile`. `null` whenever the loaded source has no video.
     */
    @Volatile
    private var videoScrubber: VideoScrubber? = null

    /**
     * Media position (µs) of the last frame request, or -1 before the first one.
     * See [requestVideoFrame] for why a plain volatile is enough.
     */
    @Volatile
    private var lastVideoRequestUs = -1L

    // --- waveform view window ---
    // The waveform shows a window of the clip rather than the whole thing. See
    // [ViewWindow] for the range it describes and the invariants every mutator
    // here upholds.

    /** Frame count of the loaded audio (0 when nothing is loaded). */
    private val _totalFrames = MutableStateFlow(0)
    val totalFrames: StateFlow<Int> = _totalFrames.asStateFlow()

    /**
     * The waveform's visible window — the single source of truth for both of its
     * bounds. Only ever written through [_viewWindow].update, so a reader always
     * sees a start/frames pair that was computed together.
     */
    private val _viewWindow = MutableStateFlow(ViewWindow(0, 0))
    val viewWindow: StateFlow<ViewWindow> = _viewWindow.asStateFlow()

    // --- A/B loop + markers ---

    /**
     * The A/B loop. Only ever written through [updateLoop], which re-establishes
     * the [LoopRegion] invariants and mirrors the result into the player.
     */
    private val _loop = MutableStateFlow(LoopRegion(0, 0, false))
    val loop: StateFlow<LoopRegion> = _loop.asStateFlow()

    /** The clip's markers, always sorted by [Marker.frame]. */
    private val _markers = MutableStateFlow<List<Marker>>(emptyList())
    val markers: StateFlow<List<Marker>> = _markers.asStateFlow()

    /**
     * Source of stable [Marker.id]s, handed out in increasing order and reset with
     * each clip (desktop parity: `DeckState::reset` restarts at 1). Only touched
     * from the main thread, like every other marker mutation here.
     */
    private var nextMarkerId = 1L

    /** Whether the hosting Activity is in the foreground (set by MainActivity). */
    private var isForeground = false

    /** Absolute path of the currently loaded source (clip/recording), or `null`. */
    private var loadedSourcePath: String? = null

    init {
        // Called on the player's worker thread. StateFlow writes are thread-safe,
        // so the playhead, the view-window follow and the video request are all
        // safe to do here.
        player.onPlayhead = { pos ->
            _playhead.value = pos
            followPlayhead(pos)
            requestVideoFrame(pos)
        }
        player.onCompletion = { _isPlaying.value = false }

        // Reconcile the Instant Replay switch with the service's real capture state
        // (it flips running=true only when the mic is actually live, false on stop
        // or arm failure). This only mirrors state — it never re-triggers
        // start/stop — so there is no feedback loop.
        viewModelScope.launch {
            InstantReplayState.running.collect { running ->
                _isInstantReplayOn.value = running
            }
        }

        // A clip saved by InstantReplayService (same process) is handed off via
        // ClipEvents. Auto-load it into the scrub editor ONLY while the app is in
        // the foreground and not mid-recording; otherwise consume it silently so a
        // backgrounded session isn't clobbered (the user still has the "Clip saved"
        // notification to open it explicitly).
        viewModelScope.launch {
            ClipEvents.latestClip.collect { file ->
                if (file != null) {
                    // Consume only this file, so a second clip saved in the same instant
                    // (compareAndSet vs a blind reset) isn't clobbered before it's seen.
                    ClipEvents.latestClip.compareAndSet(file, null)
                    if (isForeground && _uiState.value !is UiState.Recording) {
                        loadClipFile(file)
                    }
                }
            }
        }
    }

    /** Track Activity foreground state so ClipEvents only auto-loads when visible. */
    fun setForeground(foreground: Boolean) {
        isForeground = foreground
    }

    /** Begin (or restart) a microphone recording. Requires RECORD_AUDIO granted. */
    fun startRecording() {
        if (_uiState.value is UiState.Recording) return
        stopPlaybackInternal()
        // Show Recording optimistically. The actual hand-off (disarm Instant Replay,
        // then acquire the mic) may briefly block on the mic gate, so run it off the
        // main thread; revert to Idle with an error if capture can't start.
        val wasInstantReplayOn = _isInstantReplayOn.value
        val outFile = recordingFile()
        val deviceId = _micDeviceId.value
        _playhead.value = 0f
        _uiState.value = UiState.Recording
        viewModelScope.launch {
            val started = withContext(Dispatchers.IO) {
                // Mic exclusivity: Instant Replay owns the mic via its service, so
                // disarm it first. Stopping releases the mic gate; recorder.start()
                // then acquires it, guaranteeing an ordered same-process hand-off.
                if (wasInstantReplayOn) {
                    InstantReplayService.stop(getApplication())
                }
                // Resolved here, off the main thread, so AudioRecorder stays
                // Context-free. A stale id resolves to null → system default.
                val device = preferredInputDevice(deviceId)
                recorder.start(outFile, device)
            }
            if (!started) {
                _uiState.value = UiState.Idle
                _errorMessage.value = string(R.string.record_start_failed)
            }
        }
    }

    /** Stop recording, decode the WAV, and hand it to the player. */
    fun stopRecording() {
        if (_uiState.value !is UiState.Recording) return
        val file = recordingFile()
        viewModelScope.launch {
            val samples = withContext(Dispatchers.IO) {
                recorder.stop()
                runCatching { WavIo.read(file).samples }.getOrNull()
            }

            if (samples == null || samples.isEmpty()) {
                _uiState.value = UiState.Idle
                return@launch
            }

            loadSamplesIntoPlayer(samples, file)
        }
    }

    /**
     * Decode the audio (or video-with-audio) file at [uri] to the canonical
     * format on a background thread, then load it like a fresh recording. On
     * failure an [errorMessage] is surfaced for the UI to show.
     *
     * If the audio lands and the file also has a video track, a [VideoScrubber] is
     * attached to [uri] for the video pane. Video is strictly optional: a file
     * whose picture can't be opened still plays, exactly as before.
     */
    fun importFromUri(uri: Uri) {
        if (_uiState.value is UiState.Recording || _isImporting.value) return
        stopPlaybackInternal()
        _errorMessage.value = null
        _isImporting.value = true
        viewModelScope.launch {
            try {
                val samples = withContext(Dispatchers.IO) {
                    // Poll `isActive` inside the decode loop so a cancelled import
                    // (e.g. the ViewModel being cleared) aborts promptly.
                    AudioImporter.decodeToMono44100(getApplication(), uri) { isActive }
                }
                if (samples.isEmpty()) {
                    _errorMessage.value = string(R.string.import_error_empty)
                } else {
                    loadSamplesIntoPlayer(samples, sourceFile = null)
                    // Strictly after the load: loadSamplesIntoPlayer is the teardown
                    // choke point and would release a scrubber attached before it.
                    attachVideo(uri)
                }
            } catch (e: CancellationException) {
                throw e // never convert cancellation into a user-facing error
            } catch (e: Throwable) {
                _errorMessage.value = string(
                    R.string.import_failed,
                    e.message ?: string(R.string.import_error_unknown),
                )
            } finally {
                _isImporting.value = false
            }
        }
    }

    /** Toggle continuous playback of the loaded recording. */
    fun togglePlayback() {
        if (_uiState.value !is UiState.Ready) return
        if (player.isPlaying) {
            player.pause()
            _isPlaying.value = false
        } else {
            player.play()
            _isPlaying.value = true
        }
    }

    /** Move the playhead to a normalised position without playing. */
    fun seekTo(norm: Float) {
        if (_uiState.value !is UiState.Ready) return
        player.seekTo(norm)
        _playhead.value = norm.coerceIn(0f, 1f)
        // A silent seek renders nothing, so the player's worker won't emit a
        // playhead: ask for the frame here or the picture would stall behind a
        // marker jump / position-slider move. Forced past the throttle — a jump is
        // one-shot, so nothing would come along afterwards to correct the picture.
        requestVideoFrame(_playhead.value, force = true)
    }

    /**
     * Move the playhead to an absolute [frame] without playing — what tapping a
     * loop handle or a marker flag on [LoopMarkerBar] does.
     */
    fun seekToFrame(frame: Int) {
        seekTo(normForFrame(frame))
    }

    /** Scrub to a normalised position (plays a short grain). */
    fun scrub(norm: Float) {
        if (_uiState.value !is UiState.Ready) return
        // Dragging means the user is steering: cancel continuous playback.
        _isPlaying.value = false
        player.scrub(norm)
        _playhead.value = norm.coerceIn(0f, 1f)
        requestVideoFrame(_playhead.value)
    }

    /**
     * Scrub by [deltaSeconds] of media time relative to where the playhead is now —
     * the video pane's drag entry point.
     *
     * Desktop parity (`VideoView`): dragging the picture is *relative* motion, like
     * pushing a record, rather than the waveform's "the x axis is the clip" absolute
     * mapping. Goes through [scrub], so it plays the grain and cancels playback just
     * like a waveform drag; the result is clamped to the clip.
     */
    fun scrubBySeconds(deltaSeconds: Double) {
        if (_uiState.value !is UiState.Ready) return
        val duration = _durationSeconds.value.toDouble()
        if (duration <= 0.0) return
        val next = (_playhead.value.toDouble() + deltaSeconds / duration).coerceIn(0.0, 1.0)
        scrub(next.toFloat())
    }

    /**
     * Scrub to [viewNorm], a `0..1` position *within the visible waveform window*.
     * The waveform only shows [viewWindow], so its x axis is not the whole clip
     * once zoomed in; [scrub] still takes a whole-clip norm and backs the position
     * slider.
     */
    fun scrubAtViewNorm(viewNorm: Float) {
        scrub(viewNormToClipNorm(viewNorm))
    }

    /** Map a `0..1` position inside the visible window to a whole-clip norm. */
    private fun viewNormToClipNorm(viewNorm: Float): Float {
        val total = _totalFrames.value
        if (total <= 0) return 0f
        // Double throughout: Float is only exact to ~16.7M, i.e. ~379 s at 44.1 kHz,
        // so a long clip would snap the scrub target to the wrong frame near its tail.
        val w = _viewWindow.value
        val frame = w.startFrame + viewNorm.coerceIn(0f, 1f).toDouble() * w.frames
        return (frame / total).coerceIn(0.0, 1.0).toFloat()
    }

    /**
     * Re-establish the [ViewWindow] invariants for a candidate [startFrame] /
     * [frames] pair against [total]. Both bounds are decided here, together, from
     * one read of the total — the only way the window is ever built.
     *
     * Takes [Long]s because callers add unclamped frame deltas: an [Int] `start +
     * delta` can overflow and wrap negative, which would clamp a pan towards the
     * end of a long clip back to its start.
     */
    private fun clampWindow(startFrame: Long, frames: Long, total: Int): ViewWindow {
        if (total <= 0) return ViewWindow(0, 0) // nothing loaded — no window to clamp into
        val f = frames.coerceIn(minVisibleFrames(total).toLong(), total.toLong())
        val s = startFrame.coerceIn(0L, total - f)
        return ViewWindow(s.toInt(), f.toInt())
    }

    /**
     * Zoom by [zoomFactor] about [centerNorm] and then pan by [panFraction], as one
     * atomic move. Both are applied to the same window snapshot, so a pinch and a
     * drag arriving in the same event agree on how wide the view is.
     *
     * [zoomFactor] > 1 zooms in, < 1 zooms out; [centerNorm] is a `0..1` position in
     * the *current* view that stays anchored under the finger; [panFraction] is a
     * fraction of the *post-zoom* view width (so the UI never does frame math and
     * can't pass a stale width). Zoom-in stops at [MIN_VISIBLE_FRAMES], zoom-out at
     * the whole clip.
     */
    fun transformView(zoomFactor: Float, panFraction: Float, centerNorm: Float) {
        val total = _totalFrames.value
        if (total <= 0) return
        _viewWindow.update { w ->
            val visible = if (w.frames > 0) w.frames else total
            val zoom = if (zoomFactor > 0f && zoomFactor.isFinite()) zoomFactor else 1f
            val anchor = centerNorm.coerceIn(0f, 1f).toDouble()
            // Frame under the anchor, which must not move across the zoom.
            val anchorFrame = w.startFrame + anchor * visible
            val zoomed = (visible / zoom.toDouble())
                .roundToLong()
                .coerceIn(minVisibleFrames(total).toLong(), total.toLong())
            val pan = if (panFraction.isFinite()) panFraction.toDouble() else 0.0
            // Pan against the post-zoom width, not the width we started the event with.
            val start = anchorFrame - anchor * zoomed + pan * zoomed
            clampWindow(start.roundToLong(), zoomed, total)
        }
    }

    /**
     * Zoom about [centerNorm] without panning — the zoom buttons' entry point.
     * Desktop parity: one button step is 1.3×.
     */
    fun zoomBy(factor: Float, centerNorm: Float) {
        transformView(zoomFactor = factor, panFraction = 0f, centerNorm = centerNorm)
    }

    /** Show the whole clip (desktop's "zoom to fit"). */
    fun zoomToFit() {
        val total = _totalFrames.value
        _viewWindow.update { clampWindow(0L, total.toLong(), total) }
    }

    /** Scroll the view window by [delta] frames (negative pans towards the start). */
    fun panByFrames(delta: Int) {
        val total = _totalFrames.value
        _viewWindow.update { w -> clampWindow(w.startFrame.toLong() + delta, w.frames.toLong(), total) }
    }

    /** Move the view window so it starts at frame [f], clamped to the clip. */
    fun setViewStartFrame(f: Int) {
        val total = _totalFrames.value
        _viewWindow.update { w -> clampWindow(f.toLong(), w.frames.toLong(), total) }
    }

    /**
     * Recentre the view on the playhead when it leaves the visible window, so
     * auto-play doesn't run off the edge of a zoomed-in waveform.
     *
     * Called from the player's worker thread on every rendered chunk, concurrently
     * with the main thread's gestures — hence the read-modify-write of the whole
     * window in one [MutableStateFlow.update] rather than a read of one bound and a
     * write of the other. Skipped while fully zoomed out (nothing to follow) and
     * while the user is steering: [scrub] clears [isPlaying], which is the same
     * guard the desktop spells as `playing() && !scrubbing()`.
     */
    private fun followPlayhead(norm: Float) {
        if (!_isPlaying.value) return
        val total = _totalFrames.value
        if (total <= 0) return
        val frame = (norm.coerceIn(0f, 1f).toDouble() * total).toLong()
        _viewWindow.update { w ->
            if (w.frames <= 0 || w.frames >= total) return@update w
            if (frame >= w.startFrame && frame < w.startFrame + w.frames) return@update w
            clampWindow(frame - w.frames / 2, w.frames.toLong(), total)
        }
    }

    // --- video ---

    /**
     * Open [uri]'s video track, if it has one, and hand its frames to the pane.
     * Called only from a completed [importFromUri], after the audio is loaded.
     *
     * The probe is blocking, so it runs on IO; the state writes land back on the
     * caller's (main) thread. A file without video — or one whose picture the
     * device can't parse — leaves [hasVideo] false and is not an error: the audio
     * is already playing.
     */
    private suspend fun attachVideo(uri: Uri) {
        val scrubber = VideoScrubber()
        try {
            val opened = withContext(Dispatchers.IO) { scrubber.open(getApplication(), uri) }
            if (!opened) {
                scrubber.release() // no-op when open() failed, but keeps the pairing honest
                return
            }
            // Published from the scrubber's worker thread; a StateFlow write is safe
            // there. The identity check drops a frame from a scrubber that has since
            // been replaced: release() doesn't join, so a decode already in flight can
            // still land here after the next file has loaded.
            scrubber.onFrame = { bitmap ->
                if (videoScrubber === scrubber) _videoFrame.value = bitmap
            }
            videoScrubber = scrubber
            _hasVideo.value = true
            // Show the opening frame straight away rather than waiting for the playhead
            // to travel far enough to clear the throttle.
            lastVideoRequestUs = -1L
            requestVideoFrame(_playhead.value)
        } catch (e: CancellationException) {
            // open() is blocking, so cancelling the scope mid-probe still leaves a live
            // worker + retriever behind — but the result is discarded and the line that
            // publishes `scrubber` to the field never runs, so onCleared() would never
            // see it. Release the local before unwinding.
            discardVideo(scrubber)
            throw e
        } catch (_: Throwable) {
            // Video is strictly optional: a failed attach must not turn an import whose
            // audio already loaded and is playing into an "import failed" error.
            discardVideo(scrubber)
        }
    }

    /** Tear down a [scrubber] that failed to attach, clearing it from the field if it got there. */
    private fun discardVideo(scrubber: VideoScrubber) {
        if (videoScrubber === scrubber) {
            videoScrubber = null
            _videoFrame.value = null
        }
        _hasVideo.value = false
        scrubber.release()
    }

    /**
     * Ask the scrubber for the frame at [norm] (a whole-clip playhead fraction).
     * A no-op when the loaded source has no video.
     *
     * Throttled to [VIDEO_REQUEST_INTERVAL_US] of *media* time: the player emits a
     * playhead every rendered chunk (~23 ms), and decoding a frame per chunk would
     * peg a core to redraw a picture that keyframe seeking would usually land on
     * anyway. Called from both the main thread and the player's worker; the
     * read-modify-write of [lastVideoRequestUs] isn't atomic, but the two racing is
     * benign — the worst case is one extra request, which the scrubber coalesces.
     */
    private fun requestVideoFrame(norm: Float, force: Boolean = false) {
        val scrubber = videoScrubber ?: return
        val durationUs = (_durationSeconds.value.toDouble() * 1_000_000.0).toLong()
        if (durationUs <= 0L) return
        val timeUs = (norm.coerceIn(0f, 1f).toDouble() * durationUs).toLong()
        val last = lastVideoRequestUs
        // The throttle only exists to thin out the playhead's ~23 ms callbacks. A
        // discrete jump (a seek, a marker) must always ask, or landing within the
        // interval of the last position leaves the old picture up with no callback
        // coming to correct it.
        if (!force && last >= 0L && abs(timeUs - last) < VIDEO_REQUEST_INTERVAL_US) return
        lastVideoRequestUs = timeUs
        scrubber.requestFrame(timeUs)
    }

    /**
     * Drop the current file's video and clear the pane. Cheap and non-blocking —
     * [VideoScrubber.release] doesn't join its worker — so it is safe on the main
     * thread.
     */
    private fun releaseVideo() {
        val old = videoScrubber
        videoScrubber = null
        _hasVideo.value = false
        _videoFrame.value = null
        lastVideoRequestUs = -1L
        old?.release()
    }

    /**
     * Smallest allowed view window. Stands in for the desktop's "1 sample per
     * pixel" zoom limit — the ViewModel doesn't know the waveform's pixel width,
     * and past this point the columns are sample-and-held anyway.
     */
    private fun minVisibleFrames(total: Int): Int = minOf(MIN_VISIBLE_FRAMES, total)

    // --- A/B loop ---

    /**
     * The only way the loop is ever written: applies [transform], re-establishes
     * the [LoopRegion] invariants via [clampLoop], and pushes the *resulting*
     * value to the player — so what the UI draws and what the worker wraps on can
     * never drift apart.
     */
    private fun updateLoop(transform: (LoopRegion) -> LoopRegion) {
        val next = _loop.updateAndGet { clampLoop(transform(it)) }
        player.setLoop(next.beginFrame, next.endFrame, next.enabled)
    }

    /**
     * Re-establish the [LoopRegion] invariants against the loaded clip. Both
     * bounds are decided here, together, from one read of the total.
     *
     * The end is clamped *up* to the begin rather than the two being swapped: an
     * inverted region means a handle was dragged past its opposite, and stalling
     * it there is what the user sees under the finger. A degenerate `begin == end`
     * survives — the player ignores it — so dragging A onto B doesn't silently
     * disarm a loop the user is still adjusting.
     *
     * A live region is held to [MIN_LOOP_FRAMES]. The render loop caps every write
     * to the distance remaining to the end, so a region narrower than one chunk
     * would make each write one loop-width long and spin the max-priority render
     * thread at `sampleRate / width` — up to tens of thousands of wraps a second
     * for a few-frame loop, which is both a CPU burn and a zipper of seam clicks.
     * Widening the end holds the floor; if that would run past the clip, the begin
     * is pulled back instead. Width 0 is exempt — it's inert, not played.
     */
    private fun clampLoop(l: LoopRegion): LoopRegion {
        val total = _totalFrames.value
        if (total <= 0) return LoopRegion(0, 0, false) // nothing loaded — no loop to hold
        var begin = l.beginFrame.coerceIn(0, total)
        var end = l.endFrame.coerceIn(begin, total)
        if (end > begin && end - begin < MIN_LOOP_FRAMES) {
            if (begin + MIN_LOOP_FRAMES <= total) {
                end = begin + MIN_LOOP_FRAMES
            } else {
                // Not enough clip left after `begin` — anchor the floor to the end.
                end = total
                begin = (total - MIN_LOOP_FRAMES).coerceAtLeast(0)
            }
        }
        return LoopRegion(begin, end, l.enabled)
    }

    /**
     * Snap the loop's start to the playhead ("Set A").
     *
     * Desktop parity (`LoopMarkerBar`'s setA): when the playhead is at or past B,
     * B is pushed out to half a second after A rather than the press being clamped
     * away — so Set A on a fresh clip yields a region you can actually see and
     * then refine with Set B, instead of a zero-width one.
     */
    fun setLoopA() {
        val a = playheadFrame()
        updateLoop { l ->
            val end = if (l.endFrame <= a) minOf(a + SAMPLE_RATE / 2, _totalFrames.value) else l.endFrame
            l.copy(beginFrame = a, endFrame = end)
        }
    }

    /**
     * Snap the loop's end to the playhead ("Set B"). Mirrors [setLoopA]: a
     * playhead at or before A pulls A back half a second instead of clamping the
     * press away.
     */
    fun setLoopB() {
        val b = playheadFrame()
        updateLoop { l ->
            val begin = if (l.beginFrame >= b) maxOf(b - SAMPLE_RATE / 2, 0) else l.beginFrame
            l.copy(beginFrame = begin, endFrame = b)
        }
    }

    /** Reset the loop bounds and disarm it (desktop's "Clear"). */
    fun clearLoop() {
        updateLoop { LoopRegion(0, 0, false) }
    }

    /** Arm or disarm the loop, leaving its bounds alone. */
    fun setLoopEnabled(enabled: Boolean) {
        updateLoop { it.copy(enabled = enabled) }
    }

    /** Move the loop's start to frame [f] — the A handle's drag entry point. */
    fun setLoopBeginFrame(f: Int) {
        updateLoop { l -> l.copy(beginFrame = f.coerceIn(0, l.endFrame)) }
    }

    /** Move the loop's end to frame [f] — the B handle's drag entry point. */
    fun setLoopEndFrame(f: Int) {
        updateLoop { l -> l.copy(endFrame = f.coerceAtLeast(l.beginFrame)) }
    }

    // --- markers ---

    /** Drop a marker at the playhead with an empty label (desktop's `M`). */
    fun addMarkerAtPlayhead() {
        if (_uiState.value !is UiState.Ready) return
        val marker = Marker(id = nextMarkerId++, frame = playheadFrame(), label = "")
        // Re-sorting the whole list keeps the "sorted by frame" invariant in one
        // place; a clip's marker count is small enough that an insert-at-index
        // would only trade clarity for nothing.
        _markers.update { (it + marker).sortedBy { m -> m.frame } }
    }

    /** Delete the marker with [id], if it still exists. */
    fun removeMarker(id: Long) {
        _markers.update { list -> list.filterNot { it.id == id } }
    }

    /** Re-label the marker with [id]. An empty [label] draws no text. */
    fun renameMarker(id: Long, label: String) {
        _markers.update { list -> list.map { if (it.id == id) it.copy(label = label) else it } }
    }

    /** Move the playhead to the marker with [id]. */
    fun jumpToMarker(id: Long) {
        val marker = _markers.value.firstOrNull { it.id == id } ?: return
        seekTo(normForFrame(marker.frame))
    }

    /**
     * Jump to the closest marker before the playhead (desktop's `,`). Strictly
     * before, so a repeated press walks back through the list rather than sticking
     * on the marker just landed on; a no-op when there is none.
     */
    fun jumpToPrevMarker() {
        val here = playheadFrame()
        val target = _markers.value.lastOrNull { it.frame < here } ?: return
        seekTo(normForFrame(target.frame))
    }

    /** Jump to the closest marker after the playhead (desktop's `.`). */
    fun jumpToNextMarker() {
        val here = playheadFrame()
        val target = _markers.value.firstOrNull { it.frame > here } ?: return
        seekTo(normForFrame(target.frame))
    }

    /**
     * The frame the playhead sits on. Goes through [Double]: [playhead] is a
     * fraction of the whole clip, and Float carries only ~16.7M exactly (~379 s at
     * 44.1 kHz), so a long clip would land Set A / Set B on the wrong frame.
     */
    private fun playheadFrame(): Int {
        val total = _totalFrames.value
        if (total <= 0) return 0
        return (_playhead.value.coerceIn(0f, 1f).toDouble() * total)
            .roundToLong()
            .coerceIn(0L, total.toLong())
            .toInt()
    }

    /** Inverse of [playheadFrame]: an absolute frame → a whole-clip `0..1` norm. */
    private fun normForFrame(frame: Int): Float {
        val total = _totalFrames.value
        if (total <= 0) return 0f
        return (frame.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
    }

    /** Set the playback speed (base rate), clamped to 0.25×..4×. */
    fun setSpeed(rate: Float) {
        _speed.value = rate.coerceIn(MIN_SPEED, MAX_SPEED)
        applyPlaybackParams()
    }

    /** Reset the playback speed to 1×. */
    fun resetSpeed() {
        setSpeed(1f)
    }

    /** Set the coarse pitch offset in semitones, clamped to -36..+36. */
    fun setPitchSemitones(semitones: Int) {
        _pitchSemitones.value = semitones.coerceIn(-MAX_SEMITONES, MAX_SEMITONES)
        applyPlaybackParams()
    }

    /** Set the fine pitch offset in cents, clamped to -100..+100. */
    fun setPitchCents(cents: Int) {
        _pitchCents.value = cents.coerceIn(-MAX_CENTS, MAX_CENTS)
        applyPlaybackParams()
    }

    /** Reset pitch to 0 semitones / 0 cents. */
    fun resetPitch() {
        _pitchSemitones.value = 0
        _pitchCents.value = 0
        applyPlaybackParams()
    }

    /** Choose pitch-preserve or turntable (varispeed) mode. */
    fun setPlaybackMode(mode: ScrubPlayer.PlaybackMode) {
        _playbackMode.value = mode
        applyPlaybackParams()
    }

    /** Set the master volume as a percentage, clamped to 0..200. */
    fun setVolumePercent(pct: Int) {
        _volumePercent.value = pct.coerceIn(0, MAX_VOLUME_PERCENT)
        applyGain()
    }

    /** Mute or unmute the output (mute forces gain to 0). */
    fun setMuted(muted: Boolean) {
        _muted.value = muted
        applyGain()
    }

    /** Push the current speed / pitch / mode into the player. */
    private fun applyPlaybackParams() {
        player.setPlaybackParams(_speed.value, pitchRatio(), _playbackMode.value)
    }

    /** Push the current volume / mute state into the player as a gain. */
    private fun applyGain() {
        player.setGain(if (_muted.value) 0f else _volumePercent.value / 100f)
    }

    /** pitchRatio = 2^((semitones*100 + cents) / 1200). */
    private fun pitchRatio(): Float =
        2.0.pow((_pitchSemitones.value * 100 + _pitchCents.value) / 1200.0).toFloat()

    /** Dismiss the current [errorMessage], if any. */
    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Choose the rolling-buffer length (seconds), clamped to the desktop's
     * 1..120 s range and persisted. Only takes effect while Instant Replay is off —
     * the ring is allocated at this size when it arms.
     */
    fun setBufferSeconds(sec: Int) {
        if (_isInstantReplayOn.value) return
        val clamped = sec.coerceIn(AppSettings.MIN_BUFFER_SECONDS, AppSettings.MAX_BUFFER_SECONDS)
        _bufferSeconds.value = clamped
        settings.bufferSeconds = clamped
    }

    /**
     * Choose the microphone both capture paths record from, or
     * [InputDevices.SYSTEM_DEFAULT_ID] to leave it to the system. Persisted
     * immediately.
     *
     * Unlike the buffer length this is always accepted, and applies to the *next*
     * capture session: an AudioRecord's preferred device is set once, before it
     * starts, so honouring a change mid-capture would mean tearing the mic down and
     * reopening it under the user (dropping audio from a live Instant-Replay ring
     * or a running recording). Re-arming — or starting the next recording — picks
     * the new device up.
     */
    fun setMicDeviceId(id: Int) {
        _micDeviceId.value = id
        settings.micDeviceId = id
    }

    /**
     * The pickable input devices as `id → label` pairs, always led by a
     * "System default" ([InputDevices.SYSTEM_DEFAULT_ID]) entry — the fallback that
     * is also what an unresolvable id lands on.
     *
     * Filtered to sources a user would call a microphone; the raw list also carries
     * things like the telephony and FM tuner inputs, which this app can't record.
     * Labels are de-duplicated with the device id, since two identical headsets (or
     * a device whose product name is just its type) would otherwise be
     * indistinguishable in the menu. Cheap enough to call when opening the dialog,
     * which is the only caller.
     */
    fun inputDevices(): List<Pair<Int, String>> {
        val entries = mutableListOf(
            InputDevices.SYSTEM_DEFAULT_ID to string(R.string.settings_mic_default),
        )
        val manager = getApplication<Application>().getSystemService(AudioManager::class.java)
            ?: return entries
        val devices = runCatching { manager.getDevices(AudioManager.GET_DEVICES_INPUTS) }
            .getOrNull() ?: return entries

        val labels = mutableSetOf(entries[0].second)
        for (device in devices) {
            val typeName = string(INPUT_TYPE_LABELS[device.type] ?: continue)
            val product = device.productName?.toString()?.trim().orEmpty()
            val base = if (product.isEmpty() || product.equals(typeName, ignoreCase = true)) {
                typeName
            } else {
                string(R.string.settings_mic_device_format, typeName, product)
            }
            // Device ids are unique, so the disambiguated label always is too.
            val label = if (labels.add(base)) {
                base
            } else {
                string(R.string.settings_mic_device_id_format, base, device.id)
            }
            entries += device.id to label
        }
        return entries
    }

    /**
     * Arm or disarm Instant Replay by starting/stopping [InstantReplayService].
     * The service keeps running independently of this ViewModel's lifecycle.
     */
    fun setInstantReplayEnabled(enabled: Boolean) {
        if (enabled == _isInstantReplayOn.value) return
        val context = getApplication<Application>()
        if (enabled) {
            // Mic exclusivity: a manual recording already owns the mic, so refuse to
            // arm and leave the switch off. The state is reconciled from the service.
            if (_uiState.value is UiState.Recording) return
            InstantReplayService.start(context, _bufferSeconds.value, _micDeviceId.value)
        } else {
            InstantReplayService.stop(context)
        }
        // _isInstantReplayOn is not set optimistically here: it mirrors the service's
        // real capture state via the InstantReplayState.running collector, so it
        // never lies when arming fails (mic busy, AudioRecord init error).
    }

    /**
     * Load the clip at [path] into the scrub editor — used when the app is
     * launched from the "clip saved" notification. Surfaces an [errorMessage] if
     * the file is missing or can't be decoded.
     *
     * MainActivity is exported (LAUNCHER), so [path] can come from an external
     * caller: the resolved canonical path is validated to be inside this app's own
     * clips directory before anything is read.
     */
    fun loadClipFromPath(path: String) {
        val file = File(path)
        if (!isInsideClipsDir(file) || !file.exists()) {
            _errorMessage.value = string(R.string.clip_load_failed)
            return
        }
        // Clear any pending hand-off so the foreground observer doesn't double-load.
        ClipEvents.latestClip.value = null
        loadClipFile(file)
    }

    /**
     * True if [file]'s canonical path is inside this app's own clips directory (the
     * same dir [InstantReplayService] writes to). Blocks path-traversal / arbitrary
     * paths from an external launcher.
     */
    private fun isInsideClipsDir(file: File): Boolean {
        val app = getApplication<Application>()
        val base = app.getExternalFilesDir(null) ?: app.filesDir
        val clipsDir = File(base, "clips")
        return try {
            val dirPrefix = clipsDir.canonicalPath + File.separator
            file.canonicalPath.startsWith(dirPrefix)
        } catch (_: IOException) {
            false
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Both stop() and release() block on Thread.join(); run the teardown off
        // the caller (typically the main thread) so clearing the ViewModel never
        // stalls the UI. viewModelScope is already cancelled at this point, so a
        // short-lived plain thread is used instead of a coroutine.
        val recorderRef = recorder
        val playerRef = player
        val videoRef = videoScrubber
        videoScrubber = null
        thread(name = "AudioScratch-teardown") {
            recorderRef.stop()
            playerRef.release()
            // Doesn't block (its worker self-terminates), but it rides along here so
            // every engine is torn down in one place.
            videoRef?.release()
        }
    }

    private fun stopPlaybackInternal() {
        player.pause()
        _isPlaying.value = false
    }

    /**
     * Decode a saved WAV clip off the main thread, then load it like a fresh
     * recording. Shared by the foreground [ClipEvents] observer and
     * [loadClipFromPath]. Reuses the import busy/error idiom.
     */
    private fun loadClipFile(file: File) {
        if (_uiState.value is UiState.Recording || _isImporting.value) return
        // Idempotent: already showing this exact clip — don't re-decode or reset the
        // playhead (e.g. a config-change recreation re-delivering the same intent).
        if (_uiState.value is UiState.Ready && loadedSourcePath == file.absolutePath) return
        stopPlaybackInternal()
        _errorMessage.value = null
        _isImporting.value = true
        viewModelScope.launch {
            val samples = withContext(Dispatchers.IO) {
                runCatching { WavIo.read(file).samples }.getOrNull()
            }
            if (samples == null || samples.isEmpty()) {
                _errorMessage.value = string(R.string.clip_load_failed)
            } else {
                loadSamplesIntoPlayer(samples, file)
            }
            _isImporting.value = false
        }
    }

    /**
     * Load canonical mono [SAMPLE_RATE]Hz 16-bit PCM [samples] into the player,
     * reset the playhead, build the waveform peak index, move to [UiState.Ready]
     * and start playing. Shared by [stopRecording], [importFromUri] and
     * [loadClipFile]; [sourceFile] is the on-disk WAV backing a recording, or
     * `null` for an in-memory import.
     *
     * Suspends: the peak index is one pass over the whole clip and [ScrubPlayer.load]
     * blocks, so both run on a background dispatcher before anything is published to
     * the UI. Every state write below stays on the caller's (main) thread, and
     * [UiState.Ready] is only published once the player really holds the audio.
     *
     * Only ever called for a *completed* load, so the auto-play here can never fire
     * while a recording is in progress.
     */
    private suspend fun loadSamplesIntoPlayer(samples: ShortArray, sourceFile: File?) {
        // Minutes of audio would jank the frame this runs on; keep it off the main thread.
        val peaks = withContext(Dispatchers.Default) { WaveformPeaks(samples) }
        loadedSourcePath = sourceFile?.absolutePath
        // Every load drops the previous file's video: a recording and an
        // Instant-Replay clip have none, so loading one after a video must remove
        // the pane. importFromUri re-attaches afterwards when the new file has one.
        releaseVideo()
        // load() tears the old player down first, joining a render thread that may be
        // parked in a blocking AudioTrack.write() — tens of ms the main thread must
        // not spend. The re-applies ride along so they still land on the fresh track
        // before the UI can reach it.
        withContext(Dispatchers.IO) {
            player.load(samples, SAMPLE_RATE)
            // load() rebuilt the AudioTrack; re-apply the current transport + gain so
            // the freshly loaded clip inherits the user's settings.
            applyPlaybackParams()
            applyGain()
        }
        _playhead.value = 0f
        _durationSeconds.value = samples.size.toFloat() / SAMPLE_RATE
        // A fresh clip starts zoomed to fit.
        _totalFrames.value = samples.size
        _viewWindow.value = clampWindow(0L, samples.size.toLong(), samples.size)
        // Desktop parity (`DeckState::reset`): a new clip drops the old clip's loop
        // and markers — their frames mean nothing here. Cleared before the play()
        // below, so the worker can never wrap on the previous clip's bounds.
        _markers.value = emptyList()
        nextMarkerId = 1L
        updateLoop { LoopRegion(0, 0, false) }
        _uiState.value = UiState.Ready(
            file = sourceFile,
            peaks = peaks,
            samples = samples,
            sampleRate = SAMPLE_RATE,
        )
        // Desktop parity: auto-play as soon as a recording / import / clip loads.
        // This runs only on a completed load, never mid-recording.
        player.play()
        _isPlaying.value = true
    }

    private fun recordingFile(): File = File(getApplication<Application>().filesDir, RECORDING_NAME)

    /**
     * The [AudioDeviceInfo] for [deviceId], or `null` for "system default" — which
     * is also what a device that has since gone away resolves to. Queries the
     * platform, so callers keep it off the main thread.
     */
    private fun preferredInputDevice(deviceId: Int): AudioDeviceInfo? =
        InputDevices.resolve(getApplication(), deviceId)

    private fun string(resId: Int, vararg formatArgs: Any): String =
        getApplication<Application>().getString(resId, *formatArgs)

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val RECORDING_NAME = "recording.wav"

        /**
         * The input types [inputDevices] offers, and the label each gets. Anything
         * not listed here is dropped: the platform's input list also holds sources
         * this app has no business recording from (telephony, FM tuner, the loopback
         * / remote-submix inputs). USB devices and USB headsets share a label —
         * "USB audio" is what the user plugged in either way.
         */
        val INPUT_TYPE_LABELS = mapOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC to R.string.settings_mic_builtin,
            AudioDeviceInfo.TYPE_WIRED_HEADSET to R.string.settings_mic_wired,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to R.string.settings_mic_bluetooth,
            AudioDeviceInfo.TYPE_USB_DEVICE to R.string.settings_mic_usb,
            AudioDeviceInfo.TYPE_USB_HEADSET to R.string.settings_mic_usb,
        )

        /** Tightest waveform zoom, in frames across the whole view. */
        const val MIN_VISIBLE_FRAMES = 64

        /**
         * Narrowest playable A/B loop (~23 ms), matching the player's chunk size so
         * every write stays a full chunk. See [clampLoop].
         */
        const val MIN_LOOP_FRAMES = 1024

        /**
         * Smallest playhead move (media µs, ~40 ms — a frame at 25 fps) that asks
         * for a new video frame. See [requestVideoFrame].
         */
        const val VIDEO_REQUEST_INTERVAL_US = 40_000L

        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        const val MAX_SEMITONES = 36
        const val MAX_CENTS = 100
        const val MAX_VOLUME_PERCENT = 200
    }
}
