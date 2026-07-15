#pragma once
// Turntable / varispeed IStretcher (plan §6.4/§6.5, R18, Phase 2 Task 6).
//
// The "vinyl" path: instead of holding pitch constant, it reads the source with
// linear interpolation at a variable rate EQUAL to the per-block playback speed,
// so pitch bends with drag speed (slow = low, fast = high), including reverse
// (negative step = backward read). It takes the SAME per-block absolute-position
// input as BungeeStretcher, so both consume the identical playhead/automation
// stream and the ScrubEngine can switch between them seamlessly. req.pitch (the
// pitch-preserving control) is inert here. All buffers are preallocated in
// prepare(); process() never allocates, locks, or does I/O (NFR-A).

#include <vector>

#include "engine/audio/IStretcher.h"
#include "engine/model/Project.h"

namespace as {

class VarispeedStretcher final : public IStretcher {
public:
    VarispeedStretcher() = default;
    ~VarispeedStretcher() override = default;

    void prepare(int sampleRate, int channels, int maxOutputFrames) override;
    int  maxInputFrameCount() const override { return inCap_; }
    int  process(const StretchRequest& req, float* out, int outFrames,
                 SampleSource& src) override;
    bool isFlushed() const override { return true; }
    void reset() override { needReset_ = true; }

private:
    int sampleRate_ = kProjectSampleRate;
    int channels_ = 2;
    int maxOut_ = 0;
    int inCap_ = 0;              // planar span capacity (frames)
    bool needReset_ = true;

    std::vector<float> planar_;  // channel c at planar_[c*inCap_ + i]
};

} // namespace as
