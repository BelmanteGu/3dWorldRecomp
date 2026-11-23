// In-between pictures with the camera halfway (Super Mario 3D World 120 fps, sm3dw/interp_sm3dw.cpp),
// on the render thread.
//
// An in-between frame replays the display list the last logic step recorded. Its draws read the
// camera from uniform blocks in guest memory; which blocks a list reads is only known when it runs
// (some are set by nested lists recorded elsewhere), so the blend happens where the renderer reads
// a uniform block: a block that holds a camera (classify()) is replaced by the camera halfway
// between the one the previous logic frame's draws read (matched by kind, projection and nearest
// view) and the one the replayed list holds. Guest memory is not touched.
//
// Camera blocks (little-endian vec4 rows, as the GPU reads them):
//   0x150 bytes (vertex/pixel block 8): 0x00 view (3x4, then 0 0 0 1), 0x40 projection,
//     0x80 projection * view, 0xC0 view rotation transposed, 0x100 view row 2, 0x110 camera position
//   0x100 bytes (block 2 of some passes): 0x00 projection * view, 0x40 inverse view (3x4)
// All on the render thread.
#pragma once
#include <cstddef>
#include <cstdint>
#include <vector>

namespace gx2::interp_camera {
// OP_INTERP_FRAME: the frame being executed is an in-between one (blend t), or a logic one (t < 0)
void begin_frame(bool hold, float t);
void end_frame();  // OP_SWAP
// a uniform block the renderer is about to read: the bytes to use instead, or nullptr
const void* substitute(const void* bytes, size_t size, uint32_t guestAddr);
// uniform registers (ALU constants) set while an in-between frame runs: matrices that are
// projection * view * model for a camera replaced in this frame get the camera halfway (into out,
// a copy of values); false when nothing changed
bool regs_active();
bool filter_regs(uint32_t first, const uint32_t* values, uint32_t n, std::vector<uint32_t>& out);
// statistics for the [fps120] report: blocks and register matrices replaced in in-between frames
uint64_t replaced_blocks();
uint64_t replaced_regs();
}  // namespace gx2::interp_camera
