# Windows build + package recipe

Reproducible native Windows x64 build of AudioScratch, and how to bundle it into a
self-contained, R16-compliant (LGPL-only) `.zip`. This mirrors
`packaging/linux/build-appimage.sh`, but Windows is far less fiddly: Qt ships a clean
prebuilt MSVC toolchain and FFmpeg ships an LGPL-shared SDK, so there are no
GPL-kitchen-sink / orphan-trimming gymnastics — it's a straight
build → windeployqt → copy-DLLs → zip.

Everything below was run and verified natively on Windows 11 with MSVC 2022
(RelWithDebInfo). The dependency tools live **outside** the repo (Qt under `C:\Qt`,
FFmpeg under `C:\Users\maxi\audioscratch-winbuild\ffmpeg`); only the recipe is committed.

## Prerequisites

### 1. MSVC 2022 Build Tools

The compiler is `cl.exe` 14.44.35207 (x64), from **Visual Studio 2022 Build Tools**.
CMake (≥ 3.21) and Ninja ship inside the Build Tools too, so you don't need separate
installs — they're under
`...\BuildTools\Common7\IDE\CommonExtensions\Microsoft\CMake\{CMake\bin,Ninja}` (any
CMake ≥ 3.21 + Ninja on `PATH` also works).

All build commands must run from an **MSVC x64 dev shell** — a plain shell after
sourcing:

```
"C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\VC\Auxiliary\Build\vcvars64.bat"
```

### 2. Qt 6.8.3 (msvc2022_64) — no Qt account needed

Installed via [`aqtinstall`](https://github.com/miurahr/aqtinstall), which pulls the
official Qt archives without a Qt Account / installer login:

```
python -m pip install -U aqtinstall
python -m aqt install-qt windows desktop 6.8.3 win64_msvc2022_64 --outputdir C:\Qt
```

=> Qt lands at `C:\Qt\6.8.3\msvc2022_64`. This ships everything we need:
`bin\windeployqt.exe`, the `qwindows` platform plugin, and all six linked components
(Core, Gui, Widgets, Concurrent, OpenGL, OpenGLWidgets).

### 3. FFmpeg 8.x — BtbN **LGPL-shared** build

We deliberately use the LGPL-shared prebuilt (R16: LGPL, dynamic, replaceable — **no**
`--enable-gpl`, no libx264). Grab the `latest` win64 LGPL-shared zip:

```
https://github.com/BtbN/FFmpeg-Builds/releases/download/latest/ffmpeg-master-latest-win64-lgpl-shared.zip
```

Extract it to `C:\Users\maxi\audioscratch-winbuild\ffmpeg` (it has `include\`,
`lib\*.lib`, `bin\*.dll`, and `LICENSE.txt`). The app links five DLLs —
`avcodec-63`, `avformat-63`, `avutil-61`, `swresample-7`, `swscale-10` — the SDK also
ships `avdevice-63` / `avfilter-12`, which this app does **not** use.

## Build

From an MSVC x64 dev shell (i.e. after `vcvars64.bat`):

```
set "FFMPEG_DIR=C:\Users\maxi\audioscratch-winbuild\ffmpeg"
cmake -S . -B build-win -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo -DCMAKE_PREFIX_PATH=C:/Qt/6.8.3/msvc2022_64
cmake --build build-win
```

FFmpeg is discovered from the `FFMPEG_DIR` environment variable (or pass
`-DFFMPEG_ROOT=<sdk>`); on Windows CMake uses `find_path` + `find_library` rather than
pkg-config. Output: `build-win\audioscratch.exe`.

## Test

Both headless self-tests run natively and must print `=== ALL PASS (0 failures) ===`:

```
build-win\audioscratch.exe --selftest            REM decode, constant-pitch scrub, boundary desync, zero-alloc
build-win\audioscratch.exe --selftest-controls   REM pitch, A/B loop, seek, turntable/varispeed, zero-alloc
```

The app is built as a WIN32 (GUI) subsystem exe, so `main.cpp` reattaches the
`--selftest*` runs to the parent console — that's why their output is visible from the
dev shell. Launching `audioscratch.exe` with no args opens the "AudioScratch" window.

## Package

```
pwsh -File packaging\windows\deploy.ps1
```

produces `dist\AudioScratch-v0.2.0-win64.zip` (≈ 58 MB zipped, ≈ 135 MB unpacked). Run
the build first — `deploy.ps1` expects `build-win\audioscratch.exe`. See the script's
header comment for its parameters (`-QtDir`, `-FFmpegDir`, `-Version`, `-OutDir`;
defaults match the paths above / the `QTDIR` + `FFMPEG_DIR` env vars).

What it bundles into a single self-contained `AudioScratch\` folder:

- **Qt runtime** via `windeployqt --release --no-translations --no-system-d3d-compiler
  --no-opengl-sw --compiler-runtime`: the `Qt6*.dll` set + plugin subfolders
  (`platforms\`, `styles\`, `imageformats\`, `iconengines\`, `tls\`,
  `networkinformation\`, `generic\`).
- **The 5 FFmpeg DLLs** the app actually links (`avcodec` / `avformat` / `avutil` /
  `swresample` / `swscale`) — **not** `avdevice` / `avfilter`.
- **The loose VC++ CRT DLLs** (`vcruntime140*`, `msvcp140*`) so no redistributable
  install is needed on the target machine.
- **`licenses\` + `NOTICES.txt`** — the R16 third-party licence set (below).
- A short `README.txt`, then the whole folder is zipped.

`dxcompiler.dll` / `dxil.dll` are **not** needed by this Widgets + OpenGL app (verified).
Self-containment was verified by extracting the zip into a clean folder with `PATH`
reduced to just `C:\Windows`: `--selftest` still passed and the GUI still launched.

For an installer (`.exe`) instead of a zip, see `audioscratch.iss` in this folder — an
Inno Setup 6 recipe that wraps the deployed `build-win\stage\AudioScratch\` tree.

## MSVC portability notes

The port is done; these changes are already in the tree. Listed so a future maintainer
knows why they exist:

- **`CMakeLists.txt`** — an `if(MSVC)` block adds `/utf-8` (UTF-8 UI string literals)
  and `/bigobj` (Eigen), and defines `_USE_MATH_DEFINES`, `NOMINMAX`,
  `WIN32_LEAN_AND_MEAN` project-wide. The bungee target's force-include is
  compiler-split (`-include cassert` on GCC/Clang, `/FIcassert` on MSVC). FFmpeg
  discovery is branched on `WIN32` (`find_path` + `find_library` + an INTERFACE target,
  driven by `FFMPEG_ROOT` / `FFMPEG_DIR`) instead of pkg-config. The app is a WIN32
  (GUI) subsystem exe — `Qt6::Core` auto-links `Qt6::EntryPointPrivate` for the `WinMain`
  shim.
- **`src/selftest/SelfTest.cpp`** — the GNU-ld `--wrap` allocation guard is now
  `#ifdef __GNUC__`; MSVC uses an operator-`new`-only guard (via `malloc` /
  `_aligned_malloc`). The temp WAV path uses
  `std::filesystem::temp_directory_path()` instead of `/tmp`.
- **`src/main.cpp`** — on Windows the `--selftest*` paths reattach to the parent console
  so their output is visible under the WIN32 subsystem.
- **`src/engine/decode/FrameCache.cpp`** — added `#include <cstdlib>` (`std::llabs`).
- **`third_party/bungee/src/Resample.h`** — `__attribute__((noinline))` replaced by a
  portable `BUNGEE_RESAMPLE_NOINLINE` macro (`__declspec(noinline)` on MSVC).
- **`third_party/bungee/submodules/pffft/pffft.c`** — added `#include <malloc.h>` in the
  MSVC branch (declares `_alloca`).

**Result:** Bungee (MPL-2.0) compiles and links cleanly under MSVC — the Signalsmith
fallback was **not** needed. The active stretcher is Bungee, same as Linux.

## Licences (R16)

All permissive / LGPL / MPL — no GPL, no fees. Qt6 = LGPL v3 and FFmpeg = LGPL v3, both
dynamically linked and replaceable (FFmpeg's `LICENSE.txt` ships in its SDK). Bungee =
MPL-2.0 (`third_party/bungee/LICENSE`); Eigen core = MPL-2.0
(`third_party/bungee/submodules/eigen/COPYING.MPL2`); Signalsmith Stretch = MIT
(`third_party/signalsmith-stretch/LICENSE.txt`, compiled in but Bungee is the active
stretcher); pffft/FFTPACK = BSD-3-style
(`third_party/bungee/submodules/pffft/pffft.h`); miniaudio = public domain / MIT-0 (end
of `third_party/miniaudio.h`). `deploy.ps1` assembles these into `licenses\NOTICES.txt`.
