#pragma once
#include <cstdint>
#include <array>
#include <memory>
struct LatteFetchShader;
namespace gfxvk {
struct PipelineRecipe;
namespace vk { struct Shader; }
int warm_headstart_shaders();
// draw.cpp: the recipe a draw with these registers would make, nothing compiled (nullptr: no target)
std::shared_ptr<PipelineRecipe> speculative_pipeline_recipe(const uint32_t* regs, vk::Shader* vs, vk::Shader* ps,
                                                            LatteFetchShader* fs, uint32_t prim);
void record_headstart_shader(const uint32_t* regs, bool vertex, bool linkedVertex = false);
void record_headstart_pipeline(const std::array<uint8_t, 48>& fingerprint,
    const std::shared_ptr<PipelineRecipe>& recipe);
void save_headstart_recording();
}
