#pragma once
// The "deck": app-level owner that wires decode + scrub engine + audio device +
// video scrubber + waveform peaks together and exposes transport control to the
// UI (Phase 1 integration). Keeps the previously-loaded DecodedAudio alive one
// generation so the RT thread never reads freed PCM across a media swap.

#include <memory>

#include <QObject>
#include <QString>

#include "engine/audio/AudioCapture.h"
#include "engine/audio/AudioDevice.h"
#include "engine/audio/ScrubEngine.h"
#include "engine/audio/WaveformPeaks.h"
#include "engine/decode/DecodedAudio.h"
#include "engine/model/DeckState.h"
#include "engine/model/Project.h"
#include "engine/video/VideoScrubber.h"

namespace as {

class Deck : public QObject {
    Q_OBJECT
public:
    explicit Deck(QObject* parent = nullptr);
    ~Deck() override;

    bool start();                          // open the audio device (plays silence)
    bool load(const QString& path);        // decode + wire a new clip

    // Transport (thread-safe passthrough to the engine).
    void setPlaying(bool p)         { engine_.setPlaying(p); }
    void togglePlay()               { engine_.setPlaying(!engine_.playing()); }
    bool playing() const            { return engine_.playing(); }
    void setScrubbing(bool s)       { engine_.setScrubbing(s); }
    bool scrubbing() const          { return engine_.scrubbing(); }
    void setTargetSeconds(double s) { engine_.setTargetSeconds(s); }
    void seekSeconds(double s)      { engine_.seekSeconds(s); }

    double publishedSeconds() const { return engine_.publishedSeconds(); }
    double durationSeconds() const  { return engine_.durationSeconds(); }

    // --- Phase 2 performance controls: update the model AND the engine control
    //     block. The UI calls these; it never touches the audio thread directly. ---
    void setPitchRatio(float ratio)  { engine_.setPitchRatio(ratio); }
    void setBaseRate(float rate)     { engine_.setBaseRate(rate); }
    void setPlaybackMode(PlaybackMode m);

    // Master output gain (linear, 0..2; 1 = unity). Thread-safe passthrough — the
    // TransportBar volume slider / mute toggle drive these; the engine applies the
    // gain on the RT thread as a lock-free atomic store (see ScrubEngine::setGain).
    void setGain(float g)            { engine_.setGain(g); }
    float gain() const               { return engine_.gain(); }

    void setLoop(frame_t begin, frame_t end);
    void setLoopEnabled(bool on);

    uint32_t addMarker(frame_t frame, const QString& label = {});
    void     addMarkerAtPlayhead();
    void     removeMarker(uint32_t id);
    void     renameMarker(uint32_t id, const QString& label);

    void jumpToFrame(frame_t frame);       // discrete glitch-free seek
    void jumpToNextMarker();
    void jumpToPrevMarker();

    // --- Instant Replay: rolling desktop-loopback capture (ShadowPlay-style) ---
    void setCaptureEnabled(bool on);       // arm/disarm the rolling capture (persists state)
    void applyCaptureSettings();           // re-read QSettings; restart capture if running
    bool clipLastSeconds();                // snapshot last N s -> .wav + load into editor

    // Playhead / units helpers for the UI.
    int      sampleRate() const;
    frame_t  currentFrame() const;
    frame_t  durationFrames() const;

    const DeckState& state() const    { return state_; }

    const DecodedAudio* audio() const { return current_.get(); }
    WaveformPeaks* peaks()            { return &peaks_; }
    VideoScrubber* video()            { return &video_; }
    ScrubEngine*   engine()           { return &engine_; }
    bool hasVideo() const             { return video_.hasVideo(); }

signals:
    void loaded();                         // a new clip finished loading
    void stateChanged();                   // loop / markers / mode edited (UI redraw)
    void captureActiveChanged(bool active);// rolling capture's ACTUAL armed state (start ok?)

private:
    DeckState state_;                      // authoritative UI-thread session state

    ScrubEngine   engine_;
    AudioDevice   device_;
    WaveformPeaks peaks_;
    VideoScrubber video_;

    // Rolling background capture for Instant Replay (owned; created in the ctor,
    // stopped in the dtor). Independent of the playback device/engine above.
    std::unique_ptr<AudioCapture> capture_;

    // shared_ptr (not unique_ptr): the RT thread needs the previous buffer alive
    // one generation across a swap, AND the async peaks worker captures a copy so
    // a long reduction survives rapid successive loads (no use-after-free).
    std::shared_ptr<DecodedAudio> current_;
    std::shared_ptr<DecodedAudio> previous_;
};

} // namespace as
