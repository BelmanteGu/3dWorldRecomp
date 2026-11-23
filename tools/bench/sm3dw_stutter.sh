#!/bin/sh
# Stutter sweep (docs/stutter.md): the whole session of the circus benchmark (title, world map, the stage's
# loading and its play) at the user's settings (120 fps, vsync), every frame with its causes (frame_causes.h).
# usage: tools/bench/sm3dw_stutter.sh [-c] [-o OUTDIR] [-g GPU] [-r SCALE] [-f FRAMES] [-w WORK] [-- VAR=value ...]
#   -c  cold: empty the work dir's shader caches first (first run on a new install)
#   default: NVIDIA GPU, scale 1.5 (1080p), 3600 measured stage frames, work dir ../bench-stutter
set -e
cd "$(dirname "$0")/../.."
COLD=; OUT=; GPU=NVIDIA; RES=1.5; FRAMES=3600; WORK=../bench-stutter
while getopts "co:g:r:f:w:" o; do
  case $o in c) COLD=1;; o) OUT=$OPTARG;; g) GPU=$OPTARG;; r) RES=$OPTARG;; f) FRAMES=$OPTARG;; w) WORK=$OPTARG;; *) exit 2;; esac
done
shift $((OPTIND-1))
[ "$1" = "--" ] && shift
[ -n "$OUT" ] || OUT=bench_out/stutter-$(date +%Y%m%d-%H%M%S)
[ -n "$COLD" ] && rm -rf "$WORK/config"
tools/bench/sm3dw_bench.sh -o "$OUT" -g "$GPU" -r "$RES" -f "$FRAMES" -w "$WORK" -- WWHD_BENCH_ALL=1 WWHD_BENCH_CAPPED=1 \
  WWHD_FPS=${WWHD_FPS:-120} WWHD_VK_PRESENT_MODE=${WWHD_VK_PRESENT_MODE:-fifo} "$@" >/dev/null
# at 60 fps the long-frame columns count frames late against the 16.7 ms period (--fps=60)
FPSARG=; case " $* ${WWHD_FPS:-120} " in *WWHD_FPS=60*|*" 60 "*) FPSARG=--fps=60;; esac
python3 tools/bench/frame_causes_summary.py $FPSARG "$OUT/bench.csv" | tee "$OUT/stutter.txt"
