// Super Mario 3D World: widescreen hooks (tools/recomp/hooks_sm3dw.txt). The render-target and
// presentation side is the shared aspect.cpp; this file is only the game's own projections.
//
// sead::PerspectiveProjection (fovy +0xE0, aspect +0xE4, tan(fovy/2) +0xE8, near +0x30): its matrix
// and culling frustum are computed by 023D7724 from those fields (right = near * tan * aspect). The
// game's projections carry 16:9 (set by the constructor 023D763C and the camera code); entering that
// function the field is replaced with the window's aspect, so the picture and the frustum are Hor+
// together. A projection with another aspect (shadow maps, 1:1) is left alone.
#include <cstring>

#include "../aspect.h"
#include "../runtime.h"

extern "C" void f_023D7724_orig(Cpu* c);

extern "C" void hook_023D7724(Cpu* c) {
    const uint32_t obj = c->r[3];
    uint32_t bits = ld32(obj + 0xE4);
    float v;
    memcpy(&v, &bits, sizeof v);
    const float a = aspect::project_aspect(v);
    if (a != v) {
        memcpy(&bits, &a, sizeof bits);
        st32(obj + 0xE4, bits);
    }
    f_023D7724_orig(c);
}

// 0263530C: uploads a layout's 4x4 projection (GX2SetVertexUniformReg(offset, 16, this)): tagged, so the
// render thread narrows it for TV-shaped targets and the 2D keeps its proportions, centred
extern "C" void f_0263530C_orig(Cpu* c);
extern "C" void hook_0263530C(Cpu* c) { aspect::tv_projection_call(c, f_0263530C_orig); }

// ---------------------------------------------------------------------------------------------
// HD layouts (nw::lyt): the same engine as the Wind Waker HD port (aspect.cpp), fed with this game's
// functions and pane rules. Study: docs/sm3dw-widescreen-hud-study.md.
//   Pane::CalculateMtx 02636D34   edge anchoring while a TV layout's matrices are computed
//   Pane::Draw         02637768   which screen each layout root draws to
//   DrawSelf call      @026377B4 / @026377B8   content outside the 16:9 region is clipped around it
// Every layout is 1280x720 under a RootPane; the HUD sits one level down in a wrapper named after the
// layout, and pane names repeat between layouts, so a rule names its layout (the root's child on the way
// up from the pane).
#include <cmath>
#include <string_view>

#include "../aspect_panes.h"

extern "C" void f_02636D34_orig(Cpu* c);
extern "C" void f_02637768_orig(Cpu* c);
extern "C" void hook_02636D34(Cpu* c) { aspect::pane_calculate(c, f_02636D34_orig); }
extern "C" void hook_02637768(Cpu* c) { aspect::pane_draw(c, f_02637768_orig); }
extern "C" void site_026377B4(Cpu* c) { aspect::draw_self_begin(c); }
extern "C" void site_026377B8(Cpu*) { aspect::draw_self_end(); }

// the text drawing (nw::font) uploads its own projection: tagged like the layouts' (hook 0262D5CC)
extern "C" void f_0262D5CC_orig(Cpu* c);
extern "C" void hook_0262D5CC(Cpu* c) { aspect::tv_projection_call(c, f_0262D5CC_orig); }

namespace {
using namespace aspect::panes;

enum class Kind { Hud, Bar, Fill };
struct SmRule {
    std::string_view layout;  // wrapper name; empty = any layout
    std::string_view name;
    Kind kind;
    Side side;                // Bar: the edge it hangs from
    PaneRule rule;            // what aspect.cpp applies
};
constexpr PaneRule kHud{"", Where::Any, Rule::Hud};
constexpr PaneRule kFill{"", Where::Any, Rule::Fill};
constexpr PaneRule kBarTop{"", Where::Any, Rule::Frame, {}, -1e9f, {}, {}, Side::Top};
constexpr PaneRule kBarBottom{"", Where::Any, Rule::Frame, {}, -1e9f, {}, {}, Side::Bottom};

constexpr SmRule kSmRules[] = {
    // in-game HUD at the screen edges (rest positions of StageSceneLayout_Appear/Wait.bflan)
    {"StageSceneLayout", "CountrerPlayer", Kind::Hud, Side::Top, kHud},
    {"StageSceneLayout", "CounterCoin", Kind::Hud, Side::Top, kHud},
    {"StageSceneLayout", "CounterGreenStar", Kind::Hud, Side::Top, kHud},
    {"StageSceneLayout", "CounterStamp", Kind::Hud, Side::Top, kHud},
    {"StageSceneLayout", "ItemStock", Kind::Hud, Side::Top, kHud},
    {"StageSceneLayout", "CounterTime", Kind::Hud, Side::Top, kHud},
    {"StageSceneLayout", "CounterScore", Kind::Hud, Side::Top, kHud},
    // world map overlays
    {"", "CountersBase", Kind::Hud, Side::Top, kHud},
    {"", "CounterGreenStarTotal", Kind::Hud, Side::Top, kHud},
    {"", "ParGuideStart", Kind::Hud, Side::Top, kHud},
    {"", "ParGuideSelect", Kind::Hud, Side::Top, kHud},
    {"", "Miiverse", Kind::Hud, Side::Top, kHud},
    {"CameraIcon", "NormalCamera", Kind::Hud, Side::Top, kHud},
    {"CameraIcon", "GyroCamera", Kind::Hud, Side::Top, kHud},
    {"PauseMenu", "FrameSetting", Kind::Hud, Side::Top, kHud},
    {"PauseMenuMap", "FrameSetting", Kind::Hud, Side::Top, kHud},
    // full-width bars
    {"PauseMenu", "PicFrameT", Kind::Bar, Side::Top, kBarTop},
    {"PauseMenuMap", "PicFrameT", Kind::Bar, Side::Top, kBarTop},
    {"PauseMenu", "PicFrameB", Kind::Bar, Side::Bottom, kBarBottom},
    {"PauseMenuMap", "PicFrameB", Kind::Bar, Side::Bottom, kBarBottom},
    // the guide bar of the menus ("Select a file."): a part (GuideWindowParts) inside another layout, so
    // its wrapper is the menu's, not its own: matched by pane name in any layout
    {"", "PicWindow", Kind::Bar, Side::Top, kBarTop},
    {"", "PicWindowShadow", Kind::Bar, Side::Top, kBarTop},
    {"", "PicWindowEdge", Kind::Bar, Side::Top, kBarTop},
    {"GuideMessage", "PicWinT", Kind::Bar, Side::Top, kBarTop},
    {"GuideMessage", "PicWinB", Kind::Bar, Side::Bottom, kBarBottom},
    // full-screen backgrounds, fades and curtains
    {"", "PicBG", Kind::Fill, Side::Top, kFill},
    {"", "PicBase", Kind::Fill, Side::Top, kFill},
    {"", "PicWindowBoot", Kind::Fill, Side::Top, kFill},
    {"", "PicWipeFade", Kind::Fill, Side::Top, kFill},
    {"", "PicPaperBase", Kind::Fill, Side::Top, kFill},
    {"", "PicMapBase", Kind::Fill, Side::Top, kFill},
    {"", "PicCurtain", Kind::Fill, Side::Top, kFill},
    {"", "PicCurtain_00", Kind::Fill, Side::Top, kFill},
    {"", "PicKinopioCurtain", Kind::Fill, Side::Top, kFill},
    // iris/shape wipes (windows 1600-2170 wide): not clipped to the 16:9 region, scaled only as far as the
    // wider screen needs (cover keeps them as they are while they already reach the edges)
    {"", "WinCircle", Kind::Fill, Side::Top, kFill},
    {"", "WinCasino", Kind::Fill, Side::Top, kFill},
    {"", "WinFairy", Kind::Fill, Side::Top, kFill},
    {"", "WinKinoko", Kind::Fill, Side::Top, kFill},
    {"", "WinMystery", Kind::Fill, Side::Top, kFill},
    {"", "WinMiss", Kind::Fill, Side::Top, kFill},
    {"ListStampParts", "WinBG", Kind::Fill, Side::Top, kFill},
    {"ListStampParts", "WinBase", Kind::Fill, Side::Top, kFill},
};

std::string_view name_of(uint32_t pane) {
    const char* n = (const char*)mem::ptr(pane + 0x80);
    return std::string_view(n, strnlen(n, 24));
}

// the rule of a pane: the wrapper of its layout is the ancestor whose parent is the root
const PaneRule* sm3dw_rules(uint32_t pane) {
    uint32_t wrapper = pane, parent = ld32(pane + 0x0C);
    if (!parent) return nullptr;  // a root
    for (int i = 0; i < 32 && ld32(parent + 0x0C); i++) { wrapper = parent; parent = ld32(parent + 0x0C); }
    const std::string_view layout = name_of(wrapper), name = name_of(pane);
    {   // the "Back" tab (ButtonBackParts: a pane named just "Button" at the layout's bottom-left corner,
        // which other parts' buttons share the name with): by its place, it follows the left edge
        const float x = (float)ldf32(pane + 0x1C), y = (float)ldf32(pane + 0x20);
        if (name == "Button" && std::fabs(x + 640) < 8 && std::fabs(y + 360) < 8) return &kHud;
    }
    const float w = (float)ldf32(pane + 0x3C) * std::fabs((float)ldf32(pane + 0x34));
    const float h = (float)ldf32(pane + 0x40) * std::fabs((float)ldf32(pane + 0x38));
    for (const SmRule& r : kSmRules) {
        if (r.name != name || (!r.layout.empty() && r.layout != layout)) continue;
        // backgrounds are 1280x720 or larger (their centre can sit off 0,0 inside a moved parent)
        if (r.kind == Kind::Fill && !(w >= 1270 && h >= 700)) continue;
        if (r.kind == Kind::Bar && w < 1270) continue;
        return &r.rule;
    }
    return nullptr;
}
struct Register { Register() { aspect::set_rule_provider(sm3dw_rules); } } g_register;
}  // namespace
