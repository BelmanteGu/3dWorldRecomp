// Pre-translated shaders ("shader pack"): everything translate() gets out of the Latte decompiler and
// glslang for a shader, keyed by what the game hands in (program bytes, fetch shader, linkage registers),
// so a device that has the pack never runs the decompiler for a shader the pack covers.
//
// The existing SPIR-V cache (shaders.cpp, spirv.bin) is keyed by the GLSL text, so it still needs the
// decompiler to get the key, and it rebuilds the binding metadata every time. The pack is keyed by the
// input instead (the same hashes translate() already computes) and stores the metadata the renderer reads
// from the decompiler, the SPIR-V (zlib) and the identity hashes that stand in for the GLSL text.
//
// Keys are content hashes, the same on every machine for the same game dump, so a pack baked on a PC
// serves a phone. The translator itself is not part of the key: bump kTranslatorVersion (shader_pack.cpp)
// when the decompiler or what translate() adds to its output changes, and check a pack against the
// current translator with WWHD_VK_SHADER_PACK_VERIFY=1 (tools/sm3dw_shader_pack.sh verify).
//
// WWHD_VK_SHADER_PACK: 0 = off, a path = that file (read and written), unset = the default places.
// WWHD_VK_SHADER_PACK_STRICT=1: a shader the pack lacks is an error (the draw is skipped, logged), not a
// decompiler run; for builds without the decompiler and to measure coverage.
#pragma once
#include "shaders.h"
#include <array>
#include <memory>
#include <vector>

namespace gfxvk::vk::pack {

struct UseRecord {
    uint8_t unitCount = 0;
    std::array<uint8_t, LATTE_NUM_MAX_TEX_UNITS> units{};
    uint32_t exports = 0;
    bool streamout = false;
};

// The part of a translated shader the renderer reads, immutable once published.
struct ShaderRecord {
    bool vertex = false;
    LatteDecompilerShaderResourceMapping mapping;
    DescriptorRankPlan ranks;
    LatteDecompilerOutputUniformOffsets uniforms;
    // what the renderer reads from LatteDecompilerShader
    uint32_t outputParameterMask = 0, pixelColorOutputMask = 0;
    uint8_t unitCount = 0;
    std::array<uint8_t, LATTE_NUM_MAX_TEX_UNITS> units{};
    std::array<uint16_t, LATTE_NUM_MAX_TEX_UNITS> sampler{};
    std::array<uint8_t, LATTE_NUM_MAX_TEX_UNITS> depthCompare{};
    bool streamout = false;
    std::vector<std::array<uint32_t, 2>> registerEntries;  // indexOffset, mappedIndexOffset
    struct Group {
        uint16_t bufferId = 0, kcacheBankIdOffset = 0;
        std::vector<std::array<uint16_t, 2>> entries;      // indexOffset, mappedIndexOffset
    };
    std::vector<Group> groups;
    // stand-ins for the GLSL text: Shader::outputHash (glsl + binding metadata) and Shader::pipelineId
    uint64_t outputHash = 0, pipelineId = 0;
    uint64_t spirvHash = 0;
};

// the translator configuration the pack's SPIR-V depends on (shaderpack-<this>.bin, <this>-*.recipes)
uint64_t translator_fingerprint();
bool active();   // reading and recording (off with WWHD_VK_SHADER_PACK=0 and with Cemu graphic packs)
bool strict();
bool verify();

const UseRecord* find_use(uint64_t linkage);
std::shared_ptr<const ShaderRecord> find_shader(uint64_t key);
bool inflate_spirv(const ShaderRecord& record, std::vector<uint32_t>& words);

// the renderer-visible part of a ready shader (translated or from the pack)
std::shared_ptr<ShaderRecord> record_of(const Shader& shader);
// fills the metadata of an empty Shader (dec included) from the record; not the SPIR-V
void apply(Shader& shader, const ShaderRecord& record);
bool same(const ShaderRecord& a, const ShaderRecord& b);

// recording: uses at once, shaders once their SPIR-V is there (flush)
void note_use(uint64_t linkage, const UseRecord& use);
void track(uint64_t key, const Shader* shader);
void forget_tracked();
void flush(uint64_t frame);
void checkpoint(uint64_t frame);
bool save(bool wait);

struct Counts {
    size_t uses = 0, shaders = 0, blobs = 0, baselineShaders = 0;
    uint64_t hits = 0, misses = 0, recorded = 0, verified = 0, mismatched = 0;
};
Counts counts();
void count_hit();
void count_miss();
void count_verified(bool matched);
void report(const char* when);

}  // namespace gfxvk::vk::pack
