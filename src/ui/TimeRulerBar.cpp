#include "ui/TimeRulerBar.h"

#include <algorithm>
#include <array>
#include <cmath>

#include <QPainter>

#include "app/Deck.h"
#include "engine/decode/DecodedAudio.h"
#include "ui/WaveformView.h"

namespace as {

namespace {
// "Nice" label intervals in seconds, ascending (10ms up to 6h).
constexpr std::array<double, 22> kNiceSteps = {
    0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1, 2, 5, 10, 15, 30,
    60, 120, 300, 600, 900, 1800, 3600, 7200, 10800, 21600};

int decimalsFor(double step) { return step < 0.1 ? 2 : (step < 1.0 ? 1 : 0); }

QString formatTime(double s, int decimals) {
    if (s < 0) s = 0;
    const long long total = static_cast<long long>(std::floor(s + 1e-9));
    const int h = static_cast<int>(total / 3600);
    const int m = static_cast<int>((total % 3600) / 60);
    const double sec = s - h * 3600.0 - m * 60.0;
    const int secWidth = decimals > 0 ? decimals + 3 : 2;   // "05" or "05.25"
    const QString head = h > 0 ? QString("%1:%2:").arg(h).arg(m, 2, 10, QChar('0'))
                               : QString("%1:").arg(m);
    return head + QString("%1").arg(sec, secWidth, 'f', decimals, QChar('0'));
}
} // namespace

TimeRulerBar::TimeRulerBar(QWidget* parent) : QWidget(parent) {
    setFixedHeight(22);
    setAutoFillBackground(true);
}

void TimeRulerBar::setWaveform(WaveformView* w) {
    waveform_ = w;
    if (waveform_)
        connect(waveform_, &WaveformView::viewChanged, this,
                qOverload<>(&QWidget::update));
}

void TimeRulerBar::paintEvent(QPaintEvent*) {
    QPainter p(this);
    const int w = width(), h = height();
    p.fillRect(rect(), QColor(30, 32, 38));
    p.setPen(QColor(60, 64, 70));
    p.drawLine(0, h - 1, w, h - 1);            // baseline

    const DecodedAudio* a = deck_ ? deck_->audio() : nullptr;
    if (!a || a->empty() || !waveform_) return;

    const double sr = static_cast<double>(a->sampleRate());
    if (sr <= 0.0) return;

    const double dur = static_cast<double>(a->frameCount()) / sr;
    const double t0  = std::max(0.0, waveform_->frameAtX(0) / sr);
    const double t1  = std::min(dur, waveform_->frameAtX(w) / sr);
    if (t1 <= t0) return;

    // Pick a "nice" label interval so labels stay ~72px apart.
    const double secPerPx = waveform_->samplesPerPixel() / sr;
    const double minStep  = secPerPx * 72.0;
    double step = kNiceSteps.back();
    for (double s : kNiceSteps) { if (s >= minStep) { step = s; break; } }
    const int decimals = decimalsFor(step);

    // Minor ticks (unlabelled) first, then major ticks + labels on top.
    const double minor = step / 5.0;
    p.setPen(QColor(58, 62, 70));
    for (long long k = static_cast<long long>(std::floor(t0 / minor));
         k <= static_cast<long long>(std::ceil(t1 / minor)); ++k) {
        const double t = k * minor;
        if (t < 0.0 || t > dur) continue;
        const double x = waveform_->xAtFrame(t * sr);
        if (x < 0.0 || x > w) continue;
        const int xi = static_cast<int>(std::lround(x));
        p.drawLine(xi, h - 4, xi, h - 1);
    }

    QFont f = p.font();
    f.setPointSizeF(std::max(7.0, f.pointSizeF() - 1.0));
    p.setFont(f);
    for (long long k = static_cast<long long>(std::floor(t0 / step));
         k <= static_cast<long long>(std::ceil(t1 / step)); ++k) {
        const double t = k * step;
        if (t < -1e-9 || t > dur + 1e-9) continue;
        const double x = waveform_->xAtFrame(t * sr);
        if (x < -1.0 || x > w + 1.0) continue;
        const int xi = static_cast<int>(std::lround(x));
        p.setPen(QColor(96, 102, 110));
        p.drawLine(xi, h - 8, xi, h - 1);       // major tick
        p.setPen(QColor(170, 176, 184));
        p.drawText(QPoint(xi + 3, h - 10), formatTime(t, decimals));
    }
}

} // namespace as
