#include "perf_hint.h"

#ifdef __ANDROID__
#include <android/performance_hint.h>
#include <android/thermal.h>
#include <sched.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <vector>

#include "../runtime.h"
#include "memory_trim.h"
#include "heat_guard.h"
#include "../overlay/android_telemetry.h"
#include <jni.h>

#include "../interp.h"

namespace perf_hint {
namespace {
std::mutex g_mu;
std::vector<int32_t> g_tids;
bool g_changed = false;
APerformanceHintSession* g_session = nullptr;
int64_t g_target = 0;
// the 60 fps choice, and the mode adapt_60 last set (-1: none yet); another mode than that one was
// chosen by the player (settings overlay, start-up options)
std::atomic<bool> g_chosen{true};
std::atomic<int> g_set{-1};
void sync_choice() {
    const int mode = interp::mode();
    if (mode != 2 && mode != g_set.load()) {  // (true 60 is left alone)
        g_chosen = mode == 1;
        g_set = mode;
    }
}
// interpolation paused or resumed here, not by the player
void set_mode_auto(int mode) {
    g_set = mode;
    interp::set_mode(mode);
}

// The heat guard's limits for this device and setting; the rule in use goes to the log at start-up
// and again when the setting changes, e.g. "[perf] heat guard: handheld (fan) 1.00/0.90".
heat_guard::Limits heat_guard_limits() {
    const auto device = heat_guard::to_device(heat_guard::device.load());
    const auto setting = heat_guard::to_setting(heat_guard::choice.load());
    const heat_guard::Limits limits = heat_guard::limits(device, setting);
    static int logged = -1;
    if (logged != int(device) * 3 + int(setting)) {
        logged = int(device) * 3 + int(setting);
        if (limits.active)
            LOG("[perf] heat guard: %s%s %.2f/%.2f", heat_guard::device_name(device),
                setting == heat_guard::kRelaxed ? ", relaxed" : "", limits.pause, limits.resume);
        else
            LOG("[perf] heat guard: off (%s)", heat_guard::device_name(device));
    }
    return limits;
}

// The readings for the heat guard's temperature check (heat_guard::Temperatures): the battery from
// power_supply (tenths of a degree; NaN where Android does not let apps read it) and the hottest
// CPU/GPU thermal zone (as in the performance report).
heat_guard::Temperatures heat_guard_temperatures() {
    heat_guard::Temperatures t;
    double tenths = 0;
    if (overlay::AndroidTelemetry::number("/sys/class/power_supply/battery/temp", tenths) &&
        tenths > -400 && tenths < 1000)
        t.battery = float(tenths / 10);
    overlay::AndroidTelemetry sensors;
    sensors.read();
    for (const auto& [name, celsius] : sensors.temperatures)
        if ((name.find("cpu") != std::string::npos || name.find("gpu") != std::string::npos) &&
            !(t.hottest_core >= celsius))
            t.hottest_core = float(celsius);
    return t;
}

// One heat guard step (once a second): the device class's limits and the player's setting decide whether the
// device is near its thermal limit (headroom forecast 10 s ahead; on fan handhelds confirmed by a real temperature,
// heat_guard.h). Publishes heat_guard::g_hot and logs the changes; returns it.
bool heat_guard_step(int64_t now, const char* action) {
    static bool hot = false;
    static heat_guard::State heatState;
    const heat_guard::Limits limits = heat_guard_limits();
    static AThermalManager* thermal = AThermal_acquireManager();
    const float headroom = thermal ? AThermal_getThermalHeadroom(thermal, 10) : NAN;
    static int tick = 0;
    if (++tick % 15 == 0) LOG("[perf] thermal headroom %.2f%s", headroom, hot ? " (heat guard: hot)" : "");
    // fan handhelds: a headroom pause also needs a real temperature (#254, heat_guard.h); the
    // sensors are read only when the headroom alone would pause or keep the guard on
    const auto device = heat_guard::to_device(heat_guard::device.load());
    heat_guard::Temperatures temps;
    if (heat_guard::checks_temperatures(device) && heat_guard::next_paused(hot, headroom, limits))
        temps = heat_guard_temperatures();
    const bool next = heatState.update(headroom, limits, device, temps, now / 1000000);
    static bool loggedCool = false;
    if (!next && !hot && heat_guard::next_paused(hot, headroom, limits)) {
        if (!loggedCool)
            LOG("[perf] thermal headroom %.2f ignored: battery %.1f C, CPU/GPU %.1f C (heat guard needs %.0f / %.0f C)",
                headroom, temps.battery, temps.hottest_core, heat_guard::kHotBattery, heat_guard::kHotCore);
        loggedCool = true;
    }
    if (next && !hot) LOG("[perf] heat guard: hot, %s: thermal headroom %.2f (battery %.1f C, CPU/GPU %.1f C)", action,
                          headroom, temps.battery, temps.hottest_core);
    else if (!next && hot) {
        if (limits.active) LOG("[perf] heat guard: cool again: thermal headroom %.2f (battery %.1f C, CPU/GPU %.1f C)",
                               headroom, temps.battery, temps.hottest_core);
        else LOG("[perf] heat guard: off (thermal headroom %.2f)", headroom);
    }
    hot = next;
    heat_guard::g_hot.store(hot, std::memory_order_relaxed);
    return hot;
}

bool enabled() {
    static const bool on = [] { const char* e = getenv("WWHD_PERF_HINT"); return !e || atoi(e) != 0; }();
    return on;
}

int64_t thread_cpu_ns() {
    timespec t{};
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &t);
    return int64_t(t.tv_sec) * 1'000'000'000 + t.tv_nsec;
}

// Automatic 60 fps. Frame interpolation runs the game logic on every other pass, so where the phone
// draws fewer than 60 frames a second the whole game slows down (measured: 13.6 logic steps per
// second at 2,100 draws a frame). With 60 fps chosen (long press on the view button), interpolation
// is switched off while fewer than 50 frames a second are drawn, and back on once a 30 fps frame
// costs the render thread less than 14 ms (room for two). A switch back that fails again soon
// doubles the wait before the next try (5 s up to 2 min).
void adapt_60(int64_t now) {
    static int64_t windowStart = 0, windowCpu = 0, lastSwitch = 0, cooldown = 5'000'000'000;
    static int frames = 0, slow = 0, roomy = 0;
    if (!windowStart) { windowStart = now; windowCpu = thread_cpu_ns(); return; }
    ++frames;
    if (now - windowStart < 1'000'000'000) return;
    const double seconds = (now - windowStart) / 1e9, rate = frames / seconds;
    const int64_t cpu = thread_cpu_ns();
    const double msPerFrame = (cpu - windowCpu) / 1e6 / frames;
    windowStart = now; windowCpu = cpu; frames = 0;
    const int mode = interp::mode();
    sync_choice();
    if (mode == 2) return;
#ifdef WWHD_NATIVE_60
    {   // a game that already runs at 60 fps (Super Mario 3D World): nothing to pause or interpolate, and
        // the performance hint keeps its 16.7 ms target; the heat guard only decides and publishes
        // heat_guard::g_hot (heat_guard.h) for what may follow it later
        heat_guard_step(now, "nothing to pause at native 60 fps (heat_guard::g_hot set)");
        return;
    }
#endif
    if (!g_chosen) {  // 60 fps not chosen
        slow = roomy = 0;
        return;
    }
    // Heat: a phone near its thermal limit lowers every core's maximum clock (measured: 4.47 -> 1.96
    // GHz on the fast cores, the render thread then needed twice the time per frame). The in-between
    // frames double the drawing work, so 60 fps pauses at 72% of the thermal headroom (forecast 10 s
    // ahead; a Galaxy S25 Ultra already halved its clocks at 76%) and resumes below 60%: the game keeps its 30 fps instead of dropping below them.
    // (the limits: phones 0.72 / 0.60, fan-cooled handhelds 1.00 / 0.90, the player's setting; heat_guard.h)
    const bool hot = heat_guard_step(now, "60 fps paused");
    if (hot) {
        if (mode == 1) set_mode_auto(0);
        slow = roomy = 0;
        return;
    }
    if (interp::paced_interpolation()) {  // interp.cpp keeps the game's speed itself (dropped frames)
        if (mode == 0) { set_mode_auto(1); LOG("[perf] 60 fps on (paced)"); }
        return;
    }
    if (mode == 1) {
        slow = rate < 50 ? slow + 1 : 0;
        if (slow >= 2) {
            // a switch that does not hold for 30 s makes the next try wait longer
            cooldown = now - lastSwitch < 30'000'000'000 ? std::min<int64_t>(cooldown * 2, 120'000'000'000)
                                                       : 5'000'000'000;
            set_mode_auto(0);
            lastSwitch = now; slow = roomy = 0;
            LOG("[perf] 60 fps paused: %.1f frames/s would slow the game down (retry in %.0f s)", rate, cooldown / 1e9);
        }
    } else if (mode == 0) {
        roomy = rate >= 28 && msPerFrame < 14.0 && now - lastSwitch >= cooldown ? roomy + 1 : 0;
        if (roomy >= 2) {
            set_mode_auto(1);
            lastSwitch = now; slow = roomy = 0;
            LOG("[perf] 60 fps resumed (render thread %.1f ms per frame)", msPerFrame);
        }
    }
}
}  // namespace

bool fps60_chosen() {
    sync_choice();  // a mode the settings overlay just set is the new choice
    return g_chosen;
}
void set_fps60_chosen(bool on) {
    g_chosen = on;
    set_mode_auto(on ? 1 : 0);
    LOG("[perf] 60 fps %s", on ? "chosen" : "off");
}

// The cores with the highest maximum clock (a Snapdragon 8 Elite has two at 4.47 GHz next to six at
// 3.53 GHz). The scheduler kept the render thread and the game thread on the slower cores while the
// fast ones idled at 1 GHz; the render thread's CPU time limits the frame rate in the open world.
// At least two cores: a Snapdragon 8 Gen 3 has one 3.3 GHz core, and with every game thread on it
// the agl boot crash happened on every start (the render thread ran GX2CopySurface late; fixed in
// GX2CopySurface, docs/decomp-notes.md "Fixed: intermittent boot crash"); the next clock steps are
// added until there are two or more.
static cpu_set_t fastest_cores(int& count) {
    cpu_set_t set;
    CPU_ZERO(&set);
    count = 0;
    long best = 0;
    std::vector<long> freq;
    for (int cpu = 0; cpu < CPU_SETSIZE && cpu < 64; cpu++) {
        char path[96];
        snprintf(path, sizeof path, "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
        FILE* f = fopen(path, "r");
        if (!f) break;
        long v = 0;
        if (fscanf(f, "%ld", &v) != 1) v = 0;
        fclose(f);
        freq.push_back(v);
        best = std::max(best, v);
    }
    long floor = best;
    for (;;) {
        int n = 0;
        long next = 0;
        for (long v : freq) {
            if (v >= floor) n++;
            else next = std::max(next, v);
        }
        if (n >= 2 || next <= 0) break;
        floor = next;
    }
    for (size_t cpu = 0; cpu < freq.size(); cpu++)
        if (best > 0 && freq[cpu] >= floor) { CPU_SET(cpu, &set); count++; }
    return set;
}

void add_current_thread() {
    static const bool pin = [] { const char* e = getenv("WWHD_FAST_CORES"); return !e || atoi(e) != 0; }();
    if (pin) {
        int n = 0;
        cpu_set_t set = fastest_cores(n);
        // only on CPUs with distinct fast cores (all cores at one clock: nothing to choose)
        if (n > 0 && n < (int)sysconf(_SC_NPROCESSORS_CONF)) {
            const bool ok = sched_setaffinity(0, sizeof set, &set) == 0;
            LOG("[perf] thread %d on the %d fastest cores: %s", gettid(), n, ok ? "yes" : "refused");
        }
    }
    if (!enabled()) return;
    std::lock_guard<std::mutex> lk(g_mu);
    g_tids.push_back((int32_t)gettid());
    g_changed = true;
}

void frame_done() {
    using clock = std::chrono::steady_clock;
    static clock::time_point last{};
    const auto now = clock::now();
    const int64_t interval = last == clock::time_point{} ? 0 : (now - last).count();
    last = now;
    adapt_60(now.time_since_epoch().count());
    if (!enabled()) return;
    std::lock_guard<std::mutex> lk(g_mu);
    // the budget of one presented frame: 33 ms at 30 fps, 17 ms at 60 fps, 8 / 4 ms at 120 / 240 fps
    const int frames = interp::frames_per_step();
#ifdef WWHD_NATIVE_60
    const int64_t target = 16'666'667;
#else
    const int64_t target = !interp::mode() ? 33'333'333 : frames <= 2 ? 16'666'667 : 33'333'333 / frames;
#endif
    if (g_changed) {
        // (re)create the session for the current set of threads
        g_changed = false;
        if (g_session) APerformanceHint_closeSession(g_session);
        g_session = nullptr;
        if (APerformanceHintManager* m = APerformanceHint_getManager())
            g_session = APerformanceHint_createSession(m, g_tids.data(), g_tids.size(), target);
        g_target = target;
        LOG("[perf] performance hint session for %zu threads: %s", g_tids.size(), g_session ? "on" : "unavailable");
    }
    if (g_session && target != g_target) {
        APerformanceHint_updateTargetWorkDuration(g_session, target);
        g_target = target;
    }
    // WWHD_ADPF_WORK=1 (experiment): report the render thread's own CPU time for the frame as the work
    // duration, the way ADPF is meant to be used, instead of the frame interval. The interval always equals
    // the target while the frame is locked to vsync, so the system never sees how close to the budget the
    // work is. Measure with it on and off (fps percentiles, thermal headroom) before making it the default.
    static const bool reportWork = [] { const char* e = getenv("WWHD_ADPF_WORK"); return e && atoi(e) != 0; }();
    if (reportWork) {
        static int64_t lastCpu = 0;
        const int64_t cpu = thread_cpu_ns();
        const int64_t work = lastCpu ? cpu - lastCpu : 0;
        lastCpu = cpu;
        if (g_session && work > 0 && work < 1'000'000'000) APerformanceHint_reportActualWorkDuration(g_session, work);
        return;
    }
    // the frame interval: on budget it equals the target; longer frames ask for higher clocks
    if (g_session && interval > 0 && interval < 1'000'000'000)
        APerformanceHint_reportActualWorkDuration(g_session, interval);
}
}  // namespace perf_hint

// Low memory (MemoryPressure.java): `level` is memory_trim::Level, `source` the Android trim level (or -1: the app's
// own check of the system's free memory); the render thread frees at its next frame.
extern "C" JNIEXPORT void JNICALL Java_org_wwhdrecomp_wwhd_MemoryPressure_nativeTrim(JNIEnv*, jclass, jint level, jint source) {
    if (level <= memory_trim::kNone) return;
    LOG("[memory] trim requested: %s (Android level %d)", level >= memory_trim::kFull ? "full" : "light", int(source));
    memory_trim::request(level >= memory_trim::kFull ? memory_trim::kFull : memory_trim::kLight, source);
}

// The kind of device for the heat guard (HeatGuardDevice.java): phones, or fan-cooled handhelds (heat_guard.h)
extern "C" JNIEXPORT void JNICALL Java_org_wwhdrecomp_wwhd_HeatGuardDevice_nativeSetDeviceClass(JNIEnv*, jclass, jint device) {
    heat_guard::device = heat_guard::to_device(device);
}

// Memory above the system's low-memory line and the line itself, in MB (MemoryPressure.java, every 2 s in the
// foreground; memory_trim.h): the texture budget tightens when it runs low
extern "C" JNIEXPORT void JNICALL Java_org_wwhdrecomp_wwhd_MemoryPressure_nativeHeadroom(JNIEnv*, jclass, jint headroomMb, jint thresholdMb) {
    memory_trim::set_headroom(headroomMb, thresholdMb);
}

#else
namespace perf_hint {
void add_current_thread() {}
void frame_done() {}
bool fps60_chosen() { return false; }
void set_fps60_chosen(bool) {}
}  // namespace perf_hint
#endif
