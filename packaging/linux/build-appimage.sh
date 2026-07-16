#!/usr/bin/env bash
# Build a portable, R16-compliant (LGPL-only) Linux AppImage for AudioScratch.
#
# WHY THIS IS NOT A ONE-LINER — the recipe encodes several hard-won gotchas found
# on Manjaro 2026-07-15 (see docs/plans .../plan.md R16 + .claude/WRAPUP.md):
#   1. The distro FFmpeg is a GPL "kitchen-sink" build whose huge dependency tree
#      (libx264/x265/xvid/vpx/dav1d/aom/va/vdpau/OpenCL/glslang/...) both violates
#      R16 AND makes the bundle crash the dynamic loader. So we build our OWN
#      minimal, decode-only, LGPL FFmpeg (--disable-autodetect => zero external libs).
#   2. linuxdeploy resolves libraries via the system ld.so.cache and IGNORES
#      LD_LIBRARY_PATH / RUNPATH / --exclude-library for transitive deps — so it
#      still drags in the system FFmpeg's GPL codec libs as orphans. We remove them
#      afterwards with trim-orphans.py (reachability closure) and repack with
#      appimagetool (which does NOT re-run dependency deployment).
#   3. linuxdeploy's bundled `strip` can't parse Manjaro's .relr.dyn sections
#      => NO_STRIP=1.
#   4. The Qt plugin tries to bundle every KDE kimageformats plugin; kimg_jxr needs
#      libjxrglue.so.0 which isn't installed => we point QMAKE at a pruned plugin dir.
#
# Prereqs: cmake-built ./build/audioscratch, git, make, nasm, gcc, patchelf,
# imagemagick (magick), qmake6, curl, python3. Verified piecewise on Manjaro; run
# end-to-end at your own risk and adjust the FFMPEG_TAG to match your Qt/FFmpeg.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
BUILD="${BUILD_DIR:-$REPO/build}"
WORK="${WORK_DIR:-$REPO/build/appimage-work}"
FFMPEG_TAG="${FFMPEG_TAG:-n8.1.2}"   # MUST match the SONAME majors your app links
OUT="${OUTPUT:-$REPO/AudioScratch-v0.2.2-x86_64.AppImage}"
mkdir -p "$WORK"; cd "$WORK"

[ -x "$BUILD/audioscratch" ] || { echo "build the app first: cmake --build $BUILD"; exit 1; }

# --- 1. minimal, decode-only, LGPL FFmpeg (no external libs) ---------------------
FFMIN="$WORK/ffmpeg-min"
if [ ! -f "$FFMIN/lib/libavcodec.so" ]; then
  [ -d ffmpeg-src ] || git clone --depth 1 --branch "$FFMPEG_TAG" https://github.com/FFmpeg/FFmpeg.git ffmpeg-src
  ( cd ffmpeg-src && ./configure --prefix="$FFMIN" --enable-shared --disable-static \
      --disable-autodetect --disable-programs --disable-doc --disable-debug \
      --disable-encoders --disable-muxers --enable-zlib && make -j"$(nproc)" && make install )
fi

# --- 2. tools --------------------------------------------------------------------
dl() { [ -f "$2" ] || curl -fsSL -o "$2" "$1"; chmod +x "$2"; }
dl https://github.com/linuxdeploy/linuxdeploy/releases/download/continuous/linuxdeploy-x86_64.AppImage linuxdeploy.AppImage
dl https://github.com/linuxdeploy/linuxdeploy-plugin-qt/releases/download/continuous/linuxdeploy-plugin-qt-x86_64.AppImage linuxdeploy-plugin-qt.AppImage
dl https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage appimagetool.AppImage

# --- 3. pruned Qt plugin dir (drop KDE kimg_* that need uninstalled libjxrglue) --
PDIR="$(/usr/bin/qmake6 -query QT_INSTALL_PLUGINS)"
MYP="$WORK/qtplugins"
rm -rf "$MYP"; cp -r "$PDIR" "$MYP"; rm -f "$MYP"/imageformats/kimg_*.so
cat > "$WORK/qmake6" <<EOF
#!/bin/bash
/usr/bin/qmake6 "\$@" | sed "s#$PDIR#$MYP#g"
EOF
chmod +x "$WORK/qmake6"

# --- 4. AppDir: icon + desktop + pre-placed minimal FFmpeg, then linuxdeploy ------
rm -rf AppDir; mkdir -p AppDir/usr/lib
cp "$BUILD/audioscratch" AppDir_bin_audioscratch 2>/dev/null || true
magick -size 256x256 xc:'#14171c' -stroke '#58aaff' -strokewidth 7 -fill none \
  -draw "path 'M 20,128 C 56,36 92,220 128,128 S 200,36 236,128'" \
  -stroke '#ff6060' -strokewidth 5 -draw "line 128,28 128,228" audioscratch.png
for b in libavcodec libavformat libavutil libswresample libswscale; do cp -a "$FFMIN/lib/$b".so* AppDir/usr/lib/; done

export APPIMAGE_EXTRACT_AND_RUN=1 NO_STRIP=1 VERSION=0.2.2 QMAKE="$WORK/qmake6"
./linuxdeploy.AppImage --appdir AppDir \
  -e "$BUILD/audioscratch" -d "$HERE/audioscratch.desktop" -i audioscratch.png \
  --exclude-library "libav*" --exclude-library "libsw*" \
  --plugin qt
# NB: no `--output` — linuxdeploy just populates the AppDir here; appimagetool (below)
# does the actual packaging. (This linuxdeploy build rejects `--output none` as an
# unknown output plugin — "Could not find plugin: none".)

# --- 5. remove the orphaned system-GPL FFmpeg codec libs, then package -----------
python3 "$HERE/trim-orphans.py" AppDir
APPIMAGE_EXTRACT_AND_RUN=1 ./appimagetool.AppImage AppDir "$OUT"
echo "built: $OUT"
echo "verify:  ./$(basename "$OUT") --selftest   &&   ...--selftest-controls   &&   GUI launch"
