#include "ui/InstantReplayControls.h"

#include <QHBoxLayout>
#include <QLabel>
#include <QPushButton>
#include <QSignalBlocker>

namespace as {

InstantReplayControls::InstantReplayControls(QWidget* parent) : QWidget(parent) {
    armButton_ = new QPushButton("● Instant Replay", this);
    armButton_->setCheckable(true);
    armButton_->setChecked(false);       // default: capture disarmed
    armButton_->setToolTip("Arm rolling capture — continuously buffer the last N seconds");

    clipButton_ = new QPushButton(this);
    clipButton_->setToolTip("Save the last N seconds of buffered capture to a clip");

    settingsButton_ = new QPushButton("Settings…", this);
    settingsButton_->setToolTip("Configure capture length, source, and microphone");

    statusLabel_ = new QLabel(this);
    statusLabel_->setMinimumWidth(96);

    auto* row = new QHBoxLayout(this);
    row->setContentsMargins(8, 4, 8, 4);
    row->setSpacing(6);
    row->addWidget(armButton_);
    row->addWidget(clipButton_);
    row->addWidget(settingsButton_);
    row->addWidget(statusLabel_);
    row->addStretch(1);

    connect(armButton_, &QPushButton::toggled, this, [this](bool on) {
        refreshStatus();
        emit captureEnabledChanged(on);
    });
    connect(clipButton_, &QPushButton::clicked, this, [this] { emit clipRequested(); });
    connect(settingsButton_, &QPushButton::clicked, this,
            [this] { emit openSettingsRequested(); });

    setBufferSeconds(bufferSeconds_);    // initial "Clip last 30s" label
    refreshStatus();
}

bool InstantReplayControls::captureEnabled() const {
    return armButton_->isChecked();
}

void InstantReplayControls::setBufferSeconds(int seconds) {
    bufferSeconds_ = seconds;
    clipButton_->setText(QString("Clip last %1s").arg(seconds));
}

void InstantReplayControls::setCaptureEnabled(bool on) {
    if (armButton_->isChecked() == on) return;
    const QSignalBlocker block(armButton_);   // sync the view, don't re-emit
    armButton_->setChecked(on);
    refreshStatus();
}

void InstantReplayControls::refreshStatus() {
    statusLabel_->setText(armButton_->isChecked() ? "Buffering…" : "Idle");
}

} // namespace as
