#pragma once
// Bungee-backed IStretcher (default engine, plan §6.4, Phase 1 Task 2).
//
// Wraps Bungee's position-based random-access grain API. Because each grain
// carries an absolute input position and a per-block speed, reverse and
// zero/near-zero speed are native. All working buffers are preallocated in
// prepare(); process() never allocates, locks, or does I/O (NFR-A).

#include <memory>
#include <vector>

#include "engine/audio/IStretcher.h"
#include "engine/model/Project.h"

namespace Bungee { template <class> struct Stretcher; struct Basic; struct Request; }

namespace as {

class BungeeStretcher final : public IStretcher {
public:
    BungeeStretcher();
    ~BungeeStretcher() override;

    void prepare(int sampleRate, int channels, int maxOutputFrames) override;
    int  maxInputFrameCount() const override { return maxInput_; }
    int  process(const StretchRequest& req, float* out, int outFrames,
                 SampleSource& src) override;
    bool isFlushed() const override;
    void reset() override { prerolled_ = false; fifoCount_ = fifoHead_ = fifoTail_ = 0; }

private:
    void pushGrain(const float* planar, int frames, std::intptr_t channelStride);
    int  popInterleaved(float* out, int frames);

    int sampleRate_ = kProjectSampleRate;
    int channels_ = 2;
    int maxOut_ = 0;
    int maxInput_ = 0;
    bool prerolled_ = false;

    std::unique_ptr<Bungee::Stretcher<Bungee::Basic>> stretcher_;
    std::unique_ptr<Bungee::Request> req_;

    std::vector<float> analysis_;  // planar: channel c at analysis_[c*maxInput_ + i]
    std::vector<float> fifo_;      // interleaved ring, capacity fifoCap_ frames
    int fifoCap_ = 0;
    int fifoHead_ = 0;             // frame index (read)
    int fifoTail_ = 0;             // frame index (write)
    int fifoCount_ = 0;            // frames currently buffered
};

} // namespace as
