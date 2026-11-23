// Camera of in-between pictures, on the render thread (interp_camera.h).
#include "interp_camera.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <unordered_map>
#include <vector>

#include "runtime.h"

namespace gx2::interp_camera {
namespace {
// 4x4 row-major helpers
void mul44(const float* a, const float* b, float* out) {
    float o[16];
    for (int r = 0; r < 4; r++)
        for (int c = 0; c < 4; c++) o[r * 4 + c] = a[r * 4] * b[c] + a[r * 4 + 1] * b[4 + c] + a[r * 4 + 2] * b[8 + c] + a[r * 4 + 3] * b[12 + c];
    memcpy(out, o, sizeof o);
}
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
bool rigid3(const float* m, int sr, int sc) {  // a 3x3 rotation (rows sr apart, columns sc apart)
    for (int r = 0; r < 3; r++) {
        float n = 0;
        for (int c = 0; c < 3; c++) n += m[r * sr + c * sc] * m[r * sr + c * sc];
        if (!(std::fabs(n - 1) < 2e-3f)) return false;
    }
    const float d01 = m[0] * m[sr] + m[sc] * m[sr + sc] + m[2 * sc] * m[sr + 2 * sc];
    return std::fabs(d01) < 2e-3f;
}
void position(const float* v, float* p) {  // camera position of a view matrix: -R^T t
    for (int i = 0; i < 3; i++) p[i] = -(v[0 * 4 + i] * v[3] + v[1 * 4 + i] * v[7] + v[2 * 4 + i] * v[11]);
}
void inv_rigid(const float* m, float* out) {
    for (int r = 0; r < 3; r++) {
        for (int c = 0; c < 3; c++) out[r * 4 + c] = m[c * 4 + r];
        out[r * 4 + 3] = -(m[0 * 4 + r] * m[3] + m[1 * 4 + r] * m[7] + m[2 * 4 + r] * m[11]);
    }
    out[12] = out[13] = out[14] = 0, out[15] = 1;
}

// rotation <-> quaternion
void to_quat(const float* m, float q[4]) {
    auto at = [&](int r, int c) { return m[r * 4 + c]; };
    const float tr = at(0, 0) + at(1, 1) + at(2, 2);
    if (tr > 0) {
        const float s = std::sqrt(tr + 1.0f) * 2;
        q[3] = 0.25f * s, q[0] = (at(2, 1) - at(1, 2)) / s, q[1] = (at(0, 2) - at(2, 0)) / s, q[2] = (at(1, 0) - at(0, 1)) / s;
    } else if (at(0, 0) > at(1, 1) && at(0, 0) > at(2, 2)) {
        const float s = std::sqrt(1.0f + at(0, 0) - at(1, 1) - at(2, 2)) * 2;
        q[3] = (at(2, 1) - at(1, 2)) / s, q[0] = 0.25f * s, q[1] = (at(0, 1) + at(1, 0)) / s, q[2] = (at(0, 2) + at(2, 0)) / s;
    } else if (at(1, 1) > at(2, 2)) {
        const float s = std::sqrt(1.0f + at(1, 1) - at(0, 0) - at(2, 2)) * 2;
        q[3] = (at(0, 2) - at(2, 0)) / s, q[0] = (at(0, 1) + at(1, 0)) / s, q[1] = 0.25f * s, q[2] = (at(1, 2) + at(2, 1)) / s;
    } else {
        const float s = std::sqrt(1.0f + at(2, 2) - at(0, 0) - at(1, 1)) * 2;
        q[3] = (at(1, 0) - at(0, 1)) / s, q[0] = (at(0, 2) + at(2, 0)) / s, q[1] = (at(1, 2) + at(2, 1)) / s, q[2] = 0.25f * s;
    }
}
void from_quat(const float q[4], float* m) {
    const float x = q[0], y = q[1], z = q[2], w = q[3];
    m[0] = 1 - 2 * (y * y + z * z), m[1] = 2 * (x * y - z * w), m[2] = 2 * (x * z + y * w);
    m[4] = 2 * (x * y + z * w), m[5] = 1 - 2 * (x * x + z * z), m[6] = 2 * (y * z - x * w);
    m[8] = 2 * (x * z - y * w), m[9] = 2 * (y * z + x * w), m[10] = 1 - 2 * (x * x + y * y);
}
// The camera at t between two views: position linearly, orientation by slerp. False when they are
// not one camera 1/60 s apart (farther than max_dist, or turned more than min_cos allows).
bool blend_view(const float* a, const float* b, float t, float max_dist, float min_cos, float* out) {
    float pa[3], pb[3];
    position(a, pa);
    position(b, pb);
    const float d = std::sqrt((pa[0] - pb[0]) * (pa[0] - pb[0]) + (pa[1] - pb[1]) * (pa[1] - pb[1]) + (pa[2] - pb[2]) * (pa[2] - pb[2]));
    float qa[4], qb[4];
    to_quat(a, qa);
    to_quat(b, qb);
    float dot = qa[0] * qb[0] + qa[1] * qb[1] + qa[2] * qb[2] + qa[3] * qb[3];
    if (dot < 0) {
        for (float& v : qb) v = -v;
        dot = -dot;
    }
    if (d > max_dist || dot < min_cos) return false;
    const float ang = std::acos(std::min(1.0f, dot));
    float wa = 1 - t, wb = t;
    if (ang > 1e-4f) {
        wa = std::sin((1 - t) * ang) / std::sin(ang);
        wb = std::sin(t * ang) / std::sin(ang);
    }
    float q[4], n = 0;
    for (int i = 0; i < 4; i++) {
        q[i] = wa * qa[i] + wb * qb[i];
        n += q[i] * q[i];
    }
    n = 1.0f / std::sqrt(n);
    for (float& v : q) v *= n;
    from_quat(q, out);
    float p[3];
    for (int i = 0; i < 3; i++) p[i] = (1 - t) * pa[i] + t * pb[i];
    for (int r = 0; r < 3; r++) out[r * 4 + 3] = -(out[r * 4 + 0] * p[0] + out[r * 4 + 1] * p[1] + out[r * 4 + 2] * p[2]);
    out[12] = out[13] = out[14] = 0, out[15] = 1;
    return true;
}

// A camera the logic frame's draws used, from its 0x150 block (view at 0x00, projection at 0x40,
// projection * view at 0x80) or 0x100 block (projection * view at 0x00, inverse view at 0x40)
struct Cam {
    float view[16], proj[16], pv[16], proj_inv[16];
};
bool camera_block(const float* f, size_t size, Cam& c) {
    if (size == 0x150 && f[12] == 0 && f[13] == 0 && f[14] == 0 && f[15] == 1 && rigid3(f, 4, 1)) {
        memcpy(c.view, f, sizeof c.view);
        memcpy(c.proj, f + 16, sizeof c.proj);
        mul44(c.proj, c.view, c.pv);
        if (!(std::fabs(c.pv[3] - f[35]) <= 1e-3f * (1 + std::fabs(c.pv[3])))) return false;
    } else if (size == 0x100 && rigid3(f + 16, 1, 4) && f[28] == 0 && f[29] == 0 && f[30] == 0 && f[31] == 0) {
        float iv[16];
        memcpy(iv, f + 16, 12 * sizeof(float));
        iv[12] = iv[13] = iv[14] = 0, iv[15] = 1;
        inv_rigid(iv, c.view);
        mul44(f, iv, c.proj);
        const float* p = c.proj;  // a perspective or orthographic projection
        if (!(std::fabs(p[12]) < 1e-3f && std::fabs(p[13]) < 1e-3f && (std::fabs(p[14] + 1) < 1e-3f || std::fabs(p[15] - 1) < 1e-3f)))
            return false;
        memcpy(c.pv, f, sizeof c.pv);
    } else {
        return false;
    }
    return inv44(c.proj, c.proj_inv);
}

bool g_used = false;  // an in-between frame was ever asked for (nothing runs before: 60 fps unchanged)
bool g_hold = false;
float g_t = 0.5f;
std::vector<Cam> g_seen, g_prev;  // cameras of the logic frame being drawn; of the last one
std::unordered_map<uint64_t, int> g_memo;  // (addr, size) -> index into g_out (-1: unchanged)
std::vector<std::vector<float>> g_out;
std::atomic<uint64_t> g_replaced{0}, g_replaced_regs{0};
uint64_t g_frame_no = 0;

float cam_cut() {
    static const float v = getenv("WWHD_FPS_CAMERA_CUT") ? (float)atof(getenv("WWHD_FPS_CAMERA_CUT")) : 600.0f;
    return v;
}
// One 4x4 window (16 floats) or 3x4 window (12 floats) of uniform data at the start of w: the logic
// step's camera there is replaced by the camera halfway from the previous logic frame's.
// Returns the floats consumed (16, 12) or 0.
int blend_window(const float* w, size_t avail, float* out) {
    if (avail >= 16 && std::fabs(w[12]) + std::fabs(w[13]) + std::fabs(w[14]) + std::fabs(w[15]) > 0) {
        for (const Cam& c : g_prev) {  // projection * view: projection^-1 * w is a rigid view near the old one
            float v[16];
            mul44(c.proj_inv, w, v);
            if (!(std::fabs(v[12]) < 1e-3f && std::fabs(v[13]) < 1e-3f && std::fabs(v[14]) < 1e-3f && std::fabs(v[15] - 1) < 1e-3f)) continue;
            if (!rigid3(v, 4, 1)) continue;
            float vm[16];
            if (!blend_view(c.view, v, g_t, cam_cut(), 0.966f, vm)) continue;
            mul44(c.proj, vm, out);
            return 16;
        }
    }
    if (avail >= 12 && rigid3(w, 4, 1)) {
        for (const Cam& c : g_prev) {  // a view: tight match only (model matrices are rigid too)
            float v[16], vm[16];
            memcpy(v, w, 12 * sizeof(float));
            v[12] = v[13] = v[14] = 0, v[15] = 1;
            if (!blend_view(c.view, v, g_t, std::min(cam_cut(), 150.0f), 0.99f, vm)) continue;
            memcpy(out, vm, 12 * sizeof(float));
            return 12;
        }
    }
    return 0;
}
// the windows of a run of uniform data (vec4 aligned); out gets a copy when something changed
bool blend_run(const float* f, size_t n, std::vector<float>& out) {
    bool changed = false;
    float tmp[16];
    for (size_t k = 0; k + 12 <= n;) {
        const int used = blend_window(f + k, n - k, tmp);
        if (!used) {
            k += 4;
            continue;
        }
        if (memcmp(tmp, f + k, used * sizeof(float))) {
            if (out.empty()) out.assign(f, f + n);
            memcpy(out.data() + k, tmp, used * sizeof(float));
            changed = true;
        }
        k += used;
    }
    return changed;
}

// WWHD_FPS_CAMERA_TRACE=from-to (render-thread frames): per frame, the camera of the logic frame
bool trace_on() {
    static uint64_t from = 0, to = 0;
    static const bool on = [] {
        const char* e = getenv("WWHD_FPS_CAMERA_TRACE");
        return e && sscanf(e, "%llu-%llu", (unsigned long long*)&from, (unsigned long long*)&to) == 2;
    }();
    return on && g_frame_no >= from && g_frame_no <= to;
}
}  // namespace

void begin_frame(bool hold, float t) {
    g_used = true;
    g_hold = hold && !g_prev.empty();
    g_t = t;
    g_memo.clear();
    g_out.clear();
}
void end_frame() {
    if (!g_used) return;
    if (trace_on()) {
        const float* v = g_hold ? nullptr : (!g_seen.empty() ? g_seen[0].view : nullptr);
        float p[3] = {0, 0, 0};
        if (v) position(v, p);
        LOG("[fps120 camera] frame %llu %s: %s %.3f %.3f %.3f, replaced so far: %llu blocks, %llu register runs", (unsigned long long)g_frame_no,
            g_hold ? "in-between" : "logic", v ? "camera at" : "-", p[0], p[1], p[2], (unsigned long long)g_replaced.load(),
            (unsigned long long)g_replaced_regs.load());
    }
    g_frame_no++;
    if (!g_hold && !g_seen.empty()) g_prev.swap(g_seen);
    g_seen.clear();
    g_hold = false;
    g_memo.clear();
    g_out.clear();
}
uint64_t replaced_blocks() { return g_replaced.load(std::memory_order_relaxed); }
uint64_t replaced_regs() { return g_replaced_regs.load(std::memory_order_relaxed); }
bool regs_active() { return g_hold; }

bool filter_regs(uint32_t first, const uint32_t* values, uint32_t n, std::vector<uint32_t>& out) {
    constexpr uint32_t kAlu = 0xC000;  // SQ_ALU_CONSTANT0_0: pixel, then vertex constants
    if (first < kAlu || first >= kAlu + 0x800 || n < 12) return false;
    const uint32_t skip = (4 - (first - kAlu) % 4) % 4;
    if (n <= skip) return false;
    std::vector<float> res;
    if (!blend_run((const float*)(values + skip), n - skip, res)) return false;
    out.assign(values, values + n);
    memcpy(out.data() + skip, res.data(), res.size() * sizeof(float));
    g_replaced_regs.fetch_add(1, std::memory_order_relaxed);
    return true;
}

const void* substitute(const void* bytes, size_t size, uint32_t guestAddr) {
    if (!g_used || !bytes || size < 48 || size > 0x2000) return nullptr;
    uint64_t key = (uint64_t)guestAddr << 16 | size;
    if (!guestAddr) {  // uniform registers gathered for a draw (shaders without uniform blocks): by content
        if (!g_hold) return nullptr;
        uint64_t h = 1469598103934665603ull;
        const uint8_t* b = (const uint8_t*)bytes;
        for (size_t i = 0; i < size; i++) h = (h ^ b[i]) * 1099511628211ull;
        key = h ^ (uint64_t)size << 1 | 1;  // odd: never a block key
    }
    if (!g_hold) {
        if ((size != 0x150 && size != 0x100) || g_memo.count(key)) return nullptr;
        g_memo[key] = -1;
        Cam c;
        float f[0x150 / 4];
        memcpy(f, bytes, size);
        if (camera_block(f, size, c)) {
            bool known = false;
            for (const Cam& s : g_seen) known = known || !memcmp(s.pv, c.pv, sizeof c.pv);
            if (!known && g_seen.size() < 32) g_seen.push_back(c);
        }
        return nullptr;
    }
    auto it = g_memo.find(key);
    if (it != g_memo.end()) return it->second >= 0 ? g_out[it->second].data() : nullptr;
    std::vector<float> out;
    int result = -1;
    if (blend_run((const float*)bytes, size / 4, out)) {
        g_out.push_back(std::move(out));
        result = (int)g_out.size() - 1;
        g_replaced.fetch_add(1, std::memory_order_relaxed);
    }
    g_memo[key] = result;
    return result >= 0 ? g_out[result].data() : nullptr;
}
}  // namespace gx2::interp_camera
