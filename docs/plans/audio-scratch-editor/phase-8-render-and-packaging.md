# Phase 8 — Full timeline render & Win/Linux packaging

- **Goal**: Render the entire layered + FX + multi-clip timeline offline to a correct `.mp4`, and ship the app as an AppImage (Linux) and an installer/zip (Windows) with LGPL notices.

## Scope
- **In**:
  - Extend `OfflineRenderer` from the single-clip take renderer (Phase 3) to walk the **whole** timeline: for each video-layer clip apply its compiled `RtVideoEffectChain` to the source frame (Phase 7 video FX, R19) then composite all video layers (transform/opacity); mix all audio tracks, each clip driven by its own automation → the `IStretcher` selected by `clip.playbackMode` (`BungeeStretcher`/`VarispeedStretcher`, R18) → its compiled audio effect chain.
  - `Encoder`: LGPL-safe `.mp4` (non-GPL H.264 + AAC) defaulting to source res/fps, plus an export-settings dialog and an optional royalty-free `.webm` (VP9/AV1 + Opus) path.
  - Packaging: Linux **AppImage** (+ optional Flatpak), Windows **windeployqt** bundle + installer/zip, both shipping FFmpeg/Qt/Bungee license notices with lib replacement allowed.
  - Confirm the `IStretcher` implementation (Bungee, or Signalsmith fallback) actually builds and links on the chosen Windows toolchain.
  - Cross-OS smoke test exporting a real multi-layer FX project on Manjaro **and** Windows.
- **Explicitly deferred**: auto-update, code-signing/notarization, and Windows MSIX (out of v1 scope); GPU-accelerated compositing in the offline path (correctness over speed per NFR-R — CPU composite is acceptable). *(Per-clip video FX (R19, Phase 7) and both playback modes incl. varispeed (R18, Phase 2) are **now rendered** — no longer deferred.)*

## Depends on
- **Phase 5** — layered video (stacked `VideoLayer`s, PiP/overlay transforms) + the shared **`Compositor`** (`engine/render/Compositor`, the ONE bottom→top src-over blend) + audio-track mixing (`Mixer`).
- **Phase 6** — audio effects rack (`EffectChain`, `effects/*`) as non-destructive inserts.
- **Phase 7** — per-clip **video effects** (colour/chroma-key/glitch): the model-side `VideoEffectChain` (`vector<VideoEffectSpec>`) compiled to a runtime `RtVideoEffectChain` (`process(RGBA frame)`), applied to each clip's source frame **before** the `Compositor` (per plan §6.5). The render reuses this exact chain so keyed/glitched clips export identically to preview (R19).
- Transitively Phases 1–4: `ScrubEngine`/`IStretcher`, `VideoScrubber`/`FrameCache`, the `Project`/`Clip`/`Automation` model + `ProjectIO`, and the Phase 3 `OfflineRenderer`/`Encoder` single-clip skeleton.
- **External prerequisites**: an **LGPL** FFmpeg build (no `--enable-gpl`, no `--enable-nonfree`), a non-GPL H.264 encoder available at runtime (`openh264` and/or a hardware encoder), and a working Windows C++ toolchain (Clang-cl or MinGW per plan §8).

## Tasks

### 1. Full-timeline offline render (MUST)
- **Files**: `src/engine/render/OfflineRenderer.{h,cpp}`.
- **Shape**: extend the existing class with `bool renderProject(const Project& project, const ExportSettings& settings, ProgressFn onProgress)` (Phase 3 gave it a single-`Clip` path; generalise to the `Project` model of plan §6.2). Reuse the SAME `ScrubEngine` + `IStretcher` and `EffectChain` used by live preview so export matches preview (R17).
- **Video pass** — for each output video frame `n = 0 … ⌈D·fps / Project::sampleRate⌉-1` (where `D` = the project's total length in **project-rate frames** = max `clip.timelineEnd()`, a `frame_t`), whose timeline position is the project-rate frame `timelinePos = ⌊n / fps · Project::sampleRate⌋` (a `frame_t`; frames↔seconds always convert via `/ Project::sampleRate` per plan §6.5 — **no frame quantity is ever compared to or subtracted from a seconds quantity**):
  1. Allocate one RGBA canvas at output resolution (preallocated, reused per frame).
  2. Walk `project.videoLayers` **bottom→top** (z-order). For each layer, resolve the active clip + source position with `SequenceMap::resolveTrackAt(layer.clips, timelinePos, Project::sampleRate)` (Phase 4) — its clip-lookup window is `[clip.timelineStart, clip.timelineEnd())` in `frame_t`, where `timelineEnd()`/`timelineDuration()` is the **automation** duration for recorded takes, **never `srcOut-srcIn`** (per plan §6.5); for a take clip it maps the timeline offset to the source frame internally via `automation.playheadPosAt((timelinePos - clip.timelineStart) / Project::sampleRate)` — so there is **no inline seconds-based `timelineStart ≤ t < timelineEnd()` test**. Fetch the decoded picture at the returned `sourcePos` (a `frame_t`) from `VideoScrubber`/`VideoDecoder` (seek-to-keyframe → decode-forward, R20). Then, **before** compositing, run the active clip's compiled **`RtVideoEffectChain::process(frame)`** on that RGBA source frame — the runtime chain built off-RT from the clip's model-side `VideoEffectChain` (Phase 7, per plan §6.5), published/read the same way preview reads it — so keyed/glitched clips render **exactly** as in preview (R19). Then hand the (effected) active per-layer source frames to the shared **`Compositor`** (`engine/render/Compositor`, built in Phase 5, per plan §6.3/§6.5), which performs the ONE bottom→top src-over blend applying each clip's `transform{pos, scale, crop, opacity}` into the canvas — **never re-implement blending or the video FX inline**.
  3. Layers with no active clip, gaps, and **audio-only inputs** contribute nothing; where a video layer is expected but the source has no picture, emit a black/placeholder frame (R21).
  4. Convert canvas RGBA → the encoder pixel format (`AV_PIX_FMT_YUV420P` for H.264) via `sws_scale` and hand the `AVFrame` to `Encoder` with PTS = `n`.
- **Audio pass** — process in fixed blocks (e.g. 1024 **project-rate frames**, `frame_t`) over the whole duration; each block's timeline position `timelinePos` is a `frame_t`. For each `AudioTrack` and each video-layer clip's audio: resolve the active clip + source position via `SequenceMap::resolveTrackAt(track.clips, timelinePos, Project::sampleRate)` (Phase 4 — the **same** active-clip resolution as the video pass; its take branch already applies `automation.playheadPosAt((timelinePos - clip.timelineStart) / Project::sampleRate)`), and read the clip-relative `speed`/`pitch` from `clip.automation` at `(timelinePos - clip.timelineStart) / Project::sampleRate` seconds. Drive the shared `ScrubEngine`/`IStretcher` from that source position — honoring the clip's **playback mode** by selecting the `IStretcher` impl per `clip.playbackMode` (**`BungeeStretcher`** for `PitchPreserving` vs **`VarispeedStretcher`** for `Varispeed`, per plan §6.5 / R18 — the same seam the `ScrubEngine` selects live, so preview and render agree per mode) — to produce that clip's PCM (pitch-preserved in `PitchPreserving`; pitch bending with speed in `Varispeed`), then run it through the clip's **compiled** chain by calling `RtEffectChain::process` on the chain published in the clip's `std::atomic<RtEffectChain*>` — built off-RT by `EffectFactory::build` from the model-side `EffectChain`, per plan §6.5 / Phase 6 (**not** `EffectChain::process`, which does not exist: `EffectChain` is the serializable `vector<EffectSpec>` model). Accumulate into the `Mixer` sum buffer. Resample the mix to the encoder's sample format/rate via `swr_convert` and hand to `Encoder` with the matching PTS.
- **Determinism**: runs non-RT (NFR-R) — no dropouts, no locks-in-callback constraint; may run slower or faster than realtime. Report progress via `onProgress(framesDone/framesTotal)`.
- **(satisfies R12, R19, R18, R10, R11, R17)**

### 2. Encoder + export-settings dialog (MUST)
- **Files**: `src/engine/render/Encoder.{h,cpp}`, `src/engine/render/ExportSettings.h`, `src/ui/ExportDialog.{h,cpp}`.
- **`ExportSettings`** (POD struct): `container {Mp4, Webm}`, `width`, `height`, `fps`, `videoBitrate`, `audioBitrate`, `videoCodecName`, `audioCodecName`. Defaults: `container = Mp4`, `width/height/fps` = source media's (R12).
- **`Encoder`** wraps the FFmpeg mux/encode path:
  - `avformat_alloc_output_context2(…, container=="mp4"?"mp4":"webm", path)`.
  - Video encoder via `avcodec_find_encoder_by_name`, **preference order** (LGPL-safe): hardware first — Linux `h264_vaapi` / `h264_nvenc`; Windows `h264_mf` / `h264_nvenc` / `h264_qsv` / `h264_amf` — then software `libopenh264` (Cisco, BSD). **NEVER `libx264`** (GPL; would break R16). For `.webm`: `libvpx-vp9` or `libaom-av1`. (H.264-via-libx264 is GPL per <https://www.ffmpeg.org/legal.html>.)
  - Audio encoder: `aac` (FFmpeg native, LGPL) for `.mp4`; `libopus` for `.webm`.
  - Standard lifecycle: `avcodec_alloc_context3` → set params/bitrate → `avcodec_open2`; `avformat_new_stream` + `avcodec_parameters_from_context`; `avio_open` → `avformat_write_header`; per frame `avcodec_send_frame` / `avcodec_receive_packet` → `av_interleaved_write_frame` (interleave A/V by PTS); finish with `av_write_trailer`.
- **`ExportDialog`** (`QDialog`): fields for resolution / fps / video+audio bitrate / container, pre-filled with source defaults; on accept builds an `ExportSettings` and launches `OfflineRenderer::renderProject` on a worker thread behind a `QProgressDialog` (never blocks the UI thread). Offer the royalty-free `.webm` option in the container selector (OQ8 — `.webm` path may be marked SHOULD; the LGPL H.264 path is the MUST).
- **(satisfies R12, R16, OQ8)**

### 3. Linux packaging — AppImage (+ optional Flatpak) (MUST)
- **Files**: `packaging/linux/build-appimage.sh`, `packaging/linux/AppDir/usr/share/applications/audioscratch.desktop`, `packaging/linux/AppDir/…/audioscratch.png`, optional `packaging/linux/flatpak/de.maxihaaser.AudioScratch.yml`; add `install()` rules in the root `CMakeLists.txt`.
- **Approach**:
  1. CMake install rules: `install(TARGETS audioscratch RUNTIME DESTINATION bin)`; install the `.desktop` + icon; `install(FILES … DESTINATION share/doc/audioscratch/licenses)` for all notices.
  2. Bundle with `linuxdeploy` + `linuxdeploy-plugin-qt` (or `linuxdeployqt`) to pull Qt 6 libs/plugins into `AppDir/usr/lib`; copy the **LGPL FFmpeg** shared objects (`libavcodec`, `libavformat`, `libavutil`, `libswscale`, `libswresample`) and the `openh264` `.so` alongside them.
  3. **Ship license notices** (R16): FFmpeg `LICENSE.md` + `COPYING.LGPLv2.1`/`LGPLv3` (<https://github.com/FFmpeg/FFmpeg/blob/master/LICENSE.md>), Qt LGPLv3 text (<https://doc.qt.io/qt-6/licensing.html>), Bungee MPL-2.0 (or Signalsmith MIT if that fallback is shipped), miniaudio public-domain notice — into `share/doc/…/licenses` and expose a "Licenses" entry in the app's Help menu. Keep FFmpeg **dynamically linked** and document how to swap the `.so` (R16 lib-replacement).
  4. Optional Flatpak manifest (OQ7 default: AppImage first, Flatpak optional).
- **(satisfies R15, R16)**

### 4. Windows packaging + confirm the stretcher builds (MUST)
- **Files**: `packaging/windows/deploy.ps1`, `packaging/windows/audioscratch.iss` (Inno Setup) and/or a zip step; Windows toolchain notes in the root `CMakeLists.txt`.
- **Approach**:
  1. Build the `.exe` with the chosen toolchain, then run **`windeployqt`** against it to gather Qt DLLs + plugins next to the binary.
  2. Copy the **LGPL FFmpeg** DLLs (`avcodec`, `avformat`, `avutil`, `swscale`, `swresample`) + the `openh264` DLL into the deploy folder; keep them dynamically linked and document replacement (R16).
  3. Ship the same license-notice set as Task 3 (FFmpeg / Qt / Bungee-or-Signalsmith / miniaudio) in a `licenses/` folder and the Help menu (R16).
  4. Package as a **zip** and/or an **Inno Setup** installer.
  5. **Confirm `IStretcher` on Windows** (closes the plan §8 risk): verify **Bungee (MPL-2.0)** compiles + links with the chosen toolchain (Clang-cl or MinGW). If Bungee is a hard blocker on the Windows toolchain (the archived Rust binding cited the MSVC gap — <https://github.com/emuell/bungee-rs>), flip the CMake option to build **Signalsmith Stretch (MIT)** behind the same `IStretcher` seam (<https://github.com/Signalsmith-Audio/signalsmith-stretch>) — no other code changes, since the interface is swappable.
- **(satisfies R15, R16, plan §8)**

### 5. Cross-OS smoke test (MUST)
- **Files**: `tests/fixtures/smoke-multilayer.asproj` (fixture project), `tests/smoke_export.sh` (Linux) + `tests/smoke_export.ps1` (Windows); add an optional headless render entry point in `src/main.cpp` / `src/app/Application` (`--render <in.asproj> --out <out.mp4> [--settings …]`) so smoke export runs without a GUI.
- **Fixture**: a saved `.asproj` (via `ProjectIO`) with **≥2 video layers**, **≥2 clips**, **≥1 audio effect** on a clip, **≥1 video-FX'd clip** (e.g. a chroma-keyed overlay so the key must apply on render, R19), and — if easy — **≥1 `Varispeed` clip** (so both playback modes are exercised, R18) (per Exit S4).
- **Approach**: build the AppImage on Manjaro (primary) and the installer/zip on Windows; run the headless export on the fixture on each OS; assert exit code 0, non-empty `final.mp4`, and `ffprobe` reporting **H.264 video + AAC audio** with duration matching the project. Then manually load the fixture in the GUI on each OS and export via `ExportDialog`, confirming playback in VLC and a browser.
- **(satisfies R15)**

## Deliverables
- `OfflineRenderer` that renders the full `Project` (all video layers composited + all audio tracks mixed with per-clip automation/stretch/FX) to file.
- `Encoder` producing LGPL-safe `.mp4` (non-GPL H.264 + AAC) and optional `.webm` (VP9/AV1 + Opus); `ExportSettings` + `ExportDialog`.
- `packaging/linux/` AppImage recipe (+ optional Flatpak) and `packaging/windows/` windeployqt bundle + installer/zip, each with license notices and CMake `install()` rules.
- A working Windows build of the app whose `IStretcher` (Bungee or Signalsmith) is confirmed to compile/link.
- `tests/fixtures/smoke-multilayer.asproj` + `tests/smoke_export.{sh,ps1}` and a headless `--render` entry point.

## Exit / acceptance criteria (pass/fail)
- [ ] All Task 1–5 deliverables above exist at their stated paths.
- [ ] **Build gate**: `cmake --build` succeeds with warnings-as-errors on **both** Manjaro and Windows; clang-format/clang-tidy (or the project's configured lint) passes.
- [ ] **Correct render** (Task 1/2): rendering `tests/fixtures/smoke-multilayer.asproj` (≥2 video layers, ≥2 clips, ≥1 audio effect, ≥1 video-FX'd clip, and — if present — a `Varispeed` clip) produces `final.mp4` whose overlay layer is visibly composited over the base, whose **chroma-keyed / video-FX'd clip renders with its video FX applied** (the key/glitch is visible in the output, R19), whose audio contains the effect **and reflects both playback modes** (the `Varispeed` clip's pitch bends with speed while the pitch-preserving clip does not, R18), and whose duration equals the project timeline (±1 frame). `ffprobe final.mp4` reports the **h264 + aac** codec families. **R16 is enforced at build time** — the FFmpeg build has **no `--enable-gpl`** and **`libx264` is not linked** — and verified at **runtime** by the `Encoder` **logging its chosen non-GPL encoder** (hardware `h264_vaapi`/`h264_nvenc`/`h264_mf`/`h264_qsv`/`h264_amf`, else `openh264`), like Phase 3's licence gate (per plan §6.5). Do **not** try to distinguish the encoder via `ffprobe`, which reports `h264` regardless of which encoder produced the stream.
- [ ] **Plays everywhere** (S4): `final.mp4` opens and plays in **VLC**, a **browser**, and uploads/plays on **YouTube**.
- [ ] **Deterministic** (S5): two renders of the same project are byte-identical (or frame-hash-identical if the HW encoder is non-deterministic — then bit-exact under the `openh264` software path).
- [ ] **Linux package** (R15/R16): the AppImage runs on Manjaro; its `licenses/` folder contains FFmpeg + Qt + stretcher + miniaudio notices; bundled FFmpeg `.so` files are present, dynamically linked, and replaceable.
- [ ] **Windows package** (R15/R16): the Windows installer/zip runs on Windows; FFmpeg DLLs + Qt DLLs + license notices are bundled; the `IStretcher` build (Bungee or Signalsmith) is confirmed working (plan §8 risk closed).
- [ ] **Cross-OS smoke** (Task 5): `tests/smoke_export.sh` and `tests/smoke_export.ps1` both exit 0 and pass their `ffprobe` assertions.
- [ ] **END-TO-END**: from a **saved multi-layer FX `.asproj`**, exporting via the app produces a `final.mp4` that plays in VLC/browser/YouTube — verified on **both** Manjaro and Windows.

## Independently shippable?
**Yes.** This completes the full vision (plan Milestone B): a user can now edit a whole layered, multi-clip, FX-laden YTP project and **export a finished, correct `.mp4`**, then **install the app** natively on Windows or Linux — the first time the compositor + FX + render + packaging exist as one delivered product (Phases 1–3 shipped only the single-clip scratch-to-mp4 tool).
