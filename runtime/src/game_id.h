// Which game this build runs: the names that appear in logs and files. Wind Waker HD unless CMake
// defines WWHD_GAME_SM3DW (Super Mario 3D World, CMakeLists.txt). Kept tiny so merges with the upstream
// stay conflict-light.
#pragma once
#ifdef WWHD_GAME_SM3DW
#define GAME_NAME "Super Mario 3D World"
#define GAME_FILE_PREFIX "SM3DW"       // screenshots
#define GAME_HAS_ZELDA_GUEST 0         // no Wind Waker HD guest addresses (mods, cheats, interpolation hooks)
#else
#define GAME_NAME "Wind Waker HD"
#define GAME_FILE_PREFIX "WindWakerHD"
#define GAME_HAS_ZELDA_GUEST 1
#endif
