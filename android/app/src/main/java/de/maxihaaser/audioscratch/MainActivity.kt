package de.maxihaaser.audioscratch

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import de.maxihaaser.audioscratch.service.InstantReplayService
import de.maxihaaser.audioscratch.ui.RecordScrubScreen
import de.maxihaaser.audioscratch.ui.RecorderViewModel
import de.maxihaaser.audioscratch.ui.theme.AudioScratchTheme

/**
 * Single-Activity entry point. Owns the RECORD_AUDIO / POST_NOTIFICATIONS runtime
 * permission flows via [ActivityResultContracts.RequestPermission], hosts the
 * Compose UI, and routes "clip saved" notification taps into the ViewModel.
 */
class MainActivity : ComponentActivity() {

    // Same instance Compose resolves via viewModel(); held here so onNewIntent can
    // reach it. Default AndroidViewModel factory supplies the Application context.
    private val viewModel: RecorderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Launched (cold) from the "clip saved" notification?
        handleClipIntent(intent)
        setContent {
            AudioScratchTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val context = LocalContext.current
                    var hasPermission by remember {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED,
                        )
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        hasPermission = granted
                    }

                    // POST_NOTIFICATIONS (API 33+): the foreground-service and
                    // "clip saved" notifications won't show without it. We request
                    // it as the user arms Instant Replay; arming proceeds either
                    // way (it takes effect on the next arm if just granted).
                    val notificationPermissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission(),
                    ) { /* result ignored: the service arms regardless */ }

                    // OpenDocument gives read access to a single picked file. The
                    // audio is decoded once, up front, but the video pane keeps
                    // seeking the Uri for the whole session — so persist the grant,
                    // which otherwise dies with this Activity instance (a rotation
                    // would leave the picture stuck).
                    val importLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument(),
                    ) { uri ->
                        if (uri != null) {
                            takePersistableReadPermission(uri)
                            viewModel.importFromUri(uri)
                        }
                    }

                    RecordScrubScreen(
                        viewModel = viewModel,
                        hasPermission = hasPermission,
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        onImport = {
                            importLauncher.launch(arrayOf("audio/*", "video/*"))
                        },
                        onSetInstantReplay = { enabled ->
                            if (enabled &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS,
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS,
                                )
                            }
                            viewModel.setInstantReplayEnabled(enabled)
                        },
                        onSetBufferSeconds = viewModel::setBufferSeconds,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Delivered here (instead of a new instance) thanks to the PendingIntent's
        // FLAG_ACTIVITY_SINGLE_TOP.
        setIntent(intent)
        handleClipIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        // Foreground: let the ViewModel auto-load clips handed off by the service.
        viewModel.setForeground(true)
    }

    override fun onStop() {
        // Backgrounded: a saved clip must not silently replace the loaded session.
        viewModel.setForeground(false)
        super.onStop()
    }

    /**
     * Hold on to read access for [uri] beyond this Activity instance, so the video
     * pane can keep decoding frames from it.
     *
     * Best-effort: not every provider offers a persistable grant (it throws
     * [SecurityException] when it doesn't), and the import works regardless — the
     * audio is decoded immediately, under the one-shot grant the picker already
     * gave us. Only the picture would be lost, and only after a recreation.
     */
    private fun takePersistableReadPermission(uri: Uri) {
        // Drop the grants held for earlier imports first: only the current file is
        // ever read back, and the per-app persisted-grant table is capped (a few
        // hundred entries), so holding one per import would eventually start
        // throwing and cost the user the picture.
        for (held in contentResolver.persistedUriPermissions) {
            if (held.uri != uri) {
                try {
                    contentResolver.releasePersistableUriPermission(
                        held.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                } catch (_: SecurityException) {
                    // Already gone — nothing to release.
                }
            }
        }
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Non-persistable provider — nothing to do.
        }
    }

    private fun handleClipIntent(intent: Intent?) {
        intent ?: return
        val path = intent.getStringExtra(InstantReplayService.EXTRA_CLIP_PATH) ?: return
        viewModel.loadClipFromPath(path)
        // Consume the extra so a config-change recreation (rotation) can't reload the
        // same clip; loadClipFromPath is idempotent as a second line of defence.
        intent.removeExtra(InstantReplayService.EXTRA_CLIP_PATH)
        setIntent(intent)
    }
}
