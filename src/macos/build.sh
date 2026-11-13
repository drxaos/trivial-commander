#!/usr/bin/env bash
# Builds "Trivial Commander.app" and packs it into a compressed disk image with a link to /Applications next to it
# (drag to install). Runs on macOS only: needs hdiutil and codesign, both part of the system.
#   jvm    - target/<final name>.jar and a Java runtime cut down to the modules the jar needs (jlink of the JDK the build runs on);
#   native - target/tcm (mvn -Pnative) and the JDK libraries it loads (lib*.dylib).
# The app is signed ad hoc (no developer certificate): Apple Silicon does not start unsigned code at all, and a downloaded
# copy still has to be let through Gatekeeper once (right click - Open, or xattr -dr com.apple.quarantine on the app).
# Usage: build.sh <target dir> <final name> <version> jvm|native
set -euo pipefail

target=$1
name=$2
version=$3
mode=$4
src=$(cd "$(dirname "$0")" && pwd)
if ! command -v hdiutil > /dev/null || ! command -v codesign > /dev/null; then
    echo "hdiutil and codesign not found: the disk image is built on macOS only" >&2
    exit 1
fi
case $(uname -m) in
    arm64 | aarch64) arch=aarch64 ;;
    *) arch=amd64 ;;
esac
stage="$target/dmg"
app="$stage/Trivial Commander.app"

rm -rf "$stage"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
case $mode in
    native)
        out="$target/$name-macos-$arch.dmg"
        install -m 0755 "$target/tcm" "$app/Contents/MacOS/"
        # the native image looks up the JDK libraries next to the executable; JavaSound is not used
        for lib in "$target"/lib*.dylib; do
            [[ -e $lib ]] || continue
            case ${lib##*/} in
                libjsound.dylib) ;;
                *) install -m 0755 "$lib" "$app/Contents/MacOS/" ;;
            esac
        done
        ;;
    jvm)
        out="$target/$name-macos-$arch-jvm.dmg"
        java_home=${JAVA_HOME:-$(/usr/libexec/java_home)}
        jar="$target/$name.jar"
        # the modules the jar refers to; charsets: DOS and Windows code pages of archive names and of the viewer;
        # jdk.crypto.ec: TLS of the update check on JDKs where it is not part of java.base yet
        modules=$("$java_home/bin/jdeps" --multi-release 21 --ignore-missing-deps --print-module-deps "$jar" 2> /dev/null) || modules=java.se
        "$java_home/bin/jlink" --add-modules "$modules,jdk.charsets,jdk.crypto.ec,jdk.accessibility" \
            --strip-debug --no-header-files --no-man-pages --output "$app/Contents/runtime"
        install -m 0644 "$jar" "$app/Contents/Resources/tcm.jar"
        install -m 0755 "$src/launcher.sh" "$app/Contents/MacOS/tcm"
        ;;
    *)
        echo "Unknown mode: $mode (jvm or native)" >&2
        exit 1
        ;;
esac
# only digits and dots are a valid bundle version: 1.2.3-SNAPSHOT -> 1.2.3
plist_version=$(printf '%s' "$version" | sed -E 's/[^0-9.].*$//')
sed "s/@VERSION@/${plist_version:-0}/g" "$src/Info.plist" > "$app/Contents/Info.plist"
install -m 0644 "$src/tcm.icns" "$app/Contents/Resources/"
printf 'APPL????' > "$app/Contents/PkgInfo"

codesign --force --deep --sign - "$app"
ln -s /Applications "$stage/Applications"
rm -f "$out"
hdiutil create -volname "Trivial Commander" -srcfolder "$stage" -fs HFS+ -format UDZO -imagekey zlib-level=9 -ov -quiet "$out"
rm -rf "$stage"
echo "dmg: $out"
