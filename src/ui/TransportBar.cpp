#include "ui/TransportBar.h"

#include <QHBoxLayout>
#include <QLabel>
#include <QPushButton>
#include <QSlider>
#include <QTimer>

#include "app/Deck.h"

namespace as {

TransportBar::TransportBar(QWidget* parent) : QWidget(parent) {
    playButton_ = new QPushButton("⏸ Pause", this);
    playButton_->setFixedWidth(110);
    timeLabel_ = new QLabel("00:00.00 / 00:00.00", this);

    // Master volume: 0..200% mapped to a 0.0..2.0 linear gain, plus a mute toggle.
    muteButton_ = new QPushButton("Mute", this);
    muteButton_->setCheckable(true);
    muteButton_->setFixedWidth(70);
    volumeSlider_ = new QSlider(Qt::Horizontal, this);
    volumeSlider_->setRange(0, 200);
    volumeSlider_->setValue(100);          // 100% = unity gain
    volumeSlider_->setFixedWidth(120);
    volumeLabel_ = new QLabel("100%", this);
    volumeLabel_->setFixedWidth(44);

    auto* layout = new QHBoxLayout(this);
    layout->setContentsMargins(8, 6, 8, 6);
    layout->addWidget(playButton_);
    layout->addSpacing(12);
    layout->addWidget(timeLabel_);
    layout->addStretch(1);
    // (Phase 2: pitch / speed sliders + A/B loop controls go here.)
    layout->addWidget(muteButton_);
    layout->addSpacing(8);
    layout->addWidget(volumeSlider_);
    layout->addWidget(volumeLabel_);

    connect(playButton_, &QPushButton::clicked, this, [this] {
        if (deck_) deck_->togglePlay();
        refresh();
    });

    // Slider drives the live gain unless muted; while muted it only edits the value
    // that Unmute will restore (deck_->setGain is added by the integration stage).
    connect(volumeSlider_, &QSlider::valueChanged, this, [this](int value) {
        volumeLabel_->setText(QString::number(value) + "%");
        if (deck_ && !muteButton_->isChecked())
            deck_->setGain(value / 100.0f);
    });
    connect(muteButton_, &QPushButton::toggled, this, [this](bool checked) {
        if (!deck_) return;
        deck_->setGain(checked ? 0.0f : volumeSlider_->value() / 100.0f);
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
