package de.maxihaaser.audioscratch.service

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/**
 * Process-wide hand-off for freshly saved Instant Replay clips.
 *
 * [InstantReplayService] runs in the same process as the UI, so when it writes a
 * clip it publishes the file here; [de.maxihaaser.audioscratch.ui.RecorderViewModel]
 * observes [latestClip] and auto-loads the clip into the scrub editor while the
 * app is in the foreground. The observer resets the value to `null` after
 * consuming it so a recomposition / config change doesn't reload the same clip.
 */
object ClipEvents {
    /** The most recently saved clip, or `null` when there is nothing pending. */
    val latestClip = MutableStateFlow<File?>(null)
}

/**
 * Process-wide "is Instant Replay actually capturing?" signal.
 *
 * [InstantReplayService] flips [running] to `true` only once its `AudioRecord` is
 * live, and back to `false` when capture stops or arming fails (mic gate timeout /
 * init error). [de.maxihaaser.audioscratch.ui.RecorderViewModel] observes it to
 * reconcile the Instant Replay switch with the service's real state instead of
 * optimistically trusting the toggle request.
 */
object InstantReplayState {
    /** True only while the Instant Replay capture is actually running. */
    val running = MutableStateFlow(false)
}
