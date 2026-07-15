#pragma once
// Container/demuxer wrapper (plan §6.3, Phase 1 Task 3).
//
// Owns one AVFormatContext, locates the best audio and (optional) video stream,
// and exposes their indices/params/timebases. Handles the R1 container/codec
// set since FFmpeg covers them all. Used by AudioDecoder to fully decode audio;
// VideoDecoder opens its OWN independent context so seeking never fights the
// audio read cursor.

#include <string>

struct AVFormatContext;
struct AVCodecParameters;

namespace as {

class MediaDecoder {
public:
    MediaDecoder() = default;
    ~MediaDecoder();
    MediaDecoder(const MediaDecoder&) = delete;
    MediaDecoder& operator=(const MediaDecoder&) = delete;

    bool open(const std::string& path);
    void close();

    AVFormatContext* format() const { return fmt_; }

    bool hasAudio() const { return audioStream_ >= 0; }
    bool hasVideo() const { return videoStream_ >= 0; }
    int  audioStreamIndex() const { return audioStream_; }
    int  videoStreamIndex() const { return videoStream_; }

    const AVCodecParameters* audioParams() const;
    const AVCodecParameters* videoParams() const;

    double durationSeconds() const;
    const std::string& path() const { return path_; }

private:
    AVFormatContext* fmt_ = nullptr;
    int audioStream_ = -1;
    int videoStream_ = -1;
    std::string path_;
};

} // namespace as
