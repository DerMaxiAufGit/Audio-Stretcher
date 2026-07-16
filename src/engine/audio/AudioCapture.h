#pragma once
// Desktop-loopback (+ optional mic) capture into a rolling RingBuffer — the
// "ShadowPlay" core (plan: capture the last N seconds on demand).
//
// Desktop audio is ALWAYS the primary source while capturing. On Windows this uses
// miniaudio's WASAPI loopback (ma_device_type_loopback). That device type is WASAPI-
// only, so on Linux (PulseAudio/PipeWire) and other backends we instead open a normal
// capture device on the default output's ".monitor" source, which mirrors what's
// playing — see start()/findDesktopMonitorSource in the .cpp. A microphone
// (ma_device_type_capture) can optionally be summed in. Everything is normalised to
// 48 kHz stereo float32 (let miniaudio convert) and stored in ONE rolling RingBuffer
// holding the most recent `bufferSeconds`. The UI thread pulls a snapshot to save.
//
// Threading: the desktop-capture callback is the master clock — it writes its block
// into the main ring and, when the mic is enabled, pulls the same frame count from a
// small intermediate mic ring and sums it (best-effort mix; minor drift between the
// two device clocks is an accepted v1 limitation). Capture callbacks only write to
// preallocated rings — no locks, no allocation.

#include <cstddef>
#include <memory>
#include <string>
#include <vector>

namespace as {

class AudioCapture {
public:
    struct Config {
        int         bufferSeconds = 30;      // length of the rolling window
        bool        captureMic    = false;   // sum a microphone into the desktop audio
        std::string micDeviceId;             // input device (id from listInputDevices)
    };

    // A capture (input) device for the settings mic dropdown. `id` is a stable
    // string encoding of miniaudio's ma_device_id; `name` is human-readable.
    struct DeviceInfo {
        std::string id;
        std::string name;
    };

    AudioCapture();
    ~AudioCapture();

    AudioCapture(const AudioCapture&) = delete;
    AudioCapture& operator=(const AudioCapture&) = delete;

    // Open the desktop-capture device (WASAPI loopback on Windows, else the default
    // output's monitor source) and, if requested, the mic, then begin the rolling
    // capture. Returns false only if the desktop device fails to init/start; a mic
    // failure degrades gracefully to desktop-only.
    bool start(const Config& config);
    void stop();
    bool running() const;

    // Copy the most recent `seconds` of captured audio (chronological order) into
    // `outInterleaved` (resized to hold exactly the frames returned). Returns the
    // number of frames copied. Runs on the UI thread; may allocate.
    std::size_t snapshotLastSeconds(double seconds, std::vector<float>& outInterleaved) const;

    int channels()   const { return 2; }
    int sampleRate() const;

    // Enumerate available capture (input) devices for the settings mic dropdown.
    static std::vector<DeviceInfo> listInputDevices();

    struct Impl;   // defined in the .cpp (holds the miniaudio + ring state)

private:
    std::unique_ptr<Impl> d_;
};

} // namespace as
