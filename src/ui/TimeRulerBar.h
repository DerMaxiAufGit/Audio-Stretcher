#pragma once
// Time ruler drawn directly above the WaveformView (issue #2).
//
// Shares the WaveformView's px<->frame mapping so tick marks and mm:ss labels line
// up with the waveform. Adaptive: the label interval is chosen so labels stay ~72px
// apart, so a longer clip (or a zoomed-in view) shows finer time gradations. Purely a
// view — reads the clip's sample rate/duration from the Deck and the zoom from the
// WaveformView, and repaints on viewChanged(). No RT-thread contact.

#include <QWidget>

namespace as {

class Deck;
class WaveformView;

class TimeRulerBar : public QWidget {
    Q_OBJECT
public:
    explicit TimeRulerBar(QWidget* parent = nullptr);

    void setDeck(Deck* deck)          { deck_ = deck; }
    void setWaveform(WaveformView* w);   // shares its time mapping

protected:
    void paintEvent(QPaintEvent*) override;

private:
    Deck*         deck_ = nullptr;
    WaveformView* waveform_ = nullptr;
};

} // namespace as
