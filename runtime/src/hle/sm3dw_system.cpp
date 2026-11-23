// Super Mario 3D World: system calls that need more than an answer in r3 (docs/native-levels.md).
//   nn_fp    LoginAsync / RegisterAccountAsync answer through a callback the game waits for
//   gx2      GX2R buffers: allocated through the allocator the game registers
//   zlib125  the game's zlib, on the host's zlib with the guest z_stream mapped in and out
#include <zlib.h>

#include <mutex>
#include <unordered_map>

#include "../runtime.h"
#include "../write_watch.h"

uint32_t mem_default_alloc(uint32_t size, int32_t align);  // hle/coreinit_mem.cpp
void mem_default_free(uint32_t p);

static constexpr uint32_t kResultFail = 0xA0000000;  // nn::Result: bit 31 set = failure

// ---------------------------------------------------------------- nn_fp (friends)
// f_024F1CC4 calls LoginAsync(callback, userdata) and then waits on an event the callback signals: a login that
// never answers leaves that thread asleep for good. There is no network account, so the answer is a failure, delivered
// the way the real library does, from another thread.
HLE(nn_fp, LoginAsync__Q2_2nn2fpFPFQ2_2nn6ResultPv_vPv) {
    threads::post_guest_call(arg(c, 0), kResultFail, arg(c, 1));
    ret(c, 0);
}
HLE(nn_fp, RegisterAccountAsync__Q2_2nn2fpFPFQ2_2nn6ResultPv_vPv) {
    threads::post_guest_call(arg(c, 0), kResultFail, arg(c, 1));
    ret(c, 0);
}

// ---------------------------------------------------------------- GX2R (resource buffers)
// GX2RBuffer: +0 resource flags, +4 element size, +8 element count, +C pointer
namespace {
constexpr uint32_t kResAllocatedByGx2r = 1u << 29, kResLocked = 1u << 30;
uint32_t g_gx2r_alloc = 0, g_gx2r_free = 0;  // guest functions: void* alloc(flags, size, align), free(flags, ptr)

uint32_t gx2r_alignment(uint32_t flags) {
    if (flags & ((1u << 8) | (1u << 6) | (1u << 7) | (1u << 10))) return 0x100;  // stream out, uniform block, shader, GS ring
    if (flags & ((1u << 4) | (1u << 5) | (1u << 9))) return 0x40;                // vertex, index, display list
    return 0x100;
}
uint32_t gx2r_size(uint32_t buffer) { return ld32(buffer + 4) * ld32(buffer + 8); }
}  // namespace

HLE(gx2, GX2RSetAllocator) {
    g_gx2r_alloc = arg(c, 0);
    g_gx2r_free = arg(c, 1);
}
HLE(gx2, GX2RCreateBuffer) {
    const uint32_t buffer = arg(c, 0), flags = ld32(buffer);
    const uint32_t size = (gx2r_size(buffer) + 0x3F) & ~0x3Fu, align = gx2r_alignment(flags);
    // the game registers its allocator before it creates buffers; without one, the default heap
    const uint32_t ptr = g_gx2r_alloc ? guest_call(c, g_gx2r_alloc, {flags, size, align}) : mem_default_alloc(size, (int32_t)align);
    st32(buffer + 0xC, ptr);
    st32(buffer, (flags & ~kResLocked) | kResAllocatedByGx2r);
    ret(c, ptr != 0);
}
HLE(gx2, GX2RDestroyBufferEx) {
    const uint32_t buffer = arg(c, 0), flags = ld32(buffer), ptr = ld32(buffer + 0xC);
    if ((flags & kResAllocatedByGx2r) && ptr) {
        if (g_gx2r_free) guest_call(c, g_gx2r_free, {flags, ptr});
        else mem_default_free(ptr);
    }
    st32(buffer + 0xC, 0);  // a buffer the game gave its own memory for just lets go of it
}
HLE(gx2, GX2RLockBufferEx) { ret(c, ld32(arg(c, 0) + 0xC)); }
// unlocking says the CPU is done writing: what the GPU reads from here on is new (write_watch.h's buffer cache hint)
HLE(gx2, GX2RUnlockBufferEx) {
    const uint32_t ptr = ld32(arg(c, 0) + 0xC), size = gx2r_size(arg(c, 0));
    if (ptr && size) wwatch::hint(ptr, size);
}

// A tiling aperture lets the CPU read or write a GPU-tiled surface as if it were linear. The game asks for one around
// a screenshot-like readback; saying none is available makes it skip that.
HLE(gx2, GX2AllocateTilingApertureEx) {
    static bool said = false;
    if (!said) { said = true; LOG("[gx2] no tiling apertures (the game's surface readback is skipped)"); }
    ret(c, 0);
}
HLE(gx2, GX2FreeTilingAperture) {}

// ---------------------------------------------------------------- zlib125
// The guest z_stream (big endian, 0x38 bytes): +0 next_in, +4 avail_in, +8 total_in, +C next_out, +10 avail_out,
// +14 total_out, +18 msg, +1C state, +20 zalloc, +24 zfree, +28 opaque, +2C data_type, +30 adler. Each guest stream owns
// a host z_stream; the pointers in and out are guest addresses, which map straight onto host memory.
namespace {
std::mutex g_zmutex;
std::unordered_map<uint32_t, z_stream*> g_zstreams;  // guest z_stream address -> host stream

z_stream* zhost(uint32_t guest) {
    std::lock_guard<std::mutex> lk(g_zmutex);
    auto it = g_zstreams.find(guest);
    return it == g_zstreams.end() ? nullptr : it->second;
}
}  // namespace

HLE(zlib125, inflateInit2_) {  // (strm, windowBits, version, stream_size)
    const uint32_t guest = arg(c, 0);
    auto* z = new z_stream{};
    const int r = inflateInit2(z, (int)arg(c, 1));
    if (r != Z_OK) { delete z; ret(c, (uint32_t)r); return; }
    {
        std::lock_guard<std::mutex> lk(g_zmutex);
        if (auto old = g_zstreams.find(guest); old != g_zstreams.end()) { inflateEnd(old->second); delete old->second; }
        g_zstreams[guest] = z;
    }
    st32(guest + 0x08, 0); st32(guest + 0x14, 0); st32(guest + 0x18, 0);
    st32(guest + 0x1C, guest);  // `state` is non-null once initialized
    ret(c, Z_OK);
}
HLE(zlib125, inflate) {  // (strm, flush)
    const uint32_t g = arg(c, 0);
    z_stream* z = zhost(g);
    if (!z) { ret(c, (uint32_t)Z_STREAM_ERROR); return; }
    const uint32_t in = ld32(g + 0x00), avail_in = ld32(g + 0x04), out = ld32(g + 0x0C), avail_out = ld32(g + 0x10);
    z->next_in = avail_in ? (Bytef*)mem::ptr(in) : nullptr;
    z->avail_in = avail_in;
    z->next_out = avail_out ? (Bytef*)mem::ptr(out) : nullptr;
    z->avail_out = avail_out;
    const int r = inflate(z, (int)arg(c, 1));
    st32(g + 0x00, in + (avail_in - z->avail_in));
    st32(g + 0x04, z->avail_in);
    st32(g + 0x08, (uint32_t)z->total_in);
    st32(g + 0x0C, out + (avail_out - z->avail_out));
    st32(g + 0x10, z->avail_out);
    st32(g + 0x14, (uint32_t)z->total_out);
    st32(g + 0x2C, (uint32_t)z->data_type);
    st32(g + 0x30, (uint32_t)z->adler);
    ret(c, (uint32_t)r);
}
HLE(zlib125, inflateEnd) {
    z_stream* z = nullptr;
    {
        std::lock_guard<std::mutex> lk(g_zmutex);
        if (auto it = g_zstreams.find(arg(c, 0)); it != g_zstreams.end()) { z = it->second; g_zstreams.erase(it); }
    }
    if (!z) { ret(c, (uint32_t)Z_STREAM_ERROR); return; }
    const int r = inflateEnd(z);
    delete z;
    st32(arg(c, 0) + 0x1C, 0);
    ret(c, (uint32_t)r);
}
// compress / uncompress (dest, uLongf* destLen, source, sourceLen)
HLE(zlib125, compress) {
    uLongf len = ld32(arg(c, 1));
    const int r = compress((Bytef*)mem::ptr(arg(c, 0)), &len, (const Bytef*)mem::ptr(arg(c, 2)), arg(c, 3));
    st32(arg(c, 1), (uint32_t)len);
    ret(c, (uint32_t)r);
}
HLE(zlib125, uncompress) {
    uLongf len = ld32(arg(c, 1));
    const int r = uncompress((Bytef*)mem::ptr(arg(c, 0)), &len, (const Bytef*)mem::ptr(arg(c, 2)), arg(c, 3));
    st32(arg(c, 1), (uint32_t)len);
    ret(c, (uint32_t)r);
}
