#include "app/Application.h"

namespace as {

Application::Application(int& argc, char** argv) : QApplication(argc, argv) {
    setApplicationName("AudioScratch");
    setOrganizationName("AudioScratch");
    setApplicationDisplayName("AudioScratch");
}

} // namespace as
