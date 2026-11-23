// Dynamic internal resolution: keeps the GPU time of a frame inside a budget by moving the internal
// resolution factor (the same one the Graphics menu sets) between a floor and the user's chosen value.
//   WWHD_AUTO_RES=<fps>        on, with that frame rate as the target (e.g. 120, 60)
//   WWHD_AUTO_RES_MIN=0.4      lowest factor (default 0.4); the ceiling is the requested resolution
//   WWHD_AUTO_RES_LOG=1        logs every change
// The controller reads the GPU intervals the performance metrics collect (perf_metrics.h), so the
// timestamps stay on while it is active. Changes are at most one per half second and use small steps,
// because every change reallocates the screen-sized targets.
#pragma once
#include <cstdint>

namespace autores {
bool active();
int target_fps();                         // 0: off
void set_target_fps(int fps);             // runtime switch (settings overlay); 0 turns it off, restoring the chosen resolution
void init();                              // main(): reads the environment
void gpu_sample(uint64_t frame, double ms);  // perf sink (any thread)
void frame_end();                         // render thread, once per swap
float current();                          // the factor in use (0 when off)
}  // namespace autores
