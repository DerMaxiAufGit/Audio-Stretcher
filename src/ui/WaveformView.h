#pragma once
// Waveform view (plan §6.3, Phase 1 Task 4 + Task 6 pointer-scrub).
//
// Renders min/max peak columns for the current zoom (samples-per-pixel) and
// horizontal scroll offset, plus a live playhead read from the engine. Wheel =
// zoom (centred on the cursor), Shift+wheel = pan. Pointer-down/drag scrubs the
// deck (absolute x -> timeline position); release resumes forward play.

#include <QWidget>

namespace as {

class Deck;

class WaveformView : public QWidget {
    Q_OBJECT
public:
    explicit WaveformView(QWidget* parent = nullptr);

    void setDeck(Deck* deck) { deck_ = deck; }
    void onMediaLoaded();      // reset zoom/scroll to fit the new clip

protected:
    void paintEvent(QPaintEvent*) override;
    void wheelEvent(QWheelEvent*) override;
    void mousePressEvent(QMouseEvent*) override;
    void mouseMoveEvent(QMouseEvent*) override;
    void mouseReleaseEvent(QMouseEvent*) override;

private:
    double frameAtX(double x) const;
    double xAtFrame(double frame) const;
    void   scrubToX(double x);
    void   fitAll();
    void   followPlayhead();

    Deck*  deck_ = nullptr;
    double samplesPerPixel_ = 512.0;
    double scrollOffsetFrames_ = 0.0;
};

} // namespace as
