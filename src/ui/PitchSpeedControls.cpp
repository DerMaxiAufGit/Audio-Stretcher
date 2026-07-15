#include "ui/PitchSpeedControls.h"

#include <algorithm>
#include <cmath>

#include <QHBoxLayout>
#include <QLabel>
#include <QPushButton>
#include <QSlider>
#include <QVBoxLayout>

namespace as {

namespace {
constexpr double kRateMin = 0.25;   // slider left edge
constexpr double kRateSpan = 16.0;  // kRateMin * kRateSpan = 4.0 (right edge)
constexpr int    kRateSteps = 1000; // slider resolution
}

float PitchSpeedControls::sliderToRate(int slider) {
    const double t = static_cast<double>(slider) / kRateSteps;   // 0..1
    return static_cast<float>(kRateMin * std::pow(kRateSpan, t));
}

int PitchSpeedControls::rateToSlider(float rate) {
    const double t = std::log(static_cast<double>(rate) / kRateMin) / std::log(kRateSpan);
    return static_cast<int>(std::lround(std::clamp(t, 0.0, 1.0) * kRateSteps));
}

PitchSpeedControls::PitchSpeedControls(QWidget* parent) : QWidget(parent) {
    // --- Pitch: semitones (±36) + cents (±100) + reset ---
    semitones_ = new QSlider(Qt::Horizontal, this);
    semitones_->setRange(-36, 36);
    semitones_->setValue(0);
    semitones_->setMinimumWidth(180);
    semitones_->setToolTip("Pitch — semitones (±36)");

    cents_ = new QSlider(Qt::Horizontal, this);
    cents_->setRange(-100, 100);
    cents_->setValue(0);
    cents_->setMinimumWidth(120);
    cents_->setToolTip("Pitch — fine cents (±100)");

    auto* pitchReset = new QPushButton("0", this);
    pitchReset->setFixedWidth(28);
    pitchReset->setToolTip("Reset pitch to 0");

    pitchLabel_ = new QLabel(this);
    pitchLabel_->setMinimumWidth(96);

    // --- Speed / base rate + reset ---
    rate_ = new QSlider(Qt::Horizontal, this);
    rate_->setRange(0, kRateSteps);
    rate_->setValue(rateToSlider(1.0f));
    rate_->setMinimumWidth(160);
    rate_->setToolTip("Base (auto-play) speed — 0.25x .. 4x");

    auto* rateReset = new QPushButton("1×", this);
    rateReset->setFixedWidth(28);
    rateReset->setToolTip("Reset speed to 1.0x");

    rateLabel_ = new QLabel(this);
    rateLabel_->setMinimumWidth(64);

    // --- Mode toggle (R18) ---
    modeButton_ = new QPushButton(this);
    modeButton_->setCheckable(true);
    modeButton_->setFixedWidth(130);
    modeButton_->setToolTip("Toggle pitch-preserving ⟷ turntable/varispeed scrub");

    auto* row = new QHBoxLayout(this);
    row->setContentsMargins(8, 4, 8, 4);
    row->setSpacing(6);
    row->addWidget(new QLabel("Pitch", this));
    row->addWidget(semitones_);
    row->addWidget(cents_);
    row->addWidget(pitchReset);
    row->addWidget(pitchLabel_);
    row->addSpacing(16);
    row->addWidget(new QLabel("Speed", this));
    row->addWidget(rate_);
    row->addWidget(rateReset);
    row->addWidget(rateLabel_);
    row->addStretch(1);
    row->addWidget(modeButton_);

    connect(semitones_, &QSlider::valueChanged, this, [this] { emitPitch(); });
    connect(cents_, &QSlider::valueChanged, this, [this] { emitPitch(); });
    connect(rate_, &QSlider::valueChanged, this, [this] { emitRate(); });
    connect(pitchReset, &QPushButton::clicked, this, [this] {
        semitones_->setValue(0); cents_->setValue(0);   // valueChanged -> emitPitch
    });
    connect(rateReset, &QPushButton::clicked, this, [this] {
        rate_->setValue(rateToSlider(1.0f));
    });
    connect(modeButton_, &QPushButton::toggled, this, [this](bool turntable) {
        modeButton_->setText(turntable ? "Turntable" : "Pitch-preserve");
        emit playbackModeChanged(turntable ? PlaybackMode::Varispeed
                                           : PlaybackMode::PitchPreserving);
    });

    modeButton_->setChecked(false);
    modeButton_->setText("Pitch-preserve");
    refreshLabels();
}

void PitchSpeedControls::emitPitch() {
    const double total = semitones_->value() * 100.0 + cents_->value();   // cents
    emit pitchRatioChanged(static_cast<float>(std::pow(2.0, total / 1200.0)));
    refreshLabels();
}

void PitchSpeedControls::emitRate() {
    emit baseRateChanged(sliderToRate(rate_->value()));
    refreshLabels();
}

void PitchSpeedControls::refreshLabels() {
    const int st = semitones_->value();
    const int ct = cents_->value();
    pitchLabel_->setText(QString("%1%2 st %3%4 c")
                             .arg(st >= 0 ? "+" : "").arg(st)
                             .arg(ct >= 0 ? "+" : "").arg(ct));
    rateLabel_->setText(QString("%1×").arg(sliderToRate(rate_->value()), 0, 'f', 2));
}

void PitchSpeedControls::resetToDefaults() {
    // Set values without duplicate emissions, then emit once each so the deck picks
    // up the clean state even if a value was already 0/1.
    const QSignalBlocker b1(semitones_), b2(cents_), b3(rate_), b4(modeButton_);
    semitones_->setValue(0);
    cents_->setValue(0);
    rate_->setValue(rateToSlider(1.0f));
    modeButton_->setChecked(false);
    modeButton_->setText("Pitch-preserve");
    emit pitchRatioChanged(1.0f);
    emit baseRateChanged(1.0f);
    emit playbackModeChanged(PlaybackMode::PitchPreserving);
    refreshLabels();
}

} // namespace as
