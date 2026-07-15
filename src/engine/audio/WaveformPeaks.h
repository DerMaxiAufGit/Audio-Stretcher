#pragma once
// Waveform peak reduction (plan §6.3, Phase 1 Task 4).
//
// Reduces the in-RAM PCM to per-bucket min/max pairs (mono-mixed) on a
// background worker (QtConcurrent), off the UI and RT threads. WaveformView
// aggregates these buckets for zoomed-out columns and falls back to raw PCM for
// deep zoom. Emits peaksReady() when the reduction completes.

#include <memory>
#include <vector>

#include <QObject>
#include <QFutureWatcher>

#include "engine/decode/DecodedAudio.h"
#include "engine/model/Project.h"

namespace as {

class WaveformPeaks : public QObject {
    Q_OBJECT
public:
    explicit WaveformPeaks(QObject* parent = nullptr);

    // Compute peaks for `audio`. The worker captures the shared_ptr, so the buffer
    // stays alive for the whole reduction even if the deck loads another clip.
    // bucketFrames = source frames per base-level bucket.
    void computeAsync(std::shared_ptr<const DecodedAudio> audio, int bucketFrames = 64);

    bool ready() const { return ready_; }
    int  bucketFrames() const { return bucketFrames_; }
    frame_t frameCount() const { return frameCount_; }
    const std::vector<float>& mins() const { return mins_; }
    const std::vector<float>& maxs() const { return maxs_; }

signals:
    void peaksReady();

private:
    struct Result {
        std::vector<float> mins;
        std::vector<float> maxs;
        int bucketFrames = 64;
        frame_t frameCount = 0;
    };
    static Result reduce(const DecodedAudio* audio, int bucketFrames);
    void onFinished();

    QFutureWatcher<Result> watcher_;
    std::vector<float> mins_;
    std::vector<float> maxs_;
    int bucketFrames_ = 64;
    frame_t frameCount_ = 0;
    bool ready_ = false;
};

} // namespace as
