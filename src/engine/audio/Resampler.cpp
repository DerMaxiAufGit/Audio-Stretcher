#include "engine/audio/Resampler.h"

#include <algorithm>
#include <cstring>

namespace as {

void Resampler::prepare(int channels, int inRate, int outRate) {
    channels_ = channels > 0 ? channels : 1;
    inRate_ = inRate;
    outRate_ = outRate;
    ratio_ = static_cast<double>(inRate) / outRate;
    pos_ = 0.0;
}

int Resampler::inputFramesFor(int outFrames) const {
    // Passthrough in Phase 1 (device opens at the project rate).
    return outFrames;
}

int Resampler::process(const float* in, int inFrames, float* out, int outCapacity) {
    // Phase 1 is passthrough only: the audio device is opened at the project rate,
    // so no sample-rate conversion is required. A proper streaming resampler
    // (fractional phase + carried history, or an internal input FIFO) is a later
    // task — do NOT rely on this for inRate != outRate yet; ScrubEngine guards that
    // case with a warning at prepare() and does not call this at differing rates.
    const int n = std::min(inFrames, outCapacity);
    std::memcpy(out, in, static_cast<std::size_t>(n) * channels_ * sizeof(float));
    return n;
}

} // namespace as
