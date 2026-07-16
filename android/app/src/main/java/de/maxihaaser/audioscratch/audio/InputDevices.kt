package de.maxihaaser.audioscratch.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Turns a persisted input-device id (see
 * [de.maxihaaser.audioscratch.settings.AppSettings.micDeviceId]) back into the
 * live [AudioDeviceInfo] that [android.media.AudioRecord.setPreferredDevice]
 * wants.
 *
 * Shared by both capture paths — the manual [AudioRecorder] (resolved by the
 * ViewModel, which owns the Context) and
 * [de.maxihaaser.audioscratch.service.InstantReplayService] (which resolves its
 * own, being a Context itself) — so the "id is stale → fall back to the system
 * default" rule is written once.
 */
object InputDevices {

    /** "Let the system pick" — the id stored when no explicit mic is chosen. */
    const val SYSTEM_DEFAULT_ID = -1

    /**
     * The live device with [deviceId], or `null` for "no preference" — which is
     * both what [SYSTEM_DEFAULT_ID] means and what a *stale* id (a headset that
     * has since been unplugged, an id from a previous boot) resolves to. Callers
     * hand a `null` straight to the system default rather than failing: a missing
     * mic must never be a reason to refuse a recording or an arm.
     */
    fun resolve(context: Context, deviceId: Int): AudioDeviceInfo? {
        if (deviceId < 0) return null
        val manager = context.getSystemService(AudioManager::class.java) ?: return null
        return runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == deviceId }
        }.getOrNull()
    }
}
