#!/bin/sh
# Stage tour (docs/stutter.md, #16): every course of the game, one benchmark run each, in one shared work dir, so
# the runs add up the pipeline recipes and shaders a player meets (the recipe/shader pack is made from them, see
# tools/sm3dw_recipe_pack.sh) and each course's first-visit cost is measured.
# The benchmark walks into world 1-5 (the circus); a copy of SystemData/StageList.szs whose 1-5 names another stage
# (tools/bench/stage_list_patch.py, WWHD_BENCH_STAGELIST) makes that node load the course of the turn. The menus run
# uncapped, the course in real time (WWHD_BENCH_CAP_STAGE=1) at the given fps.
# usage: tools/bench/sm3dw_stage_tour.sh [-w WORK] [-o OUT] [-g GPU] [-f FRAMES] [-c] [STAGE...] [VAR=value ...]
#   -w  shared work dir (config = shader/pipeline caches)   default ../bench-tour
#   -o  output dir: <stage>/bench.csv + run.log, tour.txt  default bench_out/tour-<timestamp>
#   -g  GPU (WWHD_VK_DEVICE)                                default NVIDIA
#   -f  frames measured in each course (after 300 settling)  default 900
#   -c  cold: empty the work dir's caches first
#   STAGE  stage names (StageList.byml); default: every course in the list
# Environment: WWHD_BIN, GAME (as sm3dw_bench.sh), FPS (default 60), MUL (WWHD_MUL_MODE, default fast = the
# configuration players run; "strict" for the other baseline).
set -e
cd "$(dirname "$0")/../.."
WORK=../bench-tour; OUT=; GPU=NVIDIA; FRAMES=900; COLD=
while getopts "w:o:g:f:c" o; do
  case $o in w) WORK=$OPTARG;; o) OUT=$OPTARG;; g) GPU=$OPTARG;; f) FRAMES=$OPTARG;; c) COLD=1;; *) exit 2;; esac
done
shift $((OPTIND-1))
GAME=${GAME:-../game3dw_eur}; LIST=$GAME/content/SystemData/StageList.szs
STAGES=; VARS=
for a in "$@"; do  # VAR=value words are passed to the game, the others are stage names
  case $a in --) ;; *=*) VARS="$VARS $a";; *) STAGES="$STAGES $a";; esac
done
[ -n "$STAGES" ] || STAGES=$(python3 -I tools/bench/stage_list_patch.py "$LIST" --list | awk '{print $3}' | awk '!seen[$0]++')
[ -n "$OUT" ] || OUT=bench_out/tour-$(date +%Y%m%d-%H%M%S)
[ -n "$COLD" ] && rm -rf "$WORK/config"
mkdir -p "$OUT" "$WORK"
OUT=$(cd "$OUT" && pwd)
printf '%-34s %6s %6s %6s %6s %6s %6s %8s %s\n' stage frames pipes shaders skips '>25ms' '>50ms' 'max_ms' loaded > "$OUT/tour.txt"
for S in $STAGES; do
  python3 -I tools/bench/stage_list_patch.py "$LIST" "$WORK/StageList.szs" FlipCircusStage "$S" > /dev/null || continue
  R=$OUT/$S; mkdir -p "$R"
  WWHD_HIDDEN_WINDOWS=1 WWHD_NO_AUDIO=1 timeout 600 tools/bench/sm3dw_bench.sh -g "$GPU" -r 1 -f "$FRAMES" -s "$S" -w "$WORK" -o "$R" -- \
    WWHD_BENCH_STAGELIST="$(cd "$WORK" && pwd)/StageList.szs" WWHD_BENCH_CAP_STAGE=1 WWHD_BENCH_CAPPED= \
    WWHD_BENCH_SETTLE=300 WWHD_BENCH_ALL=1 WWHD_FPS=${FPS:-60} WWHD_MUL_MODE=${MUL:-fast} $VARS > /dev/null 2>&1 || true
  python3 -I - "$R/bench.csv" "$S" "$R/run.log" >> "$OUT/tour.txt" <<'PY'
import csv, sys
path, stage, log = sys.argv[1:4]
loaded = "no"
try:
    text = open(log, errors="replace").read()
    loaded = "yes" if ("the game loads StageData/%sMap1" % stage) in text else "no"
    if "CRASH" in text: loaded += ",crash"
    rows = list(csv.DictReader(open(path)))
except OSError:
    rows = []
# the course only: from the moment its file opened (phase loading/settling/measuring)
rows = [r for r in rows if r["phase"] != "menus"]
dt = [float(r["dt_ms"]) for r in rows]
s = lambda k: sum(int(r.get(k, 0) or 0) for r in rows)
print("%-34s %6d %6d %6d %6d %6d %6d %8.1f %s" % (stage, len(rows), s("new_pipelines"), s("new_shaders"),
      s("skipped_draws"), sum(d > 25 for d in dt), sum(d > 50 for d in dt), max(dt or [0]), loaded))
PY
  tail -1 "$OUT/tour.txt"
done
