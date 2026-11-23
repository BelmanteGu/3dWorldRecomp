// Internal interfaces between the GX2 layer and the renderer.
#pragma once
#include <algorithm>
#include <chrono>
#include <cstdint>

struct LatteFetchShader;
namespace GX2 {
struct GX2ColorBuffer;
struct GX2DepthBuffer;
}

namespace gx2 {
struct ShaderKeyDirtyStats {
    uint64_t changedBatches = 0, baselineWouldBumps = 0, actualBumps = 0;
    uint64_t avoidedBumps = 0, maskedWords = 0;
};
ShaderKeyDirtyStats shader_key_dirty_stats(); // Render-thread diagnostics.
uint32_t color_buffer_address(const GX2::GX2ColorBuffer* cb);
LatteFetchShader* build_fetch_shader(uint32_t program);  // from our encoded fetch "program"
// Local head-start semantic attributes, never guest GPU instructions. Validates
// the whole WWFA0001 payload before constructing a parsed fetch shader.
LatteFetchShader* decode_fetch_attributes(uint32_t program, uint32_t size, uint64_t* sourceHash);
void normalize_fetch_shader(LatteFetchShader* fetch);
// Uncapped (debug only: settings overlay > Graphics; WWHD_UNCAPPED=1 for any renderer, and the older
// WWHD_VK_UNCAPPED=1 with Vulkan): flips no longer wait for the virtual vsync and presentation no
// longer waits for the display (Metal: displaySyncEnabled off; Vulkan: immediate or mailbox), so the
// renderer runs as fast as it can. The game is frame-locked: it then runs faster than real time.
// Not saved.
bool uncapped();
void set_uncapped(bool on);
// Paced frame interpolation at 120/240 fps (interp.cpp): the time the next logic step's frame is due
// on the virtual vsync (its step's slot, gx2/vsync_pacing.h); false if there is none yet
bool next_step_time(std::chrono::steady_clock::time_point& at);
void drop_step_slot();  // a logic pass more than a step late: the schedule starts anew
// The virtual vsync clock in ticks (quarter 59.94 Hz vsyncs), for frame pacing that counts vsyncs
// (Super Mario 3D World's 120 fps, sm3dw/interp_sm3dw.cpp)
uint64_t vsync_ticks();
// debug probes (sm3dw/interp_sm3dw.cpp, WWHD_SM3DW_PROBE): a uniform block set (stage 0 vertex,
// 1 pixel, 2 geometry) or uniform registers (count words copied from guest address values), and
// whether the calling thread is recording a display list
extern void (*g_uniform_block_probe)(int stage, uint32_t index, uint32_t addr, uint32_t size);
extern void (*g_uniform_regs_probe)(int stage, uint32_t offset, uint32_t count, uint32_t values);
bool recording_display_list();
// Frame skip (gx2_core.cpp): the game keeps its full speed on a GPU too slow for 60 fps by drawing every
// other frame. 0 off, 1 automatic (only while the game runs below 60), 2 always. WWHD_FRAMESKIP at start.
int frameskip();
void set_frameskip(int mode);
// "30 fps (full speed)" (docs/30fps.md): the logic keeps 60 steps a second and one frame in present_divisor()
// is drawn and presented. half_rate(): the chosen mode (WWHD_FPS=30, the menu's frame rate 30).
// present_divisor(): game frames per presented one wanted now: 1, 2 (30 fps, frame skip on) or 3 (30 fps on a
// device whose logic falls behind even so: 20 presented pictures, the game keeps its speed; signalled).
bool half_rate();
void set_half_rate(bool on);
int present_divisor();
// measured on the render thread, one-second windows: game frames (= logic steps) and presented frames a second
double logic_steps_per_s();
double presents_per_s();
// the game thread's plan for the frame it starts (sm3dw/interp_sm3dw.cpp): presented or not, and whether the
// game itself left the frame's drawing out (its display list not run: nothing to draw); emits OP_FRAME_PLAN
void plan_frame(bool presented, bool game_skip);
}  // namespace gx2

// The renderer backend (Metal). All calls come from the thread executing GX2
// commands, in submission order. Guest structures are passed by guest address.
namespace gx2 {
constexpr uint32_t kDepthSlicesReg = 0xA002;  // our convention (unused register): depth buffer array size
// our convention: CB_COLORn_TILE = view width | slices << 16 (bits 16..30) | kColorTarget3D. Slices are the
// array size of a 2D array buffer, or the depth of a volume (3D) buffer, whose slice the view selects
// (CB_COLORn_VIEW slice start). The game renders its 8x8x8 colour-grading volumes slice by slice that way.
constexpr uint32_t kColorTarget3D = 0x80000000u;
constexpr uint32_t color_target_slices(uint32_t tile) { return std::max<uint32_t>((tile >> 16) & 0x7FFF, 1); }
}

namespace gfx {
void init();                     // create device; call on the main thread before the game starts
void run_main_loop();            // window/event loop; runs on the main thread, never returns
void draw(const uint32_t* regs, uint32_t prim, uint32_t count, uint32_t indexType, uint32_t indexAddr,
          uint32_t baseVertex, uint32_t instances);
void clear_color(const uint32_t* regs, uint32_t colorBuffer, const float rgba[4]);
void clear_depth_stencil(const uint32_t* regs, uint32_t depthBuffer, float depth, uint32_t stencil, uint32_t flags);
void copy_surface(uint32_t src, uint32_t srcMip, uint32_t srcSlice, uint32_t dst, uint32_t dstMip, uint32_t dstSlice);
void copy_to_scan(uint32_t colorBuffer, uint32_t target);  // target: 1 = TV, 4 = DRC (GamePad)
void write_back_linear_targets();  // GX2DrawDone: linear render targets the CPU reads, to guest memory
void swap();                     // present the TV scan buffer
void set_frame_aspect(float a);  // aspect ratio of the TV picture from the next frame on (aspect.cpp)
// render thread: a target of this guest size is made wider/taller this frame (kx, ky != 1)
bool target_aspect_factors(uint32_t w, uint32_t h, float& kx, float& ky);
uint64_t frames_completed();     // swaps whose GPU work has finished
void with_autorelease_pool(void (*fn)());  // render thread: drain Objective-C temporaries per batch
void set_tv_format(uint32_t gx2Format, bool tv);  // GX2SetTVBuffer / GX2SetDRCBuffer
void invalidate(uint32_t flags, uint32_t addr, uint32_t size);
void flush();                    // submit queued GPU work
void wait_idle();                // GX2DrawDone: block until the GPU finished
}  // namespace gfx
