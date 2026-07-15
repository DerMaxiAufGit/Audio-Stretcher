#include "engine/video/VideoScrubber.h"

#include <cmath>

#include "engine/decode/FrameCache.h"

namespace as {

struct VideoScrubber::Impl {
    FrameCache cache;

    // Convert a playhead time in seconds to a clamped frame index.
    int64_t indexForSeconds(double seconds) const {
        const double fps = cache.fps();
        if (fps <= 0.0) return 0;
        int64_t idx = static_cast<int64_t>(std::llround(seconds * fps));
        if (idx < 0) idx = 0;
        const int64_t n = cache.frameCount();
        if (n > 0 && idx >= n) idx = n - 1;
        return idx;
    }
};

VideoScrubber::VideoScrubber() : d_(std::make_unique<Impl>()) {}
VideoScrubber::~VideoScrubber() = default;

bool VideoScrubber::open(const std::string& path) { return d_->cache.open(path); }
void VideoScrubber::close() { d_->cache.close(); }

bool   VideoScrubber::hasVideo() const        { return d_->cache.isOpen(); }
int    VideoScrubber::width() const           { return d_->cache.width(); }
int    VideoScrubber::height() const          { return d_->cache.height(); }
double VideoScrubber::fps() const             { return d_->cache.fps(); }
double VideoScrubber::durationSeconds() const { return d_->cache.durationSeconds(); }

void VideoScrubber::setPlayheadSeconds(double seconds) {
    if (!d_->cache.isOpen()) return;
    d_->cache.requestIndex(d_->indexForSeconds(seconds));
}

bool VideoScrubber::currentFrame(std::vector<std::uint8_t>& rgba, int& w, int& h) {
    if (!d_->cache.isOpen()) return false;
    return d_->cache.nearest(rgba, w, h);
}

} // namespace as
