#include "engine/audio/VarispeedStretcher.h"

#include <algorithm>
#include <cmath>

namespace as {

namespace {
// Bounds the preallocated span capacity: matches the ScrubEngine scrub-speed clamp
// (± this many source frames advanced per output frame), so a full-speed block
// never needs more input than we reserved.
constexpr double kMaxAbsSpeed = 32.0;
}

void VarispeedStretcher::prepare(int sampleRate, int channels, int maxOutputFrames) {
    sampleRate_ = sampleRate;
    channels_ = channels > 0 ? channels : 1;
    maxOut_ = maxOutputFrames;

    // Worst case: |step| == kMaxAbsSpeed over maxOut_ output frames, plus 2 frames
    // of headroom for the i0/i0+1 interpolation tap and rounding.
    inCap_ = static_cast<int>(std::ceil(maxOutputFrames * kMaxAbsSpeed)) + 4;
    planar_.assign(static_cast<std::size_t>(inCap_) * channels_, 0.0f);
    needReset_ = true;
}

int VarispeedStretcher::process(const StretchRequest& r, float* out, int outFrames,
                                SampleSource& src) {
    const int ch = channels_;
    needReset_ = false;   // no cross-block history to drop; position is absolute

    // Per-output-frame read step in source frames. Freeze (speed 0) reads the same
    // frame repeatedly -> a held (constant, click-free) sample; never divides by it.
    double step = r.speed;
    if (step > kMaxAbsSpeed) step = kMaxAbsSpeed;
    if (step < -kMaxAbsSpeed) step = -kMaxAbsSpeed;

    const double posStart = r.positionFrames;
    const double posEnd = posStart + step * (outFrames - 1);
    const double pMin = std::min(posStart, posEnd);
    const double pMax = std::max(posStart, posEnd);

    // Read the whole span this block touches ONCE into the planar buffer (index
    // math only, RT-safe); interpolate from it below. src zero-fills out-of-range
    // frames, so clip edges mute cleanly.
    frame_t spanStart = static_cast<frame_t>(std::floor(pMin));
    frame_t spanEnd = static_cast<frame_t>(std::floor(pMax)) + 2;   // +1 tap, +1 rounding
    int n = static_cast<int>(spanEnd - spanStart);
    if (n < 1) n = 1;
    if (n > inCap_) n = inCap_;

    const std::intptr_t stride = inCap_;
    src.readPlanar(spanStart, n, planar_.data(), stride);

    for (int f = 0; f < outFrames; ++f) {
        const double p = posStart + step * f;
        const double local = p - static_cast<double>(spanStart);
        int i0 = static_cast<int>(std::floor(local));
        double frac = local - i0;
        if (i0 < 0) { i0 = 0; frac = 0.0; }
        int i1 = i0 + 1;
        if (i1 >= n) { i1 = n - 1; if (i0 >= n) i0 = n - 1; }
        for (int c = 0; c < ch; ++c) {
            const float* row = &planar_[static_cast<std::size_t>(c) * stride];
            const float s0 = row[i0];
            const float s1 = row[i1];
            out[f * ch + c] = static_cast<float>(s0 + (s1 - s0) * frac);
        }
    }
    return outFrames;
}

} // namespace as
