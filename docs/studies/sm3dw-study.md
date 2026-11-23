# Super Mario 3D World on the WWHD runtime: study

Working notes for the `sm3dw` branch. Nothing from the game is stored here, only numbers and names.

## Game

| | |
|---|---|
| Title | `0005000010145c00` (USA), version 1 (base only, the .wua holds no update) |
| Executable | `code/RedCarpet.rpx`, 7,227,136 bytes |
| SHA-256 | `813d906365860eb6ef4644e8380983db382cbaefd951f9a89bd3d2c84eb4aaad` |
| Files / size | 2,999 files, 1.70 GB extracted |
| Oracle | Cemu running the same .wua |

Extraction needed no new tool: `wwhd-extract extract GAME.wua game3dw` (tools/wudextract, built by CMake)
already reads .wua (ZArchive). `game3dw/` is in `.gitignore`.

## Import diff (574 imports, 22 libraries)

The runtime defines `imp_<lib>_<name>` (via `HLE(lib, name)`); the recompiler emits a weak default for
every import, so a missing one logs and returns through `hle_unimplemented`. Counting names with no
strong definition (computed from `tools/rpxinfo.py -v` against `runtime/src`):

**331 present, 243 missing.** The load-bearing gaps are far fewer than that:

| Library | Missing / total | Verdict |
|---|---|---|
| coreinit | 40 / 152 | **implement**: MEMAllocFromDefaultHeap(Ex), MEMFreeToDefaultHeap, MEMFreeToFrmHeap, block-heap tracking, OSInitCond/OSWaitCond/OSSignalCond, OSSetThreadPriority, OSIsThreadTerminated, FSReadFileWithPos/FSChangeDir/FSRemove. Stub: MCP_*, OSDriver_Register, register reads, `_iob`, atexit/cpp-exception data. |
| gx2 | 28 / 134 | **implement**: GX2SetDefaultState, GX2RCreateBuffer/Lock/Unlock/Destroy/SetAllocator, GX2SetPolygonOffset, GX2SetCullOnlyControl, GX2SetDepthOnlyControl, GX2SetPointLimits, GX2SetPrimitiveRestartIndex, GX2GetSurfaceFormatBits, GX2GetSystemTVAspectRatio/ScanMode, tessellation and stream-out setters (stub first), GX2SetTVGamma/DRCGamma. |
| snd_core | 9 / 67 (counted as 59 missing in a coarser pass) | AXAcquireVoice, AXSetVoiceSamplesAddr, AXRegisterCallback, AXSetMasterVolume, AXSetMode, AXIsInit, AXIsVoiceRunning: needed for audio, not for the first frame. |
| padscore / vpad / vpadbase | 11 + 4 + 1 | WPADInit, KPADEnableDPD etc. stub; VPADInit stub. |
| proc_ui | 5 / 8 | ProcUIInitEx, ProcUIClearCallbacks. |
| nn_save | 7 / 14 | SAVEGetFreeSpaceSize, SAVERemove, shared-data paths. |
| nn_act / nn_ac / nn_acp / sysapp | 10 / 2 / 3 / 1 | stubs that return a failing Result. |
| nn_boss, nn_fp, nn_ndm, nn_olv, nlibcurl, nsysnet, mic, camera, snd_user, zlib125 | rest | online and peripherals: stubs that log and return 0 (zlib125 is only 6 functions, worth doing for real). |

So the expectation holds: **about a hundred real functions**, not hundreds. No decision point to postpone.
Re-run the count with `python3 tools/rpxinfo.py game3dw/code/RedCarpet.rpx -v`.

## Recompile (phase 3)

`RECOMP_GAME=sm3dw python3 tools/recomp/recomp.py game3dw/code/RedCarpet.rpx build/gen3dw`

- 54,075 functions, 61 files, 541 of 549 imports used, three fixpoint rounds, 25 s.
- No jump-table or vtable failure on the first run. 29 "unhandled" `op=0` words (data inside code).
- `tools/recomp/builds.py` picks the game with `RECOMP_GAME`: its own hash and title id, no build maps,
  and **every Zelda hook file is switched off** (`hook_files()` returns nothing).

## Zelda-specific code to switch off in the sm3dw target (phase 4)

| Item | Where |
|---|---|
| Executable name `cking.rpx`, title id | runtime startup, `tools/installer`, README |
| Hash check and build maps | `tools/recomp/builds.py`, `builds/eu.json` (off with RECOMP_GAME) |
| `hooks*.txt` (aspect, climb, controller selection, glyphs, gyro, language, mods, perf, rtl, traversal) | off with RECOMP_GAME |
| Runtime code that names Zelda addresses (`f_XXXXXXXX` refs: aspect, true60, interp, glyphs, rtl, mods) | link-time: compile out of the sm3dw target (`tools/recomp/stubgen.py` lists them) |
| true60 / interp (30 fps to 60) | not needed: 3D World already targets 60 |
| Mods, button glyphs, language packs, decomp build | disable by option, do not delete (merge with upstream) |
| Shader and save caches | separate directory per title id |

## Milestones added

- **Widescreen.** Beyond 16:9, Hor+ rather than stretch. Lessons from the sibling ports in this workspace:
  - `CoD2Recomp/documentation/studies/WIDESCREEN.md` and `PES2013Recomp/documentation/studies/WIDESCREEN.md`:
    find the game's display-aspect globals and projection x-scale, hook the code that writes them,
    and keep 2D layouts correct with the pixel aspect.
  - WWHD's own `runtime/src/aspect.cpp` and `aspect_panes.h` already do this for a Wii U GX2 title (pane
    layout patching); reuse the mechanism, with 3D World's own addresses.
  - Wii U specifics: the game asks `GX2GetSystemTVAspectRatio` (currently missing), and the layouts
    are Nintendo `.bflyt` (content/LayoutData), so 2D scaling can also be done from the layout side.
  - Primary target: the Galaxy S23 in landscape, 2340x1080 (19.5:9, about 2.17:1), plus true 21:9 (2.33:1) and
    16:9. Hor+ for all three; the S23 must fill the screen with no bars and the HUD kept inside the safe area.
  - Acceptance bar: 16:9, 21:9 and the S23's aspect with correct Hor+ 3D, undistorted HUD, no culling pop.
- Android and the rest of the plan stay as in the original plan.

## Other repositories in this workspace

None targets the Wii U. Useful by area: CoD2Recomp / PES2013Recomp (widescreen studies), RaymanOriginsRecomp
(`GamePad/` touch controls and `docs/ANDROID*.md` for phase 8). The WWHD runtime itself is the closest base.

## Status log

| Phase | State | Real time |
|---|---|---|
| 0 base | repo, branch, upstream; runtime compiles on macOS 14 SDK after a guard on a macOS 15 Metal property | ~20 min |
| 1 extract | done; hash recorded above | ~5 min |
| 2 recon | done; table above | ~10 min |
| 3 recomp | done: build/gen3dw generated and all 61 C files compile | ~15 min |
| 4 target | interim: `tools/recomp/sm3dw_stubs.py` stubs the 187 Zelda-address symbols the runtime names; `-DWWHD_GAME_RPX=RedCarpet.rpx`; binary links. Still to do: a separate `sm3dw` CMake target, title id, save/shader-cache paths | ~25 min |
| 5 boot | done: the crash was `OSWaitRendezvous` ignoring the core mask (main thread did not wait for the audio launch thread). Added MCP_*/SAVEGetSharedDataTitlePath (product area drove the UsEn language folder). Runs into the main loop | ~1 h |
| 6 title | **title screen renders** (controller notice, then the logo and the 3D scene; frame dump at 1280x720, ~400k draws in 20 s). Not yet checked against Cemu; GX2 setters still logged as unimplemented; window not visible when started from a background shell (frame dump used instead) | |

## Android S23 (SM-S911B, Adreno 740): status

| Item | State |
|---|---|
| APK | `dist/SM3DW-test.apk` (debuggable, `org.sm3dwrecomp.sm3dw`, "Super Mario 3D World", the game's icon), built with `android/build_native.sh` (`CPU=cortex-a78`: `cortex-x3` enables SVE and crashes with SIGILL on this SoC) + `android/build_sm3dw_apk.sh` |
| Game files | imported in the app: the icon opens an import screen while `files/game` is missing (`GameSetupActivity`); the player picks a `.wux`/`.wud` and a Cemu `keys.txt`, or a `.wua`, and `GameImportService` extracts it (libm3dwimport.so, `tools/wudextract/game_import.h`), refusing a dump whose `RedCarpet.rpx` is not the one the APK was built from. Options > Idioma e sobre > Reimportar jogo does it again. `tools/android_push_game.sh` (adb, `run-as`) still works |
| GPU drivers | Options > Driver: download Turnip from the catalogue, import a .zip, choose, delete (`DriverStore`, the runtime's `files/gpu-drivers` layout); Adreno only |
| Shader pack | `tools/sm3dw_shader_pack.sh apk` before `android/build_sm3dw_apk.sh` puts `shaderpack.bin` in the APK's assets (git-ignored `android/app/local-assets`) |
| Widescreen | default = match the screen (Hor+ 3D, 2D undistorted and centred); hooks in `tools/recomp/hooks_sm3dw.txt` |
| fps (cool phone) | avg 55-59, p10 51-56, min 41-46 after: pipeline cache merge at checkpoints (the 2-4 fps scene-load stall), `perf_hint` with a 16.7 ms target and no 30 fps logic (`WWHD_NATIVE_60`), `-Bsymbolic` + `-fno-semantic-interposition`, `-mcpu=cortex-a78` |
| fps (hot phone, USB charging) | ~42 average at thermal headroom 0.94: the phone throttles; measure cool |
| Internal resolution below 1x | allowed (0.75 in the Graphics menu); no gain (avg 43.6 vs 47.8, same conditions): the limit is the render-thread CPU, not the GPU |
| Vulkan head start | built (`tools/shaderprep.py`), copied to `game/shadercache/headstart.bin`: no measurable gain on the first scene load, and no warm-up log line; not used. Persisting the driver cache is what removes the stall (second start onwards) |
| Studies | `docs/sm3dw-fps-study.md`, `docs/sm3dw-perf-study.md` |

## Como testar no Mac sem abrir janela

```sh
WWHD_HIDDEN_WINDOWS=1 WWHD_NO_HOST_INPUT=1 WWHD_ASPECT=21:9 \
WWHD_PRESS="700-706:8000,1000-1006:8000,1400-1406:8000" \      # segura A (VPAD 0x8000) nesses frames
WWHD_SCREENSHOT_DIR=/tmp/shots WWHD_TEST_SCREENSHOT=900,1300,1700 \   # PNG do frame da TV, sem janela
build/cmake3dw/wwhd --game game3dw
```

Verificado: sem entrada o jogo fica no título ("A Start") por 2000+ frames, com e sem `WWHD_NO_HOST_INPUT`;
o "avanço sozinho" visto antes era entrada de teclado/mouse na janela visível, não defeito do jogo.
Passeio de teste (A em 700, 1000, 1400, 1800, 2300, 2800): título -> selecionar arquivo -> selecionar
personagem -> Mario -> cena de abertura (castelo, fogos, cano), tudo em 21:9 com 3D Hor+ correto.

## Pendências de widescreen vistas no passeio (docs/sm3dw-widescreen-hud-study.md)

- Telas 2D (arquivo, personagem): barras pretas nas laterais e faixas fantasma das transições (cortina) à direita:
  falta o hook de `Pane::CalculateMtx` (02636D34) / `Pane::Draw` (02637768) e a tabela de regras por layout.
- HUD de fase ainda não visto em widescreen (precisa chegar à fase 1-1).

## Estado do código nesta etapa

- `runtime/src/game_id.h`: nome do jogo e prefixos de arquivo; `GAME_HAS_ZELDA_GUEST=0` no sm3dw.
- Aba Mods escondida (`overlay::ext::hide_tab("mods")` em `main.cpp`), seção de interpolação/30-60 fps removida
  do menu Graphics, `story_system` desligado: nada do Zelda escreve na memória do 3D World.
- Pastas de dados por jogo também em `display.mm`, `shader_prebuild.h` e no Android (`AndroidUserDirectories.java`,
  regras de backup).
- Imports reais chamados na partida e agora explícitos: `KPADEnableDPD/DisableDPD`, `WPADInit`,
  `WPADRegisterAllocator`, `WPADGetSpeakerVolume`, `AXSetPpcLoadLimit`, `MICInit`, `VPADInit`,
  `SAVEGetStatOtherApplication`/`SAVEOpenFileOtherApplication` (-6), `SAVEGetFreeSpaceSize` (1 GiB),
  `OSIsThreadTerminated`. Os demais da lista do estudo de áudio/saves não foram chamados no boot e seguem
  como estão até aparecerem num log (usar `--trace`).

## Widescreen do HUD/menus: estado (passeio de 14.000 frames em 21:9, Mac, sem janela)

- Hooks ligados: `Pane::CalculateMtx 02636D34`, `Pane::Draw 02637768`, recorte em volta do DrawSelf
  (`@026377B4/@026377B8`), texto `0262D5CC`; regras em `runtime/src/sm3dw/aspect_sm3dw.cpp` (`kSmRules`).
- Resultado: telas de arquivo/personagem com fundo branco cobrindo a tela inteira e sem faixas fantasma
  (o recorte esconde painéis parqueados fora da região 16:9); no mapa do Mundo 1 os contadores ficam na borda
  esquerda e "Menu/Map" na direita, "WORLD 1" no topo.
- Visto e ainda não tratado: barras de título largas (ex.: faixa vermelha de "Select a file") terminam na
  região 16:9; nomes dos painéis dessas barras precisam entrar em `kSmRules`. Marcadores no mundo (balões,
  números) e o HUD dentro de uma fase (1-1) ainda não foram vistos.
- Possível defeito de render a investigar: o mapa do Mundo 1 aparece lavado/estourado (muito brilho nos
  gramados e na névoa); comparar com o Cemu quando houver oportunidade (bloom, tone map, formato HDR do alvo).
- Para repetir o passeio: A em 700,1000,1400,1800,2300,2800 e depois a cada 300 frames de 3000 a 14000;
  capturas a cada 700 frames de 4000 a 14000 (`WWHD_PRESS`, `WWHD_TEST_SCREENSHOT`).

## Controles físicos (Android e SDL): vários controles

- O primeiro controle conectado (e o teclado) age como o GamePad (`VPADRead`), como antes.
- Com 2 ou mais controles, o 2º, 3º e 4º viram Pro Controllers nos canais KPAD 1..3 (`input::pad_slots()` /
  `input::read_slot()`, `input_sdl.cpp`; `WPADProbe`/`KPADReadEx` em `padscore.cpp`), para até 4 jogadores
  na tela "Select a character". `WWHD_MULTI_PAD=0` desliga (no build do Zelda é opt-in).
- Mapeamento padrão por posição: botão sul = B do Wii U, leste = A (layout Nintendo); ajustável em controls.json.
- Sem controle não há botões na tela (build sm3dw): jogar com controle Bluetooth/USB. Menu de configurações:
  segurar Select/Home.
- Não verificado em aparelho: ainda não testei com um controle real (o S23 desconectou do adb); perfis Classic e
  Wii Remote seguem sem implementação (docs/sm3dw-input-study.md).

## Transições e recorte em widescreen (regra geral)

Painéis **sem regra** são conteúdo: ficam na região 16:9 centrada e são recortados a ela (assim os painéis que o
jogo estaciona fora da tela não aparecem nas laterais). Tudo que precisa cobrir a tela inteira (fundos, fades,
cortinas, íris) precisa de regra em `kSmRules`, senão o recorte o corta. Painel com regra `Fill` é escalado só o
necessário (`cover`): um painel de 1641 px de largura (íris) não muda até ~2,28:1.
Teste de transição: capturar frames densos (a cada 4) com `WWHD_PRESS` e `WWHD_TEST_SCREENSHOT=704,708,...`.
