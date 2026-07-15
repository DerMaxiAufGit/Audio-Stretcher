#include "ui/WaveformView.h"

#include <algorithm>
#include <cmath>

#include <QMouseEvent>
#include <QPainter>
#include <QTimer>
#include <QWheelEvent>

#include "app/Deck.h"
#include "engine/audio/WaveformPeaks.h"
#include "engine/decode/DecodedAudio.h"

namespace as {

WaveformView::WaveformView(QWidget* parent) : QWidget(parent) {
    setMinimumHeight(140);
    setMouseTracking(false);
    setAutoFillBackground(true);
    setCursor(Qt::PointingHandCursor);

    auto* timer = new QTimer(this);   // ~60 fps repaint for the playhead
    connect(timer, &QTimer::timeout, this, [this] {
        if (deck_ && deck_->playing() && !deck_->scrubbing()) followPlayhead();
        update();
    });
    timer->start(16);
}

void WaveformView::onMediaLoaded() { fitAll(); emit viewChanged(); update(); }

double WaveformView::frameAtX(double x) const {
    return scrollOffsetFrames_ + x * samplesPerPixel_;
}
double WaveformView::xAtFrame(double frame) const {
    return (frame - scrollOffsetFrames_) / samplesPerPixel_;
}

void WaveformView::fitAll() {
    const DecodedAudio* a = deck_ ? deck_->audio() : nullptr;
    if (!a || a->empty() || width() <= 0) return;
    samplesPerPixel_ = std::max(1.0, static_cast<double>(a->frameCount()) / width());
    scrollOffsetFrames_ = 0.0;
}

void WaveformView::followPlayhead() {
    const DecodedAudio* a = deck_ ? deck_->audio() : nullptr;
    if (!a || a->empty()) return;
    const double pf = deck_->publishedSeconds() * a->sampleRate();
    const double x = xAtFrame(pf);
    if (x < 0 || x > width()) {             // recentre when off-screen
        scrollOffsetFrames_ = pf - width() * samplesPerPixel_ * 0.5;
        emit viewChanged();
    }
}

void WaveformView::paintEvent(QPaintEvent*) {
    QPainter p(this);
    p.fillRect(rect(), QColor(24, 26, 30));
    const int w = width(), h = height();
    const int mid = h / 2;

    p.setPen(QColor(60, 64, 70));
    p.drawLine(0, mid, w, mid);

    const DecodedAudio* a = deck_ ? deck_->audio() : nullptr;
    if (!a || a->empty()) {
        p.setPen(QColor(120, 124, 130));
        p.drawText(rect(), Qt::AlignCenter, "Open an audio or video file (File ▸ Open)");
        return;
    }

    WaveformPeaks* peaks = deck_->peaks();
    const bool havePeaks = peaks && peaks->ready();
    const int ch = a->channels();
    const float* pcm = a->data();
    const frame_t frames = a->frameCount();
    const int bf = havePeaks ? peaks->bucketFrames() : 64;

    p.setPen(QColor(88, 170, 255));
    for (int x = 0; x < w; ++x) {
        const double f0d = frameAtX(x);
        const double f1d = frameAtX(x + 1);
        frame_t f0 = static_cast<frame_t>(std::floor(f0d));
        frame_t f1 = static_cast<frame_t>(std::ceil(f1d));
        if (f1 <= f0) f1 = f0 + 1;
        if (f1 <= 0 || f0 >= frames) continue;
        f0 = std::max<frame_t>(f0, 0);
        f1 = std::min<frame_t>(f1, frames);

        float lo = 1.0f, hi = -1.0f;
        if (havePeaks && samplesPerPixel_ >= bf) {
            const std::size_t b0 = static_cast<std::size_t>(f0 / bf);
            const std::size_t b1 = std::min(peaks->mins().size(),
                                            static_cast<std::size_t>((f1 + bf - 1) / bf));
            for (std::size_t b = b0; b < b1; ++b) {
                lo = std::min(lo, peaks->mins()[b]);
                hi = std::max(hi, peaks->maxs()[b]);
            }
        } else {
            for (frame_t f = f0; f < f1; ++f) {
                float s = 0.0f;
                const float* fr = &pcm[static_cast<std::size_t>(f) * ch];
                for (int c = 0; c < ch; ++c) s += fr[c];
                s /= ch;
                lo = std::min(lo, s);
                hi = std::max(hi, s);
            }
        }
        if (lo > hi) continue;
        const int y0 = mid - static_cast<int>(hi * (h * 0.48));
        const int y1 = mid - static_cast<int>(lo * (h * 0.48));
        p.drawLine(x, y0, x, y1);
    }

    // Playhead.
    const double pf = deck_->publishedSeconds() * a->sampleRate();
    const double px = xAtFrame(pf);
    if (px >= 0 && px <= w) {
        p.setPen(QPen(QColor(255, 96, 96), 2));
        p.drawLine(static_cast<int>(px), 0, static_cast<int>(px), h);
    }
}

void WaveformView::wheelEvent(QWheelEvent* e) {
    const DecodedAudio* a = deck_ ? deck_->audio() : nullptr;
    if (!a || a->empty()) return;
    const double mx = e->position().x();

    if (e->modifiers() & Qt::ShiftModifier) {
        scrollOffsetFrames_ -= (e->angleDelta().y() / 120.0) * samplesPerPixel_ * 80.0;
    } else {
        const double fCursor = frameAtX(mx);
        const double factor = std::pow(1.2, e->angleDelta().y() / 120.0);
        const double maxSpp = std::max(1.0, static_cast<double>(a->frameCount()) / std::max(1, width()));
        samplesPerPixel_ = std::clamp(samplesPerPixel_ / factor, 1.0, maxSpp * 4.0);
        scrollOffsetFrames_ = fCursor - mx * samplesPerPixel_;
    }
    scrollOffsetFrames_ = std::clamp(scrollOffsetFrames_, 0.0,
                                     std::max(0.0, static_cast<double>(a->frameCount())));
    emit viewChanged();
    update();
}

void WaveformView::scrubToX(double x) {
    const DecodedAudio* a = deck_ ? deck_->audio() : nullptr;
    if (!a || a->empty()) return;
    const double sec = std::clamp(frameAtX(x) / a->sampleRate(), 0.0, deck_->durationSeconds());
    deck_->setTargetSeconds(sec);
}

void WaveformView::mousePressEvent(QMouseEvent* e) {
    if (e->button() != Qt::LeftButton || !deck_) return;
    deck_->setScrubbing(true);
    deck_->setTargetSeconds(std::clamp(
        (deck_->audio() ? frameAtX(e->position().x()) / deck_->audio()->sampleRate() : 0.0),
        0.0, deck_->durationSeconds()));
    scrubToX(e->position().x());
}

void WaveformView::mouseMoveEvent(QMouseEvent* e) {
    if (deck_ && deck_->scrubbing()) scrubToX(e->position().x());
}

void WaveformView::mouseReleaseEvent(QMouseEvent* e) {
    if (e->button() == Qt::LeftButton && deck_) deck_->setScrubbing(false);
}

} // namespace as
