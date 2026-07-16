package de.maxihaaser.audioscratch.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.maxihaaser.audioscratch.R
import de.maxihaaser.audioscratch.audio.AudioImporter
import de.maxihaaser.audioscratch.audio.AudioRecorder
import de.maxihaaser.audioscratch.audio.ScrubPlayer
import de.maxihaaser.audioscratch.audio.WavIo
import de.maxihaaser.audioscratch.audio.WaveformPeaks
import de.maxihaaser.audioscratch.service.ClipEvents
import de.maxihaaser.audioscratch.service.InstantReplayService
import de.maxihaaser.audioscratch.service.InstantReplayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
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
 * Owns the record → scrub flow: wires [AudioRecorder] and [ScrubPlayer] together
 * and exposes their state as [StateFlow]s for Compose. Uses [AndroidViewModel]
 * (a [androidx.lifecycle.ViewModel]) because it needs the app's `filesDir`, which
 * requires an [Application] context.
 */
class RecorderViewModel(app: Application) : AndroidViewModel(app) {

    private val recorder = AudioRecorder(SAMPLE_RATE)
    private val player = ScrubPlayer()

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

    /** Rolling-buffer length (seconds) used when Instant Replay is armed. */
    private val _bufferSeconds = MutableStateFlow(InstantReplayService.DEFAULT_SECONDS)
    val bufferSeconds: StateFlow<Int> = _bufferSeconds.asStateFlow()

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

    /** Whether the hosting Activity is in the foreground (set by MainActivity). */
    private var isForeground = false

    /** Absolute path of the currently loaded source (clip/recording), or `null`. */
    private var loadedSourcePath: String? = null

    init {
        // Called on the player's worker thread. StateFlow writes are thread-safe,
        // so both the playhead and the view-window follow are safe to do here.
        player.onPlayhead = { pos ->
            _playhead.value = pos
            followPlayhead(pos)
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
                recorder.start(outFile)
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
    }

    /** Scrub to a normalised position (plays a short grain). */
    fun scrub(norm: Float) {
        if (_uiState.value !is UiState.Ready) return
        // Dragging means the user is steering: cancel continuous playback.
        _isPlaying.value = false
        player.scrub(norm)
        _playhead.value = norm.coerceIn(0f, 1f)
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

    /**
     * Smallest allowed view window. Stands in for the desktop's "1 sample per
     * pixel" zoom limit — the ViewModel doesn't know the waveform's pixel width,
     * and past this point the columns are sample-and-held anyway.
     */
    private fun minVisibleFrames(total: Int): Int = minOf(MIN_VISIBLE_FRAMES, total)

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
     * Choose the rolling-buffer length (seconds). Only takes effect while Instant
     * Replay is off — the ring is allocated at this size when it arms.
     */
    fun setBufferSeconds(sec: Int) {
        if (_isInstantReplayOn.value) return
        _bufferSeconds.value = sec
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
            InstantReplayService.start(context, _bufferSeconds.value)
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
        thread(name = "AudioScratch-teardown") {
            recorderRef.stop()
            playerRef.release()
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

    private fun string(resId: Int, vararg formatArgs: Any): String =
        getApplication<Application>().getString(resId, *formatArgs)

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val RECORDING_NAME = "recording.wav"

        /** Tightest waveform zoom, in frames across the whole view. */
        const val MIN_VISIBLE_FRAMES = 64
        const val MIN_SPEED = 0.25f
        const val MAX_SPEED = 4f
        const val MAX_SEMITONES = 36
        const val MAX_CENTS = 100
        const val MAX_VOLUME_PERCENT = 200
    }
}
