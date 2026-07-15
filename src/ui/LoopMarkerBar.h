#pragma once
// A/B loop + marker bar (plan §6.3, Phase 2 Tasks 7/8).
//
// Drawn directly under the WaveformView and sharing its px<->frame mapping so the
// A/B handles, loop region shading, and marker flags line up with the waveform. A
// small control row on top carries the loop-enable toggle, Set-A / Set-B (at the
// playhead), Clear, and Add-Marker. The mapped strip below handles drag (A/B),
// click-to-jump, and marker rename/delete via a context menu. Reads the loaded
// clip's session state from the Deck and pushes every edit back through it (which
// updates the model + the lock-free control block). No RT-thread contact.

#include <cstdint>

#include <QWidget>

#include "engine/model/Project.h"

class QCheckBox;

namespace as {

class Deck;
class WaveformView;

class LoopMarkerBar : public QWidget {
    Q_OBJECT
public:
    explicit LoopMarkerBar(QWidget* parent = nullptr);

    void setDeck(Deck* deck)         { deck_ = deck; }
    void setWaveform(WaveformView* w);   // shares its time mapping

public slots:
    void syncFromState();            // reflect deck state into the control row

protected:
    void paintEvent(QPaintEvent*) override;
    void mousePressEvent(QMouseEvent*) override;
    void mouseMoveEvent(QMouseEvent*) override;
    void mouseReleaseEvent(QMouseEvent*) override;
    void mouseDoubleClickEvent(QMouseEvent*) override;
    void contextMenuEvent(QContextMenuEvent*) override;

private:
    enum class Drag { None, LoopA, LoopB };

    double xAtFrame(frame_t f) const;
    frame_t frameAtX(double x) const;
    int     markerHit(double x) const;   // marker id under x, or 0
    bool    onStrip(int y) const { return y >= stripTop_; }

    Deck*         deck_ = nullptr;
    WaveformView* waveform_ = nullptr;
    QCheckBox*    loopCheck_ = nullptr;

    int   stripTop_ = 30;
    Drag  drag_ = Drag::None;
    bool  dragMoved_ = false;
};

} // namespace as
