#include "selftest/SelfTest.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <new>
#include <string>
#include <vector>

#include "engine/audio/BungeeStretcher.h"
#include "engine/audio/IStretcher.h"
#include "engine/audio/ScrubEngine.h"
#include "engine/decode/AudioDecoder.h"
#include "engine/decode/DecodedAudio.h"
#include "engine/decode/MediaDecoder.h"
#include "engine/model/Project.h"

// ---------------------------------------------------------------------------
// Global allocation guard: when armed, counts EVERY heap allocation on the audio
// render path (NFR-A proof). To catch allocations that bypass operator new — in
// particular Eigen/Bungee, which use malloc/posix_memalign directly — we intercept
// the whole C allocator family via the linker's --wrap (see CMakeLists), AND
// override operator new (libstdc++'s new bypasses --wrap) routing it through
// __real_malloc so it is counted exactly once. Everything forwards to the real
// allocator, so behaviour is otherwise identical to the defaults.
// ---------------------------------------------------------------------------
extern "C" {
void* __real_malloc(std::size_t);
void  __real_free(void*);
void* __real_calloc(std::size_t, std::size_t);
void* __real_realloc(void*, std::size_t);
int   __real_posix_memalign(void**, std::size_t, std::size_t);
void* __real_aligned_alloc(std::size_t, std::size_t);
}

namespace {
std::atomic<bool> g_rtGuard{false};
std::atomic<long> g_rtAllocs{0};
inline void countAlloc() {
    if (g_rtGuard.load(std::memory_order_relaxed))
        g_rtAllocs.fetch_add(1, std::memory_order_relaxed);
}
inline void* allocAligned(std::size_t n, std::size_t align) {
    if (align < sizeof(void*)) align = sizeof(void*);
    void* p = nullptr;
    if (__real_posix_memalign(&p, align, n ? n : align) != 0) return nullptr;
    return p;
}
}

// C allocator family (catches Eigen/Bungee).
extern "C" void* __wrap_malloc(std::size_t n) { countAlloc(); return __real_malloc(n); }
extern "C" void  __wrap_free(void* p) { __real_free(p); }
extern "C" void* __wrap_calloc(std::size_t a, std::size_t b) { countAlloc(); return __real_calloc(a, b); }
extern "C" void* __wrap_realloc(void* p, std::size_t n) { countAlloc(); return __real_realloc(p, n); }
extern "C" int   __wrap_posix_memalign(void** m, std::size_t al, std::size_t n) { countAlloc(); return __real_posix_memalign(m, al, n); }
extern "C" void* __wrap_aligned_alloc(std::size_t al, std::size_t n) { countAlloc(); return __real_aligned_alloc(al, n); }

// operator new family (routes through __real_* to avoid double-counting via --wrap).
void* operator new(std::size_t n) { countAlloc(); void* p = __real_malloc(n ? n : 1); if (!p) throw std::bad_alloc(); return p; }
void* operator new[](std::size_t n) { countAlloc(); void* p = __real_malloc(n ? n : 1); if (!p) throw std::bad_alloc(); return p; }
void* operator new(std::size_t n, std::align_val_t a) { countAlloc(); void* p = allocAligned(n, static_cast<std::size_t>(a)); if (!p) throw std::bad_alloc(); return p; }
void* operator new[](std::size_t n, std::align_val_t a) { countAlloc(); void* p = allocAligned(n, static_cast<std::size_t>(a)); if (!p) throw std::bad_alloc(); return p; }
void operator delete(void* p) noexcept { __real_free(p); }
void operator delete[](void* p) noexcept { __real_free(p); }
void operator delete(void* p, std::size_t) noexcept { __real_free(p); }
void operator delete[](void* p, std::size_t) noexcept { __real_free(p); }
void operator delete(void* p, std::align_val_t) noexcept { __real_free(p); }
void operator delete[](void* p, std::align_val_t) noexcept { __real_free(p); }
void operator delete(void* p, std::size_t, std::align_val_t) noexcept { __real_free(p); }
void operator delete[](void* p, std::size_t, std::align_val_t) noexcept { __real_free(p); }

namespace as {
namespace {

int g_failures = 0;
void check(bool ok, const char* name, const char* detail = "") {
    std::printf("  [%s] %s%s%s\n", ok ? "PASS" : "FAIL", name,
                detail[0] ? " — " : "", detail);
    if (!ok) ++g_failures;
}

// A SampleSource over an in-RAM DecodedAudio (mirrors the engine's internal one).
struct DASource : SampleSource {
    const DecodedAudio* a = nullptr;
    int channels() const override { return a ? a->channels() : 2; }
    std::int64_t frameCount() const override { return a ? a->frameCount() : 0; }
    void readPlanar(std::int64_t s, int n, float* dst, std::intptr_t stride) const override {
        if (a) a->readPlanar(s, n, dst, stride);
    }
};

DecodedAudio makeSine(double freq, double seconds, double amp = 0.5) {
    const int rate = kProjectSampleRate;
    const auto frames = static_cast<frame_t>(seconds * rate);
    std::vector<float> pcm(static_cast<std::size_t>(frames) * 2);
    for (frame_t i = 0; i < frames; ++i) {
        const float v = static_cast<float>(amp * std::sin(2.0 * M_PI * freq * i / rate));
        pcm[static_cast<std::size_t>(i) * 2] = v;
        pcm[static_cast<std::size_t>(i) * 2 + 1] = v;
    }
    return DecodedAudio(std::move(pcm), 2, rate);
}

// Write a minimal PCM16 stereo WAV for the decode round-trip check.
bool writeSineWav(const char* path, double freq, double seconds) {
    const int rate = kProjectSampleRate, ch = 2, bits = 16;
    const auto frames = static_cast<std::uint32_t>(seconds * rate);
    const std::uint32_t dataBytes = frames * ch * (bits / 8);
    const std::uint32_t byteRate = rate * ch * (bits / 8);
    std::FILE* f = std::fopen(path, "wb");
    if (!f) return false;
    auto u32 = [&](std::uint32_t v) { std::fwrite(&v, 4, 1, f); };
    auto u16 = [&](std::uint16_t v) { std::fwrite(&v, 2, 1, f); };
    std::fwrite("RIFF", 1, 4, f); u32(36 + dataBytes); std::fwrite("WAVE", 1, 4, f);
    std::fwrite("fmt ", 1, 4, f); u32(16); u16(1); u16(ch);
    u32(rate); u32(byteRate); u16(ch * bits / 8); u16(bits);
    std::fwrite("data", 1, 4, f); u32(dataBytes);
    for (std::uint32_t i = 0; i < frames; ++i) {
        const double s = 0.5 * std::sin(2.0 * M_PI * freq * i / rate);
        const auto v = static_cast<std::int16_t>(s * 32767.0);
        u16(static_cast<std::uint16_t>(v)); u16(static_cast<std::uint16_t>(v));
    }
    std::fclose(f);
    return true;
}

// Max sample-to-sample discontinuity per channel (click detector) + RMS.
void analyse(const std::vector<float>& interleaved, int ch, double& maxStep, double& rms) {
    maxStep = 0.0;
    double sum = 0.0;
    const std::size_t frames = interleaved.size() / ch;
    for (int c = 0; c < ch; ++c) {
        for (std::size_t i = 1; i < frames; ++i) {
            const double d = std::fabs(interleaved[i * ch + c] - interleaved[(i - 1) * ch + c]);
            maxStep = std::max(maxStep, d);
        }
    }
    for (double v : interleaved) sum += v * v;
    rms = interleaved.empty() ? 0.0 : std::sqrt(sum / interleaved.size());
}

// --- Check 1: decode a known WAV to project-rate stereo f32 --------------------
void testDecode() {
    std::printf("\nDecode correctness (exit criterion 3)\n");
    const char* wav = "/tmp/audioscratch_selftest.wav";
    if (!writeSineWav(wav, 440.0, 2.0)) { check(false, "write test wav"); return; }

    MediaDecoder media;
    if (!media.open(wav)) { check(false, "open wav"); return; }
    DecodedAudio a = AudioDecoder::decode(media);

    check(a.channels() == 2, "stereo", ("channels=" + std::to_string(a.channels())).c_str());
    check(a.sampleRate() == kProjectSampleRate, "project rate 48000",
          ("rate=" + std::to_string(a.sampleRate())).c_str());
    const frame_t expected = 2 * kProjectSampleRate;
    const frame_t diff = std::llabs(a.frameCount() - expected);
    check(diff <= 64, "frameCount ~= duration*rate (+/-64)",
          ("frames=" + std::to_string(a.frameCount()) + " exp=" + std::to_string(expected)).c_str());

    std::vector<float> all(a.data(), a.data() + a.frameCount() * a.channels());
    double step, rms; analyse(all, 2, step, rms);
    check(rms > 0.1, "decoded signal non-silent", ("rms=" + std::to_string(rms)).c_str());
}

// --- Check 2: constant-pitch, click-free stretch at +1 / 0 / -1 ---------------
void testStretch() {
    std::printf("\nConstant-pitch scrub, speeds {+1, 0, -1} (exit criterion 5)\n");
    DecodedAudio sine = makeSine(220.0, 1.0);
    DASource src; src.a = &sine;

    BungeeStretcher st;
    st.prepare(kProjectSampleRate, 2, 512);

    struct Case { double speed; double startFrames; const char* name; };
    const Case cases[] = {
        {+1.0, 4000.0, "forward +1x"},
        { 0.0, 20000.0, "hold 0x (freeze)"},
        {-1.0, 24000.0, "reverse -1x"},
    };
    for (const Case& c : cases) {
        std::vector<float> out;
        const int blocks = 32, blk = 512;
        std::vector<float> tmp(static_cast<std::size_t>(blk) * 2);
        double pos = c.startFrames;
        for (int b = 0; b < blocks; ++b) {
            StretchRequest req;
            req.positionFrames = pos;
            req.speed = c.speed;
            req.pitch = 1.0;
            req.reset = (b == 0);
            st.process(req, tmp.data(), blk, src);
            out.insert(out.end(), tmp.begin(), tmp.end());
            pos += c.speed * blk;
        }
        bool finite = true;
        for (float v : out) if (!std::isfinite(v)) { finite = false; break; }
        double step, rms; analyse(out, 2, step, rms);
        check(finite, (std::string(c.name) + ": finite output").c_str());
        // Frozen/held output is still tonal; every case must be non-silent.
        check(rms > 0.02, (std::string(c.name) + ": non-silent").c_str(),
              ("rms=" + std::to_string(rms)).c_str());
        check(step < 0.30, (std::string(c.name) + ": click-free (max step < 0.30)").c_str(),
              ("maxStep=" + std::to_string(step)).c_str());
    }
}

// --- Check 3: no playhead/audio desync at track boundaries --------------------
// Play well past EOF (deck stays "always playing"), then scrub back. Audio must
// resume promptly — regression guard for the boundary-overshoot fix (Bungee's
// internal position must not run past the clamped playhead).
void testBoundary() {
    std::printf("\nBoundary desync (playhead vs audio at EOF)\n");
    DecodedAudio sine = makeSine(220.0, 1.0);          // 1 s clip
    ScrubEngine engine;
    engine.prepare(kProjectSampleRate, 512);
    engine.setAudio(&sine);

    const int blk = 512;
    std::vector<float> out(static_cast<std::size_t>(blk) * 2);

    engine.setPlaying(true);
    engine.setScrubbing(false);
    for (int i = 0; i < 400; ++i) engine.render(out.data(), blk);   // ~4.3 s >> 1 s clip
    const double atEnd = engine.publishedSeconds();

    engine.setScrubbing(true);
    engine.setTargetSeconds(0.5);
    bool resumed = false;
    double maxRms = 0.0;
    for (int i = 0; i < 40; ++i) {                                  // ~0.43 s
        engine.render(out.data(), blk);
        double step, rms; analyse(out, 2, step, rms);
        maxRms = std::max(maxRms, rms);
        if (rms > 0.05) { resumed = true; break; }
    }
    check(atEnd > 0.9 && atEnd <= 1.01, "playhead pins at EOF while forward-playing",
          ("end=" + std::to_string(atEnd)).c_str());
    check(resumed, "audio resumes promptly scrubbing back from EOF (no desync gap)",
          ("maxRms=" + std::to_string(maxRms)).c_str());
}

// --- Check 4: zero allocations in the RT render path (NFR-A) -------------------
void testZeroAlloc() {
    std::printf("\nZero-allocation scrub stress (exit criterion 5 / NFR-A)\n");
    DecodedAudio sine = makeSine(220.0, 5.0);

    ScrubEngine engine;
    engine.prepare(kProjectSampleRate, 512);   // warms the stretcher off-thread
    engine.setAudio(&sine);

    const int blk = 512;
    std::vector<float> out(static_cast<std::size_t>(blk) * 2);

    // Minimal unguarded settle: only the first reset/preroll blocks. The stretcher's
    // per-speed warmup already happened off-thread in prepare(), so the guarded sweep
    // below (forward, reverse, hold) must not allocate — that is the real test. A
    // large unguarded warmup here would mask first-touch allocations and false-PASS.
    for (int i = 0; i < 4; ++i) engine.render(out.data(), blk);
    engine.setScrubbing(true);

    // Measured region: continuous scrub (forward, reverse, hold) — must not alloc.
    g_rtAllocs.store(0);
    g_rtGuard.store(true);
    const int measured = 4000;
    for (int i = 0; i < measured; ++i) {
        engine.setTargetSeconds(2.5 + 2.4 * std::sin(i * 0.017));
        engine.render(out.data(), blk);
    }
    engine.setScrubbing(false);
    for (int i = 0; i < 200; ++i) engine.render(out.data(), blk);   // forward-play path too
    g_rtGuard.store(false);

    const long allocs = g_rtAllocs.load();
    check(allocs == 0, "zero allocations across ~4400 render blocks",
          ("allocs=" + std::to_string(allocs)).c_str());

    bool finite = true;
    for (float v : out) if (!std::isfinite(v)) { finite = false; break; }
    check(finite, "engine output finite after stress");
}

} // namespace

int runSelfTest() {
    std::printf("=== AudioScratch self-test (Phase 1) ===\n");
    g_failures = 0;
    testDecode();
    testStretch();
    testBoundary();
    testZeroAlloc();
    std::printf("\n=== %s (%d failure%s) ===\n",
                g_failures == 0 ? "ALL PASS" : "FAILURES", g_failures,
                g_failures == 1 ? "" : "s");
    return g_failures == 0 ? 0 : 1;
}

} // namespace as
