// Super Mario 3D World: 120 fps presentation with interpolated in-between pictures; the game logic
// stays at 60 steps per second (interp_sm3dw.cpp, docs/sm3dw-120fps.md).
#pragma once
#include <cstdint>

namespace sm3dw_interp {
// The chosen presentation rate: 60 (default: the game as it is) or 120. WWHD_FPS=120 chooses it at
// start; set_fps() switches it live (menus), from the next frame on.
int fps();
void set_fps(int fps);
// The rate presented right now: 120 only while the chosen rate is 120 and the frames keep up (it
// falls back to 60 when the in-between pictures make frames miss their vsync, and tries again later)
int active_fps();
// In-between pictures blend the camera and the skeletons between the last two logic steps; off
// (WWHD_FPS_BLEND=0) they repeat the last logic picture (pacing tests only)
bool blend();
void set_blend(bool on);
// for the GX2 layer and the profilers
int vsync_rate();        // virtual vsyncs per 59.94 Hz vsync: 2 while presenting 120, else 1
uint64_t logic_steps();  // game logic steps so far
bool hold_frame();       // the frame being drawn is an in-between picture
}  // namespace sm3dw_interp
