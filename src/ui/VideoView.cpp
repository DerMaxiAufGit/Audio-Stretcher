#include "ui/VideoView.h"

#include <algorithm>

#include <QImage>
#include <QMouseEvent>
#include <QPainter>
#include <QTimer>

#include "app/Deck.h"
#include "engine/video/VideoScrubber.h"

namespace as {

namespace {
constexpr double kSecondsPerPixel = 0.006;   // scratch sensitivity over the picture
}

VideoView::VideoView(QWidget* parent) : QOpenGLWidget(parent) {
    setMinimumHeight(220);
    setCursor(Qt::PointingHandCursor);
    auto* timer = new QTimer(this);
    connect(timer, &QTimer::timeout, this, &VideoView::tick);
    timer->start(16);   // ~60 fps
}

void VideoView::tick() {
    if (deck_ && deck_->hasVideo()) {
        VideoScrubber* v = deck_->video();
        v->setPlayheadSeconds(deck_->publishedSeconds());
        int w = 0, h = 0;
        if (v->currentFrame(rgba_, w, h) && w > 0 && h > 0) {
            frameW_ = w;
            frameH_ = h;
            haveFrame_ = true;
        }
    } else {
        haveFrame_ = false;
    }
    update();
}

void VideoView::paintGL() {
    QPainter p(this);
    p.fillRect(rect(), Qt::black);
    if (!haveFrame_ || frameW_ <= 0 || frameH_ <= 0 ||
        rgba_.size() < static_cast<std::size_t>(frameW_) * frameH_ * 4)
        return;

    QImage img(rgba_.data(), frameW_, frameH_, frameW_ * 4, QImage::Format_RGBA8888);
    // Aspect-fit into the widget.
    const double sx = static_cast<double>(width()) / frameW_;
    const double sy = static_cast<double>(height()) / frameH_;
    const double s = std::min(sx, sy);
    const int dw = static_cast<int>(frameW_ * s);
    const int dh = static_cast<int>(frameH_ * s);
    const QRect target((width() - dw) / 2, (height() - dh) / 2, dw, dh);
    p.setRenderHint(QPainter::SmoothPixmapTransform, true);
    p.drawImage(target, img);
}

void VideoView::mousePressEvent(QMouseEvent* e) {
    if (e->button() != Qt::LeftButton || !deck_) return;
    anchorSeconds_ = deck_->publishedSeconds();
    anchorX_ = e->position().x();
    deck_->setScrubbing(true);
}

void VideoView::mouseMoveEvent(QMouseEvent* e) {
    if (!deck_ || !deck_->scrubbing()) return;
    const double sec = anchorSeconds_ + (e->position().x() - anchorX_) * kSecondsPerPixel;
    deck_->setTargetSeconds(std::clamp(sec, 0.0, deck_->durationSeconds()));
}

void VideoView::mouseReleaseEvent(QMouseEvent* e) {
    if (e->button() == Qt::LeftButton && deck_) deck_->setScrubbing(false);
}

} // namespace as
