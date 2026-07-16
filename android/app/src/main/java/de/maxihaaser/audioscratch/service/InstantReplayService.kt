package de.maxihaaser.audioscratch.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import de.maxihaaser.audioscratch.MainActivity
import de.maxihaaser.audioscratch.R
import de.maxihaaser.audioscratch.audio.AudioRingBuffer
import de.maxihaaser.audioscratch.audio.InputDevices
import de.maxihaaser.audioscratch.audio.MicGate
import de.maxihaaser.audioscratch.audio.WavIo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Instant Replay ("shadowplay") foreground service. While armed it continuously
 * records the **microphone only** into a fixed-length [AudioRingBuffer] and keeps
 * an ongoing notification with a "Clip now" action. Tapping "Clip now" snapshots
 * the rolling buffer to a mono/44.1kHz/16-bit WAV via [WavIo.Writer], then hands
 * the file to the UI through [ClipEvents] and posts a "Clip saved" notification.
 *
 * The buffer is MIC ONLY — it never captures device / system audio.
 */
class InstantReplayService : Service() {

    // Match the app-wide capture format (see AudioRecorder.kt).
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioEncoding = AudioFormat.ENCODING_PCM_16BIT

    // Set false to ask the capture thread to finish; it then releases AudioRecord.
    @Volatile
    private var capturing = false

    // Set in onDestroy so the capture thread doesn't touch a torn-down service.
    @Volatile
    private var destroyed = false

    // Service callbacks run on the main Looper; used to hop stopForeground/stopSelf
    // back onto it from the capture thread (D1: never join() on the main thread).
    private val mainHandler = Handler(Looper.getMainLooper())

    // Written on the main thread (startCapture) and cleared on the capture thread
    // (requestSelfStop); volatile so handleClip()'s read on the main thread is current.
    @Volatile
    private var ring: AudioRingBuffer? = null
    private var bufferSeconds = DEFAULT_SECONDS

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(
                intent.getIntExtra(EXTRA_SECONDS, DEFAULT_SECONDS),
                intent.getIntExtra(EXTRA_DEVICE_ID, InputDevices.SYSTEM_DEFAULT_ID),
            )
            ACTION_CLIP -> handleClip()
            ACTION_STOP -> handleStop()
        }
        // Don't auto-restart with a null intent: re-arming without the ring size
        // would leave a running-but-idle service. The UI re-arms explicitly.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Just signal the capture thread — it releases AudioRecord + the mic gate in
        // its own finally. No Thread.join() here: onDestroy runs on the main thread.
        destroyed = true
        capturing = false
        super.onDestroy()
    }

    // --- lifecycle -----------------------------------------------------------

    private fun handleStart(seconds: Int, deviceId: Int) {
        // Clamp here rather than trusting the extra: `seconds` sizes the ring
        // (seconds * 44100 shorts, plain Int arithmetic that would wrap), and the
        // service's own start() API promises nothing about its input. Today every
        // caller goes through AppSettings, which already coerces — this is the
        // backstop so a future one can't allocate a garbage-sized buffer.
        val safeSeconds = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)
        bufferSeconds = safeSeconds
        // Must enter the foreground promptly after startForegroundService().
        startForegroundArmed()
        startCapture(safeSeconds, deviceId)
    }

    private fun handleStop() {
        if (capturing) {
            // Signal the capture thread; it releases the mic and self-terminates the
            // service from its finally, so we never block the main thread on join().
            capturing = false
        } else {
            // Nothing capturing — don't linger as a started, non-foreground service.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCapture(seconds: Int, deviceId: Int) {
        if (capturing) return

        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioEncoding)
        if (minBuffer == AudioRecord.ERROR || minBuffer == AudioRecord.ERROR_BAD_VALUE) {
            armFailed()
            return
        }
        // Give the read buffer a healthy cushion (~200 ms) over the minimum.
        val bufferBytes = maxOf(minBuffer, SAMPLE_RATE * 2 / 5)

        val ringBuffer = AudioRingBuffer(seconds * SAMPLE_RATE)
        ring = ringBuffer
        capturing = true
        thread(name = "InstantReplayCapture", priority = Thread.MAX_PRIORITY) {
            // Same-process mic gate: block briefly until any other AudioRecord (a
            // manual AudioRecorder session) has released the microphone. Held for the
            // whole capture and freed in the finally below, so the hand-off is
            // ordered and every exit path frees it.
            if (!MicGate.acquire(GATE_TIMEOUT_MS)) {
                armFailed()
                return@thread
            }
            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    channelConfig,
                    audioEncoding,
                    bufferBytes,
                )
            } catch (_: Exception) {
                // IllegalArgumentException, or a vendor SecurityException/etc. from
                // mic-privacy enforcement — fail arming gracefully instead of letting
                // the capture thread die uncaught (which would crash the process and
                // leave the gate held + a stuck foreground notification).
                MicGate.release()
                armFailed()
                return@thread
            }
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                MicGate.release()
                armFailed()
                return@thread
            }

            // read(ShortArray, …) returns a sample count, which is exactly what
            // the ring buffer consumes.
            val scratch = ShortArray(bufferBytes / 2)
            try {
                // Route capture to the user's chosen input, before startRecording().
                // Best-effort by design: an id that no longer resolves (headset
                // unplugged since it was picked) or a device that refuses the route
                // falls back to the system default — arming must never fail over a mic
                // preference. Inside the try so even an unexpected throw here still
                // hits the finally that frees the mic gate.
                InputDevices.resolve(this@InstantReplayService, deviceId)?.let { device ->
                    runCatching { record.setPreferredDevice(device) }
                }
                record.startRecording()
                // Capture is actually live now — publish the real armed state.
                InstantReplayState.running.value = true
                while (capturing) {
                    val read = record.read(scratch, 0, scratch.size)
                    when {
                        read > 0 -> ringBuffer.write(scratch, read)
                        read < 0 -> break // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT
                        // read == 0: nothing yet, loop and re-check the flag.
                    }
                }
            } catch (_: Throwable) {
                // Stop cleanly; the finally tears everything down.
            } finally {
                try {
                    record.stop()
                } catch (_: IllegalStateException) {
                    // Already stopped / never started — ignore.
                }
                record.release()
                MicGate.release()
                InstantReplayState.running.value = false
            }
            // Capture has ended: self-terminate the service (no main-thread join).
            requestSelfStop()
        }
    }

    /** Arming failed (mic gate/AudioRecord). Clear the armed signal and stop. */
    private fun armFailed() {
        InstantReplayState.running.value = false
        requestSelfStop()
    }

    /** Tear the service down from any thread, hopping onto the main Looper. */
    private fun requestSelfStop() {
        capturing = false
        ring = null
        mainHandler.post {
            if (!destroyed) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    // --- clip ----------------------------------------------------------------

    private fun handleClip() {
        val ringBuffer = ring
        if (ringBuffer == null || !capturing) {
            // Clip requested but nothing is (or ever was) capturing; don't linger.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val samples = ringBuffer.snapshot()
        if (samples.isEmpty()) return
        // Write off the main thread; the rolling buffer keeps running meanwhile.
        thread(name = "InstantReplayClip") {
            val file = runCatching { saveClip(samples) }.getOrNull() ?: return@thread
            // Foreground hand-off to the ViewModel…
            ClipEvents.latestClip.value = file
            // …plus a tappable "clip saved" notification for the background case.
            postClipSavedNotification(file)
        }
    }

    private fun saveClip(samples: ShortArray): File {
        val dir = File(clipsDirPath()).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(System.currentTimeMillis()))
        val file = File(dir, "clip-$stamp.wav")
        WavIo.Writer(file, SAMPLE_RATE, channels = 1).use { writer ->
            // Serialise the mono 16-bit PCM as little-endian bytes for the writer.
            val bytes = ByteArray(samples.size * 2)
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) bb.putShort(s)
            writer.writeBytes(bytes, bytes.size)
        }
        return file
    }

    private fun clipsDirPath(): String {
        val base = getExternalFilesDir(null) ?: filesDir
        return base.absolutePath + "/clips"
    }

    // --- notifications -------------------------------------------------------

    private fun startForegroundArmed() {
        createChannel()
        val notif = buildArmedNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Type "microphone"; on API ≥ 34 this requires the
            // FOREGROUND_SERVICE_MICROPHONE permission (declared in the manifest).
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun createChannel() {
        // minSdk is 26, so NotificationChannel always exists; create it once.
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.ir_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }

    private fun buildArmedNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            REQ_CONTENT,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            pendingFlags(),
        )
        val clipIntent = PendingIntent.getService(
            this,
            REQ_CLIP,
            Intent(this, InstantReplayService::class.java).setAction(ACTION_CLIP),
            pendingFlags(),
        )
        val stopIntent = PendingIntent.getService(
            this,
            REQ_STOP,
            Intent(this, InstantReplayService::class.java).setAction(ACTION_STOP),
            pendingFlags(),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.ir_notif_title))
            .setContentText(getString(R.string.ir_notif_text, bufferSeconds))
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_save, getString(R.string.ir_clip_now), clipIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.ir_stop), stopIntent)
            .build()
    }

    private fun postClipSavedNotification(file: File) {
        createChannel()
        val notifId = savedNotifCounter.getAndIncrement()
        val openIntent = PendingIntent.getActivity(
            this,
            notifId, // unique per clip so each notification opens its own file
            Intent(this, MainActivity::class.java).apply {
                putExtra(EXTRA_CLIP_PATH, file.absolutePath)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            pendingFlags(),
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle(getString(R.string.ir_saved_title))
            .setContentText(getString(R.string.ir_saved_text))
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        getSystemService(NotificationManager::class.java).notify(notifId, notif)
    }

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    companion object {
        const val ACTION_START = "de.maxihaaser.audioscratch.action.INSTANT_REPLAY_START"
        const val ACTION_CLIP = "de.maxihaaser.audioscratch.action.INSTANT_REPLAY_CLIP"
        const val ACTION_STOP = "de.maxihaaser.audioscratch.action.INSTANT_REPLAY_STOP"

        const val EXTRA_SECONDS = "de.maxihaaser.audioscratch.extra.BUFFER_SECONDS"
        const val EXTRA_DEVICE_ID = "de.maxihaaser.audioscratch.extra.MIC_DEVICE_ID"
        const val EXTRA_CLIP_PATH = "de.maxihaaser.audioscratch.extra.CLIP_PATH"

        /** Default rolling-buffer length in seconds. */
        const val DEFAULT_SECONDS = 30

        // Bounds the service enforces on the buffer length before sizing the ring.
        // Deliberately duplicated from AppSettings rather than imported: this is the
        // backstop for a caller that didn't go through the settings layer, so it
        // must not depend on it. Keep the two in step.
        private const val MIN_SECONDS = 1
        private const val MAX_SECONDS = 120

        private const val SAMPLE_RATE = 44_100

        /** Short wait for the mic gate; if the mic is busy, arming just fails. */
        private const val GATE_TIMEOUT_MS = 300L

        private const val CHANNEL_ID = "instant_replay"
        private const val NOTIF_ID = 1001
        private const val SAVED_NOTIF_BASE = 2000

        private const val REQ_CONTENT = 10
        private const val REQ_CLIP = 11
        private const val REQ_STOP = 12

        // Distinct ids/request codes so multiple "clip saved" notifications and
        // their PendingIntents don't collide.
        private val savedNotifCounter = AtomicInteger(SAVED_NOTIF_BASE)

        /**
         * Arm Instant Replay with a rolling buffer of [seconds] seconds, captured
         * from the input device with [deviceId] ([InputDevices.SYSTEM_DEFAULT_ID],
         * the default, leaves the choice to the system).
         */
        fun start(
            context: Context,
            seconds: Int,
            deviceId: Int = InputDevices.SYSTEM_DEFAULT_ID,
        ) {
            val intent = Intent(context, InstantReplayService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SECONDS, seconds)
                putExtra(EXTRA_DEVICE_ID, deviceId)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Ask the running service to save the current rolling buffer as a clip. */
        fun clip(context: Context) {
            context.startService(
                Intent(context, InstantReplayService::class.java).setAction(ACTION_CLIP),
            )
        }

        /** Disarm Instant Replay and stop the foreground service. */
        fun stop(context: Context) {
            context.startService(
                Intent(context, InstantReplayService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
