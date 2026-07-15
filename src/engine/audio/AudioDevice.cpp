#include "engine/audio/AudioDevice.h"

#include <cstdio>
#include <cstring>

#define MINIAUDIO_IMPLEMENTATION
#define MA_NO_ENCODING
#define MA_NO_DECODING
#define MA_NO_GENERATION
#include <miniaudio.h>

namespace as {

struct AudioDevice::Impl {
    ma_device device{};
    ScrubEngine* engine = nullptr;
    bool initialised = false;
};

namespace {
void dataCallback(ma_device* dev, void* pOutput, const void* /*pInput*/,
                  ma_uint32 frameCount) {
    auto* engine = static_cast<ScrubEngine*>(dev->pUserData);
    if (engine)
        engine->render(static_cast<float*>(pOutput), static_cast<int>(frameCount));
    else
        std::memset(pOutput, 0, static_cast<std::size_t>(frameCount) * 2 * sizeof(float));
}
}

AudioDevice::AudioDevice() : d_(std::make_unique<Impl>()) {}
AudioDevice::~AudioDevice() { stop(); }

bool AudioDevice::start(ScrubEngine* engine, StretcherKind kind,
                        int requestedRate, int bufferFrames) {
    stop();
    d_->engine = engine;

    ma_device_config config = ma_device_config_init(ma_device_type_playback);
    config.playback.format = ma_format_f32;
    config.playback.channels = 2;
    config.sampleRate = static_cast<ma_uint32>(requestedRate);
    config.periodSizeInFrames = static_cast<ma_uint32>(bufferFrames);
    config.dataCallback = &dataCallback;
    config.pUserData = engine;

    if (ma_device_init(nullptr, &config, &d_->device) != MA_SUCCESS) {
        std::fprintf(stderr, "[audio] ma_device_init failed\n");
        d_->engine = nullptr;
        return false;
    }
    d_->initialised = true;

    sampleRate_ = static_cast<int>(d_->device.sampleRate);
    bufferFrames_ = static_cast<int>(d_->device.playback.internalPeriodSizeInFrames);
    if (bufferFrames_ <= 0) bufferFrames_ = bufferFrames;

    // Prepare the engine now that the actual device rate/period are known.
    engine->prepare(sampleRate_, bufferFrames_, kind);

    if (ma_device_start(&d_->device) != MA_SUCCESS) {
        std::fprintf(stderr, "[audio] ma_device_start failed\n");
        ma_device_uninit(&d_->device);
        d_->initialised = false;
        d_->engine = nullptr;
        return false;
    }
    running_ = true;
    std::fprintf(stderr, "[audio] device started: %d Hz, period %d frames\n",
                 sampleRate_, bufferFrames_);
    return true;
}

void AudioDevice::stop() {
    if (d_->initialised) {
        ma_device_uninit(&d_->device);
        d_->initialised = false;
    }
    running_ = false;
    d_->engine = nullptr;
}

} // namespace as
