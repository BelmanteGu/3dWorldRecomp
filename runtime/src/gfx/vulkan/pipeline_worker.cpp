#include "pass.h"
#include "pipeline_worker.h"
#include "../headstart_limits.h"
#include "pipeline_identity.h"
#include "recipe_persistence.h"
#include "headstart.h"
#include "runtime.h"
#include "pipeline_demand.h"
#include "shader_compiler.h"
#include "platform/host.h"
#include "stutter_trace.h"
#include "worker_metrics.h"
#include <algorithm>
#include <cstring>
#include <cctype>
#include <atomic>
#include <cstdio>
#include <filesystem>
#include <mutex>
#include <condition_variable>
#include <chrono>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <zlib.h>

namespace gfxvk {
namespace {
std::string identity(const std::vector<uint8_t>& key) {
  return {reinterpret_cast<const char*>(key.data()), key.size()};
}
void destroy(VkDevice device, WarmPipeline& p) {
  if (p.pipeline) vkDestroyPipeline(device, p.pipeline, nullptr);
  if (p.layout) vkDestroyPipelineLayout(device, p.layout, nullptr);
  for (auto set : p.sets) if (set) vkDestroyDescriptorSetLayout(device, set, nullptr);
  p = {};
}
struct Ready { WarmPipeline handles; std::shared_ptr<PipelineRecipe> recipe; };
struct Worker {
  VkDevice device = VK_NULL_HANDLE;
  PipelineRecipeFingerprint fingerprint{};
  std::string path;
  std::string headstartPath;
  PipelineWarmupResult result;
  std::mutex mutex;
  std::mutex persistenceMutex; // Explicit checkpoint and periodic saver share one writer.
  std::thread thread, saver;
  std::condition_variable saveWake;
  bool saveStopped = false;
  RecipeSaveRevision revision;
  std::chrono::steady_clock::time_point lastSave = std::chrono::steady_clock::now();
  bool stopped = false;
  VkPipelineCache cache = VK_NULL_HANDLE; // Worker writes; caller reads only after join.
  bool merged = false;
  std::unordered_map<std::string, Ready> ready;
  std::unordered_map<std::string, std::shared_ptr<PipelineRecipe>> recipes;
  std::unordered_set<std::string> consumed;
  size_t recipeBytes = 0;
  RecipeShaderPool shaderPool;
  ~Worker() {
    { std::lock_guard lock(mutex); stopped = true; saveStopped = true; }
    saveWake.notify_all();
    if (thread.joinable()) thread.join();
    if (saver.joinable()) saver.join();
    for (auto& [key, entry] : ready) destroy(device, entry.handles);
    if (cache) vkDestroyPipelineCache(device, cache, nullptr);
  }
};
Worker& worker() { static Worker value; return value; }
}  // namespace
size_t drop_unused_warm_pipelines() {
  auto& w = worker();
  if (!w.device) return 0;
  std::lock_guard lock(w.mutex);
  const size_t dropped = w.ready.size();
  for (auto& [key, entry] : w.ready) destroy(w.device, entry.handles);
  w.ready.clear();
  return dropped;
}
namespace {
// Native driver allocations have no portable byte-size query. Bound retained
// unused handles independently of the CPU recipe database; evicted recipes and
// the driver cache remain available for a correct demand compile.
#ifdef __ANDROID__
constexpr size_t maxUnusedWarmPipelines = 256;
#else
// The recipe database holds every configuration played (strict and fast multiplication have different SPIR-V):
// 1500+ recipes after both. With 1024 kept, a third of the current configuration's warm pipelines were dropped
// at random before use and compiled again on demand at the world map (issue #17: served 443, not warmed 323).
constexpr size_t maxUnusedWarmPipelines = 4096;
#endif
// Full: the new pipeline is not kept (it stays in the pipeline caches, so a draw that needs it later builds it
// in about a millisecond). The startup list puts the player's own recipes before the recipe pack, so what
// stays ready is what this player met; evicting at random dropped those for pack entries (#16).
void retain_ready(Worker& w, const std::string& key, WarmPipeline handles,
                  const std::shared_ptr<PipelineRecipe>& recipe) {
  if(w.ready.size() >= maxUnusedWarmPipelines) { destroy(w.device,handles); return; }
  w.ready.emplace(key,Ready{handles,recipe});
}

// CPU bytes of one recipe. The SPIR-V is shared between recipes (RecipeShaderPool) and stored once in the
// database (format 2), so it is not counted per recipe: counting it made ~4000 recipes "256 MB" and refused
// every recipe after that (#16).
size_t footprint(const PipelineRecipe& p) {
  size_t size = 8192 + p.key.size();
  for (int i = 0; i < 2; ++i)
    size += p.descriptors[i].size() * sizeof(VkDescriptorSetLayoutBinding);
  return size;
}
bool insert_recipe(Worker& w, const std::shared_ptr<PipelineRecipe>& recipe) {
  auto key = identity(recipe->key);
  auto found = w.recipes.find(key);
  const size_t old = found == w.recipes.end() ? 0 : footprint(*found->second);
  const size_t size = footprint(*recipe);
  if ((found == w.recipes.end() && w.recipes.size() >= kPipelineRecipeMaxCount) ||
      size > kPipelineRecipeMaxBytes - (w.recipeBytes - old)) return false;
  w.shaderPool.freeze(*recipe);
  w.recipeBytes += size - old;
  w.recipes.insert_or_assign(std::move(key), recipe);
  return true;
}
void changed(Worker& w) {
  w.revision.changed(); w.saveWake.notify_one();
}
void persist_snapshot(Worker& w) {
  std::lock_guard writer(w.persistenceMutex);
  std::vector<std::shared_ptr<PipelineRecipe>> snapshot;
  uint64_t revision;
  {
    std::lock_guard lock(w.mutex);
    if (!w.revision.dirty()) return;
    revision = w.revision.current;
    snapshot.reserve(w.recipes.size());
    for (auto& [key, recipe] : w.recipes) snapshot.push_back(recipe);
  }
  try {
    auto bytes = encode_pipeline_recipes(w.fingerprint, snapshot);
    atomic_recipe_write(w.path, bytes, host::replace_file);
    {
      std::lock_guard lock(w.mutex);
      w.revision.complete(revision);
    }
    LOG("[vulkan warmup] saved %zu recipes (%zu bytes)", snapshot.size(), bytes.size());
  } catch (const std::exception& error) {
    LOG("[vulkan warmup] save failed: %s", error.what());
  }
  std::lock_guard lock(w.mutex); w.lastSave = std::chrono::steady_clock::now();
}
void save_loop(Worker& w) {
  host::set_thread_name("Vulkan recipe save");
  std::unique_lock lock(w.mutex);
  while (!w.saveStopped) {
    w.saveWake.wait(lock, [&] { return w.saveStopped || w.revision.dirty(); });
    if (w.saveStopped) break;
    // Only one bounded snapshot at a time; failures also back off for two
    // minutes, and an explicit checkpoint always saves the latest revision.
    auto due = w.lastSave + std::chrono::seconds(120);
    if (w.saveWake.wait_until(lock, due, [&] { return w.saveStopped; })) break;
    lock.unlock();
    try { persist_snapshot(w); }
    catch (const std::exception& error) {
      LOG("[vulkan warmup] snapshot failed: %s", error.what());
      std::lock_guard retry(w.mutex); w.lastSave = std::chrono::steady_clock::now();
    }
    lock.lock();
  }
}
VkResult compile(VkDevice device, VkPipelineCache cache, PipelineRecipe& recipe, WarmPipeline& out) {
  VkShaderModule modules[2]{};
  auto cleanup = [&] {
    for (auto module : modules) if (module) vkDestroyShaderModule(device, module, nullptr);
  };
  VkResult result = VK_SUCCESS;
  for (int stage = 0; stage < 2 && result == VK_SUCCESS; ++stage) {
    VkDescriptorSetLayoutCreateInfo descriptor{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    descriptor.bindingCount = recipe.descriptors[stage].size();
    descriptor.pBindings = recipe.descriptors[stage].data();
    result = vkCreateDescriptorSetLayout(device, &descriptor, nullptr, &out.sets[stage]);
    if (result != VK_SUCCESS) break;
    VkShaderModuleCreateInfo module{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    module.codeSize = recipe.words(stage).size() * 4; module.pCode = recipe.words(stage).data();
    result = vkCreateShaderModule(device, &module, nullptr, &modules[stage]);
  }
  if (result == VK_SUCCESS) {
    VkPipelineLayoutCreateInfo layout{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layout.setLayoutCount = 2; layout.pSetLayouts = out.sets;
    result = vkCreatePipelineLayout(device, &layout, nullptr, &out.layout);
  }
  if (result == VK_SUCCESS) {
    // Rebind pointers on a private copy: the persistence thread may serialize
    // this immutable recipe concurrently.
    auto state = *recipe.state;
    auto info = *state.get();
    VkPipelineShaderStageCreateInfo stages[2]{};
    for (int i = 0; i < 2; ++i) {
      stages[i].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
      stages[i].stage = i ? VK_SHADER_STAGE_FRAGMENT_BIT : VK_SHADER_STAGE_VERTEX_BIT;
      stages[i].pName = "main"; stages[i].module = modules[i];
    }
    info.pStages = stages; info.layout = out.layout;
    if (R.renderPasses) render_pass_pipeline_targets(info, state.rendering_targets());
    const auto cpuStart = stutter::trace().enabled() ? stutter::thread_cpu_now() : 0;
    const auto started = stutter::trace().enabled() ? stutter::now() : 0;
    result = vkCreateGraphicsPipelines(device, cache, 1, &info, nullptr, &out.pipeline);
    if (started) stutter::trace().add("startup_pipeline", 0, std::hash<std::string>{}(identity(recipe.key)),
        started, stutter::now(), int(result), stutter::cpu_elapsed(cpuStart));
  }
  cleanup();
  if (result != VK_SUCCESS) destroy(device, out);
  else out.dynamicUniforms = recipe.dynamicUniforms;
  return result;
}
std::vector<uint8_t> read_whole(const std::string& path, uintmax_t size) {
  std::unique_ptr<FILE, decltype(&std::fclose)> file(open_utf8_file(path, "rb"), &std::fclose);
  if (!file) throw std::runtime_error("cannot open recipe file");
  std::vector<uint8_t> bytes(size);
  if (std::fread(bytes.data(), 1, size, file.get()) != size || std::fgetc(file.get()) != EOF || std::ferror(file.get()))
    throw std::runtime_error("recipe read failed");
  return bytes;
}
// The recipe pack (tools/sm3dw_recipe_pack.sh): recipe databases next to the game, one per GPU and driver
// (<game>/shadercache/pipelines/*.recipes, or WWHD_VK_RECIPE_PACK = a file or directory; 0 = none). Only
// files made on this device and driver are used: the others are skipped without a word.
std::string g_recipePack, g_recipePackTranslator;
// <16 hex digits>-*.recipes: made by one translator configuration (strict or fast multiplication...); its
// SPIR-V never matches another one's, so compiling it would only waste the warm-up's time
bool pack_file_wanted(const std::string& file) {
  if (file.size() < 17 || file[16] != '-') return true;
  for (size_t i = 0; i < 16; ++i) if (!std::isxdigit(static_cast<unsigned char>(file[i]))) return true;
  return g_recipePackTranslator.empty() || file.compare(0, 16, g_recipePackTranslator) == 0;
}
std::vector<std::shared_ptr<PipelineRecipe>> read_recipe_pack(Worker& w, size_t& files) {
  std::vector<std::shared_ptr<PipelineRecipe>> result;
  if (g_recipePack.empty() || g_recipePack == "0") return result;
  std::error_code ec;
  const auto root = std::filesystem::u8path(g_recipePack);
  std::vector<std::filesystem::path> paths;
  if (std::filesystem::is_directory(root, ec)) {
    for (auto& entry : std::filesystem::directory_iterator(root, ec))
      if (entry.path().extension() == ".recipes" && pack_file_wanted(entry.path().filename().string()))
        paths.push_back(entry.path());
    std::sort(paths.begin(), paths.end());
  } else if (std::filesystem::exists(root, ec)) paths.push_back(root);
  for (auto& path : paths) {
    const auto size = std::filesystem::file_size(path, ec);
    if (ec || size < 64 || size > kPipelineRecipeMaxBytes) continue;
    const auto u8 = path.u8string();
    const std::string name(reinterpret_cast<const char*>(u8.c_str()), u8.size());
    try {
      auto bytes = read_whole(name, size);
      if (std::memcmp(bytes.data() + 8, w.fingerprint.data(), w.fingerprint.size())) continue; // other GPU/driver
      auto recipes = decode_pipeline_recipes(w.fingerprint, bytes);
      result.insert(result.end(), recipes.begin(), recipes.end());
      ++files;
    } catch (const std::exception& error) {
      LOG("[vulkan warmup] recipe pack %s ignored: %s", name.c_str(), error.what());
    }
  }
  return result;
}
std::vector<std::shared_ptr<PipelineRecipe>> read_recipes(Worker& w, bool headstart) {
  const auto& path = headstart ? w.headstartPath : w.path;
  if (path.empty() || path == "0") return {};
  std::error_code ec;
  const auto size = std::filesystem::file_size(std::filesystem::u8path(path), ec);
  if (ec) return {};
  if (size > (headstart ? kHeadstartMaxFileBytes : kPipelineRecipeMaxBytes)) throw std::runtime_error("recipe file size limit");
  std::unique_ptr<FILE, decltype(&std::fclose)> file(open_utf8_file(path, "rb"), &std::fclose);
  if (!file) return {};
  if (!headstart) {
    std::vector<uint8_t> bytes(size);
    if (std::fread(bytes.data(), 1, size, file.get()) != size || std::fgetc(file.get()) != EOF || std::ferror(file.get()))
      throw std::runtime_error("recipe read failed");
    return decode_pipeline_recipes(w.fingerprint, bytes);
  }
  auto word = [](const uint8_t* p) {
    return uint32_t(p[0]) | uint32_t(p[1]) << 8 | uint32_t(p[2]) << 16 | uint32_t(p[3]) << 24;
  };
  std::vector<std::shared_ptr<PipelineRecipe>> result;
  uint64_t consumed = 0, rawBytes = 0, records = 0;
  while (consumed < size) {
    uint8_t header[12];
    if (size - consumed < 12 || std::fread(header, 1, 12, file.get()) != 12)
      throw std::runtime_error("truncated head-start header");
    consumed += 12;
    auto type = word(header), rawSize = word(header + 4), compressed = word(header + 8);
    if (++records > kHeadstartMaxRecords || type < 1 || type > 6 || !rawSize || rawSize > kHeadstartMaxRecordBytes ||
        !compressed || compressed > kHeadstartMaxRecordBytes || compressed > size - consumed ||
        rawSize > kHeadstartMaxRawBytes - rawBytes) throw std::runtime_error("head-start record bounds");
    rawBytes += rawSize; consumed += compressed;
    if (type != 4) {
      if (std::fseek(file.get(), compressed, SEEK_CUR)) throw std::runtime_error("head-start seek failed");
      continue;
    }
    std::vector<uint8_t> encoded(compressed), raw(rawSize);
    if (std::fread(encoded.data(), 1, compressed, file.get()) != compressed)
      throw std::runtime_error("truncated pipeline head start");
    uLongf decoded = rawSize;
    if (uncompress(raw.data(), &decoded, encoded.data(), compressed) != Z_OK || decoded != rawSize)
      throw std::runtime_error("invalid pipeline head-start compression");
    // Different device/schema: ignore the local recipe without ever handing it
    // to Vulkan. Shader records remain useful across devices.
    if (raw.size() < 64 || std::memcmp(raw.data(), "WWVKPR01", 8))
      throw std::runtime_error("invalid pipeline head-start database");
    if (std::memcmp(raw.data() + 8, w.fingerprint.data(), w.fingerprint.size())) continue;
    auto recipes = decode_pipeline_recipes(w.fingerprint, raw);
    if (recipes.size() > kPipelineRecipeMaxCount - result.size()) throw std::runtime_error("head-start pipeline count limit");
    result.insert(result.end(), recipes.begin(), recipes.end());
  }
  return result;
}
void run(Worker& w, std::vector<uint8_t> seed) {
  host::set_thread_name("Vulkan warmup");
  VkPipelineCache cache = VK_NULL_HANDLE;
  size_t completed = 0, failed = 0, cancelled = 0;
  struct WarmupMetrics {
    size_t &completed, &failed;
    uint64_t started = stutter::trace().enabled() ? stutter::now() : 0;
    uint64_t cpu = started ? stutter::thread_cpu_now() : 0;
    ~WarmupMetrics() {
      if (started) stutter::trace().add("startup_warmup", 0, completed, started,
          stutter::now(), int(failed), stutter::cpu_elapsed(cpu));
    }
  } metrics{completed, failed};
  try {
    std::vector<std::shared_ptr<PipelineRecipe>> loaded;
    std::unordered_set<std::string> seen;
    for (bool headstart : {false, true}) {
      try {
        for (auto& recipe : read_recipes(w, headstart))
          if (loaded.size() < kPipelineRecipeMaxCount && seen.insert(identity(recipe->key)).second) loaded.push_back(recipe);
      } catch (const std::exception& error) {
        if (headstart) ++failed;
        LOG("[vulkan warmup] %s ignored: %s", headstart ? "head start" : "local recipes", error.what());
      }
    }
    const size_t own = loaded.size();
    size_t packFiles = 0, packed = 0;
    try {
      for (auto& recipe : read_recipe_pack(w, packFiles))
        if (loaded.size() < kPipelineRecipeMaxCount && seen.insert(identity(recipe->key)).second) {
          loaded.push_back(recipe); ++packed;
        }
    } catch (const std::exception& error) {
      LOG("[vulkan warmup] recipe pack ignored: %s", error.what());
    }
    if (packFiles) LOG("[vulkan warmup] %zu own recipes, %zu more from the recipe pack (%zu files)", own, packed, packFiles);
    if (loaded.empty()) { w.result = {0, failed}; return; }
    {
      // the player's own recipes stay in the database that is saved again; the pack's are only compiled
      // (WWHD_VK_RECIPE_MERGE=1: the pack's join the saved database too; tools/sm3dw_recipe_pack.sh merges so)
      const char* merge = std::getenv("WWHD_VK_RECIPE_MERGE");
      const bool mergePack = merge && !std::strcmp(merge, "1");
      bool mergedPack = false;
      std::lock_guard lock(w.mutex);
      std::vector<std::shared_ptr<PipelineRecipe>> kept;
      kept.reserve(loaded.size());
      for (size_t i = 0; i < loaded.size(); ++i) {
        if (w.consumed.contains(identity(loaded[i]->key))) continue;
        if ((i < own || mergePack) && !insert_recipe(w, loaded[i])) continue;
        if (i >= own && mergePack) mergedPack = true;
        kept.push_back(std::move(loaded[i]));
      }
      loaded.swap(kept);
      if (mergedPack) changed(w);
    }
    VkPipelineCacheCreateInfo ci{VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO};
    ci.initialDataSize = seed.size(); ci.pInitialData = seed.data();
    if (vkCreatePipelineCache(w.device, &ci, nullptr, &cache) != VK_SUCCESS) {
      ci.initialDataSize = 0; ci.pInitialData = nullptr;
      if (vkCreatePipelineCache(w.device, &ci, nullptr, &cache) != VK_SUCCESS)
        throw std::runtime_error("worker pipeline cache creation failed");
    }
    std::vector<uint8_t>().swap(seed); // Vulkan copied initial cache data during creation.
    LOG("[vulkan warmup] %zu exact pipeline recipes queued", loaded.size());
    for (auto& recipe : loaded) {
      // Demand has its own single compiler; stop issuing speculative work
      // while it is busy. A driver call already in flight cannot be cancelled.
      while (pipeline_demand_active() || vk::shader_compiler_active()) {
        { std::lock_guard lock(w.mutex); if (w.stopped) break; }
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
      }
      auto key = identity(recipe->key);
      {
        std::lock_guard lock(w.mutex);
        if (w.stopped) break;
        if (w.consumed.contains(key)) { ++cancelled; continue; }
      }
      WarmPipeline result;
      if (compile(w.device, cache, *recipe, result) != VK_SUCCESS) { ++failed; continue; }
      bool publish;
      {
        std::lock_guard lock(w.mutex);
        publish = !w.stopped && !w.consumed.contains(key);
        if (publish) {
          try { retain_ready(w,key,result,recipe); }
          catch (...) { destroy(w.device,result); throw; }
        }
      }
      if (publish) ++completed;
      else { destroy(w.device, result); ++cancelled; }
    }
    LOG("[vulkan warmup] %zu ready, %zu failed, %zu cancelled", completed, failed, cancelled);
  } catch (const std::exception& error) {
    ++failed;
    LOG("[vulkan warmup] skipped: %s", error.what());
  }
  w.cache = cache;
  w.result = {completed, failed};
}

}
void start_pipeline_worker(VkDevice device, PipelineRecipeFingerprint fingerprint,
    std::string path, std::vector<uint8_t> cacheSeed, std::string headstartPath, std::string recipePack) {
  // Construct the trace first so it outlives a worker still finishing during
  // static destruction after an exceptional shutdown.
  (void)stutter::trace();
  (void)vk::shader_compiler_active(); // Demand state must outlive startup-worker shutdown.
  auto& w = worker();
  if (w.device || path.empty()) return;
  w.device = device; w.fingerprint = fingerprint; w.path = std::move(path);
  w.headstartPath = std::move(headstartPath);
  g_recipePack = std::move(recipePack);
  w.saver = std::thread(save_loop, std::ref(w));
  w.thread = std::thread(run, std::ref(w), std::move(cacheSeed));
}
// how the warm-up served the render thread (logged at save_pipeline_recipes)
std::atomic<uint64_t> g_warmTaken{0}, g_warmAbsent{0}, g_warmSpirv{0}, g_warmState{0};
void set_recipe_pack_translator(std::string fingerprint) { g_recipePackTranslator = std::move(fingerprint); }
bool take_warm_pipeline(const std::vector<uint8_t>& key,
    const std::array<std::vector<uint32_t>, 2>& spirv,
    const std::array<std::vector<VkDescriptorSetLayoutBinding>, 2>& descriptors,
    bool dynamicUniforms, const GraphicsPipelineState& currentState, WarmPipeline& out) {
  auto& w = worker();
  if (!w.device) return false;
  Ready entry;
  {
    std::lock_guard lock(w.mutex);
    auto id = identity(key);
    // This also prevents an in-flight startup job from publishing a duplicate
    // after the render thread has already compiled this key.
    if (w.consumed.size() < kPipelineRecipeMaxCount) w.consumed.insert(id);
    auto found = w.ready.find(id);
    if (found == w.ready.end()) { ++g_warmAbsent; return false; }
    entry = std::move(found->second); w.ready.erase(found);
  }
  bool matches = false;
  try {
    const bool programs = portable_recipe_matches(*entry.recipe, spirv, descriptors, dynamicUniforms);
    matches = programs && entry.recipe->state &&
        encode_pipeline_fixed_state(*entry.recipe->state) == encode_pipeline_fixed_state(currentState);
    if (!programs) ++g_warmSpirv; else if (!matches) ++g_warmState;
  } catch (...) { /* Allocation/schema failures fall back to current-state creation. */ }
  if (!matches) {
    destroy(w.device, entry.handles);
    return false;
  }
  ++g_warmTaken;
  record_headstart_pipeline(w.fingerprint, entry.recipe);
  out = entry.handles;
  return true;
}
void remember_pipeline_recipe(std::shared_ptr<PipelineRecipe> recipe) {
  auto& w = worker();
  if (!w.device) return;
  record_headstart_pipeline(w.fingerprint, recipe);
  std::lock_guard lock(w.mutex);
  if (insert_recipe(w, recipe)) changed(w);
}
void checkpoint_pipeline_recipes() {
  auto& w=worker();
  if(!w.device) return;
  try { persist_snapshot(w); }
  catch(const std::exception& error) {
    LOG("[vulkan warmup] checkpoint snapshot failed: %s",error.what());
  }
}
void save_pipeline_recipes() {
  auto& w = worker();
  if (!w.device) return;
  LOG("[vulkan warmup] served %llu pipelines; not warmed %llu, other programs %llu, other state %llu",
      (unsigned long long)g_warmTaken.load(), (unsigned long long)g_warmAbsent.load(),
      (unsigned long long)g_warmSpirv.load(), (unsigned long long)g_warmState.load());
  {
    std::lock_guard lock(w.mutex); w.stopped = true;
  }
  if (w.thread.joinable()) w.thread.join();
  {
    std::lock_guard lock(w.mutex); w.saveStopped = true;
  }
  w.saveWake.notify_all();
  if (w.saver.joinable()) w.saver.join();
  try { persist_snapshot(w); }
  catch (const std::exception& error) {
    LOG("[vulkan warmup] final snapshot failed: %s", error.what());
  }
}
bool merge_pipeline_worker_cache(VkPipelineCache target) {
  auto& w = worker();
  if (!w.device || w.thread.joinable() || !target || !w.cache || w.merged) return false;
  const auto result = vkMergePipelineCaches(w.device, target, 1, &w.cache);
  if (result != VK_SUCCESS) {
    LOG("[vulkan warmup] cache merge failed (%d)", int(result));
    return false;
  }
  w.merged = true;
  LOG("[vulkan warmup] worker pipeline cache merged for persistence");
  return true;
}
PipelineWarmupResult warm_resolved_pipeline_recipes(std::vector<std::shared_ptr<PipelineRecipe>> recipes,
    const std::function<void(size_t)>& progress) {
  auto& w = worker();
  if (w.thread.joinable()) w.thread.join();
  if (!w.device || recipes.size() > kPipelineRecipeMaxCount) return {0, recipes.size()};
  std::atomic<size_t> next{0}, ready{0}, failed{0}, finished{0};
  std::mutex progressMutex;
  auto compileAll = [&] {
    host::set_thread_name("Vulkan state warmup");
    for (size_t i; (i = next.fetch_add(1)) < recipes.size();) {
      const auto& recipe = recipes[i];
      const auto key = identity(recipe->key);
      bool skip = false;
      {
        std::lock_guard lock(w.mutex);
        if (w.stopped) { ++failed; skip = true; }
        else if (w.ready.contains(key)) { ++ready; skip = true; }
      }
      if (!skip) {
        WarmPipeline handles;
        try {
          if (compile(w.device, w.cache, *recipe, handles) != VK_SUCCESS) ++failed;
          else {
            std::lock_guard lock(w.mutex);
            if (!insert_recipe(w, recipe)) { destroy(w.device, handles); ++failed; }
            else {
              try { retain_ready(w,key,handles,recipe); handles = {}; }
              catch (...) { destroy(w.device, handles); throw; }
              changed(w);
              ++ready;
            }
          }
        } catch (const std::exception& error) {
          destroy(w.device, handles);
          ++failed;
          LOG("[vulkan state warmup] skipped: %s", error.what());
        }
      }
      const size_t done = ++finished;
      if (progress) { std::lock_guard lock(progressMutex); progress(done); }
    }
  };
  // Offline warming only (nothing else runs): a few compiling threads, leaving cores free.
  const unsigned cores = std::max(1u, std::thread::hardware_concurrency());
  const size_t threads = std::min<size_t>({4, cores > 2 ? cores - 2 : 1, std::max<size_t>(1, recipes.size())});
  std::vector<std::thread> pool;
  for (size_t t = 1; t < threads; ++t) pool.emplace_back(compileAll);
  w.thread = std::thread(compileAll);
  w.thread.join();
  for (auto& thread : pool) thread.join();
  PipelineWarmupResult batch{ready.load(), failed.load()};
  w.result.ready += batch.ready; w.result.failed += batch.failed;
  return batch;
}
PipelineWarmupResult finish_pipeline_warmup() {
  auto& w = worker();
  if (w.thread.joinable()) w.thread.join();
  return w.result;
}
}
