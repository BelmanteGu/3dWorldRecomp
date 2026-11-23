// Integrated-GPU presets (docs/intel60.md, docs/intel120.md).
//   WWHD_PRESET=intel60  (or --intel60)   60 fps locked on an integrated GPU: automatic resolution aiming at 60
//                                          with headroom, fast multiplication, GamePad picture off
//   WWHD_PRESET=intel120 (or --intel120)  120 presented fps (in-between pictures) on an integrated GPU
//   WWHD_PRESET=intel30  (or --intel30)   30 presented fps at full game speed (docs/30fps.md): automatic resolution
//                                          aiming at 30, ceiling 1.5x
//   WWHD_PRESET=none                      no preset, not even the automatic one below
// Without WWHD_PRESET, a desktop session whose chosen Vulkan GPU is integrated (VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU)
// gets the intel60 defaults automatically, each only where the player chose nothing: a WWHD_* variable or a value
// saved by the settings overlay (settings.ini) wins. Not on Android (every phone GPU is integrated; it has its own
// defaults) and not in the benchmark unless a preset is named.
#pragma once

namespace gpu_preset {
// main(), before the renderer starts: an explicit preset fills in its WWHD_* variables (a value already set is kept).
// Returns the preset's resolution ceiling to apply after render::init() (1.25 for intel60, 1.5 = 1080p internal for
// intel120), 0 when none (no preset, or WWHD_RES_SCALE chosen).
float apply_explicit(int argc, char** argv);
// the Vulkan backend, as soon as the GPU is chosen and before the saved options are loaded. Returns the
// internal-resolution ceiling to request (0: leave it)
float device_chosen(bool integrated, const char* name);
const char* active();  // "intel60", "intel120", "intel30", "auto-intel60" or ""
}  // namespace gpu_preset
