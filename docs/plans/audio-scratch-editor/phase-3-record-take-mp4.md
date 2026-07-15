# Phase 3 — Record a scratch take & render it offline to `.mp4` (Milestone A)

- **Goal**: Capture a live scratch performance as a timestamped **automation
  envelope** and render that envelope **offline** to a playable, pitch-preserved
  **video+audio `.mp4`** — the shippable single-clip scratch-to-mp4 tool.

- **Scope**
  - **In**: an in-memory automation model (`playheadPos(t)`, `speed(t)`,
    `pitch(t)`); a UI-thread recorder that captures the live pointer-driven
    playhead + current pitch/speed; an offline audio renderer that replays the
    envelope through the **same** `ScrubEngine`/`IStretcher` non-real-time; an
    offline video renderer that samples the envelope per output-frame and pulls
    the matching source frame; an FFmpeg H.264(**non-GPL encoder**)+AAC encoder/
    muxer to `.mp4` at source resolution/fps; a Record/Export UI (arm, stop,
    choose path, progress, cancel).
  - **Deferred**: project save/load of the envelope to `.asproj` JSON (**Phase 4**,
    R13); multi-clip / timeline render (**Phase 8**, full R12); layered compositing
    (**Phase 5**); audio effects incl. stutter (**Phase 6**, R11); royalty-free
    `.webm` (VP9/AV1+Opus) export path (**future**, R12/OQ8); undo of edits
    (**Phase 4**, R14). Live scratch motion is intentionally **not** undoable (R14).

- **Depends on**
  - **Phase 1** — `MediaDecoder`/`AudioDecoder`/`VideoDecoder`, `FrameCache`,
    `AudioDevice` (miniaudio), `ScrubEngine` + `IStretcher`/`BungeeStretcher`,
    `VideoScrubber`, always-playing transport with an authoritative playhead
    (source position) exposed to the UI thread via atomics.
  - **Phase 2** — manual pitch control (R6), base speed/rate control (R7), A/B loop
    + markers (R8) — their live values are what the recorder samples.
  - **External** — an **LGPL FFmpeg build with NO `--enable-gpl` / `--enable-nonfree`**
    that includes a non-GPL H.264 encoder: `--enable-libopenh264` (Cisco openh264,
    BSD) and/or hardware encoders (`h264_vaapi`/`h264_nvenc`), plus the native
    `aac` encoder, `libswscale`, `libswresample`. This satisfies R16 — libx264 is
    GPL and MUST NOT be used. See <https://www.ffmpeg.org/legal.html> and
    <https://github.com/FFmpeg/FFmpeg/blob/master/LICENSE.md>.

---

## Tasks

### 1. Automation model + live recorder — `src/engine/model/Automation.{h,cpp}` (+ `src/engine/model/Take.h`, per plan §6.3/§6.5)
- Define `struct Envelope` = time-sorted `std::vector<Key{ double tSeconds; double value; }>`
  (t = seconds since record start), per plan §6.5, with:
  - `void append(double tSeconds, double value)` — appends monotonically; **compaction**:
    before appending, drop the previous key when it is **collinear within epsilon** with
    the key before it and the incoming key (value-based — the middle key's `value` is
    within epsilon of the straight-line interpolation between its two neighbours), so
    holds and constant-slope runs collapse to their endpoints. (A "within epsilon in
    **both** t and value" test would essentially never fire at the ~60 Hz sample rate —
    consecutive keys are ~16 ms apart in `t` — so it would not keep envelopes small;
    collinear-drop is what actually does.)
  - `double valueAt(double tSeconds) const` — **linear interpolation** between bracketing
    keys; clamp to first/last value outside range; empty ⇒ 0.
- Define `struct Automation { Envelope playhead; Envelope speed; Envelope pitch; };`
  (verbatim per plan §6.5) with these downstream methods:
  - `playhead.value` = **source position in project-rate frames** (`frame_t` = int64
    frames, sub-frame as `double`; per plan §6.5 all source/timeline positions are
    project-rate frames — consistent with Phase 1's `DecodedAudio` project-rate PCM). It
    is the authoritative motion curve: negative-slope segments = reverse, zero-slope =
    hold. `speed.value` = dimensionless base-rate **ratio** (R7 control; 1.0 = normal),
    `pitch.value` = **semitones** (R6 control).
  - `frame_t playheadPosAt(double tSeconds)` = `playhead.valueAt(tSeconds)` rounded to `frame_t`.
  - `double durationSeconds()` = max last-key `tSeconds` across the three envelopes.
  - `frame_t durationFrames()` = `durationSeconds() * Project::sampleRate` (project-rate
    frames; this is the recorded clip's on-timeline length in Phase 4).
  - `Automation slice(double t0, double t1)` — sub-range copy (used by Phase 4 clip split).
  - `void clear()`.
- Define `struct Take { MediaId mediaId; Automation automation; PlaybackMode playbackMode; };`
  (verbatim per plan §6.5; `src/engine/model/Take.h`, per plan §6.3/§6.5) — the Take,
  **not** the bare `Automation`, is the unit handed downstream: it binds the
  currently-loaded media id **and the deck's playback mode** (R18) to the recorded
  automation. (`PlaybackMode` is defined in Phase 2, plan §6.5; `Automation` itself is
  unchanged — `playbackMode` lives on the Take, not the Automation.) Used source span =
  `[min(playhead), max(playhead))`.
- Define `class AutomationRecorder` (owns an `Automation` + the currently-loaded
  `MediaId` + the deck's current `PlaybackMode`, **runs on the UI thread only** — keeps
  the RT audio callback untouched, NFR-A):
  - `void arm()` — `clear()`, mark armed, set `t0 = monotonic_now()`.
  - `void tick(double playheadFrames, double baseSpeed, double pitchSemis)` — called
    every UI frame (~60 Hz) **and on every pointer-move event** while armed; computes
    `t = monotonic_now() - t0` and appends to all three envelopes. `playheadFrames` is
    the **source position in project-rate frames** read from the **same** transport atomic
    the UI already reads, so the captured curve contains all information the live audio
    path had.
  - `Take stop()` — clears armed flag and returns
    `Take{ currentMediaId, automation, currentPlaybackMode }`, **stamping** the deck's
    current playback mode (R18, Phase 2) at record time alongside binding the
    currently-loaded media to the finished automation (the Take bundles `mediaId` +
    `automation` + `playbackMode`; the automation on its own is not the take).
- Serialization to JSON is **out of scope here** (Phase 4 `ProjectIO`); keep the
  struct trivially serializable for later.
- *(satisfies R9)*

### 2. Offline **audio** renderer — `src/engine/render/OfflineRenderer.{h,cpp}` (audio path)
- `class OfflineRenderer` drives audio **non-real-time** through the **same**
  `ScrubEngine`/`IStretcher` used for preview (R17/S5 — one code path).
- Reuse `ScrubEngine`'s per-block entry point. If Phase 1 did not already expose a
  pure parameter-driven form, add and route the live transport through it too:
  `void ScrubEngine::renderBlock(float* out, int nFrames, const ScrubParams&)`
  with `struct ScrubParams { double sourcePosFrames; double speed; double pitchSemitones; }`.
- Loop, at project sample-rate `sr` and fixed block size `B` (same `B` as live):
  for output block `k` covering wall-time `[k*B/sr, (k+1)*B/sr)`, let
  `t0 = k*B/sr`, `t1 = (k+1)*B/sr`:
  - `posStartFrames = playhead.valueAt(t0)`; `posEndFrames = playhead.valueAt(t1)` —
    both are **source positions in project-rate frames** (per plan §6.5; here
    `sr == Project::sampleRate`, so no source-rate conversion is needed).
  - `params.sourcePosFrames = posStartFrames`.
  - `params.speed = (posEndFrames - posStartFrames) / B` — the **instantaneous
    source-frame advance per output frame** (dimensionless ratio; equals
    `((posEndFrames - posStartFrames)/sr) / (t1 - t0)`); negative ⇒ reverse, ≈0 ⇒ hold.
    This is the LiveScrub-exact grain speed for Bungee's **position-based**
    `Request{position, speed, pitch}` model (native reverse/near-zero) — see
    <https://github.com/bungee-audio-stretch/bungee/blob/main/bungee/Bungee.h> and
    <https://bungee.parabolaresearch.com/>.
  - `params.pitchSemitones = pitch.valueAt(t0)` (the stretcher maps to
    `2^(semitones/12)` — pitch stays constant/independent of drag ⇒ pitch-preserved).
    `speed.valueAt()` is captured metadata; the **playhead slope above is
    authoritative** for grain speed (do not double-apply base speed).
  - Call `engine.renderBlock(out, B, params)`; append `out` to the output PCM buffer.
- Total output frames `N = round(durationSeconds() * sr)`; produce interleaved
  float PCM (matching preview's channel count). Because each block advances wall-time
  by exactly `B/sr` and samples the same envelope the same way, offline output is a
  **pure deterministic function of the envelope** (S5) and logically identical to
  preview.
- Non-RT freedom: no device callback, no deadline — Bungee may request arbitrarily
  large input chunks with zero underrun risk; correctness over speed (NFR-R).
- *(satisfies R9, R17)*

### 3. Offline **video** renderer — `src/engine/render/OfflineRenderer.{h,cpp}` (video path)
- Default output fps `F` = **source fps** and default resolution = **source
  resolution** (R12). Output frame count `nFrames = round(durationSeconds() * F)`.
- For output frame `f` (output time `t = f / F`):
  - `srcPosFrames = playhead.valueAt(t)` (project-rate frames, per plan §6.5 — the
    **same** envelope the audio path reads ⇒ audio and picture are synced by
    construction; `automation.playheadPosAt(t)` gives the rounded `frame_t`).
  - Fetch the source RGBA frame at `srcPosFrames` via the **same** `VideoScrubber`
    frame-fetch API preview uses (R17). `VideoScrubber` performs frame-accurate
    seek-to-keyframe → decode-forward (R20) through `FrameCache`
    (<https://www2.parkcity.co.uk/blog/ffmpeg-grab-exact-frames-from>); backward
    scratch jumps re-seek as needed — acceptable offline (NFR-R).
  - Hand the RGBA frame to the `Encoder` (Task 4) tagged with output PTS `f`.
- **Audio-only input (R21)**: if the source has no video track, emit a solid
  **black** RGBA frame per output tick at a default `F = 30`, so the deliverable is
  still a well-formed video+audio `.mp4`.
- Emit a progress fraction `f / nFrames` and honour an `std::atomic<bool> cancel`
  each frame (checked before decode/encode) so Export can abort promptly (Task 5).
- *(satisfies R9, R21)*

### 4. FFmpeg encoder + muxer — `src/engine/render/Encoder.{h,cpp}`
- `class Encoder` using `libavformat`/`libavcodec`/`libswscale`/`libswresample`.
  `bool open(path, width, height, fps, sampleRate, channels)`,
  `bool writeVideoFrame(rgba, pts)`, `bool writeAudioBlock(pcm, nFrames)`,
  `bool finish()` (flush + write trailer + close).
- **Container**: mp4 (`avformat_alloc_output_context2(..., "mp4", path)`).
- **Video codec selection — MUST avoid GPL (R16 / OQ8)**. Probe by name in priority
  order and pick the first available via `avcodec_find_encoder_by_name`:
  1. `h264_vaapi`, `h264_nvenc` (Linux hardware) / `h264_mf`, `h264_qsv`,
     `h264_amf` (Windows hardware) — **SHOULD** (fast).
  2. `libopenh264` (Cisco, BSD) — **MUST** be the guaranteed baseline fallback so the
     demo is reproducible on any machine.
  - **Never** `libx264` (GPL, needs `--enable-gpl`) — assert the chosen name ∉
    `{libx264}` and log the chosen encoder. Convert RGBA→`yuv420p`/`nv12` via
    `sws_scale`.
- **Audio codec**: native FFmpeg `aac` encoder (non-GPL). Convert interleaved float
  PCM → the encoder's sample format (`fltp`) via `swr_convert`.
- **Licence guard (R16, MUST)**: at `open()`, assert
  `avcodec_configuration()` contains **neither** `--enable-gpl` **nor**
  `--enable-nonfree`; abort with a clear error otherwise
  (<https://www.ffmpeg.org/legal.html>).
- Set video stream to source `width/height/fps`; mux interleaved A/V by PTS.
- Encoder output need **not** be byte-identical run-to-run (hardware encoders aren't
  deterministic); determinism (S4/S5) is asserted on the **pre-encode** content
  (Task 6), which is a pure function of the envelope.
- *(satisfies R9, R12 [single-clip partial], R16, OQ8)*

### 5. Record / Export UI — `src/ui/RecordExportPanel.{h,cpp}` (per plan §6.3) (+ hooks in `src/ui/TransportBar`)
- Add controls next to the transport:
  - **Arm Record** (toggle): on ⇒ `AutomationRecorder::arm()`; while armed, the UI
    tick and pointer-move handlers call `recorder.tick(playhead, speed, pitch)`.
    A/B-loop jumps during record are captured naturally as playhead discontinuities
    in the envelope (R8 interop).
  - **Stop**: `take = recorder.stop()` (a `Take` binding `mediaId` + `automation`);
    enable **Export** if `take.automation.durationSeconds() > 0`.
  - **Export…**: `QFileDialog::getSaveFileName` (default name `my_scratch.mp4`,
    filter `*.mp4`); then run `OfflineRenderer` on a **worker `QThread`** (never on
    the UI thread) wired to `Encoder`.
  - **Progress + Cancel**: a `QProgressDialog` bound to the renderer's progress
    signal (0–100%); Cancel sets the renderer's `std::atomic<bool> cancel`, which
    aborts the loop and deletes the partial output file.
- Disable Arm/Export appropriately (e.g. no media loaded ⇒ disabled).
- *(satisfies R9)*

### 6. Build wiring + determinism hook — `CMakeLists.txt`, `src/engine/render/OfflineRenderer.{h,cpp}`
- Add the new sources to the engine target; link `libavcodec`, `libavformat`,
  `libavutil`, `libswscale`, `libswresample` (already present from Phase 1 decode)
  and Qt widgets for the UI panel.
- Add an internal verification hook: `uint64_t OfflineRenderer::contentHash()`
  updates a rolling FNV-1a/CRC32 over **every pre-encode audio sample and every
  pre-encode RGBA byte** during a render, exposed via a `--dump-render-hash` debug
  flag on the app (or a `tests/` entry point). Two renders of the same `Automation`
  MUST produce the **same** hash (S4/S5 gate).
- *(satisfies R9, R17)*

---

## Deliverables
- `src/engine/model/Automation.{h,cpp}` — `Envelope`, `Automation`, `AutomationRecorder`;
  `src/engine/model/Take.h` — `Take` (binds `MediaId` + `Automation` + `PlaybackMode`, per plan §6.3/§6.5).
- `src/engine/render/OfflineRenderer.{h,cpp}` — offline audio + video render loops,
  progress/cancel, content-hash hook.
- `src/engine/render/Encoder.{h,cpp}` — FFmpeg H.264(non-GPL)+AAC → `.mp4`, licence guard.
- `src/ui/RecordExportPanel.{h,cpp}` (+ `TransportBar` hooks) — arm/stop/export/progress/cancel.
- Updated `CMakeLists.txt`; optional `tests/` render-hash harness.
- A produced sample `my_scratch.mp4` from a real video clip.

---

## Exit / acceptance criteria (pass/fail)
1. **Build/quality gate**: CMake configure + build succeeds on Linux with no new
   warnings-as-errors; `RecordExportPanel` appears in the running app.
2. **Licence gate (R16)**: at export the log shows the chosen video encoder name ∈
   `{libopenh264, h264_vaapi, h264_nvenc, h264_mf, h264_qsv, h264_amf}` and **never**
   `libx264`; `avcodec_configuration()` contains neither `--enable-gpl` nor
   `--enable-nonfree` (export aborts with an error if it does).
3. **Record produces an envelope (R9)**: arm record, scratch a video clip for ~15 s,
   stop ⇒ `take.automation.durationSeconds()` ≈ 15 s (±0.3 s) and the **playhead**
   envelope has > 100 keys (constant speed/pitch envelopes legitimately compact to far
   fewer under the collinear-drop rule).
4. **Export produces a valid mp4 (R9, R12-partial)**: Export → `my_scratch.mp4`
   exists; `ffprobe -v error -show_entries
   stream=codec_name,codec_type:format=format_name,duration -of default=nw=1
   my_scratch.mp4` reports a `video`/`h264` stream **and** an `audio`/`aac` stream,
   plus a `format_name` containing `mp4` and a `duration` ≈ 15 s — i.e. the command
   actually shows the mp4 container and ~15 s length, not just codec names.
5. **Playable + synced**: the file plays in **VLC** and in a browser
   (Chromium/Firefox); by inspection the **picture follows the scratch** (the frame
   shown at each moment matches the recorded playhead motion, incl. reverse/hold).
6. **Pitch-preserved (R9/S1)**: at any recorded drag speed (incl. reverse and
   near-zero) the exported audio holds **constant pitch** (spot-check a known tone or
   spectral read); no dropouts/clicks baked in (offline path ⇒ glitch-free, S5).
7. **Determinism (S4/S5)**: exporting the **same** stopped take twice yields the
   **same** `OfflineRenderer::contentHash()` (identical pre-encode PCM + RGBA); the
   two mp4s are content-identical even if their encoded bytes differ.
8. **Cancel**: pressing Cancel mid-export stops within one frame/block and leaves no
   partial `.mp4`.
9. **END-TO-END**: load a video clip → Arm Record → scratch back-and-forth for ~15 s
   → Stop → Export → open the produced **`my_scratch.mp4`** in VLC; it plays with
   pitch-preserved audio and picture tracking the scratch.

---

## Independently shippable?
**Yes — this is Milestone A.** A user can now load one audio/video file, perform a
pitch-preserving bidirectional scratch (Phases 1–2), **record the take, and export a
finished, playable `my_scratch.mp4`** with pitch-preserved audio and picture that
follows the scratch — a complete standalone scratch-to-mp4 instrument, with no
timeline, layers, or effects yet.
