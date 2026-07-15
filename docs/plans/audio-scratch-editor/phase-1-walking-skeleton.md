# Phase 1 — Walking Skeleton: pitch-preserving bidirectional scrub + synced video

- **Goal**: Load one audio/video clip and drag the playhead back-and-forth to hear
  it play in real time at **constant pitch** (any speed, incl. reverse and
  near-zero, glitch-free) with the **video frame tracking the playhead**, on an
  always-playing transport.

- **Scope**:
  - **In**: CMake scaffold + empty Qt6 window that **builds on Linux+Windows** but is
    only guaranteed to **run/open on Linux** (the primary target); the Windows C++
    build is compile-validated by the Phase-1 Bungee spike (exit criterion 2) and a
    full Windows *run* is gated in Phase 8 (packaging); the `IStretcher`
    seam with a **Bungee** impl (primary) and **Signalsmith** impl (fallback); a
    **Windows Bungee build spike** (documented outcome); full-decode of the audio
    stream to interleaved f32 stereo PCM in RAM (a `DecodedAudio`) at the **project
    rate** (`Project::sampleRate`, default 48000 Hz — per plan §6.5) with
    sample-accurate random access;
    a min/max **peak** waveform with playhead + zoom + horizontal scroll; a
    miniaudio RT device + **ScrubEngine** driving Bungee's position-based grain API;
    an always-playing transport with pointer-down scrub; **synced video scrub** via
    FFmpeg keyframe-seek→decode-forward + an LRU frame cache + hardware decode + a
    GPU-textured `VideoView`.
  - **Deferred**: manual pitch/speed controls, A/B loop, markers (Phase 2);
    record + offline `.mp4` render (Phase 3); timeline/model/project IO (Phase 4);
    layers/compositing (Phase 5); intra-frame **proxy** transcode fallback
    (Phase 5 / OQ5 — the `Proxy` unit is *not* built here, only noted); effects
    (Phase 6); packaging (Phase 8). No manual pitch shift in Phase 1: pitch is
    fixed at the source pitch (`Request.pitch = 1.0`).

- **Depends on**: nothing (first phase). External prerequisites: a C++17/20
  toolchain + CMake ≥ 3.21; **Qt 6** (LGPLv3, dynamically linked — R16); an
  **FFmpeg LGPL** build with dev headers (`libavformat`, `libavcodec`,
  `libavutil`, `libswresample`, `libswscale`) built **without**
  `--enable-gpl`/`--enable-nonfree` (R16, FFmpeg legal
  <https://www.ffmpeg.org/legal.html>); vendored `miniaudio.h`, `bungee/`,
  `signalsmith-stretch/`. A real **YouTube-downloaded H.264 1080p** clip on disk
  for the Task 7 validation.

---

## Tasks

### 1. Project scaffold + empty cross-platform window (MUST)
- **Files**: `CMakeLists.txt`; `src/main.cpp`; `src/app/Application.{h,cpp}`;
  `src/app/MainWindow.{h,cpp}`; vendor `third_party/miniaudio.h`,
  `third_party/bungee/`, `third_party/signalsmith-stretch/`.
- **Approach**:
  - `CMakeLists.txt`: `find_package(Qt6 REQUIRED COMPONENTS Widgets Gui Core)`;
    locate FFmpeg via `pkg_check_modules` (`libavformat libavcodec libavutil
    libswresample libswscale`) with a Windows fallback to explicit
    include/lib dirs. Add `third_party/` as an include dir. Define an
    `audioscratch` executable target. `set(CMAKE_AUTOMOC ON)` for Qt. Turn on
    `-ffast-math`-free strict FP for the engine target; keep warnings high.
  - `main.cpp`: instantiate `Application` (a `QApplication` subclass), create and
    `show()` a `MainWindow`, run the event loop.
  - `MainWindow`: a `QMainWindow` with a vertical split — top area reserved for
    `VideoView` (Task 7), middle for `WaveformView` (Task 4), bottom for
    `TransportBar` (Task 6). A **File → Open** action wired to `QFileDialog`.
  - Vendor Bungee **source** (MPL-2.0) and Signalsmith (MIT) as in-tree subdirs;
    add Bungee's sources to the build (or its own CMake) — do not rely on a Rust
    binding. `miniaudio.h` is single-file: compile the implementation in exactly
    one `.cpp` with `#define MINIAUDIO_IMPLEMENTATION` (Task 5).
  - **Done-condition**: `cmake --build` **compiles on both** Linux and Windows, and
    produces a runnable binary that shows an empty window **on Linux** (the primary
    target). The Windows C++ build is compile-validated by the Phase-1 Bungee spike
    (exit criterion 2); a full Windows *run* is not gated here — it is deferred to
    Phase 8 (packaging). This leaves no unverified Windows acceptance claim.
  - (satisfies R15)

### 2. `IStretcher` seam + Bungee/Signalsmith impls + Windows Bungee SPIKE (MUST — do EARLY, highest risk)
- **Files**: `src/engine/audio/IStretcher.h`;
  `src/engine/audio/BungeeStretcher.{h,cpp}`;
  `src/engine/audio/SignalsmithStretcher.{h,cpp}` *(the `IStretcher` seam — per plan
  §6.3/§6.4; all three are now listed in §6.3's `audio/` group)*.
- **Approach**:
  - **`IStretcher.h`** — a pure-virtual, RT-friendly interface abstracting the
    stretcher so the engine is swappable (mitigates the §8 Bungee/MSVC risk):
    ```
    struct StretchRequest { double positionFrames; double speed; double pitch; bool reset; };
    class IStretcher {
      virtual void  prepare(int sampleRate, int channels, int maxOutputFrames) = 0; // preallocate
      virtual int   maxInputFrameCount() const = 0;
      // Pull-model: produce up to `outFrames` interleaved-f32 output for the given
      // request, reading source PCM through `SampleSource` (absolute frame reads).
      virtual int   process(const StretchRequest&, float* out, int outFrames, SampleSource&) = 0;
      virtual bool  isFlushed() const = 0;
    };
    ```
    `SampleSource` is a lightweight callback/interface giving random access to the
    in-RAM PCM (Task 3) — no allocation, no locking.
  - **`BungeeStretcher`** — wraps `Bungee::Stretcher<Bungee::Basic>` constructed with
    `Bungee::SampleRates{ input, output }` and channel count. Per `process()`:
    build a `Bungee::Request{ position, speed, pitch, reset }`; call
    `specifyGrain(request)` → `Bungee::InputChunk{ begin, end }` (absolute input
    frame range), copy those source frames from `SampleSource` into a preallocated
    analysis buffer, `analyseGrain(data, channelStride)`, `synthesiseGrain(
    OutputChunk&)`, then advance with `next(request)`; buffer synthesised grains in
    a **preallocated output FIFO** and copy exactly `outFrames` into `out`. Use
    `preroll(request)` on `reset`. The **absolute `Request.position` per grain** is
    what makes reverse/zero speed native (Bungee header
    <https://github.com/bungee-audio-stretch/bungee/blob/main/bungee/Bungee.h>).
  - **`SignalsmithStretcher`** — same interface over
    `signalsmith::stretch::SignalsmithStretch<float>`; forward-only, so implement
    reverse by feeding source frames in reversed order and using `seek()`/`reset()`
    on direction changes. This is the **fallback path** if the Windows Bungee build
    is a hard blocker (Signalsmith <https://github.com/Signalsmith-Audio/signalsmith-stretch>).
  - **SPIKE**: attempt the **Bungee C++ build on Windows** — try **MSVC** first,
    then **clang-cl**, then **MinGW**; the archived Rust binding was dropped over
    MSVC (<https://github.com/emuell/bungee-rs>), so verify our own C++ build rather
    than assume. Record: which compiler(s) built cleanly, any patches needed, and
    whether the fallback must be the default on Windows.
  - **Done-condition**: a tiny offline harness (in `tests/` or a `--selftest` flag)
    stretches a synthetic ramp at speeds `{+1.0, 0.0, -1.0}` through
    `BungeeStretcher` on Linux and produces finite, click-free output; the Windows
    Bungee build outcome is written into this doc's **Deliverables**.
  - (satisfies R3; addresses plan §8 Bungee/MSVC risk)

### 3. Audio decode → full PCM in RAM with sample-accurate access (MUST)
- **Files**: `src/engine/decode/MediaDecoder.{h,cpp}`;
  `src/engine/decode/AudioDecoder.{h,cpp}`;
  `src/engine/decode/DecodedAudio.{h,cpp}` *(the in-RAM PCM holder — per plan
  §6.3/§6.5)*.
- **Approach**:
  - **`MediaDecoder`** — owns the container: `avformat_open_input` →
    `avformat_find_stream_info` → `av_find_best_stream` for audio (and video,
    reused by Task 7). Exposes stream indices, duration, and timebases. Handles
    the R1 container/codec set (mp4/mkv/mov/webm + mp3/wav/flac/m4a-aac/ogg-opus)
    since FFmpeg covers them all.
  - **`AudioDecoder`** — from `MediaDecoder`'s audio stream: `avcodec_alloc_context3`
    → `avcodec_parameters_to_context` → `avcodec_open2`; loop
    `av_read_frame`/`avcodec_send_packet`/`avcodec_receive_frame`; convert every
    decoded frame with **swresample** (`swr_alloc_set_opts2` + `swr_convert`) to
    **interleaved f32, stereo (up/down-mix mono→stereo), resampled to the project
    rate** — `Project::sampleRate`, default **48000 Hz** (per plan §6.5), **not** the
    device rate. Append into one contiguous `std::vector<float>` held by a
    `DecodedAudio` = the whole clip's PCM in RAM (NFR footprint; document the
    OQ6 ~30-min ceiling — decode fully below it). Positions are therefore
    project-rate frames and device-independent (§6.5).
  - The PCM lives in a **`DecodedAudio`** (`engine/decode/DecodedAudio`, per §6.5) —
    interleaved f32 at the project rate, exposing the §6.5 API: `channels()`,
    `frameCount()`, `sampleRate()` (== project rate), and random-access
    `read(frame, out, n)` used by `SampleSource` in Task 2 — pure index math, no
    locks/alloc, safe to read from the RT thread once decode is complete.
  - Audio-only inputs decode identically (no video stream) — R21.
  - **Done-condition**: decoding a known WAV yields `frameCount ≈ duration ×
    Project::sampleRate` (±1 frame, at the project rate) and the PCM peak positions
    line up with the source.
  - (satisfies R1, R21)

### 4. Waveform peaks + `WaveformView` with playhead, zoom, scroll (MUST)
- **Files**: `src/ui/WaveformView.{h,cpp}`;
  `src/engine/audio/WaveformPeaks.{h,cpp}` *(small new helper — extends §6.3's
  `audio/` group; peaks derived from the Task-3 PCM)*.
- **Approach**:
  - **`WaveformPeaks`** — a background **worker** (`QtConcurrent`/`QThread`, off the
    UI and RT threads) that reduces the in-RAM PCM to per-bucket **min/max** pairs at
    one or more zoom levels (e.g. samples-per-column mip levels), stored in a plain
    `std::vector`. Emits a "ready" signal when done.
  - **`WaveformView`** — a `QWidget` (custom `paintEvent`, or a `QOpenGLWidget` for
    GPU draw) that renders the min/max peak columns for the current
    **zoom** (samples-per-pixel) and **horizontal scroll** offset, plus a vertical
    **playhead** line read from the engine's published playhead atomic (Task 5).
    Mouse wheel = zoom; scrollbar/drag = pan. Repaint on a ~60 fps `QTimer` or on
    playhead change.
  - **Done-condition**: loading a clip shows its waveform; wheel zooms in/out;
    scrolling reveals different regions; the playhead sits at the correct
    time-proportional x during playback.
  - (satisfies R2)

### 5. miniaudio RT device + `ScrubEngine` (Bungee position API), lock-free control (MUST)
- **Files**: `src/engine/audio/AudioDevice.{h,cpp}` (contains the single
  `#define MINIAUDIO_IMPLEMENTATION` include); `src/engine/audio/ScrubEngine.{h,cpp}`;
  `src/engine/audio/Resampler.{h,cpp}` *(project-rate → device-rate output conversion
  — per plan §6.3/§6.5)*.
- **Approach**:
  - **`AudioDevice`** — wraps miniaudio: `ma_device_config` with
    `ma_format_f32`, 2 channels, target buffer **≤ ~256 frames** (NFR-A / S3),
    `ma_device_init` → `ma_device_start`; the C `data_callback(ma_device*, void*
    pOutput, const void*, ma_uint32 frameCount)` forwards to
    `ScrubEngine::render(float* out, int frames)`. We use miniaudio (public domain,
    WASAPI/ASIO on Windows, ALSA/JACK/Pulse on Linux) precisely to get a **true RT
    callback** and **bypass Qt's `QAudioSink`**, whose ~250 ms buffering is unfit
    for scrub latency (miniaudio <https://github.com/mackron/miniaudio>; QAudioSink
    caveat <https://doc.qt.io/qt-6/qaudiosink.html>). The device **may open at a
    different device rate** than the project rate; when the two differ, a
    `Resampler` (per §6.5) converts the `ScrubEngine`'s **project-rate** output →
    the device rate at this output boundary (or open the device at the project rate
    when the backend allows it). The device rate is **not** fed back to
    `AudioDecoder` — the stored PCM stays at the project rate (§6.5).
  - **Lock-free control block** (defined in `ScrubEngine.h`), written by the UI
    thread and read by the RT thread with atomics only:
    ```
    struct ScrubControl {
      std::atomic<double> targetPosSeconds;  // where the UI wants the playhead (scrub target)
      std::atomic<bool>   scrubbing;         // pointer is down
      std::atomic<bool>   playing;           // false = paused (Task 6)
    };
    std::atomic<double> publishedPlayheadSeconds; // RT → UI (waveform + video read this)
    ```
  - **`ScrubEngine::render()`** (RT, **no locks / allocs / IO** — NFR-A): the engine
    works entirely in **project-rate frames** (§6.5); read the control atomics;
    compute the **absolute source position** (project-rate frames) for this block:
    - *released & playing*: advance forward at **1×** from the current position;
    - *scrubbing*: derive a per-block `speed` from `(targetPos − currentPos) /
      blockDuration` (clamped/smoothed), moving the position toward `targetPos` —
      **negative speed = reverse, ~0 = hold**;
    - *paused*: `speed = 0`, hold position.
    Pass `StretchRequest{ positionFrames, speed, pitch=1.0, reset }` (with
    `positionFrames` in **project-rate frames**) to the active `IStretcher` (default
    Bungee), which reads source frames through a `SampleSource` bound to the Task-3
    `DecodedAudio` and produces **project-rate** interleaved f32; the `Resampler`
    then converts that to the device rate to fill exactly `frames` of `out` (a no-op
    when device rate == project rate). All scratch/FIFO/resampler buffers
    **preallocated** in `prepare()`. After the block, store `currentPos` into
    `publishedPlayheadSeconds` for the UI.
  - **Done-condition**: with a static (non-scrubbing) transport the device plays the
    clip forward at correct pitch/speed; forcing `targetPos` sweeps produces
    constant-pitch audio; a scrub **stress selftest** runs continuously with **zero
    dropouts** and no allocations in the callback (verify via a debug alloc counter
    around the callback).
  - (satisfies R3, R5, NFR-A)

### 6. Always-playing transport + pointer-down scrub + Play/Pause (MUST)
- **Files**: `src/ui/TransportBar.{h,cpp}`; edits to `src/ui/WaveformView.{h,cpp}`
  and `src/ui/VideoView.{h,cpp}` (pointer handlers); wiring in
  `src/app/MainWindow.cpp`.
- **Approach**:
  - **Released state**: `playing=true, scrubbing=false` → deck plays forward at 1×
    from the playhead (R5). **Pointer-down** on `WaveformView` or `VideoView` sets
    `scrubbing=true` and maps pointer x (and its motion/velocity) to
    `targetPosSeconds`; **pointer-move** updates `targetPosSeconds`;
    **pointer-up** sets `scrubbing=false` → forward play resumes from the current
    position. All UI→RT writes go through the Task-5 atomics only.
  - **`TransportBar`** — a `QWidget` with **Play/Pause** buttons (and a
    time/position label) toggling `ScrubControl::playing`. Pause stops motion
    (position held) but the device stays open; Play resumes. (Manual pitch/speed
    sliders are Phase 2 — leave space.)
  - **Done-condition**: dragging back and forth over the waveform/video makes the
    playhead follow the pointer and audio track it; releasing resumes forward play;
    Pause freezes and Play resumes; nothing stops the deck except Pause.
  - (satisfies R5)

### 7. Synced video scrub: decode + FrameCache + hardware decode + `VideoView` (MUST) — VALIDATE on real H.264
- **Files**: `src/engine/decode/VideoDecoder.{h,cpp}`;
  `src/engine/decode/FrameCache.{h,cpp}`; `src/engine/video/VideoScrubber.{h,cpp}`;
  `src/ui/VideoView.{h,cpp}`.
- **Approach**:
  - **`VideoDecoder`** — from `MediaDecoder`'s video stream: open the codec with
    **hardware decode** enabled where available (`av_hwdevice_ctx_create` — VAAPI on
    Linux, D3D11VA/DXVA2 on Windows — set via the codec context's `get_format`
    negotiation, with `av_hwframe_transfer_data` to pull frames to system memory);
    fall back to software decode. **Frame-accurate seek**: `av_seek_frame(...,
    AVSEEK_FLAG_BACKWARD)` to the keyframe **≤ target**, `avcodec_flush_buffers`,
    then **decode forward** until the frame whose PTS ≥ target — the standard
    long-GOP technique
    (<https://www2.parkcity.co.uk/blog/ffmpeg-grab-exact-frames-from>). Convert with
    **swscale** (`sws_getContext`/`sws_scale`) to **RGBA**.
  - **`FrameCache`** — an **LRU** cache of decoded RGBA frames keyed by frame index,
    holding a **window around the playhead**, filled by a **background decode
    thread** that watches the published playhead (Task 5) and pre-decodes ahead in
    the current scrub direction. Bounded worst-case via the cache + hwdec (R20 /
    NFR-V); never blocks audio.
  - **`VideoScrubber`** — maps the published playhead time → target video frame
    index (via the video stream timebase/fps) and returns the nearest cached frame,
    requesting a decode/prefetch if missing.
  - **`VideoView`** — a `QOpenGLWidget` that uploads the current RGBA frame to a
    **GPU texture** and draws it, updated on a ~60 fps timer synced to the playhead;
    audio-only clips (R21) show a black/placeholder frame.
  - **VALIDATE (riskiest assumption, §8)**: run the full scrub on a **real
    YouTube-downloaded H.264 1080p** clip and confirm the frame tracks the playhead
    within **≈1 frame** in both directions with no unbounded stalls. **Note** the
    intra-frame **proxy** fallback (Phase 5 / OQ5, `Proxy` unit) exists as a plan
    but is **not** built in this phase (proxy background:
    <https://workflow.frame.io/guide/proxy-codecs>).
  - **Done-condition**: scrubbing the H.264 clip updates the picture in both
    directions within ≈1 frame of the playhead, cache-backed, no unbounded stall.
  - (satisfies R4, R20)

---

## Deliverables
- A CMake project **building `audioscratch` on Linux and Windows**, opening a window
  **on Linux** (the Windows build is compile-validated via the Bungee spike; a full
  Windows run is deferred to Phase 8) (Task 1).
- `IStretcher.h` + `BungeeStretcher` (default) + `SignalsmithStretcher` (fallback),
  plus a **written Windows Bungee build outcome** appended here: which of
  MSVC / clang-cl / MinGW built it, patches required, and whether Signalsmith must be
  the Windows default (Task 2).
- `MediaDecoder` + `AudioDecoder` producing a full-clip `DecodedAudio` = **interleaved
  f32 stereo PCM in RAM at the project rate** (default 48000 Hz — §6.5) with
  sample-accurate random access (Task 3).
- `WaveformView` (+ `WaveformPeaks` worker): zoomable, scrollable min/max waveform
  with a live playhead (Task 4).
- `AudioDevice` (miniaudio RT) + `ScrubEngine` (lock-free control, project-rate
  Bungee position-based grains) + `Resampler` (project-rate → device-rate output)
  (Task 5).
- `TransportBar` + pointer-scrub wiring: always-playing transport with grab-to-scratch
  and Play/Pause (Task 6).
- `VideoDecoder` + `FrameCache` + `VideoScrubber` + `VideoView`: hardware-decoded,
  cache-backed synced video scrub (Task 7).
- A `--selftest`/`tests/` scrub **stress harness** (Task 5 done-condition).

## Exit / acceptance criteria (pass/fail)
1. **Build gate**: `cmake -S . -B build && cmake --build build` is **green on Linux**
   with no warnings-as-errors; Qt6 + FFmpeg(LGPL) + miniaudio + Bungee link and the
   window opens.
2. **Windows Bungee spike documented**: the Deliverables section states the concrete
   Windows build outcome (compiler, patches, default engine) — pass = written &
   decisive, not "TBD".
3. **Decode correctness**: loading a known WAV → `frameCount` within ±1 frame of
   `duration × Project::sampleRate`; interleaved f32 stereo `DecodedAudio` at the
   **project rate** (default 48000 Hz — §6.5), *not* the device rate (Task 3).
4. **Waveform**: waveform renders; wheel-zoom and horizontal scroll work; playhead x
   matches playback time (Task 4).
5. **Constant-pitch scrub (S1/S3)**: dragging the playhead forward, **reverse**, and
   **near-zero/hold** plays audio at **constant pitch** with **no audible
   dropouts/clicks** during a continuous scratch; audio buffer ≤ ~256 frames; the
   alloc counter confirms **zero allocations** in the callback (R3, NFR-A).
6. **Always-playing transport (R5)**: released → forward 1× play; pointer-down →
   scrub follows pointer; release → forward play resumes; **Pause** stops, **Play**
   resumes; nothing else stops the deck.
7. **Synced video (S2)**: on a **real YouTube H.264 1080p** clip the displayed frame
   tracks the playhead within **≈1 frame** in both directions, cache-backed, with no
   unbounded stall (R4, R20).
8. **END-TO-END**: on Linux, load a **30 s H.264** clip and **scratch it for 20 s
   continuously** — audio stays constant-pitch and **drops out zero times**, and the
   **picture stays synced** to the playhead throughout.

## Independently shippable?
**No** (not a product — it's the de-risking walking skeleton). But the user can now do
something previously impossible in this codebase: **load a real video clip and
physically scratch it — forward, backward, and frozen — hearing pitch-preserved audio
in real time with the picture locked to the playhead.** This proves out the two
scariest unknowns (Bungee bidirectional scrub + long-GOP H.264 synced scrub) before
any further investment.
