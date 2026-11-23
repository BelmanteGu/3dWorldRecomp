#!/bin/sh
# Copies game3dw/{code,meta,content} into the sm3dw app's files/game on a phone, folder by folder.
# usage: tools/android_push_game.sh [PACKAGE]    (default org.sm3dwrecomp.sm3dw; the APK must be debuggable)
#
# Why not `adb push` straight to Android/data: files pushed by the shell are unreadable for the app.
# They go to /data/local/tmp first and `run-as` (the app's own uid) copies them; one folder at a time,
# so a nearly full phone only needs room for the largest folder twice. Resumable: a folder whose file
# count already matches is skipped, and every adb call waits for the device to be back.
P=${1:-org.sm3dwrecomp.sm3dw}
F=/storage/emulated/0/Android/data/$P/files
TMP=/data/local/tmp/sm3dw_part
cd "$(dirname "$0")/.." || exit 1

wait_device() { until adb devices | grep -q "device$"; do sleep 3; done; }
adbw() { wait_device; adb "$@"; }

copy_part() {  # copy_part SRC_DIR DEST_PARENT_ON_PHONE
    src=$1; dest=$2; name=$(basename "$src")
    want=$(find "$src" -type f | wc -l | tr -d ' ')
    have=$(adbw shell "find $dest/$name -type f 2>/dev/null | wc -l" | tr -d '\r ')
    if [ "$have" = "$want" ] && [ "$want" != 0 ]; then echo "$name: already there ($want files)"; return; fi
    adbw shell "rm -rf $TMP; mkdir -p $TMP"
    adbw push "$src" "$TMP/" >/dev/null 2>&1
    adbw shell "chmod -R a+rX $TMP"
    adbw shell "run-as $P sh -c 'rm -rf $dest/$name; mkdir -p $dest; cp -r $TMP/$name $dest/'"
    adbw shell "rm -rf $TMP"
    have=$(adbw shell "find $dest/$name -type f | wc -l" | tr -d '\r ')
    echo "$name: $have of $want files"
}

adbw shell "run-as $P sh -c 'mkdir -p $F/game/content'"
copy_part game3dw/code "$F/game"
copy_part game3dw/meta "$F/game"
for d in game3dw/content/*; do copy_part "$d" "$F/game/content"; done
echo done
