#include "frame_causes.h"
#include <cstdlib>

namespace frame_causes {
std::atomic<bool> g_on{false};
namespace {
std::atomic<uint64_t> g_ns[kCount];
std::atomic<uint32_t> g_counts[kCounters];
}

void add_slow(Cause c, uint64_t ns) { g_ns[c].fetch_add(ns, std::memory_order_relaxed); }
void max_slow(Cause c, uint64_t ns) {
    uint64_t cur = g_ns[c].load(std::memory_order_relaxed);
    while (ns > cur && !g_ns[c].compare_exchange_weak(cur, ns, std::memory_order_relaxed)) {}
}
void count_slow(Counter c) { g_counts[c].fetch_add(1, std::memory_order_relaxed); }

Sample take() {
    Sample s{};
    for (int i = 0; i < kCount; ++i) s.ns[i] = g_ns[i].exchange(0, std::memory_order_relaxed);
    for (int i = 0; i < kCounters; ++i) s.counts[i] = g_counts[i].exchange(0, std::memory_order_relaxed);
    return s;
}

const char* name(int c) {
    static const char* const names[kCount] = {"shader_translate", "shader_pack", "pipeline_create", "pipeline_wait",
        "texture_upload", "gpu_wait", "acquire", "present", "game_idle", "game_work", "file_io"};
    return c >= 0 && c < kCount ? names[c] : "?";
}
const char* counter_name(int c) {
    static const char* const names[kCounters] = {"skipped_draws", "new_shaders", "new_pipelines", "warm_hits"};
    return c >= 0 && c < kCounters ? names[c] : "?";
}

void init() {
    const char* e = getenv("WWHD_FRAME_CAUSES");
    if ((e && *e && *e != '0') || getenv("WWHD_BENCH")) g_on = true;
}
}  // namespace frame_causes
