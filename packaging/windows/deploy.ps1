<#
.SYNOPSIS
  Build a portable, R16-compliant (LGPL-only) Windows x64 bundle of AudioScratch:
  the .exe + Qt6 runtime (via windeployqt) + LGPL-shared FFmpeg DLLs + the VC++
  runtime + all third-party licence notices, packaged as a self-contained .zip.

.DESCRIPTION
  Mirrors packaging/linux/build-appimage.sh for Windows. Unlike Linux, there are no
  GPL-kitchen-sink / orphan-trimming gymnastics: Qt is a clean prebuilt MSVC build and
  FFmpeg is the BtbN **LGPL-shared** SDK (avcodec/avformat/avutil/swresample/swscale
  only), so the recipe is a straight assemble-and-zip. Everything is dynamically linked
  and replaceable (R16): swap any Qt6*.dll or av*/sw* DLL and the app keeps working.

  Toolchain assumptions (see packaging/windows/README.md for how they were obtained):
    - Built with MSVC 2022; app at <BuildDir>\audioscratch.exe (RelWithDebInfo).
    - Qt 6.8.x msvc2022_64 (windeployqt lives in <QtDir>\bin).
    - FFmpeg LGPL-shared SDK at <FFmpegDir> (bin\*.dll, LICENSE.txt).

.EXAMPLE
  pwsh -File packaging\windows\deploy.ps1
  pwsh -File packaging\windows\deploy.ps1 -Version 0.2.1 -OutDir C:\dist
#>
[CmdletBinding()]
param(
  [string]$BuildDir  = "$PSScriptRoot\..\..\build-win",
  [string]$QtDir     = $(if ($env:QTDIR)      { $env:QTDIR }      else { "C:\Qt\6.8.3\msvc2022_64" }),
  [string]$FFmpegDir = $(if ($env:FFMPEG_DIR) { $env:FFMPEG_DIR } else { "C:\Users\maxi\audioscratch-winbuild\ffmpeg" }),
  [string]$Version   = "0.2.1",
  [string]$OutDir    = "$PSScriptRoot\..\..\dist"
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path "$PSScriptRoot\..\..").Path

$exe = Join-Path $BuildDir 'audioscratch.exe'
if (-not (Test-Path $exe)) {
  throw "audioscratch.exe not found at $exe — build first:`n" +
        "  cmake -S . -B build-win -G Ninja -DCMAKE_BUILD_TYPE=RelWithDebInfo -DCMAKE_PREFIX_PATH=$QtDir`n" +
        "  cmake --build build-win   (from an MSVC 2022 x64 dev shell)"
}
$windeployqt = Join-Path $QtDir 'bin\windeployqt.exe'
if (-not (Test-Path $windeployqt)) { throw "windeployqt not found at $windeployqt (check -QtDir)" }

$appName = "AudioScratch"
$stageRoot = Join-Path $BuildDir 'stage'
$stage = Join-Path $stageRoot $appName
if (Test-Path $stageRoot) { Remove-Item $stageRoot -Recurse -Force }
New-Item -ItemType Directory -Force $stage | Out-Null
Write-Host "==> staging into $stage"
Copy-Item $exe $stage

# --- 1. Qt runtime (DLLs + plugins) via windeployqt -----------------------------
Write-Host "==> windeployqt"
& $windeployqt --release --no-translations --no-system-d3d-compiler --no-opengl-sw `
  --compiler-runtime (Join-Path $stage 'audioscratch.exe') | Out-Null
# windeployqt drops the redist *installer*; we ship loose CRT DLLs instead (below).
Remove-Item (Join-Path $stage 'vc_redist.x64.exe') -Force -ErrorAction SilentlyContinue

# --- 2. FFmpeg LGPL-shared DLLs (only the 5 the app links; NOT avdevice/avfilter) -
Write-Host "==> FFmpeg DLLs"
foreach ($m in 'avcodec', 'avformat', 'avutil', 'swresample', 'swscale') {
  $dll = Get-ChildItem (Join-Path $FFmpegDir 'bin') -Filter "$m-*.dll" | Select-Object -First 1
  if (-not $dll) { throw "FFmpeg DLL '$m-*.dll' not found in $FFmpegDir\bin" }
  Copy-Item $dll.FullName $stage
}

# --- 3. VC++ runtime DLLs -> self-contained (no redist install needed) -----------
Write-Host "==> VC++ runtime"
$vsroot = "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools"
$crt = Get-ChildItem "$vsroot\VC\Redist\MSVC" -Directory -ErrorAction SilentlyContinue |
       Sort-Object Name -Descending |
       ForEach-Object { Get-ChildItem "$($_.FullName)\x64" -Directory -Filter '*.CRT' -ErrorAction SilentlyContinue } |
       Where-Object Name -like '*VC*' | Select-Object -First 1
if ($crt) { Copy-Item "$($crt.FullName)\*.dll" $stage }
else { Write-Warning "VC++ redist CRT dir not found; users may need the VC++ 2015-2022 x64 redistributable." }

# --- 4. Licence notices (R16) ----------------------------------------------------
Write-Host "==> licences"
$lic = Join-Path $stage 'licenses'
New-Item -ItemType Directory -Force $lic | Out-Null
Copy-Item (Join-Path $FFmpegDir 'LICENSE.txt')                                        (Join-Path $lic 'GNU-LGPL-3.0.txt')          # Qt6 + FFmpeg are both LGPL
Copy-Item (Join-Path $repo 'third_party\bungee\LICENSE')                              (Join-Path $lic 'Bungee-MPL-2.0.txt')
Copy-Item (Join-Path $repo 'third_party\bungee\submodules\eigen\COPYING.MPL2')        (Join-Path $lic 'Eigen-MPL-2.0.txt')
Copy-Item (Join-Path $repo 'third_party\signalsmith-stretch\LICENSE.txt')             (Join-Path $lic 'Signalsmith-MIT.txt')

@"
AudioScratch v$Version — third-party components and licences
============================================================

AudioScratch links the following components dynamically; each may be replaced with a
compatible build (LGPL relinking right, requirement R16). All are permissive / LGPL /
MPL — there is no GPL code and no licence fee.

  Component            Licence        Bundled as                     Full text
  -------------------- -------------- ------------------------------ ---------------------------
  Qt 6                 LGPL v3        Qt6*.dll, plugins/*            licenses\GNU-LGPL-3.0.txt
  FFmpeg (LGPL build)  LGPL v3        av*.dll, sw*.dll               licenses\GNU-LGPL-3.0.txt
  Bungee               MPL-2.0        (static, in audioscratch.exe)  licenses\Bungee-MPL-2.0.txt
  Eigen (core)         MPL-2.0        (static, via Bungee)           licenses\Eigen-MPL-2.0.txt
  Signalsmith Stretch  MIT            (static, in audioscratch.exe)  licenses\Signalsmith-MIT.txt
  pffft / FFTPACK      BSD-3-ish      (static, via Bungee)           see third_party/bungee/submodules/pffft/pffft.h
  miniaudio            Public domain / MIT-0  (static)               see end of third_party/miniaudio.h
  Microsoft VC++ CRT   redistributable        vcruntime*/msvcp*.dll  (Microsoft redistributable terms)

Replacing an LGPL library (R16): the Qt and FFmpeg DLLs are ordinary dynamic libraries.
To use your own build, replace the matching Qt6*.dll (and plugins) or av*/sw* DLL in this
folder with an ABI-compatible one of the same major version and relaunch. FFmpeg here is
the BtbN LGPL-shared build (no --enable-gpl, no libx264); its sources are at
https://github.com/BtbN/FFmpeg-Builds and https://github.com/FFmpeg/FFmpeg .
"@ | Set-Content -Encoding UTF8 (Join-Path $lic 'NOTICES.txt')

@"
AudioScratch v$Version (Windows x64)

A real-time, pitch-preserving audio/video "scratch" instrument. Run audioscratch.exe.
No installation needed — this folder is self-contained (Qt, FFmpeg and the VC++ runtime
are bundled). See licenses\NOTICES.txt for third-party licences.

  audioscratch.exe                  launch the GUI (File > Open a media file, then drag on
                                    the waveform/picture to scratch)
  audioscratch.exe --selftest       headless decode/scrub/zero-alloc checks
  audioscratch.exe --selftest-controls   headless pitch/loop/seek/mode checks
"@ | Set-Content -Encoding UTF8 (Join-Path $stage 'README.txt')

# --- 5. Zip ----------------------------------------------------------------------
if (-not (Test-Path $OutDir)) { New-Item -ItemType Directory -Force $OutDir | Out-Null }
$zip = Join-Path $OutDir "AudioScratch-v$Version-win64.zip"
if (Test-Path $zip) { Remove-Item $zip -Force }
Write-Host "==> zipping -> $zip"
Compress-Archive -Path $stage -DestinationPath $zip -CompressionLevel Optimal

$mb = [math]::Round((Get-Item $zip).Length / 1MB, 1)
$stageMb = [math]::Round(((Get-ChildItem $stage -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host ""
Write-Host "built: $zip  ($mb MB zipped, $stageMb MB unpacked)"
Write-Host "verify: unzip, then run  $appName\audioscratch.exe --selftest  and launch the GUI."
