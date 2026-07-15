# Audio-Stretcher

A native **Windows + Linux** desktop app: a real-time, **pitch-preserving audio/video
"scratch" instrument** — drag the playhead back and forth and hear the audio play as you
move, like the mobile app *AudioStretch* (LiveScrub) — built into a **layered mini video
editor** for making YouTube-Poop-style content and exporting it to `.mp4`.

> **Status: planning.** No application code yet — the full, phased implementation plan
> lives in [`docs/plans/audio-scratch-editor/`](docs/plans/audio-scratch-editor/plan.md).

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

## Building

Not yet implemented. The build system (CMake) and per-phase instructions are defined in
the plan; see [`docs/plans/audio-scratch-editor/plan.md`](docs/plans/audio-scratch-editor/plan.md)
and the `phase-*.md` docs.

## License

To be decided (the dependency choices keep both open- and closed-source options open).
