// AudioScratch entry point (Phase 1 Task 1).
//   audioscratch [file]              open the GUI, optionally auto-loading `file`
//   audioscratch --selftest          Phase 1 offline scrub/decode stress harness
//   audioscratch --selftest-controls Phase 2 performance-control engine checks

#include <cstring>

#ifdef _WIN32
#include <cstdio>
#include <windows.h>
namespace {
// The GUI is built as a WIN32-subsystem app (no console), so a --selftest run's printf
// output would otherwise be discarded. Reattach to the launching terminal's console (if
// any) and rebind stdout/stderr so the PASS/FAIL lines are visible; a no-op when there is
// no parent console (e.g. double-clicked), so the GUI path stays console-free.
void attachParentConsole() {
    // If we already have a valid stdout (launched with redirected output, or from a shell
    // that passed handles), keep it — clobbering it would break output redirection/capture.
    // Only when there is none (a bare GUI-subsystem launch from a terminal) do we attach to
    // the parent console and bind the standard streams so --selftest output is visible.
    const HANDLE existing = GetStdHandle(STD_OUTPUT_HANDLE);
    if (existing != nullptr && existing != INVALID_HANDLE_VALUE)
        return;
    if (AttachConsole(ATTACH_PARENT_PROCESS)) {
        std::FILE* f = nullptr;
        (void)freopen_s(&f, "CONOUT$", "w", stdout);
        (void)freopen_s(&f, "CONOUT$", "w", stderr);
    }
}
} // namespace
#endif

#include "app/Application.h"
#include "app/MainWindow.h"
#include "selftest/SelfTest.h"

int main(int argc, char** argv) {
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--selftest") == 0) {
#ifdef _WIN32
            attachParentConsole();
#endif
            return as::runSelfTest();
        }
        if (std::strcmp(argv[i], "--selftest-controls") == 0) {
#ifdef _WIN32
            attachParentConsole();
#endif
            return as::runControlsSelfTest();
        }
    }

    as::Application app(argc, argv);
    as::MainWindow window;
    window.show();

    if (argc > 1 && argv[1][0] != '-')
        window.loadFile(QString::fromLocal8Bit(argv[1]));

    return app.exec();
}
