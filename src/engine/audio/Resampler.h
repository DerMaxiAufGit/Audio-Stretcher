#pragma once
// Project-rate -> device-rate output conversion (plan §6.3/§6.5, Phase 1 Task 5).
//
// The engine works in project-rate frames; the audio device may run at a
// different rate. This converts the engine's interleaved-f32 output to the
// device rate at the output boundary. In Phase 1 the device is opened at the
// project rate, so this is a passthrough (memcpy) — but the seam exists per the
// contract. Stateful linear interpolation for the non-passthrough case. RT-safe.

#include <vector>

namespace as {

class Resampler {
public:
    void prepare(int channels, int inRate, int outRate);
    bool passthrough() const { return inRate_ == outRate_; }

    // Number of input (project-rate) frames needed to emit `outFrames` output.
    int inputFramesFor(int outFrames) const;

    // Convert `inFrames` interleaved input frames -> interleaved output written
    // to `out` (capacity outCapacity frames). Returns output frames produced.
    int process(const float* in, int inFrames, float* out, int outCapacity);

    int channels() const { return channels_; }

private:
    int channels_ = 2;
    int inRate_ = 48000;
    int outRate_ = 48000;
    double ratio_ = 1.0;   // input frames advanced per output frame
    double pos_ = 0.0;     // fractional read position within the current input block
};

} // namespace as
