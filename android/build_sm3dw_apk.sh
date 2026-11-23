#!/bin/sh
# Super Mario 3D World test APK (debuggable; the game is imported in the app from the player's dump): after
# android/build_native.sh with GEN_DIR=build/gen3dw EXTRA_CMAKE_ARGS=-DWWHD_GAME_RPX=RedCarpet.rpx
# usage: [JAVA_HOME=jdk17] [APK_NAME=x.apk] [VERSION_NAME=0.5] [DEFAULT_FPS=60|120] [DEFAULT_AUTO_RES=off|60|120] android/build_sm3dw_apk.sh   -> dist/$APK_NAME (default SM3DW-test.apk) (name "Super Mario 3D World", the game's icon)
set -e
cd "$(dirname "$0")"
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d "$HOME/Library/Android/sdk" ]; then ANDROID_HOME=$HOME/Library/Android/sdk; else ANDROID_HOME=$HOME/Android/Sdk; fi
fi
export ANDROID_HOME
python3 make_icon.py ../game3dw >/dev/null 2>&1 || ../build/venv/bin/python make_icon.py ../game3dw
cp app/src/main/res/values/strings.xml /tmp/strings.sm3dw.bak
trap 'cp /tmp/strings.sm3dw.bak app/src/main/res/values/strings.xml' EXIT
sed 's|<string name="app_name">Wind Waker HD</string>|<string name="app_name">Super Mario 3D World</string>|' /tmp/strings.sm3dw.bak > app/src/main/res/values/strings.xml  # no sed -i: BSD and GNU differ
# The RedCarpet.rpx the native code was recompiled from: the app's game import accepts only a dump with the same
# one. RPX_SHA256 overrides; default: game3dw's (the folder the code is generated from), else the EUR Rev 1 build's.
if [ -z "${RPX_SHA256:-}" ]; then
  if [ -f ../game3dw/code/RedCarpet.rpx ]; then
    RPX_SHA256=$({ sha256sum ../game3dw/code/RedCarpet.rpx 2>/dev/null || shasum -a 256 ../game3dw/code/RedCarpet.rpx; } | cut -c1-64)
  else
    RPX_SHA256=a25432104adaefd980f9e24559b59a3d79781e68980fd37df6de206fff744937
  fi
fi
echo "game import expects RedCarpet.rpx $RPX_SHA256"
echo "menu defaults: ${DEFAULT_FPS:-60} fps, automatic resolution ${DEFAULT_AUTO_RES:-off}"
# the pre-translated shaders (tools/sm3dw_shader_pack.sh apk -> app/local-assets, git-ignored): a fresh install
# then needs no adb at all
[ -f app/local-assets/shaderpack.bin ] || echo "note: no app/local-assets/shaderpack.bin (tools/sm3dw_shader_pack.sh apk): the phone translates every shader itself"
./gradlew assembleDebug -PwwhdAppId=org.sm3dwrecomp.sm3dw -PwwhdNdk=${NDK_VERSION:-28.2.13676358} -PwwhdVersionName=${VERSION_NAME:-0.5-sm3dw} \
  -PwwhdRpxSha256="$RPX_SHA256" -PwwhdDefaultFps=${DEFAULT_FPS:-60} -PwwhdDefaultAutoRes=${DEFAULT_AUTO_RES:-off} \
  --no-daemon --max-workers=2
mkdir -p ../dist && cp app/build/outputs/apk/debug/app-debug.apk "../dist/${APK_NAME:-SM3DW-test.apk}"
