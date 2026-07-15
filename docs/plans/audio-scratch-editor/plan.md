# AudioScratch Editor — Implementation Plan

> Native Windows + Linux desktop app: a **pitch-preserving audio/video "scratch"
> instrument** (drag the playhead back-and-forth, hear it play in real time like
> AudioStretch's *LiveScrub*) built into a **layered mini video editor**, for
> making YouTube-Poop-style content and exporting it to `.mp4`.

---

## 1. Overview / TL;DR

We are building a native desktop application (C++ / Qt 6) for Windows and Linux.
Its heart is a **real-time, pitch-preserving scrub engine**: you load an audio or
video file, see a waveform (and, for video, the picture), and drag the playhead
forward and backward with the pointer — audio plays time-stretched so the **pitch
stays constant** at any drag speed, including reverse and near-zero, exactly like
the mobile app **AudioStretch / "LiveScrub"** the user is cloning. Around that
instrument sits a **layered timeline editor** (stacked video layers for
overlays/PiP + audio tracks, cut/trim/reorder, an audio effects rack) so the user
can compose a whole edit and **render it to an `.mp4`**. The user's job-to-be-done
is *making YouTube-Poop-style videos quickly*; scratch performances are recorded
as automation and become clips on the timeline.

**End state:** a user opens the app, drops in a video, scratches/loops/pitches it,
records takes, arranges and layers them on a timeline with audio effects, and
exports a finished `.mp4` — all in one native app, faster than doing it by hand in
a general-purpose NLE.

---

## 2. Background & context

- **Reference app (verified):** the user's "AutioStretch" is **AudioStretch** by
  Cognosonic (iOS/Android; official site <https://www.audiostretch.com/>), whose
  signature feature **LiveScrub™** is: "drag the waveform and the audio plays at
  the same speed as your finger's movement," forward and backward, and *holding
  freezes at zero speed*. Its engine is **pitch-preserving** (phase-vocoder /
  granular), NOT a turntable — slow drag does **not** drop pitch; pitch is a
  separate manual control (±36 semitones on AudioStretch). It also loads video and
  shows the picture scrubbing in sync above the waveform. This matches the user's
  own spec: *"while scratching, the pitch stays the same, but you should be able to
  edit the pitch as you like manually."* Sources in §12.
- **Desktop prior art** (reference implementations, not dependencies): **Capo**
  (macOS — click-drag playhead scrub via an "audio freezer"), **TimeStretch Audio
  Player** (29a.ch — free, browser, drag-to-scrub + pitch-preserve), **Audacity**
  Scrub/Seek (open-source gesture reference), **Amazing Slow Downer**,
  **Transcribe!**. DJ/scratch simulators (Serato, VirtualDJ, Algoriddim djay) are
  the *turntable/varispeed* reference if we ever add that optional mode.
- **User & context:** the user (primary and, for now, only target user) is a
  technically capable desktop creator on **Manjaro Linux** who also targets
  **Windows**, making YouTube-Poop-style remixes. They want a fast, native tool —
  not a website, not mobile, not a general NLE.
- **Why now / why this shape:** general NLEs make back-and-forth "scratch" edits
  painful; a purpose-built scrub instrument that outputs editable clips is the gap.
- **This is a greenfield project.** The working directory
  `/home/maxi/Documents/coding/audio-stretcher` is empty and not yet a git repo.
  There is no existing code or docs to fit into.

---

## 3. Goals / Non-goals

### Goals
- A native Win+Linux app whose **core interaction is pitch-preserving,
  bidirectional, real-time audio scrubbing** driven by the pointer, with **synced
  video**.
- An **always-playing transport** (deck keeps playing; grab to scratch; only Pause
  stops it).
- **Manual, independent pitch and speed controls.**
- **A/B loop + markers.**
- **Record a scratch performance and export it as a `.mp4`** (picture + scratched
  audio).
- A **layered timeline editor**: multiple stacked **video layers** (overlays /
  picture-in-picture / compositing) + **audio track(s)**, with import of multiple
  clips and cut / trim / split / delete / move / reorder.
- An **audio effects rack** (echo/delay, reverb, distortion, chorus/vibrato,
  stutter) as non-destructive insert effects on clips.
- **Render the whole layered timeline to `.mp4`** (H.264 + AAC), offline & exact.
- **Save/load projects** non-destructively.
- **All third-party code permissive/LGPL/MPL** — no GPL obligations, no paid
  licenses (so the app can be closed- or open-sourced freely later).

### Non-goals (the scope fence)
- **Not** a general-purpose NLE / DaVinci-Resolve competitor. It is a scratch
  instrument + a *focused* layered compositor for YTP-style work.
- **No turntable/varispeed pitch-bend mode in v1** (pitch-preserving only). Optional
  later (R18).
- **No video effects/filters in v1** beyond compositing (opacity, position, scale,
  crop). Colour/visual FX deferred (R19).
- **No AI features** (stem separation, auto-transcription, beat detection).
- **No multi-user, cloud, accounts, networking, telemetry.**
- **No plugin/VST hosting.** Effects are a fixed built-in set.
- **No mobile, no web build.** Desktop native only.
- **No DRM-protected input** (e.g. Apple Music) — out of scope, same as the ref app.

---

## 4. Success metrics

- **S1 — The core feels right:** dragging the playhead plays audio with **constant
  pitch** at any speed incl. reverse and near-zero, **glitch-free** (no audible
  dropouts/clicks during a continuous scratch). This is the make-or-break signal.
- **S2 — Video stays synced:** while scrubbing a typical 1080p H.264 clip, the
  displayed frame tracks the playhead within **≈1 frame** in both directions,
  responsively (cache-backed), with no unbounded stalls.
- **S3 — Audio latency:** output round-trip latency **< ~20 ms** on a normal
  desktop audio device; audio thread never blocks (no locks/allocs in the callback).
- **S4 — Round-trip deliverable:** a user can load a video, scratch/loop/pitch it,
  record a take, place it (and an overlay) on the layered timeline with an audio
  effect, and **export a correct `.mp4`** that plays in VLC/browser/YouTube.
- **S5 — Exact offline render:** the exported file is deterministic and matches the
  intended edit (no real-time glitches baked in), because export renders from the
  recorded automation offline, not by capturing live output.
- **S6 — Runs natively on both OSes** from a single codebase, packaged
  (AppImage/Flatpak on Linux, installer/zip on Windows).

---

## 5. Requirements, decisions & constraints  ← intent-preservation

Tags: **DECISION** (settled — do not re-litigate) · **ASSUMPTION** (a gap filled
with a stated default) · **OPEN** (unresolved — owner + default). Strength:
**MUST / SHOULD / MAY** (RFC 2119). Phase tasks reference these by number.

| # | Strength | Type | Requirement |
|---|----------|------|-------------|
| **R1** | MUST | DECISION | **Load a single audio or video file** and decode it. Audio: mp3, wav, flac, m4a/aac, ogg/opus. Video containers: mp4, mkv, mov, webm (at minimum). Decode via **FFmpeg/libav**. |
| **R2** | MUST | DECISION | **Waveform view**: horizontal amplitude waveform of the loaded audio, zoomable & horizontally scrollable, with a visible **playhead**. |
| **R3** | MUST | DECISION | **Pitch-preserving real-time scrub**: dragging the playhead plays audio time-stretched so **pitch is constant** regardless of drag speed, **including reverse and near-zero speed**, glitch-free. Engine: **Bungee** (position-based, native reverse). |
| **R4** | MUST | DECISION | **Synced video scrub**: when a video is loaded, display the video frame at the playhead, updating as you scrub in **both directions**. |
| **R5** | MUST | DECISION | **Always-playing transport.** When not grabbed, the deck plays forward at 1× from the playhead. **Grab** (pointer-down on waveform/video) → scratch (playhead follows pointer). **Release** → resume forward play from current position. **Pause** stops; **Play** resumes. *User's words:* "while scratching back and forth, the audio should always play, unless manually paused with the pause button." |
| **R6** | MUST | DECISION | **Manual pitch control**, independent of speed: shift by **±36 semitones** (three octaves each way, matching the AudioStretch reference) with fine (cent-level) adjustment. *User:* "you should be able to edit the pitch as you like manually." |
| **R7** | SHOULD | DECISION | **Manual speed/rate control**: set the base (released) playback rate, independent of pitch. |
| **R8** | MUST | DECISION | **A/B loop + markers**: set loop start/end (loops while playing), drop/label/jump to markers. |
| **R9** | MUST | DECISION | **Record a scratch performance → `.mp4`** (video+audio). *Implementation decision:* record the **playhead-position automation envelope** (timestamped positions) + pitch/speed, then **render offline** from it → exact, glitch-free. Audio = pitch-preserved scrub; picture follows the same automation. |
| **R10** | MUST | DECISION | **Layered timeline**: multiple stacked **video layers** (overlays / picture-in-picture / compositing) + **audio track(s)**. Import multiple clips; **cut / split / trim / delete / move / reorder**. |
| **R11** | MUST | DECISION | **Audio effects rack** (7 effects): non-destructive **insert effects** on clips — echo/delay, reverb, distortion, chorus/vibrato, **stutter/repeat** (repeat a short slice, YTP-style; affects audio, and video when on a video clip), **bitcrusher/downsample**, and **phaser/flanger**. |
| **R12** | MUST | DECISION | **Render the whole timeline → `.mp4`** (H.264 + AAC) via FFmpeg, **offline & exact**, default matching source resolution/fps. |
| **R13** | MUST | ASSUMPTION | **Project save/load**, non-destructive. *Default:* a JSON project file (`.asproj`) capturing layers, tracks, clips, in/out points, automation, effect chains, and **references to source media by path** (media not copied). |
| **R14** | SHOULD | ASSUMPTION | **Undo/redo** for edit operations (command pattern). *Default:* covers timeline/clip/effect edits; not live scratch motion. |
| **R15** | MUST | DECISION | **Native Windows + Linux** from one codebase. Primary dev/test = **Linux (Manjaro)**; Windows is a build target. Packaging: **AppImage** (and/or Flatpak) on Linux; **installer/zip** on Windows. |
| **R16** | MUST | DECISION | **Permissive licensing only** — no GPL, no paid licenses. Stack: **Qt 6 (LGPLv3, dynamically linked)**, **FFmpeg (LGPL build, no `--enable-gpl`/`--enable-nonfree`, dynamically linked)**, **miniaudio (public domain)**, **Bungee (MPL-2.0)**. **Do NOT use Rubber Band** (GPL/commercial). |
| **R17** | SHOULD | DECISION | **Real-time preview is best-effort** (may drop frames / lower preview quality under load); **final render (R9, R12) is offline and exact**. The two paths share one engine so results match. |
| **R18** | MUST | DECISION | **Turntable/varispeed mode IN v1** (pitch bends with drag speed like real vinyl), a **toggle** alongside the default pitch-preserving mode. Implemented via a variable-rate interpolating **resampler** path (reverse = backward read; near-zero = tiny step). Shares the same playhead/automation input as the stretcher path so switching is seamless. |
| **R19** | SHOULD | DECISION | **Video effects/filters IN v1** as a per-clip non-destructive video effect chain (own phase): at minimum **colour/contrast/brightness**, **chroma-key** (green-screen, YTP-classic), and a **basic glitch** (e.g. RGB-split / pixel-shift / datamosh-style). Applied in GPU preview and the exact offline render, before compositing. Exact effect set: OQ9. |
| **R20** | SHOULD | DECISION | **Responsive video scrub** on long-GOP (H.264/H.265) sources via a **decoded-frame cache** + **hardware decode**; offer an **intra-frame proxy** (transcode to DNxHR/ProRes-proxy via FFmpeg) for heavy files. Frame-accurate seek = seek-to-keyframe → decode-forward. |
| **R21** | MUST | ASSUMPTION | **Audio-only inputs** (no video track) are supported everywhere: they occupy an audio track and render as a black/placeholder frame if placed where a video layer is expected. |

### Non-functional requirements (targets)
- **Audio (NFR-A):** RT-safe callback — **no locks, no allocations, no file I/O** in
  the audio thread; lock-free hand-off from UI. Target buffer ≤ ~256 frames /
  <~20 ms; zero dropouts during continuous scrub (S1/S3).
- **Video (NFR-V):** land the correct frame within ≈1 frame of the playhead (S2);
  bounded worst-case seek via cache/hwdec/proxy (R20).
- **Render (NFR-R):** export is deterministic and offline; may run slower or faster
  than real time; correctness over speed (S5).
- **Portability (NFR-P):** identical behaviour on Win+Linux from one codebase;
  CMake build; CI-buildable on both.
- **Footprint:** decode the loaded audio fully to PCM in RAM for waveform+scrub
  (fine for typical clips; document a size ceiling and stream/proxy above it).

---

## 6. Architecture / design overview

### 6.1 Thread model (the crux)
```
┌─ UI / render thread (Qt, ~60 fps) ─────────────────────────────┐
│  Waveform, video frame, transport, timeline, effect UI.        │
│  Reads playhead & levels (atomics); on pointer drag writes the │
│  target playhead into a lock-free control block.               │
└───────────────┬───────────────────────────────┬───────────────┘
                │ lock-free control (atomics/SPSC ring)          │
┌───────────────▼──────────────┐   ┌─────────────▼──────────────┐
│  Real-time AUDIO thread      │   │  Worker/decode threads     │
│  (miniaudio callback)        │   │  FFmpeg decode, frame-cache │
│  ScrubEngine → Bungee per    │   │  fill, waveform peaks,      │
│  block: absolute source pos  │   │  proxy transcode.           │
│  from transport/automation → │   └────────────────────────────┘
│  pitch-preserved PCM → mix.  │
│  NEVER blocks (NFR-A).       │   ┌────────────────────────────┐
└──────────────────────────────┘   │  OFFLINE render (non-RT):  │
                                    │  composite layers + mix    │
                                    │  audio(+FX) from automation │
                                    │  → FFmpeg encode → .mp4     │
                                    └────────────────────────────┘
```

### 6.2 Core data model (non-destructive)
```
Project { sampleRate=48000, ... }
 ├─ VideoLayer[] (stacking order: index 0 = bottom → top)
 │    └─ VideoLayer { id, gainDb, muted, clips[] }        // gainDb/muted per §6.5
 │         └─ Clip { sourceMediaId, srcIn, srcOut, timelineStart,
 │                   transform{pos,scale,crop,opacity},
 │                   automation,        // the "scratch" — envelopes, real API in §6.5
 │                   playbackMode{PitchPreserving|Varispeed},  // R18 (per clip)
 │                   effectChain[],      // audio insert FX (R11)
 │                   videoEffectChain[] } // video FX — video-layer clips only (R19)
 ├─ AudioTrack[] (mixed) { id, gainDb, muted, clips[] }
 │    └─ Clip { ... same shape; audio FX only; no transform / videoEffectChain ... }
 └─ Media[] { id, path, DecodedAudio handle, video-decoder handle }

Canonical struct name for a video layer is **VideoLayer** (not "Layer").
`automation` methods are defined in §6.5 (playheadPosAt / durationFrames / slice) —
the `playheadPos(t)` shorthand elsewhere is informal, not a real method.
```
- **The "scratch" is a recorded automation envelope**, not baked audio. The *same*
  ScrubEngine consumes it live (preview) and offline (render), so **record and
  export share one code path** and results are identical (R9/R12/R17). This is the
  single most important design decision after the library choices.

### 6.3 Subsystems → source layout (proposed)
```
audio-stretcher/
  CMakeLists.txt
  third_party/            miniaudio.h · bungee/ · signalsmith-stretch/ (fallback)
  src/
    main.cpp
    app/                  Application, MainWindow
    engine/
      decode/             MediaDecoder, AudioDecoder, DecodedAudio, VideoDecoder,
                          FrameCache, Proxy                                               (FFmpeg)
      audio/             AudioDevice (miniaudio), ScrubEngine, IStretcher,
                          BungeeStretcher, SignalsmithStretcher (fallback),
                          VarispeedStretcher (R18 resampler mode), WaveformPeaks,
                          Resampler, Mixer, effects/*                                     (RT-safe)
      video/             VideoScrubber (playhead→frame via FrameCache),
                          effects/* (colour · chroma-key · glitch — R19)
      model/             Project, VideoLayer, AudioTrack, Clip, Automation, Take,
                          Media, EffectChain, VideoEffectChain, SequenceMap,
                          TimelineCommands, ProjectIO (JSON), UndoStack
      render/            Compositor (shared blend), OfflineRenderer (composite+mix),
                          Encoder (FFmpeg mux → mp4)
    ui/                  WaveformView, VideoView, TransportBar, PitchSpeedControls,
                          LoopMarkerBar, RecordExportPanel, TimelineView, ClipItem,
                          LayerHeader, EffectRackView, VideoEffectRackView
    (§6.3 is authoritative but non-exhaustive; §6.5 fixes the cross-phase contracts.)
  tests/
  packaging/             linux/ (AppImage, flatpak) · windows/ (installer)
```

### 6.4 Key library roles (all permissive — R16)
- **FFmpeg/libav (LGPL):** one dependency decodes every audio format → PCM (for
  waveform + scrub) **and** video frames (seek-to-keyframe → decode-forward), **and**
  encodes/muxes the `.mp4` output. Build LGPL, dynamically linked; ship license
  notices; allow lib replacement. **H.264 *encoding* caveat:** the usual encoder
  `libx264` is **GPL** (needs `--enable-gpl`) and would violate R16 — for LGPL,
  encode H.264 with **openh264** (Cisco, BSD) or a **hardware encoder**
  (`h264_vaapi`/`h264_nvenc` on Linux; Media Foundation / NVENC / QSV / AMF on
  Windows), and/or offer a royalty-free **`.webm` (VP9/AV1 + Opus)** export path.
- **Bungee (MPL-2.0):** pitch-preserving stretch with a **position-based
  random-access grain API** — you pass an absolute input-frame position per grain,
  so **reverse and zero/near-zero speed are native**. This is why it beats
  Rubber Band (GPL, forward-only push model) and Signalsmith (MIT, forward-only) for
  a back-and-forth scrubber. **Windows/MSVC risk — see §8 / Phase 1 spike.**
- **miniaudio (public domain):** single-file cross-platform real-time audio output
  (WASAPI/ASIO on Windows, ALSA/JACK/PulseAudio on Linux) with a true RT callback.
- **Qt 6 (LGPLv3):** UI, GPU-accelerated custom rendering (waveform + video frame via
  texture), timeline widgets, file dialogs, cross-platform packaging.
- **Signalsmith Stretch (MIT):** *fallback* stretcher if Bungee's Windows build is a
  blocker; forward-only, so reverse would need reversed-buffer feeding.
- **Varispeed (R18, v1):** a variable-rate interpolating resampler (e.g. libsamplerate
  BSD-2 — verify license — or a cubic/windowed-sinc interpolator) behind the same
  `IStretcher` seam, selected per clip via `playbackMode`; pitch bends with speed,
  reverse = backward read. Shares the playhead/automation input with the stretcher
  path so the mode toggle is seamless.

### 6.5 Shared cross-phase contracts (AUTHORITATIVE)
These types and units are fixed here so phases can't drift apart. Every phase doc
MUST use them verbatim; a phase that needs more extends these, it does not redefine
them.

- **Project sample rate.** `Project::sampleRate` (default **48000 Hz**) is the one
  canonical audio rate. **`AudioDecoder` resamples every source to the project rate**
  (via swresample) — *not* the device rate. **All audio / source / timeline positions
  are in project-rate frames**: `frame_t` (int64 frames), sub-frame as `double`. The
  `AudioDevice` (miniaudio) may run at a different device rate; the engine resamples
  its project-rate output → device rate at the output boundary (or opens the device
  at the project rate when available). Positions are therefore **device-independent**.
  *(This is the single source of truth that resolves the "device rate vs project
  rate" question — Phase 1 decode and Phase 4 timeline both obey it.)*
  **Types & conversion:** `Project::sampleRate` is an **`int`** (Hz). `frame_t` is
  **`int64`**. Frames↔seconds conversions MUST use **double division**:
  `seconds = double(frames) / sampleRate`, `frames = llround(seconds * sampleRate)`
  — never integer division (which would truncate sub-second offsets).
- **`DecodedAudio`** (`engine/decode/DecodedAudio`) — interleaved f32 PCM at the
  project rate; API `channels()`, `frameCount()`, `sampleRate()` (== project rate),
  random-access `read(frame, out, n)`. Produced by `AudioDecoder`. Referenced by this
  name everywhere (no `DecodedAudio`/`AudioDecoder` ambiguity).
- **`Automation`** (`engine/model/Automation`; defined Phase 3, used by 4/6/7):
  - `struct Automation { Envelope playhead; Envelope speed; Envelope pitch; };`
  - `Envelope` stores keys `(tSeconds, value)` with `double valueAt(double tSeconds)`.
  - Units: `playhead` value = **source position in project-rate frames**; `speed` =
    ratio; `pitch` = semitones.
  - Methods used downstream: `frame_t playheadPosAt(double tSeconds)`;
    `double durationSeconds()`; `frame_t durationFrames()` (= `durationSeconds() ×
    Project::sampleRate`); `Automation slice(double t0, double t1)` (for clip split).
  - Compaction: `append` drops a key that is **collinear within epsilon** with the
    previous two (value-based) so holds/constant runs don't bloat the envelope.
- **`Take`** (`engine/model/Take`; Phase 3): `struct Take { MediaId mediaId;
  Automation automation; PlaybackMode playbackMode; };` — the recorder binds the
  currently-loaded media + the deck's current playback mode (R18) to the recorded
  automation. Used source span = `[min(playhead), max(playhead))`.
- **`Clip` timeline length:** `timelineDuration()` = `automation ?
  automation.durationFrames() : (srcOut - srcIn)`; `timelineEnd()` = `timelineStart +
  timelineDuration()`; clip lookup uses `[timelineStart, timelineEnd())`. *(A recorded
  scratch clip's on-timeline length is its **automation** duration, not its source
  span — every renderer/scheduler MUST use `timelineDuration()`.)*
- **`makeClipFromTake(const Take&, frame_t timelineStart)`** (Phase 4): produces
  `Clip{ sourceMediaId = take.mediaId, srcIn = min(playhead), srcOut = max(playhead),
  timelineStart, automation = take.automation, playbackMode = take.playbackMode }`.
- **`Compositor`** (`engine/render/Compositor`; Phase 5): the ONE bottom→top src-over
  blend (transforms pos/scale/crop/opacity). Used by **both** live preview and
  `OfflineRenderer`. **Phase 8's** full render MUST call it — never re-implement
  blending. (Phase 7 video FX run per-clip *before* this blend.)
- **Effect-chain hand-off** (Phase 6): each clip owns a
  `std::atomic<RtEffectChain*>` swapped on edit (old chain deferred-deleted),
  **separate** from the singular transport control block. Preview and offline render
  run the identical chain; their outputs match **within a small epsilon** once
  parameter smoothing settles (per R17 — not bitwise-identical).
- **Playback mode / varispeed** (R18, Phase 2): `enum class PlaybackMode {
  PitchPreserving, Varispeed };` on each `Clip` (default `PitchPreserving`). Both are
  `IStretcher` implementations behind the same seam — `BungeeStretcher`
  (pitch-preserving) and `VarispeedStretcher` (resampler; pitch bends with speed,
  reverse = backward read). The `ScrubEngine` selects by `clip.playbackMode`; the live
  deck's mode toggle sets the mode captured on new recordings. Both consume the same
  playhead/automation input, so preview and offline render agree per mode.
- **Video effect chain** (R19, Phase 7): each video-layer `Clip` owns a
  `VideoEffectChain` (serializable `vector<VideoEffectSpec>`, model side) compiled to
  a runtime `RtVideoEffectChain` (`process(RGBA frame)`). It runs in the **frame /
  composite pipeline** (GPU preview + CPU `OfflineRenderer`), applied to each clip's
  source frame **before** the `Compositor` blends it — NOT in the RT audio thread, so
  it carries no NFR-A constraint. Preview and render use the identical chain so a keyed
  / glitched clip looks the same in both.
- **Encoder / R16 enforcement.** H.264 encode uses a **non-GPL encoder** (hardware
  `h264_vaapi`/`h264_nvenc`/MF/NVENC/QSV/AMF, else **openh264**) — **never `libx264`**.
  R16 is enforced at **build time** (FFmpeg built without `--enable-gpl`; `libx264`
  not linked) and verified at **runtime** by `Encoder` logging its chosen encoder —
  **NOT** by inspecting `ffprobe` codec names (which report `h264` regardless of which
  encoder produced the stream).
- **Track/layer structs & IDs.** `using LayerId = uint64_t;` `using TrackId =
  uint64_t;`. Both structs are defined in **Phase 4** and carry gain/mute from the
  start:
  - `struct VideoLayer { LayerId id; QString name; float gainDb = 0; bool muted =
    false; std::vector<Clip> clips; };` — a video clip's audio routes through its
    layer's `gainDb`/`muted` in the `Mixer`.
  - `struct AudioTrack { TrackId id; QString name; float gainDb = 0; bool muted =
    false; std::vector<Clip> clips; };`
  - Layer/track **stacking order = vector index** (index 0 = bottom → top); there is
    **no `z` field**. Phase 5 adds a `bool visible` to `VideoLayer`; `id`, `name`,
    `gainDb`, `muted` already exist from Phase 4 and are round-tripped in `.asproj`.

---

## 7. Alternatives considered

- **JUCE instead of Qt 6.** JUCE is *the* audio-app framework and was the top pick
  for a pure-audio version — **rejected here because the app is video-central** and
  JUCE's video support is weak (especially on Linux). Qt gives first-class custom
  GPU rendering for synced video frames + waveform + a real widget toolkit for the
  timeline, with strong Win+Linux packaging. (We still borrow JUCE's *pattern* of a
  RT-safe audio callback, implemented via miniaudio.)
- **Electron / Tauri (web front-end).** Fastest for the *waveform* UI
  (wavesurfer.js), but **rejected**: HTML5 `<video>` scrubbing is not frame-accurate
  and is poor in reverse; you can't easily run a native FFmpeg decoded-frame cache
  behind it; Web Audio latency is looser and driver-dependent. (A Tauri app with a
  Rust backend doing cpal+native-stretch was the only viable web variant, but then
  the hard parts are native anyway — so go native.)
- **Rubber Band stretcher.** Best forward slow-down quality, but **GPL-or-paid** and
  a **forward-only push model** where reverse means re-priming reversed buffers.
  Rejected for a back-and-forth scrubber; violates R16 too.
- **Capture live output for record/export.** Rejected in favour of **offline render
  from the automation envelope** (R9) — exact, deterministic, no baked-in real-time
  glitches (S5).
- **Symphonia (Rust) / dr_libs / libsndfile for decode.** Fine libraries, but we
  need video decode + encode too, and FFmpeg covers audio+video+mux in one LGPL
  dependency — fewer moving parts than mixing decoders. (Symphonia would be the pick
  only in a Rust build.)

---

## 8. Risks & mitigations

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **Bungee doesn't officially support MSVC** (its Rust binding was archived over this). | Could block the Windows build of the core engine. | **Phase 1 spike:** build Bungee on Windows with **Clang-cl / MinGW**; if it's a hard blocker, fall back to **Signalsmith (MIT)** and implement reverse via reversed-buffer feeding. Keep the stretcher behind an interface (`IStretcher`) so it's swappable. |
| **Frame-accurate scrub of long-GOP H.264** (typical YouTube downloads) is expensive — each backward step may re-decode a whole GOP. | Janky/stalling video scrub — kills S2. | **Decoded-frame cache** (LRU window around playhead) + background decode thread + **hardware decode**; **intra-frame proxy** transcode for heavy files (R20). **Validate in Phase 1 on a real YouTube-downloaded H.264 clip** — the riskiest assumption. |
| **Real-time preview of a full layered composite + FX** may not hit fps. | Choppy *preview*. | Accept **best-effort preview** (R17): drop frames / reduce preview res; the **offline render is exact**. Never let preview block audio. |
| **RT-safety violations** (a lock/alloc sneaks into the audio callback). | Dropouts/clicks — kills S1/S3. | Strict lock-free control hand-off; preallocate; forbid FFmpeg/malloc/mutex in the callback; test with a scrub stress harness. |
| **Scope is large** (compositor + FX + instrument). | Never-ships risk. | **Phased, vertical slices.** Usable **scratch-to-mp4 tool by Phase 3** before the timeline exists; each phase is independently demoable. |
| **FFmpeg LGPL bundling done wrong** (accidental GPL/nonfree build, missing notices). | Licensing violation. | Build without `--enable-gpl`/`--enable-nonfree`, **dynamic-link**, ship FFmpeg license text, allow lib replacement (R16). |
| **H.264/AAC patent exposure** on *encode/distribute*. | Legal question for distribution. | Note for the owner; decode-only is low-risk; a royalty-free export path (VP9/AV1+Opus in `.webm`) can be offered later. Not legal advice. |

---

## 9. Phase index

Sequenced by **risk first** (the scary audio+video scrub is Phase 1) then
**dependency**, each ending at a demoable increment. **You have a shippable
single-clip scratch-to-mp4 tool at the end of Phase 3.**

| Phase | Goal (one line) | Depends on | Shippable increment |
|-------|-----------------|-----------|---------------------|
| **[1](phase-1-walking-skeleton.md)** | Single clip: decode + waveform + **pitch-preserving bidirectional scrub audio** + **synced video** + always-playing transport. De-risks the core. | — | Load a clip, scratch it with synced picture & constant pitch. |
| **[2](phase-2-performance-controls.md)** | Manual **pitch (±36 st)** & **speed** controls + **A/B loop** + **markers** + **turntable/varispeed mode toggle** (R18). | 1 | A usable practice/scratch instrument, pitch-preserve *or* vinyl mode. |
| **[3](phase-3-record-take-mp4.md)** | **Record** a scratch performance (automation envelope) and **render it offline → `.mp4`**. | 1,2 | ✅ **Standalone scratch-to-mp4 tool.** |
| **[4](phase-4-timeline.md)** | **Timeline foundation**: project model, import multiple clips onto one video track + audio track, cut/trim/split/reorder, **scrub/play the sequence** (single active clip at a time), **save/load**, undo/redo. | 1–3 | Assemble multiple takes into a sequence, play/scrub it & save the project. |
| **[5](phase-5-layers-compositing.md)** | **Layered video** (stacked layers, overlays/PiP, transforms) + **audio mixing**. | 4 | Composite overlays over base clips. |
| **[6](phase-6-effects-rack.md)** | **Audio effects rack** (7): echo/delay, reverb, distortion, chorus/vibrato, stutter, bitcrusher/downsample, phaser/flanger. | 4 (5 for video-clip stutter) | Add YTP-style audio FX to clips. |
| **[7](phase-7-video-effects.md)** | **Video effects**: per-clip video FX chain — colour/contrast, **chroma-key** (green screen), **glitch** (RGB-split/pixel-shift). | 5 | Green-screen keys & glitch looks in preview. |
| **[8](phase-8-render-and-packaging.md)** | **Render the whole layered + audio-FX + video-FX timeline → `.mp4`**; **package** for Win+Linux. | 5,6,7 | ✅ **Full app: edit → export → installable.** |

---

## 10. Rollout / sequencing

- **Posture:** incremental, single-user, local. No servers, migrations, or flags.
- **Milestone A (ship-worthy):** Phases 1–3 = the scratch-to-mp4 instrument. This is
  a genuinely useful standalone tool and validates the whole risky core.
- **Milestone B (full vision):** Phases 4–8 = the layered editor + audio FX + video FX
  + full render + packaging.
- **Set up git first** (`git init`) — repo is currently uninitialised. Follow the
  user's public-GitHub rules (noreply email) only if/when published.

---

## 11. Open questions

**Resolved (user decisions, 2026-07-15):**
- OQ1 → effect rack = **7 effects**: delay/echo, reverb, distortion, chorus/vibrato,
  stutter, **bitcrusher/downsample**, **phaser/flanger** (R11). Params tuned in Phase 6.
- OQ2 → pitch range **±36 semitones** with cents (R6).
- OQ3 → **build turntable/varispeed mode in v1** as a per-clip toggle (R18) — folded
  into Phase 2.
- OQ4 → **build video effects in v1** as their own phase (R19) — Phase 7.

**Locked (engineering defaults, changeable):**
- OQ5 (proxy) → manual "make proxy" ships in Phase 5; auto-suggest-above-threshold later.
- OQ6 (footprint) → full-decode audio to RAM up to ~30 min stereo; stream/proxy above.
- OQ7 (Linux pkg) → AppImage first; Flatpak optional.
- OQ8 (H.264 encoder) → hardware encoder first, else **openh264**; optional `.webm`.

**Still open:**

| # | Question | Owner | Default if unanswered |
|---|----------|-------|-----------------------|
| OQ9 | Exact video-FX set & params (which glitch style; chroma-key spill handling; colour controls) (R19). | user | colour (brightness/contrast/saturation), chroma-key (key colour + tolerance + spill), glitch (RGB-split + block-shift). Refine in Phase 7. |

---

## 12. Sources / references & glossary

### Reference app & prior art
- AudioStretch official — <https://www.audiostretch.com/> · App Store —
  <https://apps.apple.com/us/app/audiostretch/id571863178> · Google Play —
  <https://play.google.com/store/apps/details?id=com.bandlab.audiostretch>
- "What is AudioStretch / LiveScrub" (BandLab blog) —
  <https://blog.bandlab.com/what-is-audiostretch-and-how-do-i-use-it/>
- AudioStretch does video (Cult of Mac) —
  <https://www.cultofmac.com/523495/audiostretch-slow-down-songs-and-videos/>
- Capo (desktop drag-to-scrub) —
  <https://supermegaultragroovy.com/products/capo/mac/> · TimeStretch Audio Player —
  <https://29a.ch/timestretch/> · Audacity Scrubbing/Seeking —
  <https://manual.audacityteam.org/man/scrubbing_and_seeking.html>

### Time-stretch / scrub engine
- Bungee (position-based, native reverse; MPL-2.0) —
  <https://bungee.parabolaresearch.com/> · repo
  <https://github.com/bungee-audio-stretch/bungee> · header
  <https://github.com/bungee-audio-stretch/bungee/blob/main/bungee/Bungee.h> ·
  archived Rust binding (MSVC note)
  <https://github.com/emuell/bungee-rs>
- Signalsmith Stretch (MIT, fallback) —
  <https://github.com/Signalsmith-Audio/signalsmith-stretch>
- Rubber Band (GPL/commercial — *not used*) — <https://breakfastquay.com/rubberband/>
- SoundTouch (LGPL, WSOLA) — <https://www.surina.net/soundtouch/README.html>
- Time/pitch scaling & WSOLA background — <https://www.surina.net/article/time-and-pitch-scaling.html>

### Framework, audio I/O
- Qt 6 licensing (LGPLv3/commercial) — <https://doc.qt.io/qt-6/licensing.html> ·
  QAudioSink caveat (why we bypass it) — <https://doc.qt.io/qt-6/qaudiosink.html>
- miniaudio (public domain, WASAPI/ASIO/ALSA/JACK) — <https://github.com/mackron/miniaudio>
- cpal (Rust alt, not used) — <https://github.com/RustAudio/cpal>
- JUCE (considered, rejected for video) — <https://juce.com/get-juce/>

### Decode / video scrub / encode
- FFmpeg legal (LGPL vs GPL vs nonfree) — <https://www.ffmpeg.org/legal.html> ·
  LICENSE — <https://github.com/FFmpeg/FFmpeg/blob/master/LICENSE.md>
- Frame-accurate seek (keyframe → decode forward) —
  <https://www2.parkcity.co.uk/blog/ffmpeg-grab-exact-frames-from>
- mpv/libmpv hr-seek (alt player, cost notes) — <https://mpv.io/manual/stable/>
- Intra-frame vs long-GOP & proxy scrubbing — <https://workflow.frame.io/guide/proxy-codecs>
- Symphonia (Rust decode alt) — <https://github.com/pdeljanov/Symphonia>

### Practice references
- RFC 2119 (MUST/SHOULD/MAY) — <https://datatracker.ietf.org/doc/html/rfc2119>
- Design Docs at Google — <https://www.industrialempathy.com/posts/design-docs-at-google/>

### Glossary
- **LiveScrub** — AudioStretch's drag-the-waveform-to-hear-it feature; the
  interaction we clone.
- **Pitch-preserving / time-stretch** — change playback speed *without* changing
  pitch (phase-vocoder/granular). Opposite of **varispeed/turntable**, where pitch
  bends with speed (plain resampling).
- **Scratch automation envelope** — the recorded curve of playhead-position over
  time (plus speed/pitch); the app's non-destructive representation of a scratch
  performance, rendered live and offline.
- **Long-GOP** — inter-frame video (H.264/H.265) where most frames depend on a
  distant keyframe; expensive to seek. **Intra-frame** (ProRes/DNxHR) = every frame
  a keyframe; cheap to scrub → used for **proxies**.
- **Proxy** — a lightweight intra-frame stand-in transcode used for smooth
  scrubbing; final render uses the original.
- **YTP (YouTube Poop)** — remix genre built from chopping, looping, reversing, and
  distorting source clips; the app's target use.
- **RT-safe** — code that runs in the audio callback without locks, allocations, or
  I/O, so it never causes a dropout.
```
