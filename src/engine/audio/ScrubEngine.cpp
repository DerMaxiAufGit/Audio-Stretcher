#include "engine/audio/ScrubEngine.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "engine/audio/BungeeStretcher.h"
#include "engine/audio/SignalsmithStretcher.h"

namespace as {

namespace {
constexpr double kMaxScrubSpeed = 32.0;   // clamp scrub velocity (± octaves of rate)
constexpr double kSpeedSmoothing = 0.5;   // one-pole toward target speed (scrub only)
}

// SampleSource bound to whichever DecodedAudio is currently loaded. Reads are
// pure index math; safe on the RT thread once decode is complete.
struct ScrubEngine::Source : SampleSource {
    const DecodedAudio* audio = nullptr;
    int channels() const override { return audio ? audio->channels() : 2; }
    std::int64_t frameCount() const override { return audio ? audio->frameCount() : 0; }
    void readPlanar(std::int64_t startFrame, int n, float* dst,
                    std::intptr_t stride) const override {
        if (audio) audio->readPlanar(startFrame, n, dst, stride);
        else for (int c = 0; c < 2; ++c)
                 for (int i = 0; i < n; ++i) dst[c * stride + i] = 0.0f;
    }
};

ScrubEngine::ScrubEngine() : source_(std::make_unique<Source>()) {}
ScrubEngine::~ScrubEngine() = default;

void ScrubEngine::prepare(int deviceRate, int maxBlockFrames, StretcherKind kind) {
    projectRate_ = kProjectSampleRate;
    deviceRate_ = deviceRate;
    channels_ = 2;
    maxBlock_ = std::max(64, maxBlockFrames);

    if (kind == StretcherKind::Signalsmith)
        stretcher_ = std::make_unique<SignalsmithStretcher>();
    else
        stretcher_ = std::make_unique<BungeeStretcher>();

    resampler_.prepare(channels_, projectRate_, deviceRate_);
    if (deviceRate_ != projectRate_)
        std::fprintf(stderr, "[engine] WARNING: device rate %d != project rate %d; "
                     "Phase 1 runs at the project rate only (no sample-rate conversion "
                     "yet) — audio would be mispitched. (miniaudio normally delivers the "
                     "requested rate, so this should not occur.)\n",
                     deviceRate_, projectRate_);

    projScratchCap_ = maxBlock_ + 8;
    stretcher_->prepare(projectRate_, channels_, projScratchCap_);
    projScratch_.assign(static_cast<std::size_t>(projScratchCap_) * channels_, 0.0f);

    // Warm up the stretcher off the RT thread across EVERY speed/pitch path the RT
    // callback can hit (forward, reverse, hold, and pitch-shift for Phase 2) so all
    // lazy (e.g. Eigen) allocations happen now, not in the audio callback — keeping
    // the first real scrub in any direction click-free (NFR-A).
    source_->audio = nullptr;
    const struct { double speed; double pitch; } warmModes[] = {
        {1.0, 1.0}, {-1.0, 1.0}, {0.0, 1.0}, {1.0, 1.5}, {1.0, 0.5},
    };
    const int warmFrames = std::min(maxBlock_, projScratchCap_);
    for (const auto& m : warmModes) {
        StretchRequest warm;
        warm.positionFrames = static_cast<double>(kProjectSampleRate);  // away from 0 for reverse
        warm.speed = m.speed;
        warm.pitch = m.pitch;
        warm.reset = true;
        for (int i = 0; i < 4; ++i) {
            stretcher_->process(warm, projScratch_.data(), warmFrames, *source_);
            warm.reset = false;
        }
    }
    stretcher_->reset();

    currentPosFrames_ = 0.0;
    prevSpeed_ = 0.0;
    resetRequested_ = true;
}

void ScrubEngine::setAudio(const DecodedAudio* audio) {
    audio_.store(audio);
    seekPosSeconds_.store(0.0);
    seekRequested_.store(true);
    resetRequested_.store(true);
}

void ScrubEngine::seekSeconds(double s) {
    seekPosSeconds_.store(s);
    seekRequested_.store(true);
}

double ScrubEngine::durationSeconds() const {
    const DecodedAudio* a = audio_.load();
    return a ? framesToSeconds(a->frameCount(), a->sampleRate()) : 0.0;
}

void ScrubEngine::render(float* out, int frames) {
    // Chunk oversized callbacks so buffers stay bounded (RT-safe, no alloc).
    int done = 0;
    while (done < frames) {
        const int n = std::min(maxBlock_, frames - done);
        renderBlock(out + static_cast<std::size_t>(done) * channels_, n);
        done += n;
    }
}

void ScrubEngine::renderBlock(float* out, int frames) {
    const int ch = channels_;
    const DecodedAudio* a = audio_.load();
    source_->audio = a;

    if (!a || a->empty()) {
        std::memset(out, 0, static_cast<std::size_t>(frames) * ch * sizeof(float));
        publishedPlayheadSeconds_.store(0.0);
        return;
    }

    bool reset = false;
    if (resetRequested_.exchange(false)) reset = true;
    if (seekRequested_.exchange(false)) {
        currentPosFrames_ = seekPosSeconds_.load() * projectRate_;
        prevSpeed_ = 0.0;
        reset = true;
    }
    if (reset && stretcher_) stretcher_->reset();

    const double maxPos = static_cast<double>(a->frameCount());
    currentPosFrames_ = std::clamp(currentPosFrames_, 0.0, maxPos);

    const bool scrubbing = control_.scrubbing.load();
    const bool playing = control_.playing.load();

    // Phase 1 runs at the project rate (the device is opened at it), so the
    // stretcher writes device frames directly — one project-rate frame per device
    // frame. (True SRC via Resampler is deferred; guarded by the prepare() warning.)
    const int projFrames = frames;

    double speed;
    if (scrubbing) {
        const double target = control_.targetPosSeconds.load() * projectRate_;
        const double desired = (target - currentPosFrames_) / std::max(1, projFrames);
        const double clamped = std::clamp(desired, -kMaxScrubSpeed, kMaxScrubSpeed);
        speed = prevSpeed_ + kSpeedSmoothing * (clamped - prevSpeed_);
    } else if (playing) {
        speed = 1.0;
    } else {
        speed = 0.0;
    }

    // Clamp the position DELTA to the track ends and drive the stretcher with the
    // resulting EFFECTIVE speed, so Bungee (which free-runs its own position from
    // this speed) can never overshoot a boundary and desync from the published
    // playhead. Reaching an end holds (effSpeed -> 0) rather than running off it.
    const double nextPos = std::clamp(currentPosFrames_ + speed * projFrames, 0.0, maxPos);
    const double effSpeed = projFrames > 0 ? (nextPos - currentPosFrames_) / projFrames : 0.0;
    prevSpeed_ = effSpeed;

    StretchRequest req;
    req.positionFrames = currentPosFrames_;
    req.speed = effSpeed;
    req.pitch = 1.0;               // Phase 1: fixed at source pitch
    req.reset = reset;

    stretcher_->process(req, out, frames, *source_);

    currentPosFrames_ = nextPos;
    publishedPlayheadSeconds_.store(currentPosFrames_ / projectRate_);
}

} // namespace as
