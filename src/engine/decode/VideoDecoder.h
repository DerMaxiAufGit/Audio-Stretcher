#pragma once
// Frame-accurate video decode -> RGBA8888 (plan §6.3, Phase 1 Task 7).
//
// Opens its OWN independent AVFormatContext + video AVCodecContext (it never
// shares MediaDecoder's context, whose read cursor the audio path fully
// consumes). Implements the standard long-GOP seek: av_seek_frame(BACKWARD) to
// the keyframe <= target, avcodec_flush_buffers, then decode forward until the
// frame whose PTS >= target, converting to RGBA with swscale. A best-effort
// VAAPI hardware-decode path is attempted and gracefully falls back to pure
// software decode on any failure (R4/R20 robustness > speed).

#include <cstdint>
#include <string>
#include <vector>

struct AVFormatContext;
struct AVCodecContext;
struct AVFrame;
struct AVPacket;
struct AVBufferRef;
struct SwsContext;

namespace as {

class VideoDecoder {
public:
    VideoDecoder() = default;
    ~VideoDecoder();
    VideoDecoder(const VideoDecoder&) = delete;
    VideoDecoder& operator=(const VideoDecoder&) = delete;

    // Open `path`, locate the best video stream and set up the decoder. Attempts
    // VAAPI first (unless disabled), else software. Returns false if the file has
    // no decodable video stream. Logs the chosen path to stderr once.
    bool open(const std::string& path);
    void close();

    bool isOpen() const { return ctx_ != nullptr; }

    int    width()  const { return width_; }
    int    height() const { return height_; }
    double fps()    const { return fps_; }
    double durationSeconds() const { return duration_; }
    int64_t frameCount() const { return frameCount_; }

    // Decode the frame at `frameIndex` (0-based, mapped to PTS via fps/time_base)
    // into `rgbaOut` (resized to width*height*4, byte order R,G,B,A). Continues
    // decoding forward without re-seeking when `frameIndex` is just ahead of the
    // decode cursor (fast sequential prefetch), otherwise seeks. Returns false on
    // decode failure. MUST be called from a single thread.
    bool decodeFrameAt(int64_t frameIndex, std::vector<std::uint8_t>& rgbaOut);

    // Chosen pixel format for the hardware surface (AV_PIX_FMT_* as int), or
    // AV_PIX_FMT_NONE. Read by the get_format negotiation callback via ctx opaque.
    int hwPixelFormat() const { return hwPixFmt_; }

private:
    bool openFormat();                 // opens fmt_, finds stream, fills metadata
    bool openCodec(bool tryHardware);  // (re)creates ctx_ + frames for hw or sw
    void closeCodec();
    bool probeFirstFrame();            // decode frame 0 to validate a decode path

    // Pull the next decoded frame with CPU-accessible pixels; returns the frame
    // (owned by this decoder, valid until the next call) and its timestamp, or
    // nullptr at EOF / on error.
    AVFrame* nextFrame(int64_t& outTs);
    bool feedDecoder();                // send one video packet (or the EOF flush)
    bool convertToRgba(AVFrame* f, std::vector<std::uint8_t>& out);

    double  ptsToTime(int64_t pts) const;
    int64_t timeToPts(double seconds) const;
    int64_t timeToIndex(int64_t pts) const;

    AVFormatContext* fmt_ = nullptr;
    AVCodecContext*  ctx_ = nullptr;
    AVBufferRef*     hwDeviceCtx_ = nullptr;
    AVFrame*         decFrame_  = nullptr;  // receives the (possibly hw) frame
    AVFrame*         swFrame_   = nullptr;  // hw->sw transfer target
    AVFrame*         keepFrame_ = nullptr;  // retains the last scanned frame
    AVPacket*        pkt_       = nullptr;
    SwsContext*      sws_       = nullptr;

    std::string path_;
    int    videoStream_ = -1;
    int    codecId_ = 0;                     // AVCodecID as int
    int    width_ = 0;
    int    height_ = 0;
    double fps_ = 0.0;
    double duration_ = 0.0;
    int64_t frameCount_ = 0;
    int    tbNum_ = 0;                        // stream time_base numerator
    int    tbDen_ = 1;                        // stream time_base denominator
    int64_t startPts_ = 0;                    // stream start_time (0 if unset)
    int    hwPixFmt_ = -1;                    // AV_PIX_FMT_NONE

    // Decode-cursor state for sequential forward decoding.
    bool    haveCursor_ = false;
    int64_t cursorIndex_ = -1;
    bool    eofSent_ = false;                 // flush packet already queued
    bool    loggedPath_ = false;
};

} // namespace as
