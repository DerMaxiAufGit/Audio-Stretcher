#include "engine/audio/BungeeStretcher.h"

#include <algorithm>
#include <cmath>

#include <bungee/Bungee.h>

namespace as {

BungeeStretcher::BungeeStretcher() = default;
BungeeStretcher::~BungeeStretcher() = default;

void BungeeStretcher::prepare(int sampleRate, int channels, int maxOutputFrames) {
    sampleRate_ = sampleRate;
    channels_ = channels;
    maxOut_ = maxOutputFrames;

    stretcher_ = std::make_unique<Bungee::Stretcher<Bungee::Basic>>(
        Bungee::SampleRates{sampleRate, sampleRate}, channels);
    maxInput_ = stretcher_->maxInputFrameCount();

    req_ = std::make_unique<Bungee::Request>();
    req_->position = 0.0;
    req_->speed = 1.0;
    req_->pitch = 1.0;
    req_->reset = true;
    req_->resampleMode = static_cast<ResampleMode>(0);  // resampleMode_autoOut

    analysis_.assign(static_cast<std::size_t>(maxInput_) * channels_, 0.0f);

    // FIFO must hold at least one full render block plus one over-produced grain.
    fifoCap_ = maxOut_ + maxInput_ + 16;
    fifo_.assign(static_cast<std::size_t>(fifoCap_) * channels_, 0.0f);
    fifoHead_ = fifoTail_ = fifoCount_ = 0;
    prerolled_ = false;
}

void BungeeStretcher::pushGrain(const float* planar, int frames,
                                std::intptr_t channelStride) {
    const int ch = channels_;
    for (int f = 0; f < frames; ++f) {
        if (fifoCount_ >= fifoCap_) break;  // defensive: never overrun (RT-safe)
        float* dst = &fifo_[static_cast<std::size_t>(fifoTail_) * ch];
        for (int c = 0; c < ch; ++c)
            dst[c] = planar[c * channelStride + f];
        fifoTail_ = (fifoTail_ + 1) % fifoCap_;
        ++fifoCount_;
    }
}

int BungeeStretcher::popInterleaved(float* out, int frames) {
    const int ch = channels_;
    int n = std::min(frames, fifoCount_);
    for (int f = 0; f < n; ++f) {
        const float* srcf = &fifo_[static_cast<std::size_t>(fifoHead_) * ch];
        for (int c = 0; c < ch; ++c) out[f * ch + c] = srcf[c];
        fifoHead_ = (fifoHead_ + 1) % fifoCap_;
        --fifoCount_;
    }
    // Zero-pad any shortfall (e.g. right after reset before the FIFO fills).
    for (int f = n; f < frames; ++f)
        for (int c = 0; c < ch; ++c) out[f * ch + c] = 0.0f;
    return n;
}

int BungeeStretcher::process(const StretchRequest& r, float* out, int outFrames,
                             SampleSource& src) {
    Bungee::Request& req = *req_;
    req.speed = r.speed;
    req.pitch = r.pitch;

    if (r.reset || !prerolled_) {
        fifoHead_ = fifoTail_ = fifoCount_ = 0;
        req.position = r.positionFrames;
        req.reset = true;
        stretcher_->preroll(req);   // run-in a few grains before the target
        prerolled_ = true;
    } else {
        req.reset = false;
        // Position free-runs via next(); ScrubEngine integrates the same speed,
        // so the two stay aligned without a per-block reseek (§6.5).
    }

    const std::int64_t trackFrames = src.frameCount();
    int guard = 0;
    const int maxIterations = outFrames / 8 + 64;  // bounded; grains produce ~hop each

    while (fifoCount_ < outFrames && guard++ < maxIterations) {
        Bungee::InputChunk ic = stretcher_->specifyGrain(req);
        int n = ic.end - ic.begin;
        if (n <= 0) { stretcher_->next(req); continue; }
        if (n > maxInput_) n = maxInput_;   // defensive clamp

        const int head = ic.begin < 0
                             ? static_cast<int>(std::min<std::int64_t>(-ic.begin, n)) : 0;
        const int tail = (ic.begin + n) > trackFrames
                             ? static_cast<int>(std::min<std::int64_t>(ic.begin + n - trackFrames, n)) : 0;

        // Planar fill (channel c at analysis_[c*n + i]); OOR frames zeroed by src.
        src.readPlanar(ic.begin, n, analysis_.data(), n);
        stretcher_->analyseGrain(analysis_.data(), /*channelStride=*/n, head, tail);

        Bungee::OutputChunk oc;
        stretcher_->synthesiseGrain(oc);
        if (oc.frameCount > 0)
            pushGrain(oc.data, oc.frameCount, oc.channelStride);

        stretcher_->next(req);
    }

    return popInterleaved(out, outFrames);
}

bool BungeeStretcher::isFlushed() const {
    return stretcher_ ? stretcher_->isFlushed() : true;
}

} // namespace as
