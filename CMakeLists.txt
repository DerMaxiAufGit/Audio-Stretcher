cmake_minimum_required(VERSION 3.21)
project(audioscratch VERSION 0.2.2 LANGUAGES C CXX)

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
if(MSVC)
  # /utf-8: our sources carry UTF-8 UI string literals (the play/pause glyphs, ×, ⟷,
  # File ▸ Open, …). Without it cl.exe re-encodes them via the ANSI code page (C4819)
  # into mojibake that Qt then decodes wrongly. _USE_MATH_DEFINES: expose M_PI et al.
  # from <cmath>. NOMINMAX / WIN32_LEAN_AND_MEAN: stop <windows.h> (dragged in by the
  # miniaudio implementation TU) from defining the min()/max() macros that would break
  # std::min/std::max. /bigobj: Bungee's heavy Eigen expression-template instantiation
  # blows past the COFF section limit (fatal C1128) without it. These apply to every
  # target declared below (pffft, bungee, app).
  add_compile_options(/utf-8 /bigobj)
  add_compile_definitions(_USE_MATH_DEFINES NOMINMAX WIN32_LEAN_AND_MEAN)
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
target_compile_options(bungee PRIVATE
  $<$<CXX_COMPILER_ID:GNU,Clang>:-include cassert>
  $<$<CXX_COMPILER_ID:MSVC>:/FIcassert>)
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

# FFmpeg discovery. On Linux the system FFmpeg ships pkg-config .pc files; the
# prebuilt Windows (BtbN LGPL-shared) SDK does not, so on Windows we locate it by
# header dir + import libraries and expose the same consumable target. Point CMake at
# the SDK with -DFFMPEG_ROOT=<dir> or the FFMPEG_DIR environment variable (the dir that
# contains include/ and lib/). Either path yields an ${FFMPEG_TARGET} to link against.
if(WIN32)
  set(FFMPEG_ROOT "" CACHE PATH "Root of a prebuilt FFmpeg (shared) SDK with include/ and lib/")
  find_path(FFMPEG_INCLUDE_DIR libavformat/avformat.h
    HINTS ${FFMPEG_ROOT} ENV FFMPEG_DIR PATH_SUFFIXES include)
  set(FFMPEG_LINK_LIBS "")
  foreach(_ff avformat avcodec avutil swresample swscale)
    find_library(FFMPEG_${_ff}_LIBRARY NAMES ${_ff}
      HINTS ${FFMPEG_ROOT} ENV FFMPEG_DIR PATH_SUFFIXES lib)
    if(NOT FFMPEG_${_ff}_LIBRARY)
      message(FATAL_ERROR
        "FFmpeg import library '${_ff}' not found. Pass -DFFMPEG_ROOT=<sdk> (or set the "
        "FFMPEG_DIR env var) to a prebuilt LGPL-shared FFmpeg SDK containing include/ and lib/.")
    endif()
    list(APPEND FFMPEG_LINK_LIBS ${FFMPEG_${_ff}_LIBRARY})
  endforeach()
  if(NOT FFMPEG_INCLUDE_DIR)
    message(FATAL_ERROR
      "FFmpeg headers not found. Pass -DFFMPEG_ROOT=<sdk> (or set the FFMPEG_DIR env var).")
  endif()
  add_library(ffmpeg_imported INTERFACE)
  target_include_directories(ffmpeg_imported INTERFACE "${FFMPEG_INCLUDE_DIR}")
  target_link_libraries(ffmpeg_imported INTERFACE ${FFMPEG_LINK_LIBS})
  set(FFMPEG_TARGET ffmpeg_imported)
else()
  find_package(PkgConfig REQUIRED)
  pkg_check_modules(FFMPEG REQUIRED IMPORTED_TARGET
    libavformat libavcodec libavutil libswresample libswscale)
  set(FFMPEG_TARGET PkgConfig::FFMPEG)
endif()

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
  src/engine/audio/AudioCapture.cpp
  src/engine/audio/WaveformPeaks.cpp
  src/engine/io/WavWriter.cpp
  src/engine/video/VideoScrubber.cpp
  src/ui/WaveformView.cpp
  src/ui/VideoView.cpp
  src/ui/TransportBar.cpp
  src/ui/PitchSpeedControls.cpp
  src/ui/LoopMarkerBar.cpp
  src/ui/TimeRulerBar.cpp
  src/ui/InstantReplayControls.cpp
  src/ui/SettingsDialog.cpp
  src/selftest/SelfTest.cpp
)

# WIN32: build as a GUI-subsystem app so launching the GUI does not pop a console window.
# qt_add_executable(WIN32 ...) wires up the WinMain->main entry point; the --selftest paths
# reattach to the parent console in main() so their output stays visible. The WIN32 token
# must be a literal (add_executable evaluates it at parse time, not a generator expression).
set(AS_EXE_TYPE "")
if(WIN32)
  set(AS_EXE_TYPE WIN32)
endif()
qt_add_executable(audioscratch ${AS_EXE_TYPE} ${APP_SOURCES})
target_include_directories(audioscratch PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/src"
  "${THIRD_PARTY}"
  "${THIRD_PARTY}/signalsmith-stretch")
target_link_libraries(audioscratch PRIVATE
  Qt6::Core Qt6::Gui Qt6::Widgets Qt6::Concurrent Qt6::OpenGL Qt6::OpenGLWidgets
  ${FFMPEG_TARGET}
  bungee
  ${CMAKE_DL_LIBS} Threads::Threads)
# No explicit entry-point link is needed: Qt6::Core's interface auto-links
# Qt6::EntryPointPrivate (the WinMain->main shim) into any executable whose WIN32_EXECUTABLE
# property is set, which the WIN32 flag on qt_add_executable above does.

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
