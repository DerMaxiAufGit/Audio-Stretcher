#pragma once
// Playhead-driven video scrubbing facade (plan §6.3, Phase 1 Task 7).
//
// The UI-facing seam for the scrub video preview. Maps playhead seconds <-> frame
// index (via fps) and drives a FrameCache, which owns the VideoDecoder and a
// background prefetch thread. setPlayheadSeconds/currentFrame are called from the
// Qt UI thread ~60 fps and never block on a decode.
#include <cstdint>
#include <memory>
#include <string>
#include <vector>
namespace as {
class VideoScrubber {
public:
    VideoScrubber();
    ~VideoScrubber();
    bool open(const std::string& path);   // false if the file has no video stream
    void close();
    bool hasVideo() const;
    int  width() const;                    // decoded frame width  (0 if none)
    int  height() const;                   // decoded frame height (0 if none)
    double fps() const;
    double durationSeconds() const;
    // UI thread: tell the scrubber where the playhead is (seconds). Non-blocking;
    // kicks background prefetch around this position in the scrub direction.
    void setPlayheadSeconds(double seconds);
    // UI thread: copy the best-available RGBA8888 frame for the last-set playhead
    // into `rgba` (size becomes width*height*4, byte order R,G,B,A). Returns false
    // if nothing is decoded yet. Must be cheap & must NOT block on decode.
    bool currentFrame(std::vector<std::uint8_t>& rgba, int& w, int& h);
private:
    struct Impl;
    std::unique_ptr<Impl> d_;
};
}
