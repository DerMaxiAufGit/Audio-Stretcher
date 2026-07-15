#include "engine/decode/VideoDecoder.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/hwcontext.h>
#include <libavutil/imgutils.h>
#include <libavutil/pixfmt.h>
#include <libswscale/swscale.h>
}

namespace as {

namespace {

// get_format negotiation: pick the hardware surface format the decoder set up,
// otherwise let FFmpeg fall through to a software format (transparent fallback).
enum AVPixelFormat pickHwFormat(AVCodecContext* c, const enum AVPixelFormat* fmts) {
    auto* self = static_cast<VideoDecoder*>(c->opaque);
    const int want = self ? self->hwPixelFormat() : AV_PIX_FMT_NONE;
    for (const enum AVPixelFormat* p = fmts; *p != AV_PIX_FMT_NONE; ++p)
        if (static_cast<int>(*p) == want) return *p;
    std::fprintf(stderr, "[video-decode] hw surface format unavailable, using sw\n");
    // Return the first offered (software) format so decoding still succeeds.
    return fmts[0];
}

// Whether the VAAPI attempt is enabled. Software is the robust default; hardware
// is opt-in via AS_VIDEO_HWACCEL so a flaky GPU path can never break decoding.
bool hardwareRequested() {
    const char* e = std::getenv("AS_VIDEO_HWACCEL");
    return e && e[0] != '\0' && std::strcmp(e, "0") != 0;
}

} // namespace

VideoDecoder::~VideoDecoder() { close(); }

bool VideoDecoder::open(const std::string& path) {
    close();
    path_ = path;

    if (!openFormat()) { close(); return false; }

    // Best-effort hardware first, then software; probe each so a path that opens
    // but cannot actually produce frames is discarded before we commit to it.
    if (hardwareRequested() && openCodec(/*tryHardware=*/true) && probeFirstFrame()) {
        std::fprintf(stderr, "[video-decode] using hardware decode (VAAPI)\n");
        return true;
    }
    closeCodec();
    if (openCodec(/*tryHardware=*/false) && probeFirstFrame()) {
        std::fprintf(stderr, "[video-decode] using software decode\n");
        return true;
    }

    close();
    return false;
}

void VideoDecoder::close() {
    closeCodec();
    if (sws_) { sws_freeContext(sws_); sws_ = nullptr; }
    if (fmt_) avformat_close_input(&fmt_);
    fmt_ = nullptr;
    videoStream_ = -1;
    width_ = height_ = 0;
    fps_ = 0.0;
    duration_ = 0.0;
    frameCount_ = 0;
    startPts_ = 0;
    path_.clear();
    loggedPath_ = false;
}

bool VideoDecoder::openFormat() {
    if (avformat_open_input(&fmt_, path_.c_str(), nullptr, nullptr) < 0) {
        std::fprintf(stderr, "[video-decode] avformat_open_input failed: %s\n", path_.c_str());
        fmt_ = nullptr;
        return false;
    }
    if (avformat_find_stream_info(fmt_, nullptr) < 0) {
        std::fprintf(stderr, "[video-decode] avformat_find_stream_info failed\n");
        return false;
    }

    videoStream_ = av_find_best_stream(fmt_, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
    if (videoStream_ < 0) return false;

    AVStream* st = fmt_->streams[videoStream_];
    const AVCodecParameters* par = st->codecpar;
    codecId_ = par->codec_id;
    width_ = par->width;
    height_ = par->height;

    tbNum_ = st->time_base.num;
    tbDen_ = st->time_base.den > 0 ? st->time_base.den : 1;
    startPts_ = (st->start_time != AV_NOPTS_VALUE) ? st->start_time : 0;

    AVRational fr = av_guess_frame_rate(fmt_, st, nullptr);
    if (fr.num <= 0 || fr.den <= 0) fr = st->avg_frame_rate;
    if (fr.num <= 0 || fr.den <= 0) fr = st->r_frame_rate;
    fps_ = (fr.num > 0 && fr.den > 0) ? av_q2d(fr) : 25.0;

    if (fmt_->duration != AV_NOPTS_VALUE)
        duration_ = static_cast<double>(fmt_->duration) / AV_TIME_BASE;
    else if (st->duration != AV_NOPTS_VALUE)
        duration_ = st->duration * (static_cast<double>(tbNum_) / tbDen_);

    if (st->nb_frames > 0)
        frameCount_ = st->nb_frames;
    else if (duration_ > 0.0)
        frameCount_ = static_cast<int64_t>(std::llround(duration_ * fps_));
    if (frameCount_ <= 0) frameCount_ = 1;

    return width_ > 0 && height_ > 0;
}

bool VideoDecoder::openCodec(bool tryHardware) {
    const AVCodec* codec = avcodec_find_decoder(static_cast<AVCodecID>(codecId_));
    if (!codec) { std::fprintf(stderr, "[video-decode] no decoder for codec\n"); return false; }

    ctx_ = avcodec_alloc_context3(codec);
    if (!ctx_) return false;

    const AVCodecParameters* par = fmt_->streams[videoStream_]->codecpar;
    if (avcodec_parameters_to_context(ctx_, par) < 0) { closeCodec(); return false; }

    ctx_->opaque = this;
    ctx_->thread_count = 0;   // let FFmpeg pick a sensible worker count (sw path)
    hwPixFmt_ = AV_PIX_FMT_NONE;

    if (tryHardware) {
        // Locate a VAAPI hw config advertised by this decoder.
        for (int i = 0;; ++i) {
            const AVCodecHWConfig* cfg = avcodec_get_hw_config(codec, i);
            if (!cfg) break;
            if ((cfg->methods & AV_CODEC_HW_CONFIG_METHOD_HW_DEVICE_CTX) &&
                cfg->device_type == AV_HWDEVICE_TYPE_VAAPI) {
                hwPixFmt_ = cfg->pix_fmt;
                break;
            }
        }
        if (hwPixFmt_ == AV_PIX_FMT_NONE) { closeCodec(); return false; }

        if (av_hwdevice_ctx_create(&hwDeviceCtx_, AV_HWDEVICE_TYPE_VAAPI,
                                   nullptr, nullptr, 0) < 0) {
            std::fprintf(stderr, "[video-decode] VAAPI device create failed\n");
            closeCodec();
            return false;
        }
        ctx_->hw_device_ctx = av_buffer_ref(hwDeviceCtx_);
        ctx_->get_format = pickHwFormat;
    }

    if (avcodec_open2(ctx_, codec, nullptr) < 0) {
        std::fprintf(stderr, "[video-decode] avcodec_open2 failed (%s)\n",
                     tryHardware ? "hw" : "sw");
        closeCodec();
        return false;
    }

    decFrame_  = av_frame_alloc();
    swFrame_   = av_frame_alloc();
    keepFrame_ = av_frame_alloc();
    pkt_       = av_packet_alloc();
    if (!decFrame_ || !swFrame_ || !keepFrame_ || !pkt_) { closeCodec(); return false; }

    eofSent_ = false;
    haveCursor_ = false;
    cursorIndex_ = -1;
    return true;
}

void VideoDecoder::closeCodec() {
    if (pkt_)       av_packet_free(&pkt_);
    if (decFrame_)  av_frame_free(&decFrame_);
    if (swFrame_)   av_frame_free(&swFrame_);
    if (keepFrame_) av_frame_free(&keepFrame_);
    if (ctx_)       avcodec_free_context(&ctx_);
    if (hwDeviceCtx_) av_buffer_unref(&hwDeviceCtx_);
    hwPixFmt_ = AV_PIX_FMT_NONE;
    eofSent_ = false;
    haveCursor_ = false;
    cursorIndex_ = -1;
}

bool VideoDecoder::probeFirstFrame() {
    std::vector<std::uint8_t> scratch;
    return decodeFrameAt(0, scratch);
}

double VideoDecoder::ptsToTime(int64_t pts) const {
    return static_cast<double>(pts - startPts_) * (static_cast<double>(tbNum_) / tbDen_);
}

int64_t VideoDecoder::timeToPts(double seconds) const {
    return startPts_ + std::llround(seconds * (static_cast<double>(tbDen_) / tbNum_));
}

int64_t VideoDecoder::timeToIndex(int64_t pts) const {
    return std::llround(ptsToTime(pts) * fps_);
}

bool VideoDecoder::feedDecoder() {
    if (eofSent_) return false;   // flush already queued; only draining remains
    for (;;) {
        const int r = av_read_frame(fmt_, pkt_);
        if (r < 0) {
            avcodec_send_packet(ctx_, nullptr);   // enter drain mode
            eofSent_ = true;
            return true;
        }
        if (pkt_->stream_index == videoStream_) {
            const int sr = avcodec_send_packet(ctx_, pkt_);
            av_packet_unref(pkt_);
            (void)sr;   // EAGAIN cannot occur right after a receive returned EAGAIN
            return true;
        }
        av_packet_unref(pkt_);
    }
}

AVFrame* VideoDecoder::nextFrame(int64_t& outTs) {
    for (;;) {
        const int r = avcodec_receive_frame(ctx_, decFrame_);
        if (r == 0) {
            int64_t ts = decFrame_->best_effort_timestamp;
            if (ts == AV_NOPTS_VALUE) ts = decFrame_->pts;
            outTs = ts;

            if (hwPixFmt_ != AV_PIX_FMT_NONE && decFrame_->format == hwPixFmt_) {
                av_frame_unref(swFrame_);
                if (av_hwframe_transfer_data(swFrame_, decFrame_, 0) < 0) {
                    std::fprintf(stderr, "[video-decode] hw frame transfer failed\n");
                    return nullptr;   // caller discards this decode path
                }
                swFrame_->best_effort_timestamp = ts;
                return swFrame_;
            }
            return decFrame_;
        }
        if (r == AVERROR(EAGAIN)) {
            if (!feedDecoder()) return nullptr;   // nothing left to feed or drain
            continue;
        }
        if (r == AVERROR_EOF) return nullptr;
        return nullptr;   // hard error
    }
}

bool VideoDecoder::decodeFrameAt(int64_t frameIndex, std::vector<std::uint8_t>& out) {
    if (!isOpen()) return false;
    if (frameIndex < 0) frameIndex = 0;
    if (frameCount_ > 0 && frameIndex >= frameCount_) frameIndex = frameCount_ - 1;

    const double targetTime = frameIndex / fps_;
    const double halfFrame  = 0.5 / fps_;

    // Continue forward without a seek when the target is just ahead of the cursor
    // (sequential prefetch); this keeps long-GOP forward scrubbing O(1) per frame.
    constexpr int64_t kMaxForwardScan = 240;
    bool doSeek = true;
    if (haveCursor_ && frameIndex > cursorIndex_ &&
        (frameIndex - cursorIndex_) <= kMaxForwardScan) {
        doSeek = false;
    }

    if (doSeek) {
        const int64_t targetPts = timeToPts(targetTime);
        if (av_seek_frame(fmt_, videoStream_, targetPts, AVSEEK_FLAG_BACKWARD) < 0)
            av_seek_frame(fmt_, videoStream_, startPts_, AVSEEK_FLAG_BACKWARD);
        avcodec_flush_buffers(ctx_);
        eofSent_ = false;
        haveCursor_ = false;
    }

    bool haveKept = false;
    for (;;) {
        int64_t ts = AV_NOPTS_VALUE;
        AVFrame* frame = nextFrame(ts);
        if (!frame) break;   // EOF or error

        const double ftime = (ts == AV_NOPTS_VALUE) ? -1.0 : ptsToTime(ts);
        cursorIndex_ = (ts == AV_NOPTS_VALUE) ? (cursorIndex_ + 1) : timeToIndex(ts);
        haveCursor_ = true;

        if (ftime < 0.0 || ftime >= targetTime - halfFrame)
            return convertToRgba(frame, out);   // reached (or passed) the target

        // Not there yet: retain (cheap ref, no pixel copy) so an early EOF can
        // still return the closest available frame.
        av_frame_unref(keepFrame_);
        av_frame_ref(keepFrame_, frame);
        haveKept = true;
    }

    if (haveKept) return convertToRgba(keepFrame_, out);
    return false;
}

bool VideoDecoder::convertToRgba(AVFrame* f, std::vector<std::uint8_t>& out) {
    int w = f->width  > 0 ? f->width  : width_;
    int h = f->height > 0 ? f->height : height_;
    if (w <= 0 || h <= 0) return false;

    const auto srcFmt = static_cast<enum AVPixelFormat>(f->format);
    sws_ = sws_getCachedContext(sws_, w, h, srcFmt, w, h, AV_PIX_FMT_RGBA,
                                SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (!sws_) { std::fprintf(stderr, "[video-decode] sws_getCachedContext failed\n"); return false; }

    out.resize(static_cast<std::size_t>(w) * h * 4);
    std::uint8_t* dst[4]   = { out.data(), nullptr, nullptr, nullptr };
    int           stride[4] = { w * 4, 0, 0, 0 };
    sws_scale(sws_, f->data, f->linesize, 0, h, dst, stride);

    width_ = w;
    height_ = h;
    return true;
}

} // namespace as
