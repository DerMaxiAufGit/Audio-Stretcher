#pragma once
// Dependency-free 32-bit IEEE-float WAV writer (ShadowPlay export).
//
// Writes a canonical RIFF/WAVE file with a WAVE_FORMAT_IEEE_FLOAT `fmt ` chunk and
// a `data` chunk holding the interleaved float32 samples verbatim (no conversion).
// Used to persist a captured snapshot without pulling FFmpeg into the save path.

#include <cstdint>
#include <string>

namespace as {

// Write `frames` interleaved float32 frames (channels floats each) to a 32-bit
// IEEE-float WAV at `path`. Returns false on any I/O error (or invalid arguments).
bool writeWav(const std::string& path, const float* interleaved,
              std::int64_t frames, int channels, int sampleRate);

} // namespace as
