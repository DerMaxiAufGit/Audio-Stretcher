#include "selftest/SelfTest.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <new>
#include <string>
#include <vector>
#ifdef _MSC_VER
#include <malloc.h>   // _aligned_malloc / _aligned_free (no posix_memalign on MSVC)
#endif

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
// Counter state, shared by both allocator-guard implementations below.
namespace {
std::atomic<bool> g_rtGuard{false};
std::atomic<long> g_rtAllocs{0};
inline void countAlloc() {
    if (g_rtGuard.load(std::memory_order_relaxed))
        g_rtAllocs.fetch_add(1, std::memory_order_relaxed);
}
}

#ifdef __GNUC__
// --- GCC/Clang: full C-allocator interception via the linker's --wrap (see CMakeLists),
// so allocations that bypass operator new — Eigen/Bungee's direct malloc/posix_memalign —
// are counted too, then route operator new through __real_malloc so each C++ allocation is
// counted exactly once. Everything forwards to the real allocator (behaviour unchanged).
extern "C" {
void* __real_malloc(std::size_t);
void  __real_free(void*);
void* __real_calloc(std::size_t, std::size_t);
void* __real_realloc(void*, std::size_t);
int   __real_posix_memalign(void**, std::size_t, std::size_t);
void* __real_aligned_alloc(std::size_t, std::size_t);
}

namespace {
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

#else
// --- MSVC (no linker --wrap): raw malloc/posix_memalign from Eigen/Bungee cannot be
// intercepted, so the guard here counts C++ operator-new allocations only — still a valid
// proof that the RT render path performs no `new` (the stronger direct-malloc proof is kept
// on the GCC/Clang build). Over-aligned allocations must use _aligned_malloc/_aligned_free:
// MSVC's CRT has no posix_memalign and cannot release an _aligned_malloc block via plain free.
void* operator new(std::size_t n) { countAlloc(); void* p = std::malloc(n ? n : 1); if (!p) throw std::bad_alloc(); return p; }
void* operator new[](std::size_t n) { countAlloc(); void* p = std::malloc(n ? n : 1); if (!p) throw std::bad_alloc(); return p; }
void* operator new(std::size_t n, std::align_val_t a) { countAlloc(); void* p = _aligned_malloc(n ? n : 1, static_cast<std::size_t>(a)); if (!p) throw std::bad_alloc(); return p; }
void* operator new[](std::size_t n, std::align_val_t a) { countAlloc(); void* p = _aligned_malloc(n ? n : 1, static_cast<std::size_t>(a)); if (!p) throw std::bad_alloc(); return p; }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t) noexcept { std::free(p); }
void operator delete(void* p, std::align_val_t) noexcept { _aligned_free(p); }
void operator delete[](void* p, std::align_val_t) noexcept { _aligned_free(p); }
void operator delete(void* p, std::size_t, std::align_val_t) noexcept { _aligned_free(p); }
void operator delete[](void* p, std::size_t, std::align_val_t) noexcept { _aligned_free(p); }
#endif

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
    // OS-appropriate temp dir (%TEMP% on Windows, /tmp on POSIX) — no hardcoded /tmp.
    std::error_code tmpEc;
    std::filesystem::path tmpDir = std::filesystem::temp_directory_path(tmpEc);
    if (tmpEc) tmpDir = std::filesystem::current_path();
    const std::string wavPath = (tmpDir / "audioscratch_selftest.wav").string();
    const char* wav = wavPath.c_str();
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

// ===========================================================================
// Phase 2 — performance-control engine checks (plan Phase 2 Task 10).
// Drives ScrubEngine directly (no GUI) through the same control surface the UI
// uses (setPitchRatio / setBaseRate / setLoop / jumpToFrame / setPlaybackMode).
// ===========================================================================

// Upward zero-crossings per second on the mono-averaged buffer ≈ fundamental Hz.
double estimateFreq(const std::vector<float>& inter, int ch, int rate) {
    const std::size_t frames = ch > 0 ? inter.size() / ch : 0;
    if (frames < 2) return 0.0;
    auto mono = [&](std::size_t i) {
        float s = 0.0f; for (int c = 0; c < ch; ++c) s += inter[i * ch + c]; return s / ch;
    };
    int crossings = 0;
    float prev = mono(0);
    for (std::size_t i = 1; i < frames; ++i) {
        const float v = mono(i);
        if (prev <= 0.0f && v > 0.0f) ++crossings;
        prev = v;
    }
    return crossings / (static_cast<double>(frames) / rate);
}

// Render `warmup` blocks (discarded) then `collect` blocks (concatenated).
std::vector<float> renderAndCollect(ScrubEngine& e, int warmup, int collect, int blk) {
    std::vector<float> tmp(static_cast<std::size_t>(blk) * 2), out;
    for (int i = 0; i < warmup; ++i) e.render(tmp.data(), blk);
    for (int i = 0; i < collect; ++i) {
        e.render(tmp.data(), blk);
        out.insert(out.end(), tmp.begin(), tmp.end());
    }
    return out;
}

// --- Controls check 1: pitch reaches the stretcher, independent of position -----
void testPitchControl() {
    std::printf("\nPitch control: constant-pitch transpose (Task 10.1, R6)\n");
    const int rate = kProjectSampleRate, blk = 512;
    DecodedAudio sine = makeSine(220.0, 5.0);
    ScrubEngine e; e.prepare(rate, blk); e.setAudio(&sine);
    e.setPlaying(true); e.setScrubbing(false);
    e.setPlaybackMode(PlaybackMode::PitchPreserving); e.setBaseRate(1.0f);

    // +5 semitones must compute to ~1.3348 (matches the UI formula 2^(semi/12)).
    const double r5 = std::pow(2.0, 5.0 / 12.0);
    check(std::fabs(r5 - 1.334839854) < 1e-3, "+5 st ratio ≈ 1.3348",
          ("ratio=" + std::to_string(r5)).c_str());

    e.seekSeconds(0.0); e.setPitchRatio(1.0f);
    auto o1 = renderAndCollect(e, 8, 24, blk);
    const double f1 = estimateFreq(o1, 2, rate);
    const double sec1 = e.publishedSeconds();

    e.seekSeconds(0.0); e.setPitchRatio(2.0f);
    auto o2 = renderAndCollect(e, 8, 24, blk);
    const double f2 = estimateFreq(o2, 2, rate);
    const double sec2 = e.publishedSeconds();

    e.seekSeconds(0.0); e.setPitchRatio(static_cast<float>(r5));
    auto o5 = renderAndCollect(e, 8, 24, blk);
    const double f5 = estimateFreq(o5, 2, rate);

    check(f1 > 170.0 && f1 < 280.0, "pitch 0 st ≈ source 220 Hz",
          ("f=" + std::to_string(f1)).c_str());
    check(f1 > 1.0 && f2 / f1 > 1.6 && f2 / f1 < 2.4, "+12 st doubles frequency",
          ("ratio=" + std::to_string(f2 / f1)).c_str());
    check(f1 > 1.0 && f5 / f1 > 1.2 && f5 / f1 < 1.47, "+5 st shifts by ≈1.335x",
          ("ratio=" + std::to_string(f5 / f1)).c_str());
    check(std::fabs(sec1 - sec2) < 0.03, "playhead advance unchanged by pitch",
          ("d=" + std::to_string(std::fabs(sec1 - sec2))).c_str());
}

// --- Controls check 2/3: A/B loop wrap during auto-play; grab overrides ---------
void testLoop() {
    std::printf("\nA/B loop: auto-play wraps B->A; grab overrides (Task 10.2/10.3, R8)\n");
    const int rate = kProjectSampleRate, blk = 512;
    DecodedAudio sine = makeSine(220.0, 2.0);   // 2 s
    ScrubEngine e; e.prepare(rate, blk); e.setAudio(&sine);
    e.setPlaying(true); e.setScrubbing(false); e.setBaseRate(1.0f); e.setPitchRatio(1.0f);

    const frame_t A = static_cast<frame_t>(0.5 * rate);
    const frame_t B = static_cast<frame_t>(1.0 * rate);
    e.setLoop(A, B); e.setLoopEnabled(true); e.seekSeconds(0.6);

    std::vector<float> tmp(static_cast<std::size_t>(blk) * 2);
    double maxSec = 0.0, prev = 0.6; bool wrapped = false;
    for (int i = 0; i < 500; ++i) {
        e.render(tmp.data(), blk);
        const double s = e.publishedSeconds();
        maxSec = std::max(maxSec, s);
        if (prev > 0.9 && s < 0.7) wrapped = true;   // jumped back near A
        prev = s;
    }
    check(wrapped, "playhead wraps B->A during auto-play");
    check(maxSec < 1.05, "playhead never runs far past B while looping",
          ("maxSec=" + std::to_string(maxSec)).c_str());

    // Grab (scrub) past B: the loop must be ignored while grabbed.
    e.setScrubbing(true); e.seekSeconds(0.6); e.setTargetSeconds(1.6);
    double grabMax = 0.0;
    for (int i = 0; i < 120; ++i) {
        e.render(tmp.data(), blk);
        grabMax = std::max(grabMax, e.publishedSeconds());
    }
    check(grabMax > 1.05, "grabbed drag crosses B (loop ignored while grabbed)",
          ("grabMax=" + std::to_string(grabMax)).c_str());

    // Release the grab with the playhead OUTSIDE the loop (past B): the loop must
    // resume — i.e. the playhead is pulled back into [A,B). This is the counterpart
    // to the discrete-jump case: grab-release is NOT suppressed. (Criterion 6.)
    e.setScrubbing(false);
    double afterRelease = 9.9;
    for (int i = 0; i < 60; ++i) { e.render(tmp.data(), blk); afterRelease = std::min(afterRelease, e.publishedSeconds()); }
    check(afterRelease < 1.0, "loop resumes after releasing a grab outside the region",
          ("minAfterRelease=" + std::to_string(afterRelease)).c_str());
}

// --- Controls check 4: discrete seek (marker / A-B jump) -------------------------
void testSeek() {
    std::printf("\nDiscrete seek: jumpToFrame snaps the playhead (Task 10.4, R8)\n");
    const int rate = kProjectSampleRate, blk = 512;
    DecodedAudio sine = makeSine(220.0, 2.0);
    ScrubEngine e; e.prepare(rate, blk); e.setAudio(&sine);
    e.setScrubbing(false); e.setPlaying(false);   // paused: position holds after jump
    e.setLoopEnabled(false);

    std::vector<float> tmp(static_cast<std::size_t>(blk) * 2);
    e.jumpToFrame(static_cast<frame_t>(1.2 * rate));
    for (int i = 0; i < 3; ++i) e.render(tmp.data(), blk);
    check(std::fabs(e.publishedSeconds() - 1.2) < 0.02, "jumpToFrame snaps to target",
          ("pos=" + std::to_string(e.publishedSeconds())).c_str());

    // A jump that lands OUTSIDE an active loop must be honored (marker/handle jump),
    // not yanked back to A — for several blocks, not just one. (Regression: the loop
    // snap-in previously overrode discrete seeks.)
    e.setLoop(static_cast<frame_t>(0.4 * rate), static_cast<frame_t>(0.8 * rate));
    e.setLoopEnabled(true);
    e.setPlaying(true); e.setScrubbing(false);
    e.jumpToFrame(static_cast<frame_t>(1.1 * rate));   // past B (=0.8s)
    double minPos = 9.9;
    for (int i = 0; i < 8; ++i) { e.render(tmp.data(), blk); minPos = std::min(minPos, e.publishedSeconds()); }
    check(minPos > 0.85, "jump outside active loop is honored (not snapped to A)",
          ("minPos=" + std::to_string(minPos)).c_str());

    // A jump back INSIDE the loop re-engages normal looping (playhead stays in [A,B)).
    e.jumpToFrame(static_cast<frame_t>(0.5 * rate));
    double maxIn = 0.0;
    for (int i = 0; i < 200; ++i) { e.render(tmp.data(), blk); maxIn = std::max(maxIn, e.publishedSeconds()); }
    check(maxIn < 0.85, "jump inside loop re-engages looping (wraps at B=0.8)",
          ("maxIn=" + std::to_string(maxIn)).c_str());
}

// --- Controls check 5: mode select (R18) + zero-alloc across switches ------------
void testModeSelect() {
    std::printf("\nTurntable/varispeed mode select + NFR-A (Task 10.5, R18)\n");
    const int rate = kProjectSampleRate, blk = 512;
    DecodedAudio sine = makeSine(220.0, 6.0);
    ScrubEngine e; e.prepare(rate, blk); e.setAudio(&sine);
    e.setPlaying(true); e.setScrubbing(false); e.setLoopEnabled(false);

    // Varispeed: pitch bends with speed; pitchRatio is inert.
    e.setPlaybackMode(PlaybackMode::Varispeed);
    e.setBaseRate(1.0f); e.setPitchRatio(1.0f); e.seekSeconds(0.0);
    const double fv1 = estimateFreq(renderAndCollect(e, 8, 24, blk), 2, rate);
    e.setBaseRate(2.0f); e.seekSeconds(0.0);
    const double fv2 = estimateFreq(renderAndCollect(e, 8, 24, blk), 2, rate);
    e.setBaseRate(1.0f); e.setPitchRatio(2.0f); e.seekSeconds(0.0);
    const double fv3 = estimateFreq(renderAndCollect(e, 8, 24, blk), 2, rate);

    check(fv1 > 1.0 && fv2 / fv1 > 1.6 && fv2 / fv1 < 2.4, "varispeed: pitch bends with speed",
          ("ratio=" + std::to_string(fv2 / fv1)).c_str());
    check(fv1 > 1.0 && fv3 / fv1 > 0.8 && fv3 / fv1 < 1.25, "varispeed: pitchRatio inert",
          ("ratio=" + std::to_string(fv3 / fv1)).c_str());

    // Pitch-preserving contrast: pitchRatio drives pitch, base speed does not.
    e.setPlaybackMode(PlaybackMode::PitchPreserving);
    e.setBaseRate(1.0f); e.setPitchRatio(1.0f); e.seekSeconds(0.0);
    const double fp1 = estimateFreq(renderAndCollect(e, 8, 24, blk), 2, rate);
    e.setPitchRatio(2.0f); e.seekSeconds(0.0);
    const double fp2 = estimateFreq(renderAndCollect(e, 8, 24, blk), 2, rate);
    check(fp1 > 1.0 && fp2 / fp1 > 1.6 && fp2 / fp1 < 2.4, "pitch-preserve: pitchRatio drives pitch",
          ("ratio=" + std::to_string(fp2 / fp1)).c_str());

    // Zero allocations across repeated mode switches + control edits (NFR-A).
    std::vector<float> tmp(static_cast<std::size_t>(blk) * 2);
    for (int i = 0; i < 8; ++i) e.render(tmp.data(), blk);   // settle
    g_rtAllocs.store(0);
    g_rtGuard.store(true);
    for (int i = 0; i < 2000; ++i) {
        e.setPlaybackMode((i / 50) % 2 ? PlaybackMode::Varispeed
                                       : PlaybackMode::PitchPreserving);
        e.setPitchRatio(1.0f + 0.5f * std::sin(i * 0.01));   // stays in [0.5,1.5] (warmed)
        e.setBaseRate(1.0f + 0.5f * std::sin(i * 0.013));
        if (i % 200 == 0) {
            e.setLoop(2000, 60000 + i);
            e.setLoopEnabled((i % 400) < 200);
            e.jumpToFrame(5000 + i);
        }
        e.render(tmp.data(), blk);
    }
    g_rtGuard.store(false);
    const long allocs = g_rtAllocs.load();
    check(allocs == 0, "zero allocations across mode switches + control edits",
          ("allocs=" + std::to_string(allocs)).c_str());

    bool finite = true;
    for (float v : tmp) if (!std::isfinite(v)) { finite = false; break; }
    check(finite, "engine output finite after mode-switch stress");
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

int runControlsSelfTest() {
    std::printf("=== AudioScratch self-test (Phase 2 — performance controls) ===\n");
    g_failures = 0;
    testPitchControl();
    testLoop();
    testSeek();
    testModeSelect();
    std::printf("\n=== %s (%d failure%s) ===\n",
                g_failures == 0 ? "ALL PASS" : "FAILURES", g_failures,
                g_failures == 1 ? "" : "s");
    return g_failures == 0 ? 0 : 1;
}

} // namespace as
