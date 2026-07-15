#pragma once
// Main window (plan §6.3, Phase 1 Task 1/6).
//
// Vertical stack: VideoView (top) · WaveformView (middle) · TransportBar
// (bottom), with a File ▸ Open action. Owns the Deck and wires the views to it.

#include <QMainWindow>

namespace as {

class Deck;
class VideoView;
class WaveformView;
class TransportBar;
class PitchSpeedControls;
class LoopMarkerBar;

class MainWindow : public QMainWindow {
    Q_OBJECT
public:
    explicit MainWindow(QWidget* parent = nullptr);
    ~MainWindow() override;

    bool loadFile(const QString& path);

private slots:
    void openFile();

private:
    Deck* deck_ = nullptr;
    VideoView* video_ = nullptr;
    WaveformView* waveform_ = nullptr;
    TransportBar* transport_ = nullptr;
    PitchSpeedControls* pitchSpeed_ = nullptr;
    LoopMarkerBar* loopBar_ = nullptr;
};

} // namespace as
