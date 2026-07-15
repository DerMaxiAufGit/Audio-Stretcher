#include "engine/audio/WaveformPeaks.h"

#include <algorithm>

#include <QtConcurrent/QtConcurrent>

namespace as {

WaveformPeaks::WaveformPeaks(QObject* parent) : QObject(parent) {
    connect(&watcher_, &QFutureWatcher<Result>::finished,
            this, &WaveformPeaks::onFinished);
}

WaveformPeaks::Result WaveformPeaks::reduce(const DecodedAudio* audio, int bucketFrames) {
    Result r;
    r.bucketFrames = bucketFrames;
    if (!audio || audio->empty()) return r;

    const int ch = audio->channels();
    const frame_t frames = audio->frameCount();
    r.frameCount = frames;
    const float* pcm = audio->data();
    const frame_t buckets = (frames + bucketFrames - 1) / bucketFrames;
    r.mins.resize(static_cast<std::size_t>(buckets));
    r.maxs.resize(static_cast<std::size_t>(buckets));

    for (frame_t b = 0; b < buckets; ++b) {
        const frame_t f0 = b * bucketFrames;
        const frame_t f1 = std::min<frame_t>(f0 + bucketFrames, frames);
        float lo = 1.0f, hi = -1.0f;
        for (frame_t f = f0; f < f1; ++f) {
            // Mono mix for display.
            float s = 0.0f;
            const float* fr = &pcm[static_cast<std::size_t>(f) * ch];
            for (int c = 0; c < ch; ++c) s += fr[c];
            s /= ch;
            lo = std::min(lo, s);
            hi = std::max(hi, s);
        }
        if (lo > hi) { lo = 0.0f; hi = 0.0f; }   // empty bucket
        r.mins[static_cast<std::size_t>(b)] = lo;
        r.maxs[static_cast<std::size_t>(b)] = hi;
    }
    return r;
}

void WaveformPeaks::computeAsync(std::shared_ptr<const DecodedAudio> audio, int bucketFrames) {
    ready_ = false;
    bucketFrames_ = bucketFrames;
    // Capture the shared_ptr in the task so the PCM outlives the reduction.
    watcher_.setFuture(QtConcurrent::run(
        [audio = std::move(audio), bucketFrames] { return reduce(audio.get(), bucketFrames); }));
}

void WaveformPeaks::onFinished() {
    Result r = watcher_.result();
    mins_ = std::move(r.mins);
    maxs_ = std::move(r.maxs);
    bucketFrames_ = r.bucketFrames;
    frameCount_ = r.frameCount;
    ready_ = true;
    emit peaksReady();
}

} // namespace as
