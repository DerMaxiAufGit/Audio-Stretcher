#pragma once
// Transport bar (plan §6.3, Phase 1 Task 6).
//
// Play/Pause toggle + a position/duration readout. The deck is always playing
// unless paused here; grab-to-scratch on the views does not stop it. Manual
// pitch/speed sliders arrive in Phase 2 — space is left for them.

#include <QWidget>

class QPushButton;
class QLabel;

namespace as {

class Deck;

class TransportBar : public QWidget {
    Q_OBJECT
public:
    explicit TransportBar(QWidget* parent = nullptr);
    void setDeck(Deck* deck) { deck_ = deck; }

private:
    static QString formatTime(double seconds);
    void refresh();

    Deck* deck_ = nullptr;
    QPushButton* playButton_ = nullptr;
    QLabel* timeLabel_ = nullptr;
};

} // namespace as
