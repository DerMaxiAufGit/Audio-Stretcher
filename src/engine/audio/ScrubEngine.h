#pragma once
// The real-time scrub engine (plan §6.1/§6.5, Phase 1 Task 5).
//
// Reads the lock-free ScrubControl (written by the UI thread), computes an
// absolute source position + per-block speed in PROJECT-rate frames, pulls
// pitch-preserved PCM from the active IStretcher, resamples to the device rate,
// and publishes the playhead back for the UI. render() runs on the RT audio
// thread and performs NO locks, allocations, or I/O (NFR-A).

#include <atomic>
#include <memory>
#include <vector>

#include "engine/audio/IStretcher.h"
#include "engine/audio/Resampler.h"
#include "engine/decode/DecodedAudio.h"
#include "engine/model/Project.h"

namespace as {

// Lock-free control block: UI thread writes, RT thread reads (atomics only).
struct ScrubControl {
    std::atomic<double> targetPosSeconds{0.0};  // where the UI wants the playhead
    std::atomic<bool>   scrubbing{false};        // pointer is down
    std::atomic<bool>   playing{true};           // false = paused
};

enum class StretcherKind { Bungee, Signalsmith };

class ScrubEngine {
public:
    ScrubEngine();
    ~ScrubEngine();

    // Off-thread setup. Fixes the engine at the project rate / stereo.
    void prepare(int deviceRate, int maxBlockFrames,
                 StretcherKind kind = StretcherKind::Bungee);

    // --- UI-thread controls (thread-safe) ---
    void setAudio(const DecodedAudio* audio);   // nullptr = no media
    void setPlaying(bool p)          { control_.playing.store(p); }
    void setScrubbing(bool s)        { control_.scrubbing.store(s); }
    void setTargetSeconds(double s)  { control_.targetPosSeconds.store(s); }
    void seekSeconds(double s);                 // hard snap + preroll

    bool   playing()   const { return control_.playing.load(); }
    bool   scrubbing() const { return control_.scrubbing.load(); }
    double publishedSeconds() const { return publishedPlayheadSeconds_.load(); }
    double durationSeconds() const;

    // --- RT-thread entry (called from the audio callback) ---
    void render(float* out, int frames);

private:
    void renderBlock(float* out, int frames);

    struct Source;                              // SampleSource over the current audio
    std::unique_ptr<Source> source_;
    std::unique_ptr<IStretcher> stretcher_;
    Resampler resampler_;

    ScrubControl control_;
    std::atomic<const DecodedAudio*> audio_{nullptr};
    std::atomic<double> publishedPlayheadSeconds_{0.0};

    // seek / reset hand-off (UI -> RT)
    std::atomic<bool>   seekRequested_{false};
    std::atomic<double> seekPosSeconds_{0.0};
    std::atomic<bool>   resetRequested_{false};

    // RT-thread-local state
    double currentPosFrames_ = 0.0;
    double prevSpeed_ = 0.0;

    int projectRate_ = kProjectSampleRate;
    int deviceRate_ = kProjectSampleRate;
    int channels_ = 2;
    int maxBlock_ = 4096;

    std::vector<float> projScratch_;   // project-rate interleaved scratch
    int projScratchCap_ = 0;
};

} // namespace as
