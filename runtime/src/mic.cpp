// The GamePad microphone's host side (mic.h, docs/mic.md): the option, the blow button, the capture thread
// and the samples libmic hands to the game.
#include "mic.h"
#include "mic_core.h"
#include "input_map.h"
#include "runtime.h"
#include "platform/keycodes.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <thread>

namespace mic {
namespace {

const char* env(const char* name) {
    const char* v = getenv(name);
    return v && *v ? v : nullptr;
}

bool fake_blow() {  // WWHD_MIC_FAKE=noise: a blow that never stops (tests, docs/mic.md)
    static const bool on = env("WWHD_MIC_FAKE") && strcmp(env("WWHD_MIC_FAKE"), "0") != 0;
    return on;
}

std::atomic<int> g_mode{fake_blow() ? kButton : env("WWHD_MIC") ? parse_mode(env("WWHD_MIC")) : kOff};
std::atomic<int> g_sens{env("WWHD_MIC_SENS") ? std::clamp(atoi(env("WWHD_MIC_SENS")), 25, 400) : 100};
std::atomic<bool> g_touch{false}, g_key{false};

// the capture device and its thread
std::mutex g_cap_mu;
std::condition_variable g_cap_cv;
Capture* g_capture = nullptr;
bool g_cap_running = false, g_cap_failed = false, g_thread_started = false;
std::atomic<bool> g_cap_ok{false};

// the samples (guest threads)
std::mutex g_mu;
BlowFilter g_filter;
BlowSynth g_synth;
double g_frac = 0;
// statistics
uint64_t g_fed = 0, g_consumed = 0, g_loud = 0, g_window_fed = 0;
int16_t g_window_peak = 0;
std::chrono::steady_clock::time_point g_last_log;
bool g_said_reading = false;

void capture_thread() {
    std::unique_lock lk(g_cap_mu);
    for (;;) {
        const bool want = g_capture && g_mode.load() == kMicrophone;
        if (want && !g_cap_running && !g_cap_failed) {
            Capture* c = g_capture;
            lk.unlock();
            const bool ok = c->start();  // may wait for the permission dialog (Android)
            lk.lock();
            g_cap_running = ok;
            g_cap_failed = !ok;
            g_cap_ok = ok;
            LOG("[mic] capture %s", ok ? "started: the game hears the microphone" : "unavailable (no device or no permission): the game runs without a microphone");
            continue;
        }
        if (!want && g_cap_running) {
            Capture* c = g_capture;
            g_cap_running = false;
            g_cap_ok = false;
            lk.unlock();
            c->stop();
            lk.lock();
            LOG("[mic] capture stopped");
            continue;
        }
        g_cap_cv.wait(lk);
    }
}

void wake_capture() {
    std::lock_guard lk(g_cap_mu);
    if (g_mode.load() != kMicrophone) g_cap_failed = false;  // try again the next time it is chosen
    if (!g_thread_started && g_capture) {
        g_thread_started = true;
        std::thread(capture_thread).detach();
    }
    g_cap_cv.notify_all();
}

void stats(uint32_t n, const int16_t* s) {
    g_fed += n;
    g_window_fed += n;
    for (uint32_t i = 0; i < n; i++) {
        const int16_t a = s[i] < 0 ? int16_t(-std::max<int>(s[i], -32767)) : s[i];
        g_window_peak = std::max(g_window_peak, a);
        if (a > 4096) g_loud++;
    }
    const auto now = std::chrono::steady_clock::now();
    if (now - g_last_log < std::chrono::seconds(5)) return;
    g_last_log = now;
    if (log_enabled() || (!g_said_reading && g_consumed > 0)) {
        LOG("[mic] fed %llu samples (%llu last 5 s, peak %d), game consumed %llu, loud samples %llu, mode %s%s",
            (unsigned long long)g_fed, (unsigned long long)g_window_fed, g_window_peak, (unsigned long long)g_consumed,
            (unsigned long long)g_loud, mode_name(Mode(g_mode.load())), fake_blow() ? " (WWHD_MIC_FAKE)" : "");
        g_said_reading = true;
    }
    g_window_fed = 0;
    g_window_peak = 0;
}

}  // namespace

bool log_enabled() {
    static const bool on = env("WWHD_MIC_LOG") && atoi(env("WWHD_MIC_LOG")) != 0;
    return on;
}

Mode parse_mode(const char* s) {
    if (!s) return kOff;
    if (!strcmp(s, "mic") || !strcmp(s, "1") || !strcmp(s, "microphone")) return kMicrophone;
    if (!strcmp(s, "button") || !strcmp(s, "2")) return kButton;
    return kOff;
}
const char* mode_name(Mode m) { return m == kMicrophone ? "mic" : m == kButton ? "button" : "off"; }

Mode mode() { return Mode(g_mode.load()); }
void set_mode(Mode m) {
    if (fake_blow() && m == kOff) m = kButton;  // the fake blow needs a device
    const int before = g_mode.exchange(m);
    if (before != m) LOG("[mic] mode %s", mode_name(m));
    wake_capture();
}
int sensitivity() { return g_sens.load(); }
void set_sensitivity(int percent) { g_sens = std::clamp(percent, 25, 400); }

void set_blow_touch(bool down) { g_touch = down; }
void set_blow_key(bool down) { g_key = down; }
int blow_key() {
    static const int k = [] {
        const char* e = env("WWHD_MIC_KEY");
        const int code = e ? input_map::key_from_id(e) : input_map::kNoKey;
        return code != input_map::kNoKey ? code : int(kVK_ANSI_B);
    }();
    return k;
}

void set_capture(Capture* c) {  // (at static initialisation: the thread starts on the first use)
    std::lock_guard lk(g_cap_mu);
    g_capture = c;
}

bool device_present() {
    static std::once_flag once;
    std::call_once(once, wake_capture);  // a start value from WWHD_MIC
    switch (mode()) {
    case kButton: return true;
    case kMicrophone: return g_cap_ok.load() || fake_blow();
    default: return false;
    }
}

uint32_t produce(int16_t* out, uint32_t max, uint64_t elapsed_us) {
    std::lock_guard lk(g_mu);
    const bool blow = g_touch.load() || g_key.load() || fake_blow();
    const float sens = g_sens.load() / 100.0f;
    // what `elapsed_us` of real time holds at 32 kHz (the synthetic path; a capture brings its own pace)
    g_frac += double(elapsed_us) * kRate / 1e6;
    uint32_t n = uint32_t(std::min<double>(g_frac, max));
    g_frac = std::min(g_frac - n, 1.0);
    Capture* cap = nullptr;
    if (mode() == kMicrophone && g_cap_ok.load()) {
        std::lock_guard ck(g_cap_mu);
        cap = g_cap_running ? g_capture : nullptr;
    }
    if (cap) {
        static const bool tuned = [] {  // the filter's knobs (docs/mic.md)
            if (env("WWHD_MIC_GATE")) g_filter.gate = atoi(env("WWHD_MIC_GATE")) != 0;
            if (env("WWHD_MIC_LOW_SHARE")) g_filter.low_share = std::clamp(float(atof(env("WWHD_MIC_LOW_SHARE"))), 0.0f, 1.0f);
            if (env("WWHD_MIC_GATE_RATIO")) g_filter.gate_ratio = std::max(1.0f, float(atof(env("WWHD_MIC_GATE_RATIO"))));
            return true;
        }();
        (void)tuned;
        // keep the delay short: what does not fit in the ring now would only arrive late
        if (const uint32_t q = cap->queued(); q > max) cap->drop(q - max);
        static float buf[4096];
        n = 0;
        while (n < max) {
            const uint32_t got = cap->read(buf, std::min<uint32_t>(max - n, 4096));
            if (!got) break;
            g_filter.sensitivity = sens;
            for (uint32_t i = 0; i < got; i++) out[n + i] = to_pcm(g_filter.process(buf[i]) + g_synth.next(blow) * sens);
            n += got;
        }
    } else {
        for (uint32_t i = 0; i < n; i++) out[i] = to_pcm(g_synth.next(blow) * sens);
    }
    stats(n, out);
    return n;
}

void count_consumed(uint32_t n) {
    std::lock_guard lk(g_mu);
    g_consumed += n;
}

}  // namespace mic

#ifdef __ANDROID__
#include <jni.h>
// the on-screen blow button (TouchControls.java)
extern "C" JNIEXPORT void JNICALL Java_org_wwhdrecomp_wwhd_TouchControls_nativeSetBlow(JNIEnv*, jclass, jboolean down) {
    mic::set_blow_touch(down);
}
#endif
