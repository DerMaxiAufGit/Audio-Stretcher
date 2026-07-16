#include "ui/SettingsDialog.h"

#include <string>

#include <QComboBox>
#include <QDialogButtonBox>
#include <QFormLayout>
#include <QHBoxLayout>
#include <QKeySequence>
#include <QKeySequenceEdit>
#include <QPushButton>
#include <QSettings>
#include <QSpinBox>
#include <QVBoxLayout>

#include "engine/audio/AudioCapture.h"

namespace as {

namespace {
// Persisted keys (org/app set by as::Application, so a default QSettings finds
// this app's store). "capture/enabled" — the armed toggle — is owned by
// InstantReplayControls, not this dialog; it is intentionally not touched here.
constexpr auto kKeyBufferSeconds = "capture/bufferSeconds";
constexpr auto kKeySource        = "capture/source";
constexpr auto kKeyMicDevice     = "capture/micDevice";
constexpr auto kKeyClipHotkey    = "capture/clipHotkey";

constexpr int kDefaultBufferSeconds = 30;
// Instant-Replay "clip last N seconds" shortcut. In-app (window-focused) hotkey;
// MainWindow reads the same key + default and drives the live QShortcut. Stored as
// QKeySequence::PortableText so it round-trips across platforms/locales.
constexpr auto kDefaultClipHotkey = "Ctrl+Alt+R";

// Source-combo item-data tokens, matching the persisted "capture/source" values.
constexpr auto kSourceDesktop    = "desktop";
constexpr auto kSourceDesktopMic = "desktop+mic";

// AudioCapture lives in the (Qt-free) engine layer, so its device descriptor most
// likely carries std::string fields; accept a QString field too so this compiles
// regardless of which the parallel header settles on.
inline QString toQString(const std::string& s) { return QString::fromStdString(s); }
inline QString toQString(const QString& s) { return s; }
} // namespace

SettingsDialog::SettingsDialog(QWidget* parent) : QDialog(parent) {
    setWindowTitle("Capture Settings");
    setModal(true);

    bufferSpin_ = new QSpinBox(this);
    bufferSpin_->setRange(1, 120);
    bufferSpin_->setValue(kDefaultBufferSeconds);
    bufferSpin_->setSuffix(" s");
    bufferSpin_->setToolTip("Length of the rolling capture buffer");

    sourceCombo_ = new QComboBox(this);
    sourceCombo_->addItem("Desktop audio only", kSourceDesktop);
    sourceCombo_->addItem("Desktop + Microphone", kSourceDesktopMic);

    micCombo_ = new QComboBox(this);
    micCombo_->setToolTip("Microphone mixed into the capture when the source includes it");
    populateMicDevices();

    // Instant-Replay hotkey: a key-sequence editor plus a Clear button (empty = no
    // hotkey). Works while the AudioScratch window is focused; MainWindow owns the
    // live QShortcut and re-reads this on settingsChanged().
    clipHotkeyEdit_ = new QKeySequenceEdit(this);
    clipHotkeyEdit_->setToolTip(
        "Shortcut to save the last N seconds as a clip. Works while AudioScratch is focused.");
    auto* clearHotkey = new QPushButton("Clear", this);
    clearHotkey->setToolTip("Remove the Instant Replay hotkey");
    connect(clearHotkey, &QPushButton::clicked, clipHotkeyEdit_, &QKeySequenceEdit::clear);
    auto* hotkeyRow = new QWidget(this);
    auto* hotkeyLayout = new QHBoxLayout(hotkeyRow);
    hotkeyLayout->setContentsMargins(0, 0, 0, 0);
    hotkeyLayout->addWidget(clipHotkeyEdit_, 1);
    hotkeyLayout->addWidget(clearHotkey, 0);

    auto* form = new QFormLayout;
    form->addRow("Buffer length", bufferSpin_);
    form->addRow("Capture source", sourceCombo_);
    form->addRow("Microphone", micCombo_);
    form->addRow("Instant Replay hotkey", hotkeyRow);

    auto* buttons =
        new QDialogButtonBox(QDialogButtonBox::Ok | QDialogButtonBox::Cancel, this);

    auto* layout = new QVBoxLayout(this);
    layout->addLayout(form);
    layout->addWidget(buttons);

    connect(sourceCombo_, &QComboBox::currentIndexChanged, this,
            [this] { updateMicEnabled(); });
    connect(buttons, &QDialogButtonBox::accepted, this, [this] { save(); accept(); });
    connect(buttons, &QDialogButtonBox::rejected, this, &QDialog::reject);

    load();
    updateMicEnabled();
}

void SettingsDialog::populateMicDevices() {
    micCombo_->clear();
    for (const auto& dev : AudioCapture::listInputDevices())
        micCombo_->addItem(toQString(dev.name), toQString(dev.id));   // text, id
}

void SettingsDialog::load() {
    QSettings settings;
    bufferSpin_->setValue(
        settings.value(kKeyBufferSeconds, kDefaultBufferSeconds).toInt());

    const QString source = settings.value(kKeySource, kSourceDesktop).toString();
    const int si = sourceCombo_->findData(source);
    sourceCombo_->setCurrentIndex(si >= 0 ? si : 0);

    const QString micId = settings.value(kKeyMicDevice).toString();
    const int mi = micCombo_->findData(micId);
    if (mi >= 0) micCombo_->setCurrentIndex(mi);

    clipHotkeyEdit_->setKeySequence(QKeySequence(
        settings.value(kKeyClipHotkey, kDefaultClipHotkey).toString(),
        QKeySequence::PortableText));
}

void SettingsDialog::save() {
    QSettings settings;
    settings.setValue(kKeyBufferSeconds, bufferSpin_->value());
    settings.setValue(kKeySource, sourceCombo_->currentData().toString());
    settings.setValue(kKeyMicDevice, micCombo_->currentData().toString());
    settings.setValue(kKeyClipHotkey,
                      clipHotkeyEdit_->keySequence().toString(QKeySequence::PortableText));
    emit settingsChanged();
}

void SettingsDialog::updateMicEnabled() {
    const bool micActive = sourceCombo_->currentData().toString() == kSourceDesktopMic;
    micCombo_->setEnabled(micActive);
}

} // namespace as
