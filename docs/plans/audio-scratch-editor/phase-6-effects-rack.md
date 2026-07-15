# Phase 6 — Audio Effects Rack

- **Goal**: Give clips a chain of **non-destructive, RT-safe insert audio effects**
  (delay/echo, reverb, distortion, chorus/vibrato, stutter, bitcrusher/downsample,
  phaser/flanger) that are audible in
  live preview and whose offline-render outputs **match the preview within a small
  epsilon once parameter smoothing settles** (per R17 — not bitwise-identical),
  with stutter also repeating video frames on video clips.

- **Scope**:
  - **In:** an `IEffect` interface (preallocated, RT-safe `process`); a runtime
    effect chain that runs per clip; seven concrete effects
    (Delay, Reverb, Distortion, Chorus/Vibrato, Stutter, BitCrusher, Phaser); wiring the chain into
    the **ScrubEngine preview path** and the **OfflineRenderer** using one shared
    code path (R17); the stutter frame-repeat hook into the video path (Phase 5);
    an `EffectRackView` UI to add/remove/reorder/bypass/edit-params on the selected
    clip; serialization of effect chains into the project file.
  - **Explicitly deferred:** per-parameter **automation** of effect params over time
    (v1 effect params are **static per clip**); side-chaining; track/bus effects
    (effects are per-clip only, R11); any **video** effects/filters (R19, out of
    scope); plugin/VST hosting (non-goal). The full timeline render + packaging is
    **Phase 8** — this phase only proves FX in preview and in the renderer unit.

- **Depends on**:
  - **Phase 4** — `model/Clip` with an `effectChain[]` field, `model/EffectChain`,
    `ProjectIO` (JSON save/load), `UndoStack` (R14), and the selection model that
    tells the UI which clip is "selected".
  - **Phase 5** — the layered clip/frame model + `VideoScrubber`/compositor time→frame
    mapping, required so **Stutter on a video clip repeats the corresponding frames**.
    (Audio-only stutter needs only Phase 4.)
  - **Phase 1** — the existing lock-free control block / SPSC hand-off to the audio
    thread (the **singular transport** control block — **not** overloaded here; per-clip
    chains use each clip's own `std::atomic<RtEffectChain*>`, per §6.5) and the
    `ScrubEngine`→`Mixer` signal path.
  - **External:** miniaudio RT callback contract — no locks/allocs/IO in the audio
    thread (<https://github.com/mackron/miniaudio>).

---

## Tasks

### 1. `IEffect` interface + runtime chain (RT-safe contract)
- **Files:** `src/engine/audio/effects/IEffect.h` (new);
  `src/engine/audio/effects/RtEffectChain.{h,cpp}` (new).
- **Shapes / API:**
  ```cpp
  namespace ase::audio {
  enum class EffectType { Delay, Reverb, Distortion, Chorus, Stutter, BitCrusher, Phaser };

  class IEffect {
  public:
    virtual ~IEffect() = default;
    // OFF-RT ONLY: allocate ALL state for this block size / channels / rate.
    virtual void prepare(double sampleRate, int maxBlockFrames, int channels) = 0;
    // RT-SAFE: in-place, per-channel, NO alloc/lock/IO. clipStartFrame = clip-local
    // sample index of io[0]; needed by time-aware effects (Stutter).
    virtual void process(float* const* io, int frames, int64_t clipStartFrame) = 0;
    // RT-SAFE: store new target for paramId (atomic); applied via smoothing in process.
    virtual void setParam(int paramId, float value) = 0;
    virtual void reset() = 0;                 // clear tails; OFF-RT or at block edge
    virtual EffectType type() const = 0;
    bool bypassed{false};                     // read atomically in the mixer
  };
  }
  ```
- **Approach:** `IEffect::process` MUST be allocation- and lock-free; **all buffers
  (delay lines, comb/allpass banks, capture buffers, LFO state) are allocated once in
  `prepare()`** off the audio thread. `RtEffectChain` owns a **fixed-capacity**
  `std::array<IEffect*, kMaxEffectsPerClip>` (e.g. cap 8) plus a count; its `process()`
  iterates non-bypassed effects in order calling each `process()`. The chain object is
  built off-RT and **published to the audio thread by atomic pointer swap on the clip's
  own `std::atomic<RtEffectChain*>`** — **separate** from the singular Phase-1 transport
  control block (per §6.5); the **`Mixer` fetches each active clip's current chain per
  block**. The previous chain is reclaimed on the non-RT side (deferred delete), never
  freed in the callback. (satisfies R11, **NFR-A**)

### 2. Model-side `EffectChain` on `Clip` + factory
- **Files:** `src/engine/model/EffectChain.{h,cpp}` (per §6.3 `model/`, new);
  `src/engine/audio/effects/EffectFactory.{h,cpp}` (new);
  `src/engine/model/ProjectIO.cpp` (extend, from Phase 4).
- **Shapes:** `struct EffectSpec { EffectType type; std::array<float,kMaxParams> params;
  bool bypass; };` and `class EffectChain { std::vector<EffectSpec> specs; };` — the
  **serializable** description stored on `Clip::effectChain` (§6.2). `EffectFactory::build(const EffectChain&, sampleRate, maxBlock, channels) → owned RtEffectChain`
  instantiates + `prepare()`s the concrete `IEffect`s off-RT — its `EffectType`
  switch covers **all seven** effects (adds `BitCrusher` and `Phaser` alongside the
  original five).
- **Approach:** `ProjectIO` gains read/write of the `effectChain` array (type enum by
  stable string key, param array, bypass flag) so chains round-trip through the
  `.asproj` JSON with the rest of the clip (non-destructive). (satisfies R11, R13)

### 3. Concrete effects (the seven)
- **Files (all new):** `src/engine/audio/effects/Delay.{h,cpp}`,
  `Reverb.{h,cpp}`, `Distortion.{h,cpp}`, `Chorus.{h,cpp}`, `Stutter.{h,cpp}`,
  `BitCrusher.{h,cpp}`, `Phaser.{h,cpp}`.
- **Param IDs / defaults per OQ1** (all params one-pole **smoothed** in `process` to
  avoid zipper noise; mark v1 params **MUST**-present, ranges refinable per OQ1):
  1. **Delay** (echo) — `time` (ms, 1..2000), `feedback` (0..0.95), `mix` (0..1).
     Preallocate a circular buffer sized to max `time` in `prepare()`; fractional
     (interpolated) read tap; `out = dry + mix·delayed`, `write = in + feedback·delayed`.
  2. **Reverb** — `roomSize` (0..1 → comb feedback), `mix` (0..1). Schroeder/Freeverb-style
     bank of parallel comb filters + series allpass per channel, **all buffers fixed-size
     and preallocated**; RT-safe steady-state.
  3. **Distortion** — `drive` (pre-gain), `tone` (one-pole LP/HP blend), `mix` (0..1).
     Waveshaper (`tanh`/soft-clip) then tone filter; only state is the filter's one
     sample per channel. Trivially RT-safe.
  4. **Chorus/Vibrato** — `rate` (LFO Hz), `depth` (ms), `mix` (0..1; `mix=1` ⇒ vibrato).
     Short modulated delay (~5–40 ms) preallocated in `prepare()`; sine LFO advanced by a
     phase increment (no table alloc in `process`); interpolated read.
  5. **Stutter** (YTP signature) — `sliceLenMs` (or beat-division), `repeatCount` (int),
     plus an implementation **anchor** = clip-local frame where the slice starts
     (default: clip start; UI may set it from the playhead — refine per OQ1).
     - **Audio:** on entering the stutter window, capture `sliceLenMs` of clip audio into
       a **preallocated** buffer (sized in `prepare()`), then output that slice looped for
       `repeatCount` repeats before passing input through — RT-safe (buffer reused, no
       alloc). Because it substitutes captured audio, it defines a **monotone
       clip-time→source-time remap**.
     - **Video hook:** `Stutter` exposes `int64_t remapSourceFrame(int64_t clipOutFrame) const`
       (identity outside the window; inside, maps back onto the repeating slice). The
       **video path consults this so the same source frames repeat** on a video clip
       (Phase-5 dependency). On audio-only clips (R21) the remap is simply ignored by the
       video path.
  6. **BitCrusher** (bit-depth reduction + sample-rate decimation) — `bits`
     (target bit depth, e.g. 1..16), `downsampleFactor` (integer decimation, 1..N →
     sample-and-hold every Nth sample), `mix` (0..1). Quantize each sample to
     `2^bits` levels and hold the last decimated sample for `downsampleFactor`
     frames; only state is a per-channel hold sample + counter (preallocated in
     `prepare()`). Trivially RT-safe (no buffers, no alloc in `process`).
  7. **Phaser/Flanger** (all-pass phaser) — `rate` (LFO Hz), `depth` (0..1 → LFO sweep
     of the all-pass notch), `feedback` (0..0.95), `mix` (0..1). A cascade of N
     first-order all-pass sections whose coefficient is modulated by a sine LFO
     (phase increment, no table alloc in `process`); the summed dry+wet forms the
     moving notches, with `feedback` routing the wet output back in. All all-pass
     state (one sample per section per channel) + LFO phase are preallocated in
     `prepare()`; RT-safe.
  - (satisfies R11, OQ1)

### 4. Chain in preview **and** offline — one code path
- **Files:** `src/engine/audio/ScrubEngine.{h,cpp}` (extend),
  `src/engine/audio/Mixer.{h,cpp}` (extend),
  `src/engine/render/OfflineRenderer.{h,cpp}` (extend),
  `src/engine/video/VideoScrubber.{h,cpp}` (extend).
- **Approach — audio:** after `ScrubEngine` produces a clip's pitch-preserved PCM block,
  the `Mixer` fetches that clip's current chain (from its `std::atomic<RtEffectChain*>`)
  and runs `RtEffectChain::process()` **before summing** into the master. The
  **`OfflineRenderer` calls the exact same `effects/*` objects with the exact same
  `EffectSpec` param values**, so exported FX == preview FX (R17). Because v1 effect
  params are **static per clip**, once parameter smoothing settles the two paths' outputs
  **match within a small epsilon** for a given input block (per R17 — not bitwise-identical).
- **Approach — video (Stutter):** the `VideoScrubber` (preview) and the `OfflineRenderer`'s
  compositor pass their computed clip-output frame through the clip's `Stutter::remapSourceFrame`
  (if a Stutter is present) before the `FrameCache` lookup, so repeated audio slices land on
  repeated frames in both preview and export (Phase 5). (satisfies R11, R17)

### 5. Effect rack UI
- **File:** `src/ui/EffectRackView.{h,cpp}` (new, per §6.3 `ui/`).
- **Approach:** a panel bound to the **selected clip** (Phase-4 selection). It lists the
  clip's `EffectChain` and supports **add** (pick from the seven types), **remove**,
  **reorder** (drag or up/down), **bypass** (per effect), and **param editing** (sliders
  per param). Structural edits (add/remove/reorder/bypass) go through the **`UndoStack`**
  (R14) as commands that mutate `Clip::effectChain`, then trigger an **off-RT rebuild**
  (`EffectFactory::build`) and an **atomic publish** of the new `RtEffectChain` into the
  clip's own `std::atomic<RtEffectChain*>` (per §6.5; the old chain is deferred-deleted).
  Live **param drags** call `IEffect::setParam` (atomic, no rebuild) for
  click-free real-time tweaking. (satisfies R11, R14)

---

## Deliverables
1. `src/engine/audio/effects/IEffect.h`, `RtEffectChain.{h,cpp}`, `EffectFactory.{h,cpp}`.
2. `src/engine/audio/effects/{Delay,Reverb,Distortion,Chorus,Stutter,BitCrusher,Phaser}.{h,cpp}` — seven working RT-safe effects.
3. `src/engine/model/EffectChain.{h,cpp}` + `Clip::effectChain` populated; `ProjectIO` round-trip of chains.
4. `Mixer`/`ScrubEngine`/`OfflineRenderer` wired to run the per-clip chain; `VideoScrubber`/renderer honour the stutter frame-remap.
5. `src/ui/EffectRackView.{h,cpp}` wired to the selected clip.
6. Tests in `tests/`: (a) an **RT-safety / bounded-time** harness that runs a full 8-effect chain over N blocks and asserts **zero allocations and no locks** in `process`; (b) a **determinism** test asserting `OfflineRenderer` output for a fixed input + fixed `EffectChain` **matches the preview-path output within a small epsilon once parameter smoothing settles** (R17 — not bitwise-identical).

---

## Exit / acceptance criteria (pass/fail)
- [ ] **Build/lint gate:** `cmake --build` succeeds on Linux with no new warnings-as-errors; all new files compile into the existing targets.
- [ ] **RT-safety (NFR-A):** the `tests/` chain harness reports **0 heap allocations and 0 lock acquisitions** inside `IEffect::process`/`RtEffectChain::process` across ≥10 000 blocks; buffers are all allocated in `prepare()`. (`ctest -R effects_rtsafe` passes.)
- [ ] **Delay + Distortion audible in preview:** add Delay and Distortion to a clip in `EffectRackView`, scrub/play it → the output has an audible echo tail and drive; toggling **bypass** on each removes its effect in real time with no dropout.
- [ ] **BitCrusher + Phaser audible in preview:** add BitCrusher (low `bits` / high `downsampleFactor`) and Phaser (audible `rate`/`depth`/`feedback`) to a clip → the output is audibly lo-fi/crushed and gains a sweeping notch/whoosh; toggling **bypass** on each removes its effect in real time with no dropout.
- [ ] **Stutter audio:** add a Stutter (`sliceLenMs`, `repeatCount≥3`) → the chosen slice audibly repeats the set number of times.
- [ ] **Stutter video (Phase 5):** on a **video** clip, the same Stutter makes the corresponding **frames visibly repeat** in the preview `VideoView`, in lock-step with the audio repeats.
- [ ] **Preview == export (R17):** `tests/` determinism test passes — for a fixed input clip and chain, `OfflineRenderer` output **matches the preview mixer output within a small epsilon once parameter smoothing settles** (R17 — not bitwise-identical); i.e. "the same FX appear in an export."
- [ ] **Persistence (R13):** save a project with a chain, reload it → the same effects, order, params, and bypass flags are restored.
- [ ] **END-TO-END:** apply **echo + stutter** to one clip, scrub it, and confirm the effect is **audible** (echo tail + repeated slice) and the stutter's frame-repeat is **visible** on a video clip; then run the `OfflineRenderer` unit over that clip and confirm the rendered PCM/frames contain the identical echo and repeated slice (full-timeline export to `.mp4` is Phase 8).

---

## Independently shippable?
**No** — this phase produces no new standalone binary; it plugs FX into the existing
engine and depends on the Phase-4 timeline (and Phase-5 clip/frame model for video
stutter). **New user-visible capability:** a user can now add, order, tweak, bypass,
and save **echo/delay, reverb, distortion, chorus/vibrato, stutter,
bitcrusher/downsample, and phaser/flanger** on any clip
and hear them while scrubbing — the YTP effects toolkit — with those exact effects
carried into the (Phase-8) export.
