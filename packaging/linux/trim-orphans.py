#!/usr/bin/env python3
# Trim orphaned libraries from an AppDir: keep only libs reachable (via DT_NEEDED)
# from the real bundle roots — the app binary, the Qt plugins, and the minimal
# FFmpeg. linuxdeploy resolves FFmpeg via the system ld.so.cache and copies the
# *system* GPL FFmpeg's whole codec dependency tree (libx264/x265/vpx/dav1d/aom/
# va/vdpau/...) as dead weight even when we pre-place a minimal decode-only build.
# Nothing in the real bundle references those, so we compute the reachable closure
# and delete everything else. Usage: trim-orphans.py <AppDir>
import os, sys, subprocess, glob

appdir = sys.argv[1]
libdir = os.path.join(appdir, "usr", "lib")

def needed(path):
    try:
        out = subprocess.check_output(["patchelf", "--print-needed", path],
                                      stderr=subprocess.DEVNULL, text=True)
        return [x.strip() for x in out.splitlines() if x.strip()]
    except Exception:
        return []

def realname(soname):
    p = os.path.join(libdir, soname)
    return os.path.basename(os.path.realpath(p)) if os.path.exists(p) else None

roots = [os.path.join(appdir, "usr", "bin", "audioscratch")]
roots += glob.glob(os.path.join(appdir, "usr", "plugins", "**", "*.so*"), recursive=True)
for base in ("libavcodec", "libavformat", "libavutil", "libswresample", "libswscale"):
    roots += [f for f in glob.glob(os.path.join(libdir, base + ".so*")) if not os.path.islink(f)]

keep, stack = set(), list(roots)
for r in roots:
    if os.path.dirname(r) == libdir and not os.path.islink(r):
        keep.add(os.path.basename(r))
while stack:
    for so in needed(stack.pop()):
        rn = realname(so)
        if rn and rn not in keep:
            keep.add(rn)
            stack.append(os.path.join(libdir, rn))

removed = []
for f in sorted(glob.glob(os.path.join(libdir, "*.so*"))):
    tgt = os.path.basename(os.path.realpath(f)) if os.path.islink(f) else os.path.basename(f)
    if tgt not in keep:
        os.remove(f); removed.append(os.path.basename(f))

print(f"KEEP {len(keep)} libs; REMOVED {len(removed)} orphans")
for r in sorted(removed):
    print("  -", r)
