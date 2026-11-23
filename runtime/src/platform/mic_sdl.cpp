// The host microphone for the GamePad's (mic.h): SDL3's default recording device, converted by SDL to
// 32 kHz mono float. On Android SDL opens it through AAudio/OpenSL ES and needs RECORD_AUDIO, which the app
// asks for when the player picks "Microfone" in the menu (WwhdActivity); without it opening fails and the
// game runs without a microphone.
#include "../mic.h"
#include "../mic_core.h"
#include "../runtime.h"

#include <SDL3/SDL.h>

#include <chrono>
#include <thread>

namespace {

class SdlCapture final : public mic::Capture {
public:
    bool start() override {
        // the capture thread can get here before the renderer has started SDL
        for (int i = 0; i < 200 && !SDL_WasInit(SDL_INIT_AUDIO); i++) std::this_thread::sleep_for(std::chrono::milliseconds(50));
        if (!SDL_WasInit(SDL_INIT_AUDIO) && !SDL_InitSubSystem(SDL_INIT_AUDIO)) {
            LOG("[mic] SDL audio: %s", SDL_GetError());
            return false;
        }
        const SDL_AudioSpec spec{SDL_AUDIO_F32, 1, int(mic::kRate)};
        stream_ = SDL_OpenAudioDeviceStream(SDL_AUDIO_DEVICE_DEFAULT_RECORDING, &spec, nullptr, nullptr);
        if (!stream_) {
            LOG("[mic] no recording device: %s", SDL_GetError());
            return false;
        }
        SDL_AudioSpec dev{};
        int frames = 0;
        if (SDL_GetAudioDeviceFormat(SDL_GetAudioStreamDevice(stream_), &dev, &frames))
            LOG("[mic] recording device \"%s\": %d Hz, %d channel(s), %d-frame buffer (converted to 32000 Hz mono)",
                SDL_GetAudioDeviceName(SDL_GetAudioStreamDevice(stream_)) ? SDL_GetAudioDeviceName(SDL_GetAudioStreamDevice(stream_)) : "?",
                dev.freq, dev.channels, frames);
        SDL_ResumeAudioStreamDevice(stream_);
        return true;
    }
    void stop() override {
        if (stream_) SDL_DestroyAudioStream(stream_);
        stream_ = nullptr;
    }
    uint32_t read(float* out, uint32_t max) override {
        if (!stream_ || !max) return 0;
        const int got = SDL_GetAudioStreamData(stream_, out, int(max * sizeof(float)));
        return got > 0 ? uint32_t(got) / sizeof(float) : 0;
    }
    uint32_t queued() override {
        const int n = stream_ ? SDL_GetAudioStreamAvailable(stream_) : 0;
        return n > 0 ? uint32_t(n) / sizeof(float) : 0;
    }
    void drop(uint32_t n) override {
        float tmp[1024];
        while (n) {
            const uint32_t got = read(tmp, n < 1024 ? n : 1024);
            if (!got) break;
            n -= got;
        }
    }

private:
    SDL_AudioStream* stream_ = nullptr;
};

SdlCapture g_capture;
struct Register {
    Register() { mic::set_capture(&g_capture); }
} g_register;

}  // namespace
