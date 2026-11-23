#!/bin/sh
# Reproducible Super Mario 3D World benchmark (docs/benchmark.md). Runs without a human and quits by itself.
# usage: tools/bench/sm3dw_bench.sh [-o OUTDIR] [-g GPU] [-r RESSCALE] [-f FRAMES] [-s STAGE] [-w WORK] [-- extra env KEY=VAL ...]
#   -o  output directory (bench.csv, summary.txt, run.log)        default bench_out/<timestamp>
#   -g  GPU name substring for WWHD_VK_DEVICE                     default Intel (the integrated GPU)
#   -r  internal resolution scale (1.5 = 1920x1080)               default 1.5
#   -f  frames to measure                                         default 1800
#   -s  stage archive name fragment to enter                      default Circus
#   -w  work dir (save copy + settings + shader cache)            default <repo>/../bench-work
# Environment: WWHD_BIN (binary, default build/vk/wwhd), GAME (game dir, default ../game3dw_eur), SAVE_SRC
# (GameData.bin, default ../save-backup-s23/user/GameData.bin). The save is copied fresh for every run.
set -e
cd "$(dirname "$0")/../.."
OUT=; GPU=Intel; RES=1.5; FRAMES=1800; STAGE=Circus; WORK=../bench-work
while getopts "o:g:r:f:s:w:" o; do
  case $o in o) OUT=$OPTARG;; g) GPU=$OPTARG;; r) RES=$OPTARG;; f) FRAMES=$OPTARG;; s) STAGE=$OPTARG;; w) WORK=$OPTARG;; *) exit 2;; esac
done
shift $((OPTIND-1))
[ "$1" = "--" ] && shift
BIN=${WWHD_BIN:-build/vk/wwhd}; GAME=${GAME:-../game3dw_eur}; SAVE_SRC=${SAVE_SRC:-../save-backup-s23/user/GameData.bin}
[ -n "$OUT" ] || OUT=bench_out/$(date +%Y%m%d-%H%M%S)
mkdir -p "$OUT" "$WORK/user" "$WORK/config"
cp "$SAVE_SRC" "$WORK/user/GameData.bin"       # fresh copy: the run may write the save
OUT=$(cd "$OUT" && pwd)
env WWHD_LANGUAGE=${WWHD_LANGUAGE:-9} WWHD_VK_DEVICE="$GPU" WWHD_RES_SCALE="$RES" WWHD_BENCH=circus \
    WWHD_BENCH_FRAMES="$FRAMES" WWHD_BENCH_STAGE="$STAGE" WWHD_BENCH_OUT="$OUT" "$@" \
    "$BIN" --game "$GAME" --save "$WORK" --user-dir "$WORK/config" > "$OUT/run.log" 2>&1
cat "$OUT/summary.txt"
