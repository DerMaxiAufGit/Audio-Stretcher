#include "engine/audio/AudioCapture.h"

#include <atomic>
#include <cstdio>
#include <cstring>

#include "engine/audio/RingBuffer.h"
#include "engine/model/Project.h"

// miniaudio: same include recipe as AudioDevice.cpp, but WITHOUT
// MINIAUDIO_IMPLEMENTATION — that TU already provides the single implementation.
#define MA_NO_ENCODING
#define MA_NO_DECODING
#define MA_NO_GENERATION
#include <miniaudio.h>

namespace as {

// State + miniaudio handles. Declared in the header (public) so the static device
// callbacks below can name it; every member is public (plain struct) so those
// callbacks need no friend/accessor dance.
struct AudioCapture::Impl {
    ma_context context{};
    bool contextReady = false;

    ma_device loopbackDevice{};   // desktop audio (master clock)
    bool loopbackReady = false;

    ma_device micDevice{};        // optional microphone
    bool micReady = false;

    std::unique_ptr<RingBuffer> mainRing;   // desktop (+ mic) mix, bufferSeconds long
    std::unique_ptr<RingBuffer> micRing;    // intermediate SPSC mic feed (~1 s)

    // Preallocated so the loopback callback never allocates when mixing the mic.
    std::vector<float> mixScratch;          // desktop + mic sum, scratchFrames * 2 floats
    std::vector<float> micScratch;          // mic pull target, scratchFrames * 2 floats
    std::size_t scratchFrames = 0;          // max block size the mix path can handle

    int  sampleRate = kProjectSampleRate;
    bool captureMic = false;                // set before device start / cleared after stop
    std::atomic<bool> running{false};

    // Desktop-loopback callback: writes its block into the main ring, summing in the
    // most recent mic frames when enabled. Runs on miniaudio's device thread.
    static void loopbackCallback(ma_device* dev, void* /*pOutput*/,
                                 const void* pInput, ma_uint32 frameCount) {
        auto* impl = static_cast<Impl*>(dev->pUserData);
        if (!impl || !impl->mainRing || !pInput) return;
        const float* desktop = static_cast<const float*>(pInput);

        if (!impl->captureMic || !impl->micRing) {
            impl->mainRing->write(desktop, frameCount);
            return;
        }

        const std::size_t frames = frameCount;
        // Oversized block beyond our scratch (does not happen with normal WASAPI
        // periods): fall back to desktop-only for this block.
        if (frames > impl->scratchFrames) {
            impl->mainRing->write(desktop, frames);
            return;
        }

        const std::size_t ch = 2;
        float* mix = impl->mixScratch.data();
        std::memcpy(mix, desktop, frames * ch * sizeof(float));

        // Pull the most recent `frames` mic frames and sum them onto the tail of the
        // block (best-effort time alignment; small drift between the two device
        // clocks is an accepted v1 limitation).
        const std::size_t got = impl->micRing->readLast(impl->micScratch.data(), frames);
        if (got) {
            const float* mic = impl->micScratch.data();
            float* dst = mix + (frames - got) * ch;
            const std::size_t n = got * ch;
            for (std::size_t i = 0; i < n; ++i) dst[i] += mic[i];
        }
        impl->mainRing->write(mix, frames);
    }

    // Microphone callback: feeds the intermediate mic ring only.
    static void micCallback(ma_device* dev, void* /*pOutput*/,
                            const void* pInput, ma_uint32 frameCount) {
        auto* impl = static_cast<Impl*>(dev->pUserData);
        if (impl && impl->micRing && pInput)
            impl->micRing->write(static_cast<const float*>(pInput), frameCount);
    }
};

namespace {

// Encode a raw ma_device_id as lowercase hex so the settings dialog can round-trip a
// device selection through a plain std::string. WASAPI ids are persistent strings, so
// the encoding is stable across runs.
std::string encodeDeviceId(const ma_device_id& id) {
    static const char* kHex = "0123456789abcdef";
    const auto* p = reinterpret_cast<const unsigned char*>(&id);
    std::string s;
    s.reserve(sizeof(ma_device_id) * 2);
    for (std::size_t i = 0; i < sizeof(ma_device_id); ++i) {
        s.push_back(kHex[p[i] >> 4]);
        s.push_back(kHex[p[i] & 0x0F]);
    }
    return s;
}

bool decodeDeviceId(const std::string& s, ma_device_id& out) {
    if (s.size() != sizeof(ma_device_id) * 2) return false;
    auto nibble = [](char c) -> int {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    };
    auto* p = reinterpret_cast<unsigned char*>(&out);
    for (std::size_t i = 0; i < sizeof(ma_device_id); ++i) {
        const int hi = nibble(s[i * 2]);
        const int lo = nibble(s[i * 2 + 1]);
        if (hi < 0 || lo < 0) return false;
        p[i] = static_cast<unsigned char>((hi << 4) | lo);
    }
    return true;
}

} // namespace

AudioCapture::AudioCapture() : d_(std::make_unique<Impl>()) {}
AudioCapture::~AudioCapture() { stop(); }

bool AudioCapture::start(const Config& config) {
    stop();

    d_->sampleRate = kProjectSampleRate;
    d_->captureMic = config.captureMic;

    const int seconds = config.bufferSeconds > 0 ? config.bufferSeconds : 30;
    const std::size_t capFrames =
        static_cast<std::size_t>(seconds) * static_cast<std::size_t>(d_->sampleRate);
    d_->mainRing = std::make_unique<RingBuffer>(capFrames, 2);

    if (config.captureMic) {
        // ~1 s intermediate ring is ample to bridge mic/loopback callback jitter.
        d_->micRing = std::make_unique<RingBuffer>(
            static_cast<std::size_t>(d_->sampleRate), 2);
        d_->scratchFrames = static_cast<std::size_t>(d_->sampleRate);   // 1 s max mix block
        d_->mixScratch.assign(d_->scratchFrames * 2, 0.0f);
        d_->micScratch.assign(d_->scratchFrames * 2, 0.0f);
    }

    if (ma_context_init(nullptr, 0, nullptr, &d_->context) != MA_SUCCESS) {
        std::fprintf(stderr, "[capture] ma_context_init failed\n");
        stop();
        return false;
    }
    d_->contextReady = true;

    // --- Mic capture (optional) — start first so frames are queued before the
    //     loopback master clock begins pulling from the mic ring. ---
    if (config.captureMic) {
        ma_device_config micCfg = ma_device_config_init(ma_device_type_capture);
        micCfg.capture.format   = ma_format_f32;
        micCfg.capture.channels = 2;
        micCfg.sampleRate       = static_cast<ma_uint32>(d_->sampleRate);
        micCfg.dataCallback     = &Impl::micCallback;
        micCfg.pUserData        = d_.get();

        ma_device_id micId;
        if (!config.micDeviceId.empty() && decodeDeviceId(config.micDeviceId, micId))
            micCfg.capture.pDeviceID = &micId;

        if (ma_device_init(&d_->context, &micCfg, &d_->micDevice) == MA_SUCCESS) {
            d_->micReady = true;
            if (ma_device_start(&d_->micDevice) != MA_SUCCESS) {
                std::fprintf(stderr, "[capture] mic ma_device_start failed; desktop-only\n");
                ma_device_uninit(&d_->micDevice);
                d_->micReady = false;
                d_->captureMic = false;
            }
        } else {
            std::fprintf(stderr, "[capture] mic ma_device_init failed; desktop-only\n");
            d_->captureMic = false;
        }
    }

    // --- Desktop loopback (always primary; master clock) ---
    ma_device_config loopCfg = ma_device_config_init(ma_device_type_loopback);
    loopCfg.capture.format   = ma_format_f32;
    loopCfg.capture.channels = 2;
    loopCfg.sampleRate       = static_cast<ma_uint32>(d_->sampleRate);
    loopCfg.dataCallback     = &Impl::loopbackCallback;
    loopCfg.pUserData        = d_.get();

    if (ma_device_init(&d_->context, &loopCfg, &d_->loopbackDevice) != MA_SUCCESS) {
        std::fprintf(stderr, "[capture] loopback ma_device_init failed\n");
        stop();
        return false;
    }
    d_->loopbackReady = true;

    if (ma_device_start(&d_->loopbackDevice) != MA_SUCCESS) {
        std::fprintf(stderr, "[capture] loopback ma_device_start failed\n");
        stop();
        return false;
    }

    d_->running.store(true, std::memory_order_release);
    std::fprintf(stderr, "[capture] started: %d s window, %d Hz stereo%s\n",
                 seconds, d_->sampleRate, d_->captureMic ? " + mic" : "");
    return true;
}

void AudioCapture::stop() {
    // Uninit devices first (joins their callback threads) BEFORE touching shared
    // state, so no callback can race the teardown below.
    if (d_->loopbackReady) {
        ma_device_uninit(&d_->loopbackDevice);
        d_->loopbackReady = false;
    }
    if (d_->micReady) {
        ma_device_uninit(&d_->micDevice);
        d_->micReady = false;
    }
    if (d_->contextReady) {
        ma_context_uninit(&d_->context);
        d_->contextReady = false;
    }
    d_->captureMic = false;
    d_->running.store(false, std::memory_order_release);
    // The rings are intentionally kept alive so a snapshot taken right after stop()
    // still sees the captured window; start() (or the dtor) releases them.
}

bool AudioCapture::running() const {
    return d_->running.load(std::memory_order_acquire);
}

int AudioCapture::sampleRate() const { return d_->sampleRate; }

std::size_t AudioCapture::snapshotLastSeconds(double seconds,
                                              std::vector<float>& outInterleaved) const {
    outInterleaved.clear();
    if (!d_->mainRing || seconds <= 0.0) return 0;

    const std::size_t cap = d_->mainRing->capacityFrames();
    std::size_t wanted = static_cast<std::size_t>(seconds * d_->sampleRate);
    if (wanted > cap) wanted = cap;
    if (wanted == 0) return 0;

    outInterleaved.resize(wanted * 2);
    const std::size_t got = d_->mainRing->readLast(outInterleaved.data(), wanted);
    outInterleaved.resize(got * 2);
    return got;
}

std::vector<AudioCapture::DeviceInfo> AudioCapture::listInputDevices() {
    std::vector<DeviceInfo> out;

    ma_context ctx;
    if (ma_context_init(nullptr, 0, nullptr, &ctx) != MA_SUCCESS) return out;

    ma_device_info* playback = nullptr; ma_uint32 playbackCount = 0;
    ma_device_info* capture  = nullptr; ma_uint32 captureCount  = 0;
    if (ma_context_get_devices(&ctx, &playback, &playbackCount,
                               &capture, &captureCount) == MA_SUCCESS) {
        out.reserve(captureCount);
        for (ma_uint32 i = 0; i < captureCount; ++i) {
            DeviceInfo di;
            di.id   = encodeDeviceId(capture[i].id);
            di.name = capture[i].name;
            out.push_back(std::move(di));
        }
    }

    ma_context_uninit(&ctx);
    return out;
}

} // namespace as
