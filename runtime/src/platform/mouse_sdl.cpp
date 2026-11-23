#include "mouse_sdl.h"
#include "keycodes.h"
#include "../motion/motion.h"
#include "../overlay/overlay.h"
#include "../runtime.h"

#include <atomic>
#include <cstdlib>

// The host mouse as a gyro (motion.h): while the game aims, the pointer is captured so that Steam Input's
// gyro-to-mouse (or the mouse) never stops at the screen's edge; released when the aim ends.
namespace mods {
namespace {
SDL_Window* tv = nullptr;
bool gyro_capture = false;
void update_gyro_capture() {
    const bool want = tv && motion::mouse_drives_gyro() && !overlay::captures() && SDL_GetKeyboardFocus() == tv &&
                      !getenv("WWHD_NO_HOST_INPUT");
    if (want && !gyro_capture) {
        gyro_capture = true;
        if (SDL_SetWindowRelativeMouseMode(tv, true)) LOG("[gyro] mouse captured while the game aims");
    } else if (!want && gyro_capture) {
        gyro_capture = false;
        if (tv) SDL_SetWindowRelativeMouseMode(tv, false);
    }
}
}  // namespace
bool mouse_captured() { return false; }
void mouse_init(void* window) { tv = static_cast<SDL_Window*>(window); }
void update_mouse() { update_gyro_capture(); }
bool handle_mouse_event(const SDL_Event& event) {
    update_mouse();
    if (!tv || getenv("WWHD_NO_HOST_INPUT")) return false;
    const SDL_WindowID id = SDL_GetWindowID(tv);
    if (event.type == SDL_EVENT_MOUSE_MOTION && event.motion.windowID == id) {
        motion::mouse_motion(event.motion.xrel, event.motion.yrel);
        if (motion::mouse_drives_gyro()) return true;
    }
    return false;
}
}  // namespace mods
