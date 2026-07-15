#include "engine/audio/SignalsmithStretcher.h"

#include <algorithm>
#include <cmath>
#include <vector>

#include <signalsmith-stretch.h>

namespace as {

namespace {
constexpr double kMaxAbsSpeed = 32.0;   // bounds preallocated input capacity
}

struct SignalsmithStretcher::Impl {
    signalsmith::stretch::SignalsmithStretch<float> stretch;
    std::vector<float> inPlanar;        // channels rows * inCap frames
    std::vector<float> outPlanar;       // channels rows * maxOut frames
    std::vector<const float*> inPtrs;
    std::vector<float*> outPtrs;
    int inCap = 0;
};

SignalsmithStretcher::SignalsmithStretcher() : d_(std::make_unique<Impl>()) {}
SignalsmithStretcher::~SignalsmithStretcher() = default;

void SignalsmithStretcher::prepare(int sampleRate, int channels, int maxOutputFrames) {
    sampleRate_ = sampleRate;
    channels_ = channels;
    maxOut_ = maxOutputFrames;

    d_->stretch.presetDefault(channels, static_cast<float>(sampleRate));
    maxInput_ = d_->stretch.blockSamples() + d_->stretch.intervalSamples();

    d_->inCap = static_cast<int>(std::ceil(maxOutputFrames * kMaxAbsSpeed)) + maxInput_ + 16;
    d_->inPlanar.assign(static_cast<std::size_t>(d_->inCap) * channels, 0.0f);
    d_->outPlanar.assign(static_cast<std::size_t>(maxOutputFrames) * channels, 0.0f);
    d_->inPtrs.resize(channels);
    d_->outPtrs.resize(channels);
    for (int c = 0; c < channels; ++c) {
        d_->inPtrs[c] = &d_->inPlanar[static_cast<std::size_t>(c) * d_->inCap];
        d_->outPtrs[c] = &d_->outPlanar[static_cast<std::size_t>(c) * maxOutputFrames];
    }
    needReset_ = true;
    readPos_ = 0.0;
}

int SignalsmithStretcher::process(const StretchRequest& r, float* out, int outFrames,
                                  SampleSource& src) {
    Impl& d = *d_;
    const int ch = channels_;

    if (r.reset || needReset_) {
        d.stretch.reset();
        readPos_ = r.positionFrames;
        needReset_ = false;
    }
    if (r.pitch != lastPitch_) {
        d.stretch.setTransposeFactor(static_cast<float>(r.pitch));
        lastPitch_ = r.pitch;
    }

    const double absSpeed = std::min(std::fabs(r.speed), kMaxAbsSpeed);
    int inputCount = std::max(1, static_cast<int>(std::llround(absSpeed * outFrames)));
    inputCount = std::min(inputCount, d.inCap);
    const bool reverse = r.speed < 0.0;

    // Fill planar input, sourced from [readPos_ .. readPos_ + inputCount) forward,
    // or reversed for reverse playback.
    float* rowBase = d.inPlanar.data();
    const std::intptr_t stride = d.inCap;
    if (!reverse) {
        src.readPlanar(static_cast<frame_t>(std::llround(readPos_)), inputCount, rowBase, stride);
    } else {
        const frame_t start = static_cast<frame_t>(std::llround(readPos_)) - inputCount + 1;
        src.readPlanar(start, inputCount, rowBase, stride);
        for (int c = 0; c < ch; ++c)
            std::reverse(rowBase + c * stride, rowBase + c * stride + inputCount);
    }

    d.stretch.process(d.inPtrs.data(), inputCount, d.outPtrs.data(), outFrames);

    // Interleave planar output.
    for (int f = 0; f < outFrames; ++f)
        for (int c = 0; c < ch; ++c)
            out[f * ch + c] = d.outPtrs[c][f];

    readPos_ += r.speed * outFrames;
    return outFrames;
}

} // namespace as
