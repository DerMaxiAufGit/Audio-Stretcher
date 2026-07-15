# Phase 7 — Video effects

- **Goal**: Give each video-layer `Clip` a **non-destructive per-clip video effect
  chain** (colour/contrast, chroma-key, glitch) applied to its source frame **before**
  the shared `Compositor` blends it — identically in best-effort GPU preview
  (`VideoView`) and the exact CPU `OfflineRenderer`, so a keyed/glitched clip looks the
  same in both.

## Scope
- **In:** an `IVideoEffect` interface + a runtime `RtVideoEffectChain`
  (`process(RGBA frame)`) that runs per clip in the **frame/composite pipeline** (NOT
  the RT audio thread — no NFR-A constraint, per plan §6.5); a serializable model-side
  `VideoEffectChain` (`std::vector<VideoEffectSpec>`) on each **video-layer** `Clip`
  (`Clip::videoEffectChain`, per plan §6.2/§6.5); a `VideoEffectFactory` that compiles
  the model chain into an `RtVideoEffectChain`; three concrete effects — **Colour**
  (brightness/contrast/saturation), **ChromaKey** (key colour + tolerance + spill
  suppression + edge feather, **outputs alpha**), **Glitch** (RGB channel split/offset +
  block-shift/datamosh-style displacement, seeded/animatable); wiring the chain into the
  Phase-5 frame pipeline (offline: CPU `RtVideoEffectChain::process`; preview: mirroring
  GPU shader passes in `VideoView`) so **preview ≈ render within a small epsilon** (R17);
  `.asproj` persistence + undo commands for chain edits; a `VideoEffectRackView` UI bound
  to the selected video `ClipItem`.
- **Explicitly deferred:** per-parameter **automation** of video-FX params over time
  (v1 params are **static per clip**, mirroring Phase 6's audio FX — the only per-frame
  varying input is Glitch's deterministic `seed + clipFrameIndex` animation, not a user
  keyframe track); any **audio** effects (R11 → Phase 6); layer/track-level (bus) video
  FX (effects are **per-clip**, video-layer clips only, per §6.2); masks/keyframed
  rotoscoping, motion-tracking, transitions between clips; the final whole-timeline
  `.mp4` **encode/mux** (`Encoder`) — **Phase 8** (this phase proves FX in preview and in
  the `Compositor`/`OfflineRenderer` frame path only); plugin/VST-style hosting (non-goal).

## Depends on
- **Phase 5** — the shared **`Compositor`** (`src/engine/render/Compositor`, the ONE
  bottom→top src-over blend taking `std::vector<LayerFrame>`; each `LayerFrame` carries a
  decoded **RGBA** source frame + its `Transform`), the multi-voice frame pipeline that
  resolves each visible layer's active-clip frame via `VideoScrubber`/`FrameCache`, the
  `OfflineRenderer` exact video pass, and the texture-backed GPU preview `VideoView`.
  Video FX apply to the per-layer frame **before** it becomes a `LayerFrame` handed to
  `Compositor::composite`. ChromaKey's alpha output flows through the Compositor's
  **existing** src-over (`out = src.rgb·(src.a·opacity) + out.rgb·(1 − src.a·opacity)`),
  so the keyed clip reveals lower layers with **no Compositor change**.
- **Phase 4** — `Clip` (extended here with `videoEffectChain`), `ProjectIO` (`.asproj`
  JSON save/load), `UndoStack` + `TimelineCommands` (`QUndoStack`/`QUndoCommand`), and
  the selection model that tells the UI which `ClipItem` is selected.
- **Structure mirror:** this phase mirrors **Phase 6** (audio effects rack) — `IEffect`
  → `IVideoEffect`, `RtEffectChain` → `RtVideoEffectChain`, `EffectFactory` →
  `VideoEffectFactory`, `EffectChain`/`EffectSpec`/`EffectType` →
  `VideoEffectChain`/`VideoEffectSpec`/`VideoEffectType`, `EffectRackView` →
  `VideoEffectRackView` — but runs in the frame/composite pipeline, not the audio callback.
- **External:** Qt 6 GPU frame path (LGPLv3, <https://doc.qt.io/qt-6/licensing.html>) for
  the `VideoView` shader passes; `QUndoStack` for chain-edit undo
  (<https://doc.qt.io/qt-6/qundostack.html>).

---

## Tasks

### 1. `IVideoEffect` interface + `RtVideoEffectChain` + model `VideoEffectChain` on `Clip`
- **Files (new unless noted):** `src/engine/video/effects/IVideoEffect.h`;
  `src/engine/video/effects/RtVideoEffectChain.{h,cpp}`;
  `src/engine/video/effects/VideoEffectFactory.{h,cpp}`;
  `src/engine/model/VideoEffectChain.{h,cpp}` (per §6.3 `model/`);
  `src/engine/model/Clip.{h,cpp}` (extend — add the `videoEffectChain` field).
- **Shapes / API:**
  ```cpp
  namespace ase::video {
  enum class VideoEffectType { Colour, ChromaKey, Glitch };

  // In-place mutable RGBA view of a LayerFrame's pixel buffer (Phase 5).
  struct RgbaFrame { uint8_t* rgba; int width; int height; int stride; };

  class IVideoEffect {
  public:
    virtual ~IVideoEffect() = default;
    // OFF-pipeline: allocate ALL scratch for this frame size (e.g. Glitch temp buffer).
    virtual void prepare(int width, int height) = 0;
    // Frame/composite pipeline (NOT the RT audio thread — no NFR-A). In-place on RGBA;
    // ChromaKey WRITES ALPHA. clipFrameIndex = clip-local output-frame index — the §6.5
    // contract is process(RGBA frame); this phase extends it with clipFrameIndex (as
    // Phase 6's IEffect::process carries clipStartFrame) so Glitch animates deterministically.
    virtual void process(RgbaFrame& frame, int64_t clipFrameIndex) = 0;
    virtual void setParam(int paramId, float value) = 0;
    virtual VideoEffectType type() const = 0;
    bool bypassed{false};
  };
  }
  ```
  Model side (serializable): `struct VideoEffectSpec { VideoEffectType type;
  std::array<float,kMaxVideoParams> params; bool bypass; };` and
  `class VideoEffectChain { std::vector<VideoEffectSpec> specs; };` — stored on
  `Clip::videoEffectChain` (video-layer clips only; ignored on audio-track clips, per
  §6.2). `Clip` gains `VideoEffectChain videoEffectChain;` (empty by default, exactly as
  `EffectChain effectChain` is empty until populated).
- **Approach:** `RtVideoEffectChain` owns a fixed-capacity
  `std::array<IVideoEffect*, kMaxVideoEffectsPerClip>` (e.g. cap 8) + count; its
  `process(RgbaFrame&, int64_t)` iterates non-bypassed effects in order.
  `VideoEffectFactory::build(const VideoEffectChain&, width, height) → owned
  RtVideoEffectChain` instantiates + `prepare()`s the concrete `IVideoEffect`s off the
  pipeline. Each **video-layer** `Clip` publishes its compiled chain via a per-clip
  `std::atomic<RtVideoEffectChain*>` swapped on edit (old chain deferred-deleted) —
  mirroring Phase 6's per-clip `std::atomic<RtEffectChain*>` — **but because video FX run
  in the frame/composite pipeline, NOT the audio callback, this carries no NFR-A
  constraint** (§6.5); the atomic swap keeps preview from tearing during live edits, and
  allocation in the frame path is permitted. (satisfies R19)

### 2. The three concrete effects — `src/engine/video/effects/*`
- **Files (all new):** `src/engine/video/effects/Colour.{h,cpp}`,
  `ChromaKey.{h,cpp}`, `Glitch.{h,cpp}`.
- **Params / defaults per plan OQ9** (ranges refinable in this phase per OQ9;
  `kMaxVideoParams` sized to the widest effect):
  1. **Colour** — `brightness` ∈ [-1,1] (default **0**), `contrast` ∈ [0,2] (default
     **1**), `saturation` ∈ [0,2] (default **1**). Per pixel: apply contrast about
     mid-grey, add brightness, then lerp between luma-grey and colour by `saturation`.
     Leaves alpha untouched. Trivially deterministic.
  2. **ChromaKey** — `keyColour` (r,g,b in `params[0..2]`, default **green (0,1,0)**),
     `tolerance` ∈ [0,1] (default **0.15**), `spill` (suppression) ∈ [0,1] (default
     **0.5**), `feather` (edge softness) ∈ [0,1] (default **0.05**). Compute colour
     distance to `keyColour`; map through `tolerance ± feather` to a soft alpha (inside →
     `a=0` fully keyed, outside → `a` unchanged, feather band → smooth ramp); apply
     `spill` suppression to residual key-hue in surviving RGB. **Writes the frame's alpha
     channel** so the keyed clip composites over lower layers via the `Compositor`'s
     existing src-over — no Compositor change.
  3. **Glitch** — `splitPx` (RGB channel-split offset, px, default **4**), `blockSize`
     (px, default **24**), `blockShift` (max horizontal block displacement, px, default
     **16**), `seed` (uint, default **1**), `rate` (Hz, animation, default **8**),
     `amount` ∈ [0,1] (master intensity, default **0.5**). Offsets the R and B channels
     horizontally by ±`splitPx` (RGB-split) and shifts pixel blocks by a seeded
     pseudo-random displacement (datamosh-style). **Deterministic + animatable**: the PRNG
     is seeded from `seed` combined with `⌊clipFrameIndex · rate / fps⌋`, so the same
     clip frame always glitches the same way in preview and render. Uses a preallocated
     temp buffer (from `prepare()`) for the displaced read.
  - (satisfies R19)

### 3. Chain in the frame/composite pipeline — preview **and** offline, one param set
- **Files:** `src/engine/render/OfflineRenderer.{h,cpp}` (extend — CPU exact path);
  `src/ui/VideoView.{h,cpp}` (extend — GPU preview shader passes);
  `src/engine/video/VideoScrubber.{h,cpp}` (extend — return the pre-FX source frame; FX
  run on the returned frame before it becomes a `LayerFrame`);
  `src/engine/render/Compositor.{h,cpp}` (unchanged blend — consumed as-is, per §6.5).
- **Approach — offline (exactness reference, NFR-R):** in the Phase-5 video pass, for
  each visible layer's active-clip source frame returned by
  `VideoScrubber`/`FrameCache`, `OfflineRenderer` fetches the clip's current chain (from
  its `std::atomic<RtVideoEffectChain*>`) and calls `RtVideoEffectChain::process(frame,
  clipFrameIndex)` **before** wrapping the frame in a `LayerFrame` and passing the
  bottom→top vector to `Compositor::composite`. This CPU path is the deterministic
  reference.
- **Approach — GPU preview (best-effort, R17):** `VideoView` runs one **shader pass per
  non-bypassed effect** on each layer's active-clip texture (reading the same
  `VideoEffectSpec` params) **before** the Phase-5 composite quad draw. The shader math
  mirrors the CPU effects exactly (same Colour transform, same ChromaKey alpha/spill,
  same seeded Glitch), so **preview ≈ offline within a small epsilon** (R17 — not
  bitwise-identical). Under load preview MAY skip a pass / lower res, but MUST never
  block the audio thread. ChromaKey's alpha output feeds the existing Phase-5 src-over
  (`GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA`) so lower layers show through. (satisfies R19, R17)

### 4. Persistence + undo for `videoEffectChain`
- **Files:** `src/engine/model/ProjectIO.{h,cpp}` (extend);
  `src/engine/model/UndoStack.{h,cpp}` + `src/engine/model/TimelineCommands.{h,cpp}`
  (extend, following the Phase 4/6 command pattern).
- **Approach:** `ProjectIO` gains read/write of each video-layer clip's
  `videoEffectChain` array (`type` by stable string key, `params` array, `bypass` flag)
  alongside the existing `effectChain`/`transform`, bumping the `.asproj` schema
  `version`; round-trip MUST be **lossless** (save→load→deep-equal). New undoable
  commands mutate `Clip::videoEffectChain` then trigger an off-pipeline rebuild
  (`VideoEffectFactory::build`) + atomic publish (old chain deferred-deleted):
  `AddVideoEffectCommand`, `RemoveVideoEffectCommand`, `ReorderVideoEffectCommand`,
  `SetVideoEffectBypassCommand`, `SetVideoEffectParamCommand` (each stores minimal
  before/after state, per Phase 4). (satisfies R13, R14, R19)

### 5. `VideoEffectRackView` UI
- **File:** `src/ui/VideoEffectRackView.{h,cpp}` (new, per §6.3 `ui/`).
- **Approach:** a panel bound to the **selected video `ClipItem`** (Phase-4 selection;
  hidden/disabled for audio-track clips, which have no `videoEffectChain`). Lists the
  clip's `VideoEffectChain` and supports **add** (pick Colour / ChromaKey / Glitch),
  **remove**, **reorder** (drag or up/down), **bypass** per effect, and **param editing**
  (sliders/colour-swatch per param — e.g. a colour picker for ChromaKey `keyColour`).
  Structural edits go through the Task-4 `UndoStack` commands (→ rebuild + atomic
  publish); live param drags call `IVideoEffect::setParam` (no rebuild) for click-free
  tweaking with **live preview** refresh in `VideoView`. (satisfies R19)

---

## Deliverables
- `src/engine/video/effects/IVideoEffect.h`, `RtVideoEffectChain.{h,cpp}`,
  `VideoEffectFactory.{h,cpp}`.
- `src/engine/video/effects/{Colour,ChromaKey,Glitch}.{h,cpp}` — three working effects
  (ChromaKey outputs alpha; Glitch seeded/animatable).
- `src/engine/model/VideoEffectChain.{h,cpp}` + `Clip::videoEffectChain` field;
  `ProjectIO` lossless round-trip of the chain.
- `OfflineRenderer` (CPU `RtVideoEffectChain::process` before `Compositor::composite`) +
  `VideoView` (mirroring GPU shader passes) wired to run the per-clip chain before compositing.
- `UndoStack`/`TimelineCommands` extended with the five video-FX commands.
- `src/ui/VideoEffectRackView.{h,cpp}` bound to the selected video `ClipItem`; `CMakeLists.txt`
  updated with the new sources.
- Tests in `tests/`: (a) a **preview≈render** test asserting `VideoView` GPU readback
  matches `RtVideoEffectChain` CPU output for a fixed frame + fixed chain within a small
  epsilon (R17); (b) a **ProjectIO** save→load→deep-equal test over a clip with all three effects.

## Exit / acceptance criteria (pass/fail)
- [ ] **Build/lint gate:** `cmake --build` succeeds on Linux with no new
      warnings-as-errors; all new files compile into the existing targets.
- [ ] **ChromaKey (frame-dump 50/50 check):** place an overlay clip (solid green
      background + foreground) on a layer above a base clip, add ChromaKey with default
      green `keyColour` → dump the composited frame from **both** the CPU
      `Compositor`/`OfflineRenderer` path **and** a `VideoView` GPU frame-grab: in the
      previously-green region the pixels equal the **base layer** (keyed alpha≈0, lower
      layer shows through); in the foreground region the overlay survives; the two dumps
      match within a small epsilon.
- [ ] **Glitch (RGB-split visible):** add Glitch to a clip → the R and B channels are
      measurably offset from G (per-channel horizontal shift ≥ `splitPx`) and blocks are
      displaced; re-rendering the same clip frame is byte-stable (deterministic seed).
- [ ] **Colour (measurable):** add Colour and raise `brightness`/`contrast` → the frame's
      mean luma / histogram spread shift measurably in the expected direction; `saturation=0`
      yields greyscale.
- [ ] **Persistence (R13):** save a project with all three effects (order, params, bypass)
      then reload → the `videoEffectChain` is restored deep-equal; undo/redo of add/remove/
      reorder/bypass/param works (R14).
- [ ] **Preview ≈ render (R17):** the `tests/` determinism test passes — `VideoView` GPU
      output matches `RtVideoEffectChain` CPU output for a fixed frame + fixed chain within
      a small epsilon (not bitwise-identical).
- [ ] **END-TO-END:** green-screen an overlay clip over a base clip (ChromaKey removes the
      green, base shows through) **and** add a Glitch (visible RGB-split) on the same or
      another clip; confirm both looks in the live `VideoView` **preview** and that the CPU
      `OfflineRenderer` frame path produces the identical keyed/glitched composite within
      epsilon. (Full `.mp4` encode of this composite is verified in **Phase 8**.)

## Independently shippable?
**No** — the final `.mp4` encode/mux lands in **Phase 8**, so this phase produces no new
standalone deliverable binary; it plugs a per-clip video effect chain into the existing
Phase-5 compositor/frame pipeline. **But it is a large, visible increment:** the user can
now **green-screen (chroma-key) overlays**, apply **colour/contrast/saturation** grades,
and add **glitch/RGB-split** looks per clip — the YTP visual toolkit — and see them
interactively in preview, with those exact effects carried into the Phase-8 export.
