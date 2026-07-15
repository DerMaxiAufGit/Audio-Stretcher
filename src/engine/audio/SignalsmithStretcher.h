#pragma once
// Signalsmith-backed IStretcher (FALLBACK engine, plan §6.4, Phase 1 Task 2).
//
// Signalsmith Stretch (MIT) is a forward-only push/pull stretcher. We drive it
// for scrub by feeding round(|speed|*outFrames) input frames per outFrames of
// output (ratio => playback speed) and, for reverse, by feeding the source in
// reversed order. This is the swap-in path if a Bungee build is ever a blocker
// (e.g. on Windows); on Linux, Bungee is the default and this path is unexercised.
// All buffers are preallocated in prepare(); process() does not allocate.

#include <memory>

#include "engine/audio/IStretcher.h"
#include "engine/model/Project.h"

namespace as {

class SignalsmithStretcher final : public IStretcher {
public:
    SignalsmithStretcher();
    ~SignalsmithStretcher() override;

    void prepare(int sampleRate, int channels, int maxOutputFrames) override;
    int  maxInputFrameCount() const override { return maxInput_; }
    int  process(const StretchRequest& req, float* out, int outFrames,
                 SampleSource& src) override;
    bool isFlushed() const override { return true; }
    void reset() override { needReset_ = true; }

private:
    struct Impl;                      // holds the SignalsmithStretch<float> + buffers
    std::unique_ptr<Impl> d_;

    int sampleRate_ = kProjectSampleRate;
    int channels_ = 2;
    int maxOut_ = 0;
    int maxInput_ = 0;
    bool needReset_ = true;
    double readPos_ = 0.0;            // project-rate source frames
    double lastPitch_ = 1.0;
};

} // namespace as
