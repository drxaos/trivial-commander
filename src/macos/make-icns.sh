#!/usr/bin/env bash
# Regenerates tcm.icns from tcm.svg (the icon of src/appimage with the margin macOS icons keep around the shape).
# An .icns is a list of PNGs: type, length, data. Needs inkscape and python3; runs on any OS.
set -euo pipefail

src=$(cd "$(dirname "$0")" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
for size in 16 32 64 128 256 512 1024; do
    inkscape "$src/tcm.svg" -w $size -h $size -o "$tmp/$size.png" > /dev/null 2>&1
done
python3 - "$tmp" "$src/tcm.icns" <<'PY'
import struct
import sys

tmp, out = sys.argv[1], sys.argv[2]
# ic11..ic14 are the @2x variants of 16, 32, 128 and 256
types = [('icp4', 16), ('icp5', 32), ('icp6', 64), ('ic07', 128), ('ic08', 256), ('ic09', 512), ('ic10', 1024),
         ('ic11', 32), ('ic12', 64), ('ic13', 256), ('ic14', 512)]
body = b''
for kind, size in types:
    with open('%s/%d.png' % (tmp, size), 'rb') as f:
        png = f.read()
    body += kind.encode() + struct.pack('>I', len(png) + 8) + png
with open(out, 'wb') as f:
    f.write(b'icns' + struct.pack('>I', len(body) + 8) + body)
PY
echo "icns: $src/tcm.icns"
