// Reproducible benchmark mode (docs/benchmark.md). Off unless WWHD_BENCH is set.
//
//   WWHD_BENCH=circus   presses A through the menus until the game loads a stage whose file name contains
//                       WWHD_BENCH_STAGE (default "Circus"), waits for the stage to render, plays a scripted
//                       route and measures WWHD_BENCH_FRAMES frames (default 1800)
//   WWHD_BENCH=live     measures from frame WWHD_BENCH_START (default 600), input from the environment
//
// Per frame it records wall time, GPU interval, render-thread CPU time and draw count, writes
// <WWHD_BENCH_OUT>/bench.csv + summary.txt and quits the game. Everything is driven by the TV frame
// counter, so a run does not depend on the wall clock (uncapped runs get the same input).
#pragma once
#include <cstdint>
#include <string>

namespace bench {
bool active();                          // WWHD_BENCH set
void init();                            // early in main(): fills in the scripted-input environment
extern uint32_t g_draws;                // render thread: draws since the last swap (counted when active)
inline void note_draw() { ++g_draws; }
void frame_end();                       // render thread, once per swap
void gpu_sample(uint64_t frame, double ms);  // perf_metrics: the GPU interval of a finished frame
bool done();
// scripted input: false while the benchmark does not drive the pad. Called from input::read().
bool pad(uint64_t frame, uint32_t& buttons, float& lx, float& ly);
// the game opened a file (guest path): lets the benchmark see which stage is loading
void note_file_open(const char* guestPath);
// WWHD_BENCH_STAGELIST=host file (stage tour, tools/bench/sm3dw_stage_tour.sh): read instead of the game's
// SystemData/StageList.szs (a copy whose circus course loads another stage, tools/bench/stage_list_patch.py).
// Returns the host path to read, or empty.
std::string host_override(const std::string& guestPath);
}  // namespace bench
