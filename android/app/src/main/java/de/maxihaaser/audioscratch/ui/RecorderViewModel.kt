package de.maxihaaser.audioscratch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.maxihaaser.audioscratch.audio.AudioRecorder
import de.maxihaaser.audioscratch.audio.ScrubPlayer
import de.maxihaaser.audioscratch.audio.WavIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.concurrent.thread

/** High-level state driving [RecordScrubScreen]. */
sealed interface UiState {
    /** No recording yet — waiting for the user to hit record. */
    data object Idle : UiState

    /** Microphone capture is in progress. */
    data object Recording : UiState

    /** A recording is loaded and ready to scrub / play. */
    class Ready(
        val file: File,
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

    init {
        player.onPlayhead = { pos -> _playhead.value = pos }
        player.onCompletion = { _isPlaying.value = false }
    }

    /** Begin (or restart) a microphone recording. Requires RECORD_AUDIO granted. */
    fun startRecording() {
        if (_uiState.value is UiState.Recording) return
        stopPlaybackInternal()
        val outFile = recordingFile()
        if (recorder.start(outFile)) {
            _playhead.value = 0f
            _uiState.value = UiState.Recording
        }
    }

    /** Stop recording, decode the WAV, and hand it to the player. */
    fun stopRecording() {
        if (_uiState.value !is UiState.Recording) return
        val file = recordingFile()
        viewModelScope.launch {
            val prepared = withContext(Dispatchers.IO) {
                recorder.stop()
                runCatching {
                    val wav = WavIo.read(file)
                    Prepared(
                        samples = wav.samples,
                        sampleRate = wav.sampleRate,
                        peaks = WavIo.computePeaks(wav.samples, WAVEFORM_BUCKETS),
                    )
                }.getOrNull()
            }

            if (prepared == null || prepared.samples.isEmpty()) {
                _uiState.value = UiState.Idle
                return@launch
            }

            player.load(prepared.samples, prepared.sampleRate)
            _playhead.value = 0f
            _isPlaying.value = false
            val durationMs =
                if (prepared.sampleRate > 0) {
                    prepared.samples.size * 1000L / prepared.sampleRate
                } else {
                    0L
                }
            _uiState.value = UiState.Ready(
                file = file,
                peaks = prepared.peaks,
                durationMs = durationMs,
                sampleRate = prepared.sampleRate,
            )
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

    private fun recordingFile(): File = File(getApplication<Application>().filesDir, RECORDING_NAME)

    /** Result of the off-thread decode step in [stopRecording]. */
    private class Prepared(
        val samples: ShortArray,
        val sampleRate: Int,
        val peaks: FloatArray,
    )

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val RECORDING_NAME = "recording.wav"
        const val WAVEFORM_BUCKETS = 512
    }
}
