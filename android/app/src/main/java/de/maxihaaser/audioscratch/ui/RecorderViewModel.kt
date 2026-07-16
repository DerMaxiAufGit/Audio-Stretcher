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
import de.maxihaaser.audioscratch.service.ClipEvents
import de.maxihaaser.audioscratch.service.InstantReplayService
import de.maxihaaser.audioscratch.service.InstantReplayState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException

/** High-level state driving [RecordScrubScreen]. */
sealed interface UiState {
    /** No recording yet — waiting for the user to hit record. */
    data object Idle : UiState

    /** Microphone capture is in progress. */
    data object Recording : UiState

    /** A recording is loaded and ready to scrub / play. */
    class Ready(
        val file: File?,
        val peaks: FloatArray,
        val durationMs: Long,
        val sampleRate: Int,
    ) : UiState
}

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

    /** Whether the hosting Activity is in the foreground (set by MainActivity). */
    private var isForeground = false

    /** Absolute path of the currently loaded source (clip/recording), or `null`. */
    private var loadedSourcePath: String? = null

    init {
        player.onPlayhead = { pos -> _playhead.value = pos }
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
     * reset the playhead, compute the waveform peaks, and move to
     * [UiState.Ready]. Shared by [stopRecording] and [importFromUri];
     * [sourceFile] is the on-disk WAV backing a recording, or `null` for an
     * in-memory import.
     */
    private fun loadSamplesIntoPlayer(samples: ShortArray, sourceFile: File?) {
        loadedSourcePath = sourceFile?.absolutePath
        player.load(samples, SAMPLE_RATE)
        _playhead.value = 0f
        _isPlaying.value = false
        _uiState.value = UiState.Ready(
            file = sourceFile,
            peaks = WavIo.computePeaks(samples, WAVEFORM_BUCKETS),
            durationMs = samples.size * 1000L / SAMPLE_RATE,
            sampleRate = SAMPLE_RATE,
        )
    }

    private fun recordingFile(): File = File(getApplication<Application>().filesDir, RECORDING_NAME)

    private fun string(resId: Int, vararg formatArgs: Any): String =
        getApplication<Application>().getString(resId, *formatArgs)

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val RECORDING_NAME = "recording.wav"
        const val WAVEFORM_BUCKETS = 512
    }
}
