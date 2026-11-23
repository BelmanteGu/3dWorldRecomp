// Per-frame stutter attribution (docs/stutter.md): where the time of a long frame went. Off unless the
// benchmark (bench.cpp, WWHD_BENCH) or WWHD_FRAME_CAUSES=1 turns it on; then each site adds the
// nanoseconds it blocked (render thread unless noted) and bench::frame_end takes the sums once per
// presented frame, as columns of bench.csv. Cheap: one relaxed atomic add per event.
#pragma once
#include <atomic>
#include <chrono>
#include <cstdint>

namespace frame_causes {
enum Cause : int {
    kShaderTranslate,  // Latte decompile + GLSL of a shader not in the pack (render thread)
    kShaderPack,       // shader taken from the pack (inflate + metadata)
    kPipelineCreate,   // a new pipeline's state, layouts and recipe (render thread, before any wait)
    kPipelineWait,     // a draw waited for its pipeline to compile (render thread)
    kTextureUpload,    // texture decode + upload staging (render thread)
    kGpuWait,          // waiting for a frame slot's fence
    kAcquire,          // vkAcquireNextImage
    kPresent,          // vkQueuePresent
    kGameIdle,         // render thread waiting for the game's commands (logic late)
    kGameWork,         // game thread: work of the logic/in-between step (max of the frame)
    kFileIo,           // FSReadFile on any thread
    kCount
};
extern std::atomic<bool> g_on;
inline bool on() { return g_on.load(std::memory_order_relaxed); }
inline uint64_t now_ns() {
    return (uint64_t)std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
}
void add_slow(Cause c, uint64_t ns);
inline void add(Cause c, uint64_t ns) { if (on()) add_slow(c, ns); }
void max_slow(Cause c, uint64_t ns);  // keeps the largest value of the frame (kGameWork)
inline void max(Cause c, uint64_t ns) { if (on()) max_slow(c, ns); }
enum Counter : int { kSkippedDraws, kNewShaders, kNewPipelines, kWarmHits, kCounters };
void count_slow(Counter c);
inline void count(Counter c) { if (on()) count_slow(c); }

struct Scope {
    Cause cause;
    uint64_t start;
    explicit Scope(Cause c) : cause(c), start(on() ? now_ns() : 0) {}
    ~Scope() { if (start) add_slow(cause, now_ns() - start); }
};

struct Sample {
    uint64_t ns[kCount];
    uint32_t counts[kCounters];
};
Sample take();          // the sums since the last take (bench::frame_end)
const char* name(int c);
const char* counter_name(int c);
void init();            // WWHD_FRAME_CAUSES=1
}  // namespace frame_causes
