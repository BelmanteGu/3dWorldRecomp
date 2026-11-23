// Aspect ratio of the TV picture (Graphics menu, WWHD_ASPECT): see aspect.cpp.
#pragma once
#include <cstdint>

struct Cpu;

namespace aspect {
enum Mode : int { kOriginal = 0, kWindow, k16x10, k21x9, k32x9, kCustom, kModes };
int mode();
void set_mode(int m);
const char* mode_name(int m);
float mode_value(int m);           // fixed aspect of a mode (0 for kWindow)
void set_window_aspect(float a);   // shape of the TV window's drawable (render thread, every present)
float requested();                 // what the setting asks for now
// main thread, GX2SwapScanBuffers: latch the aspect for the next game frame, update the game's
// projections; returns the aspect the render thread should switch to at this swap
float on_swap();
float game();
// a hook at a projection call: rewrites the fovy / aspect registers (f<fovyReg>, f<aspectReg>) of the
// main camera for the current aspect (Hor+; Vert+ below 16:9). Used by the per-game hook files.
void adjust_projection(Cpu* c, int fovyReg, int aspectReg, bool radians = false, bool gameplayCamera = true);
// the aspect a projection field holding `v` should have now: the window's if it is the game's standard
// 16:9 (or one we stored earlier), else `v` itself
float project_aspect(float v);
// run `fn(c)` (a layout's projection load) so its matrix upload is narrowed for TV-shaped targets
void tv_projection_call(Cpu* c, void (*fn)(Cpu*));
// ---- HD layout (nw::lyt) entry points for a game's own hooks (aspect_sm3dw.cpp): the hooks pass the
// game's original function; the pane rules come from the provider (null: the Wind Waker HD table)
namespace panes { struct PaneRule; }
using RuleProvider = const panes::PaneRule* (*)(uint32_t pane);
void set_rule_provider(RuleProvider p);
void pane_calculate(Cpu* c, void (*orig)(Cpu*));   // Pane::CalculateMtx
void pane_draw(Cpu* c, void (*orig)(Cpu*));        // Pane::Draw
void draw_self_begin(Cpu* c);                      // just before Pane::Draw calls DrawSelf (r3 = pane)
void draw_self_end();                              // just after it
float fov_scale();
void set_fov_scale(float scale); // 1 = authored gameplay FOV; events keep their original FOV
uint64_t game_frame();                // swaps so far                      // aspect the game draws with this frame
// HD layouts: the game thread marks uploads of layout projection matrices and the drawing of layout
// roots; the render thread, which knows the target, narrows the projection for TV-shaped targets
// and reports where each root went
bool tagged_projection();                          // gx2: the vertex uniform upload in progress is one
void layout_root_target(uint32_t root, bool tv);   // render thread (OP_LAYOUT_ROOT)
void set_content_clip(bool clip);
bool content_clip();
void ss_reset();  // save state loaded: every layout recomputes its matrices once
}  // namespace aspect
