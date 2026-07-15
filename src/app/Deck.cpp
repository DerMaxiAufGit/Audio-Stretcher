#include "app/Deck.h"

#include <cstdio>

#include "engine/decode/AudioDecoder.h"
#include "engine/decode/MediaDecoder.h"

namespace as {

Deck::Deck(QObject* parent) : QObject(parent) {}
Deck::~Deck() { device_.stop(); }

bool Deck::start() {
    // Open the device up front so the deck is "always playing" (silence until a
    // clip loads). This also prepares/warms the engine off the RT thread.
    return device_.start(&engine_, StretcherKind::Bungee);
}

bool Deck::load(const QString& path) {
    MediaDecoder media;
    if (!media.open(path.toStdString())) {
        std::fprintf(stderr, "[deck] failed to open %s\n", path.toUtf8().constData());
        return false;
    }

    auto decoded = std::make_shared<DecodedAudio>(AudioDecoder::decode(media));
    if (decoded->empty() && !media.hasVideo()) {
        std::fprintf(stderr, "[deck] no decodable audio or video\n");
        return false;
    }

    // RT-safe swap: detach the engine, keep the old PCM alive one generation,
    // then point the engine at the new PCM.
    engine_.setAudio(nullptr);
    previous_ = std::move(current_);
    current_ = std::move(decoded);
    engine_.setAudio(current_->empty() ? nullptr : current_.get());

    if (!current_->empty())
        peaks_.computeAsync(current_);   // worker captures the shared_ptr (see Deck.h)

    // Independent video context (own seek cursor). Audio-only clips -> no video.
    video_.close();
    video_.open(path.toStdString());

    engine_.seekSeconds(0.0);
    engine_.setPlaying(true);

    std::fprintf(stderr, "[deck] loaded %s | audio=%s %.2fs | video=%s %dx%d\n",
                 path.toUtf8().constData(),
                 current_->empty() ? "none" : "yes", durationSeconds(),
                 video_.hasVideo() ? "yes" : "none", video_.width(), video_.height());

    emit loaded();
    return true;
}

} // namespace as
