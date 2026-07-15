#pragma once
// Named position marker on the single-clip session (plan §6.5, Phase 2 Task 4/8).
//
// A marker is a labelled point in the loaded clip, in PROJECT-rate frames (not
// seconds) so scrub/jump math stays exact. Each carries a stable monotonic id so
// it can be renamed/deleted independently of its position in the sorted list.
// Markers persist in the session state, ready for Phase 4 project save.

#include <cstdint>

#include <QString>

#include "engine/model/Project.h"

namespace as {

struct Marker {
    frame_t  frame = 0;      // position, project-rate frames
    QString  label;          // user-visible name (may be empty)
    uint32_t id = 0;         // stable id for rename/delete-by-id
};

} // namespace as
