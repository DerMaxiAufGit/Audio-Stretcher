cmake_minimum_required(VERSION 3.21)
project(audioscratch VERSION 0.1.0 LANGUAGES C CXX)

# --- Global settings -------------------------------------------------------
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE RelWithDebInfo CACHE STRING "" FORCE)
endif()
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

# Warnings high, but NOT warnings-as-errors (Phase 1 exit criterion 1).
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
  add_compile_options(-Wall -Wextra -Wno-unused-parameter)
endif()

set(THIRD_PARTY "${CMAKE_CURRENT_SOURCE_DIR}/third_party")

# ===========================================================================
# Third-party: pffft (C) + Bungee (C++20, MPL-2.0) built as our own targets
# so we control flags/defines and avoid Bungee's install/cmake-version pin.
# ===========================================================================
add_library(pffft STATIC
  "${THIRD_PARTY}/bungee/submodules/pffft/pffft.c"
  "${THIRD_PARTY}/bungee/submodules/pffft/fftpack.c"
)
target_compile_options(pffft PRIVATE
  $<$<C_COMPILER_ID:GNU,Clang>:-ffast-math -fno-finite-math-only -fno-exceptions>)

file(GLOB BUNGEE_SOURCES "${THIRD_PARTY}/bungee/src/*.cpp")
add_library(bungee STATIC ${BUNGEE_SOURCES})
target_include_directories(bungee PUBLIC "${THIRD_PARTY}/bungee")
target_include_directories(bungee PRIVATE
  "${THIRD_PARTY}/bungee/submodules/eigen"
  "${THIRD_PARTY}/bungee/submodules")
target_compile_features(bungee PUBLIC cxx_std_20)
target_compile_definitions(bungee PRIVATE
  BUNGEE_SELF_TEST=0
  eigen_assert=assert
  EIGEN_DONT_PARALLELIZE=1)
set_source_files_properties("${THIRD_PARTY}/bungee/src/version.cpp"
  PROPERTIES COMPILE_DEFINITIONS "BUNGEE_VERSION=\"0.1.0-vendored\"")
target_compile_options(bungee PRIVATE $<$<CXX_COMPILER_ID:GNU,Clang>:-fwrapv>)
# Eigen does not pull in <cassert> when eigen_assert is user-defined; force it so
# `assert` is always declared (RelWithDebInfo's -DNDEBUG otherwise trips it).
target_compile_options(bungee PRIVATE -include cassert)
target_link_libraries(bungee PRIVATE pffft)

# ===========================================================================
# Dependencies: Qt6 + FFmpeg (LGPL headers/libs)
# NOTE (R16): the FINAL packaged build (Phase 8) MUST link an FFmpeg built
# WITHOUT --enable-gpl/--enable-nonfree. Phase 1 only DECODES (no encoding),
# so linking the system FFmpeg for local dev is acceptable and does not create
# any GPL obligation on distribution yet. See docs plan §6.5 / R16.
# ===========================================================================
find_package(Qt6 REQUIRED COMPONENTS Core Gui Widgets Concurrent OpenGL OpenGLWidgets)
qt_standard_project_setup()
set(CMAKE_AUTOMOC ON)

find_package(PkgConfig REQUIRED)
pkg_check_modules(FFMPEG REQUIRED IMPORTED_TARGET
  libavformat libavcodec libavutil libswresample libswscale)

set(THREADS_PREFER_PTHREAD_FLAG ON)
find_package(Threads REQUIRED)

# ===========================================================================
# Application target
# ===========================================================================
set(APP_SOURCES
  src/main.cpp
  src/app/Application.cpp
  src/app/MainWindow.cpp
  src/app/Deck.cpp
  src/engine/decode/DecodedAudio.cpp
  src/engine/decode/MediaDecoder.cpp
  src/engine/decode/AudioDecoder.cpp
  src/engine/decode/VideoDecoder.cpp
  src/engine/decode/FrameCache.cpp
  src/engine/audio/BungeeStretcher.cpp
  src/engine/audio/SignalsmithStretcher.cpp
  src/engine/audio/VarispeedStretcher.cpp
  src/engine/audio/Resampler.cpp
  src/engine/audio/ScrubEngine.cpp
  src/engine/audio/AudioDevice.cpp
  src/engine/audio/WaveformPeaks.cpp
  src/engine/video/VideoScrubber.cpp
  src/ui/WaveformView.cpp
  src/ui/VideoView.cpp
  src/ui/TransportBar.cpp
  src/ui/PitchSpeedControls.cpp
  src/ui/LoopMarkerBar.cpp
  src/selftest/SelfTest.cpp
)

qt_add_executable(audioscratch ${APP_SOURCES})
target_include_directories(audioscratch PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/src"
  "${THIRD_PARTY}"
  "${THIRD_PARTY}/signalsmith-stretch")
target_link_libraries(audioscratch PRIVATE
  Qt6::Core Qt6::Gui Qt6::Widgets Qt6::Concurrent Qt6::OpenGL Qt6::OpenGLWidgets
  PkgConfig::FFMPEG
  bungee
  ${CMAKE_DL_LIBS} Threads::Threads)

# Route the C allocator family through --wrap so `--selftest` can prove the RT
# render path performs ZERO allocations INCLUDING Eigen/Bungee malloc/posix_memalign
# (which bypass operator new). The __wrap_* functions live in SelfTest.cpp and are
# harmless for the normal GUI run (count-only-when-armed, then forward to __real_*).
if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
  target_link_options(audioscratch PRIVATE
    -Wl,--wrap=malloc -Wl,--wrap=calloc -Wl,--wrap=realloc -Wl,--wrap=free
    -Wl,--wrap=posix_memalign -Wl,--wrap=aligned_alloc)
endif()

# --selftest harness is built into the binary (Phase 1 deliverable).
# The Phase 2 control-engine checks reuse the same in-binary allocation guard
# (the linker --wrap machinery above lives in SelfTest.cpp), so they run via a
# second flag rather than a standalone tests/ executable that would have to
# replicate that guard. Both are registered as independent ctest cases.
enable_testing()
add_test(NAME selftest COMMAND audioscratch --selftest)
add_test(NAME ScrubEngineControls COMMAND audioscratch --selftest-controls)
