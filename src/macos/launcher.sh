#!/bin/sh
# Contents/MacOS/tcm of the JVM flavor of the app: the bundled runtime runs the jar.
# -Xdock: without them the Dock shows "java" and its icon; add-exports: file icons from Launch Services (see SystemIcons).
HERE="$(cd "$(dirname "$0")/.." && pwd)"
exec "$HERE/runtime/bin/java" \
    -Xdock:name="Trivial Commander" \
    -Xdock:icon="$HERE/Resources/tcm.icns" \
    --add-exports=java.desktop/sun.lwawt.macosx=ALL-UNNAMED \
    -jar "$HERE/Resources/tcm.jar" "$@"
