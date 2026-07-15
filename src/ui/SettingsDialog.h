#pragma once
// Capture / Instant-Replay settings dialog.
//
// Edits the rolling-capture preferences (buffer length, audio source, mic device)
// and persists them via QSettings under the "capture/" group. as::Application sets
// the organisation/application names, so a default-constructed QSettings resolves
// to this app's store. Emits settingsChanged() on Accept so the live capture path
// can re-read the new values.

#include <QDialog>

class QSpinBox;
class QComboBox;

namespace as {

class SettingsDialog : public QDialog {
    Q_OBJECT
public:
    explicit SettingsDialog(QWidget* parent = nullptr);

signals:
    void settingsChanged();

private:
    void load();               // QSettings -> widgets
    void save();               // widgets -> QSettings (+ settingsChanged)
    void populateMicDevices(); // fill mic combo from AudioCapture
    void updateMicEnabled();   // grey out mic combo for desktop-only source

    QSpinBox*  bufferSpin_ = nullptr;
    QComboBox* sourceCombo_ = nullptr;
    QComboBox* micCombo_ = nullptr;
};

} // namespace as
