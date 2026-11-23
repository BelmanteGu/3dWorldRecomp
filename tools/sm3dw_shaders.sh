#!/bin/sh
# Super Mario 3D World: offline shader head start (game3dw/shadercache/headstart.bin).
# usage: tools/sm3dw_shaders.sh [BINARY] [SECONDS]
# 1. runs the game for SECONDS (default 120) so the runtime records the shaders and state it uses,
# 2. distils that cache into a code-free template, 3. builds the head start from the game's own
# shader archives (8,6k programs) plus the template. The runtime loads it at start, so shaders and
# pipelines are ready before the first draw. Run again after playing more to cover more of the game.
set -e
cd "$(dirname "$0")/.."
BIN=${1:-build/cmake3dw/wwhd}
SECS=${2:-120}
CACHE="$HOME/Library/Application Support/SM3DW/shader-prebuild/live/shaders.bin"
"$BIN" --game game3dw >/dev/null 2>&1 &
PID=$!
sleep "$SECS"
kill $PID 2>/dev/null || true
mkdir -p game3dw/shadercache
python3 tools/shaderprep.py template "$CACHE" game3dw/shadercache/template.bin ${HAVE_TEMPLATE:+--merge game3dw/shadercache/template.bin}
python3 tools/shaderprep.py build game3dw/shadercache/headstart.bin --game game3dw --template game3dw/shadercache/template.bin --jobs 4
