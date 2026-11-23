#include "autores.h"
#include "gfx/renderer.h"
#include "perf_metrics.h"
#include "runtime.h"
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdlib>
#include <deque>
#include <mutex>
#include <vector>

namespace autores {
namespace {
std::atomic<bool> g_on{false};
int g_fps = 0;
bool g_sink = false;
uint64_t g_set_frame = 0;
double g_budget_ms = 8.33;       // GPU budget per frame (target period x headroom)
float g_min = 0.4f, g_max = 1.5f, g_cur = 1.0f;
bool g_log = false;
std::mutex mu;
std::deque<double> samples;      // recent GPU intervals (ms)
uint64_t g_last_change = 0, g_frames = 0;
}  // namespace

bool active() { return g_on.load(std::memory_order_relaxed); }
int target_fps() { return g_on ? g_fps : 0; }
float current() { return g_on ? g_cur : 0.0f; }

void set_target_fps(int fps) {
    if (fps <= 0) {
        if (g_on.exchange(false)) {
            render::set_res_scale(g_max);  // back to the chosen resolution
            if (!getenv("WWHD_BENCH")) perf::force_demand(false);
            LOG("[autores] off");
        }
        return;
    }
    g_fps = fps;
    const char* h = getenv("WWHD_AUTO_RES_HEADROOM");  // fraction of the period the GPU may use
    g_budget_ms = 1000.0 / fps * (h ? atof(h) : 0.88);
    if (const char* m = getenv("WWHD_AUTO_RES_MIN")) g_min = std::clamp((float)atof(m), 0.25f, 1.0f);
    g_log = getenv("WWHD_AUTO_RES_LOG") != nullptr;
    if (!g_sink) { g_sink = true; perf::set_gpu_sink(&gpu_sample); }
    perf::force_demand(true);
    g_frames = 0;
    g_last_change = 0;
    { std::lock_guard<std::mutex> lk(mu); samples.clear(); }
    g_on = true;
    LOG("[autores] on: target %d fps, GPU budget %.2f ms, floor %.2fx", fps, g_budget_ms, g_min);
}

void init() {
    const char* e = getenv("WWHD_AUTO_RES");
    if (e && *e && atof(e) > 0) set_target_fps((int)atof(e));
}

void gpu_sample(uint64_t, double ms) {
    std::lock_guard<std::mutex> lk(mu);
    samples.push_back(ms);
    if (samples.size() > 24) samples.pop_front();
}

void frame_end() {
    if (!g_on) return;
    ++g_frames;
    if (g_frames == 1) {  // the ceiling is whatever the user chose
        g_max = std::max(render::res_scale(), g_min);
        g_cur = g_max;
    } else if (g_frames > g_set_frame + 3 && std::fabs(render::res_scale() - g_cur) > 0.002f) {
        // the player picked a resolution: that is the new ceiling
        g_max = std::max(render::res_scale(), g_min);
        g_cur = g_max;
        g_last_change = g_frames;
        std::lock_guard<std::mutex> lk(mu);
        samples.clear();
        return;
    }
    // a scene much heavier than the last (the world map opening, a stage starting at the ceiling): every
    // recent frame well over the budget. Down at once instead of 30+ frames late (each one a dropped frame).
    // (3 frames over 140%, or 6 over 125%; the samples arrive a frame or two after their frames)
    if (g_frames - g_last_change >= 5) {
        double low = 1e9, low3 = 1e9;
        size_t n = 0;
        {
            std::lock_guard<std::mutex> lk(mu);
            n = samples.size();
            for (size_t i = n >= 6 ? n - 6 : 0; i < n; ++i) low = std::min(low, samples[i]);
            for (size_t i = n >= 3 ? n - 3 : 0; i < n; ++i) low3 = std::min(low3, samples[i]);
        }
        const bool six = n >= 6 && low > g_budget_ms * 1.25, three = n >= 3 && low3 > g_budget_ms * 1.4;
        if ((six || three) && g_cur > g_min) {
            if (!six) low = low3;
            float want = g_cur * (float)std::max(0.6, std::sqrt(g_budget_ms / low));
            want = std::clamp(std::floor(want * 20.0f) / 20.0f, g_min, g_max);
            if (want < g_cur) {
                if (g_log) LOG("[autores] GPU %.2f ms in each of the last frames (budget %.2f): %.2fx -> %.2fx at once", low, g_budget_ms, g_cur, want);
                g_cur = want;
                g_last_change = g_set_frame = g_frames;
                render::set_res_scale(want);
                std::lock_guard<std::mutex> lk(mu);
                samples.clear();
                return;
            }
        }
    }
    if (g_frames - g_last_change < 30) return;  // settle after a change: the samples lag the frames
    double g;
    {
        std::lock_guard<std::mutex> lk(mu);
        if (samples.size() < 16) return;
        std::vector<double> v(samples.begin(), samples.end());
        std::sort(v.begin(), v.end());
        g = v[v.size() * 3 / 4];  // the upper quartile: spikes count, one outlier does not
    }
    // GPU cost is mostly per pixel: scale^2. Aim at the budget with a damped step.
    const double ratio = g_budget_ms / std::max(g, 0.1);
    float want = g_cur;
    if (ratio < 1.0)
        want = g_cur * (float)std::max(0.6, std::sqrt(ratio));          // too slow: down, up to 40% per step
    else if (ratio > 1.18)
        want = g_cur * (float)std::min(1.15, std::sqrt(ratio * 0.97));  // headroom: creep up
    want = std::clamp(want, g_min, g_max);
    want = std::round(want * 20.0f) / 20.0f;  // 0.05 steps
    want = std::clamp(want, g_min, g_max);
    if (want == g_cur) return;
    // down at once, up only after a quieter spell
    if (want > g_cur && g_frames - g_last_change < 90) return;
    if (g_log) LOG("[autores] GPU p75 %.2f ms (budget %.2f): %.2fx -> %.2fx", g, g_budget_ms, g_cur, want);
    g_cur = want;
    g_last_change = g_set_frame = g_frames;
    render::set_res_scale(want);
    std::lock_guard<std::mutex> lk(mu);
    samples.clear();
}
}  // namespace autores
