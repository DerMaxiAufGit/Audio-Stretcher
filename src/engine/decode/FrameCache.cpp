#include "engine/decode/FrameCache.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>   // std::llabs (not guaranteed transitively via <algorithm> on MSVC's STL)
#include <utility>

namespace as {

namespace {
// LRU capacity: ~32 frames of 1080p RGBA (1920*1080*4 ~= 8.3 MB) ~= 265 MB max,
// under the ~380 MB ceiling from the plan. Tune here.
constexpr int   kMaxFrames     = 32;
// How far to pre-decode ahead in the scrub direction, and a small look-behind so
// tiny jitter around the playhead stays cached. Their sum stays below kMaxFrames.
constexpr int   kPrefetchAhead = 20;
constexpr int   kPrefetchBehind = 4;
}

FrameCache::~FrameCache() { close(); }

bool FrameCache::open(const std::string& path) {
    close();

    if (!decoder_.open(path)) return false;

    width_      = decoder_.width();
    height_     = decoder_.height();
    fps_        = decoder_.fps();
    duration_   = decoder_.durationSeconds();
    frameCount_ = decoder_.frameCount();
    if (frameCount_ <= 0) frameCount_ = 1;

    lastRequested_ = 0;
    targetIndex_.store(0);
    direction_.store(1);
    requestSeq_.store(0);

    open_ = true;
    running_.store(true);
    worker_ = std::thread(&FrameCache::threadMain, this);
    return true;
}

void FrameCache::close() {
    if (running_.exchange(false)) {
        wakeCv_.notify_all();
        if (worker_.joinable()) worker_.join();
    } else if (worker_.joinable()) {
        worker_.join();
    }

    decoder_.close();
    {
        std::lock_guard<std::mutex> lk(cacheMtx_);
        cache_.clear();
        lru_.clear();
    }
    open_ = false;
    width_ = height_ = 0;
    fps_ = 0.0;
    duration_ = 0.0;
    frameCount_ = 0;
    lastRequested_ = 0;
}

void FrameCache::requestIndex(int64_t frameIndex) {
    if (frameIndex < 0) frameIndex = 0;
    if (frameCount_ > 0 && frameIndex >= frameCount_) frameIndex = frameCount_ - 1;

    if (frameIndex > lastRequested_)      direction_.store(1);
    else if (frameIndex < lastRequested_) direction_.store(-1);
    lastRequested_ = frameIndex;

    targetIndex_.store(frameIndex);
    requestSeq_.fetch_add(1);
    wakeCv_.notify_one();
}

bool FrameCache::nearest(std::vector<std::uint8_t>& rgba, int& w, int& h) {
    const int64_t want = targetIndex_.load();
    std::shared_ptr<Frame> best;

    {
        std::lock_guard<std::mutex> lk(cacheMtx_);
        if (cache_.empty()) return false;

        // Nearest key to `want`: candidate at lower_bound and the one before it.
        auto it = cache_.lower_bound(want);
        auto bestIt = cache_.end();
        int64_t bestDist = -1;
        auto consider = [&](std::map<int64_t, Entry>::iterator c) {
            if (c == cache_.end()) return;
            const int64_t d = std::llabs(c->first - want);
            if (bestDist < 0 || d < bestDist) { bestDist = d; bestIt = c; }
        };
        consider(it);
        if (it != cache_.begin()) consider(std::prev(it));
        if (bestIt == cache_.end()) return false;

        best = bestIt->second.frame;
        touch(bestIt->first);
    }

    // Copy out of the lock (the frame is kept alive by the shared_ptr).
    w = best->width;
    h = best->height;
    rgba = best->rgba;
    return true;
}

bool FrameCache::contains(int64_t idx) {
    std::lock_guard<std::mutex> lk(cacheMtx_);
    return cache_.find(idx) != cache_.end();
}

void FrameCache::touch(int64_t idx) {
    // Caller holds cacheMtx_. Move idx to the front of the LRU list.
    auto it = cache_.find(idx);
    if (it == cache_.end()) return;
    lru_.erase(it->second.lru);
    lru_.push_front(idx);
    it->second.lru = lru_.begin();
}

void FrameCache::insert(int64_t idx, std::shared_ptr<Frame> frame) {
    std::lock_guard<std::mutex> lk(cacheMtx_);

    auto existing = cache_.find(idx);
    if (existing != cache_.end()) {
        existing->second.frame = std::move(frame);
        lru_.erase(existing->second.lru);
        lru_.push_front(idx);
        existing->second.lru = lru_.begin();
        return;
    }

    lru_.push_front(idx);
    cache_.emplace(idx, Entry{std::move(frame), lru_.begin()});

    // Evict least-recently-used until within capacity.
    while (static_cast<int>(cache_.size()) > kMaxFrames && !lru_.empty()) {
        const int64_t victim = lru_.back();
        lru_.pop_back();
        cache_.erase(victim);
    }
}

void FrameCache::threadMain() {
    std::uint64_t handled = 0;
    while (true) {
        int64_t target;
        int dir;
        {
            std::unique_lock<std::mutex> lk(wakeMtx_);
            wakeCv_.wait(lk, [&] {
                return !running_.load() || requestSeq_.load() != handled;
            });
            if (!running_.load()) return;
            handled = requestSeq_.load();
            target = targetIndex_.load();
            dir = direction_.load();
        }
        prefetchAround(target, dir, handled);
    }
}

void FrameCache::prefetchAround(int64_t target, int dir, std::uint64_t seq) {
    if (dir == 0) dir = 1;

    // Build the decode plan: exact target first (so a UI read right after the
    // request gets it), then ahead in the scrub direction, then a little behind.
    std::vector<int64_t> plan;
    plan.reserve(1 + kPrefetchAhead + kPrefetchBehind);
    plan.push_back(target);
    for (int i = 1; i <= kPrefetchAhead; ++i)  plan.push_back(target + static_cast<int64_t>(i) * dir);
    for (int i = 1; i <= kPrefetchBehind; ++i) plan.push_back(target - static_cast<int64_t>(i) * dir);

    std::vector<std::uint8_t> rgba;
    for (int64_t idx : plan) {
        if (requestSeq_.load() != seq) return;   // preempted by a newer request
        if (idx < 0 || (frameCount_ > 0 && idx >= frameCount_)) continue;
        if (contains(idx)) continue;

        if (decoder_.decodeFrameAt(idx, rgba)) {
            auto f = std::make_shared<Frame>();
            f->width  = decoder_.width();
            f->height = decoder_.height();
            f->rgba   = rgba;   // copy; `rgba` is reused for the next decode
            insert(idx, std::move(f));
        }
    }
}

} // namespace as
