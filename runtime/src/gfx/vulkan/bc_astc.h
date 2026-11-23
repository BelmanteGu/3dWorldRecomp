#pragma once
// BC1 blocks transcoded to ASTC 4x4 (8 bits per texel) for GPUs that sample ASTC but not BC (the Qualcomm and Mali
// drivers): BC1 decoded to RGBA8 takes 32 bits per texel, 4x the memory. Idea after Fractal (a Cemu fork for
// Android, MPL-2.0), its LatteTextureTranscodeASTC.cpp consulted for the ASTC details; written from the Khronos Data
// Format Specification's ASTC chapter.
//
// Every ASTC block made here has one partition and a 4x4 weight grid (one weight per texel):
//  - four colour BC1 (c0 > c1): colour endpoint mode 8 (RGB direct), 8 bit endpoints (exact: the 565 colours expanded),
//    2 bit weights 0 / 21 / 43 / 64 of 64 for the BC indices c0 / 2:1 / 1:2 / c1 (BC's thirds, within 1 of 255);
//  - three colour BC1 without transparent texels: mode 8, weights 0 / 32 / 64 (c0, the midpoint, c1), exact;
//  - three colour BC1 with transparent texels: mode 12 (RGBA), two planes (alpha the second), 3 level weights and 7 bit
//    endpoints; a transparent texel takes the darker endpoint's colour with alpha 0 (BC1 gives black, alpha 0);
//  - one colour (with or without transparent texels) and all transparent: exact (a void-extent block, or mode 12
//    between transparent black and the colour).
#include <cstdint>
#include <cstring>
#include <array>
#include <utility>

namespace gfxvk::bc_astc {

using u128 = unsigned __int128;

// the five trits packed in one byte, inverted from the specification's trit decoding (index t0 + 3 t1 + 9 t2 + 27 t3 + 81 t4)
inline const std::array<uint8_t, 243>& trit_bytes() {
    static const std::array<uint8_t, 243> table = [] {
        std::array<uint8_t, 243> t{};
        std::array<bool, 243> seen{};
        for (uint32_t T = 0; T < 256; ++T) {
            uint32_t C, t4, t3, t2, t1, t0;
            if (((T >> 2) & 7) == 7) { C = ((T >> 5) << 2) | (T & 3); t4 = t3 = 2; }
            else {
                C = T & 31;
                if (((T >> 5) & 3) == 3) { t4 = 2; t3 = (T >> 7) & 1; }
                else { t4 = (T >> 7) & 1; t3 = (T >> 5) & 3; }
            }
            if ((C & 3) == 3) { t2 = 2; t1 = (C >> 4) & 1; t0 = (((C >> 3) & 1) << 1) | ((C >> 2) & 1 & ~(C >> 3) & 1); }
            else if (((C >> 2) & 3) == 3) { t2 = 2; t1 = 2; t0 = C & 3; }
            else { t2 = (C >> 4) & 1; t1 = (C >> 2) & 3; t0 = (((C >> 1) & 1) << 1) | (C & 1 & ~(C >> 1) & 1); }
            const uint32_t i = t0 + 3 * t1 + 9 * t2 + 27 * t3 + 81 * t4;
            if (i < 243 && !seen[i]) { seen[i] = true; t[i] = uint8_t(T); }
        }
        return t;
    }();
    return table;
}

inline uint32_t reverse32(uint32_t v) {
    v = ((v >> 1) & 0x55555555u) | ((v & 0x55555555u) << 1);
    v = ((v >> 2) & 0x33333333u) | ((v & 0x33333333u) << 2);
    v = ((v >> 4) & 0x0F0F0F0Fu) | ((v & 0x0F0F0F0Fu) << 4);
    v = ((v >> 8) & 0x00FF00FFu) | ((v & 0x00FF00FFu) << 8);
    return (v >> 16) | (v << 16);
}
inline uint64_t reverse64(uint64_t v) { return (uint64_t(reverse32(uint32_t(v))) << 32) | reverse32(uint32_t(v >> 32)); }

inline void store(uint8_t* out, u128 block) {
    const uint64_t lo = uint64_t(block), hi = uint64_t(block >> 64);
    std::memcpy(out, &lo, 8);
    std::memcpy(out + 8, &hi, 8);
}

// block modes, 4x4 weight grid (bits 3:2 = 0, A = 2, B = 0), weight range r in bits 1:0 and 4 (H = 0), D = dual plane
constexpr uint32_t kMode2BitWeights = 0x042;       // r = 4: 4 levels (2 bits)
constexpr uint32_t kMode3LevelWeights = 0x051;     // r = 3: 3 levels (trits)
constexpr uint32_t kMode3LevelDual = 0x451;        // the same, two planes

// a constant colour (void extent, LDR, no extent coordinates), 8 bit channels
inline void void_extent(uint32_t r, uint32_t g, uint32_t b, uint32_t a, uint8_t* out) {
    const uint64_t lo = 0xFFFFFFFFFFFFFDFCull;
    const uint64_t hi = uint64_t(r * 257) | (uint64_t(g * 257) << 16) | (uint64_t(b * 257) << 32) | (uint64_t(a * 257) << 48);
    std::memcpy(out, &lo, 8);
    std::memcpy(out + 8, &hi, 8);
}

struct Rgb { uint32_t r, g, b; uint32_t sum() const { return r + g + b; } bool operator==(const Rgb& o) const { return r == o.r && g == o.g && b == o.b; } };
inline Rgb expand565(uint32_t c) {
    const uint32_t r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
    return {(r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2)};
}

// count trits (0..2) as ISE with 0 extra bits: one byte per group of five, the tail cut to ceil(8 count / 5) bits
inline uint64_t trit_stream(const uint8_t* trits, uint32_t count) {
    const auto& T = trit_bytes();
    uint64_t s = 0;
    for (uint32_t g = 0; g * 5 < count; ++g) {
        uint32_t i = 0;
        for (uint32_t k = 5; k-- > 0;) i = i * 3 + (g * 5 + k < count ? trits[g * 5 + k] : 0);
        s |= uint64_t(T[i]) << (8 * g);
    }
    const uint32_t bits = (8 * count + 4) / 5;
    return bits >= 64 ? s : s & ((uint64_t(1) << bits) - 1);
}

// one BC1 block (8 bytes) to one ASTC 4x4 block (16 bytes)
inline void transcode_bc1(const uint8_t* bc, uint8_t* out) {
    const uint32_t c0 = bc[0] | (bc[1] << 8), c1 = bc[2] | (bc[3] << 8);
    uint32_t idx;
    std::memcpy(&idx, bc + 4, 4);
    Rgb e0 = expand565(c0), e1 = expand565(c1);
    if (c0 > c1) {
        // BC index 0 1 2 3 = c0, c1, 2/3 c0 + 1/3 c1, 1/3 c0 + 2/3 c1 -> weight 0, 3, 1, 2
        const uint32_t lo = idx & 0x55555555u, hi = (idx >> 1) & 0x55555555u;
        uint32_t w = (lo << 1) | (lo ^ hi);
        if (e1.sum() < e0.sum()) { std::swap(e0, e1); w = ~w; }  // the decoder wants the smaller RGB sum first
        const uint64_t colours = e0.r | (e1.r << 8) | (uint64_t(e0.g) << 16) | (uint64_t(e1.g) << 24) |
                                 (uint64_t(e0.b) << 32) | (uint64_t(e1.b) << 40);
        store(out, u128(kMode2BitWeights) | (u128(8) << 13) | (u128(colours) << 17) | (u128(reverse32(w)) << 96));
        return;
    }
    // three colour mode: c0, c1, the midpoint and transparent black
    uint32_t used = 0;
    for (uint32_t i = 0; i < 16; ++i) used |= 1u << ((idx >> (2 * i)) & 3);
    const uint32_t opaque = used & 7;
    if (!opaque) { void_extent(0, 0, 0, 0, out); return; }
    if (e0 == e1 || __builtin_popcount(opaque) == 1) {
        const uint32_t k = uint32_t(__builtin_ctz(opaque));
        const Rgb c = k == 0 ? e0 : k == 1 ? e1 : Rgb{(e0.r + e1.r) / 2, (e0.g + e1.g) / 2, (e0.b + e1.b) / 2};
        if (!(used & 8)) { void_extent(c.r, c.g, c.b, 255, out); return; }
        // RGBA between transparent black and (c, 255), 2 bit weights 0 or 3, exact (black has the smaller sum)
        uint32_t w = 0;
        for (uint32_t i = 0; i < 16; ++i) if (((idx >> (2 * i)) & 3) != 3) w |= 3u << (2 * i);
        const uint64_t colours = uint64_t(c.r) << 8 | uint64_t(c.g) << 24 | uint64_t(c.b) << 40 | uint64_t(255) << 56;
        store(out, u128(kMode2BitWeights) | (u128(12) << 13) | (u128(colours) << 17) | (u128(reverse32(w)) << 96));
        return;
    }
    const bool swap = e1.sum() < e0.sum();
    if (!(used & 8)) {
        // c0 / c1 / midpoint as weights 0 / 64 / 32: 16 trits (26 bits), 8 bit endpoints
        static constexpr uint8_t kWeight[4] = {0, 2, 1, 0};
        uint8_t trits[16];
        for (uint32_t i = 0; i < 16; ++i) { const uint8_t t = kWeight[(idx >> (2 * i)) & 3]; trits[i] = swap ? 2 - t : t; }
        const Rgb& a = swap ? e1 : e0;
        const Rgb& b = swap ? e0 : e1;
        const uint64_t colours = a.r | (b.r << 8) | (uint64_t(a.g) << 16) | (uint64_t(b.g) << 24) | (uint64_t(a.b) << 32) | (uint64_t(b.b) << 40);
        const uint64_t stream = trit_stream(trits, 16);  // 26 bits
        store(out, u128(kMode3LevelWeights) | (u128(8) << 13) | (u128(colours) << 17) | (u128(reverse64(stream)) << 64));
        return;
    }
    // two planes: RGB on the c0-c1 line (0, 1/2, 1) and alpha (0 or 1); 32 trits (52 bits), endpoints 7 bit (128 levels,
    // bit replication), the component selector (3: alpha) just below the weights, at bit 128 - 52 - 2 = 74
    auto q7 = [](uint32_t v) { return (v * 127 + 127) / 255; };  // nearest 7 bit level of an 8 bit value
    auto dq7 = [](uint32_t v) { return (v << 1) | (v >> 6); };
    uint32_t a[3] = {q7(e0.r), q7(e0.g), q7(e0.b)}, b[3] = {q7(e1.r), q7(e1.g), q7(e1.b)};
    const bool swapQ = dq7(b[0]) + dq7(b[1]) + dq7(b[2]) < dq7(a[0]) + dq7(a[1]) + dq7(a[2]);
    if (swapQ) std::swap(a, b);
    const uint32_t values[8] = {a[0], b[0], a[1], b[1], a[2], b[2], swapQ ? 127u : 0u, swapQ ? 0u : 127u};
    uint64_t colours = 0;
    for (uint32_t i = 0; i < 8; ++i) colours |= uint64_t(values[i]) << (7 * i);
    const uint8_t darker = e0.sum() <= e1.sum() ? 0 : 2;  // the transparent texels' colour (before the swap)
    uint8_t rgbW[4] = {0, 2, 1, darker}, alphaW[4] = {2, 2, 2, 0};
    if (swapQ) for (uint32_t k = 0; k < 4; ++k) { rgbW[k] = 2 - rgbW[k]; alphaW[k] = 2 - alphaW[k]; }
    uint8_t trits[32];
    for (uint32_t i = 0; i < 16; ++i) { const uint32_t k = (idx >> (2 * i)) & 3; trits[2 * i] = rgbW[k]; trits[2 * i + 1] = alphaW[k]; }
    const uint64_t stream = trit_stream(trits, 32);  // 52 bits
    store(out, u128(kMode3LevelDual) | (u128(12) << 13) | (u128(colours) << 17) | (u128(3) << 74) | (u128(reverse64(stream)) << 64));
}

}  // namespace gfxvk::bc_astc
