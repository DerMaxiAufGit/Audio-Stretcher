#pragma once
// Lock-free single-producer/single-consumer ROLLING ring buffer of interleaved
// float32 (ShadowPlay capture core).
//
// One writer (a miniaudio capture-callback thread) appends the newest frames; one
// reader (the UI thread) takes a snapshot of the most recent frames. The storage
// is allocated ONCE at construction (capacityFrames * channels floats) and never
// grows: write() overwrites the oldest samples when full so the buffer always
// holds the most recent `capacityFrames` frames. The writer never blocks and never
// allocates (NFR-A style — safe from an RT-ish callback thread).
//
// Synchronisation is a single std::atomic<uint64_t> total-frames-written counter
// plus modulo indexing (no head/tail pair, no locks). The writer publishes the new
// count with a release store AFTER copying the samples; the reader acquires it
// before copying, so any count the reader observes is backed by written samples.
//
// TEAR RISK (accepted, bounded): the reader snapshots `written`, then copies the
// range [written-n, written) oldest->newest. If the writer laps the reader — i.e.
// advances by ~capacityFrames frames DURING the copy — it can overwrite the oldest
// slots the reader has not yet reached, producing a discontinuity at the start of
// the snapshot. The writer runs at real time, so a lap requires the read to take
// longer than `capacityFrames / sampleRate` seconds. Sizing the buffer to the
// configured window (bufferSeconds) makes that window multiple seconds long, while
// a UI-thread snapshot completes in microseconds — so in practice the writer cannot
// lap the reader. No mutex is used precisely because this bound is acceptable.

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace as {

class RingBuffer {
public:
    RingBuffer(std::size_t capacityFrames, int channels)
        : channels_(channels > 0 ? channels : 1),
          capacityFrames_(capacityFrames > 0 ? capacityFrames : 1),
          buffer_(capacityFrames_ * static_cast<std::size_t>(channels_ > 0 ? channels_ : 1), 0.0f) {}

    int         channels()       const { return channels_; }
    std::size_t capacityFrames() const { return capacityFrames_; }

    // Producer side. Appends `frames` interleaved frames (channels() floats each),
    // overwriting the oldest samples once full. Never blocks, never allocates.
    void write(const float* interleaved, std::size_t frames) {
        if (frames == 0 || interleaved == nullptr) return;
        const std::size_t cap = capacityFrames_;
        const std::size_t ch  = static_cast<std::size_t>(channels_);

        // A block larger than the whole buffer can only leave its tail behind —
        // skip ahead so we copy just the last `cap` frames.
        if (frames > cap) {
            interleaved += (frames - cap) * ch;
            frames = cap;
        }

        // Relaxed load is safe: this is the sole writer, so nobody else advances it.
        const std::uint64_t written = written_.load(std::memory_order_relaxed);
        std::size_t pos = static_cast<std::size_t>(written % cap);

        // Copy in up to two spans to straddle the wrap point.
        std::size_t first = frames;
        if (pos + first > cap) first = cap - pos;
        std::memcpy(&buffer_[pos * ch], interleaved, first * ch * sizeof(float));
        const std::size_t rest = frames - first;
        if (rest) std::memcpy(&buffer_[0], interleaved + first * ch, rest * ch * sizeof(float));

        // Publish the samples: release so a reader observing the new count also
        // observes the writes above.
        written_.store(written + frames, std::memory_order_release);
    }

    // Consumer side. Copies the most recent min(framesWanted, available) frames in
    // chronological (oldest->newest) order into `out` (framesWanted*channels floats
    // of capacity). Returns the number of frames copied. See the TEAR RISK note.
    std::size_t readLast(float* out, std::size_t framesWanted) const {
        if (out == nullptr || framesWanted == 0) return 0;
        const std::size_t cap = capacityFrames_;
        const std::size_t ch  = static_cast<std::size_t>(channels_);

        const std::uint64_t written = written_.load(std::memory_order_acquire);
        const std::size_t available =
            written < cap ? static_cast<std::size_t>(written) : cap;
        std::size_t n = framesWanted < available ? framesWanted : available;
        if (n == 0) return 0;

        // The newest frame sits just before `written`; our block starts n frames back.
        const std::uint64_t startFrame = written - n;
        std::size_t pos = static_cast<std::size_t>(startFrame % cap);

        std::size_t first = n;
        if (pos + first > cap) first = cap - pos;
        std::memcpy(out, &buffer_[pos * ch], first * ch * sizeof(float));
        const std::size_t rest = n - first;
        if (rest) std::memcpy(out + first * ch, &buffer_[0], rest * ch * sizeof(float));
        return n;
    }

private:
    int         channels_;
    std::size_t capacityFrames_;
    std::vector<float> buffer_;              // interleaved, capacityFrames_ * channels_
    std::atomic<std::uint64_t> written_{0};  // total frames ever written (monotonic)
};

} // namespace as
