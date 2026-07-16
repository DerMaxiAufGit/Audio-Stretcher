#include "app/MainWindow.h"

#include <algorithm>
#include <cmath>

#include <QAction>
#include <QFileDialog>
#include <QFileInfo>
#include <QHBoxLayout>
#include <QMenuBar>
#include <QPushButton>
#include <QScrollBar>
#include <QSettings>
#include <QShortcut>
#include <QSignalBlocker>
#include <QStatusBar>
#include <QVBoxLayout>
#include <QWidget>

#include "app/Deck.h"
#include "ui/InstantReplayControls.h"
#include "ui/LoopMarkerBar.h"
#include "ui/PitchSpeedControls.h"
#include "ui/SettingsDialog.h"
#include "ui/TimeRulerBar.h"
#include "ui/TransportBar.h"
#include "ui/VideoView.h"
#include "ui/WaveformView.h"

namespace as {

MainWindow::MainWindow(QWidget* parent) : QMainWindow(parent) {
    setWindowTitle("AudioScratch");
    resize(1000, 720);

    deck_ = new Deck(this);

    video_ = new VideoView(this);
    waveform_ = new WaveformView(this);
    loopBar_ = new LoopMarkerBar(this);
    pitchSpeed_ = new PitchSpeedControls(this);
    instantReplay_ = new InstantReplayControls(this);
    transport_ = new TransportBar(this);
    video_->setDeck(deck_);
    waveform_->setDeck(deck_);
    transport_->setDeck(deck_);
    loopBar_->setDeck(deck_);
    loopBar_->setWaveform(waveform_);

    auto* central = new QWidget(this);

    ruler_ = new TimeRulerBar(central);
    ruler_->setDeck(deck_);
    ruler_->setWaveform(waveform_);

    // Timeline navigation: zoom buttons + horizontal scrollbar (issue #2).
    auto* navRow    = new QWidget(central);
    auto* navLayout = new QHBoxLayout(navRow);
    navLayout->setContentsMargins(8, 4, 8, 4);
    navLayout->setSpacing(6);
    auto* zoomOutBtn = new QPushButton("-", navRow);
    auto* zoomFitBtn = new QPushButton("Fit", navRow);
    auto* zoomInBtn  = new QPushButton("+", navRow);
    zoomOutBtn->setFixedWidth(32); zoomOutBtn->setToolTip("Zoom out  (-)");
    zoomInBtn->setFixedWidth(32);  zoomInBtn->setToolTip("Zoom in  (+)");
    zoomFitBtn->setToolTip("Fit the whole clip  (0)");
    hScroll_ = new QScrollBar(Qt::Horizontal, navRow);
    hScroll_->setToolTip("Scroll the timeline");
    navLayout->addWidget(zoomOutBtn);
    navLayout->addWidget(zoomFitBtn);
    navLayout->addWidget(zoomInBtn);
    navLayout->addWidget(hScroll_, 1);

    auto* layout = new QVBoxLayout(central);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(0);
    layout->addWidget(video_, 3);
    layout->addWidget(ruler_, 0);        // NEW — time ruler above the waveform
    layout->addWidget(waveform_, 1);
    layout->addWidget(loopBar_, 0);      // aligned directly under the waveform
    layout->addWidget(navRow, 0);        // NEW — zoom buttons + scrollbar
    layout->addWidget(pitchSpeed_, 0);
    layout->addWidget(instantReplay_, 0);
    layout->addWidget(transport_, 0);
    setCentralWidget(central);

    connect(zoomInBtn,  &QPushButton::clicked, waveform_, &WaveformView::zoomIn);
    connect(zoomOutBtn, &QPushButton::clicked, waveform_, &WaveformView::zoomOut);
    connect(zoomFitBtn, &QPushButton::clicked, waveform_, &WaveformView::zoomToFit);
    connect(waveform_,  &WaveformView::viewChanged, this, &MainWindow::syncScrollBar);
    connect(hScroll_,   &QScrollBar::valueChanged, this, [this](int v) {
        if (!waveform_) return;
        waveform_->setScrollOffsetFrames(v * waveform_->samplesPerPixel());
    });
    connect(new QShortcut(QKeySequence(Qt::Key_Plus),  this), &QShortcut::activated,
            waveform_, &WaveformView::zoomIn);
    connect(new QShortcut(QKeySequence(Qt::Key_Equal), this), &QShortcut::activated,
            waveform_, &WaveformView::zoomIn);
    connect(new QShortcut(QKeySequence(Qt::Key_Minus), this), &QShortcut::activated,
            waveform_, &WaveformView::zoomOut);
    connect(new QShortcut(QKeySequence(Qt::Key_0),     this), &QShortcut::activated,
            waveform_, &WaveformView::zoomToFit);

    syncScrollBar();   // initialise the scrollbar (disabled until a clip loads)

    // --- Wire performance controls to the deck (model + engine control block) ---
    connect(pitchSpeed_, &PitchSpeedControls::pitchRatioChanged,
            deck_, &Deck::setPitchRatio);
    connect(pitchSpeed_, &PitchSpeedControls::baseRateChanged,
            deck_, &Deck::setBaseRate);
    connect(pitchSpeed_, &PitchSpeedControls::playbackModeChanged,
            deck_, &Deck::setPlaybackMode);
    connect(deck_, &Deck::stateChanged, loopBar_, &LoopMarkerBar::syncFromState);

    // --- Marker hotkeys: M drop, ',' prev, '.' next ---
    auto* addMk = new QShortcut(QKeySequence(Qt::Key_M), this);
    connect(addMk, &QShortcut::activated, this, [this] {
        if (deck_->audio()) deck_->addMarkerAtPlayhead();
    });
    auto* prevMk = new QShortcut(QKeySequence(Qt::Key_Comma), this);
    connect(prevMk, &QShortcut::activated, this, [this] { deck_->jumpToPrevMarker(); });
    auto* nextMk = new QShortcut(QKeySequence(Qt::Key_Period), this);
    connect(nextMk, &QShortcut::activated, this, [this] { deck_->jumpToNextMarker(); });

    // --- Spacebar toggles play/pause (mirrors the TransportBar button) ---
    auto* playPause = new QShortcut(QKeySequence(Qt::Key_Space), this);
    playPause->setContext(Qt::WindowShortcut);
    connect(playPause, &QShortcut::activated, this, [this] { deck_->togglePlay(); });

    // --- Instant Replay (rolling capture): the strip only emits; wire to the deck ---
    connect(instantReplay_, &InstantReplayControls::captureEnabledChanged,
            deck_, &Deck::setCaptureEnabled);
    // The deck reports the ACTUAL armed state back: a failed loopback start comes
    // back as active==false while the toggle still shows armed — reflect reality
    // (uncheck, no re-emit thanks to the guard in setCaptureEnabled) and warn the
    // user. A normal disable also reports false, but the toggle is already
    // unchecked by then, so no spurious message fires.
    connect(deck_, &Deck::captureActiveChanged, this, [this](bool active) {
        const bool wasArmed = instantReplay_->captureEnabled();
        instantReplay_->setCaptureEnabled(active);
        if (!active && wasArmed)
            statusBar()->showMessage("Instant Replay: capture device failed to start", 4000);
    });
    connect(instantReplay_, &InstantReplayControls::clipRequested,
            this, &MainWindow::clipInstantReplay);
    // ...and the same action on a configurable hotkey (Capture Settings). A
    // window-focused QShortcut whose key is (re)loaded from QSettings; seeded now so
    // the default fires even before the user opens Settings.
    clipShortcut_ = new QShortcut(this);
    clipShortcut_->setContext(Qt::WindowShortcut);
    connect(clipShortcut_, &QShortcut::activated, this, &MainWindow::clipInstantReplay);
    updateClipHotkey();
    connect(instantReplay_, &InstantReplayControls::openSettingsRequested, this, [this] {
        // Fresh dialog per open so its constructor's load() always reflects the
        // current QSettings; settingsChanged() then re-reads them into the live path.
        SettingsDialog dlg(this);
        connect(&dlg, &SettingsDialog::settingsChanged, this, [this] {
            deck_->applyCaptureSettings();
            instantReplay_->setBufferSeconds(
                QSettings().value("capture/bufferSeconds", 30).toInt());
            updateClipHotkey();
        });
        dlg.exec();
    });

    auto* fileMenu = menuBar()->addMenu("&File");
    auto* openAct = new QAction("&Open…", this);
    openAct->setShortcut(QKeySequence::Open);
    connect(openAct, &QAction::triggered, this, &MainWindow::openFile);
    fileMenu->addAction(openAct);
    fileMenu->addSeparator();
    auto* quitAct = new QAction("&Quit", this);
    quitAct->setShortcut(QKeySequence::Quit);
    connect(quitAct, &QAction::triggered, this, &QWidget::close);
    fileMenu->addAction(quitAct);

    connect(deck_, &Deck::loaded, this, [this] {
        waveform_->onMediaLoaded();
        pitchSpeed_->resetToDefaults();      // 0 st / 1.0x / pitch-preserve
        loopBar_->syncFromState();           // clear A/B + markers in the UI
        statusBar()->showMessage(
            QString("Loaded — %1 s%2")
                .arg(deck_->durationSeconds(), 0, 'f', 2)
                .arg(deck_->hasVideo() ? " · video" : " · audio only"));
    });

    // Seed the Instant Replay strip from persisted state and (re)arm capture to
    // match. setCaptureEnabled() on the strip only reflects the toggle (no re-emit),
    // so drive the deck explicitly to actually start/stop the rolling capture.
    {
        QSettings s;
        instantReplay_->setBufferSeconds(s.value("capture/bufferSeconds", 30).toInt());
        const bool armed = s.value("capture/enabled", false).toBool();
        instantReplay_->setCaptureEnabled(armed);
        deck_->setCaptureEnabled(armed);
    }

    if (!deck_->start())
        statusBar()->showMessage("Audio device failed to open");
    else
        statusBar()->showMessage("Ready — open a file to scratch");
}

MainWindow::~MainWindow() = default;

void MainWindow::syncScrollBar() {
    if (!waveform_ || !hScroll_ || !deck_) return;
    const double spp = waveform_->samplesPerPixel();
    const long long total = static_cast<long long>(deck_->durationFrames());
    const int page = std::max(1, waveform_->width());
    if (total <= 0 || spp <= 0.0) {
        QSignalBlocker b(hScroll_);
        hScroll_->setRange(0, 0);
        hScroll_->setEnabled(false);
        return;
    }
    const long long contentPx = std::llround(total / spp);
    const int maxv = static_cast<int>(std::max(0LL, contentPx - page));
    const int val  = static_cast<int>(std::llround(waveform_->scrollOffsetFrames() / spp));
    QSignalBlocker b(hScroll_);
    hScroll_->setPageStep(page);
    hScroll_->setSingleStep(std::max(1, page / 10));
    hScroll_->setRange(0, maxv);
    hScroll_->setValue(std::clamp(val, 0, maxv));
    hScroll_->setEnabled(maxv > 0);
}

void MainWindow::clipInstantReplay() {
    // Shared by the "Clip last Ns" button and the Instant Replay hotkey.
    if (deck_->clipLastSeconds())
        statusBar()->showMessage("Saved instant-replay clip", 3000);
    else
        statusBar()->showMessage("Nothing captured yet — arm Instant Replay first", 3000);
}

void MainWindow::updateClipHotkey() {
    if (!clipShortcut_) return;
    // Default matches SettingsDialog's kDefaultClipHotkey; an empty stored value
    // (user pressed Clear) yields a null sequence, which disables the shortcut.
    const QString hk = QSettings().value("capture/clipHotkey", "Ctrl+Alt+R").toString();
    clipShortcut_->setKey(QKeySequence(hk, QKeySequence::PortableText));
}

void MainWindow::openFile() {
    const QString path = QFileDialog::getOpenFileName(
        this, "Open audio or video",
        QString(),
        "Media (*.mp4 *.mkv *.mov *.webm *.mp3 *.wav *.flac *.m4a *.aac *.ogg *.opus);;All files (*)");
    if (!path.isEmpty()) loadFile(path);
}

bool MainWindow::loadFile(const QString& path) {
    const bool ok = deck_->load(path);
    if (ok) setWindowTitle(QString("AudioScratch — %1").arg(QFileInfo(path).fileName()));
    else statusBar()->showMessage("Failed to load " + path);
    return ok;
}

} // namespace as
