#include "engine/decode/MediaDecoder.h"

#include <cstdio>

extern "C" {
#include <libavformat/avformat.h>
#include <libavutil/avutil.h>
}

namespace as {

MediaDecoder::~MediaDecoder() { close(); }

bool MediaDecoder::open(const std::string& path) {
    close();
    path_ = path;

    if (avformat_open_input(&fmt_, path.c_str(), nullptr, nullptr) < 0) {
        std::fprintf(stderr, "[decode] avformat_open_input failed: %s\n", path.c_str());
        fmt_ = nullptr;
        return false;
    }
    if (avformat_find_stream_info(fmt_, nullptr) < 0) {
        std::fprintf(stderr, "[decode] avformat_find_stream_info failed\n");
        close();
        return false;
    }

    audioStream_ = av_find_best_stream(fmt_, AVMEDIA_TYPE_AUDIO, -1, -1, nullptr, 0);
    videoStream_ = av_find_best_stream(fmt_, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);

    if (audioStream_ < 0 && videoStream_ < 0) {
        std::fprintf(stderr, "[decode] no audio or video stream found\n");
        close();
        return false;
    }
    return true;
}

void MediaDecoder::close() {
    if (fmt_) avformat_close_input(&fmt_);
    fmt_ = nullptr;
    audioStream_ = -1;
    videoStream_ = -1;
}

const AVCodecParameters* MediaDecoder::audioParams() const {
    return hasAudio() ? fmt_->streams[audioStream_]->codecpar : nullptr;
}

const AVCodecParameters* MediaDecoder::videoParams() const {
    return hasVideo() ? fmt_->streams[videoStream_]->codecpar : nullptr;
}

double MediaDecoder::durationSeconds() const {
    if (!fmt_) return 0.0;
    if (fmt_->duration != AV_NOPTS_VALUE)
        return static_cast<double>(fmt_->duration) / AV_TIME_BASE;
    return 0.0;
}

} // namespace as
