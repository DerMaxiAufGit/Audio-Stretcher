# Audio-Stretcher

A native **Windows + Linux** desktop app: a real-time, **pitch-preserving audio/video
"scratch" instrument** — drag the playhead back and forth and hear the audio play as you
move, like the mobile app *AudioStretch* (LiveScrub) — built into a **layered mini video
editor** for making YouTube-Poop-style content and exporting it to `.mp4`.

> **Status: Phase 1 (walking skeleton) implemented.** You can load an audio/video clip
> and scratch it — pitch-preserving, bidirectional, with synced video — on an
> always-playing transport. The full phased plan lives in
> [`docs/plans/audio-scratch-editor/`](docs/plans/audio-scratch-editor/plan.md);
> Phases 2–8 (manual pitch/speed, loop/markers, record→mp4, timeline, layers,
> effects, render, packaging) are not built yet.

## What it does

- **Scrub / scratch** any audio or video file: drag the playhead forward/backward with
  the pointer, audio plays time-stretched with **constant pitch** (reverse & near-zero
  included). The deck keeps playing until you hit Pause.
- **Turntable mode** toggle: pitch bends with speed like real vinyl (varispeed).
- **Manual pitch** (±36 semitones) and speed, **A/B loop**, and **markers**.
- **Record** a scratch performance and export it as an `.mp4`.
- **Layered timeline editor**: stacked video layers (overlays / picture-in-picture),
  audio tracks, cut / trim / reorder.
- **Audio effects rack**: delay, reverb, distortion, chorus, stutter, bitcrusher, phaser.
- **Video effects**: colour, chroma-key (green screen), glitch.
- **Render** the whole edit to `.mp4`.

## Tech stack

C++ / **Qt 6** · **FFmpeg** (decode + encode) · **miniaudio** (real-time audio) ·
**Bungee** (pitch-preserving, position-based scrub). All dependencies are
permissive / LGPL / MPL — no GPL, no license fees.

## Building (Linux)

Prerequisites: a C++20 toolchain, CMake ≥ 3.21, **Qt 6** (Widgets/Gui/OpenGL/Concurrent),
and **FFmpeg** dev libraries (`libavformat libavcodec libavutil libswresample libswscale`).
Bungee, pffft, Eigen (core), Signalsmith, and miniaudio are vendored under `third_party/`.

```sh
cmake -S . -B build -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build build -j
./build/audioscratch [optional-media-file]   # GUI: File ▸ Open, then drag on the waveform/video
./build/audioscratch --selftest               # headless: decode + click-free scrub + zero-alloc checks
```

Wheel = zoom the waveform, Shift+wheel = pan; drag on the waveform or the picture to scratch;
Play/Pause is the only thing that stops the deck.

> **Licensing note (R16):** the app links FFmpeg dynamically. The system FFmpeg on many
> distros is a **GPL** build (`--enable-gpl`) — fine for local development (Phase 1 only
> *decodes*), but the **packaged** build (Phase 8) must link an FFmpeg built *without*
> `--enable-gpl`/`--enable-nonfree` to keep the permissive-only guarantee. Windows is a
> build target; a full Windows run is gated in Phase 8.

## License

To be decided (the dependency choices — Qt LGPLv3, FFmpeg LGPL, Bungee MPL-2.0, miniaudio
public domain, Signalsmith MIT — keep both open- and closed-source options open).
