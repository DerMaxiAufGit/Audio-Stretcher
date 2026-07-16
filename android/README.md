# AudioScratch (Android)

A native Android companion app for the AudioStretcher project. This is the first
slice of [issue #5 — "Android native App"](../../../issues/5).

It is a **fresh, standalone** Kotlin + Jetpack Compose app. It does **not** share
any C++/Qt code with the desktop application and uses only Android-native audio
APIs (`AudioRecord` / `AudioTrack`). It lives in this repo as a subproject under
`android/` but builds completely independently of the desktop CMake/Qt build.

- **Package / namespace:** `de.maxihaaser.audioscratch`
- **App name:** AudioScratch
- **Min SDK:** 26 (Android 8.0) · **Target/Compile SDK:** 35 · **JVM target:** 17

## What this app does

A single-Activity Compose app. Everything works in one canonical audio format —
**mono, 44.1 kHz, 16-bit PCM** — end to end, so recordings, imports, and clips
all flow through the same scrub/playback path.

1. **Record** from the microphone, in-app, to a 16-bit PCM mono WAV file
   (44.1 kHz) in the app's private `filesDir`.
2. **Scrub / play back** that recording:
   - A waveform drawn on a `Canvas` as a **min/max peak envelope**, one column
     per pixel, for the currently visible window.
   - Drag the waveform (or the position slider) to **scrub** — each drag plays a
     short grain around the dragged position, so it feels like scratching a record.
   - Play / pause, plus elapsed / total time as `m:ss`.
3. **Import** any audio *or* video file (SAF picker, `audio/*` + `video/*`):
   decoded via the platform `MediaExtractor` / `MediaCodec` stack — the first
   audio track wins, so a video decodes to just its soundtrack — then downmixed
   to mono and resampled to 44.1 kHz and loaded straight into the scrubber.
4. **Instant Replay** ("shadowplay"), **microphone only**: a foreground service
   continuously keeps the **last N seconds** (15 / 30 / 60 s, default 30) of mic
   audio in a rolling ring buffer, with an ongoing notification carrying a
   **"Clip now"** button. Tapping it saves that window as a WAV and loads it into
   the scrubber (in-app when foregrounded, or via a "Clip saved" notification).
   It never captures device / system audio.
5. **Transport / DSP controls** matching the desktop app:
   - **Speed** 0.25×–4× (log-mapped slider, reset to 1×).
   - **Pitch** ±36 semitones + ±100 cents fine (reset to 0), independent of speed.
   - **Playback mode**: *Pitch-preserve* (default) ⇄ *Turntable* (varispeed — pitch
     rides the rate, like vinyl).
   - **Volume** 0–200% with mute, and an `M:SS.CC / M:SS.CC` time readout.
   - Auto-play as soon as a recording / import / clip loads.

   The time-stretch is Android's built-in **Sonic** engine, driven via
   `AudioTrack.setPlaybackParams` — pitch-preserve maps to `speed=rate,
   pitch=pitchRatio`; turntable maps to `speed=rate, pitch=rate × pitchRatio`.
   No hand-written DSP.
6. **Waveform zoom / scroll + time ruler**, matching the desktop:
   - **Pinch** with two fingers to zoom and pan; one finger still scrubs. Buttons
     for **−** / **Fit** / **+** (1.3× per step, centred on the zoom point), and a
     **Pan** scrollbar once zoomed in (distinct from the whole-clip **Position**
     slider, which seeks).
   - Zoom clamps at the whole clip (fit) on the way out; on the way in it stops at
     a 64-frame window — the stand-in for the desktop's 1 sample/pixel limit.
   - During playback the view **recentres when the playhead leaves the window**.
   - An **adaptive time ruler** above the waveform picks the tick interval that
     keeps labels ~72 dp apart (10 ms … 6 h), with 5 minor ticks per major and a
     label format that adapts (`M:SS`, `M:SS.C`, `M:SS.CC`, `H:MM:SS`).

Runtime permissions: RECORD_AUDIO is requested with a rationale card and a denied
state; POST_NOTIFICATIONS (API 33+) is requested when arming Instant Replay. The
service declares `foregroundServiceType="microphone"` (+ FOREGROUND_SERVICE /
FOREGROUND_SERVICE_MICROPHONE). Only one component can hold the mic, so a shared
same-process gate hands the microphone off between manual recording and Instant
Replay (arming is blocked while a manual recording is in progress).

### Audio implementation notes

- `audio/WavIo.kt` — streaming WAV **writer** (placeholder header patched on
  close, so nothing is buffered in RAM while recording), a chunk-scanning WAV
  **reader** → `ShortArray`, and a **peak downsampler**.
- `audio/WaveformPeaks.kt` — the waveform's **min/max envelope index**: per-bucket
  extremes over 64 frames, built once per load off the main thread. `columns()`
  fills caller-supplied arrays with the per-pixel envelope of the visible window,
  aggregating buckets when zoomed out (`framesPerPixel >= bucketFrames`) and
  scanning raw PCM when zoomed in past that — so drawing never allocates and
  stays O(visible).
- `audio/AudioRecorder.kt` — `AudioRecord` capture on a dedicated background
  thread; clean start/stop; releases the recorder in `finally`.
- `audio/ScrubPlayer.kt` — `AudioTrack` in `MODE_STREAM` rendering from an
  in-memory `ShortArray` on one worker thread; `play`/`pause`/`seekTo(norm)`/
  `scrub(norm)`; playhead callback; `release()` tears everything down and is
  reusable via `load()`.
- `audio/AudioImporter.kt` — decodes an arbitrary `content://` audio/video file
  to mono 44.1 kHz 16-bit PCM using `MediaExtractor` + `MediaCodec` (16-bit and
  float PCM outputs, channel downmix, linear resample); cancellable and capped at
  ~10 min to bound memory.
- `audio/AudioRingBuffer.kt` — a fixed-capacity, thread-safe mono ring buffer;
  `snapshot()` returns the last N seconds in chronological order.
- `audio/MicGate.kt` — a process-wide gate serialising microphone ownership
  between the manual recorder and the Instant Replay service (ordered hand-off,
  every `acquire()` matched by a `release()` on all exit paths).
- `service/InstantReplayService.kt` — the mic-only foreground service: rolling
  capture into the ring, the ongoing "Clip now" notification, and clip-to-WAV.
- `service/ClipEvents.kt` — process-wide `StateFlow`s handing a saved clip and
  the live armed state to the ViewModel.
- `ui/RecorderViewModel.kt` — wires recorder + player + importer + Instant Replay,
  exposes `StateFlow`s (`uiState`, `playhead`, `isPlaying`, `isImporting`,
  `errorMessage`, `isInstantReplayOn`, `bufferSeconds`), owns the waveform's view
  window (`totalFrames`, `viewStartFrame`, `viewFrames` + `zoomBy`/`zoomToFit`/
  `panByFrames`/`setViewStartFrame`), and releases both audio engines in
  `onCleared()`.
- `ui/TimeRuler.kt` — the adaptive ruler over the waveform's visible window
  (nice-step tick interval, minor ticks, scale-dependent labels).

No memory or resource leaks: `AudioRecord` and `AudioTrack` are released, worker
threads are joined/self-terminate on stop, the mic gate is released on every exit
path, and the ViewModel releases the player when it is cleared.

## Project layout

```
android/
  settings.gradle.kts        # rootProject "AudioScratch"; include(":app")
  build.gradle.kts           # root plugins (apply false)
  gradle.properties
  gradle/wrapper/gradle-wrapper.properties   # Gradle 8.11.1
  gradlew / gradlew.bat
  app/
    build.gradle.kts         # android{} + Compose + inlined dependency versions
    src/main/AndroidManifest.xml
    src/main/res/…           # strings, themes, backup/data-extraction rules
    src/main/java/de/maxihaaser/audioscratch/
      MainActivity.kt
      audio/{WavIo,WaveformPeaks,AudioRecorder,ScrubPlayer,AudioImporter,AudioRingBuffer,MicGate}.kt
      service/{InstantReplayService,ClipEvents}.kt
      ui/{RecorderViewModel,RecordScrubScreen,TimeRuler}.kt
      ui/theme/{Color,Theme,Type}.kt
```

Versions are **inlined** in the build scripts on purpose (no Gradle version
catalog) so everything is visible in one place:

- Gradle **8.11.1**, Android Gradle Plugin **8.7.3**
- Kotlin **2.0.21** + the Compose compiler Gradle plugin
  (`org.jetbrains.kotlin.plugin.compose`)
- Compose BOM **2024.12.01**

## Build & run

Open the `android/` directory (not the repo root) as a project in **Android
Studio** (Ladybug or newer), let it sync, then Run on a device or emulator with
a microphone. Grant the microphone permission when prompted, record, then drag
the waveform to scrub.

Command line (once the wrapper JAR exists — see below):

```sh
cd android
./gradlew :app:assembleDebug        # build the debug APK
./gradlew :app:installDebug         # install on a connected device
```

### ⚠️ Missing `gradle/wrapper/gradle-wrapper.jar`

The Gradle **wrapper JAR** is a binary and is intentionally **not** checked in
here (it cannot be hand-written). The `gradlew` / `gradlew.bat` scripts and
`gradle-wrapper.properties` are present, but you must generate the JAR once:

- **Easiest:** open `android/` in Android Studio — it regenerates the wrapper
  (including the JAR) on the first Gradle sync, or
- **CLI:** with a system Gradle 8.x installed, run `gradle wrapper
  --gradle-version 8.11.1` inside `android/`.

Until the JAR exists, `./gradlew` will fail with
`Could not find or load main class org.gradle.wrapper.GradleWrapperMain`.

> Once the wrapper JAR and a `local.properties` (SDK path) exist, the app builds
> with a standard `./gradlew :app:assembleDebug` (JDK 17–21) and produces a debug
> APK. `local.properties` is git-ignored; the wrapper JAR is intentionally not
> committed (regenerate it as above).

## Desktop parity

The app now covers the desktop application's feature set: in-app recording,
waveform scrubbing, **audio/video import**, **mic-only Instant Replay**, the
**transport / DSP controls** (speed, pitch, pitch-preserve ⇄ turntable,
volume/mute), **waveform zoom / scroll** with an adaptive **time ruler**,
**A/B loop**, **markers**, **video-frame scrubbing**, and a persisted
**settings** surface.

Features the desktop app doesn't have either (and so aren't parity gaps): project
save/load, undo/redo, an effects rack, a multi-clip timeline, render-to-MP4,
drag-and-drop, and a recent-files list.

### Deliberate divergences from the desktop

- **Instant Replay is microphone-only.** The desktop captures the output mix
  (WASAPI loopback / PulseAudio monitor) with an optional mic mix; that was a
  product decision for this app, and Android has no equivalent unrestricted
  system-audio capture anyway. The settings dialog therefore drops the desktop's
  *capture source* and *mic device mix* options, keeping a plain input picker.
- **No clip hotkey.** The desktop's `Ctrl+Alt+R` has no touch equivalent — the
  ongoing notification's **Clip now** action is the stand-in, so the hotkey
  setting is omitted.
- **Touch gestures replace mouse ones.** Pinch replaces wheel-zoom; long-press a
  marker flag replaces right-click; buttons replace the `M` / `,` / `.` keys.
- **Time-stretch is Android's Sonic engine**, not the desktop's Bungee/Signalsmith
  pair, and the scrub grain is not a position-based stretcher — so extreme-speed
  scrub artefacts won't sound identical to the desktop.
- **Video seeks to keyframes** (`OPTION_CLOSEST_SYNC`), mirroring the desktop's
  keyframe-seek approach. On a long-GOP file the picture can therefore sit behind
  the playhead until the next keyframe.

### Remaining polish

- A proper adaptive launcher icon (ships without a custom one).
- Multiple saved takes; exporting clips to shared storage (MediaStore) rather than
  app-private `filesDir`.
- Stereo capture (the pipeline is mono end to end).
- A loop wrap that resets the stretcher, as the desktop does, to remove the small
  seam discontinuity.

### Known residual risks

- Instant Replay's mic gate is released when the capture loop's blocking
  `AudioRecord.read()` returns. If the audio HAL ever stalls a read indefinitely
  (rare vendor/driver/Bluetooth-handoff bug), the gate could stay held until the
  process is killed. There is no read-timeout watchdog yet.
- **None of this has been exercised on a physical device or emulator** — it is
  compile-verified and reviewed only. Audio routing, notification behaviour,
  codec quirks, and gesture feel all need a real device pass.
