#!/bin/sh
# Super Mario 3D World: the pre-translated shader pack (runtime/src/gfx/vulkan/shader_pack.h, docs/sm3dw-shader-pack.md).
# The phone reads these instead of running the Latte decompiler. Everything runs hidden and silent.
#
#   tools/sm3dw_shader_pack.sh record [FRAMES] [BINARY]   scripted tour (title -> opening -> map), adds the shaders it
#                                                         meets to build/shaderpack/shaderpack.bin (default 12000 frames)
#   tools/sm3dw_shader_pack.sh verify [FRAMES] [BINARY]   translates every shader again and compares: must say 0 different
#   tools/sm3dw_shader_pack.sh strict [FRAMES] [BINARY]   the same tour with the decompiler forbidden: lists what is missing
#   tools/sm3dw_shader_pack.sh cemu SHADERS.bin PIPELINES.bin   a Cemu shader cache (shaderCache/transferable/<id>_shaders.bin
#                                                         and _vkpipeline.bin) translated into the working pack, nobody plays
#   tools/sm3dw_shader_pack.sh merge PACK...              adds other packs (a phone's, a friend's) to the working pack
#   tools/sm3dw_shader_pack.sh pull                       the phone's own recordings -> build/shaderpack/phone.bin, merged in
#   tools/sm3dw_shader_pack.sh apk                        puts the working pack into the APK build (android/app/local-assets)
#   tools/sm3dw_shader_pack.sh baseline MODE [BINARY] [PACK...]   the desktop baseline for one translator configuration
#                                                         (MODE default | fast = WWHD_MUL_MODE=fast): the Cemu head start
#                                                         (build/shaderpack/run-cemu/headstart.bin, from `cemu`) translated
#                                                         by BINARY, merged with the given packs (live packs of the same
#                                                         configuration, e.g. a stutter sweep's), installed as
#                                                         game3dw/shadercache/shaderpack-<fingerprint>.bin (docs/stutter.md)
#
# The more of the game a pack has seen, the fewer shaders the phone must translate: play on the PC (the normal game
# records into its own cache), `merge` that, and run `strict` to see what is still missing. A pack is made from your
# game files: like the APK, keep it to yourself. BINARY: default build/cmake3dw_vk/wwhd (a Vulkan build).
set -e
cd "$(dirname "$0")/.."
CMD=${1:?record | verify | strict | merge | pull | apk}; shift
DIR=build/shaderpack; PACK=$DIR/shaderpack.bin
mkdir -p "$DIR"
PKG=org.sm3dwrecomp.sm3dw

tour() {  # NAME FRAMES BINARY [ENV...]: one hidden run with empty SPIR-V and pipeline caches, so only the pack helps
    NAME=$1; FRAMES=$2; BIN=$3; shift 3
    RUN=$DIR/run-$NAME; rm -rf "$RUN"; mkdir -p "$RUN/spirv" "$RUN/pipe"
    P="700-706:8000,1000-1006:8000,1400-1406:8000,1800-1806:8000,2300-2306:8000,2800-2806:8000"
    f=3000; while [ "$f" -le "$FRAMES" ]; do P="$P,$f-$((f+5)):8000"; f=$((f+300)); done
    env WWHD_HIDDEN_WINDOWS=1 WWHD_NO_HOST_INPUT=1 WWHD_NO_AUDIO=1 WWHD_ASPECT=2340:1080 WWHD_PRESS="$P" \
        WWHD_VK_SHADER_CACHE="$RUN/spirv" WWHD_VK_PIPELINE_CACHE_DIR="$RUN/pipe" WWHD_HEADSTART=0 \
        WWHD_VK_SHADER_PACK="$PACK" WWHD_EXIT_AT_FRAME="$FRAMES" "$@" \
        "$BIN" --game game3dw > "$RUN/run.log" 2>&1 || { echo "run failed, see $RUN/run.log"; exit 1; }
    grep -a -E '\[shader pack\] (saved|verify|missing)|MISMATCH' "$RUN/run.log" | sed 's/^/  /'
}

case "$CMD" in
record) tour record "${1:-12000}" "${2:-build/cmake3dw_vk/wwhd}" ;;
verify) tour verify "${1:-12000}" "${2:-build/cmake3dw_vk/wwhd}" WWHD_VK_SHADER_PACK_VERIFY=1 ;;
strict) tour strict "${1:-12000}" "${2:-build/cmake3dw_vk/wwhd}" WWHD_VK_SHADER_PACK_STRICT=1 ;;
cemu)
    SH=${1:?Cemu _shaders.bin}; PL=${2:?Cemu _vkpipeline.bin}; BIN=${3:-build/cmake3dw_vk/wwhd}
    RUN=$DIR/run-cemu; rm -rf "$RUN"; mkdir -p "$RUN/spirv" "$RUN/pipe"
    python3 -I tools/cemu_to_headstart.py "$SH" "$PL" "$RUN/headstart.bin"
    # --warm-shaders exits 1 when some records fail (about 1%: glslang refuses them), the pack is saved anyway
    env WWHD_HIDDEN_WINDOWS=1 WWHD_NO_HOST_INPUT=1 WWHD_NO_AUDIO=1 WWHD_HEADSTART="$RUN/headstart.bin" \
        WWHD_VK_SHADER_PACK="$RUN/cemu.pack" WWHD_VK_SHADER_CACHE="$RUN/spirv" WWHD_VK_PIPELINE_CACHE_DIR="$RUN/pipe" \
        "$BIN" --game game3dw --warm-shaders > "$RUN/run.log" 2>&1 || true
    grep -a -E 'headstart\] known|shader pack\] saved' "$RUN/run.log" | sed 's/^/  /'
    [ -f "$RUN/cemu.pack" ] || { echo "no pack made, see $RUN/run.log"; exit 1; }
    "$0" merge "$RUN/cemu.pack" ;;
merge)
    [ -f "$PACK" ] && set -- "$PACK" "$@"
    python3 -I tools/shaderpack.py merge "$PACK.new" "$@" && mv "$PACK.new" "$PACK" ;;
pull)
    ADB=${ADB:-adb}
    SRC=$($ADB shell "find /storage/emulated/0/Android/data/$PKG/files -name shaderpack.bin" | tr -d '\r' | head -1)
    [ -n "$SRC" ] || { echo "no shaderpack.bin on the phone yet (play first)"; exit 1; }
    $ADB pull "$SRC" "$DIR/phone.bin" > /dev/null
    python3 -I tools/shaderpack.py info "$DIR/phone.bin"
    "$0" merge "$DIR/phone.bin" ;;
baseline)
    MODE=${1:?default | fast}; BIN=${2:-build/vk/wwhd}; shift; [ $# -gt 0 ] && shift
    HS=${HEADSTART:-$DIR/run-cemu/headstart.bin}
    [ -f "$HS" ] || { echo "no head start at $HS (tools/sm3dw_shader_pack.sh cemu ...)"; exit 1; }
    RUN=$DIR/run-base-$MODE; rm -rf "$RUN"; mkdir -p "$RUN/spirv" "$RUN/pipe"
    MUL=; [ "$MODE" = fast ] && MUL=fast
    env WWHD_HIDDEN_WINDOWS=1 WWHD_NO_HOST_INPUT=1 WWHD_NO_AUDIO=1 WWHD_HEADSTART="$HS" ${MUL:+WWHD_MUL_MODE=$MUL} \
        WWHD_VK_SHADER_PACK="$RUN/base.pack" WWHD_VK_SHADER_CACHE="$RUN/spirv" WWHD_VK_PIPELINE_CACHE_DIR="$RUN/pipe" \
        "$BIN" --game game3dw --warm-shaders > "$RUN/run.log" 2>&1 || true
    [ -f "$RUN/base.pack" ] || { echo "no pack made, see $RUN/run.log"; exit 1; }
    python3 -I tools/shaderpack.py merge "$RUN/merged.pack" "$RUN/base.pack" "$@"
    FP=$(python3 -I tools/shaderpack.py info "$RUN/merged.pack" | sed -n 's/.*fingerprint \([0-9a-f]*\);.*/\1/p')
    mkdir -p game3dw/shadercache
    cp "$RUN/merged.pack" "game3dw/shadercache/shaderpack-$FP.bin"
    echo "installed game3dw/shadercache/shaderpack-$FP.bin"
    exit 0 ;;
apk)
    [ -f "$PACK" ] || { echo "no pack yet: tools/sm3dw_shader_pack.sh record"; exit 1; }
    mkdir -p android/app/local-assets
    cp "$PACK" android/app/local-assets/shaderpack.bin
    python3 -I tools/shaderpack.py info android/app/local-assets/shaderpack.bin
    echo "now: android/build_sm3dw_apk.sh" ;;
*) echo "unknown command $CMD"; exit 2 ;;
esac
[ -f "$PACK" ] && python3 -I tools/shaderpack.py info "$PACK" || true
