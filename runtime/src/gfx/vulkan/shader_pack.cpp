#include "shader_pack.h"
#include "atomic_temporary.h"
#include "mods/cemu_pack.h"
#include <zlib.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <future>
#include <unordered_map>
#ifdef __ANDROID__
#include <SDL3/SDL_iostream.h>  // SDL_LoadFile: the pack in the APK's assets
#include <SDL3/SDL_stdinc.h>
#endif

void log_msg(const char* fmt, ...);  // runtime.h (not included here: the Cemu headers)
namespace config { extern std::string game_dir; }  // runtime.h

namespace gfxvk::vk::pack {
namespace {

// Bump when the decompiler, what translate() adds to its output (area-sample fixes, the invariant
// position, descriptor ranks) or the metadata kept here changes: an older pack is then ignored.
constexpr uint32_t kTranslatorVersion = 3;  // 3: fetch shader identity = hash of its attributes (instance divisor only for instance data)
constexpr char kMagic[8] = {'W', 'W', 'S', 'P', '0', '0', '0', '1'};
constexpr uint64_t kMaxFileBytes = 1ull << 30;
constexpr uint32_t kMaxCount = 1u << 20;
constexpr uint32_t kMaxEntries = 1u << 16;

uint64_t fast_hash(const void* bytes, size_t size, uint64_t hash = 0x9E3779B97F4A7C15ull) {
    const auto* p = static_cast<const uint8_t*>(bytes);
    size_t i = 0;
    for (; i + 8 <= size; i += 8) {
        uint64_t word;
        std::memcpy(&word, p + i, 8);
        hash = (hash ^ word) * 0xFF51AFD7ED558CCDull;
        hash ^= hash >> 32;
    }
    for (; i < size; ++i) hash = (hash ^ p[i]) * 0x100000001B3ull;
    return hash ^ (hash >> 29);
}

// What the pack's SPIR-V and metadata depend on besides the key: the translator version, the compile
// recipe and glslang version of the SPIR-V cache, and the layout of the structs kept as raw bytes.
uint64_t fingerprint() {
    const uint64_t parts[] = {kTranslatorVersion, spirv_cache_fingerprint(),
        sizeof(LatteDecompilerShaderResourceMapping), sizeof(DescriptorRankPlan),
        sizeof(LatteDecompilerOutputUniformOffsets), LATTE_NUM_MAX_TEX_UNITS};
    return fast_hash(parts, sizeof parts);
}

struct Out {
    std::vector<uint8_t> bytes;
    void raw(const void* data, size_t size) {
        const auto* p = static_cast<const uint8_t*>(data);
        bytes.insert(bytes.end(), p, p + size);
    }
    template <class T> void put(T value, size_t size = sizeof(T)) {
        for (size_t i = 0; i < size; ++i) bytes.push_back(uint8_t(uint64_t(value) >> (8 * i)));
    }
    void u8(uint32_t v) { put<uint32_t>(v, 1); }
    void u16(uint32_t v) { put<uint32_t>(v, 2); }
    void u32(uint32_t v) { put<uint32_t>(v, 4); }
    void u64(uint64_t v) { put<uint64_t>(v, 8); }
};
struct In {
    const uint8_t* data;
    size_t size, at = 0;
    bool ok = true;
    In(const uint8_t* d, size_t n) : data(d), size(n) {}
    uint64_t take(size_t n) {
        if (!ok || n > size - at) { ok = false; return 0; }
        uint64_t v = 0;
        for (size_t i = 0; i < n; ++i) v |= uint64_t(data[at + i]) << (8 * i);
        at += n;
        return v;
    }
    uint8_t u8() { return uint8_t(take(1)); }
    uint16_t u16() { return uint16_t(take(2)); }
    uint32_t u32() { return uint32_t(take(4)); }
    uint64_t u64() { return take(8); }
    void raw(void* out, size_t n) {
        if (!ok || n > size - at) { ok = false; return; }
        std::memcpy(out, data + at, n);
        at += n;
    }
    const uint8_t* skip(size_t n) {
        if (!ok || n > size - at) { ok = false; return nullptr; }
        const uint8_t* p = data + at;
        at += n;
        return p;
    }
};

void write_record(const ShaderRecord& r, Out& out) {
    out.u8(r.vertex);
    out.raw(&r.mapping, sizeof r.mapping);
    out.raw(&r.ranks, sizeof r.ranks);
    out.raw(&r.uniforms, sizeof r.uniforms);
    out.u32(r.outputParameterMask); out.u32(r.pixelColorOutputMask);
    out.u8(r.unitCount);
    for (auto v : r.units) out.u8(v);
    for (auto v : r.sampler) out.u16(v);
    for (auto v : r.depthCompare) out.u8(v);
    out.u8(r.streamout);
    out.u32(uint32_t(r.registerEntries.size()));
    for (const auto& e : r.registerEntries) { out.u32(e[0]); out.u32(e[1]); }
    out.u32(uint32_t(r.groups.size()));
    for (const auto& g : r.groups) {
        out.u16(g.bufferId); out.u16(g.kcacheBankIdOffset); out.u32(uint32_t(g.entries.size()));
        for (const auto& e : g.entries) { out.u16(e[0]); out.u16(e[1]); }
    }
    out.u64(r.outputHash); out.u64(r.pipelineId); out.u64(r.spirvHash);
}
bool read_record(In& in, ShaderRecord& r) {
    r.vertex = in.u8() != 0;
    in.raw(&r.mapping, sizeof r.mapping);
    in.raw(&r.ranks, sizeof r.ranks);
    in.raw(&r.uniforms, sizeof r.uniforms);
    r.outputParameterMask = in.u32(); r.pixelColorOutputMask = in.u32();
    r.unitCount = in.u8();
    for (auto& v : r.units) v = in.u8();
    for (auto& v : r.sampler) v = in.u16();
    for (auto& v : r.depthCompare) v = in.u8();
    r.streamout = in.u8() != 0;
    if (r.unitCount > LATTE_NUM_MAX_TEX_UNITS) return false;
    const uint32_t registers = in.u32();
    if (registers > kMaxEntries) return false;
    r.registerEntries.resize(registers);
    for (auto& e : r.registerEntries) { e[0] = in.u32(); e[1] = in.u32(); }
    const uint32_t groups = in.u32();
    if (groups > kMaxEntries) return false;
    r.groups.resize(groups);
    for (auto& g : r.groups) {
        g.bufferId = in.u16(); g.kcacheBankIdOffset = in.u16();
        const uint32_t entries = in.u32();
        if (entries > kMaxEntries) return false;
        g.entries.resize(entries);
        for (auto& e : g.entries) { e[0] = in.u16(); e[1] = in.u16(); }
    }
    r.outputHash = in.u64(); r.pipelineId = in.u64(); r.spirvHash = in.u64();
    return in.ok;
}

struct Blob {
    uint32_t words = 0;
    std::vector<uint8_t> z;
};
struct UseEntry { UseRecord use; bool baseline = false; };
struct ShaderEntry { std::shared_ptr<const ShaderRecord> record; bool baseline = false; };

struct Store {
    bool loaded = false;
    std::unordered_map<uint64_t, UseEntry> uses;
    std::unordered_map<uint64_t, ShaderEntry> shaders;
    std::unordered_map<uint64_t, std::shared_ptr<const Blob>> blobs;
    std::vector<std::pair<uint64_t, const Shader*>> tracked;
    std::string livePath;
    uint64_t revision = 0, savedRevision = 0, changedFrame = ~uint64_t{0}, attemptFrame = 0;
    Counts counts;
} S;

struct Snapshot {
    std::vector<std::pair<uint64_t, UseRecord>> uses;
    std::vector<std::pair<uint64_t, std::shared_ptr<const ShaderRecord>>> shaders;
    std::vector<std::pair<uint64_t, std::shared_ptr<const Blob>>> blobs;
    uint64_t revision = 0;
};
std::future<std::pair<bool, uint64_t>> saveJob;  // saved, revision

std::vector<uint8_t> encode(const Snapshot& snap) {
    Out out;
    out.raw(kMagic, sizeof kMagic);
    out.u64(fingerprint());
    out.u32(uint32_t(snap.uses.size())); out.u32(uint32_t(snap.shaders.size())); out.u32(uint32_t(snap.blobs.size()));
    for (const auto& [linkage, use] : snap.uses) {
        out.u64(linkage); out.u8(use.unitCount);
        for (auto v : use.units) out.u8(v);
        out.u32(use.exports); out.u8(use.streamout);
    }
    for (const auto& [hash, blob] : snap.blobs) {
        out.u64(hash); out.u32(blob->words); out.u32(uint32_t(blob->z.size()));
        out.raw(blob->z.data(), blob->z.size());
    }
    for (const auto& [key, record] : snap.shaders) {
        Out one;
        write_record(*record, one);
        out.u64(key); out.u32(uint32_t(one.bytes.size()));
        out.raw(one.bytes.data(), one.bytes.size());
    }
    out.u64(fast_hash(out.bytes.data(), out.bytes.size()));
    return std::move(out.bytes);
}

std::pair<bool, uint64_t> write_snapshot(Snapshot snap, std::string path) {
    std::string temporary;
    try {
        const auto bytes = encode(snap);
        std::error_code ec;
        const auto parent = std::filesystem::u8path(path).parent_path();
        if (!parent.empty()) std::filesystem::create_directories(parent, ec);
        if (ec) return {false, snap.revision};
        FILE* file = open_atomic_temporary(path, temporary);
        if (!file) return {false, snap.revision};
        bool wrote = fwrite(bytes.data(), 1, bytes.size(), file) == bytes.size();
        if (fclose(file) != 0) wrote = false;
        const bool saved = wrote && host::replace_file(temporary, path);
        if (!saved) { std::error_code rc; std::filesystem::remove(std::filesystem::u8path(temporary), rc); }
        return {saved, snap.revision};
    } catch (...) {
        if (!temporary.empty()) { std::error_code rc; std::filesystem::remove(std::filesystem::u8path(temporary), rc); }
        return {false, snap.revision};
    }
}

// Merges a pack file into the store. baseline entries are never written back.
bool parse(const uint8_t* data, size_t size, bool baseline, const char* origin) {
    if (size < sizeof kMagic + 8 + 12 + 8 || size > kMaxFileBytes || std::memcmp(data, kMagic, sizeof kMagic)) return false;
    In in(data, size - 8);
    in.skip(sizeof kMagic);
    if (in.u64() != fingerprint()) {
        log_msg("[shader pack] %s ignored (other translator or struct layout)", origin);
        return false;
    }
    uint64_t sum;
    std::memcpy(&sum, data + size - 8, 8);
    if (sum != fast_hash(data, size - 8)) { log_msg("[shader pack] %s ignored (damaged)", origin); return false; }
    const uint32_t nUses = in.u32(), nShaders = in.u32(), nBlobs = in.u32();
    if (nUses > kMaxCount || nShaders > kMaxCount || nBlobs > kMaxCount) return false;
    std::unordered_map<uint64_t, UseEntry> uses;
    std::unordered_map<uint64_t, ShaderEntry> shaders;
    std::unordered_map<uint64_t, std::shared_ptr<const Blob>> blobs;
    for (uint32_t i = 0; i < nUses && in.ok; ++i) {
        const uint64_t linkage = in.u64();
        UseEntry entry; entry.baseline = baseline;
        entry.use.unitCount = in.u8();
        for (auto& v : entry.use.units) v = in.u8();
        entry.use.exports = in.u32(); entry.use.streamout = in.u8() != 0;
        if (entry.use.unitCount > LATTE_NUM_MAX_TEX_UNITS) return false;
        uses.emplace(linkage, entry);
    }
    for (uint32_t i = 0; i < nBlobs && in.ok; ++i) {
        const uint64_t hash = in.u64();
        auto blob = std::make_shared<Blob>();
        blob->words = in.u32();
        const uint32_t zsize = in.u32();
        const uint8_t* z = in.skip(zsize);
        if (!z || !blob->words || blob->words > (1u << 22)) return false;
        blob->z.assign(z, z + zsize);
        blobs.emplace(hash, std::move(blob));
    }
    for (uint32_t i = 0; i < nShaders && in.ok; ++i) {
        const uint64_t key = in.u64();
        const uint32_t recordSize = in.u32();
        const uint8_t* p = in.skip(recordSize);
        if (!p) return false;
        In one(p, recordSize);
        auto record = std::make_shared<ShaderRecord>();
        if (!read_record(one, *record) || one.at != recordSize || !blobs.count(record->spirvHash)) return false;
        shaders.emplace(key, ShaderEntry{std::move(record), baseline});
    }
    if (!in.ok || in.at != size - 8) return false;
    for (auto& [k, v] : uses) S.uses.emplace(k, v);
    for (auto& [k, v] : blobs) S.blobs.emplace(k, v);
    size_t added = 0;
    for (auto& [k, v] : shaders) added += S.shaders.emplace(k, std::move(v)).second;
    log_msg("[shader pack] %s: %zu shaders, %zu programs (%zu new)", origin, size_t(nShaders), size_t(nUses), added);
    return true;
}

bool read_file(const std::string& path, std::vector<uint8_t>& bytes) {
    std::error_code ec;
    const auto length = std::filesystem::file_size(std::filesystem::u8path(path), ec);
    if (ec || length < 40 || length > kMaxFileBytes) return false;
    bytes.resize(size_t(length));
    FILE* file = open_utf8_file(path, "rb");
    if (!file) return false;
    const bool ok = fread(bytes.data(), 1, bytes.size(), file) == bytes.size() && fgetc(file) == EOF && !ferror(file);
    fclose(file);
    return ok;
}

// the live pack lives next to the SPIR-V cache (spirv.bin)
std::string live_path() {
    if (const auto setting = path_environment_utf8("WWHD_VK_SHADER_PACK"); setting && *setting != "0") return *setting;
    const auto overrideStorage = path_environment_utf8("WWHD_VK_SHADER_CACHE");
    const auto generalStorage = path_environment_utf8("WWHD_SHADER_CACHE");
    if ((overrideStorage && *overrideStorage == "0") || (!overrideStorage && generalStorage && *generalStorage == "0")) return {};
    const std::string dir = overrideStorage ? *overrideStorage : host::config_dir() + "/shadercache/vulkan-shaders";
    return dir.empty() ? std::string() : dir + "/shaderpack.bin";
}

void ensure_loaded() {
    if (S.loaded) return;
    S.loaded = true;
    const auto start = std::chrono::steady_clock::now();
    S.livePath = live_path();
    std::vector<uint8_t> bytes;
    const auto explicitPath = path_environment_utf8("WWHD_VK_SHADER_PACK");
    if (!explicitPath) {  // the baseline: shipped with the game folder or the APK
        // One baseline per translator configuration: shaderpack-<fingerprint>.bin (the fingerprint follows
        // WWHD_MUL_MODE, WWHD_STRICT_MUL, WWHD_NAN_GUARD, the translator and glslang), so the strict and the
        // fast multiplication builds each find theirs; shaderpack.bin is the older single name (issue #16).
        char named[64];
        std::snprintf(named, sizeof named, "shaderpack-%016llx.bin", (unsigned long long)fingerprint());
        if (!config::game_dir.empty()) {
            bool found = false;
            for (const std::string name : {std::string(named), std::string("shaderpack.bin")}) {
                if (found) break;
                const std::string path = config::game_dir + "/shadercache/" + name;
                if (read_file(path, bytes)) found = parse(bytes.data(), bytes.size(), true, path.c_str());
            }
        }
#ifdef __ANDROID__
        for (const char* name : {static_cast<const char*>(named), "shaderpack.bin"}) {
            size_t size = 0;
            void* asset = SDL_LoadFile(name, &size);
            if (!asset) continue;
            const std::string origin = std::string("APK asset ") + name;
            const bool ok = parse(static_cast<const uint8_t*>(asset), size, true, origin.c_str());
            SDL_free(asset);
            if (ok) break;
        }
#endif
        if (S.shaders.empty())
            log_msg("[shader pack] no baseline for this configuration (%s): every new shader is translated while playing", named);
    }
    if (!S.livePath.empty() && read_file(S.livePath, bytes)) parse(bytes.data(), bytes.size(), false, S.livePath.c_str());
    for (const auto& [k, v] : S.shaders) S.counts.baselineShaders += v.baseline;
    log_msg("[shader pack] %zu shaders, %zu programs, %zu SPIR-V blobs loaded in %lld ms",
            S.shaders.size(), S.uses.size(), S.blobs.size(),
            (long long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count());
}

Snapshot snapshot() {
    Snapshot snap;
    snap.revision = S.revision;
    std::unordered_map<uint64_t, bool> needed;
    for (const auto& [k, v] : S.uses) if (!v.baseline) snap.uses.emplace_back(k, v.use);
    for (const auto& [k, v] : S.shaders) if (!v.baseline) {
        snap.shaders.emplace_back(k, v.record);
        needed[v.record->spirvHash] = true;
    }
    for (const auto& [h, _] : needed) {
        if (auto it = S.blobs.find(h); it != S.blobs.end()) snap.blobs.emplace_back(h, it->second);
    }
    return snap;
}

bool env_on(const char* name) {
    const char* value = std::getenv(name);
    return value && std::strcmp(value, "0") && *value;
}

}  // namespace

bool active() {
    static const bool enabled = [] {
        const auto setting = path_environment_utf8("WWHD_VK_SHADER_PACK");
        return !(setting && *setting == "0");
    }();
    return enabled && !mods::cemu::has_shaders();
}
bool strict() { static const bool value = env_on("WWHD_VK_SHADER_PACK_STRICT"); return value; }
bool verify() { static const bool value = env_on("WWHD_VK_SHADER_PACK_VERIFY"); return value; }

const UseRecord* find_use(uint64_t linkage) {
    ensure_loaded();
    auto it = S.uses.find(linkage);
    return it == S.uses.end() ? nullptr : &it->second.use;
}

std::shared_ptr<const ShaderRecord> find_shader(uint64_t key) {
    ensure_loaded();
    auto it = S.shaders.find(key);
    return it == S.shaders.end() ? nullptr : it->second.record;
}

bool inflate_spirv(const ShaderRecord& record, std::vector<uint32_t>& words) {
    auto it = S.blobs.find(record.spirvHash);
    if (it == S.blobs.end()) return false;
    const Blob& blob = *it->second;
    words.resize(blob.words);
    uLongf size = uLongf(blob.words) * 4;
    if (uncompress(reinterpret_cast<Bytef*>(words.data()), &size, blob.z.data(), uLong(blob.z.size())) != Z_OK ||
        size != uLongf(blob.words) * 4) {
        words.clear();
        return false;
    }
    return true;
}

std::shared_ptr<ShaderRecord> record_of(const Shader& shader) {
    auto r = std::make_shared<ShaderRecord>();
    r->vertex = shader.vertex;
    r->mapping = shader.mapping;
    r->ranks = shader.descriptorRanks;
    r->uniforms = shader.uniforms;
    r->outputHash = shader.outputHash;
    r->pipelineId = shader.pipelineId;
    if (!shader.dec) return r;
    const LatteDecompilerShader& dec = *shader.dec;
    r->outputParameterMask = dec.outputParameterMask;
    r->pixelColorOutputMask = dec.pixelColorOutputMask;
    r->unitCount = uint8_t(std::clamp(int(dec.textureUnitListCount), 0, int(LATTE_NUM_MAX_TEX_UNITS)));
    for (uint32_t i = 0; i < r->unitCount; ++i) r->units[i] = dec.textureUnitList[i];
    for (uint32_t i = 0; i < LATTE_NUM_MAX_TEX_UNITS; ++i) {
        r->sampler[i] = dec.textureUnitSamplerAssignment[i];
        r->depthCompare[i] = dec.textureUsesDepthCompare[i];
    }
    r->streamout = dec.hasStreamoutBufferWrite;
    for (const auto& e : dec.list_remappedUniformEntries_register)
        r->registerEntries.push_back({e.indexOffset, e.mappedIndexOffset});
    for (const auto& g : dec.list_remappedUniformEntries_bufferGroups) {
        ShaderRecord::Group group;
        group.bufferId = g.bufferId; group.kcacheBankIdOffset = g.kcacheBankIdOffset;
        for (const auto& e : g.entries) group.entries.push_back({e.indexOffset, e.mappedIndexOffset});
        r->groups.push_back(std::move(group));
    }
    return r;
}

void apply(Shader& shader, const ShaderRecord& r) {
    shader.vertex = r.vertex;
    shader.mapping = r.mapping;
    shader.descriptorRanks = r.ranks;
    shader.uniforms = r.uniforms;
    shader.outputHash = r.outputHash;
    shader.pipelineId = r.pipelineId;
    shader.fromPack = true;
    auto* dec = new LatteDecompilerShader(r.vertex ? LatteConst::ShaderType::Vertex : LatteConst::ShaderType::Pixel);
    dec->outputParameterMask = r.outputParameterMask;
    dec->pixelColorOutputMask = r.pixelColorOutputMask;
    dec->textureUnitListCount = r.unitCount;
    std::fill(std::begin(dec->textureUnitList), std::end(dec->textureUnitList), uint8_t{0});
    for (uint32_t i = 0; i < r.unitCount; ++i) dec->textureUnitList[i] = r.units[i];
    for (uint32_t i = 0; i < LATTE_NUM_MAX_TEX_UNITS; ++i) {
        dec->textureUnitSamplerAssignment[i] = r.sampler[i];
        dec->textureUsesDepthCompare[i] = r.depthCompare[i] != 0;
    }
    dec->hasStreamoutBufferWrite = r.streamout;
    dec->resourceMapping = r.mapping;
    for (const auto& e : r.registerEntries) {
        LatteFastAccessRemappedUniformEntry_register_t entry{};
        entry.indexOffset = e[0]; entry.mappedIndexOffset = e[1];
        dec->list_remappedUniformEntries_register.push_back(entry);
    }
    for (const auto& g : r.groups) {
        dec->list_remappedUniformEntries_bufferGroups.emplace_back(g.bufferId, g.kcacheBankIdOffset);
        for (const auto& e : g.entries) {
            LatteFastAccessRemappedUniformEntry_buffer_t entry{};
            entry.indexOffset = e[0]; entry.mappedIndexOffset = e[1];
            dec->list_remappedUniformEntries_bufferGroups.back().entries.push_back(entry);
        }
    }
    shader.dec = dec;
}

bool same(const ShaderRecord& a, const ShaderRecord& b) {
    Out x, y;
    write_record(a, x);
    write_record(b, y);
    return x.bytes == y.bytes;
}

void note_use(uint64_t linkage, const UseRecord& use) {
    ensure_loaded();
    if (S.uses.count(linkage)) return;
    S.uses.emplace(linkage, UseEntry{use, false});
    ++S.revision;
}

void track(uint64_t key, const Shader* shader) {
    ensure_loaded();
    if (!shader || S.shaders.count(key)) return;
    S.tracked.emplace_back(key, shader);
}

void forget_tracked() { S.tracked.clear(); }

void flush(uint64_t frame) {
    if (S.tracked.empty()) return;
    size_t kept = 0;
    for (auto& entry : S.tracked) {
        const Shader* shader = entry.second;
        if (S.shaders.count(entry.first)) continue;
        if (!shader->error.empty() || !shader->dec) continue;  // failed: nothing to keep
        if (shader->spirv.empty()) { S.tracked[kept++] = entry; continue; }  // SPIR-V still compiling
        auto record = record_of(*shader);
        record->spirvHash = fast_hash(shader->spirv.data(), shader->spirv.size() * 4, shader->spirv.size());
        if (!S.blobs.count(record->spirvHash)) {
            auto blob = std::make_shared<Blob>();
            blob->words = uint32_t(shader->spirv.size());
            uLongf capacity = compressBound(uLong(blob->words) * 4);
            blob->z.resize(capacity);
            if (compress2(blob->z.data(), &capacity, reinterpret_cast<const Bytef*>(shader->spirv.data()),
                          uLong(blob->words) * 4, 6) != Z_OK) continue;
            blob->z.resize(capacity);
            S.blobs.emplace(record->spirvHash, std::move(blob));
        }
        S.shaders.emplace(entry.first, ShaderEntry{std::move(record), false});
        ++S.counts.recorded;
        ++S.revision;
        S.changedFrame = frame;
    }
    S.tracked.resize(kept);
}

void checkpoint(uint64_t frame) {
    // a line a minute of play (60 fps) while something changed: how much of the game the pack serves on this device
    static uint64_t reportedFrame = 0, reportedTotal = 0;
    const uint64_t total = S.counts.hits + S.counts.misses;
    if (frame != ~uint64_t{0} && frame - reportedFrame >= 3600 && total != reportedTotal) {
        reportedFrame = frame; reportedTotal = total;
        report("running");
    }
    if (saveJob.valid() && saveJob.wait_for(std::chrono::seconds(0)) == std::future_status::ready) {
        try { const auto [saved, revision] = saveJob.get(); if (saved) S.savedRevision = revision; } catch (...) {}
    }
    if (saveJob.valid() || S.livePath.empty() || S.revision == S.savedRevision || S.changedFrame == ~uint64_t{0} ||
        frame < S.changedFrame || frame - S.changedFrame < 120 || frame < S.attemptFrame || frame - S.attemptFrame < 120) return;
    S.attemptFrame = frame;
    try { saveJob = std::async(std::launch::async, write_snapshot, snapshot(), S.livePath); } catch (...) {}
}

bool save(bool wait) {
    (void)wait;
    if (saveJob.valid()) {
        try { const auto [saved, revision] = saveJob.get(); if (saved) S.savedRevision = revision; } catch (...) {}
    }
    if (S.livePath.empty() || S.revision == S.savedRevision) return true;
    const auto [saved, revision] = write_snapshot(snapshot(), S.livePath);
    if (saved) S.savedRevision = revision;
    return saved;
}

Counts counts() {
    Counts c = S.counts;
    c.uses = S.uses.size(); c.shaders = S.shaders.size(); c.blobs = S.blobs.size();
    return c;
}
void count_hit() { ++S.counts.hits; }
void count_miss() { ++S.counts.misses; }
void count_verified(bool matched) { ++S.counts.verified; S.counts.mismatched += !matched; }

void report(const char* when) {
    const Counts c = counts();
    log_msg("[shader pack] %s: %llu hits, %llu misses, %llu recorded; %zu shaders (%zu from the baseline)%s",
            when, (unsigned long long)c.hits, (unsigned long long)c.misses, (unsigned long long)c.recorded,
            c.shaders, c.baselineShaders, verify() ? "" : "");
    if (verify())
        log_msg("[shader pack] verify: %llu checked, %llu different", (unsigned long long)c.verified,
                (unsigned long long)c.mismatched);
}

uint64_t translator_fingerprint() { return fingerprint(); }
}  // namespace gfxvk::vk::pack
