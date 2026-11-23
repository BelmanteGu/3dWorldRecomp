// The Android app's own menu (android/app/.../menu: the Belmante ports' "ds" design system): it pauses the
// game while it is open and changes the live settings. The values themselves live in the app (its
// preferences); they reach the game at start as WWHD_* variables (MenuSettings.java) and while it runs
// through nativeSetOption, so nothing here is saved.
#include "app_menu.h"
#include "gx2/gx2.h"

#include <atomic>
#include <chrono>
#include <cstdlib>
#include <string>
#include <thread>

#include "audio_out.h"
#include "autores.h"
#include "sm3dw/interp_sm3dw.h"
#include "gfx/renderer.h"
#include "overlay/hostui.h"
#include "overlay/overlay.h"
#include "runtime.h"
#include "rumble.h"
#include "mic.h"
#include "motion/motion.h"
#include "gfx/display_modes.h"
#ifdef WWHD_HAS_VULKAN
#include "gfx/vulkan/settings.h"
#endif

namespace app_menu {
namespace {
std::atomic<bool> g_paused{false};
float g_volume_before = 1.0f;
}

bool paused() { return g_paused.load(std::memory_order_acquire); }

void set_paused(bool on) {
    if (on == g_paused.load()) return;
    if (on) {
        g_volume_before = audio::master_volume();
        audio::set_master_volume(0.0f, false);  // silent while paused, not saved
    } else {
        audio::set_master_volume(g_volume_before, false);
    }
    g_paused.store(on, std::memory_order_release);
    LOG("[menu] game %s", on ? "paused" : "resumed");
}

// GX2WaitForVsync: the game advances one step per vsync, so holding it here stops the game logic
void wait_while_paused() {
    while (g_paused.load(std::memory_order_acquire)) std::this_thread::sleep_for(std::chrono::milliseconds(16));
}

bool set_option(const std::string& name, const std::string& value) {
    const bool on = value == "1";
    if (name == "res") {
        const float s = std::strtof(value.c_str(), nullptr);
        if (s < 0.25f || s > 4.0f) return false;
        render::set_res_scale(s);
    } else if (name == "aniso") {
        render::set_aniso(on);
    } else if (name == "fxaa") {
        render::set_fxaa(on);
    } else if (name == "fps") {
        overlay::set_perf_shown(on);
    } else if (name == "volume") {
        const float v = std::strtof(value.c_str(), nullptr) / 100.0f;
        if (g_paused.load()) g_volume_before = v; else audio::set_master_volume(v, false);
#ifdef WWHD_HAS_VULKAN
    } else if (name == "shadows") {
        gfxvk::set_cast_shadows(on);
    } else if (name == "rt_mips") {
        gfxvk::set_rt_mips(on);
    } else if (name == "present") {
        const int m = value == "mailbox" ? gfxvk::kPresentMailbox : gfxvk::kPresentFifo;
        hostui::post([m] { gfxvk::set_present_mode(m); });
#endif
    } else if (name == "frameskip") {
        gx2::set_frameskip(value == "on" ? 2 : value == "auto" ? 1 : 0);
    } else if (name == "rate") {  // presentation rate: the logic stays at 60; 120 adds in-between pictures, 30 presents
        // every other frame (docs/30fps.md)
        if (value != "30" && value != "60" && value != "120") return false;
        sm3dw_interp::set_fps(std::atoi(value.c_str()));
    } else if (name == "autores") {  // the automatic resolution's target: off, 30, 60 or 120 fps
        if (value != "off" && value != "30" && value != "60" && value != "120") return false;
        autores::set_target_fps(value == "off" ? 0 : std::atoi(value.c_str()));
    } else if (name == "filter") {
        const int f = value == "smooth" ? 0 : value == "integer" ? 2 : value == "quality" ? 3 : 1;
        hostui::post([f] { hostui::set_scale_filter(f); });
    } else if (name == "drc") {
        const int m = value == "pip" ? 1 : value == "auto" ? 2 : 3;  // gfx::DrcMode: pip, auto, off
        hostui::post([m] { hostui::set_drc_mode(m); });
    } else if (name == "rumble") {
        rumble::set_enabled(on);  // the phone's vibration and any controller's motors
    } else if (name == "gyro") {
        motion::Settings m = motion::settings();
        m.source = on ? motion::kController : motion::kOff;  // the phone's sensors play as a controller with a gyro
        motion::set_settings(m);
    } else if (name == "tvtouch") {
        gfx::g_tv_touch = on;
    } else if (name == "mic") {  // the GamePad microphone: off, mic (the app has the permission), button
        if (value != "off" && value != "mic" && value != "button") return false;
        mic::set_mode(mic::parse_mode(value.c_str()));
    } else if (name == "mic_sens") {
        mic::set_sensitivity(std::atoi(value.c_str()));
    } else {
        return false;
    }
    LOG("[menu] %s = %s", name.c_str(), value.c_str());
    return true;
}
}  // namespace app_menu

#ifdef __ANDROID__
#include <jni.h>
static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}
extern "C" JNIEXPORT void JNICALL Java_org_wwhdrecomp_wwhd_WwhdActivity_nativeSetPaused(JNIEnv*, jclass, jboolean on) {
    app_menu::set_paused(on);
}
// the diagnostics export (WwhdActivity.exportDiagnostics, Diagnostics.java): the performance report text
extern "C" JNIEXPORT jstring JNICALL Java_org_wwhdrecomp_wwhd_WwhdActivity_nativeDiagnosticReport(JNIEnv* env, jclass) {
    std::string report;
    try { report = overlay::performance_report_text(); }
    catch (const std::exception& e) { report = std::string("(no report: ") + e.what() + ")\n"; }
    // NewStringUTF takes modified UTF-8: keep the report to printable ASCII, tabs and new lines
    for (char& c : report) if ((unsigned char)c >= 0x80 || ((unsigned char)c < 0x20 && c != '\n' && c != '\t')) c = '?';
    return env->NewStringUTF(report.c_str());
}
extern "C" JNIEXPORT jboolean JNICALL Java_org_wwhdrecomp_wwhd_WwhdActivity_nativeSetOption(JNIEnv* env, jclass, jstring name, jstring value) {
    return app_menu::set_option(jstr(env, name), jstr(env, value)) ? JNI_TRUE : JNI_FALSE;
}
#endif
