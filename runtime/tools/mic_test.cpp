// mic_core.h: the libmic ring buffer as the game drives it (sead::MicDevice: 3072 samples, 96 consumed at a
// time), the blow filter (a blow passes, speech and room noise do not) and the synthetic blow.
#include "mic_core.h"

#include <cassert>
#include <cmath>
#include <cstdio>
#include <vector>

using namespace mic;

static float peak_of(const std::vector<float>& v, size_t from) {
    float p = 0;
    for (size_t i = from; i < v.size(); i++) p = std::max(p, std::fabs(v[i]));
    return p;
}
static float rms_of(const std::vector<float>& v, size_t from) {
    double s = 0;
    for (size_t i = from; i < v.size(); i++) s += double(v[i]) * v[i];
    return float(std::sqrt(s / double(v.size() - from)));
}

static void ring() {
    Ring r;
    r.reset(3072);
    std::vector<int16_t> mem(3072, 0);
    auto put = [&](uint32_t i, int16_t s) { mem[i] = s; };
    assert(r.available() == 0 && r.space() == 3071);
    std::vector<int16_t> in(1000);
    for (int i = 0; i < 1000; i++) in[i] = int16_t(i);
    assert(r.push(in.data(), 1000, put) == 1000);
    assert(r.available() == 1000);
    // the game: 96 at a time while at least 96 are there
    uint32_t got = 0;
    while (r.available() >= 96) {
        assert(mem[r.read] == int16_t(got));
        assert(r.consume(96) == kOk);
        got += 96;
    }
    assert(got == 960 && r.available() == 40 && r.read == 960);
    // wrap around the end
    for (int k = 0; k < 3; k++) assert(r.push(in.data(), 1000, put) == 1000);
    assert(r.write == (4000 % 3072));
    assert(r.available() == 3040);
    // full: the rest is dropped, the read side is never overrun
    assert(r.push(in.data(), 1000, put) == 31);
    assert(r.available() == 3071 && r.space() == 0);
    // more than available: an error and the read index jumps to the write index
    assert(r.consume(5000) == kOverrun);
    assert(r.available() == 0 && r.read == r.write);
    std::printf("ring ok\n");
}

// a signal through the filter; returns the output
static std::vector<float> run(BlowFilter& f, const std::vector<float>& in) {
    std::vector<float> out(in.size());
    for (size_t i = 0; i < in.size(); i++) out[i] = f.process(in[i]);
    return out;
}

static std::vector<float> room(size_t n, float level, uint32_t seed = 7) {  // quiet broadband noise
    std::vector<float> v(n);
    for (auto& x : v) {
        seed = seed * 1664525u + 1013904223u;
        x = level * float(int32_t(seed)) / 2147483648.0f;
    }
    return v;
}

// wind on a microphone: noise with nearly all of its energy under ~300 Hz (two one-pole low-passes at 250 Hz),
// scaled to the RMS `level`
static std::vector<float> blow(size_t n, float level, uint32_t seed = 3) {
    std::vector<float> v(n);
    const float k = 1.0f - std::exp(-2.0f * 3.14159265f * 250.0f / float(kRate));
    float a = 0, b = 0;
    for (auto& x : v) {
        seed = seed * 1664525u + 1013904223u;
        a += k * (float(int32_t(seed)) / 2147483648.0f - a);
        b += k * (a - b);
        x = b;
    }
    const float r = rms_of(v, 0);
    for (auto& x : v) x *= level / r;
    return v;
}

static std::vector<float> voice(size_t n, float level) {  // a vowel: 140 Hz with formant-like harmonics
    std::vector<float> v(n);
    const float f0 = 140.0f, w = 2.0f * 3.14159265f / float(kRate);
    const float amp[] = {0.15f, 0.25f, 0.45f, 0.9f, 1.0f, 0.8f, 0.5f, 0.3f, 0.5f, 0.6f, 0.4f, 0.3f, 0.2f, 0.2f, 0.15f, 0.1f};
    for (size_t i = 0; i < n; i++) {
        float x = 0;
        for (int h = 0; h < 16; h++) x += amp[h] * std::sin(w * f0 * (h + 1) * float(i));
        v[i] = level * x / 4.0f;
    }
    return v;
}

static void filter() {
    const size_t sec = kRate;
    // room noise only: silence for the game
    {
        BlowFilter f;
        auto out = run(f, room(2 * sec, 0.01f));
        assert(peak_of(out, sec) < 1e-3f);
        std::printf("room noise: peak %.5f\n", peak_of(out, sec));
    }
    // a soft blow on a phone after a second of room noise: through, brought up by the AGC
    {
        BlowFilter f;
        run(f, room(sec, 0.01f));
        auto out = run(f, blow(sec, 0.08f));
        const float r = rms_of(out, sec / 4);
        std::printf("soft blow: rms in 0.08, out %.3f, peak %.3f, low-band share %.2f\n", r, peak_of(out, sec / 4), f.env_low / f.env);
        assert(r > 0.2f);
        assert(peak_of(out, sec / 4) > 0.5f);
    }
    // a long blow stays open (the noise floor does not chase it)
    {
        BlowFilter f;
        run(f, room(sec, 0.01f));
        auto out = run(f, blow(5 * sec, 0.2f));
        const float r = rms_of(out, 4 * sec);
        std::printf("long blow, last second: rms %.3f\n", r);
        assert(r > 0.2f);
    }
    // speech at a normal level: kept out
    {
        BlowFilter f;
        run(f, room(sec, 0.01f));
        auto out = run(f, voice(2 * sec, 0.35f));
        const float r = rms_of(out, sec / 4);
        std::printf("speech: rms in %.3f, out %.4f, low-band share %.2f\n", rms_of(voice(sec, 0.35f), 0), r, f.env_low / f.env);
        assert(r < 0.02f);
    }
    // sensitivity scales the result; the gate off passes everything
    {
        BlowFilter a, b;
        b.sensitivity = 2.0f;
        run(a, room(sec, 0.01f));
        run(b, room(sec, 0.01f));
        auto oa = run(a, blow(sec, 0.08f)), ob = run(b, blow(sec, 0.08f));
        const float ra = rms_of(oa, sec / 2), rb = rms_of(ob, sec / 2);
        assert(std::fabs(rb / ra - 2.0f) < 0.05f);
        BlowFilter g;
        g.gate = false;
        auto og = run(g, voice(sec, 0.2f));
        assert(rms_of(og, sec / 2) > 0.05f);
    }
    std::printf("filter ok\n");
}

static void synth() {
    BlowSynth s;
    std::vector<float> on(kRate), off(kRate);
    for (auto& x : on) x = s.next(true);
    for (auto& x : off) x = s.next(false);
    const float r = rms_of(on, kRate / 4);
    std::printf("synthetic blow: rms %.3f peak %.3f; 0.75 s after the release: peak %.5f\n", r, peak_of(on, 0), peak_of(off, kRate * 3 / 4));
    assert(r > 0.25f && r < 0.45f);
    assert(peak_of(off, kRate * 3 / 4) < 5e-3f);
    assert(to_pcm(2.0f) == 32767 && to_pcm(-2.0f) == -32767 && to_pcm(0.5f) == 16384);
    std::printf("synth ok\n");
}

int main() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    ring();
    filter();
    synth();
    std::printf("mic_test: all ok\n");
    return 0;
}
