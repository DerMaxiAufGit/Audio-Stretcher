package de.maxihaaser.audioscratch.settings

import android.content.Context
import de.maxihaaser.audioscratch.audio.InputDevices
import de.maxihaaser.audioscratch.service.InstantReplayService

/**
 * The app's persisted capture preferences, backed by a single SharedPreferences
 * file. Desktop parity: the Qt build stores the same settings under `capture/` in
 * QSettings, so the keys here mirror those names (`capture.bufferSeconds`,
 * `capture.micDevice`) — only the separator differs, since a `/` in a
 * SharedPreferences key would just be part of the key rather than a group.
 *
 * Deliberately *not* a full mirror of the desktop dialog:
 *  - `capture/source` (desktop / desktop+mic) is omitted — this app is
 *    microphone-only by product decision and never captures device audio.
 *  - `capture/clipHotkey` is omitted — there is no hardware keyboard to bind on a
 *    touch device; the notification's "Clip now" action is the equivalent.
 *
 * Cheap enough for the main thread: the *first* access blocks while the (tiny)
 * prefs file is parsed, every read after that hits the in-memory map, and writes
 * go through `apply()` — in-memory immediately, fsync'd on a background thread.
 */
class AppSettings(context: Context) {

    // applicationContext: an AppSettings built from a shorter-lived Context (an
    // Activity) would otherwise pin it for as long as this store is held.
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Rolling-buffer length for Instant Replay, in seconds. Coerced to
     * [MIN_BUFFER_SECONDS]..[MAX_BUFFER_SECONDS] on the way in *and* out, so a
     * value written by an older/newer build (or hand-edited) can never hand the
     * ring an absurd allocation.
     */
    var bufferSeconds: Int
        get() = prefs.getInt(KEY_BUFFER_SECONDS, DEFAULT_BUFFER_SECONDS)
            .coerceIn(MIN_BUFFER_SECONDS, MAX_BUFFER_SECONDS)
        set(value) {
            prefs.edit()
                .putInt(KEY_BUFFER_SECONDS, value.coerceIn(MIN_BUFFER_SECONDS, MAX_BUFFER_SECONDS))
                .apply()
        }

    /**
     * The chosen input device's [android.media.AudioDeviceInfo.getId], or
     * [InputDevices.SYSTEM_DEFAULT_ID] for "let the system pick".
     *
     * Not validated here: device ids are assigned per boot / per plug-in event, so
     * a persisted one routinely stops resolving (headset unplugged, phone
     * rebooted). [InputDevices.resolve] treats a stale id as "no preference", which
     * is exactly the fallback we want — the id is kept so re-plugging the same
     * device can pick the preference back up.
     */
    var micDeviceId: Int
        get() = prefs.getInt(KEY_MIC_DEVICE, InputDevices.SYSTEM_DEFAULT_ID)
        set(value) {
            prefs.edit().putInt(KEY_MIC_DEVICE, value).apply()
        }

    companion object {
        /** Desktop parity: the buffer spin box's range is 1..120 s, default 30. */
        const val MIN_BUFFER_SECONDS = 1
        const val MAX_BUFFER_SECONDS = 120
        const val DEFAULT_BUFFER_SECONDS = InstantReplayService.DEFAULT_SECONDS

        private const val PREFS_NAME = "audioscratch"
        private const val KEY_BUFFER_SECONDS = "capture.bufferSeconds"
        private const val KEY_MIC_DEVICE = "capture.micDevice"
    }
}
