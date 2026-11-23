// The GamePad microphone's logic without the host or the guest (docs/mic.md): the libmic ring buffer
// bookkeeping, the blow filter for a real microphone (noise gate, light AGC) and the synthetic blow.
// Header-only so runtime/tools/mic_test.cpp tests it alone.
#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace mic {

constexpr uint32_t kRate = 32000;  // libmic: PCM16 mono, 32 kHz, big-endian in guest memory

// libmic results (MICInit reports through its error out-parameter and returns -1)
enum : int32_t {
    kOk = 0,
    kBadParam = -2,
    kAlreadyOpen = -5,
    kNotOpen = -6,
    kNotInitialized = -7,
    kNotConnected = -8,
    kOverrun = -81,  // MICSetDataConsumed: more than available (the read index jumps to the write index)
};
// MICStatus.flags
enum : uint32_t { kFlagPcm16 = 1, kFlagOpen = 2, kFlagConnected = 4 };
// MICGetState / MICSetState ids
enum : uint32_t {
    kStateSampleRate = 0,
    kStateGainDb = 1,  // S7.8 dB
    kStateGainMin = 2,
    kStateGainMax = 3,
    kStateGainStep = 4,
    kStateMute = 5,
    kStateEchoCancellation = 7,
    kStateAutoSelection = 8,
};

// The ring buffer the game hands to MICInit ({u32 sampleCount; u32 samples}): the host writes at `write`,
// the game reads from `read` (MICGetStatus) and moves it on with MICSetDataConsumed. One slot stays
// empty, so available() == size means nothing; input that does not fit is dropped (the game was not
// reading: it is paused, or the stage has nothing listening).
struct Ring {
    uint32_t size = 0, read = 0, write = 0;
    void reset(uint32_t n) { size = n; read = write = 0; }
    uint32_t available() const { return size ? (size + write - read) % size : 0; }
    uint32_t space() const { return size ? size - 1 - available() : 0; }
    // put(index, sample) stores one sample at the ring index; returns how many fitted
    template <class Put> uint32_t push(const int16_t* s, uint32_t n, Put&& put) {
        n = std::min(n, space());
        for (uint32_t i = 0; i < n; i++) {
            put(write, s[i]);
            write = write + 1 == size ? 0 : write + 1;
        }
        return n;
    }
    int32_t consume(uint32_t n) {
        if (n > available()) {
            read = write;
            return kOverrun;
        }
        read = size ? (read + n) % size : 0;
        return kOk;
    }
};

inline float coef(float seconds) { return 1.0f - std::exp(-1.0f / (seconds * float(kRate))); }  // one-pole step
inline int16_t to_pcm(float x) {
    x = std::clamp(x, -1.0f, 1.0f) * 32767.0f;
    return int16_t(std::lrint(x));
}

// A real microphone (32 kHz mono float -1..1) to what the game should hear. Blowing on a microphone is loud,
// broadband turbulence with most of its energy at the bottom of the spectrum; speech and room noise are
// quieter or carry their energy higher up. So the gate opens only when the level is well above the learnt
// noise floor AND the share of low-band energy is high; while open, a light AGC (at most +12 dB) brings a
// soft phone-microphone blow up to the level a GamePad blow has, and the sensitivity scales the result.
// Closed, the game hears silence.
struct BlowFilter {
    // settings
    float sensitivity = 1.0f;  // the menu's 25..400 %
    float gate_ratio = 6.0f;   // level over the noise floor (amplitude ratio) that opens the gate
    float min_level = 0.02f;   // ... and an absolute minimum (RMS, full scale = 1)
    float low_share = 0.50f;   // low-band (< ~400 Hz) share of the energy that counts as blowing
    float agc_target = 0.35f;  // RMS the AGC aims for while blowing
    float agc_max = 4.0f;      // +12 dB at most
    bool gate = true;          // false: everything goes through (WWHD_MIC_GATE=0, for tests)

    // state
    float dc_x = 0, dc_y = 0, low = 0, env = 0, env_low = 0, noise = 1e-4f, open = 0, agc = 1, hold = 0, blow_time = 0;
    float level = 0;  // last RMS (for the logs)

    float process(float x) {
        static const float k_dc = 1.0f - 2.0f * 3.14159265f * 60.0f / float(kRate);  // DC / rumble high-pass
        static const float k_low = 1.0f - std::exp(-2.0f * 3.14159265f * 400.0f / float(kRate));
        static const float k_env = coef(0.03f), k_floor_down = coef(0.3f), k_floor_up = coef(6.0f);
        static const float k_open = coef(0.004f), k_close = coef(0.08f), k_agc = coef(0.25f);
        const float y = x - dc_x + k_dc * dc_y;
        dc_x = x;
        dc_y = y;
        low += k_low * (y - low);
        env += k_env * (y * y - env);
        env_low += k_env * (low * low - env_low);
        level = std::sqrt(env);
        const bool loud = level > std::max(min_level, gate_ratio * std::sqrt(noise));
        const bool blowing = loud && env_low > low_share * env;
        if (blowing) {
            hold = 0.06f * float(kRate);  // keep open 60 ms over short dips
            blow_time += 1;
        } else {
            if (hold > 0) hold -= 1;
            blow_time = 0;
        }
        // the noise floor follows the room, not the blow (unless "blowing" lasts so long it must be the room)
        if (hold <= 0 || blow_time > 8.0f * float(kRate)) noise += (env < noise ? k_floor_down : k_floor_up) * (env - noise);
        noise = std::max(noise, 1e-8f);
        const float want = !gate || hold > 0 ? 1.0f : 0.0f;
        open += (want > open ? k_open : k_close) * (want - open);
        if (blowing && level > 1e-4f) agc += k_agc * (std::clamp(agc_target / level, 1.0f, agc_max) - agc);
        return y * open * agc * sensitivity;
    }
};

// The on-screen button / key: broadband noise low-passed at ~800 Hz with a 30 ms attack and 120 ms release,
// at about a third of full scale RMS (peaks near full scale): what a firm blow on the GamePad looks like.
struct BlowSynth {
    uint32_t rng = 0x12345678u;
    float lp = 0, env = 0;
    float level = 0.33f;
    float next(bool on) {
        static const float k_lp = 1.0f - std::exp(-2.0f * 3.14159265f * 800.0f / float(kRate));
        static const float k_on = coef(0.03f), k_off = coef(0.12f);
        static const float norm = 1.0f / std::sqrt(k_lp / (2.0f - k_lp) / 3.0f);  // low-passed uniform noise to RMS 1
        rng = rng * 1664525u + 1013904223u;
        const float white = float(int32_t(rng)) / 2147483648.0f;
        lp += k_lp * (white - lp);
        env += (on ? k_on : k_off) * ((on ? 1.0f : 0.0f) - env);
        return lp * norm * level * env;
    }
};

}  // namespace mic
