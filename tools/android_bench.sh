#!/bin/sh
# fps percentiles and thermals of a run on an adb-connected phone, from logcat. Does nothing to the phone
# beyond (re)starting the app: run it only when you want the phone used.
# usage: tools/android_bench.sh [SECONDS] [PACKAGE]    (default 90 s, org.sm3dwrecomp.sm3dw)
# Prints: n, min, p10, median, avg of the per-second "[fps]" lines (the first 5 s skipped), the seconds under
# 20 fps, "[stall]" / "[pipelines]" counts and the thermal headroom lines. Measure with a cool phone, off the
# charger: heat lowers the clocks and moves every number.
SECS=${1:-90}; P=${2:-org.sm3dwrecomp.sm3dw}
adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard 2>/dev/null
adb logcat -c; adb shell am force-stop "$P"
adb shell monkey -p "$P" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep "$SECS"
adb logcat -d -s wwhd > /tmp/android_bench.txt
grep -a '\[fps\]' /tmp/android_bench.txt | sed -E 's/.*\] ([0-9.]+) swaps.*/\1/' | tail -n +6 | sort -n |
  awk 'NF{a[++n]=$1; s+=$1} END{if(n) printf "n=%d min=%s p10=%s median=%s avg=%.1f\n", n, a[1], a[int(n*0.1)+1], a[int(n/2)+1], s/n}'
echo "under 20 fps: $(grep -a '\[fps\]' /tmp/android_bench.txt | sed -E 's/.*\] ([0-9.]+) swaps.*/\1/' | awk '$1<20' | tr '\n' ' ')"
echo "stalls: $(grep -ac '\[stall\]' /tmp/android_bench.txt)  skipped-draw reports: $(grep -ac '\[pipelines\]' /tmp/android_bench.txt)"
grep -a 'thermal' /tmp/android_bench.txt | tail -3 | cut -c40-120
adb shell dumpsys battery | grep -E '^  (level|temperature)'
