#include "bench.h"
#include "frame_causes.h"
#include "gfx/renderer.h"
#include "perf_metrics.h"
#include "render_prof.h"
#include "runtime.h"
#include "screenshot.h"
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <mutex>
#include <string>
#include <vector>
namespace gx2 { int present_divisor(); void set_uncapped(bool on); }  // gx2/gx2.h

namespace bench {
uint32_t g_draws = 0;
namespace {
enum Phase { kMenus, kStageLoading, kSettling, kMeasuring, kFinished };
std::string g_mode, g_stage = "Circus", g_loaded_stage;
std::atomic<bool> g_done{false};
std::atomic<int> g_phase{kMenus};
std::atomic<bool> g_stage_file{false};
uint64_t g_frames = 1800, g_settle = 600, g_first_a = 600, g_a_every = 100, g_live_start = 600;
uint64_t g_busy_run = 0, g_menu_run = 0, g_load_frame = 0;
std::atomic<bool> g_map_up{false};        // consecutive frames with a stage-sized draw count
std::atomic<uint64_t> g_play_start{0};  // frame at which the route starts
std::string g_out = "bench_out";
struct Row { uint64_t frame; double dt, rcpu; uint32_t draws; int phase; frame_causes::Sample causes; };
bool g_all = false;  // WWHD_BENCH_ALL=1: every frame from the first one (title, world map, loading, stage)
std::vector<Row> rows;
std::mutex gpuMutex;
std::map<uint64_t, double> gpu;  // frame id -> GPU interval ms
uint64_t g_extra = 0;
std::mutex nameMutex;

const char* env(const char* k) { const char* e = getenv(k); return e && *e ? e : nullptr; }
uint64_t env_u(const char* k, uint64_t d) { const char* e = env(k); return e ? strtoull(e, nullptr, 10) : d; }
void set_default(const char* k, const std::string& v) { setenv(k, v.c_str(), 0); }

// The route (frames from the start of the play phase): run (hold B), stick forward with turns, a jump (A)
// every 70 frames. Deterministic by frame count; stage layout decides where Mario ends up.
struct Leg { int len; float x, y; };
const Leg kRoute[] = {{240, 0, 1}, {60, 0.7f, 0.7f}, {180, 0, 1}, {60, -0.7f, 0.7f}, {120, 0, 1}, {90, -0.5f, 0.85f}};
}  // namespace

bool active() { return !g_mode.empty(); }
bool done() { return g_done.load(std::memory_order_relaxed); }

void init() {
    const char* m = env("WWHD_BENCH");
    if (!m) return;
    g_mode = m;
    g_frames = env_u("WWHD_BENCH_FRAMES", 1800);
    g_settle = env_u("WWHD_BENCH_SETTLE", 600);
    g_first_a = env_u("WWHD_BENCH_FIRST_A", 600);
    g_a_every = env_u("WWHD_BENCH_A_EVERY", 100);
    g_live_start = env_u("WWHD_BENCH_START", 600);
    if (env("WWHD_BENCH_STAGE")) g_stage = getenv("WWHD_BENCH_STAGE");
    if (env("WWHD_BENCH_OUT")) g_out = getenv("WWHD_BENCH_OUT");
    g_all = env("WWHD_BENCH_ALL") && strcmp(getenv("WWHD_BENCH_ALL"), "0");
    if (g_mode == "live") g_phase = kMeasuring;
    // measurement settings: no frame limit unless asked, immediate presentation, no human input
    if (!env("WWHD_BENCH_CAPPED")) set_default("WWHD_UNCAPPED", "1");
    set_default("WWHD_VK_PRESENT_MODE", "immediate");
    set_default("WWHD_NO_HOST_INPUT", "1");
    set_default("WWHD_PERF_OVERLAY", "0");
    if (env("WWHD_BENCH_SHOTS")) set_default("WWHD_SCREENSHOT_DIR", g_out);
    perf::force_demand(true);
    perf::set_gpu_sink(&gpu_sample);
    LOG("[bench] mode %s, stage \"%s\": measuring %llu frames, out %s", g_mode.c_str(), g_stage.c_str(),
        (unsigned long long)g_frames, g_out.c_str());
}

void note_file_open(const char* p) {
    if (!active() || g_phase.load() != kMenus || !p) return;
    const char* s = strstr(p, "StageData/");
    if (!s) return;
    static const bool all = getenv("WWHD_BENCH_LOG_FILES") != nullptr;
    if (all) LOG("[bench] open %s", p);
    if (!strstr(s, "Map1.szs")) return;
    // the stage's own map archive (the world map is CourseSelect*)
    if (strstr(s, "CourseSelect") || strstr(s, "WorldMap")) return;
    {
        std::lock_guard<std::mutex> lk(nameMutex);
        g_loaded_stage = s + 10;
    }
    LOG("[bench] the game loads %s", s);
    if (strstr(s, g_stage.c_str())) {
        // stage tour: the menus ran uncapped (fast); the stage plays in real time, so its load, title card and
        // route take the time they take for a player (WWHD_BENCH_CAP_STAGE=1)
        if (env("WWHD_BENCH_CAP_STAGE") && strcmp(getenv("WWHD_BENCH_CAP_STAGE"), "0")) gx2::set_uncapped(false);
        g_phase = kStageLoading;
        g_busy_run = 0;
        g_load_frame = render::frame_count();
    }
}

std::string host_override(const std::string& guest) {
    static const std::string stageList = [] {
        const char* e = env("WWHD_BENCH_STAGELIST");
        if (e) LOG("[bench] the stage list is read from %s", e);
        return std::string(e ? e : "");
    }();
    static const std::string name = "SystemData/StageList.szs";
    if (stageList.empty() || guest.size() < name.size() ||
        guest.compare(guest.size() - name.size(), name.size(), name)) return {};
    return stageList;
}

bool pad(uint64_t frame, uint32_t& buttons, float& lx, float& ly) {
    if (!active() || g_mode == "live") return false;
    const int ph = g_phase.load();
    if (ph == kMenus || ph == kStageLoading) {
        // A pulses (title, file, character, world map, level confirmation)
        if (ph == kMenus && frame >= g_first_a) {
            const uint64_t o = (frame - g_first_a) % g_a_every;
            if (g_map_up.load()) {
                // world map: walk forward onto the stage node, then A (Mario starts in front of it)
                if (o < 40) { lx = 0; ly = 1; }
                else if (o >= 60 && o < 65) buttons |= 0x8000;
            } else if (o < 5) buttons |= 0x8000;
        }
        // the stage's title card waits for a button ("press a button to join")
        if (ph == kStageLoading && frame >= g_first_a && (frame - g_first_a) % g_a_every < 5) buttons |= 0x8000;
        return true;
    }
    const uint64_t t = frame - g_play_start.load();
    if (frame < g_play_start.load()) return true;
    int total = 0;
    for (auto& l : kRoute) total += l.len;
    int o = int(t % total);
    for (auto& l : kRoute) {
        if (o < l.len) { lx = l.x; ly = l.y; break; }
        o -= l.len;
    }
    buttons |= 0x4000;                        // run
    if (t % 70 < 12) buttons |= 0x8000;       // jump
    return true;
}

void gpu_sample(uint64_t frame, double ms) {
    std::lock_guard<std::mutex> lk(gpuMutex);
    gpu[frame] = ms;
}

static double pct(std::vector<double> v, double p) {
    if (v.empty()) return 0;
    std::sort(v.begin(), v.end());
    return v[std::min(v.size() - 1, size_t(p / 100.0 * v.size()))];
}

static void finish() {
    if (!g_all && rows.size() > g_frames) rows.resize(g_frames);
    std::string dir = g_out;
    std::string cmd = "mkdir -p '" + dir + "'";
    if (system(cmd.c_str())) {}
    std::lock_guard<std::mutex> lk(gpuMutex);
    FILE* f = fopen((dir + "/bench.csv").c_str(), "w");
    std::vector<double> dt, gp, rc, dr;
    unsigned gpuHits = 0;
    static const char* const kPhaseNames[] = {"menus", "loading", "settling", "measuring", "finished"};
    if (f) {
        fprintf(f, "frame,phase,dt_ms,gpu_ms,render_cpu_ms,draws");
        for (int c = 0; c < frame_causes::kCount; ++c) fprintf(f, ",%s_ms", frame_causes::name(c));
        for (int c = 0; c < frame_causes::kCounters; ++c) fprintf(f, ",%s", frame_causes::counter_name(c));
        fprintf(f, "\n");
    }
    for (auto& r : rows) {
        double g = -1;
        auto it = gpu.find(r.frame);
        if (it == gpu.end()) it = gpu.find(r.frame - 1);
        if (it != gpu.end()) { g = it->second; ++gpuHits; gp.push_back(g); }
        dt.push_back(r.dt); rc.push_back(r.rcpu); dr.push_back(r.draws);
        if (f) {
            fprintf(f, "%llu,%s,%.3f,%.3f,%.3f,%u", (unsigned long long)r.frame, kPhaseNames[r.phase], r.dt, g, r.rcpu, r.draws);
            for (int c = 0; c < frame_causes::kCount; ++c) fprintf(f, ",%.3f", r.causes.ns[c] / 1e6);
            for (int c = 0; c < frame_causes::kCounters; ++c) fprintf(f, ",%u", r.causes.counts[c]);
            fprintf(f, "\n");
        }
    }
    if (f) fclose(f);
    double total = 0;
    for (double d : dt) total += d;
    char line[512];
    std::string sum;
    auto add = [&](const char* fmt, auto... a) { snprintf(line, sizeof line, fmt, a...); sum += line; };
    {
        std::lock_guard<std::mutex> nl(nameMutex);
        add("mode=%s stage=%s frames=%zu res_scale=%.3f\n", g_mode.c_str(), g_loaded_stage.empty() ? "-" : g_loaded_stage.c_str(), rows.size(),
            double(render::res_scale()));
    }
    add("fps_avg=%.2f (frame time mean %.3f ms)\n", total > 0 ? 1000.0 * rows.size() / total : 0.0, rows.empty() ? 0.0 : total / rows.size());
    add("frame_ms p50=%.3f p95=%.3f p99=%.3f max=%.3f\n", pct(dt, 50), pct(dt, 95), pct(dt, 99), pct(dt, 100));
    add("gpu_ms   p50=%.3f p95=%.3f p99=%.3f (%u/%zu frames with a GPU sample, %zu samples total)\n", pct(gp, 50), pct(gp, 95), pct(gp, 99),
        gpuHits, rows.size(), gpu.size());
    add("render_cpu_ms p50=%.3f p95=%.3f p99=%.3f\n", pct(rc, 50), pct(rc, 95), pct(rc, 99));
    add("draws   p50=%.0f p95=%.0f max=%.0f\n", pct(dr, 50), pct(dr, 95), pct(dr, 100));
    double over8 = 0, over16 = 0;
    for (double d : dt) { over8 += d > 8.5; over16 += d > 17.2; }
    add("frames_over_8.33ms=%.1f%% frames_over_16.67ms=%.1f%%\n", rows.empty() ? 0 : 100 * over8 / rows.size(), rows.empty() ? 0 : 100 * over16 / rows.size());
    {   // stutter: long frames and what they spent their time on (docs/stutter.md)
        unsigned over10 = 0, over16b = 0, over33 = 0;
        uint64_t skipped = 0, shaders = 0, pipes = 0, warm = 0;
        std::map<std::string, unsigned> blame;
        for (auto& r : rows) {
            skipped += r.causes.counts[frame_causes::kSkippedDraws];
            shaders += r.causes.counts[frame_causes::kNewShaders];
            pipes += r.causes.counts[frame_causes::kNewPipelines];
            warm += r.causes.counts[frame_causes::kWarmHits];
            if (r.dt <= 10.0) continue;
            ++over10; over16b += r.dt > 16.0; over33 += r.dt > 33.0;
            int best = -1; double bestMs = 0;
            for (int c = 0; c < frame_causes::kCount; ++c) {
                if (c == frame_causes::kFileIo) continue;  // other threads: not part of this frame's time
                const double ms = r.causes.ns[c] / 1e6;
                if (ms > bestMs) { bestMs = ms; best = c; }
            }
            blame[best >= 0 && bestMs > 0.3 * r.dt ? frame_causes::name(best) : "other"]++;
        }
        add("frame_ms p99.9=%.3f; frames over 10 ms: %u, over 16 ms: %u, over 33 ms: %u (of %zu)\n",
            pct(dt, 99.9), over10, over16b, over33, rows.size());
        std::string causes;
        for (auto& [k, v] : blame) { causes += " " + k + "=" + std::to_string(v); }
        add("frames over 10 ms by main cause:%s\n", causes.empty() ? " -" : causes.c_str());
        add("skipped draws %llu, new shaders %llu, new pipelines %llu (warm-up hits %llu)\n", (unsigned long long)skipped,
            (unsigned long long)shaders, (unsigned long long)pipes, (unsigned long long)warm);
    }
    if (FILE* s = fopen((dir + "/summary.txt").c_str(), "w")) { fputs(sum.c_str(), s); fclose(s); }
    LOG("[bench] done\n%s", sum.c_str());
    fputs(sum.c_str(), stdout);
    fflush(stdout);
}

void frame_end() {
    using clk = std::chrono::steady_clock;
    static clk::time_point last{};
    static uint64_t lastCpu = 0;
    const auto now = clk::now();
    const uint64_t cpu = rprof::thread_cpu_ns();
    const uint32_t draws = g_draws;
    g_draws = 0;
    const uint64_t frame = render::frame_count();
    int ph = g_phase.load();
    // the stage has loaded when the frames carry a stage-sized number of draws again (a loading screen
    // is ~12 draws); then the route starts and, after the settle time, the measurement
    // 30 fps / frame skip (docs/30fps.md): a frame left out draws nothing; it neither counts nor breaks a run
    const bool unpresented = draws == 0 && gx2::present_divisor() > 1;
    if (unpresented) {
    } else if (ph == kMenus) {
        g_menu_run = draws >= 100 ? g_menu_run + 1 : 0;
        if (g_menu_run >= 900 && !g_map_up.exchange(true)) LOG("[bench] a busy screen (the world map) at frame %llu", (unsigned long long)frame);
    }
    if (ph == kStageLoading && unpresented) {
    } else if (ph == kStageLoading) {
        g_busy_run = draws >= env_u("WWHD_BENCH_STAGE_DRAWS", 200) ? g_busy_run + 1 : 0;
        // the stage's title card also draws a lot: give the load time before trusting the draw count
        // a course that never draws that much (a small boss room, a cut scene) starts after WWHD_BENCH_LOAD_MAX frames
        if ((g_busy_run >= 120 && frame >= g_load_frame + env_u("WWHD_BENCH_LOAD_MIN", 300)) ||
            frame >= g_load_frame + env_u("WWHD_BENCH_LOAD_MAX", 3000)) {
            g_play_start = frame;
            g_phase = ph = kSettling;
            LOG("[bench] stage on screen at frame %llu", (unsigned long long)frame);
        }
    } else if (ph == kSettling && frame >= g_play_start.load() + g_settle) {
        g_phase = ph = kMeasuring;
        LOG("[bench] measuring from frame %llu", (unsigned long long)frame);
    } else if (ph == kMenus && frame > env_u("WWHD_BENCH_GIVE_UP", 60000)) {
        LOG("[bench] stage \"%s\" never loaded (last: %s)", g_stage.c_str(), g_loaded_stage.c_str());
        g_phase = ph = kMeasuring;  // measure whatever is there so the run ends with data
    }
    if (ph == kMeasuring && g_mode == "live" && frame < g_live_start) ph = kSettling;
    if (ph == kMeasuring && env("WWHD_BENCH_SHOTS") && (rows.size() == 5 || rows.size() == g_frames / 2)) screenshot::request();
    const frame_causes::Sample causes = frame_causes::take();
    if (last != clk::time_point{} && (ph == kMeasuring || (g_all && ph != kFinished))) {
        Row r;
        r.phase = ph;
        r.causes = causes;
        r.frame = frame;
        r.dt = std::chrono::duration<double, std::milli>(now - last).count();
        r.rcpu = double(cpu - lastCpu) / 1e6;
        r.draws = draws;
        rows.push_back(r);
        static size_t measured = 0;
        measured += ph == kMeasuring;
        if (measured >= g_frames + 8) {  // +8: the last frames' GPU samples arrive late
            g_phase = kFinished;
            finish();
            g_done = true;
        }
    }
    last = now;
    lastCpu = cpu;
}
}  // namespace bench
