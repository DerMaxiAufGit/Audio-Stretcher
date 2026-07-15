#pragma once
// Single-clip session state (plan §6.5, Phase 2 Task 4/6).
//
// The AUTHORITATIVE UI-thread values for the loaded clip: the A/B loop, the
// marker list, and the live-deck playback mode (R18). The RT audio thread reads a
// mirror of the loop/mode via the lock-free ScrubControl atomics; this struct is
// the source of truth for redraw and (Phase 3) for what gets recorded. All
// positions are in project-rate frames. Pure data + small helpers — the Deck owns
// one instance and pushes every edit to the engine control block.

#include <algorithm>
#include <cstdint>
#include <vector>

#include "engine/model/Marker.h"
#include "engine/model/Project.h"

namespace as {

struct DeckState {
    // --- A/B loop ---
    bool    loopEnabled    = false;
    frame_t loopBeginFrame = 0;
    frame_t loopEndFrame   = 0;

    // --- markers (kept sorted by frame) ---
    std::vector<Marker> markers;
    uint32_t nextMarkerId = 1;

    // --- live-deck stretcher mode (R18) ---
    PlaybackMode playbackMode = PlaybackMode::PitchPreserving;

    // Reset everything to defaults on a new clip load (Phase 2 Task 9).
    void reset() {
        loopEnabled = false;
        loopBeginFrame = loopEndFrame = 0;
        markers.clear();
        nextMarkerId = 1;
        playbackMode = PlaybackMode::PitchPreserving;
    }

    // Set the A/B pair, clamping so begin < end (a MUST per Task 7).
    void setLoop(frame_t begin, frame_t end) {
        if (end < begin) std::swap(begin, end);
        loopBeginFrame = begin;
        loopEndFrame = end;
    }

    // Insert a marker at `frame`; returns its stable id. Keeps the list sorted.
    uint32_t addMarker(frame_t frame, QString label = {}) {
        Marker m{frame, std::move(label), nextMarkerId++};
        const uint32_t id = m.id;
        auto it = std::lower_bound(markers.begin(), markers.end(), frame,
                                   [](const Marker& a, frame_t f) { return a.frame < f; });
        markers.insert(it, std::move(m));
        return id;
    }

    void removeMarker(uint32_t id) {
        markers.erase(std::remove_if(markers.begin(), markers.end(),
                                     [id](const Marker& m) { return m.id == id; }),
                      markers.end());
    }

    Marker* markerById(uint32_t id) {
        for (auto& m : markers) if (m.id == id) return &m;
        return nullptr;
    }

    // Nearest marker strictly after / before `frame` (nullptr if none). Used by
    // the next/prev-marker jump hotkeys.
    const Marker* nextMarkerAfter(frame_t frame) const {
        const Marker* best = nullptr;
        for (const auto& m : markers)
            if (m.frame > frame) { best = &m; break; }   // list is sorted
        return best;
    }
    const Marker* prevMarkerBefore(frame_t frame) const {
        const Marker* best = nullptr;
        for (const auto& m : markers) {
            if (m.frame < frame) best = &m;              // last one below frame
            else break;
        }
        return best;
    }
};

} // namespace as
