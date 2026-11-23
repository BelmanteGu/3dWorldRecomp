#!/bin/sh
# Super Mario 3D World: the pipeline recipe pack and the shader baseline for players who have not played yet (#16,
# docs/stutter.md "Pacote de receitas"). A Vulkan pipeline that first appears mid-game is compiled while you play:
# its draws wait or are left out. The pack has them compiled before they are needed, at start-up.
#
#   tools/sm3dw_recipe_pack.sh build MODE GPU [RECIPES...]   the pack of one translator configuration on one GPU:
#        the pipelines of the Cemu head start's linked records (build/shaderpack/run-cemu/headstart.bin, made by
#        tools/sm3dw_shader_pack.sh cemu: every pipeline Cemu met, with the registers of its draw), built by
#        --warm-shaders, merged with the installed pack and with RECIPES (recipe databases of the same GPU: a stage
#        tour's work dir, a player's config dir: <dir>/shader-prebuild/live/vk-*.bin.recipes; directories are
#        searched), installed as game3dw/shadercache/pipelines/<translator fingerprint>-<gpu>.recipes
#   tools/sm3dw_recipe_pack.sh shaders MODE PACK...          merges live shader packs (vulkan-shaders/shaderpack.bin
#        of a tour or a player) of that configuration into the installed baseline shaderpack-<fingerprint>.bin
#
# MODE: fast (WWHD_MUL_MODE=fast, what players run) | default (strict multiplication). GPU: a WWHD_VK_DEVICE
# substring (NVIDIA, Intel). BINARY: $WWHD_BIN or build/vk/wwhd.
# A recipe database is only valid for the GPU and driver it was made on (vendor, device, driver version and
# pipeline-cache UUID are in its header): the game ignores the others, so one directory can hold every GPU's pack.
# After a driver update, build it again. Made from your game files: keep it out of the repository.
set -e
cd "$(dirname "$0")/.."
CMD=${1:?build | shaders}; shift
BIN=${WWHD_BIN:-build/vk/wwhd}; GAME=${GAME:-game3dw}
MODE=${1:?MODE: fast | default}; shift
MUL=strict; [ "$MODE" = fast ] && MUL=fast
case "$MODE" in fast|default) ;; *) echo "MODE is fast or default"; exit 2;; esac
DIR=build/shaderpack
mkdir -p "$DIR" "$GAME/shadercache/pipelines"

translator() {  # the baseline shader pack of this configuration and its fingerprint (written by sm3dw_shader_pack.sh)
    RUN=$DIR/fp-$MODE; rm -rf "$RUN"; mkdir -p "$RUN/spirv" "$RUN/pipe"
    env WWHD_HIDDEN_WINDOWS=1 WWHD_NO_HOST_INPUT=1 WWHD_NO_AUDIO=1 WWHD_MUL_MODE=$MUL WWHD_HEADSTART=0 \
        WWHD_VK_SHADER_CACHE="$RUN/spirv" WWHD_VK_PIPELINE_CACHE_DIR="$RUN/pipe" WWHD_VK_RECIPE_PACK=0 \
        "$BIN" --game "$GAME" --warm-shaders > "$RUN/run.log" 2>&1 || true
    sed -n 's/.*shaderpack-\([0-9a-f]\{16\}\)\.bin.*/\1/p' "$RUN/run.log" | head -1
}

case "$CMD" in
build)
    GPU=${1:?GPU: NVIDIA | Intel}; shift
    TAG=$(echo "$GPU" | tr 'A-Z ' 'a-z_')
    FP=$(translator)
    [ -n "$FP" ] || { echo "no baseline shader pack for $MODE (tools/sm3dw_shader_pack.sh baseline $MODE)"; exit 1; }
    BASE=$GAME/shadercache/shaderpack-$FP.bin
    HS=${HEADSTART:-$DIR/run-cemu/headstart.bin}
    [ -f "$HS" ] || { echo "no head start at $HS (tools/sm3dw_shader_pack.sh cemu ...)"; exit 1; }
    RUN=$DIR/recipes-$MODE-$TAG; rm -rf "$RUN"; mkdir -p "$RUN/spirv" "$RUN/pipe" "$RUN/merge" "$RUN/in"
    cp "$BASE" "$RUN/base.pack"   # the run records into its pack: a copy, the baseline stays as it is
    echo "1/2 pipelines of the head start's linked records ($MODE, $GPU)"
    env WWHD_HIDDEN_WINDOWS=1 WWHD_NO_HOST_INPUT=1 WWHD_NO_AUDIO=1 WWHD_VK_DEVICE="$GPU" WWHD_MUL_MODE=$MUL \
        WWHD_HEADSTART="$HS" WWHD_VK_SHADER_PACK="$RUN/base.pack" WWHD_VK_SHADER_CACHE="$RUN/spirv" \
        WWHD_VK_PIPELINE_CACHE_DIR="$RUN/pipe" WWHD_VK_RECIPE_PACK=0 \
        "$BIN" --game "$GAME" --warm-shaders > "$RUN/cemu.log" 2>&1 || true
    grep -a -E 'linked records|linked-record' "$RUN/cemu.log" | sed 's/^/  /'
    n=0
    for f in "$RUN"/pipe/*.recipes "$GAME/shadercache/pipelines/$FP-$TAG.recipes"; do
        [ -f "$f" ] && { n=$((n+1)); cp "$f" "$RUN/in/$n.recipes"; }
    done
    for src in "$@"; do
        if [ -d "$src" ]; then
            for f in $(find "$src" -name 'vk-*.recipes' -o -name '*.recipes' | sort -u); do n=$((n+1)); cp "$f" "$RUN/in/$n.recipes"; done
        elif [ -f "$src" ]; then n=$((n+1)); cp "$src" "$RUN/in/$n.recipes"; fi
    done
    echo "2/2 merging $n recipe databases (other GPUs' are skipped)"
    env WWHD_HIDDEN_WINDOWS=1 WWHD_NO_HOST_INPUT=1 WWHD_NO_AUDIO=1 WWHD_VK_DEVICE="$GPU" WWHD_MUL_MODE=$MUL \
        WWHD_HEADSTART=0 WWHD_VK_SHADER_CACHE="$RUN/spirv" WWHD_VK_PIPELINE_CACHE_DIR="$RUN/merge" \
        WWHD_VK_RECIPE_PACK="$RUN/in" WWHD_VK_RECIPE_MERGE=1 \
        "$BIN" --game "$GAME" --warm-shaders > "$RUN/merge.log" 2>&1 || true
    grep -a -E 'recipe pack|saved [0-9]+ recipes' "$RUN/merge.log" | tail -3 | sed 's/^/  /'
    OUTF=$(ls "$RUN"/merge/*.recipes 2>/dev/null | head -1)
    [ -n "$OUTF" ] || { echo "nothing merged, see $RUN/merge.log"; exit 1; }
    cp "$OUTF" "$GAME/shadercache/pipelines/$FP-$TAG.recipes"
    ls -la "$GAME/shadercache/pipelines/$FP-$TAG.recipes" ;;
shaders)
    FP=$(translator)
    BASE=$GAME/shadercache/shaderpack-$FP.bin
    [ -f "$BASE" ] || { echo "no baseline $BASE (tools/sm3dw_shader_pack.sh baseline $MODE)"; exit 1; }
    python3 -I tools/shaderpack.py merge "$DIR/merged-$FP.pack" "$BASE" "$@"
    cp "$DIR/merged-$FP.pack" "$BASE"
    python3 -I tools/shaderpack.py info "$BASE" ;;
*) echo "unknown command $CMD"; exit 2 ;;
esac
