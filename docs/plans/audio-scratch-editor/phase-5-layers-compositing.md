# Phase 5 — Layered video compositing + multi-track audio mixing

- **Goal**: Stack N ordered video layers with per-clip transform (position/scale/crop/opacity) composited bottom→top, and mix N audio tracks with per-track gain — identically in best-effort GPU preview and in the exact offline renderer.

## Scope
- **In**: extend the project model to N `VideoLayer`s (z-order) + N `AudioTrack`s; per-video-clip `Transform{position, scale, crop, opacity}`; a shared bottom→top src-over **Compositor** used by GPU preview (`VideoView`) and CPU offline render (`OfflineRenderer`); an RT-safe/offline **Mixer** summing per-track voices with gain/mute; a `LayerHeader` UI for add/remove/reorder/show-hide + a transform inspector for the selected clip; a manual **"make proxy"** action (intra-frame DNxHR/ProRes transcode) so heavy long-GOP overlays scrub smoothly.
- **Explicitly deferred**: final `.mp4` muxing/encode of the composite (`Encoder`) — **Phase 8**; audio insert effects (7: echo/reverb/distortion/chorus/stutter/bitcrusher/phaser) — **Phase 6** (the Mixer only sums already-processed voices); video effects/filters → **Phase 7** (per-clip video-FX chain), **NOT deferred** (R19/§9 — video effects are IN v1 as Phase 7, not beyond it); **auto** proxy generation above a resolution/GOP threshold — R20/OQ5, only the *manual* action ships here; direct-manipulation drag handles for PiP in the preview — SHOULD, may slip to a polish pass (numeric inspector is the MUST).

## Depends on
- **Phase 4** (timeline foundation): `Project`, `VideoLayer`/`AudioTrack`, `Clip`, `Automation`, `EffectChain`, `ProjectIO` (JSON, `.asproj`), `UndoStack`, `TimelineView`/`ClipItem`, and the **timeline playback scheduler** which provides **SINGLE-voice** sequence scrub/playback — **one active clip at a time** (a single `ScrubEngine` voice) driving `VideoScrubber` for one video track + one audio track (per plan §9). Phase 5 **ADDS the simultaneous MULTI-VOICE scheduler** (Task 5): one `ScrubEngine` voice per concurrently-active clip across N layers/tracks, generalising that single-voice path so live composite + multiple tracks playing at once are delivered *here*.
- **Phases 1–3**: `ScrubEngine`/`BungeeStretcher` (pitch-preserving per-clip PCM), `VideoScrubber`+`FrameCache` (playhead→frame), `AudioDevice` (miniaudio RT callback), `OfflineRenderer` skeleton.
- **External**: FFmpeg/libav built LGPL (no `--enable-gpl`/`--enable-nonfree`) with the `prores_ks` and `dnxhd` encoders + `libswscale` available (R16). Qt 6 GPU frame path (texture-backed `VideoView`) from Phase 1.

## Tasks

1. **Model: N video layers + N audio tracks + per-clip transform.**
   Files: `src/engine/model/Project.{h,cpp}`, `src/engine/model/VideoLayer.{h,cpp}`, `src/engine/model/AudioTrack.{h,cpp}`, `src/engine/model/Clip.{h,cpp}`.
   - `Project`: replace the single video track / single audio track with `std::vector<VideoLayer> videoLayers` (**index 0 = bottom, ascending = up**; composite order is this vector order) and `std::vector<AudioTrack> audioTracks`. Add `addVideoLayer/removeVideoLayer/moveVideoLayer(from,to)` and `addAudioTrack/removeAudioTrack/moveAudioTrack`.
   - `VideoLayer`: `{ LayerId id; QString name; bool visible = true; float gainDb = 0.0f; bool muted = false; std::vector<Clip> clips; }` — `gainDb`/`muted` are **already defined by Phase 4** (per plan §6.5, like `AudioTrack`, default **0 dB / not muted**); this phase does **not** re-add them or bump the schema for them, it **uses** the existing fields to route the layer's video-clip audio through the `Mixer`. This phase adds **only** `bool visible`; `id`, `name`, `gainDb`, `muted` already exist from Phase 4 (plan §6.5) and are USED here, not re-added. `VideoLayer`s stay ordered by vector index (index 0 = bottom).
   - `AudioTrack` (Phase 4's struct, **used** here — not redefined): `{ TrackId id; QString name; float gainDb = 0.0f; bool muted = false; std::vector<Clip> clips; }` — `TrackId` (`= uint64_t`) and `AudioTrack` are **defined in Phase 4** (plan §6.5); this phase **references** `TrackId`, never redefines it. Track (and layer) ids originate in Phase 4 and are round-tripped in `.asproj` by `ProjectIO`.
   - `Clip`: add `Transform transform;` used only by video-layer clips (ignored on audio tracks). `struct Transform { QPointF position{0.5,0.5}; double scale{1.0}; QRectF crop{0,0,1,1}; double opacity{1.0}; };` — `position` = normalised centre of the clip in the output frame (0.5,0.5 = centred), `scale` = uniform factor relative to output frame, `crop` = normalised source sub-rect kept, `opacity` ∈ [0,1]. All resolution-independent so the same numbers drive preview and render.
   - (satisfies R10)

2. **Persistence + undo for the new model fields.**
   Files: `src/engine/model/ProjectIO.{h,cpp}`, `src/engine/model/UndoStack.{h,cpp}` (or `commands/` beside it, matching Phase 4).
   - `ProjectIO`: bump the `.asproj` schema `version` for the **new structure** (single video/audio track → ordered `videoLayers` + `audioTracks` arrays) and new fields; (de)serialise the ordered `videoLayers` array (preserving order), per-layer `name`/`visible`, and per-clip `transform{position,scale,crop,opacity}`. The **already-present** per-layer/per-track `gainDb`/`muted` (Phase 4, plan §6.5) continue to round-trip — this phase does **not** add them or bump the schema for them. Round-trip MUST be lossless (save→load→deep-equal). Keep media referenced by path (R13).
   - `UndoStack`: add undoable commands `AddLayerCommand`, `RemoveLayerCommand`, `ReorderLayerCommand`, `AddAudioTrackCommand`, `SetTrackGainCommand`, `SetTransformCommand` (following Phase 4's command pattern).
   - (satisfies R10, R13, R14)

3. **Shared Compositor (deterministic reference) + exact offline compositing.**
   Files: `src/engine/render/Compositor.{h,cpp}` (new, small), `src/engine/render/OfflineRenderer.{h,cpp}`.
   - **Shared blend (per plan §6.3/§6.5):** `Compositor` (`src/engine/render/Compositor`, now listed in §6.3) is the **ONE** bottom→top src-over blend used by **both** the best-effort GPU preview (`VideoView`, Task 4) and the exact CPU `OfflineRenderer` — and **Phase 8's full `.mp4` render MUST reuse this same `Compositor`**, never re-implementing blending (plan §6.5).
   - `Compositor::composite(outRGBA, const std::vector<LayerFrame>& bottomToTop)` fills an **opaque black** canvas (base background — this is also the placeholder for audio-only / no-video positions, R21) then, for each visible layer's active clip frame in bottom→top order, applies `crop → scale → position` then **src-over** blend with layer opacity folded into source alpha: `out = src.rgb·(src.a·opacity) + out.rgb·(1 − src.a·opacity)`. A `LayerFrame` carries the decoded source frame (RGBA) + its `Transform`. This CPU implementation is the **exactness reference** (deterministic, NFR-R).
   - `OfflineRenderer`: for each output frame time `t`, ask `VideoScrubber`/`FrameCache` for each visible layer's active clip source frame at `t`, build `LayerFrame`s, and call `Compositor::composite` → the exact composited output frame (handed to `Encoder` in Phase 8). Deterministic; no real-time drops.
   - **Phase 7 forward note**: Phase 7 will insert a per-clip `RtVideoEffectChain::process(frame)` pass on each layer's **active-clip frame BEFORE** `Compositor::composite` (per plan §6.5 — video FX apply to each clip's source frame before compositing). Structure this video pass so that hook fits: process each `LayerFrame`'s clip frame first, then composite. Phase 5 adds no video FX; keep the composite call as-is.
   - (satisfies R10, R17, R21)

4. **GPU preview compositing (best-effort) mirroring the Compositor.**
   File: `src/ui/VideoView.{h,cpp}`.
   - Extend the Phase-1 texture-backed frame path: upload each visible layer's active-clip frame as a texture and draw each as a textured quad **bottom→top**, applying `Transform` as a model transform (crop = source UVs, scale/position = quad geometry) with constant per-layer alpha = `opacity`, blended src-over (`GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA`) over a black clear. Blend math and layer order MUST match `Compositor` so preview ≈ render.
   - **Best-effort (R17)**: under load it MAY drop to fewer layers / lower preview resolution / skip a frame, but MUST never block or starve the audio thread. The offline path (Task 3) stays exact.
   - (satisfies R10, R17)

5. **Multi-voice playback scheduler — one `ScrubEngine` voice per concurrently-active clip.**
   Files: generalise Phase 4's timeline playback scheduler (active-clip lookup via `src/engine/model/SequenceMap.{h,cpp}`, per plan §6.3); wiring in `src/engine/audio/AudioDevice` callback and `src/engine/render/OfflineRenderer.{h,cpp}`, driving multiple `ScrubEngine` voices + per-visible-layer `VideoScrubber` lookups.
   - Phase 4 delivers **SINGLE-voice** sequence playback — **one active clip at a time** (per plan §9). This task **ADDS the simultaneous multi-voice scheduler**: at each transport time, query every `VideoLayer` and `AudioTrack` via `SequenceMap` for its active clip (`[timelineStart, timelineEnd())`, using `Clip::timelineDuration()` per plan §6.5) and **instantiate/schedule one `ScrubEngine` voice per concurrently-active clip across all layers and tracks**, plus one `VideoScrubber` frame lookup per visible layer.
   - Hand the per-visible-layer frames to the `Compositor` (Task 3/4) and the per-voice PCM blocks to the `Mixer` (Task 6) — so a **live composite of multiple layers** and **multiple tracks playing at once** are delivered by **this** phase (exit criteria 4, 5, 7), not assumed from Phase 4.
   - **RT-safe (NFR-A)**: voices come from a **preallocated pool** (bounded max simultaneous voices); activating/deactivating a voice as clips enter/leave the playhead adds no locks/allocations/I-O in the audio callback.
   - (satisfies R10, R17, NFR-A)

6. **Audio Mixer — sum per-track/-layer voices with gain/mute (RT-safe + offline).**
   File: `src/engine/audio/Mixer.{h,cpp}`, wired into `AudioDevice`'s callback and `OfflineRenderer`'s audio pass.
   - Each active audio-producing clip (audio-track clips **and** video-layer clips that carry audio) is rendered to a **project-rate PCM block** (plan §6.5) by its own `ScrubEngine` voice (instantiated by the Task-5 scheduler). `Mixer::mix(out, voices)` groups each voice under its **owning track or layer** and sums each group applying linear gain = `dbToLinear(gainDb)` and `muted` skip — an **audio-track clip** uses its `AudioTrack.gainDb`/`muted`; a **video-layer clip's audio voice** uses its `VideoLayer.gainDb`/`muted` (default **0 dB, not muted**, per plan §6.5) — then sums all groups into the master, all in **float** at the **project rate**, clamping only at the final output. Voices and `Mixer::mix` are **entirely project-rate internally** — there is **no** device-rate PCM in the mix.
   - **Preview = RT-safe (NFR-A)**: no locks/allocations/I-O in the callback — preallocate per-track scratch buffers to max block size at prepare time; per-track `gain`/`mute` are read via atomics updated from the UI (lock-free hand-off). The project-rate master is resampled → device rate **only** at the live-preview output boundary (the `AudioDevice` callback), **never** inside `Mixer::mix` (plan §6.5).
   - **Offline = exact**: `OfflineRenderer` calls the *same* `Mixer::mix` block-by-block for a deterministic master track (to `Encoder` in Phase 8) — **device-independent, at the project rate, with no device-rate resample** (S5/NFR-R: the offline mix has no device rate). One code path → preview and render agree (R17).
   - (satisfies R10, R17, NFR-A)

7. **Layer/track UI + transform inspector.**
   Files: `src/ui/LayerHeader.{h,cpp}`, `src/ui/TransformInspector.{h,cpp}` (new, small), integration in `src/ui/TimelineView.{h,cpp}` / `src/ui/ClipItem.{h,cpp}`.
   - `LayerHeader`: the timeline's left header column — one row per `VideoLayer` and per `AudioTrack`, top→bottom mirroring z-order (top row = topmost layer). Row controls: **add** / **remove** layer|track, **reorder** (up/down buttons or drag), **show/hide** toggle (`VideoLayer.visible`), and **mute** + **gain** slider on **both** `AudioTrack` rows and `VideoLayer` rows (`VideoLayer.gainDb`/`muted`, per plan §6.5, routing the layer's video-clip audio). Every mutation goes through the Task-2 undoable commands and refreshes preview.
   - `TransformInspector`: bound to the selected `ClipItem`; numeric editors for `position`, `scale`, `crop`, `opacity` writing via `SetTransformCommand` with **live** preview update. Direct-manipulation drag of the PiP in `VideoView` is SHOULD/MAY (deferred).
   - (satisfies R10, R14)

8. **Proxy: manual "make proxy" intra-frame transcode + scrub wiring.**
   Files: `src/engine/decode/Proxy.{h,cpp}`, wiring in `src/engine/decode/MediaDecoder.{h,cpp}` / `VideoDecoder`+`FrameCache`, action in `src/ui/LayerHeader`/`ClipItem` context menu, association persisted via `ProjectIO`.
   - `Proxy::make(mediaPath) → proxyPath`: a **worker-thread** libav transcode (decode packets → `libswscale` downscale → encode) to an **intra-frame** proxy — `prores_ks` (ProRes Proxy) or `dnxhd` with `-profile:v dnxhr_lb` (DNxHR LB) in a `.mov`, keeping **identical fps and frame count** so playhead→frame maps 1:1. `prores_ks`/`dnxhd` are FFmpeg-native LGPL encoders — no `--enable-gpl` needed (R16). Never on the audio/UI thread (NFR-A).
   - Wiring: store `Media.proxyPath`; when present, **preview** scrubbing (`FrameCache`/`VideoDecoder`) reads frames from the proxy (cheap random access — every frame a keyframe); the **offline render uses the original** (glossary: proxies are preview-only). OQ5: manual action now; auto-suggest-above-threshold deferred.
   - Intra-frame proxies scrub far cheaper than long-GOP because every frame is a keyframe (no GOP re-decode). See frame.io proxy guide — <https://workflow.frame.io/guide/proxy-codecs>.
   - (satisfies R20)

## Deliverables
- `Project`/`VideoLayer`/`AudioTrack`/`Clip` extended for N ordered video layers, N audio tracks, and per-clip `Transform`; `ProjectIO` schema bumped with lossless round-trip; undo commands for all new mutations.
- `src/engine/render/Compositor.{h,cpp}` (the shared bottom→top src-over reference, per plan §6.3/§6.5) driving `OfflineRenderer`'s exact composite and mirrored by `VideoView`'s GPU preview — **Phase 8's full render reuses this same Compositor**.
- A multi-voice playback scheduler instantiating one `ScrubEngine` voice per concurrently-active clip across all layers/tracks (generalising Phase 4's single-voice path), driving the `Compositor` and `Mixer`.
- `src/engine/audio/Mixer.{h,cpp}` summing per-track/-layer voices with gain/mute (audio-track clips via `AudioTrack.gainDb`/`muted`, video-layer clip audio via `VideoLayer.gainDb`/`muted`), RT-safe in preview and exact offline.
- `src/ui/LayerHeader.{h,cpp}` (add/remove/reorder/show-hide/mute/gain) + `src/ui/TransformInspector.{h,cpp}` (per-clip transform editing).
- `src/engine/decode/Proxy.{h,cpp}` + a working manual "make proxy" action and proxy-backed preview scrub.

## Exit / acceptance criteria (pass/fail)
1. **Build/quality gate**: `cmake --build` succeeds on Linux with all new files; no new warnings in `-Wall` for the added translation units.
2. **Model + persistence**: a project with **≥2 video layers** and **≥2 audio tracks** saves and reloads with layer order, per-layer `visible`/`gainDb`/`muted`, per-track `gainDb`/`muted`, and per-clip `transform` all preserved (`ProjectIO` save→load→deep-equal test passes).
3. **Compositor exactness** (unit/frame-dump): base clip + overlay on a higher layer with `opacity=0.5`, `scale=0.4`, `position` top-right → `Compositor::composite` output frame has the overlay region as a measurable ~50/50 blend of overlay/base pixels and untouched base elsewhere; reordering the two layers swaps which occludes.
4. **Preview composite** (demo): in the running app, place that overlay clip on a layer above the base and confirm `VideoView` shows a scaled, top-right, half-transparent **PiP** over the base; toggling the layer's show/hide adds/removes it live; editing `opacity` in the inspector updates the preview live; all edits undo/redo.
5. **Audio mix** (demo + unit): two audio tracks each with a clip over the same span play simultaneously and are **audibly mixed** (proving the Task-5 multi-voice scheduler runs both voices at once); a unit test sums two known signals and checks output = per-track-gain-weighted sum; muting one track silences only that track; muting a **video layer** silences that layer's video-clip audio (routed via `VideoLayer.gainDb`/`muted`) while its picture still composites; the audio callback shows no locks/allocs (NFR-A stress harness clean).
6. **Proxy** (demo): "make proxy" on a long-GOP H.264 clip produces a `.mov` DNxHR/ProRes proxy of equal fps/frame-count; subsequent preview scrubbing of that clip reads from the proxy with measurably lower backward-step latency; the render path still opens the original file.
7. **End-to-end**: composite a **PiP overlay** (scaled, positioned, reduced opacity) over a base clip on a higher layer and confirm it shows correctly in **preview** — the exact **render** of the same composite is verified in Phase 8.

## Independently shippable?
**No** (final `.mp4` encode of the composite lands in Phase 8). But it is a large user-visible increment: the user can now stack multiple video layers, place overlays / picture-in-picture with position/scale/crop/opacity, reorder and show/hide layers, mix multiple audio tracks with per-track gain/mute, and generate scrub proxies for heavy footage — the full **layered compositor** is now interactive in preview.
