package de.maxihaaser.audioscratch.audio

/**
 * Process-wide microphone hand-off gate.
 *
 * [de.maxihaaser.audioscratch.service.InstantReplayService] and [AudioRecorder]
 * both open an `AudioRecord` on the MIC source, and they live in the **same
 * process**. Two concurrent MIC sessions corrupt capture, so every component that
 * opens an `AudioRecord` must hold this gate for that recorder's whole lifetime:
 * [acquire] it *before* constructing the `AudioRecord` and [release] it in the
 * *same* finally that releases the `AudioRecord`. That serialises the hand-off —
 * e.g. arming Instant Replay ↔ starting a manual recording — so the microphone is
 * only ever owned by one session at a time.
 *
 * Every [acquire] that returns `true` MUST be matched by exactly one [release],
 * on every exit path (success, init failure, exception), or waiters deadlock.
 */
object MicGate {

    private val lock = Any()
    private var held = false

    /**
     * Block until the mic is free (or [timeoutMs] elapses), then mark it held and
     * return `true`. Returns `false` on timeout. Robust against spurious wakeups
     * (the remaining time is recomputed each loop) and never throws on interrupt —
     * an interrupt just re-checks the deadline, preserving the interrupt flag.
     */
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // (lock as Object) for wait()
    fun acquire(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (held) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) return false
                try {
                    (lock as Object).wait(remaining)
                    // A plain return from wait() is a (possibly spurious) wakeup:
                    // the while-loop re-checks `held` and recomputes `remaining`.
                } catch (_: InterruptedException) {
                    // Never propagate the interrupt as an exception; treat it as a
                    // failed acquire (so no matching release is owed) but preserve
                    // the interrupt flag for the caller.
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            held = true
            return true
        }
    }

    /** Release the mic and wake any thread waiting in [acquire]. */
    @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") // (lock as Object) for notifyAll()
    fun release() {
        synchronized(lock) {
            held = false
            (lock as Object).notifyAll()
        }
    }
}
