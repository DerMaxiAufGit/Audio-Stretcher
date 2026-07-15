#pragma once
// LRU cache of decoded RGBA frames + background prefetch (plan §6.3, Phase 1 Task 7).
//
// Holds a bounded window of decoded 1080p-ish frames keyed by frame index. A
// single background thread owns the VideoDecoder, watches the requested playhead
// (an atomic target index) and pre-decodes ahead in the current scrub direction,
// inserting into the LRU under a mutex. The UI thread only ever sets the target
// (requestIndex) and copies out the nearest cached frame (nearest) — neither call
// blocks on a decode. The thread is joined cleanly in close()/destructor.

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <list>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "engine/decode/VideoDecoder.h"

namespace as {

class FrameCache {
public:
    FrameCache() = default;
    ~FrameCache();
    FrameCache(const FrameCache&) = delete;
    FrameCache& operator=(const FrameCache&) = delete;

    // Open `path`, decode metadata, and start the background prefetch thread.
    // Returns false if the file has no decodable video stream.
    bool open(const std::string& path);
    void close();
    bool isOpen() const { return open_; }

    int     width()  const { return width_; }
    int     height() const { return height_; }
    double  fps()    const { return fps_; }
    double  durationSeconds() const { return duration_; }
    int64_t frameCount() const { return frameCount_; }

    // UI thread: request that the playhead frame index become cached (and prefetch
    // around it). Non-blocking; updates the scrub direction and wakes the thread.
    void requestIndex(int64_t frameIndex);

    // UI thread: copy the nearest cached frame to the last requested index into
    // `rgba` (size becomes w*h*4). Returns false if nothing is decoded yet. Never
    // blocks on a decode.
    bool nearest(std::vector<std::uint8_t>& rgba, int& w, int& h);

private:
    struct Frame {
        int width = 0;
        int height = 0;
        std::vector<std::uint8_t> rgba;
    };
    struct Entry {
        std::shared_ptr<Frame> frame;
        std::list<int64_t>::iterator lru;   // position in lru_ (most-recent = front)
    };

    void threadMain();
    void prefetchAround(int64_t target, int dir, std::uint64_t seq);
    bool contains(int64_t idx);
    void insert(int64_t idx, std::shared_ptr<Frame> frame);
    void touch(int64_t idx);   // move idx to most-recently-used (caller holds lock)

    VideoDecoder decoder_;

    // Cache state (guarded by cacheMtx_).
    std::mutex cacheMtx_;
    std::map<int64_t, Entry> cache_;
    std::list<int64_t> lru_;   // front = most recently used, back = eviction target

    // Prefetch coordination.
    std::thread worker_;
    std::mutex wakeMtx_;
    std::condition_variable wakeCv_;
    std::atomic<bool>     running_{false};
    std::atomic<int64_t>  targetIndex_{0};
    std::atomic<int>      direction_{1};
    std::atomic<std::uint64_t> requestSeq_{0};
    int64_t lastRequested_ = 0;

    bool    open_ = false;
    int     width_ = 0;
    int     height_ = 0;
    double  fps_ = 0.0;
    double  duration_ = 0.0;
    int64_t frameCount_ = 0;
};

} // namespace as
