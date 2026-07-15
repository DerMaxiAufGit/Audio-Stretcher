#include "ui/TransportBar.h"

#include <QHBoxLayout>
#include <QLabel>
#include <QPushButton>
#include <QTimer>

#include "app/Deck.h"

namespace as {

TransportBar::TransportBar(QWidget* parent) : QWidget(parent) {
    playButton_ = new QPushButton("⏸ Pause", this);
    playButton_->setFixedWidth(110);
    timeLabel_ = new QLabel("00:00.00 / 00:00.00", this);

    auto* layout = new QHBoxLayout(this);
    layout->setContentsMargins(8, 6, 8, 6);
    layout->addWidget(playButton_);
    layout->addSpacing(12);
    layout->addWidget(timeLabel_);
    layout->addStretch(1);
    // (Phase 2: pitch / speed sliders + A/B loop controls go here.)

    connect(playButton_, &QPushButton::clicked, this, [this] {
        if (deck_) deck_->togglePlay();
        refresh();
    });

    auto* timer = new QTimer(this);
    connect(timer, &QTimer::timeout, this, &TransportBar::refresh);
    timer->start(80);
}

QString TransportBar::formatTime(double seconds) {
    if (seconds < 0) seconds = 0;
    const int total = static_cast<int>(seconds);
    const int m = total / 60;
    const int s = total % 60;
    const int cs = static_cast<int>((seconds - total) * 100);
    return QString("%1:%2.%3")
        .arg(m, 2, 10, QChar('0'))
        .arg(s, 2, 10, QChar('0'))
        .arg(cs, 2, 10, QChar('0'));
}

void TransportBar::refresh() {
    if (!deck_) return;
    playButton_->setText(deck_->playing() ? "⏸ Pause" : "▶ Play");
    timeLabel_->setText(formatTime(deck_->publishedSeconds()) + " / " +
                        formatTime(deck_->durationSeconds()));
}

} // namespace as
