#pragma once
// The real-time scrub engine (plan §6.1/§6.5, Phase 1 Task 5 + Phase 2 Tasks 1/5/6).
//
// Reads the lock-free ScrubControl (written by the UI thread), computes an
// absolute source position + per-block speed in PROJECT-rate frames, pulls PCM
// from the active IStretcher (pitch-preserving Bungee OR turntable/varispeed,
// selected per block by ScrubControl::playbackMode), resamples to the device rate,
// and publishes the playhead back for the UI. render() runs on the RT audio thread
// and performs NO locks, allocations, or I/O (NFR-A).

#include <atomic>
#include <cstdint>
#include <memory>
#include <vector>

#include "engine/audio/IStretcher.h"
#include "engine/audio/Resampler.h"
#include "engine/decode/DecodedAudio.h"
#include "engine/model/Project.h"

namespace as {

// Lock-free control block: UI thread writes, RT thread reads (atomics only).
// Phase 2 adds pitch/base-rate/loop/discrete-seek/mode fields. Every field is a
// single-word atomic; the (begin,end) loop pair is published as a consistent
// snapshot via loopGeneration (a seqlock — see ScrubEngine::setLoop).
struct ScrubControl {
    std::atomic<double> targetPosSeconds{0.0};  // where the UI wants the playhead
    std::atomic<bool>   scrubbing{false};        // pointer is down
    std::atomic<bool>   playing{true};           // false = paused

    // Phase 2 — performance controls.
    std::atomic<float>    pitchRatio{1.0f};      // freq multiplier (2^(semi+cents)); 1 = no shift
    std::atomic<float>    baseRate{1.0f};        // released/auto-play speed multiplier
    std::atomic<bool>     loopEnabled{false};
    std::atomic<int64_t>  loopBeginFrame{0};
    std::atomic<int64_t>  loopEndFrame{0};
    std::atomic<uint32_t> loopGeneration{0};     // seqlock guard for the (begin,end) pair
    std::atomic<int64_t>  seekTargetFrame{0};    // discrete jump target (marker / A-B click)
    std::atomic<uint32_t> seekSeq{0};            // bumped per jump; RT acts when it changes
    std::atomic<PlaybackMode> playbackMode{PlaybackMode::PitchPreserving};  // live-deck mode (R18)
};

enum class StretcherKind { Bungee, Signalsmith };

class ScrubEngine {
public:
    ScrubEngine();
    ~ScrubEngine();

    // Off-thread setup. Fixes the engine at the project rate / stereo and builds
    // BOTH the pitch-preserving and varispeed stretchers (mode switch never allocs).
    void prepare(int deviceRate, int maxBlockFrames,
                 StretcherKind kind = StretcherKind::Bungee);

    // --- UI-thread controls (thread-safe) ---
    void setAudio(const DecodedAudio* audio);   // nullptr = no media
    void setPlaying(bool p)          { control_.playing.store(p); }
    void setScrubbing(bool s)        { control_.scrubbing.store(s); }
    void setTargetSeconds(double s)  { control_.targetPosSeconds.store(s); }
    void seekSeconds(double s);                 // hard snap + preroll (load path)

    // Phase 2 controls (all lock-free stores; UI never calls the audio thread).
    void setPitchRatio(float ratio)  { control_.pitchRatio.store(ratio, std::memory_order_release); }
    void setBaseRate(float rate)     { control_.baseRate.store(rate, std::memory_order_release); }
    void setLoop(frame_t begin, frame_t end);   // publishes a consistent A/B snapshot (seqlock)
    void setLoopEnabled(bool on)     { control_.loopEnabled.store(on, std::memory_order_release); }
    void jumpToFrame(frame_t frame);            // discrete glitch-free seek (marker / A-B click)
    void setPlaybackMode(PlaybackMode m) { control_.playbackMode.store(m, std::memory_order_release); }

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

    // Preallocated stretcher pair; activeStretcher_ points at the one selected by
    // control_.playbackMode. Switching is a pointer swap + reset (no alloc, NFR-A).
    std::unique_ptr<IStretcher> pitchStretcher_;   // Bungee (or Signalsmith fallback)
    std::unique_ptr<IStretcher> varispeed_;        // VarispeedStretcher
    IStretcher* activeStretcher_ = nullptr;

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
    uint32_t lastSeekSeq_ = 0;       // last-seen ScrubControl::seekSeq
    bool pendingLoopReset_ = false;  // reset the stretcher next block at a loop wrap
    bool loopSuppressed_ = false;    // honor a discrete jump outside the loop (no snap-in
                                     // until the playhead re-enters [A,B); grab-release
                                     // is NOT suppressed, so the loop resumes then)

    int projectRate_ = kProjectSampleRate;
    int deviceRate_ = kProjectSampleRate;
    int channels_ = 2;
    int maxBlock_ = 4096;

    std::vector<float> projScratch_;   // project-rate interleaved scratch
    int projScratchCap_ = 0;
};

} // namespace as
