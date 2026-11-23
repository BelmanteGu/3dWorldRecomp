// libmic: the GamePad microphone (docs/mic.md). The game's sead::MicDevice (0x02351F00..0x02352780) inits
// it with a 3072-sample ring buffer in its own memory, opens it, polls MICGetStatus and consumes 96 samples
// at a time with MICSetDataConsumed; sead::MicConnectThread calls the init again while it fails, so the
// option can be switched on while the game runs. The host writes 32 kHz big-endian PCM16 into the ring when
// the game asks for the status (runtime/src/mic.cpp decides what it hears).
//
// Semantics (states, flags, result codes) follow Cemu's libmic (Cafe/OS/libs/mic/mic.cpp, MPL-2.0) checked
// against the game's own use; no Cemu code is copied.
#include "../mic.h"
#include "../mic_core.h"
#include "../runtime.h"

#include <algorithm>
#include <chrono>
#include <mutex>

namespace {

struct Drc {
    bool inited = false, open = false;
    uint32_t samples = 0;  // guest address of the ring buffer
    mic::Ring ring;
    uint32_t gain_db = 0, mute = 0, echo = 1, auto_select = 1;
    std::chrono::steady_clock::time_point last;
};
std::mutex g_mu;
Drc g_drc;  // the one GamePad (handle 0); a second one (handle 1) is never connected
int g_last_init = 1;  // 1: not tried, 0: failed, 2: succeeded (logged on change)

// the samples that arrived since the last call go into the ring buffer
void feed(Drc& d) {
    const auto now = std::chrono::steady_clock::now();
    const uint64_t us = std::min<uint64_t>(std::chrono::duration_cast<std::chrono::microseconds>(now - d.last).count(), 200000);
    d.last = now;
    if (!d.open) return;
    int16_t tmp[4096];
    const uint32_t n = mic::produce(tmp, std::min<uint32_t>(d.ring.space(), 4096), us);
    if (d.mute) std::fill(tmp, tmp + n, int16_t(0));
    d.ring.push(tmp, n, [&](uint32_t i, int16_t s) { st16(d.samples + 2 * i, uint16_t(s)); });
}

int32_t check(uint32_t handle) {
    if (handle != 0) return mic::kBadParam;
    if (!g_drc.inited) return mic::kNotInitialized;
    return mic::kOk;
}

}  // namespace

// MICInit(instance, unused, MICWorkMemory* {u32 sampleCount; void* samples}, int32* error) -> handle or -1
HLE(mic, MICInit) {
    const uint32_t instance = arg(c, 0), work = arg(c, 2), error = arg(c, 3);
    std::lock_guard lk(g_mu);
    int32_t err = mic::kOk;
    const uint32_t size = work ? ld32(work) : 0, samples = work ? ld32(work + 4) : 0;
    if (instance != 0 || !mic::device_present()) err = mic::kNotConnected;
    else if (size < 2 || !samples) err = mic::kBadParam;
    if (const int now = err ? 0 : 2; now != g_last_init) {
        g_last_init = now;
        if (err) LOG("[mic] MICInit(%u): no microphone (option %s): the game goes on without one", instance, mic::mode_name(mic::mode()));
        else LOG("[mic] MICInit succeeded: ring of %u samples at 0x%08X, option %s", size, samples, mic::mode_name(mic::mode()));
    }
    if (error) st32(error, uint32_t(err));
    if (err) {
        ret(c, 0xFFFFFFFFu);
        return;
    }
    // (again after the game dropped a disconnected device without MICUninit: start over)
    g_drc.inited = true;
    g_drc.open = false;
    g_drc.samples = samples;
    g_drc.ring.reset(size);
    g_drc.last = std::chrono::steady_clock::now();
    ret(c, 0);
}

HLE(mic, MICOpen) {
    std::lock_guard lk(g_mu);
    int32_t r = check(arg(c, 0));
    if (r == mic::kOk && g_drc.open) r = mic::kAlreadyOpen;
    if (r == mic::kOk && !mic::device_present()) r = mic::kNotConnected;
    if (r == mic::kOk) {
        g_drc.open = true;
        g_drc.ring.read = g_drc.ring.write = 0;
        g_drc.last = std::chrono::steady_clock::now();
        LOG("[mic] MICOpen: the game listens");
    }
    ret(c, uint32_t(r));
}

HLE(mic, MICClose) {
    std::lock_guard lk(g_mu);
    int32_t r = check(arg(c, 0));
    if (r == mic::kOk && !g_drc.open) r = mic::kNotOpen;
    if (r == mic::kOk) g_drc.open = false;
    ret(c, uint32_t(r));
}

HLE(mic, MICUninit) {
    std::lock_guard lk(g_mu);
    const int32_t r = check(arg(c, 0));
    if (r == mic::kOk) g_drc = Drc{};
    ret(c, uint32_t(r));
}

// MICGetStatus(handle, MICStatus* {u32 flags; u32 available; u32 readIndex})
HLE(mic, MICGetStatus) {
    const uint32_t out = arg(c, 1);
    std::lock_guard lk(g_mu);
    const int32_t r = check(arg(c, 0));
    if (r == mic::kOk) {
        feed(g_drc);
        // switching the option off disconnects the "GamePad microphone": the game drops it and inits again
        const uint32_t flags = mic::kFlagPcm16 | (g_drc.open ? mic::kFlagOpen : 0) | (mic::device_present() ? mic::kFlagConnected : 0);
        if (out) {
            st32(out, flags);
            st32(out + 4, g_drc.ring.available());
            st32(out + 8, g_drc.ring.read);
        }
    }
    ret(c, uint32_t(r));
}

// MICGetState(handle, id, u32* value)
HLE(mic, MICGetState) {
    const uint32_t id = arg(c, 1), out = arg(c, 2);
    std::lock_guard lk(g_mu);
    int32_t r = check(arg(c, 0));
    uint32_t v = 0;
    if (r == mic::kOk) switch (id) {
        case mic::kStateSampleRate: v = mic::kRate; break;
        case mic::kStateGainDb: v = g_drc.gain_db; break;
        case mic::kStateGainMin: v = 0x0000; break;  // S7.8 dB; the game clamps its gain between these
        case mic::kStateGainMax: v = 0x0200; break;
        case mic::kStateGainStep: v = 0x0001; break;
        case mic::kStateMute: v = g_drc.mute; break;
        case mic::kStateEchoCancellation: v = g_drc.echo; break;
        case mic::kStateAutoSelection: v = g_drc.auto_select; break;
        default: r = mic::kBadParam;
    }
    if (r == mic::kOk && out) st32(out, v);
    ret(c, uint32_t(r));
}

// MICSetState(handle, id, value)
HLE(mic, MICSetState) {
    const uint32_t id = arg(c, 1), v = arg(c, 2);
    std::lock_guard lk(g_mu);
    int32_t r = check(arg(c, 0));
    if (r == mic::kOk) switch (id) {
        case mic::kStateGainDb: g_drc.gain_db = v; break;
        case mic::kStateMute: g_drc.mute = v != 0; break;
        case mic::kStateEchoCancellation: g_drc.echo = v != 0; break;
        case mic::kStateAutoSelection: g_drc.auto_select = v != 0; break;
        default: r = mic::kBadParam;
    }
    if (mic::log_enabled()) LOG("[mic] MICSetState(%u, 0x%X) = %d", id, v, r);
    ret(c, uint32_t(r));
}

// MICSetDataConsumed(handle, samples)
HLE(mic, MICSetDataConsumed) {
    const uint32_t n = arg(c, 1);
    std::lock_guard lk(g_mu);
    int32_t r = check(arg(c, 0));
    if (r == mic::kOk && !g_drc.open) r = mic::kNotOpen;
    if (r == mic::kOk) {
        r = g_drc.ring.consume(n);
        mic::count_consumed(n);
    }
    ret(c, uint32_t(r));
}
