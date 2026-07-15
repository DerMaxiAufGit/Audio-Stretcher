#pragma once
// Full-decode audio -> in-RAM PCM (plan §6.3/§6.5, Phase 1 Task 3).
//
// Decodes the whole audio stream and resamples (swresample) to interleaved f32,
// STEREO, at the PROJECT rate (default 48000 Hz) — not the device rate. The
// result is a DecodedAudio with sample-accurate random access. Documented
// footprint ceiling (OQ6): full-decode to RAM up to ~30 min stereo.

#include "engine/decode/DecodedAudio.h"
#include "engine/model/Project.h"

namespace as {

class MediaDecoder;

class AudioDecoder {
public:
    // Decode `media`'s audio stream. Returns an empty DecodedAudio on failure or
    // if the media has no audio stream (R21: audio-only and video-only both OK).
    static DecodedAudio decode(MediaDecoder& media, int projectRate = kProjectSampleRate);
};

} // namespace as
