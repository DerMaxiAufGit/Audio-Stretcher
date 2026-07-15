#pragma once
// Pitch / speed / mode controls (plan §6.3, Phase 2 Tasks 3/6).
//
// Manual pitch (±36 semitones + cent-level fine, R6/OQ2) computed to a single
// frequency ratio, a base/auto-play rate (R7), and the Pitch-preserve ⟷ Turntable
// mode toggle (R18). Emits pure value signals; MainWindow wires them to the Deck
// (which updates the model + the lock-free control block). Pitch and speed are
// kept strictly independent — the two sliders never cross-couple.

#include <QWidget>

#include "engine/model/Project.h"

class QSlider;
class QLabel;
class QPushButton;

namespace as {

class PitchSpeedControls : public QWidget {
    Q_OBJECT
public:
    explicit PitchSpeedControls(QWidget* parent = nullptr);

    void resetToDefaults();               // 0 st, 1.0x, Pitch-preserve (+ emits)

signals:
    void pitchRatioChanged(float ratio);  // 2^((semitones*100 + cents)/1200)
    void baseRateChanged(float rate);      // released/auto-play speed multiplier
    void playbackModeChanged(PlaybackMode mode);

private:
    void emitPitch();
    void emitRate();
    void refreshLabels();
    static float sliderToRate(int slider);
    static int   rateToSlider(float rate);

    QSlider*     semitones_ = nullptr;    // -36 .. +36
    QSlider*     cents_ = nullptr;        // -100 .. +100
    QSlider*     rate_ = nullptr;         // log-mapped 0.25x .. 4x
    QLabel*      pitchLabel_ = nullptr;
    QLabel*      rateLabel_ = nullptr;
    QPushButton* modeButton_ = nullptr;   // checkable: checked = Turntable
};

} // namespace as
