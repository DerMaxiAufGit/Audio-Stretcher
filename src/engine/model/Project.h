#pragma once
// Project-wide canonical units and rate (plan §6.5).
//
// The project sample rate is the ONE canonical audio rate: every source is
// resampled to it on decode, and ALL audio/source/timeline positions are in
// project-rate frames (`frame_t`). The audio device may run at a different rate;
// the engine resamples its project-rate output to the device rate at the output
// boundary. Positions are therefore device-independent.

#include <cstdint>
#include <cmath>

namespace as {

using frame_t = std::int64_t;               // integer frames (project rate)
using MediaId = std::uint64_t;

inline constexpr int kProjectSampleRate = 48000;  // default Project::sampleRate

struct Project {
    int sampleRate = kProjectSampleRate;    // Hz (int, per §6.5)
};

// Frames <-> seconds MUST use double division (never integer division, which
// would truncate sub-second offsets) — §6.5.
inline double framesToSeconds(frame_t frames, int sampleRate) {
    return static_cast<double>(frames) / sampleRate;
}
inline frame_t secondsToFrames(double seconds, int sampleRate) {
    return static_cast<frame_t>(std::llround(seconds * sampleRate));
}

} // namespace as
