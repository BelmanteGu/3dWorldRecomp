// The Android app's own menu: the game pauses while it is open, and it changes live settings (app_menu.cpp).
#pragma once
#include <string>

namespace app_menu {
bool paused();
void set_paused(bool on);
void wait_while_paused();  // GX2WaitForVsync
// A setting by the app's key ("res", "shadows", "rt_mips", "aniso", "fxaa", "fps", "volume", "present",
// "filter", "drc", "frameskip", "rumble", "gyro", "tvtouch", "mic",
// "mic_sens") and value; false for an unknown key or value.
bool set_option(const std::string& name, const std::string& value);
}
