#include "engine/decode/DecodedAudio.h"

#include <algorithm>
#include <cstring>

namespace as {

DecodedAudio::DecodedAudio(std::vector<float> interleaved, int channels, int sampleRate)
    : samples_(std::move(interleaved)),
      channels_(channels > 0 ? channels : 1),
      sampleRate_(sampleRate > 0 ? sampleRate : kProjectSampleRate) {
    frameCount_ = channels_ > 0
                      ? static_cast<frame_t>(samples_.size()) / channels_
                      : 0;
}

void DecodedAudio::read(frame_t frame, float* out, int n) const {
    const int ch = channels_;
    for (int i = 0; i < n; ++i) {
        const frame_t f = frame + i;
        if (f >= 0 && f < frameCount_) {
            const float* s = &samples_[static_cast<std::size_t>(f) * ch];
            for (int c = 0; c < ch; ++c) out[i * ch + c] = s[c];
        } else {
            for (int c = 0; c < ch; ++c) out[i * ch + c] = 0.0f;
        }
    }
}

void DecodedAudio::readPlanar(frame_t startFrame, int n, float* dst,
                              std::intptr_t stride) const {
    const int ch = channels_;
    for (int i = 0; i < n; ++i) {
        const frame_t f = startFrame + i;
        if (f >= 0 && f < frameCount_) {
            const float* s = &samples_[static_cast<std::size_t>(f) * ch];
            for (int c = 0; c < ch; ++c) dst[c * stride + i] = s[c];
        } else {
            for (int c = 0; c < ch; ++c) dst[c * stride + i] = 0.0f;
        }
    }
}

} // namespace as
