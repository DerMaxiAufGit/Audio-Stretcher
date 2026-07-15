#include "engine/audio/ScrubEngine.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>

#include "engine/audio/BungeeStretcher.h"
#include "engine/audio/SignalsmithStretcher.h"
#include "engine/audio/VarispeedStretcher.h"

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

    // Build BOTH stretchers so the R18 mode toggle is a pointer swap with no
    // allocation in the audio callback (NFR-A).
    if (kind == StretcherKind::Signalsmith)
        pitchStretcher_ = std::make_unique<SignalsmithStretcher>();
    else
        pitchStretcher_ = std::make_unique<BungeeStretcher>();
    varispeed_ = std::make_unique<VarispeedStretcher>();
    activeStretcher_ = pitchStretcher_.get();

    resampler_.prepare(channels_, projectRate_, deviceRate_);
    if (deviceRate_ != projectRate_)
        std::fprintf(stderr, "[engine] WARNING: device rate %d != project rate %d; "
                     "Phase 1 runs at the project rate only (no sample-rate conversion "
                     "yet) — audio would be mispitched. (miniaudio normally delivers the "
                     "requested rate, so this should not occur.)\n",
                     deviceRate_, projectRate_);

    projScratchCap_ = maxBlock_ + 8;
    pitchStretcher_->prepare(projectRate_, channels_, projScratchCap_);
    varispeed_->prepare(projectRate_, channels_, projScratchCap_);
    projScratch_.assign(static_cast<std::size_t>(projScratchCap_) * channels_, 0.0f);

    // Warm up BOTH stretchers off the RT thread across EVERY speed/pitch path the
    // RT callback can hit (forward, reverse, hold, and pitch-shift) so all lazy
    // (e.g. Eigen) allocations happen now, not in the audio callback — keeping the
    // first real scrub (and the first mode switch) click-free (NFR-A).
    source_->audio = nullptr;
    const struct { double speed; double pitch; } warmModes[] = {
        {1.0, 1.0}, {-1.0, 1.0}, {0.0, 1.0}, {1.0, 1.5}, {1.0, 0.5},
    };
    const int warmFrames = std::min(maxBlock_, projScratchCap_);
    IStretcher* warmTargets[] = { pitchStretcher_.get(), varispeed_.get() };
    for (IStretcher* st : warmTargets) {
        for (const auto& m : warmModes) {
            StretchRequest warm;
            warm.positionFrames = static_cast<double>(kProjectSampleRate);  // away from 0 for reverse
            warm.speed = m.speed;
            warm.pitch = m.pitch;
            warm.reset = true;
            for (int i = 0; i < 4; ++i) {
                st->process(warm, projScratch_.data(), warmFrames, *source_);
                warm.reset = false;
            }
        }
        st->reset();
    }

    currentPosFrames_ = 0.0;
    prevSpeed_ = 0.0;
    pendingLoopReset_ = false;
    loopSuppressed_ = false;
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

// Publish the A/B pair as a consistent snapshot (seqlock): bump the generation to
// an ODD value while writing, then to the next EVEN value when done. The RT reader
// retries while the generation is odd or changed mid-read, so it never observes a
// torn (new begin, old end) pair. Writes are rare (user drag), so the reader
// almost never spins — RT-safe.
void ScrubEngine::setLoop(frame_t begin, frame_t end) {
    if (end < begin) std::swap(begin, end);
    const uint32_t g = control_.loopGeneration.load(std::memory_order_relaxed);
    control_.loopGeneration.store(g + 1, std::memory_order_release);       // odd: in progress
    control_.loopBeginFrame.store(begin, std::memory_order_relaxed);
    control_.loopEndFrame.store(end, std::memory_order_relaxed);
    control_.loopGeneration.store(g + 2, std::memory_order_release);       // even: committed
}

void ScrubEngine::jumpToFrame(frame_t frame) {
    control_.seekTargetFrame.store(frame, std::memory_order_release);
    control_.seekSeq.fetch_add(1, std::memory_order_release);
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

    // --- Mode select: point at the preallocated stretcher for this block (R18) ---
    const PlaybackMode mode = control_.playbackMode.load(std::memory_order_acquire);
    IStretcher* st = (mode == PlaybackMode::Varispeed) ? varispeed_.get() : pitchStretcher_.get();
    bool reset = false;
    if (st != activeStretcher_) { activeStretcher_ = st; reset = true; }   // reset new stretcher

    // --- Reset / seek hand-off ---
    if (resetRequested_.exchange(false)) reset = true;
    if (pendingLoopReset_) { reset = true; pendingLoopReset_ = false; }
    if (seekRequested_.exchange(false)) {                       // seconds-based (load / hard seek)
        currentPosFrames_ = seekPosSeconds_.load() * projectRate_;
        prevSpeed_ = 0.0;
        reset = true;
    }
    const uint32_t seq = control_.seekSeq.load(std::memory_order_acquire);   // frame-based jump
    if (seq != lastSeekSeq_) {
        lastSeekSeq_ = seq;
        currentPosFrames_ = static_cast<double>(control_.seekTargetFrame.load(std::memory_order_acquire));
        prevSpeed_ = 0.0;
        reset = true;
        loopSuppressed_ = true;   // honor a jump that lands outside the loop (marker / handle)
    }

    const double maxPos = static_cast<double>(a->frameCount());
    currentPosFrames_ = std::clamp(currentPosFrames_, 0.0, maxPos);

    const bool scrubbing = control_.scrubbing.load();
    const bool playing = control_.playing.load();

    // Phase 1 runs at the project rate (the device is opened at it), so the
    // stretcher writes device frames directly — one project-rate frame per device
    // frame. (True SRC via Resampler is deferred; guarded by the prepare() warning.)
    const int projFrames = frames;

    // --- Speed: drag-driven while grabbed; base rate while auto-playing; 0 paused ---
    double speed;
    if (scrubbing) {
        const double target = control_.targetPosSeconds.load() * projectRate_;
        const double desired = (target - currentPosFrames_) / std::max(1, projFrames);
        const double clamped = std::clamp(desired, -kMaxScrubSpeed, kMaxScrubSpeed);
        speed = prevSpeed_ + kSpeedSmoothing * (clamped - prevSpeed_);
    } else if (playing) {
        speed = std::clamp(static_cast<double>(control_.baseRate.load()), 0.0, kMaxScrubSpeed);
    } else {
        speed = 0.0;
    }

    // --- A/B loop (auto-play only; ignored while grabbed) ---
    // Reads a consistent (begin,end) snapshot via the loopGeneration seqlock. When
    // the forward-advancing position would cross loopEnd, this block ends exactly at
    // loopEnd and the next block wraps to loopBegin with a stretcher reset at the
    // discontinuity (Bungee free-runs, so the reset re-syncs it click-free).
    frame_t loopBeginF = 0, loopEndF = 0;
    bool loopActive = false;
    if (control_.loopEnabled.load(std::memory_order_acquire) && !scrubbing) {
        uint32_t g0, g1;
        do {
            g0 = control_.loopGeneration.load(std::memory_order_acquire);
            loopBeginF = control_.loopBeginFrame.load(std::memory_order_relaxed);
            loopEndF = control_.loopEndFrame.load(std::memory_order_relaxed);
            g1 = control_.loopGeneration.load(std::memory_order_acquire);
        } while (g0 != g1 || (g0 & 1u));
        loopActive = loopEndF > loopBeginF;
    }
    if (loopActive) {
        const double loopBegin = static_cast<double>(loopBeginF);
        const double loopEnd = static_cast<double>(loopEndF);
        // Once the playhead is inside [A,B) again, drop any discrete-jump suppression
        // so normal looping (and future drift snap-in) resumes.
        if (currentPosFrames_ >= loopBegin && currentPosFrames_ < loopEnd)
            loopSuppressed_ = false;
        // Snap a playhead that sits OUTSIDE the loop back to A — but only for
        // drift / grab-release (loop just enabled past B, released a scrub outside
        // the region). A discrete jump (marker / A-B handle click) sets
        // loopSuppressed_, so its target is honored instead of being yanked to A.
        if (!loopSuppressed_ &&
            (currentPosFrames_ >= loopEnd || currentPosFrames_ < loopBegin)) {
            currentPosFrames_ = loopBegin;
            reset = true;
        }
    } else {
        loopSuppressed_ = false;
    }

    if (reset && activeStretcher_) activeStretcher_->reset();

    // --- Advance with boundary handling; drive the stretcher with the effective
    //     speed so a position-based stretcher (Bungee) can never overshoot. ---
    double effSpeed;
    double nextPos;
    if (loopActive && speed > 0.0 &&
        currentPosFrames_ < static_cast<double>(loopEndF) &&
        currentPosFrames_ + speed * projFrames >= static_cast<double>(loopEndF)) {
        // End this block exactly at B; wrap to A next block (reset re-syncs).
        // The `currentPos < loopEnd` guard keeps effSpeed non-negative when a
        // suppressed discrete jump has parked the playhead at/beyond B.
        const double loopEnd = static_cast<double>(loopEndF);
        effSpeed = projFrames > 0 ? (loopEnd - currentPosFrames_) / projFrames : 0.0;
        nextPos = static_cast<double>(loopBeginF);
        pendingLoopReset_ = true;
    } else {
        // Track-boundary clamp (Phase 1): reduce effSpeed so the stretcher's
        // free-run position can't run off either end and desync the playhead.
        nextPos = std::clamp(currentPosFrames_ + speed * projFrames, 0.0, maxPos);
        effSpeed = projFrames > 0 ? (nextPos - currentPosFrames_) / projFrames : 0.0;
    }
    prevSpeed_ = effSpeed;

    StretchRequest req;
    req.positionFrames = currentPosFrames_;
    req.speed = effSpeed;
    // Pitch-preserving transpose (inert in varispeed, which bends pitch with speed).
    req.pitch = static_cast<double>(control_.pitchRatio.load(std::memory_order_acquire));
    req.reset = reset;

    activeStretcher_->process(req, out, frames, *source_);

    currentPosFrames_ = nextPos;
    publishedPlayheadSeconds_.store(currentPosFrames_ / projectRate_);
}

} // namespace as
