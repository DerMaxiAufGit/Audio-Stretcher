#pragma once
// Offline self-test / scrub stress harness (Phase 1 Task 5 done-condition +
// exit criteria 3/5). Runs headless (no GUI, no audio device) and returns 0 on
// success, non-zero if any check fails. Invoked via `audioscratch --selftest`.

namespace as {
int runSelfTest();
}
