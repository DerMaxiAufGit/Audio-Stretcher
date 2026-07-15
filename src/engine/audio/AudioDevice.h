#pragma once
// miniaudio real-time playback device (plan §6.4, Phase 1 Task 5).
//
// Opens a true low-latency RT callback (WASAPI/ASIO on Windows, ALSA/JACK/Pulse
// on Linux) — deliberately bypassing Qt's QAudioSink (~250 ms buffering, unfit
// for scrub latency). The device is requested at the project rate so the engine
// runs passthrough; the callback forwards straight to ScrubEngine::render().

#include <memory>

#include "engine/audio/ScrubEngine.h"

namespace as {

class AudioDevice {
public:
    AudioDevice();
    ~AudioDevice();

    // Initialise + start playback, wiring the callback to `engine`. Prepares the
    // engine at the device's actual rate/period. Returns false on failure.
    bool start(ScrubEngine* engine, StretcherKind kind = StretcherKind::Bungee,
               int requestedRate = kProjectSampleRate, int bufferFrames = 256);
    void stop();

    bool running() const { return running_; }
    int  sampleRate() const { return sampleRate_; }
    int  bufferFrames() const { return bufferFrames_; }

    // Total output-buffer latency in device frames (period size * period count):
    // audio handed to the callback plays this many frames AFTER it is generated, so
    // the UI subtracts it to place the visual playhead on the sample being heard.
    int  latencyFrames() const { return latencyFrames_; }

private:
    struct Impl;
    std::unique_ptr<Impl> d_;
    bool running_ = false;
    int  sampleRate_ = kProjectSampleRate;
    int  bufferFrames_ = 256;
    int  latencyFrames_ = 0;
};

} // namespace as
