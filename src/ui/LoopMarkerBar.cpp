#include "ui/LoopMarkerBar.h"

#include <algorithm>
#include <cmath>

#include <QCheckBox>
#include <QContextMenuEvent>
#include <QHBoxLayout>
#include <QInputDialog>
#include <QLabel>
#include <QMenu>
#include <QMouseEvent>
#include <QPainter>
#include <QPushButton>
#include <QTimer>
#include <QVBoxLayout>

#include "app/Deck.h"
#include "engine/decode/DecodedAudio.h"
#include "ui/WaveformView.h"

namespace as {

namespace {
constexpr int kHandleGrab = 6;   // px tolerance for grabbing an A/B handle
constexpr int kMarkerGrab = 5;   // px tolerance for hitting a marker flag
}

LoopMarkerBar::LoopMarkerBar(QWidget* parent) : QWidget(parent) {
    setMinimumHeight(64);
    setAutoFillBackground(true);

    // Control row (top): loop-enable + Set A/B + Clear + Add Marker.
    auto* controls = new QWidget(this);
    controls->setFixedHeight(stripTop_);
    auto* h = new QHBoxLayout(controls);
    h->setContentsMargins(8, 2, 8, 2);
    h->setSpacing(6);

    loopCheck_ = new QCheckBox("Loop", controls);
    auto* setA = new QPushButton("Set A", controls);
    auto* setB = new QPushButton("Set B", controls);
    auto* clear = new QPushButton("Clear", controls);
    auto* addMk = new QPushButton("Add Marker", controls);
    for (QPushButton* b : {setA, setB, clear, addMk}) b->setFixedHeight(24);
    setA->setToolTip("Set loop start A at the playhead");
    setB->setToolTip("Set loop end B at the playhead");
    addMk->setToolTip("Drop a marker at the playhead (M)");

    h->addWidget(loopCheck_);
    h->addSpacing(10);
    h->addWidget(setA);
    h->addWidget(setB);
    h->addWidget(clear);
    h->addSpacing(10);
    h->addWidget(addMk);
    h->addStretch(1);

    auto* v = new QVBoxLayout(this);
    v->setContentsMargins(0, 0, 0, 0);
    v->setSpacing(0);
    v->addWidget(controls, 0);
    v->addStretch(1);   // reserves the mapped strip below the control row

    connect(loopCheck_, &QCheckBox::toggled, this, [this](bool on) {
        if (deck_) deck_->setLoopEnabled(on);
    });
    connect(setA, &QPushButton::clicked, this, [this] {
        if (!deck_ || !deck_->audio()) return;
        const frame_t a = deck_->currentFrame();
        frame_t b = deck_->state().loopEndFrame;
        if (b <= a) b = std::min<frame_t>(a + deck_->sampleRate() / 2, deck_->durationFrames());
        deck_->setLoop(a, b);
    });
    connect(setB, &QPushButton::clicked, this, [this] {
        if (!deck_ || !deck_->audio()) return;
        const frame_t b = deck_->currentFrame();
        frame_t a = deck_->state().loopBeginFrame;
        if (a >= b) a = std::max<frame_t>(b - deck_->sampleRate() / 2, 0);
        deck_->setLoop(a, b);
    });
    connect(clear, &QPushButton::clicked, this, [this] {
        if (deck_) { deck_->setLoop(0, 0); deck_->setLoopEnabled(false); }
    });
    connect(addMk, &QPushButton::clicked, this, [this] {
        if (deck_ && deck_->audio()) deck_->addMarkerAtPlayhead();
    });

    auto* timer = new QTimer(this);   // ~30 fps repaint (playhead + live edits)
    connect(timer, &QTimer::timeout, this, [this] { update(); });
    timer->start(33);
}

void LoopMarkerBar::setWaveform(WaveformView* w) {
    waveform_ = w;
    if (waveform_)
        connect(waveform_, &WaveformView::viewChanged, this,
                qOverload<>(&QWidget::update));
}

void LoopMarkerBar::syncFromState() {
    if (!deck_ || !loopCheck_) return;
    const QSignalBlocker block(loopCheck_);
    loopCheck_->setChecked(deck_->state().loopEnabled);
    update();
}

double LoopMarkerBar::xAtFrame(frame_t f) const {
    return waveform_ ? waveform_->xAtFrame(static_cast<double>(f)) : 0.0;
}
frame_t LoopMarkerBar::frameAtX(double x) const {
    return waveform_ ? static_cast<frame_t>(std::llround(waveform_->frameAtX(x))) : 0;
}

int LoopMarkerBar::markerHit(double x) const {
    if (!deck_) return 0;
    for (const Marker& m : deck_->state().markers)
        if (std::fabs(xAtFrame(m.frame) - x) <= kMarkerGrab) return static_cast<int>(m.id);
    return 0;
}

void LoopMarkerBar::paintEvent(QPaintEvent*) {
    QPainter p(this);
    const int w = width(), h = height();
    p.fillRect(0, stripTop_, w, h - stripTop_, QColor(18, 20, 24));

    if (!deck_ || !deck_->audio() || deck_->audio()->empty() || !waveform_) return;

    const DeckState& s = deck_->state();
    const int sTop = stripTop_ + 2, sBot = h - 2;

    // Loop region shading + A/B handles.
    if (s.loopEndFrame > s.loopBeginFrame) {
        const double ax = xAtFrame(s.loopBeginFrame);
        const double bx = xAtFrame(s.loopEndFrame);
        const QColor fill = s.loopEnabled ? QColor(90, 200, 120, 60)
                                          : QColor(140, 140, 150, 40);
        p.fillRect(QRectF(ax, sTop, bx - ax, sBot - sTop), fill);
        const QColor line = s.loopEnabled ? QColor(120, 230, 150) : QColor(150, 150, 160);
        p.setPen(QPen(line, 2));
        p.drawLine(QPointF(ax, sTop), QPointF(ax, sBot));
        p.drawLine(QPointF(bx, sTop), QPointF(bx, sBot));
        p.setPen(line);
        p.drawText(QPointF(ax + 3, sTop + 12), "A");
        p.drawText(QPointF(bx - 12, sTop + 12), "B");
    }

    // Marker flags + labels.
    p.setPen(QColor(240, 200, 90));
    for (const Marker& m : s.markers) {
        const double mx = xAtFrame(m.frame);
        if (mx < 0 || mx > w) continue;
        p.setPen(QPen(QColor(240, 200, 90), 1));
        p.drawLine(QPointF(mx, sTop), QPointF(mx, sBot));
        p.fillRect(QRectF(mx, sTop, 6, 8), QColor(240, 200, 90));
        if (!m.label.isEmpty())
            p.drawText(QPointF(mx + 8, sBot - 3), m.label);
    }

    // Playhead (context; the waveform is the primary indicator). Uses the HEARD
    // position (latency-compensated) so it lines up with the waveform playhead (#3).
    const double px = waveform_->xAtFrame(deck_->heardSeconds() * deck_->sampleRate());
    if (px >= 0 && px <= w) {
        p.setPen(QPen(QColor(255, 96, 96), 1));
        p.drawLine(QPointF(px, sTop), QPointF(px, sBot));
    }
}

void LoopMarkerBar::mousePressEvent(QMouseEvent* e) {
    if (!deck_ || !deck_->audio() || !onStrip(static_cast<int>(e->position().y()))) {
        QWidget::mousePressEvent(e);
        return;
    }
    if (e->button() != Qt::LeftButton) return;
    const double x = e->position().x();
    dragMoved_ = false;
    drag_ = Drag::None;

    const DeckState& s = deck_->state();
    if (s.loopEndFrame > s.loopBeginFrame) {
        if (std::fabs(xAtFrame(s.loopBeginFrame) - x) <= kHandleGrab) { drag_ = Drag::LoopA; return; }
        if (std::fabs(xAtFrame(s.loopEndFrame) - x) <= kHandleGrab)   { drag_ = Drag::LoopB; return; }
    }
    if (const int id = markerHit(x)) {           // click a marker flag -> jump
        for (const Marker& mk : s.markers)
            if (static_cast<int>(mk.id) == id) { deck_->jumpToFrame(mk.frame); break; }
    }
}

void LoopMarkerBar::mouseMoveEvent(QMouseEvent* e) {
    if (drag_ == Drag::None || !deck_) return;
    dragMoved_ = true;
    const frame_t f = std::clamp<frame_t>(frameAtX(e->position().x()), 0, deck_->durationFrames());
    const DeckState& s = deck_->state();
    if (drag_ == Drag::LoopA)
        deck_->setLoop(std::min<frame_t>(f, s.loopEndFrame - 1), s.loopEndFrame);
    else if (drag_ == Drag::LoopB)
        deck_->setLoop(s.loopBeginFrame, std::max<frame_t>(f, s.loopBeginFrame + 1));
}

void LoopMarkerBar::mouseReleaseEvent(QMouseEvent* e) {
    if (deck_ && !dragMoved_ && drag_ != Drag::None) {   // click on handle -> jump there
        const DeckState& s = deck_->state();
        deck_->jumpToFrame(drag_ == Drag::LoopA ? s.loopBeginFrame : s.loopEndFrame);
    }
    drag_ = Drag::None;
    dragMoved_ = false;
    QWidget::mouseReleaseEvent(e);
}

void LoopMarkerBar::mouseDoubleClickEvent(QMouseEvent* e) {
    if (!deck_ || !onStrip(static_cast<int>(e->position().y()))) { QWidget::mouseDoubleClickEvent(e); return; }
    const int id = markerHit(e->position().x());
    if (!id) return;
    QString cur;
    for (const Marker& m : deck_->state().markers)
        if (static_cast<int>(m.id) == id) { cur = m.label; break; }
    bool ok = false;
    const QString name = QInputDialog::getText(this, "Rename marker", "Label:",
                                               QLineEdit::Normal, cur, &ok);
    if (ok) deck_->renameMarker(static_cast<uint32_t>(id), name);
}

void LoopMarkerBar::contextMenuEvent(QContextMenuEvent* e) {
    if (!deck_ || !deck_->audio() || !onStrip(e->pos().y())) return;
    const double x = e->pos().x();
    const int id = markerHit(x);
    QMenu menu(this);
    if (id) {
        menu.addAction("Jump to marker", this, [this, id] {
            for (const Marker& m : deck_->state().markers)
                if (static_cast<int>(m.id) == id) { deck_->jumpToFrame(m.frame); break; }
        });
        menu.addAction("Rename marker…", this, [this, id] {
            QString cur;
            for (const Marker& m : deck_->state().markers)
                if (static_cast<int>(m.id) == id) { cur = m.label; break; }
            bool ok = false;
            const QString name = QInputDialog::getText(this, "Rename marker", "Label:",
                                                       QLineEdit::Normal, cur, &ok);
            if (ok) deck_->renameMarker(static_cast<uint32_t>(id), name);
        });
        menu.addAction("Delete marker", this, [this, id] {
            deck_->removeMarker(static_cast<uint32_t>(id));
        });
    } else {
        const frame_t f = std::clamp<frame_t>(frameAtX(x), 0, deck_->durationFrames());
        menu.addAction("Add marker here", this, [this, f] { deck_->addMarker(f); });
        menu.addAction("Set A here", this, [this, f] {
            frame_t b = deck_->state().loopEndFrame;
            if (b <= f) b = std::min<frame_t>(f + deck_->sampleRate() / 2, deck_->durationFrames());
            deck_->setLoop(f, b);
        });
        menu.addAction("Set B here", this, [this, f] {
            frame_t a = deck_->state().loopBeginFrame;
            if (a >= f) a = std::max<frame_t>(f - deck_->sampleRate() / 2, 0);
            deck_->setLoop(a, f);
        });
    }
    menu.exec(e->globalPos());
}

} // namespace as
