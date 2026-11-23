// Frame-rate state shared by the hosts, the overlay and the GX2 layer (interp.h).
//
// Super Mario 3D World runs its logic at 60 steps per second and has no pass-based interpolation
// engine here: its 120 fps presentation is sm3dw/interp_sm3dw.cpp. What remains in this file is the
// small amount of state the display / pacing code still asks for (mode, chosen rate, display refresh
// rate, "keep game speed"), kept with the same behaviour for the 60 fps (mode 1, 60) default.
#include "interp.h"
#include <algorithm>
#include <atomic>
#include <cstdlib>
#include <string>

#include "sm3dw/interp_sm3dw.h"
#include "interp_pacing.h"
#include "render_prof.h"
#include "runtime.h"

namespace interp {
static std::atomic<uint64_t> g_executed_steps{0};
using pacing::valid_fps;
static const int g_env_fps = [] { const char* e = getenv("WWHD_INTERP_FPS"); return e ? valid_fps(atoi(e)) : 0; }();
static std::atomic<int> g_fps{g_env_fps ? g_env_fps : 60};
static std::atomic<bool> g_on{[] {
    const char* e = getenv("WWHD_INTERP");
    return e ? atoi(e) != 0 : g_env_fps != 0;
}()};
bool interp_on() { return g_on.load(std::memory_order_relaxed); }
bool enabled() { return interp_on(); }
int fps() { return g_fps.load(std::memory_order_relaxed); }


// The display: refresh rate of the TV window's screen (hosts: gfx/display.mm, the SDL main loop;
// 0 = unknown) and whether presenting waits for its vsync (Metal; Vulkan FIFO). Frames beyond the
// refresh rate never reach the screen with vsync and would only slow the game down (or, paced, be
// dropped unevenly), so the drawn rate is capped to what the display shows (pacing::cap_fps: 240 fps
// on a 120 Hz display draws 120, on a 60 Hz display 60). WWHD_DISPLAY_HZ=n overrides the detected
// rate (0: no cap; tests and benchmarks with hidden windows).
static const int g_env_hz = getenv("WWHD_DISPLAY_HZ") ? atoi(getenv("WWHD_DISPLAY_HZ")) : -1;
static std::atomic<int> g_display_hz{0};
static std::atomic<bool> g_present_vsync{true};
int display_hz() { return g_env_hz >= 0 ? g_env_hz : g_display_hz.load(std::memory_order_relaxed); }
bool present_vsync() { return g_present_vsync.load(std::memory_order_relaxed); }
int output_fps() { return pacing::cap_fps(fps(), display_hz(), present_vsync()); }
void set_display_hz(int hz) {
    if (hz < 0) hz = 0;
    const int old = g_display_hz.exchange(hz);
    if (old == hz) return;
    if (g_env_hz >= 0) LOG("[interp] display refresh rate %d Hz (WWHD_DISPLAY_HZ=%d is used)", hz, g_env_hz);
    else LOG("[interp] display refresh rate %d Hz: frame interpolation draws up to %d fps", hz, pacing::cap_fps(240, hz, present_vsync()));
}
void set_present_vsync(bool on) {
    if (g_present_vsync.exchange(on) != on) LOG("[interp] presentation %s the display's vsync", on ? "waits for" : "does not wait for");
}
int in_between() { return interp_on() ? output_fps() / 30 - 1 : 0; }
// frames drawn per 30 Hz step of the chosen rate: 1 (off), 2 (60 fps), 4 (120 fps), 8 (240 fps)
int frames_per_step() { return in_between() + 1; }
static const char* fps_name(int f) { return f == 240 ? "240 fps" : f == 120 ? "120 fps" : "60 fps"; }
void set_enabled(bool v) {
    g_on = v;
    LOG("[interp] frame interpolation %s", v ? (fps() == 240 ? "on (240 fps)" : fps() == 120 ? "on (120 fps)" : "on (60 fps)") : "off");
}
void set_fps(int f) {
    f = valid_fps(f);
    if (g_fps.exchange(f) != f && interp_on()) LOG("[interp] frame interpolation at %s", fps_name(f));
}
int mode() { return g_on.load(std::memory_order_relaxed) ? 1 : 0; }
void set_mode(int m) { set_enabled(m == 1); }
void toggle_fps(int f) {
    if (mode() == 1 && fps() == valid_fps(f)) {
        set_mode(0);
        return;
    }
    set_fps(f);
    set_mode(1);
}
const char* mode_name() {
    if (mode() != 1) return "30 fps";
    const int f = fps(), out = output_fps();
    if (out < f) return f == 240 ? (out == 120 ? "240 fps (120 shown)" : "240 fps (60 shown)") : "120 fps (60 shown)";
    return fps_name(f);
}
uint32_t effective_swap_interval(uint32_t game) { return enabled() ? std::max<uint32_t>(1, game / 2) : game; }
// Super Mario 3D World's 120 fps presents tick the virtual vsync twice as fast (sm3dw/interp_sm3dw.cpp)
int vsync_rate() { return sm3dw_interp::vsync_rate(); }

// "Keep game speed" (WWHD_INTERP_PACED): kept for the saved graphics options
static const char* const g_env_paced = getenv("WWHD_INTERP_PACED");
// Windows on ARM64 (Snapdragon X) draws 60 fps only where the Adreno driver's per-draw CPU cost
// allows: unpaced, the Outset save-state benchmark ran 16 logic steps a second (half speed, and the
// audio, which follows the game's speed, crackled)
static std::atomic<bool> g_paced{[] {
#if defined(__ANDROID__) || (defined(_WIN32) && (defined(_M_ARM64) || defined(__aarch64__)))
    return !g_env_paced || atoi(g_env_paced) != 0;
#else
    return g_env_paced && atoi(g_env_paced) != 0;
#endif
}()};
static std::atomic<bool> g_paced_hi{!g_env_paced || atoi(g_env_paced) != 0};  // 120/240 fps
static std::atomic<bool>& paced_flag(int f) { return f > 60 ? g_paced_hi : g_paced; }
static bool paced() { return paced_flag(fps()).load(std::memory_order_relaxed); }
bool paced_interpolation() { return paced(); }  // at the current frame rate
bool paced_interpolation_at(int f) { return paced_flag(f).load(std::memory_order_relaxed); }
void set_paced_interpolation_at(int f, bool on) {
    if (paced_flag(f).exchange(on) != on)
        LOG("[interp] paced interpolation at %s %s", f > 60 ? "120/240 fps" : "60 fps", on ? "on (keeps the game's speed)" : "off");
}
void set_paced_interpolation(bool on) { set_paced_interpolation_at(fps(), on); }
// share of in-between frames drawn over the last 60 steps (performance overlay), -1 before any
static std::atomic<float> g_paced_share{-1};
float paced_drawn_share() { return paced() && interp_on() ? g_paced_share.load(std::memory_order_relaxed) : -1; }

// no pass engine: every frame is a "logic" frame
bool hold_pass() { return false; }
bool repeat_input() { return false; }
bool fresh_sticks() { return false; }
const char* phase_name() { return "interp off"; }
void ss_reset() {}
void trace_read(const char*) {}
uint64_t logic_steps() { return sm3dw_interp::logic_steps(); }
void record_executed_step() { g_executed_steps.fetch_add(1, std::memory_order_relaxed); }
uint64_t executed_steps() { return g_executed_steps.load(std::memory_order_relaxed); }
}  // namespace interp
