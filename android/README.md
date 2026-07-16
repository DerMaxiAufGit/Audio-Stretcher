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

## What this MVP does

A single-Activity Compose app with exactly two capabilities:

1. **Record** from the microphone, in-app, to a 16-bit PCM mono WAV file
   (44.1 kHz) in the app's private `filesDir`.
2. **Scrub / play back** that recording:
   - A waveform (downsampled peak amplitudes) drawn on a `Canvas`.
   - Drag the waveform (or the slider) to **scrub** — each drag plays a short
     grain around the dragged position, so it feels like scratching a record.
   - Play / pause, plus elapsed / total time as `m:ss`.

The RECORD_AUDIO runtime permission is requested with a rationale card and a
denied state.

### Audio implementation notes

- `audio/WavIo.kt` — streaming WAV **writer** (placeholder header patched on
  close, so nothing is buffered in RAM while recording), a chunk-scanning WAV
  **reader** → `ShortArray`, and a **peak downsampler** for the waveform.
- `audio/AudioRecorder.kt` — `AudioRecord` capture on a dedicated background
  thread; clean start/stop; releases the recorder in `finally`.
- `audio/ScrubPlayer.kt` — `AudioTrack` in `MODE_STREAM` rendering from an
  in-memory `ShortArray` on one worker thread; `play`/`pause`/`seekTo(norm)`/
  `scrub(norm)`; playhead callback; `release()` tears everything down and is
  reusable via `load()`.
- `ui/RecorderViewModel.kt` — wires recorder + player, exposes `StateFlow`s
  (`uiState`, `playhead`, `isPlaying`), and releases both in `onCleared()`.

No memory or resource leaks: both `AudioRecord` and `AudioTrack` are released,
worker threads are joined on stop, and the ViewModel releases the player when it
is cleared.

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
      audio/{WavIo,AudioRecorder,ScrubPlayer}.kt
      ui/{RecorderViewModel,RecordScrubScreen}.kt
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

> This subproject was authored without a local Android SDK or Gradle, so it has
> not been compiled here. Expect Android Studio to want to add
> `local.properties` (SDK path) and regenerate the wrapper JAR on first open —
> both are git-ignored.

## Roadmap — remaining scope of issue #5

Everything below is **out of scope for this slice** and intentionally not built
yet. It is captured here so the MVP can grow into the full feature set:

- **Media import:** load existing audio/video files (SAF picker), decode via
  `MediaExtractor` / `MediaCodec`, and scrub imported media rather than only
  in-app recordings.
- **Time-stretch DSP:** independent tempo/pitch control (phase-vocoder or
  WSOLA), mirroring the desktop stretcher's behaviour.
- **Instant Replay:** a foreground `Service` continuously buffering recent audio
  with a persistent notification, so the last N seconds can be captured on
  demand (the Android analogue of the desktop Instant Replay feature).
- **Polish:** a proper adaptive launcher icon (the MVP ships without a custom
  icon), variable-speed / reverse scrubbing with real resampling, waveform zoom,
  multiple saved takes, and stereo capture.
