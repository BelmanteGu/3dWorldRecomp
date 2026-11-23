#!/bin/sh
# Scripted tour of Super Mario 3D World on the Mac, window hidden: presses A on a schedule and saves TV frames.
# usage: tools/sm3dw_tour.sh OUTDIR [ASPECT] [FRAMES] [BINARY] [LAST_PRESS_FRAME]
#   ASPECT  default 2340:1080 (the Galaxy S23); 16:9, 21:9 ... as WWHD_ASPECT
#   FRAMES  comma list of TV frames to capture, default 900,1300,1700,2100,4600,6000
#   BINARY  default build/cmake3dw/wwhd (Metal); build/cmake3dw_vk/wwhd is the Vulkan (MoltenVK) build, the
#           renderer the phone uses, and keeps 60 fps while pipelines compile
# A is pressed at frames 700, 1000, 1400, 1800, 2300, 2800 (title -> file -> character -> opening), then every
# 300 frames up to LAST_PRESS_FRAME (default 12000). Writes OUTDIR/*.png, OUTDIR/sheet.jpg (contact sheet) and
# OUTDIR/run.log. The window never opens: WWHD_HIDDEN_WINDOWS=1, no host input (WWHD_NO_HOST_INPUT=1).
set -e
cd "$(dirname "$0")/.."
OUT=${1:?output directory}; ASPECT=${2:-2340:1080}; FRAMES=${3:-900,1300,1700,2100,4600,6000}
BIN=${4:-build/cmake3dw/wwhd}; LAST=${5:-12000}
mkdir -p "$OUT"
P="700-706:8000,1000-1006:8000,1400-1406:8000,1800-1806:8000,2300-2306:8000,2800-2806:8000"
f=3000; while [ $f -le "$LAST" ]; do P="$P,$f-$((f+5)):8000"; f=$((f+300)); done
LAST_SHOT=$(echo "$FRAMES" | tr ',' '\n' | sort -n | tail -1)
env WWHD_HIDDEN_WINDOWS=1 WWHD_NO_HOST_INPUT=1 WWHD_ASPECT="$ASPECT" WWHD_PRESS="$P" \
    WWHD_SHADER_CACHE="$OUT/shaders.bin" WWHD_SCREENSHOT_DIR="$OUT" WWHD_TEST_SCREENSHOT="$FRAMES" \
    "$BIN" --game game3dw > "$OUT/run.log" 2>&1 &
PID=$!
# done when the last frame was taken (or the process ended)
until [ "$(ls "$OUT"/*.png 2>/dev/null | wc -l)" -ge "$(echo "$FRAMES" | tr ',' '\n' | wc -l)" ] || ! kill -0 $PID 2>/dev/null; do sleep 3; done
kill $PID 2>/dev/null || true
n=0; set --
for p in $(ls "$OUT"/*.png | sort); do ffmpeg -loglevel error -y -i "$p" -vf scale=480:-1 "$OUT/t$n.jpg"; set -- "$@" -i "$OUT/t$n.jpg"; n=$((n+1)); done
[ $n -gt 0 ] && ffmpeg -loglevel error -y "$@" -filter_complex "hstack=inputs=$n" "$OUT/sheet.jpg" || true
echo "$n frames in $OUT (last requested: $LAST_SHOT)"
