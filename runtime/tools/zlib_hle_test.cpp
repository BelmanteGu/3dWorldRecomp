// The game's zlib (zlib125) on the host's: the guest z_stream is big endian in guest memory, the pointers in it are guest
// addresses. Inflates in small steps (input and output both chopped) and round-trips compress and uncompress, through the real
// HLE handlers of hle/sm3dw_system.cpp.
#include "runtime.h"
#include "write_watch.h"
#include <zlib.h>
#include <cassert>
#include <iostream>
#include <map>
#include <string>
#include <vector>
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
uint32_t guest_call(Cpu*, uint32_t, std::initializer_list<uint32_t>) { return 0; }
void threads::post_guest_call(uint32_t, uint32_t, uint32_t) {}
uint32_t mem_default_alloc(uint32_t, int32_t) { return 0; }
void mem_default_free(uint32_t) {}
int32_t call(const char* name, std::initializer_list<uint32_t> args) {
    Cpu c{}; size_t i = 3;
    for (auto a : args) c.r[i++] = a;
    functions().at(name)(&c);
    return int32_t(c.r[3]);
}

int main() {
    auto address = mem::ptr(base); constexpr size_t memory_size = 1 << 20;
#ifdef _WIN32
    auto memory = VirtualAlloc(address, memory_size, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
#else
    auto memory = mmap(address, memory_size, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
#endif
    assert(memory == address);
    std::string original;
    for (int i = 0; i < 5000; i++) original += "Super Mario 3D World " + std::to_string(i * 7919 % 1000) + ";";
    std::vector<uint8_t> packed(compressBound((uLong)original.size()));
    uLongf packed_size = (uLongf)packed.size();
    assert(compress(packed.data(), &packed_size, (const Bytef*)original.data(), (uLong)original.size()) == Z_OK);

    // ---- inflate through the guest z_stream: 100 bytes in, 700 bytes out at a time
    const uint32_t strm = base, in = base + 0x1000, out = base + 0x40000;
    memcpy(mem::ptr(in), packed.data(), packed_size);
    memset(mem::ptr(strm), 0xAA, 0x38);
    assert(call("zlib125:inflateInit2_", {strm, 15, 0, 0x38}) == Z_OK);
    assert(ld32(strm + 0x1C) != 0);                          // state is set once initialized
    uint32_t consumed = 0, produced = 0;
    int r = Z_OK;
    for (int step = 0; r == Z_OK && step < 100000; step++) {
        const uint32_t chunk = std::min<uint32_t>(100, (uint32_t)packed_size - consumed);
        st32(strm + 0x00, in + consumed); st32(strm + 0x04, chunk);
        st32(strm + 0x0C, out + produced); st32(strm + 0x10, 700);
        r = call("zlib125:inflate", {strm, Z_NO_FLUSH});
        assert(r == Z_OK || r == Z_STREAM_END || r == Z_BUF_ERROR);
        const uint32_t used_in = ld32(strm + 0x00) - (in + consumed), made = ld32(strm + 0x0C) - (out + produced);
        assert(used_in + ld32(strm + 0x04) == chunk && made + ld32(strm + 0x10) == 700);  // pointers and counts agree
        consumed += used_in; produced += made;
        assert(ld32(strm + 0x08) == consumed && ld32(strm + 0x14) == produced);           // total_in, total_out
        if (r == Z_BUF_ERROR) r = Z_OK;                       // no progress possible this step: feed on
    }
    assert(r == Z_STREAM_END && produced == original.size());
    assert(std::string((char*)mem::ptr(out), produced) == original);
    assert(call("zlib125:inflateEnd", {strm}) == Z_OK);
    assert(ld32(strm + 0x1C) == 0);
    assert(call("zlib125:inflate", {strm, Z_NO_FLUSH}) == Z_STREAM_ERROR);                 // a stream that was ended

    // ---- compress and uncompress with guest buffers and a guest length
    const uint32_t src = base + 0x80000, dst = base + 0xA0000, dst_len = base + 0x9F000, back = base + 0xC0000, back_len = base + 0x9F010;
    memcpy(mem::ptr(src), original.data(), original.size());
    st32(dst_len, 0x20000);
    assert(call("zlib125:compress", {dst, dst_len, src, (uint32_t)original.size()}) == Z_OK);
    assert(ld32(dst_len) > 0 && ld32(dst_len) < original.size());
    st32(back_len, 0x20000);
    assert(call("zlib125:uncompress", {back, back_len, dst, ld32(dst_len)}) == Z_OK);
    assert(ld32(back_len) == original.size() && std::string((char*)mem::ptr(back), original.size()) == original);
    st32(back_len, 100);                                                                    // too small an output buffer
    assert(call("zlib125:uncompress", {back, back_len, dst, ld32(dst_len)}) == Z_BUF_ERROR);
#ifdef _WIN32
    VirtualFree(memory, 0, MEM_RELEASE);
#else
    munmap(memory, memory_size);
#endif
    std::cout << "zlib_hle_test: ok\n";
    return 0;
}
