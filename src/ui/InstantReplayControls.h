#pragma once
// Instant Replay (ShadowPlay-style) control strip.
//
// Arms a rolling background capture, snapshots the last N seconds on demand, and
// opens the capture settings dialog. Purely a view: it never touches the Deck or
// the audio thread — every action leaves as a signal that MainWindow wires to the
// Deck (whose capture methods land in the integration stage). Symmetrically, the
// armed state can be pushed back in via setCaptureEnabled() without re-emitting.

#include <QWidget>

class QPushButton;
class QLabel;

namespace as {

class InstantReplayControls : public QWidget {
    Q_OBJECT
public:
    explicit InstantReplayControls(QWidget* parent = nullptr);

    bool captureEnabled() const;          // current armed state of the toggle

public slots:
    void setBufferSeconds(int seconds);   // relabel the "Clip last Ns" button
    void setCaptureEnabled(bool on);      // reflect armed state (no re-emit)

signals:
    void captureEnabledChanged(bool enabled);
    void clipRequested();
    void openSettingsRequested();

private:
    void refreshStatus();

    QPushButton* armButton_ = nullptr;    // checkable: checked = capturing
    QPushButton* clipButton_ = nullptr;
    QPushButton* settingsButton_ = nullptr;
    QLabel*      statusLabel_ = nullptr;
    int          bufferSeconds_ = 30;
};

} // namespace as
