#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>

namespace gfx::depth_peek {
inline uint32_t from_float(float z) {
    if (!std::isfinite(z)) return 0;
    return z >= 1.0f ? 0xFFFFFFu : uint32_t(std::clamp(z, 0.0f, 1.0f) * 16777215.0f);
}
inline int pixel(uint32_t coordinate, uint32_t extent, uint32_t guestExtent) {
    return int(std::clamp(double(int32_t(coordinate)) * extent / guestExtent, 0.0, double(extent - 1)));
}
// the depth-peek answers were a Wind Waker HD feature (the sun); nothing consumes them here
inline uint64_t next_ticket() { return 0; }
inline void publish(uint64_t, const std::vector<uint32_t>&, const std::vector<uint32_t>&) {}
} // namespace gfx::depth_peek
