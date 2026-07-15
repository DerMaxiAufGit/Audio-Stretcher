// AudioScratch entry point (Phase 1 Task 1).
//   audioscratch [file]              open the GUI, optionally auto-loading `file`
//   audioscratch --selftest          Phase 1 offline scrub/decode stress harness
//   audioscratch --selftest-controls Phase 2 performance-control engine checks

#include <cstring>

#include "app/Application.h"
#include "app/MainWindow.h"
#include "selftest/SelfTest.h"

int main(int argc, char** argv) {
    for (int i = 1; i < argc; ++i) {
        if (std::strcmp(argv[i], "--selftest") == 0)
            return as::runSelfTest();
        if (std::strcmp(argv[i], "--selftest-controls") == 0)
            return as::runControlsSelfTest();
    }

    as::Application app(argc, argv);
    as::MainWindow window;
    window.show();

    if (argc > 1 && argv[1][0] != '-')
        window.loadFile(QString::fromLocal8Bit(argv[1]));

    return app.exec();
}
