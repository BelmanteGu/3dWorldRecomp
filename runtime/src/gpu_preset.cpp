#include "gpu_preset.h"

#include <cstdlib>
#include <cstring>
#include <string>

#include "autores.h"
#include "gfx/display_modes.h"
#include "overlay/hostui.h"
#include "runtime.h"
#include "sm3dw/interp_sm3dw.h"

namespace gpu_preset {
namespace {
std::string g_active;

void set_default(const char* name, const char* value) {
#ifdef _WIN32
    if (!getenv(name)) _putenv_s(name, value);
#else
    setenv(name, value, 0);
#endif
}
bool env_set(const char* name) {
    const char* e = getenv(name);
    return e && *e;
}
}  // namespace

const char* active() { return g_active.c_str(); }

float apply_explicit(int argc, char** argv) {
    std::string preset = getenv("WWHD_PRESET") ? getenv("WWHD_PRESET") : "";
    for (int i = 1; i < argc; ++i) {
        if (!strcmp(argv[i], "--intel120")) preset = "intel120";
        if (!strcmp(argv[i], "--intel60")) preset = "intel60";
        if (!strcmp(argv[i], "--intel30")) preset = "intel30";
    }
    if (preset != "intel60" && preset != "intel120" && preset != "intel30") return 0;
    const bool fps120 = preset == "intel120", fps30 = preset == "intel30";
    // Each part stays overridable by a value already set:
    //   WWHD_FPS                 120 (in-between pictures), 60, or 30 (full speed, every other frame presented:
    //                            docs/30fps.md)
    //   WWHD_AUTO_RES            the internal resolution follows the GPU time (ceiling WWHD_RES_SCALE; when nothing is
    //                            chosen 1.25 = 900p internal at 60 fps, 1.5 = 1080p at 120)
    //   WWHD_AUTO_RES_HEADROOM   share of the frame period the GPU may use
    //   WWHD_MUL_MODE=fast       plain multiplication in shaders (+20% GPU; docs/benchmark.md)
    //   WWHD_DRC_MODE=off        the GamePad picture is not drawn
    //   WWHD_VK_NARROW_BARRIERS=1 precise barriers instead of whole-GPU ones (desktop only: Adreno device loss, #104)
    set_default("WWHD_FPS", fps120 ? "120" : fps30 ? "30" : "60");
    set_default("WWHD_AUTO_RES", fps120 ? "120" : fps30 ? "30" : "60");
    // 60: 85% of 16.7 ms. The integrated GPU shares the package's power and heat with the CPU, and its clock
    // drops when the notebook gets warm: the margin keeps the frame inside the period then too.
    set_default("WWHD_AUTO_RES_HEADROOM", fps120 ? "0.95" : "0.85");
    set_default("WWHD_MUL_MODE", "fast");
    set_default("WWHD_DRC_MODE", "off");
#ifndef __ANDROID__
    set_default("WWHD_VK_NARROW_BARRIERS", "1");  // precise barriers: -3.5% GPU on the Intel UHD (docs/intel60.md)
#endif
    // read before main(): applied after render::init(). 60 fps: the heavy scenes settle near 1.0x on an Intel UHD;
    // a 1.5x ceiling made the light ones (menus, loading screens) climb and the next heavy scene start far over
    // budget (whole session: frames >25 ms 39 at 1.5, 26 at 1.25, 28 at 1.0; docs/intel60.md)
    const float ceiling = env_set("WWHD_RES_SCALE") ? 0.0f : fps120 || fps30 ? 1.5f : 1.25f;
    autores::init();  // main() read WWHD_AUTO_RES and WWHD_FPS before this point: apply them again
    sm3dw_interp::set_fps(atoi(getenv("WWHD_FPS")));  // 30, 60 or 120
    g_active = preset;
    LOG("[%s] on: %s fps, automatic resolution (ceiling %s, headroom %s), %s multiplication, GamePad picture %s",
        preset.c_str(), getenv("WWHD_FPS"), ceiling ? (fps120 || fps30 ? "1.5" : "1.25") : getenv("WWHD_RES_SCALE"),
        getenv("WWHD_AUTO_RES_HEADROOM"), strcmp(getenv("WWHD_MUL_MODE"), "fast") ? "strict" : "fast",
        strcmp(getenv("WWHD_DRC_MODE"), "off") ? "on" : "off");
    return ceiling;
}

float device_chosen(bool integrated, const char* name) {
#ifdef __ANDROID__
    (void)integrated; (void)name;
    return 0;
#else
    if (!integrated || !g_active.empty()) return 0;
    if (const char* p = getenv("WWHD_PRESET"); p && *p) return 0;  // "none", or a preset applied above
    if (env_set("WWHD_BENCH")) return 0;                            // the benchmark measures what it is told to
    g_active = "auto-intel60";
    // as WWHD_PRESET=intel60, but only where nothing was chosen. Variables without an overlay option are filled
    // in; the overlay's options are set here and the saved ones (settings.ini, loaded right after) win.
    std::string saved;
    set_default("WWHD_MUL_MODE", "fast");
    set_default("WWHD_AUTO_RES_HEADROOM", "0.85");
    set_default("WWHD_VK_NARROW_BARRIERS", "1");
    // 30 fps (WWHD_FPS=30, docs/30fps.md): the GPU has the period of a presented frame, 33 ms
    if (!env_set("WWHD_AUTO_RES") && !hostui::get("autoResFps", saved)) autores::set_target_fps(sm3dw_interp::fps() == 30 ? 30 : 60);
    if (!env_set("WWHD_DRC_MODE") && !hostui::get("drcMode", saved)) gfx::g_mode = gfx::kDrcOff;
    const bool ceiling = !env_set("WWHD_RES_SCALE") && !hostui::get("resScale", saved);
    LOG("[auto-intel60] integrated GPU (%s): 60 fps defaults: automatic resolution %s, %s multiplication, "
        "GamePad picture %s (WWHD_PRESET=none turns this off)", name,
        autores::active() ? (ceiling ? "on (ceiling 1.25)" : "on") : "as saved",
        strcmp(getenv("WWHD_MUL_MODE"), "fast") ? "strict" : "fast",
        gfx::g_mode == gfx::kDrcOff ? "off" : "as saved");
    return ceiling ? 1.25f : 0.0f;
#endif
}
}  // namespace gpu_preset
