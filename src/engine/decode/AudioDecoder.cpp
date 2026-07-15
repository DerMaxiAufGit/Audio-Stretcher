#include "engine/decode/AudioDecoder.h"

#include <cstdio>
#include <vector>

#include "engine/decode/MediaDecoder.h"

extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/channel_layout.h>
#include <libavutil/samplefmt.h>
#include <libswresample/swresample.h>
}

namespace as {

namespace {
constexpr int kOutChannels = 2;

// RAII-ish cleanup helpers kept local and simple.
struct FrameGuard { AVFrame* f = av_frame_alloc(); ~FrameGuard() { av_frame_free(&f); } };
struct PacketGuard { AVPacket* p = av_packet_alloc(); ~PacketGuard() { av_packet_free(&p); } };
}

DecodedAudio AudioDecoder::decode(MediaDecoder& media, int projectRate) {
    if (!media.hasAudio()) return {};
    AVFormatContext* fmt = media.format();
    const int streamIdx = media.audioStreamIndex();
    const AVCodecParameters* par = media.audioParams();

    const AVCodec* codec = avcodec_find_decoder(par->codec_id);
    if (!codec) { std::fprintf(stderr, "[audio-decode] no decoder\n"); return {}; }

    AVCodecContext* ctx = avcodec_alloc_context3(codec);
    if (!ctx) return {};
    if (avcodec_parameters_to_context(ctx, par) < 0) { avcodec_free_context(&ctx); return {}; }
    if (avcodec_open2(ctx, codec, nullptr) < 0) {
        std::fprintf(stderr, "[audio-decode] avcodec_open2 failed\n");
        avcodec_free_context(&ctx);
        return {};
    }

    // Ensure a valid input channel layout (some streams leave it unspecified).
    if (ctx->ch_layout.nb_channels <= 0) {
        av_channel_layout_uninit(&ctx->ch_layout);
        av_channel_layout_default(&ctx->ch_layout, par->ch_layout.nb_channels > 0
                                                       ? par->ch_layout.nb_channels : 2);
    }

    AVChannelLayout outLayout;
    av_channel_layout_default(&outLayout, kOutChannels);   // stereo

    SwrContext* swr = nullptr;
    if (swr_alloc_set_opts2(&swr,
                            &outLayout, AV_SAMPLE_FMT_FLT, projectRate,
                            &ctx->ch_layout, ctx->sample_fmt, ctx->sample_rate,
                            0, nullptr) < 0 || swr_init(swr) < 0) {
        std::fprintf(stderr, "[audio-decode] swresample init failed\n");
        if (swr) swr_free(&swr);
        av_channel_layout_uninit(&outLayout);
        avcodec_free_context(&ctx);
        return {};
    }

    std::vector<float> pcm;
    pcm.reserve(static_cast<std::size_t>(projectRate) * kOutChannels * 8);

    FrameGuard frame;
    PacketGuard pkt;
    std::vector<float> conv;

    auto drainFrame = [&](AVFrame* fr) {
        const int maxOut = swr_get_out_samples(swr, fr->nb_samples);
        if (maxOut <= 0) return;
        conv.resize(static_cast<std::size_t>(maxOut) * kOutChannels);
        uint8_t* outPtr = reinterpret_cast<uint8_t*>(conv.data());
        const int got = swr_convert(swr, &outPtr, maxOut,
                                    const_cast<const uint8_t**>(fr->extended_data),
                                    fr->nb_samples);
        if (got > 0)
            pcm.insert(pcm.end(), conv.begin(),
                       conv.begin() + static_cast<std::size_t>(got) * kOutChannels);
    };

    auto receiveAll = [&]() {
        for (;;) {
            const int r = avcodec_receive_frame(ctx, frame.f);
            if (r == AVERROR(EAGAIN) || r == AVERROR_EOF) break;
            if (r < 0) break;
            drainFrame(frame.f);
        }
    };

    while (av_read_frame(fmt, pkt.p) >= 0) {
        if (pkt.p->stream_index == streamIdx) {
            if (avcodec_send_packet(ctx, pkt.p) == 0) receiveAll();
        }
        av_packet_unref(pkt.p);
    }
    // Flush decoder, then flush the resampler tail.
    avcodec_send_packet(ctx, nullptr);
    receiveAll();
    {
        const int tail = swr_get_out_samples(swr, 0);
        if (tail > 0) {
            conv.resize(static_cast<std::size_t>(tail) * kOutChannels);
            uint8_t* outPtr = reinterpret_cast<uint8_t*>(conv.data());
            const int got = swr_convert(swr, &outPtr, tail, nullptr, 0);
            if (got > 0)
                pcm.insert(pcm.end(), conv.begin(),
                           conv.begin() + static_cast<std::size_t>(got) * kOutChannels);
        }
    }

    swr_free(&swr);
    av_channel_layout_uninit(&outLayout);
    avcodec_free_context(&ctx);

    return DecodedAudio(std::move(pcm), kOutChannels, projectRate);
}

} // namespace as
