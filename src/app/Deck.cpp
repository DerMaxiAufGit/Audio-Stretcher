#include "app/Deck.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <vector>

#include <QDateTime>
#include <QDir>
#include <QSettings>
#include <QStandardPaths>

#include "engine/decode/AudioDecoder.h"
#include "engine/decode/MediaDecoder.h"
#include "engine/io/WavWriter.h"

namespace as {

namespace {
// Build an AudioCapture::Config from the persisted "capture/*" settings. Mirrors
// the exact keys the SettingsDialog writes (bufferSeconds / source / micDevice).
AudioCapture::Config readCaptureConfig() {
    QSettings s;
    AudioCapture::Config cfg;
    cfg.bufferSeconds = s.value("capture/bufferSeconds", 30).toInt();
    cfg.captureMic    = s.value("capture/source", "desktop").toString() == "desktop+mic";
    cfg.micDeviceId   = s.value("capture/micDevice").toString().toStdString();
    return cfg;
}
} // namespace

Deck::Deck(QObject* parent)
    : QObject(parent), capture_(std::make_unique<AudioCapture>()) {}
Deck::~Deck() {
    device_.stop();
    if (capture_) capture_->stop();
}

int Deck::sampleRate() const {
    return current_ && !current_->empty() ? current_->sampleRate() : kProjectSampleRate;
}

frame_t Deck::currentFrame() const {
    return static_cast<frame_t>(std::llround(publishedSeconds() * sampleRate()));
}

frame_t Deck::durationFrames() const {
    return current_ && !current_->empty() ? current_->frameCount() : 0;
}

void Deck::setPlaybackMode(PlaybackMode m) {
    state_.playbackMode = m;
    engine_.setPlaybackMode(m);
    emit stateChanged();
}

void Deck::setLoop(frame_t begin, frame_t end) {
    state_.setLoop(begin, end);
    engine_.setLoop(state_.loopBeginFrame, state_.loopEndFrame);
    emit stateChanged();
}

void Deck::setLoopEnabled(bool on) {
    state_.loopEnabled = on;
    engine_.setLoopEnabled(on);
    emit stateChanged();
}

uint32_t Deck::addMarker(frame_t frame, const QString& label) {
    const frame_t clamped = std::clamp<frame_t>(frame, 0, durationFrames());
    const uint32_t id = state_.addMarker(clamped, label);
    emit stateChanged();
    return id;
}

void Deck::addMarkerAtPlayhead() { addMarker(currentFrame()); }

void Deck::removeMarker(uint32_t id) {
    state_.removeMarker(id);
    emit stateChanged();
}

void Deck::renameMarker(uint32_t id, const QString& label) {
    if (Marker* m = state_.markerById(id)) { m->label = label; emit stateChanged(); }
}

void Deck::jumpToFrame(frame_t frame) {
    engine_.jumpToFrame(std::clamp<frame_t>(frame, 0, durationFrames()));
}

void Deck::jumpToNextMarker() {
    if (const Marker* m = state_.nextMarkerAfter(currentFrame())) jumpToFrame(m->frame);
}

void Deck::jumpToPrevMarker() {
    if (const Marker* m = state_.prevMarkerBefore(currentFrame())) jumpToFrame(m->frame);
}

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

    // Fresh clip: reset the session (loop off, markers cleared, pitch-preserve) and
    // push clean defaults to the engine control block (Phase 2 Task 9).
    state_.reset();
    engine_.setPitchRatio(1.0f);
    engine_.setBaseRate(1.0f);
    engine_.setLoopEnabled(false);
    engine_.setLoop(0, 0);
    engine_.setPlaybackMode(PlaybackMode::PitchPreserving);
    emit stateChanged();

    engine_.seekSeconds(0.0);
    engine_.setPlaying(true);

    std::fprintf(stderr, "[deck] loaded %s | audio=%s %.2fs | video=%s %dx%d\n",
                 path.toUtf8().constData(),
                 current_->empty() ? "none" : "yes", durationSeconds(),
                 video_.hasVideo() ? "yes" : "none", video_.width(), video_.height());

    emit loaded();
    return true;
}

void Deck::setCaptureEnabled(bool on) {
    // Reflect REALITY, not the request: if the loopback device refuses to start,
    // capture stays OFF so the UI toggle and the persisted "capture/enabled" flag
    // don't lie about being armed.
    bool active = false;
    if (on) active = capture_->start(readCaptureConfig());
    else    capture_->stop();
    QSettings().setValue("capture/enabled", active);   // the arm toggle owns this key
    emit captureActiveChanged(active);
}

void Deck::applyCaptureSettings() {
    // Buffer length / source / mic device changed: restart with a fresh Config so
    // the new rolling window takes effect. No-op when capture is not armed.
    if (capture_ && capture_->running()) {
        capture_->stop();
        capture_->start(readCaptureConfig());
    }
}

bool Deck::clipLastSeconds() {
    const double seconds = QSettings().value("capture/bufferSeconds", 30).toInt();
    std::vector<float> buf;
    const std::size_t frames = capture_->snapshotLastSeconds(seconds, buf);
    if (frames == 0 || buf.empty()) return false;   // nothing captured yet

    const QString dir = QStandardPaths::writableLocation(QStandardPaths::MusicLocation)
                        + "/AudioScratch Clips";
    QDir().mkpath(dir);
    const QString path = dir + "/clip-"
        + QDateTime::currentDateTime().toString("yyyyMMdd-HHmmss") + ".wav";

    if (!writeWav(path.toStdString(), buf.data(), static_cast<std::int64_t>(frames),
                  capture_->channels(), capture_->sampleRate()))
        return false;

    load(path);   // bring the clip into the editor (reuses the decode + wire path)
    return true;
}

} // namespace as
