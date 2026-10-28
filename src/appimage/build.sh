#!/usr/bin/env bash
# Packs target/tcm + lib*.so into an AppImage: type2 runtime + squashfs of the AppDir.
# Needs mksquashfs (squashfs-tools); zstd because the runtime has no xz decoder. The runtime is downloaded once into ~/.cache/appimage
# (override with APPIMAGE_RUNTIME=/path/to/runtime-x86_64).
# Usage: build.sh <target dir> <final name>
set -euo pipefail

target=$1
name=$2
src=$(dirname "$(readlink -f "$0")")
out="$target/$name-linux-amd64.AppImage"
appdir="$target/appimage/AppDir"
fs="$target/appimage/tcm.squashfs"

runtime=${APPIMAGE_RUNTIME:-$HOME/.cache/appimage/runtime-x86_64}
if [[ ! -s $runtime ]]; then
    mkdir -p "$(dirname "$runtime")"
    curl -fsSL -o "$runtime.part" https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-x86_64
    mv "$runtime.part" "$runtime"
fi

rm -rf "$target/appimage"
mkdir -p "$appdir/usr/bin"
install -m 0755 "$target/tcm" "$appdir/usr/bin/"
# same set as src/assembly/linux-amd64.xml: headless AWT, JAWT and JavaSound are not loaded by the GUI
for lib in "$target"/lib*.so; do
    case ${lib##*/} in
        libawt_headless.so | libjawt.so | libjsound.so) ;;
        *) install -m 0755 "$lib" "$appdir/usr/bin/" ;;
    esac
done
install -m 0755 "$src/AppRun" "$appdir/"
install -m 0644 "$src/tcm.desktop" "$src/tcm.svg" "$appdir/"
ln -s tcm.svg "$appdir/.DirIcon"

mksquashfs "$appdir" "$fs" -root-owned -noappend -comp zstd -Xcompression-level 22 -b 1M -no-progress -quiet
cat "$runtime" "$fs" > "$out"
chmod 0755 "$out"
rm -rf "$target/appimage"
echo "AppImage: $out"
