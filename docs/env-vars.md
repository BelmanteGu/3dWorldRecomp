# Variáveis de ambiente

O prefixo `WWHD_` vem do projeto de origem (porte do Wind Waker HD) e foi mantido por compatibilidade com os scripts, 
configurações salvas e nomes de pacote (`org.wwhdrecomp.wwhd`). Todas são lidas no início do processo; em Android podem ser 
colocadas, uma `NOME=valor` por linha, em `env.txt` na pasta de dados do app. As opções abaixo são as relevantes para o Super Mario 3D World; 
a lista completa (todas as que o código lê) vem em seguida, com o arquivo onde aparecem, para depuração.

## Principais

| Variável | Padrão | Efeito |
|---|---|---|
| `WWHD_FPS` | 60 (padrão), 30 ou 120 | A lógica fica sempre a 60 passos/s. `120`: quadros intermediários (docs/sm3dw-120fps.md). `30`: velocidade total, um quadro a cada dois desenhado e apresentado; se a lógica ainda ficar abaixo de 57 passos/s por 3 s, um a cada três (20 fps), sinalizado (docs/30fps.md) |
| `WWHD_FPS_BLEND` | 1 | 0: quadros intermediários repetem a última imagem (só testes de ritmo) |
| `WWHD_FPS_STATS` | 0 | 1: linha `[fps120]` a cada 600 quadros |
| `WWHD_PRESET` | vazio (automático `intel60` em GPU integrada no desktop) | `intel60`: 60 fps travados em GPU integrada (resolução automática alvo 60 com 85% do período, teto 1.25, mul rápida, barreiras precisas, DRC off; docs/intel60.md; `--intel60`). `intel120`: 120 fps (docs/intel120.md; `--intel120`). `intel30`: 30 fps a velocidade total, resolução automática alvo 30, teto 1.5 (docs/30fps.md; `--intel30`). `none`: nenhum preset, nem o automático |
| `WWHD_AUTO_RES` | desligado | Alvo de fps (`30`, `60` ou `120`): a resolução interna segue o tempo de GPU medido |
| `WWHD_AUTO_RES_HEADROOM` | 0.88 (0.85 em intel60, 0.95 em intel120) | Fração do período do quadro que a GPU pode usar |
| `WWHD_AUTO_RES_MIN` | 0.4 | Piso da resolução automática |
| `WWHD_AUTO_RES_LOG` | 0 | 1: registra cada mudança |
| `WWHD_RES_SCALE` | 1.0 (1.5 = 1080p interno) | Resolução interna (0.25 a 3+) |
| `WWHD_MUL_MODE` | padrão | `fast`: multiplicação simples nos shaders (~+20% de GPU; docs/benchmark.md) |
| `WWHD_STRICT_MUL` | 1 | 0: multiplicação sem o desvio `mul_nonIEEE` |
| `WWHD_DRC_MODE` | window/pip/auto/off/gamepad | Imagem do GamePad; `off` não a desenha |
| `WWHD_FRAMESKIP` | 0 (Android: 1) | 0 desligado, 1 automático (abaixo de 54 fps cai para um quadro a cada dois), 2 sempre (= `WWHD_FPS=30` sem a queda para 1 em 3). docs/frameskip.md |
| `WWHD_LOG_PRESENT_PACING` | 0 | 1: linha `[30fps]` a cada 10 s (apresentados/s, passos de lógica/s, intervalo entre apresentações média/desvio/p50/p99/máx); sai sozinha com 30 fps ou frame skip |
| `WWHD_VK_NARROW_BARRIERS` | 0 (1 nos presets Intel, desktop) | 1: barreiras com estágios/acessos exatos em vez de esperar a GPU inteira (-3,5% de GPU na Intel UHD) |
| `WWHD_VK_DETILE_THREADS` | 3 (8+ núcleos) | Threads auxiliares para destilar níveis grandes de textura (0: só a thread de render) |
| `WWHD_VK_DETILE_FAST` / `_VERIFY` | 1 / 0 | Caminho rápido por micro-tile das texturas 2D tiled; `VERIFY=1` compara com o caminho geral e registra diferenças |
| `WWHD_SCALE_FILTER` | bilinear | `quality`: upscale Catmull-Rom + nitidez |
| `WWHD_VK_DEVICE` | automático | Subcadeia do nome da GPU Vulkan a usar |
| `WWHD_LANGUAGE` | do sistema | Índice de idioma do jogo (9 = português) |
| `WWHD_BENCH / _FRAMES / _STAGE / _OUT` | vazio | Benchmark sem humano; ver docs/benchmark.md |
| `WWHD_BENCH_ALL` | 0 | 1: o benchmark grava todos os quadros (título, mapa, carga, fase) com as causas por quadro; docs/stutter.md |
| `WWHD_FRAME_CAUSES` | 0 | 1: contadores de causa por quadro fora do benchmark (frame_causes.h) |
| `WWHD_VK_PENDING_WAIT_MS` | 50 (Android: 3) | Espera máxima por quadro por pipelines em compilação antes de pular o draw (0: pula na hora) |
| `WWHD_VK_RECIPE_PACK` | `<jogo>/shadercache/pipelines` | Pacote de receitas de pipeline (arquivo, pasta ou 0); docs/stutter.md |
| `WWHD_VK_RECIPE_MERGE` | 0 | 1: as receitas do pacote entram no banco salvo do jogador (ferramentas) |
| `WWHD_HEADSTART_PIPELINES` | 1 | 0: `--warm-shaders` não monta os pipelines dos registros ligados do head start |
| `WWHD_BENCH_STAGELIST` / `_CAP_STAGE` / `_LOAD_MAX` | vazio / 0 / 3000 | Tour de fases: lista de fases substituta, fase em tempo real, quadro máximo de carga; docs/stutter.md |
| `WWHD_VK_PIPELINE_QUEUE` | 1024 | Tamanho da fila de compilação de pipelines por demanda (excedente compila síncrono) |
| `WWHD_VK_PIPELINE_THREADS` | 1–4 | Threads de compilação de pipelines por demanda (4 com 12+ núcleos) |
| `WWHD_USER_DIR / --user-dir` |  | Pasta de configuração do usuário |
| `WWHD_GYRO` | off | `off|controller|cemuhook|mouse`: fonte do giroscópio |
| `WWHD_MIC` | off | Microfone do GamePad (sopro, docs/mic.md): `off` (MICInit falha, como antes), `mic` (dispositivo de gravação do host; no Android só com a permissão), `button` (só o botão/tecla de soprar) |
| `WWHD_MIC_SENS` | 100 | Sensibilidade do sopro em % (25 a 400) |
| `WWHD_MIC_KEY` | B | Tecla de soprar no desktop (id de tecla do controls.json, ex. `B`, `M`) |
| `WWHD_MIC_FAKE` | vazio | `noise`: sopro sintético o tempo todo (testes; liga pelo menos o modo `button`) |
| `WWHD_MIC_LOG` | 0 | 1: contadores `[mic]` a cada 5 s (amostras entregues, pico, consumidas pelo jogo) |
| `WWHD_MIC_GATE` / `_LOW_SHARE` / `_GATE_RATIO` | 1 / 0.5 / 6 | Filtro do microfone real: 0 desliga o gate; fração mínima de energia abaixo de ~400 Hz; nível sobre o ruído de fundo |
| `WWHD_LOG_FILE` |  | 0 desliga o arquivo de log |

## Lista completa (gerada do código)

Muitas são ferramentas de depuração, de testes ou de detalhes do renderizador Vulkan e não são estáveis.

| Variável | Arquivos |
|---|---|
| `WWHD_ADPF_WORK` | platform/perf_hint.cpp |
| `WWHD_ADRENOTOOLS` | CMakeLists.txt, gfx/vulkan/android_driver.cpp |
| `WWHD_ANDROID_KEYBOARD` | android/app/src/main/java/org/libsdl/app/SDLActivity.java, platform/input_sdl.cpp |
| `WWHD_ANISO` | MenuOptions.java, gfx/menu.mm (+3) |
| `WWHD_ANISO_MAX` | gfx/vulkan/draw.cpp |
| `WWHD_AO_HIRES` | gfx/menu.mm, gfx/metal_draw.mm (+2) |
| `WWHD_AO_MODE` | gfx/menu.mm, gfx/metal_draw.mm (+2) |
| `WWHD_ASPECT` | aspect.cpp, aspect.h (+4) |
| `WWHD_ASPECT_AT` | aspect.cpp |
| `WWHD_ASPECT_DEFAULT_WINDOW` | CMakeLists.txt, aspect.cpp |
| `WWHD_ASPECT_LOG` | aspect.cpp |
| `WWHD_ASPECT_TRACE_FRAME` | aspect.cpp |
| `WWHD_AUDIO_DUMP` | audio_out.cpp |
| `WWHD_AUDIO_MODE_TRACE` | hle/ax.cpp |
| `WWHD_AUDIO_OUTPUT` | audio_output_mode.h, hle/ax.cpp |
| `WWHD_AUDIO_SPEAKERS` | audio_out.cpp |
| `WWHD_AUDIO_VOLUME` | MenuOptions.java, audio_out.cpp (+2) |
| `WWHD_AUTO_RES` | autores.cpp, autores.h (+2) |
| `WWHD_AUTO_RES_HEADROOM` | autores.cpp, gpu_preset.cpp |
| `WWHD_AUTO_RES_LOG` | autores.cpp, autores.h |
| `WWHD_AUTO_RES_MIN` | autores.cpp, autores.h |
| `WWHD_AX_STATS` | hle/ax.cpp |
| `WWHD_BENCH` | autores.cpp, bench.cpp (+3) |
| `WWHD_BENCH_A_EVERY` | bench.cpp |
| `WWHD_BENCH_CAPPED` | bench.cpp |
| `WWHD_BENCH_FIRST_A` | bench.cpp |
| `WWHD_BENCH_FRAMES` | bench.cpp, bench.h (+1) |
| `WWHD_BENCH_GIVE_UP` | bench.cpp |
| `WWHD_BENCH_LOAD_MIN` | bench.cpp |
| `WWHD_BENCH_LOG_FILES` | bench.cpp |
| `WWHD_BENCH_OUT` | bench.cpp, bench.h (+1) |
| `WWHD_BENCH_SETTLE` | bench.cpp |
| `WWHD_BENCH_SHOTS` | bench.cpp |
| `WWHD_BENCH_STAGE` | bench.cpp, bench.h (+1) |
| `WWHD_BENCH_STAGE_DRAWS` | bench.cpp |
| `WWHD_BENCH_START` | bench.cpp, bench.h |
| `WWHD_BG_COMPILES` | gfx/metal_draw.mm |
| `WWHD_BIN` | tools/bench/sm3dw_bench.sh |
| `WWHD_BLOOM_STRENGTH` | gfx/menu.mm, gfx/renderer.cpp (+1) |
| `WWHD_BUILD_INFO` | CMakeLists.txt |
| `WWHD_BUNDLED_DEPS` | CMakeLists.txt, android/build_native.sh |
| `WWHD_CAPTURE` | gfx/metal_draw.mm, gfx/vulkan/backend.cpp |
| `WWHD_CAPTURE_PATH` | gfx/vulkan/backend.cpp |
| `WWHD_CLANG_BUILTINS` | CMakeLists.txt |
| `WWHD_CLANG_TARGET_ARG` | CMakeLists.txt |
| `WWHD_COMPILE_WAIT_MS` | gfx/metal_draw.mm |
| `WWHD_CONTROLS` | gfx/controls_ui.mm, input_map.cpp (+1) |
| `WWHD_CONTROLS_HOVER` | gfx/controls_ui.mm |
| `WWHD_CONTROLS_SELFTEST` | gfx/controls_ui.mm, quit_prompt.h |
| `WWHD_CONTROLS_SNAPSHOT` | gfx/controls_ui.mm |
| `WWHD_CONTROLS_SNAPSHOT_AT` | gfx/controls_ui.mm |
| `WWHD_COPYDBG` | gfx/metal_surfaces.mm, gx2/gx2_core.cpp |
| `WWHD_CRASH_RECOVERY` | crashrec.cpp |
| `WWHD_CRASH_RECOVERY_INTERVAL` | crashrec.cpp |
| `WWHD_CREATE_WINDOW_SURFACE` | gfx/vulkan/backend.cpp |
| `WWHD_DECOMP` | tools/recomp/recomp.py, tools/recomp/stubgen.py |
| `WWHD_DISPLAY_HZ` | interp.cpp, interp.h |
| `WWHD_DISPLAY_SETTINGS` | gfx/display.mm, main.cpp |
| `WWHD_DISPLAY_SMOKE_INPUT_TRACE` | gfx/vulkan/backend.cpp, gfx/vulkan/smoke.cpp |
| `WWHD_DRC_MODE` | MenuOptions.java, gfx/display.mm (+5) |
| `WWHD_DRC_PAUSE` | CMakeLists.txt, gfx/display_modes.cpp (+1) |
| `WWHD_DRC_PIP` | gfx/display.mm, gfx/display_modes.cpp (+2) |
| `WWHD_DUMP_DRAWS` | gfx/metal_draw.mm |
| `WWHD_DUMP_FRAMES` | gfx/display.mm, gfx/metal_main.mm (+2) |
| `WWHD_DUMP_LOAD_FRAMES` | gfx/metal_main.mm, gfx/vulkan/backend.cpp |
| `WWHD_DUMP_PRESENT` | gfx/display.h, gfx/display.mm (+2) |
| `WWHD_DUMP_PS` | gfx/metal_draw.mm |
| `WWHD_DUMP_SHADERS` | gfx/metal_draw.mm |
| `WWHD_EXIT_AT_FRAME` | gfx/display.mm, gfx/renderer.h (+3) |
| `WWHD_EXTRACT_EXE` | tools/wudextract/extract_test.cpp, tools/wudextract/wua_test.cpp |
| `WWHD_EXTRACT_NO_MAIN` | tools/wudextract/game_import.cpp, tools/wudextract/wwhd_extract.cpp |
| `WWHD_FAST_CORES` | platform/perf_hint.cpp |
| `WWHD_FPS` | main.cpp, sm3dw/interp_sm3dw.cpp (+1) |
| `WWHD_FPS_AFTER` | sm3dw/interp_sm3dw.cpp |
| `WWHD_FPS_BLEND` | sm3dw/interp_sm3dw.cpp, sm3dw/interp_sm3dw.h |
| `WWHD_FPS_BONES` | sm3dw/interp_sm3dw.cpp |
| `WWHD_FPS_CAMERA_CUT` | gx2/interp_camera.cpp, sm3dw/interp_sm3dw.cpp |
| `WWHD_FPS_CAMERA_TRACE` | gx2/interp_camera.cpp |
| `WWHD_FPS_HOLD_DRC` | sm3dw/interp_sm3dw.cpp |
| `WWHD_FPS_LISTS` | sm3dw/interp_sm3dw.cpp |
| `WWHD_FPS_NO_SHED` | sm3dw/interp_sm3dw.cpp |
| `WWHD_FPS_STATS` | sm3dw/interp_sm3dw.cpp |
| `WWHD_FPS_TEST_ALTERNATE` | sm3dw/interp_sm3dw.cpp |
| `WWHD_FPS_TEST_NOREPLAY` | sm3dw/interp_sm3dw.cpp |
| `WWHD_FPS_TRACE` | sm3dw/interp_sm3dw.cpp |
| `WWHD_FRAMESKIP` | MenuOptions.java, gx2/gx2.h (+1) |
| `WWHD_FULLSCREEN` | gfx/display.mm, gfx/display_modes.cpp (+2) |
| `WWHD_FULL_SAVE_STATES` | overlay/overlay.cpp, savestate.h |
| `WWHD_FXAA` | MenuOptions.java, gfx/menu.mm (+3) |
| `WWHD_GAME_RPX` | CMakeLists.txt, main.cpp (+2) |
| `WWHD_GAME_SM3DW` | CMakeLists.txt, game_id.h |
| `WWHD_GLSLANG_TYPE` | CMakeLists.txt |
| `WWHD_GLSLANG_WEAK` | CMakeLists.txt, gfx/vulkan/backend.cpp |
| `WWHD_GUEST_BUILD_CONFIG` | overlay/template_export.h |
| `WWHD_GX2_DELAY_COPY` | gx2/gx2_core.cpp |
| `WWHD_GYRO` | motion/motion.cpp, motion/motion.h (+1) |
| `WWHD_GYRO_LOG` | gfx/input.mm, motion/motion.cpp (+1) |
| `WWHD_HAS_METAL` | CMakeLists.txt, gfx/renderer.cpp (+3) |
| `WWHD_HAS_VULKAN` | CMakeLists.txt, app_menu.cpp (+11) |
| `WWHD_HEADSTART` | gfx/shader_headstart.mm, gfx/shader_prebuild.h (+4) |
| `WWHD_HEAT_GUARD` | platform/heat_guard.h |
| `WWHD_HIDDEN_WINDOWS` | gfx/controls_ui.mm, gfx/display.mm (+7) |
| `WWHD_INDEX_CASE` | gfx/vulkan/index_conversion.h |
| `WWHD_INSTALL_DIR` | overlay/template_export.h |
| `WWHD_INTERP` | gfx/menu.mm, gfx/vulkan/overlay_sdl.cpp (+2) |
| `WWHD_INTERP_FPS` | gfx/menu.mm, gfx/vulkan/overlay_sdl.cpp (+1) |
| `WWHD_INTERP_PACED` | gfx/menu.mm, gfx/vulkan/overlay_sdl.cpp (+2) |
| `WWHD_KEEP_TEST` | tools/wudextract/extract_test.cpp, tools/wudextract/wua_test.cpp |
| `WWHD_KEYS` | gfx/input.mm, input.h (+1) |
| `WWHD_KPAD_TRACE` | hle/padscore.cpp |
| `WWHD_LANGUAGE` | MenuOptions.java, hle/coreinit_misc.cpp (+2) |
| `WWHD_LANGUAGE_REGION` | hle/coreinit_misc.cpp |
| `WWHD_LANG_DIR` | game_languages.cpp, game_languages.h |
| `WWHD_LOG_BUTTONS` | gfx/input.mm, platform/input_sdl.cpp |
| `WWHD_LOG_FILE` | MenuOptions.java, main.cpp (+1) |
| `WWHD_LOG_FLIP_PACING` | gx2/gx2_core.cpp |
| `WWHD_LOG_PRESENT_PACING` | gx2/gx2_core.cpp |
| `WWHD_LOG_FRAME` | gfx/metal_draw.mm |
| `WWHD_LOG_INVALIDATE` | gfx/metal_main.mm |
| `WWHD_LOG_LONGWAIT` | threads.cpp |
| `WWHD_LOG_RESCALE` | gfx/metal_surfaces.mm |
| `WWHD_LOG_RUMBLE` | rumble.cpp, rumble.h |
| `WWHD_LOG_SLOW_SWAP` | gx2/gx2_core.cpp |
| `WWHD_LOG_TEXCHECK` | gfx/metal_surfaces.mm |
| `WWHD_LOG_TITLE` | gfx/menu.mm |
| `WWHD_LOG_VSYNC` | gx2/gx2_core.cpp |
| `WWHD_MIC` | MenuOptions.java, mic.cpp (+1) |
| `WWHD_MIC_FAKE` | mic.cpp |
| `WWHD_MIC_GATE` | mic.cpp |
| `WWHD_MIC_GATE_RATIO` | mic.cpp |
| `WWHD_MIC_KEY` | mic.cpp |
| `WWHD_MIC_LOG` | mic.cpp |
| `WWHD_MIC_LOW_SHARE` | mic.cpp |
| `WWHD_MIC_SENS` | MenuOptions.java, mic.cpp (+1) |
| `WWHD_MSL_NONCE` | gfx/metal_draw.mm |
| `WWHD_MULTI_PAD` | input.h, platform/input_sdl.cpp |
| `WWHD_MUL_MODE` | gfx/vulkan/shaders.cpp, main.cpp |
| `WWHD_NAN_GUARD` | gfx/metal_draw.mm, gfx/vulkan/shaders.cpp |
| `WWHD_NAN_PROBE` | trace.cpp |
| `WWHD_NATIVE_60` | CMakeLists.txt, main.cpp (+2) |
| `WWHD_NO_AO_QUIRK` | gfx/menu.mm, gfx/metal_draw.mm (+2) |
| `WWHD_NO_AUDIO` | CMakeLists.txt, ShaderPrepareActivity.java (+2) |
| `WWHD_NO_CHUNK` | gfx/metal_main.mm |
| `WWHD_NO_CONTROLLERS` | platform/input_sdl.cpp |
| `WWHD_NO_GAMEPAD` | gfx/display.mm, gfx/vulkan/backend.cpp (+2) |
| `WWHD_NO_HOST_INPUT` | bench.cpp, gfx/controls_ui.mm (+15) |
| `WWHD_NO_QOS` | platform/host.h |
| `WWHD_NO_RENDER_THREAD` | gx2/gx2_core.cpp |
| `WWHD_NO_RT_MIPS` | MenuOptions.java, gfx/metal_surfaces.mm (+6) |
| `WWHD_NO_SCHED` | threads.cpp |
| `WWHD_NO_UV_SNAP` | gfx/metal_draw.mm |
| `WWHD_OVERLAY_MOUSE_HANDOFF` | CMakeLists.txt, gfx/input.mm (+1) |
| `WWHD_OVERLAY_TRACE` | CMakeLists.txt, gfx/vulkan/backend.cpp (+2) |
| `WWHD_PAD_TRACE` | hle/system_stubs.cpp |
| `WWHD_PERF_FULL` | overlay/overlay.cpp |
| `WWHD_PERF_HINT` | platform/perf_hint.cpp |
| `WWHD_PERF_METRICS_DEBUG` | render_prof.cpp |
| `WWHD_PERF_OVERLAY` | MenuOptions.java, bench.cpp (+1) |
| `WWHD_PERF_OVERLAY_DEFAULT` | CMakeLists.txt, overlay/overlay.cpp |
| `WWHD_PHONE_GYRO` | MenuOptions.java, platform/input_sdl.cpp |
| `WWHD_PIPELINE_RUNTIME_ID` | CMakeLists.txt, gfx/vulkan/backend.cpp |
| `WWHD_PORTABLE_LOAD` | savestate.cpp |
| `WWHD_PORTABLE_LOAD_AT` | savestate.cpp |
| `WWHD_PORTABLE_SAVE_AT` | savestate.cpp |
| `WWHD_POTATO` | main.cpp |
| `WWHD_PRESENT_CAPTURE_FRAME` | gfx/vulkan/present.cpp |
| `WWHD_PRESENT_CAPTURE_PATH` | gfx/vulkan/present.cpp |
| `WWHD_PRESET` | gpu_preset.cpp |
| `WWHD_PRESS` | gfx/input.mm, platform/input_sdl.cpp (+2) |
| `WWHD_PROFILE` | gfx/metal_main.mm, render_prof.cpp (+2) |
| `WWHD_PRO_CONTROLLER` | gfx/input.mm, overlay/overlay.cpp (+1) |
| `WWHD_QUIT_PROMPT` | quit_prompt.h |
| `WWHD_RECOMP_CRLIVE` | tools/recomp/recomp.py |
| `WWHD_RECOMP_CR_CHECK` | core.cpp, tools/recomp/recomp.py |
| `WWHD_RECOMP_GQR` | tools/recomp/recomp.py |
| `WWHD_RECOMP_LEAF` | tools/recomp/recomp.py |
| `WWHD_RECOMP_LEAF_POISON` | tools/recomp/leaflocal.py, tools/recomp/recomp.py |
| `WWHD_RECOMP_MOD_HOOKS` | tools/recomp/recomp.py |
| `WWHD_RECOMP_NONLEAF` | tools/recomp/recomp.py |
| `WWHD_RECOMP_PLAIN` | tools/recomp/ppc2c.py, tools/recomp/recomp.py |
| `WWHD_RECOMP_RAWSTORE` | tools/recomp/recomp.py |
| `WWHD_RENDERER` | CMakeLists.txt, gfx/renderer.h |
| `WWHD_RENDERER_RUNTIME` | gfx/renderer.cpp, gfx/renderer.h (+2) |
| `WWHD_REPLAY` | crashrec.cpp, crashrec.h |
| `WWHD_REPLAY_AT` | crashrec.cpp |
| `WWHD_RES_SCALE` | MenuOptions.java, gfx/menu.mm (+6) |
| `WWHD_RES_SCALE_AT` | gfx/metal_surfaces.mm, gfx/vulkan/surfaces.cpp |
| `WWHD_RSTICK` | gfx/input.mm, platform/input_sdl.cpp |
| `WWHD_RUMBLE` | MenuOptions.java, overlay/overlay.cpp (+2) |
| `WWHD_RUMBLE_LOG` | rumble.cpp |
| `WWHD_SAMPLE_AFTER` | platform/thread_sampler.cpp, platform/thread_sampler.h |
| `WWHD_SAMPLE_SECONDS` | platform/thread_sampler.cpp, platform/thread_sampler.h |
| `WWHD_SAMPLE_THREADS` | platform/thread_sampler.cpp, platform/thread_sampler.h (+1) |
| `WWHD_SCALE_FILTER` | MenuOptions.java, gfx/display.mm (+4) |
| `WWHD_SCHED_STATS` | gx2/gx2_core.cpp, threads.cpp |
| `WWHD_SCREENSHOT_DIR` | bench.cpp, screenshot.cpp (+2) |
| `WWHD_SCREENSHOT_GAMEPAD` | screenshot.cpp, screenshot.h |
| `WWHD_SDL_HOST` | CMakeLists.txt, audio_out.cpp (+8) |
| `WWHD_SERIAL_PIPELINES` | gfx/vulkan/serial_pipelines.h |
| `WWHD_SETTINGS` | gfx/vulkan/overlay_sdl.cpp |
| `WWHD_SHADER_CACHE` | gfx/metal_draw.mm, gfx/shader_prebuild.h (+4) |
| `WWHD_SHADOW_SCALE` | gfx/metal_surfaces.mm, gfx/vulkan/surfaces.cpp |
| `WWHD_SHOW_CONTROLS` | gfx/controls_ui.mm |
| `WWHD_SIM_SCREEN` | gfx/display.h, gfx/display.mm (+2) |
| `WWHD_SM3DW_PROBE` | gx2/gx2.h, sm3dw/interp_sm3dw.cpp |
| `WWHD_SM3DW_PROBE_PV` | sm3dw/interp_sm3dw.cpp |
| `WWHD_SNAPSHOT` | gfx/metal_draw.mm |
| `WWHD_SOUND_TRACE` | hle/ax.cpp |
| `WWHD_STATE_DEBUG` | threads.cpp |
| `WWHD_STATE_DIR` | main.cpp, savestate.cpp |
| `WWHD_STATE_DUMP` | savestate.cpp |
| `WWHD_STATE_LOAD_AT` | gfx/vulkan/backend.cpp, savestate.cpp (+1) |
| `WWHD_STATE_MIN_HEADROOM_MB` | platform/memory_trim.h |
| `WWHD_STATE_SAVE_AT` | savestate.cpp, savestate.h |
| `WWHD_STATE_STAGE_ADDR` | savestate.cpp |
| `WWHD_STICK` | gfx/input.mm, platform/input_sdl.cpp |
| `WWHD_STRICT_MUL` | gfx/metal_draw.mm, gfx/vulkan/shaders.cpp |
| `WWHD_STRIP_RELEASE` | CMakeLists.txt |
| `WWHD_SWKBD_TEXT` | hle/swkbd.cpp, platform/input_sdl.cpp |
| `WWHD_SYNC_GPU` | gfx/metal_main.mm |
| `WWHD_SYNC_SHADERS` | gfx/metal_draw.mm |
| `WWHD_SYNC_STATS` | gx2/gx2_core.cpp |
| `WWHD_TEST_CAPTURE_LOAD_COUNTER` | gx2/gx2_core.cpp |
| `WWHD_TEST_CAPTURE_LOAD_STEP` | gx2/gx2_core.cpp |
| `WWHD_TEST_CATALOGUE_INSTALL` | overlay/overlay.h |
| `WWHD_TEST_CEMU_TRACE` | mods/cemu_pack.cpp |
| `WWHD_TEST_CLOSE_TV_AT` | gfx/quit_prompt.mm |
| `WWHD_TEST_COMPILE_CRASH` | gfx/vulkan/compile_journal.cpp, gfx/vulkan/compile_journal.h |
| `WWHD_TEST_CONTROLLER_STICK` | gfx/input.mm, platform/input_sdl.cpp |
| `WWHD_TEST_CRASH_AT` | crashrec.cpp |
| `WWHD_TEST_DEBUG` | gfx/input.mm, platform/input_sdl.cpp |
| `WWHD_TEST_DRC_KEY` | gfx/display.mm, gfx/vulkan/backend.cpp |
| `WWHD_TEST_DRC_MODE` | gfx/display.mm, gfx/display_modes.cpp (+1) |
| `WWHD_TEST_END` | gfx/input.mm, platform/input_sdl.cpp |
| `WWHD_TEST_FAIL_COMPILES` | gfx/metal_draw.mm |
| `WWHD_TEST_FULLSCREEN_KEY` | gfx/vulkan/backend.cpp |
| `WWHD_TEST_GAMEPAD_UI_TRACE` | gfx/gamepad_ui_state.cpp |
| `WWHD_TEST_GAMESCOPE` | overlay/window_target.h |
| `WWHD_TEST_GAME_LOAD_MS` | gx2/gx2_core.cpp |
| `WWHD_TEST_GAME_SOURCES` | overlay/overlay.h |
| `WWHD_TEST_GPU_MODEL` | gfx/vulkan/android_driver.cpp |
| `WWHD_TEST_GYRO` | motion/motion.cpp, motion/motion.h |
| `WWHD_TEST_HEADROOM_MB` | platform/memory_trim.h |
| `WWHD_TEST_HOST_CRASH` | main.cpp |
| `WWHD_TEST_LOAD` | savestate.h |
| `WWHD_TEST_MEMORY_TRIM` | gfx/vulkan/backend.cpp |
| `WWHD_TEST_MODE` | gfx/input.mm, platform/input_sdl.cpp |
| `WWHD_TEST_MODES` | gfx/input.mm |
| `WWHD_TEST_MOD_DISABLE` | overlay/overlay.h |
| `WWHD_TEST_MOD_REMOVE` | overlay/overlay.h |
| `WWHD_TEST_MOD_SETUP` | overlay/overlay.h |
| `WWHD_TEST_MOUSE` | motion/motion.h |
| `WWHD_TEST_OFFSCREEN_FRAMES` | gfx/headless_compose.h |
| `WWHD_TEST_ORIGIN` | gfx/input.mm, platform/input_sdl.cpp |
| `WWHD_TEST_ORIGIN_LOAD` | gfx/input.mm, platform/input_sdl.cpp |
| `WWHD_TEST_OVERLAY` | overlay/extensions.h, overlay/overlay.cpp (+1) |
| `WWHD_TEST_PAD` | overlay/overlay.cpp, overlay/text_entry.h |
| `WWHD_TEST_PERF_REPORT` | overlay/overlay.cpp, overlay/overlay.h |
| `WWHD_TEST_PERF_REPORT_AT` | overlay/overlay.cpp, overlay/overlay.h |
| `WWHD_TEST_POST_KEYS` | gfx/input.mm, overlay/overlay.cpp (+2) |
| `WWHD_TEST_PRESENT_MODE` | overlay/overlay.cpp |
| `WWHD_TEST_PRESS` | gfx/input.mm, platform/input_sdl.cpp |
| `WWHD_TEST_QUIT_ANSWER` | gfx/quit_prompt.mm, quit_prompt.h |
| `WWHD_TEST_RENDERER_SWITCH` | gfx/display.mm |
| `WWHD_TEST_RENDER_LOAD_MS` | gx2/gx2_core.cpp |
| `WWHD_TEST_RSTICK` | gfx/input.mm, platform/input_sdl.cpp |
| `WWHD_TEST_RUNTIME_ERROR` | main.cpp |
| `WWHD_TEST_SAVE` | savestate.h |
| `WWHD_TEST_SCREENSHOT` | screenshot.cpp, screenshot.h (+2) |
| `WWHD_TEST_STICK` | gfx/input.mm, platform/input_sdl.cpp |
| `WWHD_TEST_TOUCH` | gfx/display.mm, gfx/display_modes.cpp (+1) |
| `WWHD_THREAD_DUMP` | threads.cpp |
| `WWHD_TICK_STATS` | threads.cpp |
| `WWHD_TRACE_FLIPS` | gx2/gx2_core.cpp |
| `WWHD_TRACE_FUNCS` | trace.cpp |
| `WWHD_TRACE_MSG` | threads.cpp |
| `WWHD_TRACE_PS` | gfx/metal_draw.mm |
| `WWHD_TRACE_STEPS` | gx2/gx2_core.cpp |
| `WWHD_TRACE_SWAP` | gx2/gx2_core.cpp |
| `WWHD_TRACE_VOICE` | hle/ax.cpp |
| `WWHD_TRACE_VPAD` | hle/system_stubs.cpp |
| `WWHD_TRUE60` | gfx/menu.mm, gfx/vulkan/overlay_sdl.cpp (+1) |
| `WWHD_UNCAPPED` | bench.cpp, gx2/gx2.h (+1) |
| `WWHD_USER_DIR` | WwhdActivity.java, main.cpp (+3) |
| `WWHD_USER_DIR_CAPS` | CMakeLists.txt, platform/host.h |
| `WWHD_USER_DIR_LOWER` | CMakeLists.txt, gfx/display.mm (+5) |
| `WWHD_VB_SNAPSHOT` | gfx/metal_draw.mm |
| `WWHD_VERSION` | CMakeLists.txt |
| `WWHD_VIEW_BUTTON` | MenuOptions.java, gfx/display_modes.cpp (+1) |
| `WWHD_VK_ASYNC_PIPELINES` | gfx/vulkan/pipeline_demand.cpp |
| `WWHD_VK_ASYNC_PRESENT` | gfx/vulkan/backend.cpp, report_header.cpp (+1) |
| `WWHD_VK_ASYNC_SHADERS` | gfx/vulkan/shader_compiler.cpp |
| `WWHD_VK_ASYNC_TRANSLATION` | gfx/vulkan/shaders.cpp |
| `WWHD_VK_ATTACHMENT_VIEW_USAGE` | gfx/vulkan/attachment_view_usage.h, gfx/vulkan/backend.cpp (+1) |
| `WWHD_VK_BC_CPU` | gfx/vulkan/backend.cpp, gfx/vulkan/bc_decode.cpp (+2) |
| `WWHD_VK_BC_TIMING` | gfx/vulkan/bc_decode.cpp |
| `WWHD_VK_BC_VERIFY` | gfx/vulkan/bc_decode.cpp |
| `WWHD_VK_BUFFER_CACHE` | gfx/vulkan/buffer_cache.cpp, gfx/vulkan/buffer_cache.h (+1) |
| `WWHD_VK_BUFFER_CACHE_HINTS` | gfx/vulkan/buffer_cache.cpp, gfx/vulkan/buffer_cache.h |
| `WWHD_VK_BUFFER_CACHE_LOG` | gfx/vulkan/buffer_cache.cpp |
| `WWHD_VK_BUFFER_CACHE_MB` | gfx/vulkan/buffer_cache.cpp, gfx/vulkan/buffer_cache.h |
| `WWHD_VK_BUFFER_CACHE_VERIFY` | gfx/vulkan/backend.cpp, gfx/vulkan/buffer_cache.cpp (+2) |
| `WWHD_VK_CLIP_EMULATION` | gfx/vulkan/backend.cpp |
| `WWHD_VK_COMPILE_JOURNAL` | gfx/vulkan/backend.cpp |
| `WWHD_VK_CPU_ONLY_STATS` | gfx/vulkan/backend.cpp, gfx/vulkan/shaders.cpp (+2) |
| `WWHD_VK_DECLARE` | gfx/vulkan/loader.h |
| `WWHD_VK_DEFINE` | gfx/vulkan/loader.cpp |
| `WWHD_VK_DEPTH_COPY` | gfx/vulkan/backend.h, gfx/vulkan/surfaces.cpp |
| `WWHD_VK_DESCRIPTOR_RANKS` | gfx/vulkan/draw.cpp, report_header.cpp |
| `WWHD_VK_DEVICE` | gfx/menu.mm, gfx/vulkan/backend.cpp (+4) |
| `WWHD_VK_DEVICE_FUNCTIONS` | gfx/vulkan/loader.cpp, gfx/vulkan/loader.h |
| `WWHD_VK_DRAW_BATCH` | gfx/vulkan/draw.cpp, main.cpp |
| `WWHD_VK_DRAW_BATCH_CAP` | gfx/vulkan/draw.cpp |
| `WWHD_VK_FASTPATH` | gfx/vulkan/draw.cpp, gx2/state_groups.h |
| `WWHD_VK_FASTPATH_STATS` | gfx/vulkan/draw.cpp |
| `WWHD_VK_FASTPATH_VERIFY` | gfx/vulkan/draw.cpp |
| `WWHD_VK_FEEDBACK_ALLOC_LOG` | gfx/vulkan/draw.cpp |
| `WWHD_VK_FEEDBACK_STATS` | gfx/vulkan/draw.cpp |
| `WWHD_VK_FETCH_MEMO` | gfx/vulkan/shaders.cpp, report_header.cpp |
| `WWHD_VK_FORCE_API` | gfx/vulkan/backend.cpp |
| `WWHD_VK_FORCE_BC_DECODE` | gfx/vulkan/backend.cpp, gfx/vulkan/bc_decode.cpp |
| `WWHD_VK_FORCE_DEPTH_CLAMP_EMULATION` | gfx/vulkan/backend.cpp, gfx/vulkan/shaders.cpp |
| `WWHD_VK_FORCE_INIT_FAIL` | gfx/vulkan/backend.cpp |
| `WWHD_VK_FORCE_RENDERPASS` | gfx/vulkan/backend.cpp, gfx/vulkan/rendering_path.h |
| `WWHD_VK_FUSE_SMALL_REGS` | gx2/gx2_core.cpp |
| `WWHD_VK_GLOBAL_FUNCTIONS` | gfx/vulkan/loader.cpp, gfx/vulkan/loader.h |
| `WWHD_VK_GPU_PASS_TIMESTAMPS` | gfx/vulkan/backend.cpp, gfx/vulkan/smoke.cpp |
| `WWHD_VK_GPU_TIMESTAMPS` | gfx/vulkan/backend.cpp, gfx/vulkan/smoke.cpp (+1) |
| `WWHD_VK_HIDE_EXTENSIONS` | gfx/vulkan/backend.cpp |
| `WWHD_VK_INSTANCE_FUNCTIONS` | gfx/vulkan/loader.cpp, gfx/vulkan/loader.h |
| `WWHD_VK_INVALIDATE_INDEX` | gfx/vulkan/surfaces.cpp |
| `WWHD_VK_INVALIDATE_VERIFY` | gfx/vulkan/surfaces.cpp |
| `WWHD_VK_ISOLATED_SECONDARY` | gfx/vulkan/backend.cpp, main.cpp |
| `WWHD_VK_KEEP_HIDDEN_DRC` | gfx/vulkan/draw.cpp |
| `WWHD_VK_KEY_TRACE` | gfx/vulkan/shaders.cpp |
| `WWHD_VK_LAZY_DRAW_DONE` | gx2/gx2_core.cpp, report_header.cpp (+1) |
| `WWHD_VK_LOAD` | gfx/vulkan/loader.cpp |
| `WWHD_VK_NARROW_BARRIERS` | gfx/vulkan/backend.cpp, gpu_preset.cpp |
| `WWHD_VK_PENDING_CAPTURE_AFTER_LOAD` | gfx/vulkan/backend.cpp |
| `WWHD_VK_PENDING_CAPTURE_DIR` | gfx/vulkan/backend.cpp |
| `WWHD_VK_PENDING_PROOF_DIR` | gfx/vulkan/smoke.cpp |
| `WWHD_VK_PIPELINE_CACHE` | gfx/vulkan/backend.cpp |
| `WWHD_VK_PIPELINE_CACHE_DIR` | gfx/shader_prebuild.h, gfx/vulkan/backend.cpp (+1) |
| `WWHD_VK_PIPELINE_LOOKASIDE` | gfx/vulkan/draw.cpp, report_header.cpp |
| `WWHD_VK_PIPELINE_THREADS` | gfx/vulkan/pipeline_demand.cpp |
| `WWHD_VK_PIPELINE_WARMUP` | gfx/vulkan/backend.cpp |
| `WWHD_VK_PRESENT_MODE` | MenuOptions.java, bench.cpp (+6) |
| `WWHD_VK_PROGRAM_HASH_VERIFY` | gfx/vulkan/shaders.cpp |
| `WWHD_VK_PROGRAM_HASH_WATCH` | gfx/vulkan/shaders.cpp |
| `WWHD_VK_READY_FLIP_PARK` | gx2/gx2_core.cpp |
| `WWHD_VK_READY_FLIP_WAIT` | gx2/gx2_core.cpp |
| `WWHD_VK_RECIPE_RECORD` | gfx/vulkan/headstart.cpp |
| `WWHD_VK_RENDERING_FUNCTIONS` | gfx/vulkan/loader.cpp, gfx/vulkan/loader.h |
| `WWHD_VK_REUSE_FEEDBACK_IMAGES` | gfx/vulkan/draw.cpp, report_header.cpp |
| `WWHD_VK_REUSE_UNIFORM_SNAPSHOTS` | gfx/vulkan/draw.cpp, report_header.cpp |
| `WWHD_VK_REUSE_VERTEX_SNAPSHOTS` | gfx/vulkan/draw.cpp, gfx/vulkan/smoke.cpp (+2) |
| `WWHD_VK_SAMPLER_MEMO` | gfx/vulkan/draw.cpp, report_header.cpp |
| `WWHD_VK_SHADER_ADDRESS_MEMO` | gx2/gx2_core.cpp, report_header.cpp |
| `WWHD_VK_SHADER_CACHE` | gfx/shader_prebuild.h, gfx/vulkan/shader_pack.cpp (+2) |
| `WWHD_VK_SHADER_KEY_DIRTY` | gx2/backend_hooks.h, gx2/gx2_core.cpp (+1) |
| `WWHD_VK_SHADER_KEY_VERIFY` | gfx/vulkan/shaders.cpp, gfx/vulkan/shaders.h |
| `WWHD_VK_SHADER_PACK` | gfx/vulkan/shader_pack.cpp, gfx/vulkan/shader_pack.h (+1) |
| `WWHD_VK_SHADER_PACK_STRICT` | gfx/vulkan/shader_pack.cpp, gfx/vulkan/shader_pack.h (+2) |
| `WWHD_VK_SHADER_PACK_VERIFY` | gfx/vulkan/shader_pack.cpp, gfx/vulkan/shader_pack.h (+2) |
| `WWHD_VK_SHADER_STATE_MEMO` | gfx/vulkan/shaders.cpp, report_header.cpp |
| `WWHD_VK_SHARED_GENERAL` | gfx/vulkan/android_isolated_presenter.cpp, gfx/vulkan/smoke.cpp (+1) |
| `WWHD_VK_SKIP_PENDING` | gfx/vulkan/draw.cpp |
| `WWHD_VK_SKIP_REDUNDANT_BINDS` | gfx/vulkan/draw.cpp, report_header.cpp |
| `WWHD_VK_SKIP_SHADOWS` | MenuOptions.java, gfx/vulkan/draw.cpp (+5) |
| `WWHD_VK_SKIP_VERTEX_BINDS` | gfx/vulkan/draw.cpp, report_header.cpp |
| `WWHD_VK_SPARSE_HASH_ENTRIES` | gfx/vulkan/surfaces.cpp |
| `WWHD_VK_SPARSE_HASH_MEMO` | gfx/vulkan/surfaces.cpp, report_header.cpp |
| `WWHD_VK_SPECIALIZE_INDICES` | gfx/vulkan/draw.cpp, report_header.cpp |
| `WWHD_VK_STATS` | gfx/vulkan/backend.cpp, gfx/vulkan/draw.cpp (+6) |
| `WWHD_VK_STUTTER_TRACE` | gfx/vulkan/stutter_trace.h, tools/bench/stutter_summary.py |
| `WWHD_VK_SYNC_NEGATIVE_CONTROL` | gfx/vulkan/smoke.cpp |
| `WWHD_VK_TEXTURE_BUDGET` | gfx/vulkan/backend.h, gfx/vulkan/surfaces.cpp (+1) |
| `WWHD_VK_TEXTURE_BUDGET_MB` | gfx/vulkan/surfaces.cpp |
| `WWHD_VK_TEXTURE_IDLE_FRAMES` | gfx/vulkan/surfaces.cpp |
| `WWHD_VK_TEXTURE_PIN_FRAMES` | gfx/vulkan/surfaces.cpp |
| `WWHD_VK_TRACE_PASSES` | gfx/vulkan/draw.cpp |
| `WWHD_VK_UNCAPPED` | gx2/gx2.h, gx2/gx2_core.cpp |
| `WWHD_VK_UPLOAD_READS` | gfx/vulkan/backend.cpp, gfx/vulkan/backend.h |
| `WWHD_VK_VALIDATION` | gfx/vulkan/backend.cpp |
| `WWHD_VK_VERTEX_COPY_WINDOW` | gfx/vulkan/draw.cpp, gfx/vulkan/vertex_snapshot_history.h |
| `WWHD_VK_VERTEX_HISTORY_PROBE` | gfx/vulkan/draw.cpp |
| `WWHD_VK_VERTEX_HISTORY_REUSE` | gfx/vulkan/draw.cpp, report_header.cpp |
| `WWHD_VK_VERTEX_WINDOW_STATS` | gfx/vulkan/draw.cpp |
| `WWHD_VOICE_RATE` | hle/ax.cpp |
| `WWHD_VPAD_TRACE` | hle/system_stubs.cpp |
| `WWHD_VSYNC_PRECISE` | gx2/gx2_core.cpp |
| `WWHD_VSYNC_SPIN_US` | threads.cpp |
| `WWHD_WARM_PROGRESS_FILE` | ShaderPrepareActivity.java, gfx/warm_progress.h |
| `WWHD_WATCH` | trace.cpp |
| `WWHD_WATCH_DUMP` | trace.cpp |
| `WWHD_WATCH_EXPR` | trace.cpp |
| `WWHD_WATCH_IF` | trace.cpp |
| `WWHD_WATCH_LIMIT` | trace.cpp |
| `WWHD_WATCH_MEM` | threads.cpp |
| `WWHD_WATCH_R3` | trace.cpp |
| `WWHD_WATCH_STR` | trace.cpp |
| `WWHD_YAZ0_LIB` | tools/shaderprep.py, tools/test_shaderprep.py |
| `WWHD_YAZ0_SUFFIX` | CMakeLists.txt |
