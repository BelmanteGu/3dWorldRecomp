# SM3DW no Adreno 740: estudo de desempenho (GPU, CPU, stutter)

Estudo somente leitura, branch `sm3dw`. Nada foi compilado nem executado; os números vêm dos logs já capturados
em `build/run_prof.txt` e `build/phone_logcat.txt` (S23, `WWHD_PROFILE=1`) e de `llvm-objdump`/`llvm-readelf` sobre o
`android/app/libs/arm64-v8a/libmain.so` já existente. Caminhos de código são relativos a `runtime/src/`.
Tudo marcado **[não verificado]** é hipótese a medir.

## 0. Resumo executivo

| # | Achado | Evidência | Ganho esperado |
|---|---|---|---|
| A | Toda chamada entre funções do jogo é `bl f_XXXX@plt` (29.291 relocs JUMP_SLOT; 54.265 símbolos `f_*` exportados); `g_ppc_trace`/`g_core_preempt` lidos via GOT em cada entrada de função. Falta `-fvisibility=hidden` / `-Bsymbolic`. | `llvm-objdump -d --disassemble-symbols=f_02092A3C libmain.so` (linhas `bl ... <f_02441274@plt>`); `CMakeLists.txt:110` e `:253` sem flags de visibilidade | CPU da thread do jogo: provável 3 a 10 % **[não medido]** |
| B | `perf_hint.cpp` assume jogo de 30 fps: com `hot` (headroom >= 0.72, o S23 mede 0.79 a 0.88) ou modo interp 0, o alvo ADPF vira 33,3 ms num jogo que roda a 60. | `platform/perf_hint.cpp:85-97` (hot, `set_mode_auto(0)`), `:204` (`target = !interp::mode() ? 33'333'333 ...`); log: `[perf] thermal headroom 0.79 (60 fps paused)` em `build/phone_logcat.txt` | Pode estar derrubando clocks: testar |
| C | Todos os passes usam `loadOp=LOAD`/`storeOp=STORE` e os clears são `vkCmdClear*Image` fora de passe (transfer). Em GPU tiled isso é um round-trip completo à memória por clear + por passe. | `gfx/vulkan/draw.cpp:2377-2378`, `:2399-2400`; `gfx/vulkan/surfaces.cpp:1218`, `:1226` | GPU: 0,3 a 1,5 ms **[estimativa]**; medir antes |
| D | `WWHD_SHADOW_SCALE` nunca passa de [1, 4]: `parse_scale` faz `clamp(...,1,4)`; 0,5 vira 1 (o log `shadow maps at 1x` confirma). Além disso só vale para depth com `slices > 1`. | `gfx/vulkan/surfaces.cpp:90-93`, `:175-181`; `build/shadow.txt` (`WWHD_SHADOW_SCALE=0.5` -> `=1`) | Potencial maior de GPU (220 draws depth-only/frame) |
| E | Render thread é o gargalo de CPU (13,5 a 13,9 ms de CPU por frame na cena pesada) e a thread do jogo espera 5,5 a 12 ms/frame no `GX2DrawDone`. Fases mais caras por draw: texturas, pass, descritores, shader, uniforms. | `build/run_prof.txt` (frames 600 a 1080), tabela 1.1 | Qualquer corte de CPU do render vira fps direto |
| F | Compilador de pipelines sob demanda é **uma** thread e fila máxima de 8. Cada pipeline novo bloqueia a render thread até compilar. | `gfx/vulkan/pipeline_demand.cpp:52-64`, `:137`, `:225` | Stutter de cena nova |
| G | O head start gerado contém microcódigo do jogo (não pode ir no APK) e os pipelines (tipo 4) carregam fingerprint do dispositivo, driver **e commit/versão do build**. | `tools/shaderprep.py:41-58`; `gfx/vulkan/backend.cpp:256-275` | Solução: enviar só o *template* sem código; gerar no aparelho |

## 1. GPU e renderer

### 1.1 Onde o render thread gasta CPU (S23, cena pesada)

Fonte: últimas janelas de 120 frames de `build/run_prof.txt` (render thread CPU 13,45 a 13,87 ms/frame, 493 a 557 draws/frame,
16,5 a 18,7 mil escritas de registrador/frame). Fases amostradas (1 draw em 64, `render_prof.h:1-12`) em ms/frame:

| Fase | ms/frame | Onde no código |
|---|---|---|
| ops `draw` (total) | 10,2 a 11,9 (≈ 21,8 µs/draw) | `gx2/gx2_core.cpp` `execute_one`, `gfx/vulkan/draw.cpp` |
| textures | 2,1 a 3,0 | `draw.cpp:1861-1906`: por textura e por draw: `sampled_texture` (busca multimap + score, `surfaces.cpp:1061-1110`), `upload_surface` (1x por frame via `lastCheckedFrame`, `surfaces.cpp:1123-1127`), view, `transition_image`, sampler |
| pass | 1,75 a 2,3 | `draw.cpp:2328-2415` (reuso de passe, `vkCmdBeginRendering`) |
| descriptors | 1,1 a 1,6 | `draw.cpp:1956-1977` (cache por hash, `vkAllocateDescriptorSets` + `vkUpdateDescriptorSets` só em miss) |
| shader | 0,95 a 1,4 | `shaders.cpp` (chave de estado) |
| uniforms | 0,94 a 1,07 | `draw.cpp:1840-1853` |
| submit | ≈ 0,96 | `draw.cpp:2703-2707` (`WWHD_VK_DRAW_BATCH`=2048, `main.cpp:400`) |
| vertex / record / indices / targets / pipeline | 0,46-0,64 / ≈ 0,5 / 0,25-0,35 / ≈ 0,3 / ≈ 0,24 | |
| ops `regs` | 2,1 a 2,4 | `gx2_core.cpp:141` (`WWHD_VK_FUSE_SMALL_REGS`, já ligado) |
| ops `swap` | 1,4 a 3,4 | composição/apresentação, `present.cpp:360-427` |
| ops `invalidate` | 0,5 a 0,66 (380 a 444 chamadas/frame) | |

Dados úteis: só ≈ 4,9 MiB/frame de leitura única de guest, ≈ 0,1 MiB copiados (vertex reuse funciona); `DrawDone` 2/frame custa
5,5 a 12 ms de espera à thread do jogo (o `lazy_draw_done` já é padrão, `gx2_core.cpp:392-398`, mas a fence ainda espera o render thread
gravar tudo: `gx2_core.cpp:264-288`). Ou seja, na cena pesada o jogo espera o render thread, não o contrário.

Estados de draw já deduplicados (viewport, scissor, blend, stencil, pipeline): `draw.cpp:2342-2363`, `:2475-2530`.
Não deduplicados por padrão e **desligados** (default `=1` necessário): `WWHD_VK_SKIP_REDUNDANT_BINDS` (`draw.cpp:2443`),
`WWHD_VK_SAMPLER_MEMO` (`draw.cpp:1188`), `WWHD_VK_SKIP_VERTEX_BINDS` (`draw.cpp:2645`), `WWHD_VK_SHADER_STATE_MEMO`
(`shaders.cpp:965`), `WWHD_VK_FETCH_MEMO` (`shaders.cpp:847`), `WWHD_VK_SHADER_ADDRESS_MEMO` (`gx2_core.cpp:63`),
`WWHD_VK_REUSE_VERTEX/UNIFORM_SNAPSHOTS`, `WWHD_VK_PIPELINE_LOOKASIDE` (`draw.cpp:641`), `WWHD_VK_DESCRIPTOR_RANKS` (`draw.cpp:1718`).
Atenção: `default_vulkan_cpu_paths()` (`main.cpp:281-289`) liga a lista `reporthdr::kVulkanCpuPaths` (`report_header.cpp:27-31`) por padrão, então
parte desses já está ativa. **Não verifiquei qual subconjunto** (a lista está em `report_header.cpp`); o log de boot com `WWHD_VK_STATS=1` ou o
cabeçalho do relatório do overlay mostra o estado de cada um.

### 1.2 Estrutura de passes, load/store, barreiras (tiled GPU)

| Item | Estado | Referência | Avaliação |
|---|---|---|---|
| loadOp/storeOp | Sempre `LOAD`/`STORE` em cor e depth | `draw.cpp:2377-2378`, `:2399-2400` | Em Adreno (GMEM) cada abertura de passe lê a imagem inteira da memória e cada fim a grava. `DONT_CARE`/`CLEAR` nunca é usado nos draws |
| Clear | `vkCmdClearColorImage`/`vkCmdClearDepthStencilImage` com layout TRANSFER_DST, depois novo barrier para ATTACHMENT e passe com LOAD | `surfaces.cpp:1191-1226`, `transition_image` `backend.cpp:889-926` | Candidato nº 1 de ganho de largura de banda: adiar o clear e fundi-lo ao `loadOp=CLEAR` do próximo passe daquele alvo (não existe infraestrutura de clear pendente: `grep pendingClear` vazio) |
| Reuso de passe | Só se mesmos attachments, mesmas slices e mesmo tamanho; qualquer upload de textura, barrier de layout ou feedback termina o passe | `draw.cpp:2328-2337`, `transition_image` | Medir quantos passes/frame: `WWHD_VK_STATS=1` (`backend.cpp:2844`, `renderPassCount`) |
| Barreiras | `ALL_COMMANDS` -> stage, access `MEMORY_READ|WRITE` | `backend.cpp:916-926` | Conservador. `WWHD_VK_NARROW_BARRIERS=1` existe, mas está documentado com device-lost 4/10 no Adreno 650 + Turnip (`backend.cpp:870-875`). Em driver Qualcomm 512 **não testado** |
| Imagens | Todas `MUTABLE_FORMAT_BIT` + `SAMPLED|TRANSFER_SRC|TRANSFER_DST` + attachment, sem `VkImageFormatListCreateInfo`; memória alocada uma a uma, sem aliasing/transient | `surfaces.cpp:634-637`, `:640-650`; `vkAllocateMemory` por surface | Pode impedir/limitar UBWC (o README do fork cita desligar UBWC no Turnip 6xx). **[não verificado no driver Qualcomm 512.676]** |
| TRANSIENT/LAZILY_ALLOCATED, subpass | Não usados | (nenhuma ocorrência) | Pouco aplicável: o jogo amostra os alvos depois; sem subpass no modelo GX2 |
| Multisample | 1 amostra em tudo | `draw.cpp:910` | Sem MSAA a remover |
| FXAA | Pós em `present.cpp` só se ligado; padrão desligado | `settings.cpp:22` | Já está no mínimo |
| Aniso | Forçada só se `WWHD_ANISO=1` (padrão 0); o jogo pede o seu valor via `MAX_ANISO_RATIO` até 16x | `settings.cpp:21`, `draw.cpp:1266-1274` | Não há teto de aniso no Android. Ideia: limitar `maxAnisotropy` a 2 ou 4 (precisa de mudança de código/env novo) |
| AO / `ao_hires` | Já padrão **off no Android** (`kHiresDefault=false`). Mas o código do AO é por identidade de shader WWHD (`shader_identity.h:7-17`, `ProgramKind::OcclusionPixel/DepthDownsample`) | `settings.cpp:11-19`, `draw.cpp:1870`, `:2692` | **Provavelmente inerte no SM3DW** (shaders de outro jogo). **[não verificado]**: não há `[gfx] AO` no log do aparelho; nada a ganhar aqui |
| Leituras de volta | `write-back to guest memory: 0 surfaces, 0.0 KiB` | `run_prof.txt` | Sem readback no caso normal |
| GamePad (DRC) | Tela off (`[display] GamePad screen mode: off`); o jogo ainda emite ≈ 5 draws/frame em 854x480 e 2 scans/frame, custo de render ≈ 0,03 ms | `run_prof.txt`; `present.cpp:491-515` | Ganho pequeno no render. Parar o jogo de produzir a imagem do DRC exigiria hook no guest (**[não verificado]** quanto custaria na thread do jogo) |
| Overlay | `WWHD_PERF_OVERLAY_DEFAULT=true` no SM3DW: ImGui + queries de timestamp por frame | `CMakeLists.txt:77`, `overlay/overlay.cpp:102-105`, `backend.cpp:~780-800` | As medições de 16,7 ms/81 % já incluem o custo do overlay. Medir com ele oculto |

Medida certa antes de mexer: `WWHD_VK_GPU_PASS_TIMESTAMPS=1` (`backend.cpp:665-690`) imprime, a cada 120 frames, os 8 "scopes" mais caros agrupados por
extensão/formato/depth-only com ms/frame e draws/frame (`backend.cpp:~755-775`). Os intervalos são por passe e podem se sobrepor: não são tempo ocupado de GPU,
mas ordenam o custo relativo (shadow x 1280x720 x pós).

### 1.3 Mapa de sombras e `WWHD_SHADOW_SCALE`

- `parse_scale` (`surfaces.cpp:90-93`): `atof` e `std::clamp(f > 0 ? f : 1.0f, 1.0f, 4.0f)`. Qualquer valor < 1 vira 1. Idem `WWHD_RES_SCALE` e `set_res_scale` (`:94-101`), e o equivalente Metal (`gfx/metal_surfaces.mm:46`).
- Explica o log `[gfx] WWHD_SHADOW_SCALE=1: shadow maps at 1x` com `env.txt: WWHD_SHADOW_SCALE=0.5` (`build/shadow.txt`).
- Condição de uso (`surfaces.cpp:179`): `shadow && s->isDepth && s->slices > 1`, ou seja, só depth em **array**. Se as sombras do SM3DW são depth 2D de camada única, o env não faz nada nem para valores > 1: elas seguem `res_scale()`. **[não verificado]**: o contador `[draws] per target` (`draw.cpp:2262-2279`, aprox.) chaveia só pelo tamanho do alvo de **cor**, então os ≈ 220 draws/frame em "0x0" misturam shadow maps e qualquer depth-only. Falta um log do tamanho/slices do depth.
- Reduzir abaixo de 1 é estruturalmente possível: `extent = ceil(width * scale * ax)` (`surfaces.cpp:607`), `sx = extent/width` (`:616`), e o draw usa `sx/sy` do alvo para viewport e scissor (`draw.cpp:2293-2297`, `:2475-2530`); a amostragem usa coordenadas normalizadas e `texScale` (`draw.cpp:1870-1880`). Risco: shaders de sombra do jogo que usam texel size absoluto, ou `GX2CopySurface` entre alvos de tamanhos diferentes (`resample` em `surfaces.cpp:961`).
- Mudança mínima proposta (código): permitir `[0.25, 4]` só para `WWHD_SHADOW_SCALE` (função separada), e logar `w x h x slices` do primeiro depth-only de cada alvo.

## 2. CPU

### 2.1 Código do jogo (libmain.so)

| Ponto | Estado atual | Referência | Proposta |
|---|---|---|---|
| Chamadas via PLT | `bl f_...@plt` em todas as chamadas entre funções; `.plt` = 468 KB, `.got.plt` = 234 KB, 54.265 `f_*` em `.dynsym` | `llvm-readelf --dyn-syms/-r libmain.so`; `CMakeLists.txt:109-113` | `target_compile_options(gamecode PRIVATE -fvisibility=hidden)` (os `extern` de `ppc.h` herdam; as definições do runtime têm de ficar visíveis dentro do mesmo `.so`, o que é o caso) e/ou `-Wl,-Bsymbolic` no link de `wwhd`. Elimina o stub `adrp/ldr/br` e o load do GOT por chamada. Verificar que `index_game_functions` (`main.cpp:320`) e a tabela de salto não dependem de `dlsym` |
| `PPC_ENTER` | Lê `g_ppc_trace` e `g_core_preempt[c->core]` em toda entrada (`include/ppc.h:59-63`); no binário são 2 cargas via GOT + 2 branches | `ppc.h:59-63`; disassembly de `f_02092A3C` | Com hidden isso vira `adrp+ldr` direto. Opcional: gerar variante sem `g_ppc_trace` (é depuração) |
| Nível de otimização | `-O3 -ffp-contract=off -fno-strict-aliasing -fwrapv` só em `gamecode`; runtime em Release (-O2) | `CMakeLists.txt:110`; `build_native.sh:57` | `-O3` pode inchar o texto (34 MB em `.text`) e piorar I-cache/iTLB; testar `-O2` e `-Os` em `gamecode`. `-ffp-contract=off` é necessário (exatidão de FP): manter |
| `-mcpu` | Genérico (`CPU=generic`, sem flags) | `android/build_native.sh:19-22`, `:46-47` | `CPU=cortex-a710`/`-march=armv8.2-a+lse` etc. (o script já aceita `CPU=`). Ganho esperado pequeno (LSE para `lwarx/stwcx`, melhor agendamento no X3); risco: não roda em SoCs sem ARMv8.2 |
| LTO/PGO | Nenhum | `CMakeLists.txt` (sem `INTERPROCEDURAL_OPTIMIZATION`) | LTO entre `gamecode` e runtime tem pouco a ganhar (chamadas são HLE indiretas). PGO/AutoFDO é o que reduz I-cache miss em 34 MB de texto, mas exige captura no aparelho (`simpleperf` + profdata): ciclo longo, deixar para depois |
| Recompilador | Já ligados por padrão: `crlive`, `leaflocal`, `nonleaf` (registradores em locais) | `tools/recomp/recomp.py:24-26`, `:56-66`; saída `build/gen3dw/code_005.c` mostra `cr_set_s_m` e spill só dos regs vivos | Sem opção óbvia a mais em `recomp.py` (só `--insns-per-file`, `--build`, `--mod-hooks`, `--decomp-hooks`). Não há `--no-trace`. Seria preciso mexer em `ppc.h` |
| Acesso a memória big-endian | `ld32 = rev + orr 0x1000000000` (base é imediato lógico, custo zero de soma); `st32` idem | `include/ppc.h:93-101`, disassembly (`rev w14,w8; orr x15,x12,#0x1000000000; str`) | Já eficiente; não há hot spot de swap a atacar. Vertex data big-endian vai para o `unique guest` (4,1 MiB/frame) e é tratada no renderer, não medida aqui |
| Loops de espera | `PPC_LOOP()` é só barreira de compilador (`ppc.h:90`); `OSYieldThread` = `std::this_thread::yield()` com `BlockingScope` (`threads.cpp:795-799`); `OSSleepTicks` dorme de verdade (`:800-812`) | | Não vi spin livre no runtime; spin dentro do código do jogo (`while (*flag)`) gasta CPU de uma "core" emulada. **[não medido]**: use `WWHD_SAMPLE_THREADS=1` (`thread_sampler.cpp`) para ver quanta CPU as threads do jogo queimam em espera |
| Thread do jogo vs render | Na cena pesada a thread do jogo está **ociosa** (espera DrawDone 5,5-12 ms/frame); o limite é o render thread | `run_prof.txt` | Otimizar o código do jogo só ajuda em carga de cena/transições; o ganho de fps vem do render thread |

### 2.2 Afinidade e hints

- `perf_hint::add_current_thread` pina **só** a thread principal do jogo (primeira thread sem nome, `threads.cpp:605-610`) e o render thread (`gx2_core.cpp:208`) nos "N cores mais rápidos" lendo `cpuinfo_max_freq` (`perf_hint.cpp:143-170`). No S23 o log diz `thread ... on the 5 fastest cores` (`phone logs`): o prime X3 + os 4 médios. As duas threads quentes e todo o resto dos threads que o driver cria compartilham esse conjunto; threads de compilação (`Vulkan demand`, shader translator, warmup) **não têm prioridade nem afinidade** própria (nenhum `setpriority`/`sched_setaffinity` fora de `perf_hint.cpp`). Ideia: nice/afinidade para os cores pequenos nas threads de compilação, para não disputar o prime com o render thread. **[não medido]**.
- Bug de premissa em `perf_hint.cpp` (ver B no resumo): `hot` desliga o modo interp (`:96-97`), e então `frame_done` usa 33,3 ms de alvo ADPF (`:204`) enquanto o intervalo reportado é ≈ 16,7 ms. Para o SM3DW (60 fps nativo, `swap interval 1` no log) o alvo deveria ser 16,67 ms sempre. Além disso, `interp::mode()==1` é ligado por padrão no Android (`main.cpp:403`) mesmo sem hooks de interpolação do SM3DW (`hooks_sm3dw.txt` existe, mas o profiler mostra `120 hold`/`0.0 logic steps/s`: **[não verificado]** o que o modo 1 faz de fato neste jogo). Teste direto: `WWHD_INTERP=0` e, separadamente, `WWHD_PERF_HINT=0` (`perf_hint.cpp:48`), `WWHD_FAST_CORES=0` (`:177`).
- Linha de cuidado: o APK de teste é `assembleDebug` (`android/build_sm3dw_apk.sh:11`, `debuggable`). As libs nativas são Release, mas um build `assembleRelease` assinado elimina variáveis (ART, `run-as`). Efeito **[não medido]**.

### 2.3 Fila de pipelines e threads de compilação (ver também seção 3)

`warm_resolved_pipeline_recipes` usa até 4 threads (`pipeline_worker.cpp:449-455`), mas só para aquecimento offline; em jogo o compilador sob demanda é uma única thread (`pipeline_demand.cpp:59`, `:137`).

## 3. Stutter de shader/pipeline

Mecanismo (fluxo real):

1. Shader GX2 -> GLSL -> SPIR-V (`shader_compiler.cpp`, thread única, `:86` e `:173`), cache em disco `shadercache/vulkan-shaders/spirv.bin` (`docs/vulkan.md:~300`).
2. Pipeline ausente: `build_pipeline_async` põe um job na fila do `Compiler` (máx. 8 pendentes, `pipeline_demand.cpp:225`) e o draw **espera** o resultado; durante a espera pode re-apresentar o último frame (`presentWhilePending`, `:238-262`). No caminho de draw, se a preparação "exata" está ativa a espera é diferida para o replay (`draw.cpp:2314`, `:2418-2426`).
3. Recipes: cada pipeline criado grava um recipe em `<cache>.vkcache.recipes`; no próximo boot a thread "Vulkan warmup" os reconstrói em segundo plano (`pipeline_worker.cpp:242-310`). O arquivo no aparelho tem 93,8 MB (`build/vkcache/vk-6c8e66114c47.bin.recipes`).
4. O cache nativo do driver (`VkPipelineCache`) é mesclado em checkpoints: `checkpoint_pipeline_cache` a cada 60 frames mescla o cache da thread de demanda (`backend.cpp:386-396`) e grava 120 frames depois se sujo; teto de 64 MiB por arquivo (`backend.cpp:126`, `:168`). **[não verificado]** se o cache nativo do Adreno 740 para o SM3DW passa de 64 MiB (nesse caso é descartado por inteiro no boot, `:168`): olhar `[vulkan cache] loaded N bytes` com `WWHD_VK_STATS=1`.
5. Head start (`headstart.cpp`, `shader_prebuild.h:431`): por padrão `game/shadercache/headstart.bin` (ou `WWHD_HEADSTART=<arquivo>`, `backend.cpp:279-281`). Registros tipo 1 = shaders (microcódigo do jogo), tipo 4 = banco Vulkan (chaves exatas, SPIR-V, descritores, estado fixo, fingerprint).

Pontos que mantêm o stutter mesmo depois do fix do checkpoint:

- **Fingerprint inclui o build**: `backend.cpp:256-276` mistura `vendorID`, `deviceID`, `driverVersion`, UUID do cache, features habilitadas **e `build::commit()`, `build::version()`, `WWHD_PIPELINE_RUNTIME_ID`**. Cada rebuild (o log diz `54d404e-dirty`) invalida todos os recipes locais e o tipo 4 de qualquer head start prévio. Vale conferir `[vulkan warmup] ... ignored: incompatible pipeline recipe database` (`pipeline_worker.cpp:263`, `pipeline_recipe.cpp:293-295`) em cada novo APK.
- **Compilador de demanda serial**: uma thread, fila 8. Cena nova com 100+ pipelines = fila serial. Ideia: pool de 2 a 3 threads (o `VkPipelineCache` é sincronizado internamente; o cache é compartilhado, `pipeline_demand.cpp:67-76`), com as de menor prioridade (nice) para não matar o render thread. Risco: ordem de resolução e `c.changed`/`merge` (`:278-297`); medir antes com `WWHD_VK_STUTTER_TRACE=<arquivo>` (`stutter_trace.cpp:25`, eventos `pipeline_request/pipeline_wait/pipeline_pending`, `pipeline_demand.cpp`).
- Cargas de textura: o stall `textures 7.4 ms/frame` na carga de cena (`run_prof.txt`, frame 480) é decode CPU no render thread (`decode_level`, staging por textura com `create_buffer`, `surfaces.cpp:1164-1180`). BC está nativo no Adreno 740 (não há a linha `no BC texture support`, `backend.cpp:3411`).

### 3.1 Enviar head start dentro do APK

- **Não colocar `build/vkcache/headstart_vk.bin` (10 MB) no APK**: `tools/shaderprep.py:41-58` diz "Never distribute that generated file" (carrega microcódigo/SPIR-V derivado do jogo) e o tipo 4 amarra ao aparelho/driver/build. É um arquivo **local**.
- O que pode ir no APK é o **template** sem código (`WWHT0003`, `shaderprep.py:48-53`); `build/vkcache/template_vk.bin` (61.930 bytes, destilado do `.recipes` do aparelho) é o candidato. O aparelho então gera o head start a partir do próprio jogo extraído: a infraestrutura existe (`SetupService.java:197`, `ShaderPrepareActivity.java`, `libyaz0.so`, Python embutido em `android/python`) e o runtime procura `game/shadercache/headstart.bin` (`shader_prebuild.h:431-441`).
- Falta confirmar: (a) onde `SetupService` espera o template no APK/assets (`grep template` em `android/app/src/main/java` não achou, e `tools/shadercache/template.bin` é buscado de um repo privado: `tools/shadercache/README.md`, `template.ref`); (b) se `shaderprep.py build --template` funciona com o conjunto de shaders do SM3DW (`template_both.bin` 1,06 MB parece misto); (c) a ordem: o fingerprint do build faz o passo de "preparar shaders" ser refeito a cada rebuild.
- Teste mais barato do `headstart_vk.bin` **como está** (no mesmo aparelho e no mesmo build): copiar para `<files>/game/shadercache/headstart.bin` (ou `WWHD_HEADSTART=/sdcard/Android/data/<pkg>/files/headstart_vk.bin` no `env.txt`), apagar `*.recipes` e o cache nativo para simular instalação limpa, e comparar `[fps]`/`WWHD_VK_STUTTER_TRACE` nas três primeiras cargas de cena.

## 4. Driver da GPU

- Carregamento de driver custom: `gfx/vulkan/android_driver.cpp:42-60` (`adrenotools_open_libvulkan` com `ADRENOTOOLS_DRIVER_CUSTOM`, hooks de `nativeLibraryDir`, `main_hook`/`hook_impl`/`file_redirect_hook`/`gsl_alloc_hook` copiados em `build_native.sh:67-72`); armazenamento em `<internal>/gpu-drivers/<id>/` com sonda de 120 frames e volta ao driver do sistema em falha (`driver_store.cpp:67-76`). Cache de pipeline separado por driver (`pipeline_directory`, `android_driver.cpp:80`). Instalação: ZIP com `meta.json` + `.so` arm64 (`driver_store.cpp:36-50`); só pela UI (`request_install`), **não** por `env.txt`.
- README do fork (`/private/tmp/claude-501/wwhd-plus/README.md:303-313`): no Adreno 650, o driver Qualcomm (v502) teve o **menor tempo de GPU** (12-14 ms vs 17-22 ms do Turnip), e Turnip só ganhou em estabilidade/hangs. Turnip acima de 1x é lento no 650. Para o 740 (Qualcomm 512.676 no S23) a recomendação extrapolada é: **manter o driver do sistema para tempo de GPU** e usar Turnip apenas se houver erro de renderização ou hang. Qualquer comparação 740 é **[não verificada]**.
- Experimento cabível: Turnip recente (`26.x`) no S23 com os mesmos `env.txt`, 1x, comparando `GPU busy`/`GPU interval` do overlay. Atenção: o `fingerprint` muda com o driver, então o cache de pipeline recomeça do zero (`android_driver.cpp:80` + `backend.cpp:256-263`).

## 5. Experimentos ranqueados

Legenda: **env** = dá para mudar em `env.txt` sem rebuild (`main.cpp:404-416`, uma `NOME=valor` por linha). Medir sempre com
`WWHD_PROFILE=1` (relatório a cada 120 frames: `render thread CPU`, `ops ms/frame`, `draw phases`, `waits`) e o overlay (GPU busy/interval).
Repita no mesmo ponto da cena de attract e use a janela 600 a 1080 de `build/run_prof.txt` como base (CPU 13,5-13,9 ms, ≈ 520 draws, DrawDone 5,5-12 ms).

| # | Mudança | Ganho esperado | Risco | Como medir | env? |
|---|---|---|---|---|---|
| 1 | Perfil de GPU por passe: `WWHD_VK_GPU_PASS_TIMESTAMPS=1` (+ `WWHD_VK_GPU_TIMESTAMPS=1`), mais overlay oculto vs visível | Nenhum direto; decide 3, 4, 5 e 8 | Baixo (overhead de queries) | Linhas `[vulkan GPU scope N]` com ms/frame e `depth-only` | **Sim** |
| 2 | `-fvisibility=hidden` em `gamecode` + `-Wl,-Bsymbolic` (ver 2.1) | 3-10 % de CPU do jogo; render thread não muda | Baixo-médio (símbolos usados por `dlsym`/crash_addr) | CPU da thread "game" (`WWHD_SAMPLE_THREADS=1`) e tempo de cena/carregamento | Não (rebuild) |
| 3 | Reparar `perf_hint` para 60 fps nativo: alvo ADPF = 16,67 ms fixo para `WWHD_GAME_RPX=RedCarpet.rpx`, não pausar por `hot` (ou limite mais alto) e não depender do `interp::mode()` | Pode recuperar clocks nos dips 30 a 50 fps | Baixo; se esquentar demais, o throttle do SO decide | `[fps]` e `[perf] thermal headroom` por 5 min; comparar com `WWHD_PERF_HINT=0` e `WWHD_INTERP=0` | Parcial: `WWHD_PERF_HINT=0`, `WWHD_INTERP=0`, `WWHD_FAST_CORES=0` |
| 4 | Fundir clear + passe seguinte (`loadOp=CLEAR`, pular `vkCmdClear*Image`) e `storeOp=DONT_CARE` de depth em passes depth-only sem leitura posterior | 0,3-1,5 ms de GPU (estimado); menos barriers | Médio (precisa saber se o clear cobre todo o alvo e se o alvo é lido depois) | `GPU scope` do passo 1 e `renderPassCount` (`WWHD_VK_STATS=1`) | Não |
| 5 | Sombras < 1x: relaxar o `clamp` só para `WWHD_SHADOW_SCALE` (`surfaces.cpp:90-93`), logar tamanho e slices do depth-only, e aceitar depth 2D (`surfaces.cpp:179`) | Alto se as sombras forem grande parte dos 13,4 ms de GPU (220 draws/frame) | Médio: sombra borrada/instável; shaders com texel size absoluto | Passo 1, depois screenshot comparativo | Não (precisa código); com 1 só a parte `> 1` já vale |
| 6 | Pool de 2-3 threads no compilador de demanda e prioridade baixa para threads de compilação | Reduz a duração dos stalls de cena nova | Médio (ordem/mesclagem de cache) | `WWHD_VK_STUTTER_TRACE=<arq>`: soma de `pipeline_wait`; frames < 30 fps nas 3 primeiras cargas | Não |
| 7 | Gerar head start no aparelho com `template_vk.bin` no APK (ou cópia manual do `headstart_vk.bin` para teste) | Elimina a maior parte do stutter na primeira execução | Baixo (fallback é compilar sob demanda); legalmente só o template viaja | Primeira execução limpa: `[fps]` e `[vulkan warmup]` | Parcial: `WWHD_HEADSTART=` e cópia de arquivo para testar |
| 8 | Limitar aniso a 2x/4x no Android (nova var. ou constante) | Pequeno-médio na banda de textura | Baixo (nitidez no chão inclinado) | Passo 1; comparar `GPU interval` | Não |
| 9 | Ligar memos opt-in um a um: `WWHD_VK_SKIP_REDUNDANT_BINDS=1`, `WWHD_VK_SAMPLER_MEMO=1`, `WWHD_VK_SKIP_VERTEX_BINDS=1`, `WWHD_VK_SHADER_STATE_MEMO=1`, `WWHD_VK_FETCH_MEMO=1`, `WWHD_VK_SHADER_ADDRESS_MEMO=1`, `WWHD_VK_PIPELINE_LOOKASIDE=1`, `WWHD_VK_REUSE_VERTEX_SNAPSHOTS=1`, `WWHD_VK_REUSE_UNIFORM_SNAPSHOTS=1`, `WWHD_VK_DESCRIPTOR_RANKS=1` | 0,3-1,5 ms de CPU de render (as fases `descriptors/shader/textures/vertex` somam ≈ 5 ms) | Baixo-médio: cada um é um caminho de cache; conferir quais já entram em `kVulkanCpuPaths` | `draw phases` e `render thread CPU` de `WWHD_PROFILE=1` | **Sim** |
| 10 | Cache de textura por palavras do descritor (evita `find_or_create_surface` + view + sampler por draw), fase `textures` (≈ 2-3 ms) | 0,5-1,5 ms de CPU | Médio (coerência com alias/rescale) | `textures` no relatório | Não |
| 11 | `-O2`/`-Os` em `gamecode`; `CPU=cortex-a710` (ou `-march=armv8.2-a+lse`) | 0-5 % no jogo | Baixo-médio (compatibilidade com CPUs antigas) | CPU thread jogo, tempo de carga | Não (rebuild; `CPU=` em `build_native.sh`) |
| 12 | Driver custom: Turnip x Qualcomm 512.676 no S23 | Incerto; no 650 o Qualcomm foi mais rápido | Médio (hang, cache recomeça) | `GPU busy`/`GPU interval` | Não (UI) |
| 13 | `WWHD_VK_NARROW_BARRIERS=1`, `WWHD_VK_PRESENT_MODE=mailbox`, `WWHD_VK_ASYNC_PRESENT` | Pequeno | Alto para barriers (device-lost documentado no 650/Turnip) | `GPU interval`, travadas | **Sim** |
| 14 | Verificar UBWC: tentar criar imagens de cor sem `MUTABLE_FORMAT_BIT` e sem `TRANSFER_SRC/DST` quando não usadas como cópia; usar `VkImageFormatListCreateInfo` | Desconhecido, pode ser relevante em banda | Alto (cópias, views sRGB e aliasing de formato dependem disso: `surfaces.cpp:1099-1108`) | `GPU interval` | Não |

Ordem sugerida: 1 (medir), 3 e 9 (só configuração/pouco código), 2 e 11 (build), 6 e 7 (stutter), depois 4/5/8/10 guiados pelo resultado do 1.

## 6. O que não pude verificar

- Tempo de GPU por passe, tamanho/slices das sombras, número de passes por frame e quanto das ≈ 220 draws "0x0" é shadow map.
- Quais memos de CPU já entram no padrão (`reporthdr::kVulkanCpuPaths`, `report_header.cpp:27-31`, não li a lista completa).
- Se o modo interp 1 afeta o SM3DW, e quanto o alvo ADPF de 33 ms custa de fato (hipótese baseada no código e nos logs `60 fps paused`).
- Impacto real de PLT/GOT (medi o código gerado, não o tempo), de `-O2` vs `-O3`, de `-mcpu`.
- Se `MUTABLE_FORMAT_BIT` impede UBWC no Qualcomm 512.676.
- Se o head start de 10 MB melhora algo no aparelho (não foi testado), e como o app localiza o template do SM3DW no APK.
- Custo na thread do jogo de preparar o frame do GamePad com a tela desligada.
