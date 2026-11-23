#!/bin/sh
# Opens Super Mario 3D World on the Mac in a visible window, in the Galaxy S23's shape, GamePad screen off.
# usage: tools/sm3dw_play.sh [ASPECT] [BINARY]   (default 2340:1080, build/cmake3dw/wwhd = Metal)
#   build/cmake3dw_vk/wwhd is the Vulkan (MoltenVK) build, the renderer the phone uses.
# Settings menu: F1. Close the window or press Cmd+Q to quit.
cd "$(dirname "$0")/.." || exit 1
WWHD_DRC_MODE=off WWHD_ASPECT=${1:-2340:1080} exec "${2:-build/cmake3dw/wwhd}" --game game3dw
