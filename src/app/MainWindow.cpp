#include "app/MainWindow.h"

#include <QAction>
#include <QFileDialog>
#include <QFileInfo>
#include <QMenuBar>
#include <QStatusBar>
#include <QVBoxLayout>
#include <QWidget>

#include "app/Deck.h"
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
    transport_ = new TransportBar(this);
    video_->setDeck(deck_);
    waveform_->setDeck(deck_);
    transport_->setDeck(deck_);

    auto* central = new QWidget(this);
    auto* layout = new QVBoxLayout(central);
    layout->setContentsMargins(0, 0, 0, 0);
    layout->setSpacing(0);
    layout->addWidget(video_, 3);
    layout->addWidget(waveform_, 1);
    layout->addWidget(transport_, 0);
    setCentralWidget(central);

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
        statusBar()->showMessage(
            QString("Loaded — %1 s%2")
                .arg(deck_->durationSeconds(), 0, 'f', 2)
                .arg(deck_->hasVideo() ? " · video" : " · audio only"));
    });

    if (!deck_->start())
        statusBar()->showMessage("Audio device failed to open");
    else
        statusBar()->showMessage("Ready — open a file to scratch");
}

MainWindow::~MainWindow() = default;

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
