// The GamePad microphone (docs/mic.md): libmic (runtime/src/hle/mic.cpp) reads from here, the option and
// the blow button write here, the host's audio capture (platform/mic_sdl.cpp) plugs in here.
#pragma once
#include <cstdint>

namespace mic {

enum Mode : int { kOff = 0, kMicrophone = 1, kButton = 2 };

// ---- the option (any thread). Off: MICInit fails as on a console without a GamePad microphone, so the
// game runs without one (the default). Microphone: the host's capture device, plus the blow button/key.
// Button: only the blow button/key (no capture, no permission). WWHD_MIC=off|mic|button sets the start
// value; WWHD_MIC_FAKE=noise blows all the time (tests).
Mode mode();
void set_mode(Mode m);
Mode parse_mode(const char* s);  // "off" / "mic" / "button" (also "0"/"1"/"2"); unknown -> kOff
const char* mode_name(Mode m);
int sensitivity();               // percent, 25..400
void set_sensitivity(int percent);

// the on-screen "blow" button (Android) and the desktop key (B): held = a synthetic blow
void set_blow_touch(bool down);
void set_blow_key(bool down);
int blow_key();                  // keycodes.h code of the key (WWHD_MIC_KEY, default B)

// ---- the host's capture device: 32 kHz mono float. start() may block (Android asks for the permission
// there if it was not granted); it runs on mic's own thread.
struct Capture {
    virtual ~Capture() = default;
    virtual bool start() = 0;
    virtual void stop() = 0;
    virtual uint32_t read(float* out, uint32_t max) = 0;  // what was captured since, at most max samples
    virtual uint32_t queued() = 0;                         // samples waiting
    virtual void drop(uint32_t n) = 0;                     // discard the oldest n
};
void set_capture(Capture* c);    // the SDL host registers one at start

// ---- for the HLE (guest threads)
bool device_present();           // MICInit may succeed: a mode that can deliver samples
// fills `max` samples or fewer of 32 kHz PCM for `elapsed_us` of real time since the last call
uint32_t produce(int16_t* out, uint32_t max, uint64_t elapsed_us);
void count_consumed(uint32_t n);
bool log_enabled();              // WWHD_MIC_LOG=1

}  // namespace mic
