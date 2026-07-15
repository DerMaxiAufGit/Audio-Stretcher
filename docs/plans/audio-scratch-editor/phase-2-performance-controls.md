# Phase 2 — Performance controls (pitch · speed · A/B loop · markers · turntable mode)

- **Goal**: Give the Phase-1 scrub skeleton independent manual **pitch** and
  **speed** controls plus an **A/B loop**, named **markers**, and a
  **turntable/varispeed mode toggle** (R18), turning it from a proof-of-concept into
  a playable practice/scratch instrument.

- **Scope**
  - **In**: a pitch control (±36 semitones with cent-level fine, per OQ2/R6)
    that feeds Bungee's per-grain pitch parameter so pitch is **constant while
    scrubbing and independent of drag speed** (R6); a base-rate/speed control that
    sets the **released** (auto-play) playback rate without changing pitch (R7); an
    A/B loop that wraps **B→A during auto-play**, is ignored while grabbed, and
    resumes on release (R8); drop/label/delete markers and click/hotkey jump-to
    (R8); a **turntable/varispeed mode toggle** (R18) that swaps the live deck
    between the pitch-preserving stretcher and a pitch-bends-with-speed
    `VarispeedStretcher`, both behind the same `IStretcher` seam.
  - **Deferred**: recording the pitch/speed/loop/mode as automation and offline
    render (Phase 3, R9/R12); tempo/beat detection (never — non-goal §3; "4-bar"
    regions are selected visually, not detected); timeline/multi-clip, per-clip
    automation curves (Phase 4+).

- **Depends on**
  - **Phase 1** (walking skeleton): `AudioDevice` (miniaudio callback),
    `ScrubEngine` + `BungeeStretcher` behind `IStretcher`, the **lock-free control
    block** the UI writes the drag target into, the transport grabbed/released
    state, `WaveformView` with a playhead, `TransportBar`, and the single-clip
    session/clip state that holds the loaded media + playhead. Phase 2 **extends**
    these; it does not re-create them.
  - External: Bungee (MPL-2.0) already vendored in `third_party/bungee/` and linked.

---

## Tasks

> Ordered so data plumbing (control block + stretcher interface) lands before the
> engine logic that reads it, before the UI that writes it. Paths are from plan
> §6.3. Requirement strength per RFC 2119
> (<https://datatracker.ietf.org/doc/html/rfc2119>).

### 1. Extend the lock-free control block with pitch / speed / loop / seek fields
- **Files**: the Phase-1 `ScrubControl` control-block definition in
  `src/engine/audio/ScrubEngine.h` (the struct Phase 1 placed there, already carrying
  `targetPosSeconds` / `scrubbing` / `playing` + the published playhead).
- **Shapes** (all `std::atomic`, single-word, lock-free — **NFR-A: no locks/allocs
  in the audio thread**):
  - `std::atomic<float>  pitchRatio{1.0f};`    — frequency multiplier, UI-computed
    (see Task 3); `1.0` = no shift.
  - `std::atomic<float>  baseRate{1.0f};`      — released-playback speed multiplier.
  - `std::atomic<bool>   loopEnabled{false};`
  - `std::atomic<int64_t> loopBeginFrame{0};`
  - `std::atomic<int64_t> loopEndFrame{0};`
  - `std::atomic<uint32_t> loopGeneration{0};` — bump after writing the A/B pair so
    the audio thread reads a **consistent** (begin,end) snapshot (guards against a
    torn pair on the boundary block).
  - `std::atomic<int64_t> seekTargetFrame{0};` + `std::atomic<uint32_t> seekSeq{0};`
    — discrete playhead jump (marker jump / A/B click); audio thread acts when
    `seekSeq` changes.
  - `std::atomic<PlaybackMode> playbackMode{PlaybackMode::PitchPreserving};` — live
    deck stretcher mode (R18); the audio thread selects `BungeeStretcher` vs
    `VarispeedStretcher` by this. `PlaybackMode` enum per §6.5 (see Task 6).
- **Approach**: UI/render thread writes with `store(..., std::memory_order_release)`;
  audio thread reads with `acquire`. No new mutex, no allocation. Keep the struct a
  POD of atomics preallocated in Phase 1's engine instance. *(satisfies R6, R7, R8;
  NFR-A)*

### 2. Populate the existing `StretchRequest.pitch` field (no new `IStretcher` surface)
- **Files**: `src/engine/audio/ScrubEngine.{h,cpp}` (populates the field each block —
  see Task 5). `IStretcher` and `BungeeStretcher.{h,cpp}` need **no changes** for
  pitch.
- **What Phase 1 already provides (do not re-add)**: Phase 1 defined the per-block
  `IStretcher` call around `StretchRequest { double positionFrames; double speed;
  double pitch; bool reset; }`, and `BungeeStretcher` **already forwards `pitch`**
  into Bungee's `Bungee::Request::pitch` (a frequency multiplier independent of the
  time-ratio; header
  <https://github.com/bungee-audio-stretch/bungee/blob/main/bungee/Bungee.h>). Phase 1
  simply passes `pitch = 1.0` (no shift).
- **Approach**: Phase 2 **only populates** the existing `StretchRequest.pitch` — the
  `ScrubEngine` reads the pitch ratio from `ScrubControl` (Task 1) and writes it into
  `StretchRequest.pitch` each block **without touching `positionFrames` or `speed`**,
  so the drag-driven position and the manual pitch stay orthogonal. The value is a
  ratio, `2^(semitones/12)` (UI-computed with cents in Task 3); `1.0` = no shift.
  **Do NOT add a `pitchRatio` parameter or a `setPitch(float)` to `IStretcher`** — the
  field is already there. For swappability (§8) the same ratio maps onto Signalsmith's
  transpose factor (MIT fallback,
  <https://github.com/Signalsmith-Audio/signalsmith-stretch>). *(satisfies R6, R16)*

### 3. `PitchSpeedControls` widget — pitch (±36 st + cents) and base rate
- **Files**: `src/ui/PitchSpeedControls.{h,cpp}` (new).
- **Class**: `class PitchSpeedControls : public QWidget` exposing signals
  `pitchRatioChanged(float ratio)` and `baseRateChanged(float rate)`, plus setters
  to reflect state.
- **Controls**:
  - **Pitch**: a semitone stepper/slider clamped to **±36 semitones** (per OQ2/R6,
    matching the ref app AudioStretch's ±36 with 1-cent fine —
    <https://www.audiostretch.com/>) **plus** a cents fine control (±100 cents,
    1-cent resolution). A reset-to-0 button. UI computes the single ratio
    `pitchRatio = 2^((semitones*100 + cents)/1200)` and emits `pitchRatioChanged`.
  - **Speed/base rate**: a rate slider (e.g. 0.25×–4×, default 1.0×) emitting
    `baseRateChanged`; label shows the multiplier.
- **Wiring**: `MainWindow` connects `pitchRatioChanged → scrubControl.pitchRatio.store`
  and `baseRateChanged → scrubControl.baseRate.store` (release ordering). No engine
  calls from the UI thread beyond the atomic store. **MUST** keep pitch a distinct
  control from speed (the two sliders never cross-couple). *(satisfies R6 [MUST],
  R7 [SHOULD])*

### 4. Model: A/B loop bounds + `Marker` list on the single-clip session state
- **Files**: `src/engine/model/Marker.h` (new, small value type) and the Phase-1
  single-clip session/clip state (extend it in `src/engine/model/`).
- **Shapes**:
  - `struct Marker { int64_t frame; QString label; };` (stable id optional — use
    vector index or a monotonic `uint32_t id` for delete-by-id).
  - On the session/clip state add: `int64_t loopBeginFrame`, `int64_t loopEndFrame`,
    `bool loopEnabled`, and `std::vector<Marker> markers` (kept sorted by `frame`).
- **Approach**: these are the **authoritative** UI-thread values (source of truth for
  redraw and, in Phase 3, for what gets recorded). The atomics in Task 1 are the
  RT-thread mirror; the model updates the atomics on every edit. Provide small helper
  methods `addMarker/removeMarker/setLoop`. Sample-frame units (not seconds) so scrub
  math is exact. *(satisfies R8)*

### 5. `ScrubEngine` transport: loop wrap + discrete seek, both RT-safe
- **Files**: `src/engine/audio/ScrubEngine.{h,cpp}` (extend Phase-1 engine).
- **Approach** (runs in the audio callback each block):
  1. **Pitch/speed read**: read `pitchRatio` → pass to `IStretcher` (Task 2); when
     **released/auto-playing**, advance the source position by `baseRate` frames per
     output frame and set `Request::speed = baseRate`. When **grabbed**, position is
     driven by the Phase-1 drag target exactly as before (pitch still applied).
  2. **A/B loop wrap**: if `loopEnabled` **and not grabbed**, read a consistent
     (begin,end) via `loopGeneration`; when the advancing position reaches/crosses
     `loopEndFrame`, wrap to `loopBeginFrame` (carry the overshoot) and request a
     stretcher **reset** at the discontinuity (Bungee `Request::reset = true`) to
     avoid a click. **While grabbed the loop is ignored** — scrubbing freely crosses
     A/B; the loop **resumes on release**.
  3. **Discrete seek**: when `seekSeq` differs from the last-seen value, snap the
     source position to `seekTargetFrame` and reset the stretcher (marker jump / A/B
     click land glitch-free).
  - **MUST** remain lock-free/alloc-free (NFR-A); all inputs are the Task-1 atomics.
  *(satisfies R8 [MUST], R7)*

### 6. Turntable / varispeed mode — `VarispeedStretcher` behind the `IStretcher` seam
- **Files**: `src/engine/audio/VarispeedStretcher.{h,cpp}` (new) using the
  `Resampler` (`src/engine/audio/Resampler.{h,cpp}`, per §6.3);
  `src/engine/audio/ScrubEngine.{h,cpp}` (mode-select in the block loop); the
  `playbackMode` field on the single-clip session/deck state (`src/engine/model/`,
  Task 4) mirrored by the control-block mode atomic (Task 1); a mode toggle on
  `src/ui/PitchSpeedControls.{h,cpp}` (Task 3) or `src/ui/TransportBar`.
- **Types (per §6.5 — use verbatim)**: `enum class PlaybackMode { PitchPreserving,
  Varispeed };`, default `PitchPreserving`. Both `BungeeStretcher` (pitch-preserving)
  and `VarispeedStretcher` (resampler) are `IStretcher` implementations behind the
  same seam; §6.5 fixes this contract cross-phase. In Phase 2 the **live deck /
  session state** holds the current `PlaybackMode`; Phase 3 stamps it into
  `Take::playbackMode` (§6.5) at record time and Phase 4's `makeClipFromTake` copies
  it onto the per-`Clip` `playbackMode` field (§6.2, which arrives with the `Clip`
  struct in Phase 4).
- **`VarispeedStretcher`**: an `IStretcher` impl that reads source through the
  `Resampler` at a **variable rate equal to the current playback speed**, so **pitch
  bends with drag speed** (real-vinyl feel) instead of being held constant. Reverse =
  **backward read** (negative resampler step); near-zero speed = a **tiny step**
  (never stall or divide-by-zero). It takes the **same per-block absolute-position
  input** as `BungeeStretcher`, so both consume the identical playhead/automation
  input (§6.5). The pitch-preserving `pitchRatio` (Tasks 2/3) is inert in this mode;
  the base-rate control (Task 3) biases the resample rate.
- **`ScrubEngine` selection**: each block, read `playbackMode` (Task-1 atomic) and
  route the source read through the preallocated `BungeeStretcher` (PitchPreserving)
  or `VarispeedStretcher` (Varispeed) — a **branch on a preallocated pair**, no
  alloc/lock in the callback (NFR-A). Grab/release, loop-wrap and discrete seek
  (Task 5) work unchanged in either mode. Reset the newly-selected stretcher at a
  mode switch to avoid a click at the discontinuity.
- **UI toggle**: a two-state **Pitch-preserve ⟷ Turntable** toggle; on change
  `MainWindow` stores the `PlaybackMode` into the deck/session state (Task 4) **and**
  the control-block atomic (Task 1) via a release store — the UI thread never calls
  the audio thread directly. This sets the **live deck's** mode. That mode is what
  gets captured on new recordings downstream (§6.5): Phase 3 **stamps the deck's
  current `PlaybackMode` into the `Take`** at record time (`struct Take { MediaId
  mediaId; Automation automation; PlaybackMode playbackMode; }`, §6.5), and Phase 4's
  `makeClipFromTake` **copies it onto the new `Clip::playbackMode`** (§6.2/§6.5).
- **MUST** keep both paths behind `IStretcher` so switching is seamless and RT-safe.
  *(satisfies R18 [MUST], R16, NFR-A)*

### 7. `LoopMarkerBar` widget — A/B handles + enable/disable, drawn under the waveform
- **Files**: `src/ui/LoopMarkerBar.{h,cpp}` (new). Shares the `WaveformView`
  horizontal time mapping (px↔frame) so handles align with the waveform.
- **Class**: `class LoopMarkerBar : public QWidget` with signals
  `loopChanged(int64_t begin, int64_t end)`, `loopEnabledChanged(bool)`,
  `markerJumpRequested(int64_t frame)`.
- **Behaviour**:
  - Draggable **A** and **B** handles set `loopBeginFrame`/`loopEndFrame`; the region
    between is shaded on the bar/waveform. Clamp `A < B`; snapping to markers is a
    MAY.
  - A **loop enable** toggle (button/checkbox) drives `loopEnabledChanged`.
  - On any change `MainWindow` updates the model (Task 4) **and** publishes to the
    control block: store begin/end then **bump `loopGeneration`**, then store
    `loopEnabled`.
  - A **click on the A or B handle** (or double-click in the region) emits
    `markerJumpRequested` → a seek (Task 5) so the deck jumps there while playing.
  *(satisfies R8)*

### 8. Markers: drop / label / delete + click & hotkey jump-to-playhead
- **Files**: `src/ui/LoopMarkerBar.{h,cpp}` (marker flags rendered on the same bar)
  and marker gestures on `src/ui/WaveformView.{h,cpp}` (Phase 1); model from Task 4;
  hotkeys registered in `src/app/MainWindow.{h,cpp}`.
- **Behaviour**:
  - **Drop**: a "Add marker" action / hotkey (e.g. `M`) drops a `Marker` at the
    current playhead frame; also drop at a right-click position on the bar.
  - **Label**: double-click a marker flag → inline rename (writes `Marker::label`).
  - **Delete**: select a flag + `Delete`, or a context-menu entry → `removeMarker`.
  - **Jump**: click a marker flag **or** press next/prev-marker hotkeys (e.g. `,`/`.`)
    to jump the playhead to the nearest marker → emits `markerJumpRequested(frame)` →
    seek (Task 5). Markers persist in the Phase-1 session/clip state (Task 4), ready
    for Phase 4 project save.
  *(satisfies R8)*

### 9. Wire controls into `MainWindow` layout and the engine
- **Files**: `src/app/MainWindow.{h,cpp}`.
- **Approach**: add `PitchSpeedControls` (incl. the Task-6 mode toggle) and
  `LoopMarkerBar` to the main layout (pitch/speed/mode near the `TransportBar`; the
  loop/marker bar directly under `WaveformView`). Connect every signal from
  Tasks 3/6/7/8 to (a) the model (Task 4) and (b) the engine control block
  (Tasks 1/5/6) via atomic stores — the UI thread **never** calls into the audio
  thread directly. On file load, reset pitch=0/rate=1, mode=PitchPreserving, clear
  loop+markers. *(satisfies R6, R7, R8, R18)*

### 10. Headless engine test for controls (no GUI)
- **Files**: `tests/ScrubEngineControlsTest.cpp` (new; wire into `tests/` + CMake).
- **Cases** (drive `ScrubEngine` directly with a synthetic mono ramp/sine buffer):
  1. `pitchRatio` conversion: +5 semitones ⇒ ratio ≈ `2^(5/12)` ≈ `1.3348` reaches
     `Request::pitch`; position unchanged.
  2. Auto-play loop: with `loopEnabled` and begin/end set, position advancing past
     `end` **wraps** to `begin` (asserts the wrapped frame and that a reset was
     flagged).
  3. Grabbed overrides loop: with `grabbed=true`, position may exceed `end` without
     wrapping.
  4. Seek: bumping `seekSeq` snaps position to `seekTargetFrame`.
  5. Mode select (R18): with `playbackMode = Varispeed` the block routes through the
     `VarispeedStretcher` (resample step tracks speed → pitch scales with speed);
     with `PitchPreserving` it routes through `BungeeStretcher` (position drives the
     stretcher, `pitchRatio` sets pitch); the switch allocates nothing.
  *(satisfies R6, R7, R8, R18; NFR-A regression guard)*

---

## Deliverables
- New widgets `src/ui/PitchSpeedControls.{h,cpp}` and `src/ui/LoopMarkerBar.{h,cpp}`.
- `src/engine/model/Marker.h` + loop/markers fields on the Phase-1 session state.
- Extended lock-free control block (pitch/rate/loop/seek/mode atomics) and
  `IStretcher`/`BungeeStretcher` pitch plumbing.
- `VarispeedStretcher` (`src/engine/audio/VarispeedStretcher.{h,cpp}`, resampler
  `IStretcher`) + `PlaybackMode` on the deck/session state + `ScrubEngine`
  mode-select + the Pitch-preserve/Turntable UI toggle (R18).
- `ScrubEngine` loop-wrap + discrete-seek logic (RT-safe).
- `MainWindow` wiring; `tests/ScrubEngineControlsTest.cpp`.

## Exit / acceptance criteria (pass/fail)
1. **Build/lint gate**: `cmake --build build` completes with no new warnings-as-
   errors; the app binary (`./build/audio-stretcher`, from Phase 1) launches.
2. **Headless test passes**: `ctest --test-dir build -R ScrubEngineControls` (or the
   bin) is **green** — all five cases in Task 10 pass.
3. **Pitch independent of drag speed** (R6): load a tonal clip; set pitch **+12 st**;
   grab and scrub fast, slow, and near-zero — the perceived pitch is one octave up
   and **does not change with drag speed**; setting **−12 st** is one octave down;
   `0 st` returns to source pitch. Fine cents nudge is audible (± a few cents).
4. **Pitch constant *during* a single scrub** (R6): while holding a continuous drag,
   pitch stays fixed (no pitch drift as speed varies mid-drag).
5. **Speed independent of pitch** (R7): release the deck; change base rate 0.5×↔2×;
   the **auto-play** rate changes while **pitch is unchanged**; the current pitch
   setting is unaffected.
6. **A/B loop** (R8): set A and B around a region, enable loop; on auto-play the
   playhead **wraps B→A repeatedly with no click**; **grab and drag past B — it does
   not wrap**; on **release the loop resumes**; toggling loop off plays straight
   through B.
7. **Markers** (R8): drop ≥2 markers (via hotkey and via bar), label one, delete one;
   clicking a flag / pressing the jump hotkey moves the playhead to that marker while
   the deck keeps playing; markers survive within the session state.
8. **Turntable / varispeed mode** (R18): toggle the deck to **Turntable** and scrub —
   dragging now **bends pitch with speed** (slow drag = low, fast = high, like vinyl),
   **including reverse**; toggle back to **Pitch-preserve** and the same scrub again
   holds **constant pitch**. Both switches are **seamless** — shared playhead/
   automation input, no reload, audio keeps playing across the toggle.
9. **END-TO-END**: load a clip, set pitch **+5 semitones**, set A/B around a ~4-bar
   region and enable loop, then **scrub back-and-forth within the region** — the
   pitch stays constant at +5 st regardless of drag direction/speed, and on release
   the region **loops** cleanly.

## Independently shippable?
**Yes.** Users go from "scratch a clip at source pitch" (Phase 1) to a usable
practice/scratch instrument: transpose in real time (±36 st + cents), set a
released base speed, loop an A/B region hands-free, drop/jump named markers, and
**switch between pitch-preserving and turntable/vinyl scrub** (R18) — with pitch
held constant while scrubbing in the default mode. Recording these to `.mp4` is
Phase 3.
