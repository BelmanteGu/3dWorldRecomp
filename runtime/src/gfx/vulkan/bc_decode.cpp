#include "deferred_dispatch.h"
#include "../../exception_report.h"
#include "bc_decode.h"
#include "bc_astc.h"
#include "bc_reference.h"
#include "bc_shader.h"
#include "shaders.h"
#include "runtime.h"
#include "render_prof.h"
#include "worker_metrics.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <string>
#include <stdexcept>
namespace gfxvk {
namespace {
VkDescriptorSetLayout descriptors = VK_NULL_HANDLE;
VkPipelineLayout layout = VK_NULL_HANDLE;
VkPipeline pipeline = VK_NULL_HANDLE;
bool env_on(const char* key) { const char* s=std::getenv(key); return s && std::strcmp(s,"0"); }
void initialize() {
    if(pipeline)return;
    if(!R.computeQueue)exception_report::raise("BC fallback requires a compute-capable graphics queue");
    VkDescriptorSetLayoutBinding bindings[2]{};
    for(uint32_t n=0;n<2;++n)bindings[n]={n,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1,VK_SHADER_STAGE_COMPUTE_BIT,nullptr};
    VkDescriptorSetLayoutCreateInfo di{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    di.bindingCount=2;di.pBindings=bindings;
    vk_check(vkCreateDescriptorSetLayout(R.device,&di,nullptr,&descriptors),"BC descriptor layout");
    VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT,0,16};
    VkPipelineLayoutCreateInfo li{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    li.setLayoutCount=1;li.pSetLayouts=&descriptors;li.pushConstantRangeCount=1;li.pPushConstantRanges=&push;
    vk_check(vkCreatePipelineLayout(R.device,&li,nullptr,&layout),"BC pipeline layout");
    std::string error;auto spirv=vk::compile_compute(bc::shader,&error);
    if(spirv.empty())exception_report::raise("BC compute compile: "+error);
    VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};mi.codeSize=spirv.size()*4;mi.pCode=spirv.data();
    VkShaderModule module=VK_NULL_HANDLE;vk_check(vkCreateShaderModule(R.device,&mi,nullptr,&module),"BC shader module");
    VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};ci.layout=layout;
    ci.stage={VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,nullptr,0,VK_SHADER_STAGE_COMPUTE_BIT,module,"main",nullptr};
    const bool tracing=stutter::trace().enabled();
    const auto cpuStart=tracing?stutter::thread_cpu_now():0;
    const auto started=tracing?stutter::now():0;
    VkResult result=vkCreateComputePipelines(R.device,R.pipelineCache,1,&ci,nullptr,&pipeline);
    if(tracing) {
        const auto ended=stutter::now();
        stutter::trace().add("bc_decode_compute_pipeline",R.frame,0,started,ended,int(result),
                             stutter::cpu_elapsed(cpuStart));
    }
    vkDestroyShaderModule(R.device,module,nullptr);vk_check(result,"BC compute pipeline");
}
// narrow output (mode bit 512): BC4 as R8, BC5 as R8G8 instead of RGBA8 (4x / 2x less memory, the same values: the
// channels RGBA8 held beyond them were the constants 0 and 1 that R8/R8G8 sample as). Rows and slices are padded
// to whole 4x4 blocks (the compute decoder writes a block's row as whole words).
uint32_t narrow_channels(uint32_t mode) { return (mode&512)&&(mode&255)>=4?(mode&255)==5?2u:1u:0u; }
uint64_t output_bytes(uint32_t w,uint32_t h,uint32_t slices,uint32_t mode) {
    const uint32_t ch=narrow_channels(mode);
    return ch?uint64_t((w+3)/4*4)*((h+3)/4*4)*slices*ch:uint64_t(w)*h*slices*4;
}
// the CPU decoder's RGBA8 in the layout of output_bytes (padding pixels 0)
std::vector<uint8_t> reference_output(const std::vector<uint8_t>& blocks,uint32_t w,uint32_t h,uint32_t slices,uint32_t mode) {
    auto rgba=bc::decode(blocks,w,h,slices,mode&~512u);
    const uint32_t ch=narrow_channels(mode);
    if(!ch)return rgba;
    const size_t pw=(w+3)/4*4,ph=(h+3)/4*4;
    std::vector<uint8_t> out(pw*ph*slices*ch,0);
    for(size_t z=0;z<slices;++z)for(size_t y=0;y<h;++y)for(size_t x=0;x<w;++x)for(uint32_t c=0;c<ch;++c)
        out[((z*ph+y)*pw+x)*ch+c]=rgba[((z*h+y)*w+x)*4+c];
    return out;
}
// whether byte n of an output_bytes layout is a real pixel (not padding)
bool output_byte_real(size_t n,uint32_t w,uint32_t h,uint32_t mode) {
    const uint32_t ch=narrow_channels(mode);
    if(!ch)return true;
    const size_t pw=(w+3)/4*4,ph=(h+3)/4*4,px=n/ch;
    return px%pw<w&&px/pw%ph<h;
}
Buffer dispatch(const std::vector<uint8_t>& blocks,uint32_t w,uint32_t h,uint32_t slices,uint32_t mode,bool readback) {
    initialize();
    uint64_t pixels=output_bytes(w,h,slices,mode)/4;
    if(!pixels||pixels>UINT32_MAX/4||pixels*4>R.properties.limits.maxStorageBufferRange||
       blocks.size()>R.properties.limits.maxStorageBufferRange)
        exception_report::raise("BC decode exceeds device buffer limits");
    // one invocation per 4x4 block, 8x8 blocks per workgroup, one workgroup layer per slice
    const uint32_t gx=((w+3)/4+7)/8,gy=((h+3)/4+7)/8;
    const auto& groupLimit=R.properties.limits.maxComputeWorkGroupCount;
    if(gx>groupLimit[0]||gy>groupLimit[1]||slices>groupLimit[2])exception_report::raise("BC decode dispatch exceeds device limits");
    end_encoder();auto cmd=command_buffer();
    Buffer input=create_buffer(blocks.size(),VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    Buffer output=create_buffer(pixels*4,VK_BUFFER_USAGE_STORAGE_BUFFER_BIT|VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
        readback?VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT:VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if(!input.mapped)exception_report::raise("BC input buffer not mapped");
    std::memcpy(input.mapped,blocks.data(),blocks.size());
    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};ai.descriptorPool=R.descriptorPool;ai.descriptorSetCount=1;ai.pSetLayouts=&descriptors;
    VkDescriptorSet set=VK_NULL_HANDLE;vk_check(vkAllocateDescriptorSets(R.device,&ai,&set),"BC descriptor set");
    VkDescriptorBufferInfo buffers[2]={{input.buffer,0,blocks.size()},{output.buffer,0,pixels*4}};
    VkWriteDescriptorSet writes[2]{};
    for(uint32_t i=0;i<2;++i){writes[i]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};writes[i].dstSet=set;writes[i].dstBinding=i;writes[i].descriptorCount=1;writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;writes[i].pBufferInfo=&buffers[i];}
    vkUpdateDescriptorSets(R.device,2,writes,0,nullptr);
    // Coherent host writes are made available by submission. The input is unique to this dispatch.
    record::vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pipeline);
    record::vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,layout,0,1,&set,0,nullptr);
    uint32_t params[]={w,h,slices,mode};record::vkCmdPushConstants(cmd,layout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof params,params);
    record::vkCmdDispatch(cmd,gx,gy,slices);
    derive_dependency(output.use,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_ACCESS_SHADER_WRITE_BIT);
    transition_buffer(output,VK_PIPELINE_STAGE_TRANSFER_BIT|(readback?VK_PIPELINE_STAGE_HOST_BIT:0),
                      VK_ACCESS_TRANSFER_READ_BIT|(readback?VK_ACCESS_HOST_READ_BIT:0));
    defer_buffer(input);return output;
}
// the BC block holding decoded pixel `pixel` (row-major over w x h x slices), as hex: lets a mismatch
// report be replayed against the CPU decoder
std::string block_hex(const std::vector<uint8_t>& blocks,uint32_t w,uint32_t h,uint32_t mode,size_t pixel) {
    const size_t bx=(w+3)/4,by=(h+3)/4,x=pixel%w,y=pixel/w%h,z=pixel/(size_t(w)*h),bytes=bc::block_bytes(mode&255);
    const size_t at=((z*by+y/4)*bx+x/4)*bytes;std::string hex;char digits[3];
    for(size_t n=0;n<bytes&&at+n<blocks.size();++n){std::snprintf(digits,sizeof digits,"%02x",blocks[at+n]);hex+=digits;}
    return hex+" pixel "+std::to_string((y%4)*4+x%4);
}
void verify(Buffer output,const std::vector<uint8_t>& blocks,uint32_t w,uint32_t h,uint32_t slices,uint32_t mode) {
    flush();
    auto reference=reference_output(blocks,w,h,slices,mode);
    auto bytes=static_cast<const uint8_t*>(output.mapped);
    if(!bytes)exception_report::raise("BC verification buffer not mapped");
    for(size_t n=0;n<reference.size();++n)if(bytes[n]!=reference[n]&&output_byte_real(n,w,h,mode)) {
        LOG("[vulkan BC] mismatch mode=%u size=%ux%ux%u byte=%zu GPU=%u CPU=%u",mode,w,h,slices,n,bytes[n],reference[n]);
        exception_report::raise("BC GPU/CPU verification mismatch");
    }
}
// WWHD_VK_BC_VERIFY in the game: compare once the frame's submission has completed (its completion
// callbacks run before its deferred buffers are freed), without draining the GPU mid-frame, which
// would cut the frame's command buffer under the bound draw state.
void verify_when_complete(Buffer output,const std::vector<uint8_t>& blocks,uint32_t w,uint32_t h,uint32_t slices,uint32_t mode) {
    R.completions.push_back([output,blocks,w,h,slices,mode] {
        static uint32_t verified=0,mismatched=0;
        const auto reference=reference_output(blocks,w,h,slices,mode);
        const auto* bytes=static_cast<const uint8_t*>(output.mapped);
        ++verified;
        for(size_t n=0;bytes&&n<reference.size();++n)if(bytes[n]!=reference[n]&&output_byte_real(n,w,h,mode)) {
            if(++mismatched<=20)
                LOG("[vulkan BC] mismatch mode=%u size=%ux%ux%u byte=%zu (pixel %zu) GPU=%u CPU=%u block %s",mode,w,h,slices,n,n/4,
                    bytes[n],reference[n],block_hex(blocks,w,h,mode,n/4).c_str());
            break;
        }
        if(verified==1||verified%200==0)LOG("[vulkan BC] verified %u decoded uploads against the CPU decoder: %u mismatched",verified,mismatched);
    });
}
}
bool bc_decode_required(const FormatInfo& fmt) {
    if(!fmt.compressed)return false;
    if(env_on("WWHD_VK_FORCE_BC_DECODE")||!R.enabledFeatures.textureCompressionBC)return true;
    VkFormatProperties p{};vkGetPhysicalDeviceFormatProperties(R.physicalDevice,fmt.pixel,&p);
    constexpr auto needed=VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT|VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT|
        VK_FORMAT_FEATURE_TRANSFER_SRC_BIT|VK_FORMAT_FEATURE_TRANSFER_DST_BIT;
    return (p.optimalTilingFeatures&needed)!=needed;
}
void bc_decode_upload(Surface* s,uint32_t level,const std::vector<uint8_t>& blocks,uint32_t w,uint32_t h,uint32_t slices) {
    auto start=std::chrono::steady_clock::now();
    uint32_t type=(s->format&63)-0x30;
    uint32_t mode=type|((type>=4&&(s->format&0x200))?256:0);
    const VkFormat px=s->fmt.pixel;
    if(px==VK_FORMAT_R8_UNORM||px==VK_FORMAT_R8_SNORM||px==VK_FORMAT_R8G8_UNORM||px==VK_FORMAT_R8G8_SNORM)mode|=512;
    VkBufferImageCopy copy{};bool volume=s->imageType==VK_IMAGE_TYPE_3D;
    copy.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,level,0,volume?1:slices};copy.imageExtent={w,h,volume?slices:1};
    if(mode&512){copy.bufferRowLength=(w+3)/4*4;copy.bufferImageHeight=(h+3)/4*4;}
    if(px==VK_FORMAT_ASTC_4x4_UNORM_BLOCK||px==VK_FORMAT_ASTC_4x4_SRGB_BLOCK) {
        // BC1 transcoded to ASTC 4x4 on the CPU (bc_astc.h), block for block: no decode, a quarter of RGBA8
        const size_t count=blocks.size()/8;
        Buffer staging=create_buffer(count*16,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if(!staging.mapped){defer_buffer(staging);exception_report::raise("ASTC staging allocation is not mapped");}
        auto* out=static_cast<uint8_t*>(staging.mapped);
        for(size_t i=0;i<count;++i)bc_astc::transcode_bc1(blocks.data()+i*8,out+i*16);
        record::vkCmdCopyBufferToImage(command_buffer(),staging.buffer,s->image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&copy);
        defer_buffer(staging);
        rprof::add_upload(rprof::kUpTexture,blocks.size());
        if(env_on("WWHD_VK_BC_TIMING"))LOG("[vulkan BC] ASTC %ux%ux%u bytes=%zu CPU-transcode-us=%.1f",w,h,slices,blocks.size(),
            std::chrono::duration<double,std::micro>(std::chrono::steady_clock::now()-start).count());
        return;
    }
    if(R.bcCpuDecode) {
        // driver rule (bc_decoder_choice.h): decode on the CPU and upload RGBA8 (R8 / R8G8 for BC4 / BC5) like any
        // other texture
        std::vector<uint8_t> rgba;
        try{rgba=reference_output(blocks,w,h,slices,mode);}
        catch(const std::exception& e){exception_report::raise(std::string("BC CPU decode: ")+e.what());}
        Buffer staging=create_buffer(rgba.size(),VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if(!staging.mapped){defer_buffer(staging);exception_report::raise("BC CPU staging allocation is not mapped");}
        std::memcpy(staging.mapped,rgba.data(),rgba.size());
        record::vkCmdCopyBufferToImage(command_buffer(),staging.buffer,s->image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&copy);
        defer_buffer(staging);
        rprof::add_upload(rprof::kUpTexture,blocks.size());
        if(env_on("WWHD_VK_BC_TIMING"))LOG("[vulkan BC] mode=%u %ux%ux%u bytes=%zu CPU-decode-us=%.1f",mode,w,h,slices,blocks.size(),
            std::chrono::duration<double,std::micro>(std::chrono::steady_clock::now()-start).count());
        return;
    }
    bool check=env_on("WWHD_VK_BC_VERIFY");
    Buffer output=dispatch(blocks,w,h,slices,mode,check);
    record::vkCmdCopyBufferToImage(command_buffer(),output.buffer,s->image,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&copy);
    if(check)verify_when_complete(output,blocks,w,h,slices,mode);
    defer_buffer(output);
    rprof::add_upload(rprof::kUpTexture,blocks.size());
    if(env_on("WWHD_VK_BC_TIMING"))LOG("[vulkan BC] mode=%u %ux%ux%u bytes=%zu CPU-upload-us=%.1f%s",mode,w,h,slices,blocks.size(),
        std::chrono::duration<double,std::micro>(std::chrono::steady_clock::now()-start).count(),check?" (includes the verification copy)":" (dispatch recording only)");
}
// The compute decoder against the CPU decoder on the known-answer blocks of every format
// (bc::known_answer_blocks: every selector in every pixel position of both channel palette modes,
// signed and unsigned). One submission, waited for; run at device creation, before any frame.
// Returns an empty string or the first difference.
static std::string bc_decode_self_test() {
    struct Run { uint32_t mode; std::vector<uint8_t> blocks; Buffer output; };
    std::vector<Run> runs;
    constexpr uint32_t side=64; // 16 x 16 blocks
    for(uint32_t type=1;type<=5;++type)for(uint32_t sign=0;sign<=(type>=4?1u:0u);++sign) {
        for(uint32_t narrow=0;narrow<=(type>=4?1u:0u);++narrow) {
            Run run{type|(sign<<8)|(narrow<<9),bc::known_answer_blocks(type,(side/4)*(side/4)),{}};
            run.output=dispatch(run.blocks,side,side,1,run.mode,true);runs.push_back(std::move(run));
        }
    }
    flush();
    std::string failure;
    for(auto& run:runs) {
        const auto reference=reference_output(run.blocks,side,side,1,run.mode);
        const auto* bytes=static_cast<const uint8_t*>(run.output.mapped);
        for(size_t n=0;failure.empty()&&n<reference.size();++n)if(!bytes||bytes[n]!=reference[n]) {
            char line[160];std::snprintf(line,sizeof line,"mode=%u pixel %zu channel %zu GPU=%u CPU=%u block ",run.mode,n/4,n%4,
                bytes?bytes[n]:0u,reference[n]);
            failure=line+block_hex(run.blocks,side,side,run.mode,n/4);
        }
        defer_buffer(run.output);
    }
    return failure;
}
void bc_decode_startup_check() {
    const char* forced=std::getenv("WWHD_VK_FORCE_BC_DECODE");
    if(R.bcCpuDecode||!R.computeQueue||(R.enabledFeatures.textureCompressionBC&&!(forced&&std::strcmp(forced,"0"))))return;
    const std::string failure=bc_decode_self_test();
    if(failure.empty()){LOG("[vulkan BC] compute decoder self-test passed (known-answer blocks, 7 formats, BC4/BC5 narrow too)");return;}
    // A driver that miscompiles the decoder (Qualcomm v762 did, see bc_shader.h) gets the CPU decoder;
    // WWHD_VK_BC_CPU=0 keeps the compute decoder for diagnosis.
    const char* choice=std::getenv("WWHD_VK_BC_CPU");
    const bool keep=choice&&!std::strcmp(choice,"0");
    LOG("[vulkan BC] compute decoder self-test FAILED (%s); %s",failure.c_str(),
        keep?"keeping the compute decoder (WWHD_VK_BC_CPU=0)":"using the CPU decoder");
    if(!keep)R.bcCpuDecode=true;
}
void bc_decode_shutdown() {
    if(pipeline)vkDestroyPipeline(R.device,pipeline,nullptr);
    if(layout)vkDestroyPipelineLayout(R.device,layout,nullptr);
    if(descriptors)vkDestroyDescriptorSetLayout(R.device,descriptors,nullptr);
    pipeline=VK_NULL_HANDLE;layout=VK_NULL_HANDLE;descriptors=VK_NULL_HANDLE;
}
// BC1 -> ASTC 4x4 (bc_astc.h) checked against the driver's own ASTC decoder: the transcoded blocks are uploaded to an
// ASTC image, blitted (nearest) into an RGBA8 one and read back, and every texel compared with the CPU BC1 decoder.
// Tolerances: opaque texels within 2 per channel (BC's thirds vs ASTC's 21/64 and rounding), 3 in blocks with
// transparent texels (7 bit endpoints); transparent texels must have alpha 0. Skipped when the device can't blit
// from ASTC. Returns an empty string or the first difference.
std::string bc_astc_check() {
    const VkFormat astc=VK_FORMAT_ASTC_4x4_UNORM_BLOCK;
    VkFormatProperties p{};vkGetPhysicalDeviceFormatProperties(R.physicalDevice,astc,&p);
    if(!R.enabledFeatures.textureCompressionASTC_LDR||!(p.optimalTilingFeatures&VK_FORMAT_FEATURE_BLIT_SRC_BIT))return "skipped";
    constexpr uint32_t side=64,count=(side/4)*(side/4);
    // known-answer blocks (every selector in every position, both modes) then random blocks, then crafted three
    // colour blocks: one colour + transparent, all transparent, c0 == c1
    std::vector<uint8_t> blocks=bc::known_answer_blocks(1,count/2);
    uint32_t random=12345;
    while(blocks.size()<count*8){random=random*1664525+1013904223;blocks.push_back(uint8_t(random>>24));}
    auto put=[&](size_t i,uint16_t c0,uint16_t c1,uint32_t idx){uint8_t* b=&blocks[i*8];memcpy(b,&c0,2);memcpy(b+2,&c1,2);memcpy(b+4,&idx,4);};
    put(count-1,0x1234,0x1234,0xFFFF0000u);put(count-2,0x0000,0xFFFF,0xFFFFFFFFu);put(count-3,0x8410,0x8410,0x5555AAAAu);
    put(count-4,0x0000,0x0000,0x0F0F0F0Fu);put(count-5,0x07E0,0xF800,0xC0C0C0C0u);put(count-6,0xF800,0x07E0,0x3333CCCCu);
    std::vector<uint8_t> astcBlocks(count*16);
    for(uint32_t i=0;i<count;++i)bc_astc::transcode_bc1(&blocks[i*8],&astcBlocks[i*16]);
    auto image=[&](VkFormat f,VkImageUsageFlags usage,VkImage& img,VkDeviceMemory& mem){
        VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};ii.imageType=VK_IMAGE_TYPE_2D;ii.format=f;ii.extent={side,side,1};
        ii.mipLevels=1;ii.arrayLayers=1;ii.samples=VK_SAMPLE_COUNT_1_BIT;ii.tiling=VK_IMAGE_TILING_OPTIMAL;ii.usage=usage;
        vk_check(vkCreateImage(R.device,&ii,nullptr,&img),"ASTC check image");
        VkMemoryRequirements req{};vkGetImageMemoryRequirements(R.device,img,&req);
        VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};ai.allocationSize=req.size;
        ai.memoryTypeIndex=memory_type(req.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
        vk_check(vkAllocateMemory(R.device,&ai,nullptr,&mem),"ASTC check memory");vk_check(vkBindImageMemory(R.device,img,mem,0),"ASTC check bind");
    };
    VkImage src=VK_NULL_HANDLE,dst=VK_NULL_HANDLE;VkDeviceMemory srcMem=VK_NULL_HANDLE,dstMem=VK_NULL_HANDLE;
    image(astc,VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT,src,srcMem);
    image(VK_FORMAT_R8G8B8A8_UNORM,VK_IMAGE_USAGE_TRANSFER_DST_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT,dst,dstMem);
    Buffer upload=create_buffer(astcBlocks.size(),VK_BUFFER_USAGE_TRANSFER_SRC_BIT,VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    std::memcpy(upload.mapped,astcBlocks.data(),astcBlocks.size());
    Buffer readback=create_readback_buffer(size_t(side)*side*4);
    end_encoder();auto cmd=command_buffer();
    auto barrier=[&](VkImage img,VkImageLayout from,VkImageLayout to,VkAccessFlags srcA,VkAccessFlags dstA){
        VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};b.oldLayout=from;b.newLayout=to;b.srcAccessMask=srcA;b.dstAccessMask=dstA;
        b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;b.image=img;b.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};
        record::vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT|VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,0,0,nullptr,0,nullptr,1,&b);
    };
    barrier(src,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,0,VK_ACCESS_TRANSFER_WRITE_BIT);
    barrier(dst,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,0,VK_ACCESS_TRANSFER_WRITE_BIT);
    VkBufferImageCopy copy{};copy.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};copy.imageExtent={side,side,1};
    record::vkCmdCopyBufferToImage(cmd,upload.buffer,src,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&copy);
    barrier(src,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT);
    VkImageBlit blit{};blit.srcSubresource=blit.dstSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
    blit.srcOffsets[1]=blit.dstOffsets[1]={int32_t(side),int32_t(side),1};
    record::vkCmdBlitImage(cmd,src,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,dst,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&blit,VK_FILTER_NEAREST);
    barrier(dst,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_TRANSFER_READ_BIT);
    record::vkCmdCopyImageToBuffer(cmd,dst,VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,readback.buffer,1,&copy);
    VkBufferMemoryBarrier hb{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};hb.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT;hb.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
    hb.srcQueueFamilyIndex=hb.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;hb.buffer=readback.buffer;hb.size=VK_WHOLE_SIZE;
    record::vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,0,nullptr,1,&hb,0,nullptr);
    flush();
    const auto reference=bc::decode(blocks,side,side,1,1);
    const auto* got=static_cast<const uint8_t*>(readback.mapped);
    std::string failure;uint32_t worst=0;
    for(uint32_t px=0;px<side*side&&failure.empty();++px) {
        const uint32_t x=px%side,y=px/side,block=(y/4)*(side/4)+x/4;
        const uint8_t* bc=&blocks[block*8];
        const uint16_t c0=uint16_t(bc[0]|(bc[1]<<8)),c1=uint16_t(bc[2]|(bc[3]<<8));
        uint32_t idx;memcpy(&idx,bc+4,4);bool transparentBlock=false;
        if(c0<=c1)for(uint32_t i=0;i<16;++i)if(((idx>>(2*i))&3)==3)transparentBlock=true;
        const uint8_t* want=&reference[px*4];const uint8_t* have=&got[px*4];
        const uint32_t tolerance=transparentBlock?3:2;
        if(want[3]==0){if(have[3]!=0)failure="transparent texel has alpha "+std::to_string(have[3]);}
        else for(int c=0;c<4;++c){
            const uint32_t d=uint32_t(std::abs(int(want[c])-int(have[c])));worst=std::max(worst,d);
            if(d>tolerance)failure="channel "+std::to_string(c)+" "+std::to_string(have[c])+" vs BC "+std::to_string(want[c]);
        }
        if(!failure.empty())failure+=" at texel "+std::to_string(px)+", block "+block_hex(blocks,side,side,1,px);
    }
    defer_buffer(upload);defer_buffer(readback);
    vkDestroyImage(R.device,src,nullptr);vkDestroyImage(R.device,dst,nullptr);vkFreeMemory(R.device,srcMem,nullptr);vkFreeMemory(R.device,dstMem,nullptr);
    if(failure.empty())LOG("[vulkan BC] BC1 -> ASTC 4x4 matches the BC1 decoder on %u blocks (largest difference %u)",count,worst);
    return failure;
}
void bc_decode_smoke() {
    if(const std::string astcFailure=bc_astc_check();!astcFailure.empty()&&astcFailure!="skipped")
        exception_report::raise("BC1 -> ASTC check: "+astcFailure);
    uint32_t dispatches=0;
    for(uint32_t type=1;type<=5;++type)for(uint32_t sign=0;sign<=(type>=4?1u:0u);++sign)
        for(auto shape:{std::array<uint32_t,3>{4,4,1},{7,5,2},{1,1,1},{70,37,3}}) {  // the last: several workgroups
            auto [w,h,z]=shape;std::vector<uint8_t> blocks(size_t((w+3)/4)*((h+3)/4)*z*bc::block_bytes(type));
            for(uint32_t seed=0;seed<12;++seed) {
                uint32_t random=seed+1;
                for(auto& byte:blocks){random=random*1664525+1013904223;byte=uint8_t(random>>24);}
                if(seed==0)std::fill(blocks.begin(),blocks.end(),0);
                if(seed==1)std::fill(blocks.begin(),blocks.end(),255);
                if(seed==2){auto known=bc::known_answer_blocks(type,uint32_t(blocks.size()/bc::block_bytes(type)));blocks=known;}
                for(uint32_t narrow=0;narrow<=(type>=4?1u:0u);++narrow) {
                    const uint32_t mode=type|(sign<<8)|(narrow<<9);
                    Buffer output=dispatch(blocks,w,h,z,mode,true);
                    verify(output,blocks,w,h,z,mode);defer_buffer(output);++dispatches;
                }
            }
        }
    LOG("[vulkan BC] synthetic GPU/CPU checks PASS (7 formats, tails, layers, workgroups, %u dispatches)",dispatches);
}
}
