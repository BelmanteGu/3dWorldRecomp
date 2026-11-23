// The block heap and frame heap calls of Super Mario 3D World (sead's main heap sits on a block heap): the real HLE handlers of
// hle/coreinit_mem.cpp over a small guest-memory window.
#include "runtime.h"
#include "write_watch.h"
#include <cassert>
#include <iostream>
#include <map>
#ifdef _WIN32
#include <windows.h>
#else
#include <sys/mman.h>
#endif
namespace {std::map<std::string, PpcFunc>& functions() { static std::map<std::string, PpcFunc> f; return f; } constexpr uint32_t base = 0x10000000;}
HleReg::HleReg(const char* lib, const char* name, PpcFunc fn) { functions()[std::string(lib) + ":" + name] = fn; }
bool g_trace_hle = false;
void log_msg(const char*, ...) {}
namespace wwatch { void hint(uint32_t, uint32_t) {} }
namespace dispatch { uint32_t register_host(PpcFunc, const char*) { return 0; } }
int32_t call(const char* name, std::initializer_list<uint32_t> args) {
    Cpu c{}; size_t i = 3;
    for (auto a : args) c.r[i++] = a;
    functions().at(name)(&c);
    return int32_t(c.r[3]);
}
uint32_t ucall(const char* name, std::initializer_list<uint32_t> args) { return (uint32_t)call(name, args); }

int main() {
    auto address = mem::ptr(base); constexpr size_t memory_size = 1 << 20;
#ifdef _WIN32
    auto memory = VirtualAlloc(address, memory_size, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
#else
    auto memory = mmap(address, memory_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
#endif
    assert(memory == address);
    // ---- block heap: header at base, memory [base + 0x1000, base + 0x11000)
    const uint32_t hdr = base, start = base + 0x1000, end = base + 0x11000;
    assert(ucall("coreinit:MEMInitBlockHeap", {0, start, end, 0, 0, 0}) == 0);      // no header: refused
    assert(ucall("coreinit:MEMInitBlockHeap", {hdr, end, start, 0, 0, 0}) == 0);    // empty range: refused
    assert(ucall("coreinit:MEMInitBlockHeap", {hdr, start, end, 0, 0, 0}) == hdr);  // the handle is the header
    const uint32_t a = ucall("coreinit:MEMAllocFromBlockHeapEx", {hdr, 0x100, 0});    // alignment 0 means 4
    const uint32_t b = ucall("coreinit:MEMAllocFromBlockHeapEx", {hdr, 0x200, 0x40});
    assert(a >= start && a + 0x100 <= end && b >= start && b + 0x200 <= end && a != b);
    assert(b % 0x40 == 0 && (a + 0x100 <= b || b + 0x200 <= a));                    // aligned, not overlapping
    const uint32_t top = ucall("coreinit:MEMAllocFromBlockHeapEx", {hdr, 0x80, (uint32_t)-0x10});  // negative: from the end
    assert(top % 0x10 == 0 && top + 0x80 <= end && top > b && top > a);
    assert(ucall("coreinit:MEMAllocFromBlockHeapEx", {hdr, 0x100000, 4}) == 0);     // more than there is
    assert(ucall("coreinit:MEMAllocFromBlockHeapEx", {base + 0x500, 16, 4}) == 0);  // not a heap
    call("coreinit:MEMFreeToBlockHeap", {hdr, a});
    assert(ucall("coreinit:MEMAllocFromBlockHeapEx", {hdr, 0x100, 4}) == a);        // the freed block comes back
    assert(call("coreinit:MEMAddBlockHeapTracking", {hdr, 0, 0}) == 0);
    assert(call("coreinit:MEMGetTrackingLeftInBlockHeap", {hdr}) > 0);
    // a second init of the same header starts over
    assert(ucall("coreinit:MEMInitBlockHeap", {hdr, start, end, 0, 0, 0}) == hdr);
    assert(ucall("coreinit:MEMAllocFromBlockHeapEx", {hdr, 0x100, 4}) == start);

    // ---- frame heap: allocations from the head and the tail, MEMFreeToFrmHeap gives each side back
    const uint32_t frm = base + 0x20000;
    assert(ucall("coreinit:MEMCreateFrmHeapEx", {frm, 0x1000, 0}) == frm);
    const uint32_t h1 = ucall("coreinit:MEMAllocFromFrmHeapEx", {frm, 0x100, 4});
    const uint32_t t1 = ucall("coreinit:MEMAllocFromFrmHeapEx", {frm, 0x100, (uint32_t)-4});
    assert(h1 && t1 && h1 < t1);
    call("coreinit:MEMFreeToFrmHeap", {frm, 1});                                     // head only
    assert(ucall("coreinit:MEMAllocFromFrmHeapEx", {frm, 0x100, 4}) == h1);        // the head starts over
    assert(ucall("coreinit:MEMAllocFromFrmHeapEx", {frm, 0x100, (uint32_t)-4}) < t1);  // the tail still holds its block
    call("coreinit:MEMFreeToFrmHeap", {frm, 2});                                     // tail only
    assert(ucall("coreinit:MEMAllocFromFrmHeapEx", {frm, 0x100, (uint32_t)-4}) == t1);
    call("coreinit:MEMFreeToFrmHeap", {frm, 3});                                     // both
    assert(ucall("coreinit:MEMAllocFromFrmHeapEx", {frm, 0x100, 4}) == h1);
    call("coreinit:MEMFreeToFrmHeap", {base + 0x30000, 3});                          // not a heap: nothing happens
#ifdef _WIN32
    VirtualFree(memory, 0, MEM_RELEASE);
#else
    munmap(memory, memory_size);
#endif
    std::cout << "mem_heaps_test: ok\n";
    return 0;
}
