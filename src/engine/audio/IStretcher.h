#pragma once
// The stretcher seam (plan §6.3/§6.4, Phase 1 Task 2).
//
// Abstracts the time/pitch stretcher so the engine is swappable between
// BungeeStretcher (default, position-based, native reverse/zero-speed) and
// SignalsmithStretcher (fallback). All implementations are RT-friendly: after
// prepare(), process() performs NO allocation, locking, or I/O.

#include <cstdint>

namespace as {

// Random-access PCM source handed to the stretcher (Phase 1 Task 2/3).
// Implemented over the in-RAM DecodedAudio; reads are pure index math — no
// allocation, no locking — safe to call from the RT audio thread.
struct SampleSource {
    virtual ~SampleSource() = default;
    virtual int channels() const = 0;
    virtual std::int64_t frameCount() const = 0;
    // Planar copy: for i in [0,n), channel c value goes to dst[c*stride + i].
    // Frames outside [0, frameCount()) are written as 0 (mute).
    virtual void readPlanar(std::int64_t startFrame, int n,
                            float* dst, std::intptr_t stride) const = 0;
};

// One stretch request per render block. Units per §6.5:
//   positionFrames — absolute SOURCE position, project-rate frames (double for sub-frame)
//   speed          — playback ratio; negative = reverse, 0 = hold/freeze
//   pitch          — frequency multiplier; 1.0 = source pitch (Phase 1 fixes this at 1.0)
//   reset          — snap to positionFrames and preroll (on load / hard seek)
struct StretchRequest {
    double positionFrames = 0.0;
    double speed = 1.0;
    double pitch = 1.0;
    bool   reset = false;
};

class IStretcher {
public:
    virtual ~IStretcher() = default;

    // Preallocate all internal buffers. Called off the RT thread.
    virtual void prepare(int sampleRate, int channels, int maxOutputFrames) = 0;

    // Largest input-frame span a single grain may request (buffer sizing).
    virtual int  maxInputFrameCount() const = 0;

    // Produce up to outFrames interleaved-f32 frames for `req`, pulling source
    // PCM through `src`. Returns the number of frames written. RT-safe.
    virtual int  process(const StretchRequest& req, float* out, int outFrames,
                         SampleSource& src) = 0;

    // True once every pending grain has drained.
    virtual bool isFlushed() const = 0;

    // Drop internal history (forces preroll on the next block).
    virtual void reset() = 0;
};

} // namespace as
