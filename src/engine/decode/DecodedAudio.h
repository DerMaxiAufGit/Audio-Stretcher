#pragma once
// In-RAM decoded PCM holder (plan §6.3/§6.5, Phase 1 Task 3).
//
// Interleaved float32 at the PROJECT rate (default 48000 Hz), with
// sample-accurate random access. Produced by AudioDecoder. Once decode is
// complete the buffer is immutable, so read()/readPlanar() are safe to call
// from the RT audio thread (pure index math, no locks/alloc).

#include <cstdint>
#include <vector>
#include "engine/model/Project.h"

namespace as {

class DecodedAudio {
public:
    DecodedAudio() = default;
    DecodedAudio(std::vector<float> interleaved, int channels, int sampleRate);

    int      channels()   const { return channels_; }
    int      sampleRate() const { return sampleRate_; }   // == project rate (§6.5)
    frame_t  frameCount() const { return frameCount_; }
    bool     empty()      const { return frameCount_ == 0; }

    const float* data() const { return samples_.data(); }

    // Copy n frames from `frame` into interleaved `out` (channels() floats per
    // frame). Frames outside [0, frameCount()) are zero-filled.
    void read(frame_t frame, float* out, int n) const;

    // Planar copy for stretcher analysis: for i in [0,n), channel c goes to
    // dst[c*stride + i]. Out-of-range frames are written as 0.
    void readPlanar(frame_t startFrame, int n, float* dst, std::intptr_t stride) const;

private:
    std::vector<float> samples_;   // interleaved, size = frameCount_ * channels_
    int      channels_   = 2;
    int      sampleRate_ = kProjectSampleRate;
    frame_t  frameCount_ = 0;
};

} // namespace as
