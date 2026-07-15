#include "engine/io/WavWriter.h"

#include <fstream>
#include <ostream>

namespace as {
namespace {

// RIFF is little-endian; write explicitly so the output is host-endian-independent.
void putU32(std::ostream& os, std::uint32_t v) {
    const char b[4] = {
        static_cast<char>(v & 0xFF), static_cast<char>((v >> 8) & 0xFF),
        static_cast<char>((v >> 16) & 0xFF), static_cast<char>((v >> 24) & 0xFF)};
    os.write(b, 4);
}
void putU16(std::ostream& os, std::uint16_t v) {
    const char b[2] = {static_cast<char>(v & 0xFF), static_cast<char>((v >> 8) & 0xFF)};
    os.write(b, 2);
}

} // namespace

bool writeWav(const std::string& path, const float* interleaved,
              std::int64_t frames, int channels, int sampleRate) {
    if (channels <= 0 || sampleRate <= 0 || frames < 0) return false;
    if (frames > 0 && interleaved == nullptr) return false;

    std::ofstream os(path, std::ios::binary | std::ios::trunc);
    if (!os) return false;

    constexpr std::uint32_t kBytesPerSample = 4;                 // 32-bit float
    const std::uint32_t blockAlign = static_cast<std::uint32_t>(channels) * kBytesPerSample;
    const std::uint64_t dataBytes64 = static_cast<std::uint64_t>(frames) * blockAlign;
    const std::uint32_t dataBytes = static_cast<std::uint32_t>(dataBytes64);  // WAV sizes are 32-bit
    const std::uint32_t byteRate = static_cast<std::uint32_t>(sampleRate) * blockAlign;

    // RIFF / WAVE header.
    os.write("RIFF", 4);
    putU32(os, 36u + dataBytes);            // total file size - 8
    os.write("WAVE", 4);

    // fmt  chunk (16 bytes, WAVE_FORMAT_IEEE_FLOAT = 3).
    os.write("fmt ", 4);
    putU32(os, 16u);                        // fmt chunk size
    putU16(os, 3);                          // audio format: IEEE float
    putU16(os, static_cast<std::uint16_t>(channels));
    putU32(os, static_cast<std::uint32_t>(sampleRate));
    putU32(os, byteRate);
    putU16(os, static_cast<std::uint16_t>(blockAlign));
    putU16(os, static_cast<std::uint16_t>(kBytesPerSample * 8));  // bits per sample = 32

    // data chunk.
    os.write("data", 4);
    putU32(os, dataBytes);
    if (dataBytes64 > 0) {
        os.write(reinterpret_cast<const char*>(interleaved),
                 static_cast<std::streamsize>(dataBytes64));
    }

    os.flush();
    return static_cast<bool>(os);
}

} // namespace as
