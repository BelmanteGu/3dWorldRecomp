// The texture budget's choice and policy (gfx/vulkan/texture_budget.h): least recently used first down to 90 %,
// pinning, idle release; off on the desktop unless asked, on by default on Android (300-frame sweeps, budget
// max(RAM / 10, 384 MB), idle 7200 frames, 300 while memory is tight), WWHD_VK_TEXTURE_BUDGET=0/1 overrides.
#include "gfx/vulkan/texture_budget.h"
#include <cassert>
#include <cstdio>
using namespace gfxvk::texture_budget;
int main() {
    // over a 100-byte budget: oldest first down to 90, the pinned (last 3 frames) never
    std::vector<Candidate> all{{10, 40, 0}, {5, 40, 1}, {99, 40, 2}, {50, 40, 3}};
    auto out = select(all, 160, 100, 100, 0, 3);
    assert(out.size() == 2 && out[0] == 1 && out[1] == 0);
    // idle release only (no budget)
    out = select(all, 160, 100, 0, 60, 3);
    assert(out.size() == 2 && out[0] == 1 && out[1] == 0);
    // desktop: off unless WWHD_VK_TEXTURE_BUDGET=1 (a quarter of the device budget, every 30 frames)
    assert(!policy(false, nullptr, 4096, 0, -1).on);
    Policy d = policy(false, "1", 4096, 0, -1);
    assert(d.on && d.budget == 1024 && d.every == 30 && d.pin == 3 && d.idle == 0);
    // Android: on by default
    const uint64_t gb = 1ull << 30;
    Policy a = policy(true, nullptr, 0, 8 * gb, -1);
    assert(a.on && a.every == 300 && a.pin == kAndroidPin && a.idle == kAndroidIdle && a.budget == 8 * gb / 10);
    assert(policy(true, nullptr, 0, 2 * gb, 2000).budget == kAndroidMinBudget);  // 2 GB: the 384 MB floor
    assert(policy(true, nullptr, 0, 8 * gb, 500).idle == kAndroidPin);           // tight: every 300-frame-idle one
    assert(policy(true, nullptr, 0, 8 * gb, 800).idle == kAndroidIdle);
    assert(!policy(true, "0", 0, 8 * gb, -1).on);                                // turned off
    assert(policy(true, "1", 4096, 8 * gb, -1).budget == 1024);                  // forced on: the desktop rule
    std::puts("texture_budget: ok");
}
