#pragma once
// QApplication subclass (plan §6.3, Phase 1 Task 1). A thin seam for app-wide
// setup (name, style) — kept minimal in Phase 1.

#include <QApplication>

namespace as {

class Application : public QApplication {
    Q_OBJECT
public:
    Application(int& argc, char** argv);
};

} // namespace as
