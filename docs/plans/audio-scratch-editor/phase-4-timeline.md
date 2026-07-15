# Phase 4 — Timeline foundation (project model, single-track editing, save/load, undo)

- **Goal**: Import multiple clips onto one video track + one audio track, edit them
  (cut/trim/split/delete/move/reorder), scrub across clip boundaries with the Phase-1
  ScrubEngine, and save/reload the whole project — with undo/redo on edits.

- **Scope**
  - **In**: a persistent non-destructive project data model (`Project → VideoLayer /
    AudioTrack → Clip → Media`); a `MediaRegistry` (dedup source media by path,
    holding decoded-audio + video-decoder handles); a `SequenceMap` that maps a
    timeline-time to the active clip + absolute source position and drives the
    **same** `ScrubEngine`/`VideoScrubber` from Phase 1; a `QGraphicsScene`-based
    `TimelineView`/`ClipItem` with move/trim/split/delete/reorder; JSON `.asproj`
    save/load (media referenced **by path**); a `QUndoStack`-based command system for
    edits. Recorded takes from Phase 3 become `Clip`s. Audio-only inputs are
    first-class (R21).
  - **Explicitly deferred**: **multiple stacked video layers, compositing,
    transforms (pos/scale/crop/opacity) and multi-track audio mixing → Phase 5**
    (Phase 4 has exactly **one** `VideoLayer` + **one** `AudioTrack`, monitored one
    at a time; no cross-track mix). **Effect chains → Phase 6** (the `effectChain`
    field exists but is always empty and is (de)serialised as an empty array).
    **Video effect chains → Phase 7** (the `videoEffectChain` field exists on
    video-layer clips but is always empty and (de)serialised as an empty array, per
    plan §6.5/R19). **Time remapping / clip speed-ramp UI → not in scope** (a clip's
    timeline length is
    either `srcOut-srcIn` for a plain import, or the recorded automation duration for
    a take). **Whole-timeline `.mp4` render → Phase 8.**

- **Depends on**
  - **Phase 1**: `ScrubEngine` (consumes an absolute source-frame position via its
    lock-free control block), `VideoScrubber` (playhead→frame), `AudioDecoder`
    (full-decode to a `DecodedAudio` handle — interleaved f32 PCM resampled to the
    project sample rate, per plan §6.5), `VideoDecoder`, and the transport
    (always-playing forward + grab-to-scratch).
  - **Phase 2**: pitch/speed controls, loop/markers (unchanged; operate on the
    monitored clip's source).
  - **Phase 3**: the `Automation` type (envelopes `playhead`/`speed`/`pitch`, with
    `playheadPosAt(tSeconds)`, `durationSeconds()`, `durationFrames()` and
    `slice(t0, t1)` — all per plan §6.5) and the record path that produces a
    **`Take` `{ MediaId mediaId; Automation automation; PlaybackMode playbackMode; }`**
    (per plan §6.5); Phase 4 turns a take into a `Clip`.
  - External: Qt 6 (`QGraphicsView`/`QGraphicsScene`, `QJsonDocument`, `QUndoStack` —
    all LGPLv3, <https://doc.qt.io/qt-6/licensing.html>).

---

## Conventions (fix these before coding)

- **Time units** (per plan §6.5). `frame_t = int64_t`, sample frames at
  `Project::sampleRate` (the one canonical audio rate, default **48000**). All
  timeline positions and all clip src/timeline positions are in **project-rate
  frames**. `AudioDecoder` (Phase 1) already resamples **every source to
  `Project::sampleRate`** (not the device rate), so **source frames and timeline
  frames share one unit** and the timeline→source mapping is 1:1 with no rate
  conversion in this phase. Video display time = `double(frame) / sampleRate` seconds →
  `VideoScrubber`.
- **IDs.** `MediaId = uint64_t`, `ClipId = uint64_t` — allocated by monotonic
  counters owned by `Project` (persisted so reload keeps identity stable) — and
  `LayerId = uint64_t` for `VideoLayer` / `TrackId = uint64_t` for `AudioTrack`
  (Phase 4 has a single layer and track; the counters matter in Phase 5).

---

## Tasks

### 1. Core data model + media registry — `src/engine/model/`
- **Files**: `Media.h`/`Media.cpp` (incl. `MediaRegistry`), `Clip.h`,
  `AudioTrack.h`, `VideoLayer.h`, `Project.h`/`Project.cpp`.
- **`Media`** `{ MediaId id; QString path; bool missing=false;
  std::shared_ptr<DecodedAudio> audio; std::shared_ptr<VideoDecoder> video; //
  video==nullptr for audio-only (R21) frame_t durationFrames; }`. Holds the *handles*
  from Phase 1 decoders — a `DecodedAudio` (produced by `AudioDecoder`) + a
  video-decoder handle, per plan §6.5 — the registry owns them so many clips can
  share one decode.
- **`MediaRegistry`** `{ MediaId add(const QString& path); Media* get(MediaId);
  const std::vector<Media>& all() const; }`. `add` canonicalises the path
  (`QFileInfo::canonicalFilePath`) and **dedups**: importing the same file twice
  returns the existing `MediaId` and decodes once.
- **`Clip`** `{ ClipId id; MediaId sourceMediaId; frame_t srcIn, srcOut,
  timelineStart; std::optional<Automation> automation; PlaybackMode playbackMode =
  PlaybackMode::PitchPreserving; // R18, per clip (PlaybackMode defined Phase 2, per
  plan §6.2/§6.5) EffectChain effectChain; // empty in P4 (Phase 6) VideoEffectChain
  videoEffectChain; // video-layer clips only; empty here, reserved for Phase 7 (R19)
  Transform transform; // identity in P4 (Phase 5) }`. Methods:
  `frame_t timelineDuration() const` → `automation ? automation->durationFrames() :
  (srcOut - srcIn)`; `frame_t timelineEnd() const` → `timelineStart +
  timelineDuration()` (both per plan §6.5 — a recorded take's on-timeline length is
  its **automation** duration, not its source span; every scheduler MUST use
  `timelineDuration()`). A **plain import** has `automation == nullopt` (linear 1×
  passthrough); a **take** carries the Phase-3 `Automation`.
- **`makeClipFromTake(const Take&, frame_t timelineStart)`** (free function in
  `Clip.cpp`, per plan §6.5): builds `Clip{ sourceMediaId = take.mediaId,
  srcIn = min(playhead), srcOut = max(playhead), timelineStart,
  automation = take.automation, playbackMode = take.playbackMode }`. The `Take`
  carries `mediaId` + `automation` + `playbackMode` (from Phase 3, per plan §6.5) — it
  does **not** pre-carry `srcIn`/`srcOut`; the used source span
  `[min(playhead), max(playhead))` is computed here from the automation's playhead
  envelope, and the deck's recorded `playbackMode` (R18) carries onto the clip.
  (Satisfies **R21** for audio-only takes too.)
- **`AudioTrack`** `{ TrackId id; QString name; float gainDb = 0; bool muted =
  false; std::vector<Clip> clips; }` (kept timeline-sorted by `timelineStart`;
  `gainDb`/`muted` per plan §6.5). **`VideoLayer`** (`VideoLayer.h`) `{ LayerId id; QString name;
  float gainDb = 0; bool muted = false; std::vector<Clip> clips; }` — carries the
  same `gainDb`/`muted` audio-routing fields as `AudioTrack` (per plan §6.5, so a
  video clip's audio routes through its layer in the `Mixer`), **from this phase**.
  Layers are ordered by **vector index** (index 0 = bottom → top, exactly as
  Phase 5); there is no `z` field. **`Project`** `{ int version=1; int
  sampleRate = 48000; double fps = 30; MediaRegistry media; std::vector<VideoLayer> videoLayers; //
  size()==1 in P4 std::vector<AudioTrack> audioTracks; // size()==1 in P4 uint64_t
  nextMediaId, nextClipId; }` with `Clip& addClip(trackRef, Clip)` /
  `removeClip(ClipId)` / `Clip* findClip(ClipId)` helpers used by commands.
- *(satisfies **R10** [import + model subset], **R21**)*

### 2. Sequence mapping + ScrubEngine driving — `src/engine/model/SequenceMap.{h,cpp}` (per plan §6.3)
- **Pure, UI-free, unit-testable.** `struct ActiveSource { bool valid; MediaId
  mediaId; frame_t sourcePos; ClipId clipId; };`
- **`ActiveSource resolveTrackAt(const std::vector<Clip>& clips, frame_t
  timelinePos, int sampleRate)`** (`sampleRate` = the project's `Project::sampleRate`,
  passed in so the take-clip branch below compiles; stays pure/UI-free): find the
  clip whose `[timelineStart, timelineEnd())` contains
  `timelinePos` (binary search on sorted clips); if none → `{valid=false}` (a **gap**
  = silence / black frame). If found, compute the absolute source frame:
  - plain clip: `sourcePos = srcIn + (timelinePos - timelineStart)`;
  - take clip: `sourcePos = automation->playheadPosAt(double(timelinePos -
    timelineStart) / sampleRate)` (the Phase-3 envelope is keyed in seconds per plan §6.5, so the
    clip-relative timeline offset is converted frames→seconds; reusing it is why
    scrubbing a take on the timeline sounds identical to the recording).
- **Driving the engine (the PHASE FACT).** In the timeline transport tick / on scrub
  drag: call `resolveTrackAt` for the **monitored** track (passing `Project::sampleRate`),
  and if `mediaId` changed
  since last tick, point `ScrubEngine` at that `Media`'s decoded-audio handle and
  `VideoScrubber` at its `VideoDecoder`; then **write `sourcePos` into the
  ScrubEngine's lock-free control block** (the exact hand-off Phase 1 established —
  no new RT path, no locks/allocs, NFR-A preserved). Always-forward play advances the
  timeline playhead and re-resolves each tick, so playback crosses clip boundaries by
  swapping the active source. Frame-accurate video across a boundary still uses the
  Phase-1 seek-to-keyframe→decode-forward path
  (<https://www2.parkcity.co.uk/blog/ffmpeg-grab-exact-frames-from>).
- Phase 4 monitors **one track at a time** (a "monitor/solo" toggle in the track
  header); true multi-track mixing is Phase 5.
- *(satisfies **R10** [scrub-the-sequence subset])*

### 3. Timeline UI — `src/ui/TimelineView.{h,cpp}`, `src/ui/ClipItem.{h,cpp}`
- **`TimelineView : QGraphicsView`** over a `QGraphicsScene`: x-axis = timeline
  frames scaled by a zoom factor (px/frame); rows = the one `VideoLayer` and one
  `AudioTrack`. Holds a movable **playhead** line; emits `playheadMoved(frame_t)` and
  owns a monitor tick that calls the Task-2 driving code. Public API: `void
  setProject(Project*)`, `void refreshFromModel()`, `frame_t playhead() const`.
- **`ClipItem : QGraphicsRectItem`** = one `Clip`. Renders name + a mini waveform
  (peaks from the Phase-1 waveform data). Interactions, each producing **one undo
  command** (Task 5), never mutating the model directly:
  1. **Move** — drag body horizontally → new `timelineStart` (snap to
     playhead/clip edges; clamp ≥ 0; no overlap: push-or-reject) → `MoveClipCommand`.
  2. **Trim** — drag left/right edge handle → left edge changes `srcIn` +
     `timelineStart` together; right edge changes `srcOut`; clamp to
     `[0, media.durationFrames]` and to a 1-frame min → `TrimClipCommand`.
  3. **Split** — action/shortcut at playhead → replace the clip under the playhead
     with two clips meeting at the cut (source + timeline offsets split
     proportionally; automation split via `Automation::slice(t0, t1)` per plan §6.5)
     → `SplitClipCommand`.
  4. **Delete** — Del key → `DeleteClipCommand`.
  5. **Reorder** — dragging a clip past a neighbour reassigns order on the track;
     realised as a `MoveClipCommand` (order derives from `timelineStart`).
- **Import**: `TimelineView` accepts file drops / an "Add clip" action → `Project::
  media.add(path)` (decode via Phase 1) → construct a `Clip` directly (a plain import
  has `automation == nullopt`, `srcIn/srcOut` = the source span; a recorded take uses
  `makeClipFromTake`) → `AddClipCommand` at the drop x (= timeline frame). Audio-only
  files land on the audio track and show a placeholder on the video row (R21).
- Wire into `src/app/MainWindow`: dock the `TimelineView`, add Edit-menu Undo/Redo,
  and File-menu New/Open/Save/Save-As. Update `CMakeLists.txt` with the new sources.
- *(satisfies **R10** [cut/trim/split/delete/move/reorder subset])*

### 4. Project IO — `src/engine/model/ProjectIO.{h,cpp}`
- **API**: `bool save(const Project&, const QString& path, QString* err)`;
  `std::optional<Project> load(const QString& path, LoadReport* report)`. Format:
  **JSON via `QJsonDocument`/`QJsonObject`/`QJsonArray`**, written with `QFile`,
  extension **`.asproj`** (MUST — R13).
- **Schema** (media referenced **by path**, never copied):
  ```json
  { "version": 1, "sampleRate": 48000, "fps": 30,
    "nextMediaId": 3, "nextClipId": 7,
    "media":  [ { "id": 1, "path": "/abs/or/relative/clip.mp4" } ],
    "videoLayers": [ { "id": 1, "name": "V1", "gainDb": 0, "muted": false, "clips": [ {
        "id": 4, "sourceMediaId": 1, "srcIn": 0, "srcOut": 132300,
        "timelineStart": 0,
        "automation": null,                     // or the Phase-3 envelope object
        "playbackMode": "PitchPreserving",       // "PitchPreserving" | "Varispeed" (R18)
        "effectChain": [],                       // always [] in Phase 4 (Phase 6)
        "videoEffectChain": [],                  // always [] in Phase 4 (Phase 7, R19)
        "transform": null                        // always null in Phase 4 (Phase 5)
    } ] } ],
    "audioTracks": [ { "id": 1, "name": "A1", "gainDb": 0, "muted": false, "clips": [ ... ] } ] }
  ```
  Paths stored **relative to the project file** when under its directory, else
  absolute (portable projects). Automation (de)serialised by the Phase-3
  `Automation` schema.
- **Missing media on load** (MUST handle): if a media `path` does not resolve,
  create the `Media` with `missing=true` (null decode handles), keep every referring
  clip, and record it in `LoadReport.missing` so the UI can show a non-blocking
  "relink media" prompt. Load never fails on missing media; `ClipItem` renders a
  hatched "offline" placeholder.
- *(satisfies **R13**)*

### 5. Undo/redo — `src/engine/model/UndoStack.{h,cpp}`, `src/engine/model/TimelineCommands.{h,cpp}` (both per plan §6.3)
- Use **Qt `QUndoStack` + `QUndoCommand`** (LGPL, idiomatic;
  <https://doc.qt.io/qt-6/qundostack.html>). `UndoStack` wraps one `QUndoStack` per
  `Project`, exposes `undoAction()/redoAction()` for the Edit menu, and emits a
  "model changed" signal so `TimelineView::refreshFromModel()` runs after every
  push/undo/redo.
- **Commands** (each stores the minimal before/after state and implements
  `redo()`/`undo()` against `Project`; all edits go through these — nothing mutates
  the model directly):
  - `AddClipCommand` (clip + target track) / `DeleteClipCommand` (snapshots the
    removed clip so undo restores identical fields incl. `ClipId`).
  - `MoveClipCommand` (old/new `timelineStart`; also covers reorder).
  - `TrimClipCommand` (old/new `srcIn, srcOut, timelineStart`).
  - `SplitClipCommand` (undo removes the two halves and restores the original clip;
    redo re-splits deterministically — store the cut frame).
- **Excludes live scratch motion** (recording is Phase 3's automation, not an undoable
  edit) — SHOULD per R14.
- *(satisfies **R14**)*

---

## Deliverables
- `src/engine/model/`: `Media.{h,cpp}`, `Clip.h`+`Clip.cpp`, `AudioTrack.h`,
  `VideoLayer.h`, `Project.{h,cpp}`, `SequenceMap.{h,cpp}`, `ProjectIO.{h,cpp}`,
  `UndoStack.{h,cpp}`, `TimelineCommands.{h,cpp}`.
- `src/ui/`: `TimelineView.{h,cpp}`, `ClipItem.{h,cpp}`; `MainWindow` wired with the
  timeline dock, Edit(Undo/Redo) and File(New/Open/Save/Save-As) menus.
- `CMakeLists.txt` updated; a sample `.asproj` fixture under `tests/fixtures/`.
- `tests/`: `test_sequence_map.cpp` (mapping across boundaries/gaps/takes) and
  `test_project_io.cpp` (save→load round-trip + missing-media), registered with CTest.

## Exit / acceptance criteria (pass/fail)
1. **Build/lint gate**: `cmake --build build` succeeds on Linux with **zero new
   warnings**; `ctest` green.
2. **Model/mapping test** (RUNNABLE): `ctest -R sequence_map` passes — for a track of
   ≥3 clips with a gap, `resolveTrackAt` returns the correct `mediaId`+`sourcePos`
   inside each clip, `{valid=false}` in the gap, and the take-clip branch matches
   `Automation::playheadPosAt`.
3. **IO round-trip test** (RUNNABLE): `ctest -R project_io` passes — `save` then
   `load` yields a `Project` **equal** to the original (all ids, src/timeline
   positions, automation, playbackMode, empty effectChain/videoEffectChain/transform),
   and loading a project whose
   media path was renamed yields `missing=true` + a populated `LoadReport.missing`
   with no crash and all clips retained.
4. **Import & edit demo**: import 2+ media files → clips appear on the video+audio
   track; move, trim (both edges), split at playhead, delete, and reorder each
   visibly update and are individually undoable/redoable via Edit ▸ Undo/Redo.
5. **Scrub-across-boundary demo**: dragging the timeline playhead across a clip
   boundary plays the correct source with constant pitch (ScrubEngine) and shows the
   correct video frame (VideoScrubber) with no audio dropout; a gap plays
   silence/black.
6. **END-TO-END**: build a **3-clip** sequence (mix of imports and a Phase-3 take,
   including one audio-only clip), **Save** to `demo.asproj`, close the app, **reopen**
   it → the timeline is **identical** (same clips, positions, in/out, order,
   playhead-scrub behaviour); then **undo a trim** and confirm the clip returns to its
   pre-trim `srcIn/srcOut/timelineStart`.

## Independently shippable?
**Yes.** On top of the Phase-3 scratch-to-mp4 tool, the user can now **assemble
multiple takes/clips into a sequence on a video+audio track, edit them
non-destructively (move/trim/split/delete/reorder) with full undo/redo, scrub the
whole sequence, and save/reopen the project** — the editor half of the app becomes
usable, even though layering/compositing (Phase 5), effects (Phase 6) and
whole-timeline export (Phase 8) are not yet built.
