# GamePad microphone (blowing) — issue #19

Super Mario 3D World reads the Wii U GamePad's microphone through libmic. The runtime now implements libmic
(`runtime/src/hle/libmic.cpp`) and feeds it from the host's microphone (SDL3 recording device, desktop and
Android), from an on-screen "blow" button / a key, or from a synthetic blow for tests. It is **off by default**:
with the option off `MICInit` fails exactly like before and the game runs without a microphone.

## What the game does with it

Found in the recompiled code (`build/gen3dw`, not versioned) and the RPX/stage data:

| Where | What |
|---|---|
| `sead::MicDevice::init` `0x02351F00` | `MICInit(0, 0, &{0xC00, buf}, &err)`: a **3072-sample** ring buffer in the game's own memory (`this+0x10`). Only `err == 0` counts (the return value is the handle, kept in `this+0xC`). Then `MICGetStatus`, `MICSetDataConsumed(available)` to flush, `MICOpen`, `MICGetStatus` again. |
| `0x02352048` | Reads `MICGetState` ids 4, 2, 3, 1 (gain step, min, max, current gain in S7.8 dB). The gain must fit in 15 bits (`& 0xFFFF8000 == 0`) or the device is not used. |
| `0x02352108` / `0x0235217C` | `sead::MicConnectThread`'s step: while not initialised it calls the init **again** (so the option can be switched on while the game runs); once running it checks `flags & (OPEN|CONNECTED)` and, when one is missing, prints "disconnected", forgets the handle (no `MICUninit`) and goes back to init. It clamps the gain between min and max and calls `MICSetState(1, gain)` when it changed ("SetMicGain returned"). |
| `0x02352358` / `0x023523E0` | Status: waits for 768 samples (24 ms) before the first read; then takes 96 samples at a time while at least 96 are there: scales them in place (`>> 2`), `DCFlushRange`, `MICSetDataConsumed(96)`. |
| `0x023524D4` (state 2) | Level: over the available samples, `abs(sample) > 0x80` counts, and the running peak (`this+0x68`) and its sum (`this+0x6C`) are what the higher layers (`MicMgr`, `MicInput`) read. |

Who listens (`ReactionMic`, `Mic*` states in the RPX strings and stage files): bushes (`Bush` reaction),
dandelions (`FlowerDandelion` / `Cotton`, `PgBlowFtuff`: blow the fluff off), Toads and the Captain Toad
brigade (`Kinopio`, `KinopioBrigade`), Rosalina (`Rosetta`), Plessie (`Raidon`: `MicStartWait`,
`MicReadyWait`, `MicGoodbye`), and the **mic lifts** `LiftMike*` (blocks that move along rails while you blow).
The course built around them is **world 2-2, `TouchAndMikeStage`** (with `TouchAndMikeSecondZone`,
`TouchAndMikeBonusRoomZone`, `CloudBonusTouchAndMikeZone`; the world map piece is `MiniatureTouchAndMike`);
`LiftMike` also appears in Captain Toad's first course (`KinopioBrigadeTentenStage`, 1-8 on the stage list)
and in `ArrangeChikaAZone`. Dandelions are in 1-1 (`EnterCatMarioStage`), 1-2, 2-2 and many more.

## libmic as implemented

PCM16 mono, **32 kHz, big-endian** in guest memory. Semantics follow Cemu's libmic
(`Cafe/OS/libs/mic/mic.cpp`, MPL-2.0; used as a reference, no code copied), checked against the game's use above.

| Call | Behaviour |
|---|---|
| `MICInit(instance, unused, MICWorkMemory* {u32 sampleCount; void* samples}, int32* err)` | Handle 0 for instance 0 when a microphone is present (option on and, for "Microphone", the capture device opened); `*err = 0`. Otherwise `*err = -8` (not connected), returns -1. A second init (after the game dropped the device) starts over. |
| `MICOpen(h)` / `MICClose(h)` | Start / stop listening. -5 already open, -6 not open, -7 not initialised, -8 not connected, -2 bad handle. |
| `MICGetStatus(h, MICStatus* {u32 flags; u32 available; u32 readIndex})` | Writes the samples that arrived since the last call into the ring (this is where the host feeds), then reports. Flags: 1 PCM16, 2 open, 4 connected (cleared when the option is switched off: the game drops the device). |
| `MICGetState(h, id, u32*)` | 0 sample rate 32000; 1 gain (S7.8, starts 0); 2 min 0x0000; 3 max 0x0200; 4 step 0x0001; 5 mute; 7 echo cancellation; 8 auto selection. Others -2. |
| `MICSetState(h, id, v)` | 1 gain, 5 mute, 7, 8. |
| `MICSetDataConsumed(h, n)` | Moves the read index on; more than available returns -81 and jumps to the write index. |
| `MICUninit(h)` | Forgets everything. |

The ring keeps one slot free; input that does not fit is dropped (the game was not reading). The ring logic,
the filter and the synthetic blow are in `runtime/src/mic_core.h`, tested by `runtime/tools/mic_test.cpp`.

## Where the samples come from (`runtime/src/mic.cpp`)

- **Microphone**: SDL3's default recording device (`runtime/src/platform/mic_sdl.cpp`), converted by SDL to
  32 kHz mono. It runs on its own thread while the option is "Microphone" (Android shows the microphone
  indicator then). Each `MICGetStatus` drains what was captured (at most what fits; older audio is dropped to keep
  the delay short), through the **blow filter**:
  - DC / rumble high-pass (60 Hz);
  - **gate**: opens when the level is 6x above the learnt noise floor (and at least 0.02 RMS) *and* at least
    half of the energy is under ~400 Hz. A blow on a microphone is loud low-frequency turbulence (share ~0.8 in
    the test signal); speech has most of its energy higher (vowel test: 0.23), so talking does not blow.
    The floor does not adapt while blowing (up to 8 s), so a long blow stays open; 60 ms hold, 4 ms attack,
    80 ms release;
  - **light AGC** while blowing: up to +12 dB towards 0.35 RMS, so a soft phone-microphone blow reaches the level
    of a GamePad blow;
  - **sensitivity** (menu, 25-400 %) scales the result. Closed, the game hears silence.
- **Button**: a synthetic blow (noise low-passed at 800 Hz, ~0.33 RMS, peaks near full scale, 30 ms attack,
  120 ms release) while the on-screen button (Android) or the key **B** (desktop) is held. The button/key also
  works in "Microphone" mode. No capture, no permission.
- Silence otherwise, paced by real time (32 000 samples a second).

## The option

| Where | |
|---|---|
| Android | Options > Controles > **Sopro (microfone do GamePad)**: Desligado / Microfone / Botão, and **Sensibilidade do sopro**. "Microfone" asks for `RECORD_AUDIO` at that moment (never at the first start); refused, it goes back to Desligado with a message. If the permission is later taken away in Android's settings, the next start runs with it off. "Botão" shows a round **≈** button on the right (movable in the touch-button editor). |
| Desktop | Settings overlay (F1) > Controls > **Blow (GamePad mic)**: Off / Microphone / Button (B key), and **Blow sensitivity** (saved). |
| Environment | `WWHD_MIC=off|mic|button`, `WWHD_MIC_SENS=25..400`, `WWHD_MIC_KEY=<key id>` (default `B`), `WWHD_MIC_FAKE=noise` (blow all the time; implies at least "button"), `WWHD_MIC_LOG=1` (counters every 5 s), filter knobs `WWHD_MIC_GATE=0` (no gate), `WWHD_MIC_LOW_SHARE=0..1` (default 0.5), `WWHD_MIC_GATE_RATIO` (default 6). See docs/env-vars.md. |

Log lines: `[mic] MICInit succeeded: ring of 3072 samples ...`, `[mic] MICOpen: the game listens`,
`[mic] capture started ...` / `unavailable ...`, and (once the game reads, or every 5 s with `WWHD_MIC_LOG=1`)
`[mic] fed N samples (... peak P), game consumed M, loud samples L`.

## Testing

- `build/vk/mic_test`: ring buffer as the game drives it (3072 / 96), the filter (room noise silent, soft blow
  through and raised, 5 s blow stays open, speech kept out, sensitivity scales), synthetic blow levels.
- Desktop, no microphone needed: `WWHD_MIC_FAKE=noise WWHD_MIC_LOG=1` and a course with something that reacts
  (2-2 `TouchAndMikeStage`: the mic lifts; or dandelions in 1-1). The stage tour can put 2-2 on the
  benchmark's 1-5 node: `tools/bench/stage_list_patch.py <game>/content/SystemData/StageList.szs OUT.szs
  FlipCircusStage TouchAndMikeStage` (see tools/bench/sm3dw_stage_tour.sh).
- Phone: Options > Controles > Sopro: Microfone (allow), enter 2-2 and blow on the bottom microphone; or
  Botão and hold ≈. `adb logcat | grep '\[mic\]'` shows the lines above.

Not verified on hardware: the gain values (min/max/step are Cemu's guesses; the game only clamps between them)
and how loud a real GamePad blow is. The filter thresholds are heuristics tuned on synthetic signals; the
environment knobs exist to retune them on a phone.
