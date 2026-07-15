#pragma once
// Video view (plan §6.3, Phase 1 Task 7 + Task 6 pointer-scrub).
//
// A GPU-backed widget that displays the frame at the playhead, pulled from the
// VideoScrubber each tick and drawn aspect-fit. Audio-only clips show black.
// Grab-and-drag over the picture scrubs the deck like a turntable (relative
// motion -> timeline delta); release resumes forward play.

#include <cstdint>
#include <vector>

#include <QOpenGLWidget>

namespace as {

class Deck;

class VideoView : public QOpenGLWidget {
    Q_OBJECT
public:
    explicit VideoView(QWidget* parent = nullptr);
    void setDeck(Deck* deck) { deck_ = deck; }

protected:
    void paintGL() override;
    void mousePressEvent(QMouseEvent*) override;
    void mouseMoveEvent(QMouseEvent*) override;
    void mouseReleaseEvent(QMouseEvent*) override;

private:
    void tick();

    Deck* deck_ = nullptr;
    std::vector<std::uint8_t> rgba_;
    int frameW_ = 0;
    int frameH_ = 0;
    bool haveFrame_ = false;

    double anchorSeconds_ = 0.0;
    double anchorX_ = 0.0;
};

} // namespace as
