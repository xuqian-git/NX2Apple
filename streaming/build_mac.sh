#!/usr/bin/env bash
# Use in a disposable checkout: prepare_launcher.py changes build inputs intentionally.
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=${1:?usage: streaming/build_mac.sh ABSOLUTE_OUTPUT_DIRECTORY}
python3 streaming/prepare_launcher.py
python3 - <<'PY'
from pathlib import Path
p=Path('tools/try-local');s=p.read_text()
old='python3 tools/engine.py tree --out "$work/engine-tree"'
assert s.count(old)==1
s=s.replace(old,old+'\npython3 streaming/overlay.py "$work/engine-tree"')
s=s.replace('org.nxnative.NSPortBuilder','dev.nxstream.NX2Apple')
s=s.replace('echo "$src-local"','echo "$src-nxstream"')
Path('tools/.nxstream-try-local').write_text(s)
PY
trap 'rm -f tools/.nxstream-try-local' EXIT
bash tools/.nxstream-try-local --no-open --dir "$OUT"
cp streaming/README.zh-CN.md "$OUT/README.zh-CN.md"
ditto -c -k --sequesterRsrc --keepParent "$OUT/NX2Apple.app" "$OUT/NX2Apple-macOS-arm64.zip"
shasum -a 256 "$OUT/NX2Apple-macOS-arm64.zip" > "$OUT/SHA256SUMS-mac.txt"
