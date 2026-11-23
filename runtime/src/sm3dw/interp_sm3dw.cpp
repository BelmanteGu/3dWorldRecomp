// Super Mario 3D World: 120 fps presentation, game logic unchanged at 60 steps per second
// (hooks: tools/recomp/hooks_sm3dw_interp.txt; notes: docs/sm3dw-120fps.md).
//
// The game's frame (RedCarpet procFrame_, 024DB2B8) is sead's display-list loop:
//   procDraw_ 024DB728   GX2DirectCallDisplayList of the list recorded last frame (TV and DRC),
//                        then the list index +0x388 ^= 1
//   procCalc_ 02374FA0   one logic step (input, actors, camera; models' world and view matrices
//                        into their uniform buffers, calcWorld 024E7574 / calcView 024E762C)
//   record    024DBA84   the draw tree recorded into list[+0x388] (+0x380 = 1: display-list mode)
//   [0x6C], swapBuffer_ 024DB93C (GX2DrawDone, GX2SwapScanBuffers, GX2DrawDone), the vsync wait
//   02375030 (GX2WaitForVsync until every swap has flipped)
// A logic frame N therefore shows the state S(N-1) recorded the frame before, and leaves S(N)
// recorded. At 120 fps the virtual vsync ticks twice per 59.94 Hz vsync (gx2_core.cpp) and every
// other wake is an in-between ("hold") frame: no logic, no recording; procDraw_ replays the list
// recorded by the last logic frame (and the index is put back, so the next logic frame shows that
// list again, exactly). Before the replay the hold blends what the list reads from guest memory:
// the models' uniform buffers (their view matrices, built from the camera, and their skeletons'
// world matrices) are recomputed halfway between the last two logic steps; swapBuffer_'s DrawDone
// means the render thread has consumed the replay when it returns, and the logic step's values are
// put back. Presented: S(N-1), mid(S(N-1), S(N)), S(N), mid(S(N), S(N+1)), ... (no added latency).
#include "interp_sm3dw.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <set>
#include <string>
#include <unordered_map>
#include <vector>

#include "../gx2/gx2.h"
#include "../gx2/gx2_cmd.h"
#include "../gx2/interp_camera.h"
#include "gfx/renderer.h"
#include "../runtime.h"
#include "../write_watch.h"
#include "guest_addr.h"
#include "../frame_causes.h"

extern "C" {
void f_024DB2B8_orig(Cpu* c);  // procFrame_
void f_024DB728_orig(Cpu* c);  // procDraw_
void f_02374FA0_orig(Cpu* c);  // procCalc_
void f_024DBA84_orig(Cpu* c);  // record display lists
void f_024E7574_orig(Cpu* c);  // model calcWorld
void f_024E762C_orig(Cpu* c);  // model calcView
void f_024E76DC_orig(Cpu* c);  // model buffer ring advance
void f_024DB93C(Cpu* c);       // swapBuffer_
void f_024DB6B4(Cpu* c);       // run one recorded list (GPU timer samples, DirectCall, copy to scan buffer)
void f_02375030_orig(Cpu* c);  // wait for vsync (every swap flipped)
void f_023942C8(Cpu* c);       // CriticalSection lock (the frame's draw lock)
void f_02394328(Cpu* c);       // ... unlock
}

extern "C" void hook_02375030(Cpu* c);

namespace sm3dw_interp {
namespace {
constexpr uint32_t kDrawLock = 0x103BEA04;  // the CriticalSection procFrame_ holds from draw to swap
constexpr uint32_t kListMode = 0x380, kListIndex = 0x388, kFlags = 0x74;

int env_fps() {
    const char* e = getenv("WWHD_FPS");
    return !e ? 60 : atoi(e) >= 120 ? 120 : atoi(e) > 0 && atoi(e) <= 30 ? 30 : 60;
}
std::atomic<int> g_fps{env_fps()};
std::atomic<bool> g_blend{[] {
    const char* e = getenv("WWHD_FPS_BLEND");
    return !e || atoi(e) != 0;
}()};
std::atomic<int> g_rate{1};  // vsync_rate()
std::atomic<uint64_t> g_logic_steps{0};
bool g_hold = false;  // game thread
int g_last_blended = 0;  // models the last in-between frame blended
std::chrono::steady_clock::time_point g_frame_t0;
double g_frame_work_ms = 0;  // this frame's work up to the vsync wait

// ---------------------------------------------------------------- pacing
// Wall clock: a logic step is due every 1/59.94 s. A wake (one per presented frame, after its flip)
// runs a logic step when the open time reaches it (with half a 120 Hz frame of slack for wake
// jitter), else an in-between frame; one logic step per wake at most. A late frame therefore turns
// the next in-between frame into a logic one and the game keeps its speed. If frames are late
// often, the in-between pictures pause (60 fps on the 59.94 Hz grid) and are tried again later.
struct Pacing {
    bool valid = false;
    std::chrono::steady_clock::time_point last;
    double acc = 0;  // ns of game time not run yet
    // shedding
    int window = 0, misses = 0;
    double window_ns = 0;  // sum of the window's frame times
    bool shed = false;
    uint64_t shed_until = 0;  // logic step
    uint64_t backoff = 180;   // logic steps paused after a shed (doubles up to 3600)
} g_pace;
constexpr double kStepNs = 1e9 / 59.94, kFrameNs = kStepNs / 2;
constexpr int kShedWindow = 240, kShedMisses = 12;

// statistics (WWHD_FPS_STATS=1 or WWHD_PROFILE=1): every 600 frames
struct Stats {
    uint64_t frames = 0, holds = 0, logic = 0, misses = 0;
    double logic_ms = 0, hold_ms = 0, blend_ms = 0;  // game thread work up to the vsync wait
    std::chrono::steady_clock::time_point t0 = std::chrono::steady_clock::now();
} g_stats;
bool stats_on() {
    static const bool on = getenv("WWHD_FPS_STATS") != nullptr || getenv("WWHD_PROFILE") != nullptr;
    return on;
}
double ms_since(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t).count();
}
void report() {
    if (!stats_on() || g_stats.frames < 600) return;
    const double s = std::chrono::duration<double>(std::chrono::steady_clock::now() - g_stats.t0).count();
    LOG("[fps120] %.1f frames/s, %.1f logic steps/s, %.0f%% in-between, late frames %llu; game thread per logic frame %.2f ms, "
        "per in-between frame %.2f ms (blend %.2f ms); presenting %d (chosen %d)",
        g_stats.frames / s, g_stats.logic / s, 100.0 * g_stats.holds / g_stats.frames, (unsigned long long)g_stats.misses,
        g_stats.logic ? g_stats.logic_ms / g_stats.logic : 0.0, g_stats.holds ? g_stats.hold_ms / g_stats.holds : 0.0,
        g_stats.holds ? g_stats.blend_ms / g_stats.holds : 0.0, active_fps(), fps());
    g_stats = Stats{};
}

// ---------------------------------------------------------------- probe (WWHD_SM3DW_PROBE=1)
enum Phase { kNone, kDraw, kCalc, kRecord, kPhases };
Phase g_phase = kNone;
const char* kPhaseName[kPhases] = {"other", "draw", "calc", "record"};
bool probe() {
    static const bool on = getenv("WWHD_SM3DW_PROBE") != nullptr;
    return on;
}
uint64_t g_frames = 0;
enum Counter { kCalcWorld, kCalcView, kRingAdvance, kCounters };
const char* kCounterName[kCounters] = {"calcWorld", "calcView", "ringAdvance"};
uint64_t g_count[kCounters][kPhases];
std::map<uint32_t, uint32_t> g_views;  // calcView view matrix address -> calls (this window)
std::set<uint32_t> g_probe_models;
uint32_t g_ring_model = 0;  // a model whose ring index the probe follows
std::string g_order;
bool probe_frame() { return probe() && (g_frames % 120) == 60; }

void log_mtx(const char* what, uint32_t m) {
    float v[12];
    for (int i = 0; i < 12; i++) v[i] = (float)ldf32(m + 4 * i);
    LOG("[sm3dw probe] %s %08X: [%.3f %.3f %.3f %.3f] [%.3f %.3f %.3f %.3f] [%.3f %.3f %.3f %.3f]", what, m, v[0], v[1], v[2], v[3], v[4],
        v[5], v[6], v[7], v[8], v[9], v[10], v[11]);
}
// uniform blocks/registers set while the probe frame records its lists
struct UniformUse {
    int stage;
    uint32_t index, addr, size;
    bool regs;
    std::vector<uint32_t> words;  // registers: the values as set
};
std::vector<UniformUse> g_uniform_uses;
void probe_uniform_block(int stage, uint32_t index, uint32_t addr, uint32_t size) {
    if (g_phase == kRecord && gx2::recording_display_list() && probe_frame() && g_uniform_uses.size() < 100000)
        g_uniform_uses.push_back({stage, index, addr, size, false, {}});
}
void uniform_block_hook(int stage, uint32_t index, uint32_t addr, uint32_t size) {
    if (probe()) probe_uniform_block(stage, index, addr, size);
}
void probe_uniform_regs(int stage, uint32_t offset, uint32_t count, uint32_t values) {
    if (g_phase == kRecord && gx2::recording_display_list() && probe_frame() && g_uniform_uses.size() < 100000)
    {
        UniformUse u{stage, offset, values, count * 4, true, {}};
        for (uint32_t i = 0; i < count && i < 0x400; i++) u.words.push_back(ld32(values + 4 * i));
        g_uniform_uses.push_back(std::move(u));
    }
}
// general 4x4 inverse (rows); false if singular
bool inv44(const float* m, float* out) {
    double a[4][8];
    for (int r = 0; r < 4; r++)
        for (int c = 0; c < 8; c++) a[r][c] = c < 4 ? m[r * 4 + c] : (c - 4 == r);
    for (int c = 0; c < 4; c++) {
        int p = c;
        for (int r = c + 1; r < 4; r++)
            if (std::fabs(a[r][c]) > std::fabs(a[p][c])) p = r;
        if (std::fabs(a[p][c]) < 1e-12) return false;
        if (p != c)
            for (int k = 0; k < 8; k++) std::swap(a[p][k], a[c][k]);
        const double d = a[c][c];
        for (int k = 0; k < 8; k++) a[c][k] /= d;
        for (int r = 0; r < 4; r++)
            if (r != c) {
                const double f = a[r][c];
                for (int k = 0; k < 8; k++) a[r][k] -= f * a[c][k];
            }
    }
    for (int r = 0; r < 4; r++)
        for (int c = 0; c < 4; c++) out[r * 4 + c] = (float)a[r][4 + c];
    return true;
}
// rows r0..r3 of a uniform set = projection * view * (affine model matrix)?
bool is_mvp(const float* pv_inv, const float* r) {
    float x[4];
    for (int c = 0; c < 4; c++) x[c] = pv_inv[12] * r[c] + pv_inv[13] * r[4 + c] + pv_inv[14] * r[8 + c] + pv_inv[15] * r[12 + c];
    const float scale = 1e-3f * (1 + std::fabs(r[15]) + std::fabs(r[3]));
    return std::fabs(x[0]) < 1e-3f && std::fabs(x[1]) < 1e-3f && std::fabs(x[2]) < 1e-3f && std::fabs(x[3] - 1) < scale;
}
float swapf(uint32_t v) {
    v = __builtin_bswap32(v);
    float f;
    memcpy(&f, &v, 4);
    return f;
}
// where does the camera's view matrix (or its translation) appear in what the lists read?
void probe_uniforms(uint32_t view) {
    std::map<std::pair<uint32_t, uint32_t>, std::pair<UniformUse, int>> uniq;
    int blocks = 0, regs = 0;
    for (auto& u : g_uniform_uses) {
        (u.regs ? regs : blocks)++;
        auto& e = uniq[{u.regs ? 0x80000000u + u.index * 0x10000 + (uint32_t)(std::hash<std::string>()(std::string((const char*)u.words.data(), u.words.size() * 4)) & 0xFFFF) : u.addr, u.size}];
        e.first = u;
        e.second++;
    }
    LOG("[sm3dw probe] record: %d uniform block sets, %d uniform register sets, %zu distinct (addr, size)", blocks, regs, uniq.size());
    // register sets that hold projection * view * model for a camera of a 0x100 block
    {
        std::map<uint32_t, std::vector<float>> pvs;
        for (auto& u : g_uniform_uses)
            if (!u.regs && (u.size == 0x100 || u.size == 0x150) && !pvs.count(u.addr)) {
                std::vector<float> pv(16), inv(16);
                for (int i = 0; i < 16; i++) pv[i] = swapf(ld32(u.addr + (u.size == 0x150 ? 0x80 : 0) + 4 * i));
                if (inv44(pv.data(), inv.data())) pvs[u.addr] = inv;
            }
        std::map<std::string, int> hits;
        int sets = 0, rows4 = 0;
        for (auto& u : g_uniform_uses) {
            if (!u.regs) continue;
            sets++;
            for (size_t k = 0; k + 16 <= u.words.size(); k += 4) {
                float r[16], rt[16];
                memcpy(r, &u.words[k], sizeof r);
                for (int i = 0; i < 4; i++)
                    for (int j = 0; j < 4; j++) rt[i * 4 + j] = r[j * 4 + i];
                rows4++;
                for (auto& [a, inv] : pvs)
                    for (int t = 0; t < 2; t++)
                        if (is_mvp(inv.data(), t ? rt : r)) {
                            char b[96];
                            snprintf(b, sizeof b, "%s offset %u+%zu count %u camera %08X%s", u.stage == 0 ? "VS" : "PS", u.index, k, (uint32_t)u.words.size(), a,
                                     t ? " transposed" : "");
                            hits[b]++;
                        }
            }
        }
        LOG("[sm3dw probe] %zu cameras; %d register sets, %d 4-row windows; MVP matches:", pvs.size(), sets, rows4);
        std::map<std::string, int> hist;
        for (auto& u : g_uniform_uses) {
            char b[64];
            snprintf(b, sizeof b, "%s %s %u size %X", u.regs ? "regs" : "block", u.stage == 0 ? "VS" : u.stage == 1 ? "PS" : "GS", u.index, u.size);
            hist[b]++;
        }
        std::string hs;
        for (auto& [k, n] : hist) hs += " [" + k + "] x" + std::to_string(n);
        LOG("[sm3dw probe] uniform sets while recording:%s", hs.c_str());
        std::map<std::string, int> dumped;
        for (auto& u : g_uniform_uses) {
            if (!u.regs || u.stage != 0 || u.words.size() < 12) continue;
            char k[32];
            snprintf(k, sizeof k, "r%u/%zu", u.index, u.words.size());
            if (dumped[k]++ >= 2) continue;
            std::string d;
            for (size_t i = 0; i < u.words.size() && i < 32; i++) {
                float f;
                memcpy(&f, &u.words[i], 4);
                char b[24];
                snprintf(b, sizeof b, "%s%.4g", i % 4 ? " " : i ? " | " : "", f);
                d += b;
            }
            LOG("[sm3dw probe] VS regs %u count %zu: %s", u.index, u.words.size(), d.c_str());
        }
        for (auto& u : g_uniform_uses) {
            if (u.regs || u.stage != 0 || (u.index != 4 && u.index != 6 && u.index != 2 && u.index != 3)) continue;
            char k[32];
            snprintf(k, sizeof k, "%u/%X", u.index, u.size);
            if (dumped[k]++ >= 2) continue;
            std::string d;
            for (uint32_t i = 0; i < 32 && i < u.size / 4; i++) {
                char b[24];
                snprintf(b, sizeof b, "%s%.4g", i % 4 ? " " : i ? " | " : "", swapf(ld32(u.addr + 4 * i)));
                d += b;
            }
            LOG("[sm3dw probe] VS block %u size %X at %08X: %s", u.index, u.size, u.addr, d.c_str());
        }
        int shown = 0;
        for (auto& [k, n] : hits)
            if (shown++ < 30) LOG("[sm3dw probe]   %s: x%d", k.c_str(), n);
    }
    if (!view) return;
    float keys[3] = {(float)ldf32(view + 12), (float)ldf32(view + 28), (float)ldf32(view + 44)};
    static const bool pv = getenv("WWHD_SM3DW_PROBE_PV") != nullptr;  // search projection * view instead
    if (pv)
        for (auto& [k, e] : uniq)
            if (k.second == 0x150 && !e.first.regs && std::fabs(swapf(ld32(k.first + 12)) - keys[0]) < 1e-2f) {
                keys[0] = swapf(ld32(k.first + 0x8C));
                keys[1] = swapf(ld32(k.first + 0x9C));
                keys[2] = swapf(ld32(k.first + 0xAC));
                LOG("[sm3dw probe] searching projection * view translations %.3f %.3f %.3f", keys[0], keys[1], keys[2]);
                break;
            }
    int shown = 0;
    for (auto& [k, e] : uniq) {
        const UniformUse& u = e.first;
        const uint32_t n = std::min<uint32_t>(u.size / 4, 0x1000);
        for (uint32_t i = 0; i < n; i++) {
            const uint32_t w = u.regs ? (i < u.words.size() ? u.words[i] : 0) : ld32(u.addr + 4 * i);
            float be, le = swapf(w);
            memcpy(&be, &w, 4);
            for (float key : keys) {
                if (std::fabs(key) < 1.0f) continue;
                const bool hb = std::fabs(std::fabs(be) - std::fabs(key)) < std::fabs(key) * 1e-4f,
                           hl = std::fabs(std::fabs(le) - std::fabs(key)) < std::fabs(key) * 1e-4f;
                if (pv && u.size == 0x150 && !u.regs) continue;
                if ((hb || hl) && shown < 40) {
                    if (shown == 0 || (shown < 12 && i == 3)) {
                        std::string d;
                        for (uint32_t k = 0; k < n && k < 0x60; k++) {
                            char b[24];
                            snprintf(b, sizeof b, "%s%.4g", k % 4 ? " " : k ? " | " : "", swapf(ld32(u.addr + 4 * k)));
                            d += b;
                        }
                        LOG("[sm3dw probe] block %08X (%s %u, size %X) as LE floats: %s", u.addr, u.stage == 0 ? "VS" : u.stage == 1 ? "PS" : "GS",
                            u.index, u.size, d.c_str());
                    }
                    shown++;
                    LOG("[sm3dw probe] view translation %.3f in %s %s %u addr %08X size %X (x%d) at +0x%X (%s)", key, u.regs ? "regs" : "block",
                        u.stage == 0 ? "VS" : u.stage == 1 ? "PS" : "GS", u.index, u.addr, u.size, e.second, i * 4, hb ? "BE" : "LE");
                }
            }
        }
    }
    g_uniform_uses.clear();
}
uint32_t g_probe_view = 0;

void probe_report(uint32_t self) {
    probe_uniforms(g_probe_view);
    if (g_ring_model)
        LOG("[sm3dw probe] model %08X ring index at the end of the frame %u", g_ring_model, ld32(ld32(g_ring_model + 0x14)));
    LOG("[sm3dw probe] frame %llu: order %s; +0x380 %u +0x388 %d lists TV %08X/%08X (%X/%X) DRC %08X/%08X (%X/%X) +0x3EC %u +0x3ED %u",
        (unsigned long long)g_frames, g_order.c_str(), ld8(self + 0x380), (int)ld32(self + 0x388), ld32(self + 0x38C), ld32(self + 0x390),
        ld32(self + 0x39C), ld32(self + 0x3A0), ld32(self + 0x394), ld32(self + 0x398), ld32(self + 0x3A4), ld32(self + 0x3A8),
        ld8(self + 0x3EC), ld8(self + 0x3ED));
    std::string s;
    for (int k = 0; k < kCounters; k++) {
        char b[160];
        snprintf(b, sizeof b, " %s draw %llu calc %llu record %llu other %llu;", kCounterName[k], (unsigned long long)g_count[k][kDraw],
                 (unsigned long long)g_count[k][kCalc], (unsigned long long)g_count[k][kRecord], (unsigned long long)g_count[k][kNone]);
        s += b;
    }
    LOG("[sm3dw probe] per 120 frames:%s models %zu", s.c_str(), g_probe_models.size());
    for (auto& [v, n] : g_views) {
        char b[64];
        snprintf(b, sizeof b, "calcView view x%u", n);
        log_mtx(b, v);
    }
    memset(g_count, 0, sizeof g_count);
    g_views.clear();
    g_probe_models.clear();
}


// ---------------------------------------------------------------- what the in-between frame blends
// Every model's calcView(model, viewMtx) (024E762C) runs in procCalc_: it multiplies the camera's
// view matrix (sead Mtx34, world to view) with the skeleton's world matrices (SkeletonObj +0x10,
// 0x30 bytes per matrix, count u16 at +0x2C; computed by calcWorld 024E7574) into the model's
// uniform buffers (the ring slot *(model+0x14) the recorded list points at). The in-between frame
// reruns those calls with the camera and the world matrices halfway between the last two logic
// steps, and once more with the logic step's values after the replay has been consumed.
struct Mtx34 {
    float m[12];
};
Mtx34 read_mtx(uint32_t a) {
    Mtx34 r;
    for (int i = 0; i < 12; i++) r.m[i] = (float)ldf32(a + 4 * i);
    return r;
}
void write_mtx(uint32_t a, const Mtx34& v) {
    for (int i = 0; i < 12; i++) stf32(a + 4 * i, v.m[i]);
}

// rotation matrix (rows of the 3x3) <-> quaternion (x, y, z, w)
void mtx_to_quat(const float* m, float q[4]) {
    auto at = [&](int r, int c) { return m[r * 4 + c]; };
    const float tr = at(0, 0) + at(1, 1) + at(2, 2);
    if (tr > 0) {
        const float s = std::sqrt(tr + 1.0f) * 2;
        q[3] = 0.25f * s;
        q[0] = (at(2, 1) - at(1, 2)) / s;
        q[1] = (at(0, 2) - at(2, 0)) / s;
        q[2] = (at(1, 0) - at(0, 1)) / s;
    } else if (at(0, 0) > at(1, 1) && at(0, 0) > at(2, 2)) {
        const float s = std::sqrt(1.0f + at(0, 0) - at(1, 1) - at(2, 2)) * 2;
        q[3] = (at(2, 1) - at(1, 2)) / s;
        q[0] = 0.25f * s;
        q[1] = (at(0, 1) + at(1, 0)) / s;
        q[2] = (at(0, 2) + at(2, 0)) / s;
    } else if (at(1, 1) > at(2, 2)) {
        const float s = std::sqrt(1.0f + at(1, 1) - at(0, 0) - at(2, 2)) * 2;
        q[3] = (at(0, 2) - at(2, 0)) / s;
        q[0] = (at(0, 1) + at(1, 0)) / s;
        q[1] = 0.25f * s;
        q[2] = (at(1, 2) + at(2, 1)) / s;
    } else {
        const float s = std::sqrt(1.0f + at(2, 2) - at(0, 0) - at(1, 1)) * 2;
        q[3] = (at(1, 0) - at(0, 1)) / s;
        q[0] = (at(0, 2) + at(2, 0)) / s;
        q[1] = (at(1, 2) + at(2, 1)) / s;
        q[2] = 0.25f * s;
    }
}
void quat_to_mtx(const float q[4], float* m) {
    const float x = q[0], y = q[1], z = q[2], w = q[3];
    m[0] = 1 - 2 * (y * y + z * z), m[1] = 2 * (x * y - z * w), m[2] = 2 * (x * z + y * w);
    m[4] = 2 * (x * y + z * w), m[5] = 1 - 2 * (x * x + z * z), m[6] = 2 * (y * z - x * w);
    m[8] = 2 * (x * z - y * w), m[9] = 2 * (y * z + x * w), m[10] = 1 - 2 * (x * x + y * y);
}

// The camera halfway: its position (-R^T t) blended linearly, its orientation by slerp; false for a
// cut (a jump or a turn no camera makes in one step)
bool blend_view(const Mtx34& a, const Mtx34& b, float t, Mtx34& out) {
    float pa[3], pb[3];
    for (int i = 0; i < 3; i++) {
        pa[i] = -(a.m[0 * 4 + i] * a.m[3] + a.m[1 * 4 + i] * a.m[7] + a.m[2 * 4 + i] * a.m[11]);
        pb[i] = -(b.m[0 * 4 + i] * b.m[3] + b.m[1 * 4 + i] * b.m[7] + b.m[2 * 4 + i] * b.m[11]);
    }
    const float d = std::sqrt((pa[0] - pb[0]) * (pa[0] - pb[0]) + (pa[1] - pb[1]) * (pa[1] - pb[1]) + (pa[2] - pb[2]) * (pa[2] - pb[2]));
    float qa[4], qb[4];
    mtx_to_quat(a.m, qa);
    mtx_to_quat(b.m, qb);
    float dot = qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3];
    if (dot < 0) {
        for (float& v : qb) v = -v;
        dot = -dot;
    }
    static const float kCut = getenv("WWHD_FPS_CAMERA_CUT") ? (float)atof(getenv("WWHD_FPS_CAMERA_CUT")) : 600.0f;
    if (d > kCut || dot < 0.966f) return false;  // more than 600 units or 30 degrees in 1/60 s
    float wa, wb;
    const float ang = std::acos(std::min(1.0f, dot));
    if (ang > 1e-4f) {
        wa = std::sin((1 - t) * ang) / std::sin(ang);
        wb = std::sin(t * ang) / std::sin(ang);
    } else {
        wa = 1 - t;
        wb = t;
    }
    float q[4], n = 0;
    for (int i = 0; i < 4; i++) {
        q[i] = wa * qa[i] + wb * qb[i];
        n += q[i] * q[i];
    }
    n = 1.0f / std::sqrt(n);
    for (float& v : q) v *= n;
    quat_to_mtx(q, out.m);
    float p[3];
    for (int i = 0; i < 3; i++) p[i] = (1 - t) * pa[i] + t * pb[i];
    for (int r = 0; r < 3; r++) out.m[r * 4 + 3] = -(out.m[r * 4 + 0] * p[0] + out.m[r * 4 + 1] * p[1] + out.m[r * 4 + 2] * p[2]);
    return true;
}

// one logic step's calls and values
struct StepViews {
    uint64_t step = 0;
    std::unordered_map<uint32_t, Mtx34> views;  // view matrix address -> its value at calcView
    std::vector<std::pair<uint32_t, uint32_t>> calls;  // (model, view address), in call order
};
StepViews g_prev_views, g_cur_views;
// skeleton world matrices per skeleton, for the last two steps
struct Pose {
    uint64_t step = 0;
    uint32_t mtx = 0;  // the array they were read from
    std::vector<Mtx34> m;
};
struct SkelPoses {
    Pose prev, cur;
};
std::unordered_map<uint32_t, SkelPoses> g_poses;  // by SkeletonObj
bool g_collect = false;   // inside a logic step's procCalc_
bool g_rerun = false;     // the in-between frame's own calcView calls

bool bones_on() {
    static const bool on = !getenv("WWHD_FPS_BONES") || atoi(getenv("WWHD_FPS_BONES")) != 0;
    return on;
}
uint32_t skeleton_of(uint32_t model) {
    const uint32_t obj = ld32(model);
    return obj ? ld32(obj + 0x18) : 0;
}
bool read_pose(uint32_t skel, Pose& out, uint64_t step) {
    const uint32_t mtx = ld32(skel + 0x10);
    const uint32_t n = ld16(skel + 0x2C);
    if (!mtx || n == 0 || n > 1024) return false;
    out.step = step;
    out.mtx = mtx;
    out.m.resize(n);
    for (uint32_t i = 0; i < n; i++) out.m[i] = read_mtx(mtx + 0x30 * i);
    return true;
}
// world matrices halfway; false when the skeleton cannot blend (new, re-created, or a bone jumped)
bool blend_pose(const SkelPoses& p, uint64_t step, float t, std::vector<Mtx34>& out) {
    if (p.cur.step != step || p.prev.step + 1 != step || p.prev.m.size() != p.cur.m.size() || p.prev.mtx != p.cur.mtx) return false;
    constexpr float kCutDist = 400.0f;
    out.resize(p.cur.m.size());
    for (size_t i = 0; i < out.size(); i++) {
        const Mtx34 &a = p.prev.m[i], &b = p.cur.m[i];
        const float dx = a.m[3] - b.m[3], dy = a.m[7] - b.m[7], dz = a.m[11] - b.m[11];
        if (dx * dx + dy * dy + dz * dz > kCutDist * kCutDist) return false;
        for (int k = 0; k < 12; k++) out[i].m[k] = (1 - t) * a.m[k] + t * b.m[k];
    }
    return true;
}

uint32_t g_scratch = 0;  // guest memory for blended view matrices (one per view address)
struct Rerun {
    uint32_t model, view;
    uint32_t skel = 0;  // world matrices were blended (restore from g_poses[skel].cur)
};
std::vector<Rerun> g_reruns;

// before the replay: rerun calcView halfway; returns the number of models
int blend_before_replay(Cpu* c) {
    g_reruns.clear();
    const uint64_t step = g_cur_views.step;
    if (!bones_on() || g_cur_views.calls.empty() || step != g_logic_steps.load(std::memory_order_relaxed)) return 0;
    const float t = 0.5f;
    const uint32_t r3 = c->r[3], r4 = c->r[4];
    std::vector<Mtx34> pose;
    std::unordered_map<uint32_t, bool> done;  // a model's calcView once (its last call wins)
    for (auto it = g_cur_views.calls.rbegin(); it != g_cur_views.calls.rend(); ++it) {
        const auto [model, view] = *it;
        if (done.count(model)) continue;
        done[model] = true;
        const uint32_t skel = skeleton_of(model);
        auto pit = skel ? g_poses.find(skel) : g_poses.end();
        if (pit == g_poses.end() || ld32(skel + 0x10) != pit->second.cur.mtx || !blend_pose(pit->second, step, t, pose)) continue;
        bool moved = false;
        for (size_t i = 0; i < pose.size() && !moved; i++) moved = memcmp(&pose[i], &pit->second.cur.m[i], sizeof(Mtx34)) != 0;
        if (!moved) continue;  // a still model keeps its uniform buffer
        for (size_t i = 0; i < pose.size(); i++) write_mtx(pit->second.cur.mtx + 0x30 * (uint32_t)i, pose[i]);
        c->r[3] = model;
        c->r[4] = view;
        f_024E762C_orig(c);
        g_reruns.push_back({model, view, skel});
    }
    c->r[3] = r3;
    c->r[4] = r4;
    return (int)g_reruns.size();
}

// a logic step starts: the last one's calls become the previous step's
void begin_step(uint64_t step) {
    std::swap(g_prev_views, g_cur_views);
    g_cur_views.views.clear();
    g_cur_views.calls.clear();
    g_cur_views.step = step;
    if (step % 600 == 0)  // forget skeletons that were not drawn lately (deleted ones)
        for (auto it = g_poses.begin(); it != g_poses.end();) it = it->second.cur.step + 2 < step ? g_poses.erase(it) : std::next(it);
}
// calcView in a logic step's procCalc_: what the in-between frame reruns
void note_calc_view(uint32_t model, uint32_t view) {
    const uint64_t step = g_cur_views.step;
    if (!g_cur_views.views.count(view)) g_cur_views.views[view] = read_mtx(view);
    g_cur_views.calls.emplace_back(model, view);
    if (!bones_on()) return;
    const uint32_t skel = skeleton_of(model);
    if (!skel) return;
    SkelPoses& p = g_poses[skel];
    if (p.cur.step != step) std::swap(p.prev, p.cur);
    if (!read_pose(skel, p.cur, step)) p.cur.step = 0;
}

// after the replay has been consumed: the logic step's world matrices and uniform buffers back
void restore_after_replay(Cpu* c) {
    const uint32_t r3 = c->r[3], r4 = c->r[4];
    for (const Rerun& r : g_reruns) {
        if (r.skel) {
            const Pose& cur = g_poses[r.skel].cur;
            for (size_t i = 0; i < cur.m.size(); i++) write_mtx(cur.mtx + 0x30 * (uint32_t)i, cur.m[i]);
        }
        c->r[3] = r.model;
        c->r[4] = r.view;
        f_024E762C_orig(c);
    }
    g_reruns.clear();
    c->r[3] = r3;
    c->r[4] = r4;
}

// ---------------------------------------------------------------- in-between frame
// WWHD_FPS_HOLD_DRC=1: in-between frames replay the GamePad's list too
bool replay_drc() {
    static const bool on = getenv("WWHD_FPS_HOLD_DRC") && atoi(getenv("WWHD_FPS_HOLD_DRC")) != 0;
    return on;
}
bool can_hold(uint32_t self) {
    if (!ld8(self + kListMode) || (int32_t)ld32(self + kListIndex) < 0) return false;  // no recorded list to replay
    const uint32_t flags = ld32(self + kFlags);
    if ((flags & 1) && (flags & 2)) return false;  // drawing suspended
    return g_logic_steps.load(std::memory_order_relaxed) > 0;
}

void hold_frame_run(Cpu* c, uint32_t self) {
    const uint32_t lock = GD(kDrawLock);
    c->r[3] = ld32(lock);
    f_023942C8(c);
    const uint32_t index = ld32(self + kListIndex);
    const auto tb = std::chrono::steady_clock::now();
    g_rerun = true;
    g_last_blended = blend() ? blend_before_replay(c) : 0;
    const bool blended = g_last_blended > 0;
    if (blend()) {  // the render thread blends the camera blocks this replay reads (gx2/interp_camera.h)
        const float t = 0.5f;
        uint32_t w[2] = {1, 0};
        memcpy(&w[1], &t, 4);
        gx2::emit(gx2::OP_INTERP_FRAME, w, 2);
    }
    double blend_ms = ms_since(tb);
    static const bool no_replay = getenv("WWHD_FPS_TEST_NOREPLAY") != nullptr;  // debug
    static int dbg = getenv("WWHD_FPS_LISTS") ? 6 : 0;
    if (dbg > 0 && g_logic_steps.load() > 2000) {
        dbg--;
        LOG("[fps120 lists] in-between replays index %d list %08X", (int)index, ld32(self + 0x38C + 4 * index));
    }
    if (no_replay) {
    } else if (replay_drc()) {
        c->r[3] = self;
        f_024DB728_orig(c);  // replay list[index] (TV and DRC)
    } else {
        // procDraw_'s TV half only: the GamePad keeps the logic step's picture (60 fps) and the
        // in-between frame costs the TV list alone
        c->r[3] = ld32(self + 0x38C + 4 * index);
        c->r[4] = ld32(self + 0x39C + 4 * index);
        c->r[5] = ld32(self + 0x378);
        c->r[6] = c->r[5] + 8;
        c->r[7] = ld32(self + 0x88);
        c->r[8] = ld32(self + 0x360);
        c->r[9] = self + 0x368;
        c->r[10] = ld8(self + 0x3ED);
        f_024DB6B4(c);
    }
    st32(self + kListIndex, index);  // the next logic frame replays it again (the exact step)
    c->r[3] = self;
    f_024DB93C(c);  // swap: GX2DrawDone before and after, so the replay has been consumed
    if (blended) {
        const auto tr = std::chrono::steady_clock::now();
        restore_after_replay(c);
        blend_ms += ms_since(tr);
    }
    g_rerun = false;
    g_stats.blend_ms += blend_ms;
    c->r[3] = ld32(lock);
    f_02394328(c);
    c->r[3] = self;
    hook_02375030(c);  // until it has flipped
}

// a 120 fps wake: true for an in-between frame
bool plan_wake(uint32_t self) {
    const auto now = std::chrono::steady_clock::now();
    Pacing& p = g_pace;
    const uint64_t steps = g_logic_steps.load(std::memory_order_relaxed);
    if (p.shed && steps >= p.shed_until) {
        p.shed = false;
        p.window = p.misses = 0;
        p.window_ns = 0;
        p.valid = false;
        LOG("[fps120] in-between pictures resumed");
    }
    g_rate.store(p.shed ? 1 : 2, std::memory_order_relaxed);
    if (p.shed) {
        p.valid = false;
        return false;
    }
    double dt = 0;
    if (!p.valid) {
        p.valid = true;
        p.acc = kStepNs;
    } else {
        dt = std::chrono::duration<double, std::nano>(now - p.last).count();
        p.acc += std::min(dt, 4 * kStepNs);
    }
    p.last = now;
    if (dt > kFrameNs * 1.25) {  // the previous frame missed its 120 Hz vsync
        p.misses++;
        g_stats.misses++;
    }
    // A single long frame (a shader or a scene load) used to push the window's mean over the limit by itself
    // (one 500 ms frame among 240 makes it 10.4 ms): 120 fps paused for 3 s, 6 s, ... up to a minute after a
    // one-off hitch that pausing does not help (issue #16). The mean takes each frame capped at two periods;
    // the hitch still counts as one late frame, and steady slowness still pauses (misses or mean).
    p.window_ns += std::min(dt, 2 * kFrameNs);
    if (++p.window >= kShedWindow) {
        // too many frames late, or the frames run slower than 120 fps on average (a steady 9.7 ms
        // each never misses by much, but presents ~103 uneven frames a second)
        const double mean = p.window_ns / (p.window - 1);
        static const bool never = getenv("WWHD_FPS_NO_SHED") != nullptr;  // tests: in-between frames even when late
        if (!never && (p.misses > kShedMisses || mean > kFrameNs * 1.05)) {
            p.shed = true;
            p.shed_until = steps + p.backoff;
            LOG("[fps120] in-between pictures paused for %llu logic steps: %d of %d frames late, %.2f ms per frame",
                (unsigned long long)p.backoff, p.misses, p.window, mean / 1e6);
            p.backoff = std::min<uint64_t>(p.backoff * 2, 3600);
            g_rate.store(1, std::memory_order_relaxed);
            p.window = p.misses = 0;
            p.window_ns = 0;
            return false;
        }
        if (p.misses == 0) p.backoff = 180;
        p.window = p.misses = 0;
        p.window_ns = 0;
    }
    // tests (pictures of consecutive frames, which slow them down): logic and in-between frames
    // strictly alternate, whatever the time
    static const bool alternate = getenv("WWHD_FPS_TEST_ALTERNATE") != nullptr;
    static bool last_hold = false;
    if (alternate) {
        last_hold = !last_hold && can_hold(self);
        return last_hold;
    }
    if (p.acc < kStepNs - kFrameNs / 2 && can_hold(self)) return true;
    p.acc = std::clamp(p.acc - kStepNs, -kFrameNs / 2, kStepNs);
    return false;
}
// WWHD_FPS_TRACE=from-to: one line per frame in that range of renderer frames (the numbers of
// WWHD_TEST_SCREENSHOT): in-between or logic, the logic step, how many models were blended
void trace_frame(bool hold) {
    static uint64_t from = 0, to = 0;
    static const bool on = [] {
        const char* e = getenv("WWHD_FPS_TRACE");
        return e && sscanf(e, "%llu-%llu", (unsigned long long*)&from, (unsigned long long*)&to) == 2;
    }();
    if (!on) return;
    const uint64_t f = render::frame_count();
    if (f < from || f > to) return;
    static uint64_t g_last_replaced = 0, g_last_regs = 0;
    LOG("[fps120] frame %llu (swap of this wake ~%llu): %s, logic step %llu, %d models blended, camera blocks replaced since %d, register matrices %d", (unsigned long long)f,
        (unsigned long long)f + 1, hold ? "in-between" : "logic", (unsigned long long)logic_steps(), hold ? g_last_blended : 0,
        (int)(gx2::interp_camera::replaced_blocks() - g_last_replaced), (int)(gx2::interp_camera::replaced_regs() - g_last_regs));
    g_last_replaced = gx2::interp_camera::replaced_blocks();
    g_last_regs = gx2::interp_camera::replaced_regs();
}

// ---------------------------------------------------------------- 30 fps (full speed) and frame skip
// One frame in gx2::present_divisor() is presented (docs/30fps.md). The frame loop draws, in each frame, the
// display list the frame before recorded; with the list mode on, the game's own work is cut down too: a frame
// that is not presented leaves procDraw_ out (no list runs: nothing to draw, nothing for the render thread or
// the GPU, and the list index stays), the presented one leaves the recording out (nothing would draw it), and
// the frame just before a presented one records the list it draws. So every recorded list is drawn whole, in a
// presented frame, and the logic (procCalc_) runs in every frame. A change of the divisor starts at a presented
// frame (the frame before it always recorded). Without the list mode (the game drawing as it goes) every frame
// runs as usual and the render thread leaves the steady draws of the frames not presented out.
bool g_skip_draw = false, g_skip_record = false;  // game thread, this frame
void plan_frame(uint32_t self) {
    static int phase = 0, cur = 1;
    static bool prevList = false;
    const int want = gx2::present_divisor();
    if (phase == 0) cur = want;
    const bool presented = phase == 0;
    const uint32_t flags = ld32(self + kFlags);
    const bool list = ld8(self + kListMode) && (int32_t)ld32(self + kListIndex) >= 0 && !((flags & 1) && (flags & 2));
    const bool cut = cur > 1 && list && prevList;
    g_skip_draw = cut && !presented;
    g_skip_record = cut && phase != cur - 1;
    if (cur > 1 || want > 1) gx2::plan_frame(presented, g_skip_draw);
    prevList = list;
    phase = (phase + 1) % cur;
}
}  // namespace

int fps() { return g_fps.load(std::memory_order_relaxed); }
void set_fps(int f) {
    f = f >= 120 ? 120 : f > 0 && f <= 30 ? 30 : 60;
    gx2::set_half_rate(f == 30);
    if (g_fps.exchange(f) != f) LOG("[fps120] presentation rate %d fps", f);
}
int active_fps() { return fps() == 120 && g_rate.load(std::memory_order_relaxed) == 2 ? 120 : 60; }
bool blend() { return g_blend.load(std::memory_order_relaxed); }
void set_blend(bool on) { g_blend.store(on, std::memory_order_relaxed); }
int vsync_rate() { return g_rate.load(std::memory_order_relaxed); }
uint64_t logic_steps() { return g_logic_steps.load(std::memory_order_relaxed); }
bool hold_frame() { return g_hold; }
}  // namespace sm3dw_interp

using namespace sm3dw_interp;

extern "C" void hook_024DB2B8(Cpu* c) {
    const uint32_t self = c->r[3];
    g_frame_t0 = std::chrono::steady_clock::now();
    g_frame_work_ms = 0;
    if (probe_frame()) g_order.clear();
    if (probe() && !gx2::g_uniform_regs_probe) {
        gx2::g_uniform_block_probe = uniform_block_hook;
        gx2::g_uniform_regs_probe = probe_uniform_regs;
    }
    bool hold = false;
    // debug: WWHD_FPS_AFTER=n keeps 60 fps until renderer frame n (scripted tests reach the game at 60)
    static const uint64_t after = getenv("WWHD_FPS_AFTER") ? strtoull(getenv("WWHD_FPS_AFTER"), nullptr, 10) : 0;
    if (fps() == 120 && render::frame_count() >= after) {
        hold = plan_wake(self);
    } else {
        g_rate.store(1, std::memory_order_relaxed);
        g_pace.valid = false;
        g_pace.shed = false;
    }
    g_hold = hold;
    if (fps() != 120) plan_frame(self);
    if (hold) {
        hold_frame_run(c, self);
        g_stats.holds++;
        g_stats.hold_ms += g_frame_work_ms;
    } else {
        const uint64_t step = g_logic_steps.fetch_add(1, std::memory_order_relaxed) + 1;
        g_collect = fps() == 120 && blend();
        if (g_collect) begin_step(step);
        f_024DB2B8_orig(c);
        g_collect = false;
        g_stats.logic++;
        g_stats.logic_ms += g_frame_work_ms;
    }
    g_hold = false;
    g_skip_draw = g_skip_record = false;
    trace_frame(hold);
    g_stats.frames++;
    report();
    if (probe_frame()) probe_report(self);
    g_frames++;
}

static void phase_call(Cpu* c, Phase p, void (*orig)(Cpu*), const char* tag) {
    const Phase prev = g_phase;
    g_phase = p;
    if (probe_frame()) g_order += tag;
    orig(c);
    g_phase = prev;
}
extern "C" void hook_024DB728(Cpu* c) {
    static int dbg = getenv("WWHD_FPS_LISTS") ? 12 : 0;
    if (dbg > 0 && g_logic_steps.load() > 2000) {
        dbg--;
        const uint32_t self = c->r[3], idx = ld32(self + kListIndex);
        LOG("[fps120 lists] procDraw_ (%s) runs index %d list %08X", g_hold ? "in-between" : "logic", (int)idx, ld32(self + 0x38C + 4 * idx));
    }
    if (g_skip_draw) return;  // 30 fps / frame skip: a frame not presented runs no list (plan_frame)
    phase_call(c, kDraw, f_024DB728_orig, "D");
}
extern "C" void hook_02374FA0(Cpu* c) { phase_call(c, kCalc, f_02374FA0_orig, "C"); }
extern "C" void hook_024DBA84(Cpu* c) {
    static int dbg = getenv("WWHD_FPS_LISTS") ? 12 : 0;
    if (dbg > 0 && g_logic_steps.load() > 2000) {
        dbg--;
        const uint32_t self = c->r[3], idx = ld32(self + kListIndex);
        LOG("[fps120 lists] record into index %d list %08X", (int)idx, ld32(self + 0x38C + 4 * idx));
    }
    if (g_skip_record) return;  // 30 fps / frame skip: no presented frame would draw this list (plan_frame)
    phase_call(c, kRecord, f_024DBA84_orig, "R");
}

extern "C" void hook_024E7574(Cpu* c) {
    g_count[kCalcWorld][g_phase]++;
    f_024E7574_orig(c);
}
extern "C" void hook_024E762C(Cpu* c) {
    g_count[kCalcView][g_phase]++;
    if (g_collect && g_phase == kCalc && !g_rerun) note_calc_view(c->r[3], c->r[4]);
    if (probe()) {
        g_views[c->r[4]]++;
        g_probe_view = c->r[4];
        g_probe_models.insert(c->r[3]);
        if (probe_frame() && g_phase == kCalc) {
            static uint64_t frame = ~0ull;
            if (frame != g_frames) {
                frame = g_frames;
                g_ring_model = c->r[3];
            }
            if (c->r[3] == g_ring_model)
                LOG("[sm3dw probe] model %08X calcView in %s, ring index %u of %u (index word %08X)", c->r[3], kPhaseName[g_phase],
                    ld32(ld32(c->r[3] + 0x14)), ld32(c->r[3] + 0x18), ld32(c->r[3] + 0x14));
        }
    }
    f_024E762C_orig(c);
}
extern "C" void hook_024E76DC(Cpu* c) {
    g_count[kRingAdvance][g_phase]++;
    const bool log = probe_frame() && g_ring_model && (c->r[3] == g_ring_model || ld32(c->r[3] + 0x14) == ld32(g_ring_model + 0x14));
    const uint32_t obj = c->r[3], before = log ? ld32(ld32(obj + 0x14)) : 0;
    f_024E76DC_orig(c);
    if (log)
        LOG("[sm3dw probe] ring advance of %08X in %s: %u -> %u (lr %08X)", obj, kPhaseName[g_phase], before, ld32(ld32(obj + 0x14)), c->lr);
}

// the frame's work ends where it starts waiting for its flip
extern "C" void hook_02375030(Cpu* c) {
    if (g_frame_work_ms == 0) g_frame_work_ms = ms_since(g_frame_t0);
    frame_causes::max(frame_causes::kGameWork, uint64_t(g_frame_work_ms * 1e6));
    f_02375030_orig(c);
}
