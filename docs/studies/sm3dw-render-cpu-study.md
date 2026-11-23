# SM3DW no Adreno 740: estudo da CPU do render thread

Estudo somente leitura (branch `sm3dw`). Nada foi compilado nem executado no aparelho. Os números vêm de `build/run_prof.txt`,
`build/res_1.txt`, `build/res_0.75.txt` (S23, `WWHD_PROFILE=1`, janelas de 120 frames) e de `build/gpu_pass.txt` (scopes de GPU).
Caminhos relativos a `runtime/src/`. **[inferido]** = deduzido do código, **[não verificado]** = precisa de medição.
Este documento complementa `docs/sm3dw-perf-study.md` e corrige alguns pontos dele (seção 0).

## 0. Resumo e correções ao estudo anterior

| # | Achado | Evidência |
|---|---|---|
| 1 | O tempo por op do profiler é **relógio de parede**, não CPU. Na janela do frame 2280: 20,25 ms/frame = ops 17,52 + idle 2,48; CPU da thread = 13,99. Ou seja **3,5 ms/frame dentro de ops sem CPU** (bloqueio/preempção/espera de present). Nas janelas mais lentas de `res_1` (frame 3240: 26,4 ms, CPU 21,0, idle 0,3) são ≈ 5 ms. | `render_prof.cpp:100-113` (`now_ns`), `:273-276` |
| 2 | A 60 fps (janelas de 16,63-16,7 ms) a thread **não está saturada**: CPU 13,9 + idle 2,6 ≈ frame. Ela só vira o limite quando a CPU sobe (13 → 15-21 ms nas janelas finais, provável clock/thermal **[não verificado]**), e aí idle cai a 0,3 ms e `DrawDone` sobe a 8-14 ms. | tabela da seção 2.3 |
| 3 | **Todos os 15 memos de CPU já estão ligados por padrão** (`default_vulkan_cpu_paths()` roda antes do `env.txt`). O experimento nº 9 do estudo anterior não existe mais; `NAME=0` no `env.txt` serve para ablação (medir a contribuição de cada um). | `main.cpp:281-289`, `:384`, `:402-414`; lista em `report_header.cpp:27-31` |
| 4 | `WWHD_SAMPLE_THREADS` (thread_sampler) é só Windows ARM64. No Android o perfil de CPU tem de vir de `simpleperf` (APK é debuggable). | `platform/thread_sampler.h:1-12` |
| 5 | `WWHD_VK_DRAW_BATCH=2048` **nunca dispara** no SM3DW: ~520 draws/frame < 2048, então não há submissão no meio do frame; a GPU só recebe trabalho no `GX2Flush` (1/frame) e no swap. | `draw.cpp:71-85`, `:2700-2708`, `main.cpp:400` |
| 6 | A fase "submit" (0,9 ms) **não é `vkQueueSubmit`**: ela cobre `vkCmdBindIndexBuffer` + `vkCmdDrawIndexed` + `mark_gpu_written` + `after_draw`. É o custo do driver Adreno gravando o draw (~1,7 µs/draw). | marks em `draw.cpp:2660` (kVertex) e `:2709` (kSubmit) |
| 7 | O log `[gfx] mip chain for ...` é limitado a 8 linhas, mas revela algo real: um alvo GPU (`F4F0C000`, 640x360, 4 níveis) é amostrado com cadeia de mips regenerada por `vkCmdCopyImage` + blits, até 4 vezes num mesmo frame (a contagem "N do próprio jogo" sobe 0,1,1,2,2,3,3,3). | `surfaces.cpp:287-383` (`logged++ < 8` em `:380-381`) |

## 1. Caminho por draw, passo a passo, com custos

Cerca de 520 draws/frame; ms/frame vêm do relatório (1 draw em 64 amostrado, `render_prof.cpp:67`, janelas "frios"); µs/draw = ms ÷ 520.

| Fase (kPhase) | ms/frame | µs/draw | O que o código faz (cite) |
|---|---|---|---|
| shader | 1,0 | 1,9 | `get_fetch_shader` + 2x `vk::translate` com memo por `g_shader_state_gen` (`draw.cpp:2069-2075`). 64 % dos draws mudam registradores "outros" (SQ_PGM etc., 150-278 escritas/frame cada), logo o memo falha com frequência. |
| indices | 0,27 | 0,5 | Conversão/cópia de índices; buffer cache + `cached_native_indices` (`draw.cpp:2088-2247`). Já barato. |
| targets | 0,26 | 0,5 | `color_target`/`depth_target` → `find_or_create_surface(forRendering)` (`:2250-2259`, `surfaces.cpp:1061`), mais um contador estático por tamanho (`:2262-2278`). |
| pipeline | 0,26 | 0,5 | `pipeline()` com lookaside (`:2311`, `:731-737`). |
| uniforms | 1,1 | 2,1 | Por estágio: `pack_uniforms_into` (zera o bloco inteiro e copia as ALU constants, `shaders.cpp:1218-1285`), depois `uniform()` com cache de reuso por slot (memcmp contra cópia CPU, `uniform_snapshot.h`) ou `snapshot()` (arena + memcpy, `draw.cpp:129`). Blocos guest vão pelo buffer cache (`cached_guest_range`, `:1764`). |
| **textures** | **2,2-5,2** | **4-10** | Ver 1.1. |
| descriptors | 1,0-1,6 | 1,9-3 | `std::sort` dos writes (ranks ligados evitam isso), `descriptor_matches` contra o último set, senão hash + busca, senão `vkAllocateDescriptorSets` + `vkUpdateDescriptorSets` (`draw.cpp:1927-1980`). Dois sets por draw (VS e PS). UBOs são dinâmicos (`:749-753`): trocar uniform não cria set novo. |
| pass | 1,7-2,7 | 3,3-5,2 | Só aparece como custo grande nos draws que abrem passe: `end_encoder`, `upload_surface`+`transition_image` de cada attachment, `layer_view`, `vkCmdBeginRendering` com `LOAD/STORE` (`:2370-2421`). ~24 scopes de GPU por frame (soma de `scopes/frame` em `gpu_pass.txt`: 6+4,74+4+1,01+1+3+2+1+1,53). |
| record | 0,4 | 0,8 | bind pipeline/descriptors dinâmicos, viewport, scissor, blend, stencil, com memo de estado (`:2433-2531`). |
| vertex | 0,5 | 1,0 | Varredura de índices (`indexed_vertex_extent`) e snapshot/bind de vertex buffers (`:2538-2660`). |
| submit (= vkCmdDraw) | 0,9 | 1,7 | ver 0.6. |
| ops fora do draw | regs 2,1 · swap 3,4 · invalidate 0,66 · flush 0,37 | | `regs`: 16,5 mil escritas/frame (33/draw), 128 ns cada (`gx2_core.cpp:94-133`, `shader_irrelevant` em `:58-83` roda por registrador alterado). `swap`: overlay ImGui + plano de apresentação + present (`backend.cpp:2605-2760`). `invalidate`: 444 chamadas/frame, 1,5 µs cada (`surfaces.cpp:1401-1449`, varre `R.surfaces` só quando `flags&2`). |

### 1.1 Texturas: como estado, cache e descritor são tratados

Por textura e por draw, em `bind_stage` (`draw.cpp:1859-1920`):

1. `sampled_texture` (`surfaces.cpp:385-445`): decodifica 6 palavras do descritor, `LatteTexture_ReconstructGX2Format`, e para sombra faz varredura de aliases; depois `find_or_create_surface` = `equal_range` num `unordered_multimap<addr, unique_ptr<Surface>>` (`backend.h:198`) com pontuação por candidato (`surfaces.cpp:1061-1110`). Sem cache por palavras do descritor: é refeito em todo draw e em todo estágio.
2. `upload_surface` (`surfaces.cpp:1123-1190`): sai cedo se `gpuWritten` ou se `lastCheckedFrame == R.frame` (1x por frame por superfície). Primeira vez no frame: calcula os ranges de todos os mips (`mip_base`, `guest_info` em cache) e consulta `wwatch::written_since` (varredura linear de carimbos de página, `write_watch.cpp:183-189`). Só re-hash/upload se houve escrita. Em regime estável uploads são ~0 (`uploads MiB/frame` de textura = 0,01).
3. `with_mip_chain` (`surfaces.cpp:287-383`): só para alvos GPU com `mips>1`; a chave inclui `writeSeq` do alvo e dos níveis do jogo, então **qualquer render no alvo invalida a cadeia** e ela é refeita: `transition_image` TRANSFER_SRC/DST (termina o passe), `vkCmdCopyImage`, `mips-1` blits e uma barreira por nível (`:333-378`).
4. `sampled_texture_view` (`:700-740`): chave de 32 bits (tipo + swizzle) num mapa por superfície; barato após o primeiro uso.
5. `aliases` (`draw.cpp:1880-1886`): se o alvo amostrado é um attachment do próprio draw, `feedback_view` copia a imagem (`vkCmdCopyImage` para scratch, termina o passe). O scope de GPU nº 8 (`feedback 1559x720`, 1,53 scopes/frame, 0,43 ms) mostra que isso ocorre ~1,5x/frame.
6. `transition_image` para SHADER_READ: retorna cedo se o layout já é o certo (`backend.cpp:911-912`), caso contrário `end_encoder()` + barreira `ALL_COMMANDS` com `MEMORY_READ|WRITE` (`:913-926`).
7. `sampler()` (`draw.cpp:1186-1289`): memo de 1 entrada (ligado) e, em falha, chave `std::string` de 15 bytes (cabe em SSO) + `unordered_map`. Criação só na primeira vez.
8. Descritor: `LastDescriptorSet` por estágio (comparação de identidades), depois `SubmissionDescriptorCache` (hash 64 bits + comparação), válido até o pool da submissão ser resetado (`:1946-1980`).

Texturas ≈ 156 trocas/frame (`SQ_TEX_RESOURCE E002/E003`, 156 escritas cada), ~1300 resoluções textura-unidade por frame × ~1,5 µs ≈ 2 ms: é o piso de 2,2 ms. O resto (até 5,2) não é o fluxo normal; as hipóteses, em ordem de plausibilidade:

| Causa da variação 2,2 → 5,2 | Evidência | Como confirmar |
|---|---|---|
| Relógio/thermal: CPU total sobe 14 → 18-21 ms nas janelas finais de `res_1` (frames 2760-3480) e `textures` sobe 2,3 → 5,1-6,5 (2,2-2,8x contra 1,4x do total). Como as fases são medidas por parede, preempção também entra. | `res_1.txt`, `res_0.75.txt` (tabela 2.3) | Comparar `tex/CPU` com `[perf] thermal headroom` e rodar frio vs quente; `simpleperf` |
| Poucos draws amostrados com evento raro caro (upload, regeneração de mip, feedback, `end_encoder`): 1/64 dá ~8 amostras por frame; um único evento de 0,5 ms vira +4 ms/frame na extrapolação. É ruído de estimador. | `render_prof.cpp:79-96`, fator de escala `:288` | Contadores exatos: `WWHD_VK_STATS=1` (`[vulkan textures] N full checks, N uploads, faults`, `backend.cpp:2870-2877`), `WWHD_VK_FEEDBACK_STATS=1` (`draw.cpp:1541`) |
| Regeneração de cadeia de mips do alvo 640x360 até 4x/frame | `build/run_prof.txt` (linhas `[gfx] mip chain`) | `WWHD_NO_RT_MIPS=1` (`surfaces.cpp:288`) e comparar |
| Cenas de carga: decode CPU por texel e staging por nível (`create_buffer` = `vkCreateBuffer`+`vkAllocateMemory`+`vkMapMemory` por nível, `surfaces.cpp:1181`, `backend.cpp:436-473`). A janela do frame 480 tem `textures 7,4`, 89,6 ms/frame e `DrawDone` 73 ms. Não afeta o regime estável. | `run_prof.txt` | contagem de uploads da linha `[vulkan textures]` |
| Falha de `mprotect` em `wwatch::arm` (limite de mapeamentos): páginas viram "escritas a cada checagem" e a textura é re-hasheada inteira todo frame (`write_watch.cpp:160-170`) | contador `protect_failures()` (`write_watch.h:80`) não é impresso em lugar nenhum | **[não verificado]**; expor no `[vulkan textures]` |

Risco de memória do upload: cada nível de textura aloca um `VkDeviceMemory` próprio (Adreno limita `maxMemoryAllocationCount` a 4096 **[não verificado]**), e cada imagem também (`surfaces.cpp:656`). A arena de upload (`allocate_upload`, blocos de 32 MiB com `TRANSFER_SRC`, `backend.cpp:521-546`) já serve para staging e não é usada aqui.

### 1.2 Estrutura de passes

- Passe é reaberto sempre que o conjunto exato (cores, slices, depth, tamanho) muda ou que algo chama `end_encoder` (22 sítios; os de regime estável são clear `surfaces.cpp:1201/1224`, copy `:984`, upload `:1163`, mip chain `:333`, feedback `draw.cpp:107/1626`, barreira de layout `backend.cpp:913`, scissor vazio `draw.cpp:2503`).
- Dados de GPU (`gpu_pass.txt`): o mesmo depth `126` abre 4 conjuntos de attachments diferentes: `[0:37,1:64,2:83,3:122]` 4,74 scopes/frame (171 draws), `[0:122]` 4,0/frame (71 draws), `[0:37,1:64]` 1/frame (32 draws), `[0:37,2:83,3:122]` 1/frame. O buffer `122` aparece no índice 3 e no índice 0 conforme o passe: o próprio jogo alterna configurações de MRT. Cada alternância custa `end/begin` com `LOAD/STORE` de 4 attachments 1559x720 (CPU: ~60-80 µs, `pass` 1,7-2,7 ms; GPU: parte dos 7,6 ms do scope 1). **[inferido]**: a causa é a configuração de MRT do jogo e não a máscara de cores; falta histograma de motivo de quebra de passe.
- Todo `loadOp=LOAD`/`storeOp=STORE` (`draw.cpp:2377-2378`, `:2399-2400`), como já apontado no estudo anterior.

## 2. Por que o jogo espera em `DrawDone`, e se dá para relaxar

### 2.1 O que acontece

- `GX2DrawDone` (HLE, `gx2_core.cpp:898-903`): `emit_host(OP_DRAW_DONE)` e depois `render_sync(kSyncDrawDone)`. `render_sync` enfileira `OP_FENCE` e dorme em `g_q_done_cv` até o render thread executar **tudo** que veio antes (`:264-288`).
- O lado do render thread (`OP_DRAW_DONE`, `:480-488` → `draw_done()` em `gx2/backend_hooks.h:34-40`) com `lazy_draw_done()` (padrão, `:392-398`) só faz `guest_flush()` (= `flush_async`, no-op se não há gravação pendente) e `write_back()` (0 superfícies no SM3DW). O custo medido da op é 0,01 ms. **O `lazy` não removeu a espera da thread do jogo; removeu a espera da GPU.** A espera restante é "o render thread terminou de *gravar* tudo".
- Por que ela existe: o render thread lê memória guest *na hora de executar*, não na hora de enfileirar (vertex/uniform/índice em `draw()`, texturas em `upload_surface`, shaders em `translate`, display lists em `OP_CALL` `:474`). O comentário em `gx2_core.cpp:480-486` diz isso ("guest data is copied into fenced upload slices when work is recorded, so DrawDone needs this op executed"). O jogo usa `DrawDone` como o console: "a GPU terminou, posso reutilizar/liberar". Sem a espera, o jogo sobrescreveria buffers que o render thread ainda não leu (dados de ~4,7 MiB/frame únicos de guest, `unique guest MiB/frame: all 4.67`).
- No jogo: `GX2DrawDone` é chamado em 6 pontos do código recompilado (`build/gen3dw/code_029.c:4954, 5573, 14179`; `code_042.c:3598, 10228/10240, 10844`). O wrapper `f_023594E0` faz `DrawDone` e **em seguida** chama `f_023594D4` em dois objetos (`+0xB4`, `+0x308`) e `OSMemoryBarrier` (`code_029.c:4946-4970`). **[inferido]**: parece "espera a GPU e libera o que estava em uso", o que indica que o jogo *realmente* reutiliza/libera memória após o retorno. Não verifiquei quais dos 6 sítios são os 2/frame.
- Write-back: `0 surfaces` no SM3DW; só alvos lineares são lidos de volta (Picto Box do WWHD, `surfaces.cpp:1228-1305`). Portanto o write-back **não** é o motivo da espera no SM3DW.

### 2.2 O que a espera custa

`DrawDone` = 5,4-10 ms/frame (cena pesada fria) e 13-14,5 ms (janelas quentes), acompanhando a CPU do render thread (tabela 2.3): é a *cauda* do trabalho do render thread no momento da chamada. A thread do jogo gasta o resto do frame (~10-11 ms) em lógica e geração de comandos.

### 2.3 Dados por janela (`res_1.txt`, `run_prof.txt`)

| Janela (frame) | ms/frame | CPU render | idle | DrawDone | textures | leitura |
|---|---|---|---|---|---|---|
| 1080 (`run_prof`) | 16,63 | 13,87 | 2,58 | 5,45 | 2,73 | vsync-bound, thread não saturada |
| 2280 (`run_prof`) | 20,25 | 13,99 | 2,48 | 10,10 | 3,01 | 3,5 ms off-CPU dentro de ops |
| 1560 (`res_1`) | 19,6 | 15,41 | 0,26 | 8,65 | 2,62 | render-bound |
| 3240 (`res_1`) | 26,37 | 21,04 | 0,32 | 14,53 | 6,51 | CPU +50 %, ~5 ms off-CPU |

### 2.4 Opções para relaxar (da mais segura à mais ousada)

| Opção | Ideia | Efeito esperado | Risco |
|---|---|---|---|
| A. Mais cedo, não mais assíncrono | Reduzir a cauda: submeter no meio do frame e diminuir o trabalho do render thread (seções 3 e 5). | A espera cai junto com a CPU do render | nenhum |
| B. `DrawDone` com atraso de 1 (lag-1) | `DrawDone` k espera a fence do `DrawDone` k-1 em vez da própria. Experimento de ~10 linhas em `render_sync`/`GX2DrawDone`. | Tira ~1 `DrawDone` do caminho crítico da thread do jogo: o jogo gera a fase seguinte enquanto o render thread termina. Só ajuda quando a thread do jogo é o limite (janelas frias); em janela saturada (CPU 21 ms) o render thread continua sendo o gargalo. | **Alto**: o wrapper libera/reutiliza logo após o retorno (2.1). Pode sobrescrever vertex/uniform/display list ainda não lidos: flicker, geometria errada. Só como teste com captura visual |
| C. Snapshot na thread do jogo | Copiar para a fila, no enqueue, os blocos guest que o draw vai ler (vertex/uniform/índice) e remover a espera. | Remove a espera por completo para dados de draw | Alto/esforço grande: 4,7 MiB/frame de memcpy na thread do jogo e as leituras de textura/display list/shader continuam tardias |
| D. Fence por geração + proteção de página | Usar `wwatch` (já protege páginas) para detectar escrita do jogo em página ainda não lida e só então bloquear. | Espera só quando há conflito real | Muito alto: fault no caminho do jogo, lógica de lock por intervalo; não recomendo antes de A/B |

Medição da cauda no próprio código atual (sem alterar comportamento): `WWHD_SYNC_STATS=1` imprime a cada 5 s quantas vezes/quanto o jogo esperou por sítio (`gx2_core.cpp:240-263`); acrescentar o tamanho de `g_q_pending` no `OP_DRAW_DONE` diz quanto trabalho estava na fila (**falta**: contador novo, 3 linhas).

## 3. Agrupamento, multi-draw, descritores, extensões

Classes de draw (`[prof] draw classes`, `render_prof.cpp:349-352`): 2,2 % registradores idênticos, 33,6 % só ponteiros de buffer/ALU constants, 64,2 % outros (493 draws/frame; principais: `SQ_PGM A225` 278, `A216` 193, `A217` 175, `SQ_TEX_RESOURCE E002/E003` 156). Isto limita agrupamento:

- **Multi-draw/instancing**: só vale para sequências com o mesmo pipeline e as mesmas texturas. Só ~36 % dos draws (≈180/frame) não trocam programa; entre eles os objetos diferem em ALU constants (uniforms), não em instance id. Juntá-los exigiria um UBO por draw indexado por `gl_DrawID`/instância e `VK_EXT_multi_draw` (**[não verificado]** se o 512.676 expõe; o código não consulta essa extensão: nenhuma ocorrência de `multi_draw`, `push_descriptor`, `descriptor_buffer`, `extended_dynamic_state` em `gfx/vulkan/*.cpp`). Ganho teórico pequeno: o custo de `vkCmdDraw` é ~1,7 µs/draw (0,9 ms/frame), o resto é preparação CPU.
- **Caminho de "draw continuado" (o que o profiler foi feito para medir, `render_prof.h:8-9`)**: para o 33,6 % de draws que só mudam ponteiros/constantes, pular `translate`, `pipeline`, resolução de textura, comparação de descritores e viewport/scissor. Estimativa: 8-10 µs de ~21 µs por draw em ~180 draws = **1,5-1,8 ms/frame (11-13 %)**. Precisa de uma época de recursos (bump em criação/destruição de superfície, `writeSeq` de alvo amostrado, `dirty`, invalidate, troca de submissão) e ficar restrito a texturas não escritas pela GPU. Risco médio (staleness), esforço médio.
- **Descritores**: já há `LastDescriptorSet` + hash por submissão + UBO dinâmico; a pergunta decisiva é a taxa de alocação por frame (linha `[vulkan descriptors] ... allocations/frame ... % reused`, `backend.cpp:~2930`; **não tenho esse número**). Se as alocações forem < 100/frame, push descriptors/descriptor buffer não ganham nada (o caminho quente já evita `vkUpdateDescriptorSets`). Juntar o set de VS e o de PS em um só (dois sets hoje, `:2437-2463`) reduziria hash/compare/bind pela metade (0,3-0,5 ms estimado, **[não medido]**), mas exige renumerar bindings do SPIR-V.
- **Push descriptors / descriptor buffer / EDS / multi-draw no Adreno 740 driver 512.676.1 (Vulkan 1.3.128)**: **não verifiquei** quais extensões ele expõe: o runtime não loga a lista. Passo 0: logar `vkEnumerateDeviceExtensionProperties` (ou `vulkaninfo` no aparelho). Não assumir `VK_EXT_descriptor_buffer` nem `VK_EXT_multi_draw` em driver Qualcomm proprietário.
- **Submissão no meio do frame (`WWHD_VK_DRAW_BATCH`)**: com 2048 nunca ocorre (ver 0.5). `DRAW_BATCH=N` com `DRAW_BATCH_CAP` 1-3 (`draw.cpp:53-70`; cap padrão 3) faz até 3 `flush_async` por frame a cada N draws (cap inválido vira 2). Isso põe a GPU para trabalhar durante a gravação (a GPU hoje só começa depois do flush; ~30 % ociosa), ao custo de terminar o passe (próximo draw reabre com `LOAD`, comentário `:2704-2705`). Anel de 4 submissões (`backend.h:195`). É o único dos "agrupamentos" que muda a latência; **toggle por env.txt**.

## 4. Dividir o trabalho do render thread em duas threads

O que o código já tem: `DeferredCommands` (`deferred_commands.h`) captura chamadas `vkCmd*` como `std::function` e as emite depois, em ordem (usado só para esperar a compilação de pipeline: `draw.cpp:2314-2315`, `:2424-2432`, `begin_exact_preparation` `backend.cpp:934`). Todas as gravações passam por `record::vkCmd*`. É a costura certa para uma thread de gravação, mas o formato (`std::function` por comando, alocação de heap) custaria ~12 comandos/draw × ~50 ns = ~0,3 ms só para empacotar; precisaria virar ring de bytes SPSC.

| Parte | Fica na thread A (estado) | Pode ir para a thread B (gravação) |
|---|---|---|
| Registradores, `translate`, `pipeline()`, `find_or_create_surface`, `upload_surface`, layouts (`s->layout`, `writeSeq`), arena de upload, snapshots de guest | sim (mutam estado serial; precisam ler guest antes do jogo escrever) | |
| `vkAllocate/UpdateDescriptorSets` (descriptors 1,0-1,6 ms) | decisão de hash/cache | alocação/atualização do pool da submissão |
| `vkCmdBind*`, viewport/scissor, `vkCmdDraw*`, barreiras, `Begin/EndRendering` (record 0,4 + submit 0,9 + parte de vertex/pass/textures) | | sim |
| `vkEndCommandBuffer`, `vkQueueSubmit`, fences, retirada de slot, present | | sim (já existe worker de present em fila dedicada, `present_worker.h`, só para display secundário) |

Ganho bruto máximo: ~2,5-3,5 ms de 14; líquido ~1,5-2,5 ms (≈ 10-18 %) depois de pagar o empacotamento. Acoplamentos que tornam isso grande: `R.cmd`, `R.rendering`, `R.passTracked` e `command_buffer()` (início preguiçoso) são lidos pela thread A (`draw.cpp:2328-2337`); `R.descriptorPool`/`uploadBlocks`/lixo são trocados em `activate_submission` (`backend.cpp:1021-1036`) e `retire_submission`. Esforço: semanas, risco alto (device-lost é o modo de falha já documentado em `backend.cpp:870-875`).

**Alternativas mais baratas que dividem a mesma CPU:**
- **Thread de swap/overlay**: `swap` custa 3,4 ms/frame de parede (`ops swap`), com `overlay::frame` (ImGui, `backend.cpp:2706-2712`) e `display_plan`. Se o overlay de performance estiver ligado (padrão no SM3DW, `CMakeLists.txt:77` do estudo anterior), vale medir `[vulkan swap phases]` (`backend.cpp:~2990`) com ele oculto; mover composição/present para o worker (já existe para o secundário) é de esforço médio.
- **Decode de textura em worker** (só cenas de carga): `decode_level` faz uma chamada de endereçamento por texel (`surfaces.cpp:474-489`); é função pura e paralelizável por nível/textura, com o `vkCmdCopyBufferToImage` ainda na thread A.
- **Passes independentes em command buffers secundários** (ex.: sombra, 1024x1024 depth-only, 203 draws, 1,2 ms de GPU): ganho só na gravação do passe de sombra, ~0,6 ms, e a GX2 é uma máquina de estado serial: o front-end A não paraleliza.

## 5. Experimentos ranqueados

Medir sempre: janela fria idêntica da cena de attract (frames 600-1080 de `run_prof.txt`: CPU 13,5-13,9, ≈ 520 draws, DrawDone 5,5-12), três repetições, comparando `render thread CPU`, `ops`, `draw phases`, `DrawDone`, `[fps]`. Misturar `WWHD_VK_STATS=1` adiciona contadores por draw; use-o em rodada separada (ou `WWHD_VK_CPU_ONLY_STATS=1`, `backend.cpp:106`).

| # | Experimento | Ganho esperado | Risco | Esforço | Como medir | env.txt? |
|---|---|---|---|---|---|---|
| 1 | **Medição de base sem código**: `WWHD_VK_STATS=1` (uploads/full checks/faults por frame, alocações de descritor, `queue submit`, `swap phases`, `waits`, passes/frame), `WWHD_VK_FEEDBACK_STATS=1`, `WWHD_VK_GPU_PASS_TIMESTAMPS=1` (+ `WWHD_VK_GPU_TIMESTAMPS=1`), e `simpleperf record -t <tid render>` (APK debuggable). | Decide 4, 6, 8, 10 | Baixo | Horas | Linhas `[vulkan *]` e `[vulkan GPU scope N]` (`backend.cpp:665-775`, `2832+`) | Sim |
| 2 | **Onde o render thread roda**: A/B `WWHD_FAST_CORES=0/1` (`perf_hint.cpp:171-185`) e depois fixar render thread no núcleo prime (X3, cpuinfo_max_freq maior) e a thread do jogo nos 4 médios; hoje as duas e o driver dividem o mesmo conjunto de 5 núcleos e o nice -5 é no-op sem `CAP_SYS_NICE` (`platform/host.h:84-101`). | **Potencial maior**: CPU do render thread cai se hoje ele migra para núcleos de 2,8 GHz (X3 3,36 GHz e IPC maior) [não verificado onde ele roda: ver `adb shell ps -T -o tid,psr,name,%cpu`] | Baixo | A/B: horas; pin por papel: ~20 linhas | `render thread CPU ms/frame`, `DrawDone` | A/B sim; pin por papel não |
| 3 | `WWHD_VK_PRESENT_MODE=mailbox` (log: `fifo, mailbox` disponíveis) e `WWHD_VK_ASYNC_PRESENT=1/0` (`backend.cpp:1453-1459`) | Pode eliminar parte dos 3,5-5 ms de parede de `swap` sem CPU (seção 0.1) e a diferença parede×CPU | Baixo (tearing não ocorre em mailbox; pacing é virtual, `GX2WaitForVsync`) | Minutos | `ms/frame` vs `CPU`, `[vulkan swap phases]`, `[vulkan pacing]` p95 | Sim |
| 4 | **Submissão no meio do frame**: `WWHD_VK_DRAW_BATCH=128/192/256` com `WWHD_VK_DRAW_BATCH_CAP=3` (e `=1`); comparar `[vulkan draw batches] extra submissions/frame` e scopes de GPU. | A GPU começa antes (30 % ociosa); possível -1 a -3 ms de latência de frame. Custo: +passes (LOAD/STORE) | Médio | Minutos | `[fps]`, `GPU busy`/`GPU interval` do overlay, scopes/frame | Sim |
| 5 | `WWHD_NO_RT_MIPS=1` | Elimina cópia+blits+`end_encoder` da cadeia do alvo 640x360 (até 4x/frame); -0,3 a -1 ms CPU estimado | Visual: bloom/blur sem mips autênticos | Minutos | `textures`, `pass`, scopes/frame, comparação de screenshot | Sim |
| 6 | **Caminho de draw continuado** (33,6 % dos draws) com época de recursos | 1,5-1,8 ms (11-13 % da CPU do render thread) | Médio (texturas stale) | Médio | `draw phases`, `us/draw`, `[prof] draw classes` | Não |
| 7 | **Pequenos ganhos de CPU de registrador**: tabela `bool[kNumRegs]` para `shader_irrelevant` (hoje laços de comparação por registrador alterado, `gx2_core.cpp:58-83`) e para o filtro `fast_class_reg` do profiler | 0,4-0,8 ms de 2,1 ms de `regs` | Baixo | Pequeno | `ops regs` | Não |
| 8 | **Quebras de passe**: contar `end_encoder` por motivo (clear/copy/upload/mip/feedback/layout/scissor) e por conjunto de attachments; só então decidir fusão de conjuntos (ex.: mesmo depth 126, 4 configurações) | `pass` 1,7-2,7 ms + GPU (LOAD/STORE de 4x 1559x720 a cada reabertura) | Baixo (contagem) / médio (fusão) | Pequeno (contagem) | passes/frame, `GPU scope` | Não |
| 9 | `DrawDone` lag-1 (opção 2.4-B), atrás de flag `WWHD_DRAWDONE_LAG=1` | Retira ~5-10 ms/frame de espera da thread do jogo em janelas frias; nenhum ganho de CPU do render thread | **Alto** (corrupção) | Pequeno | `DrawDone ms`, fps, captura visual repetida | Só após ter a flag |
| 10 | Staging de textura pela arena de upload e decode paralelo (cena de carga) | Corta o spike de 89 ms/frame no carregamento; zero em regime | Baixo | Pequeno/médio | janelas de carga em `run_prof.txt` (frame 480) | Não |
| 11 | Mover `swap`/overlay para fora do render thread | até 1-1,5 ms CPU **[não medido]** | Médio | Médio | `ops swap`, `[vulkan swap phases]` | Parcial (overlay oculto) |
| 12 | Thread de gravação SPSC (seção 4) | 1,5-2,5 ms líquido | Alto | Semanas | `render thread CPU`, `draw phases` | Não |
| 13 | Merge de sets VS+PS / push descriptors | 0,3-0,5 ms **[não medido]**; depende das extensões e da taxa de alocação | Médio | Médio | `descriptors` | Não |

Ablação barata de memos já ligados (cada um `NAME=0` no `env.txt`): `WWHD_VK_REUSE_UNIFORM_SNAPSHOTS`, `REUSE_FEEDBACK_IMAGES`, `SKIP_REDUNDANT_BINDS`, `DESCRIPTOR_RANKS`, `PIPELINE_LOOKASIDE`, `SHADER_ADDRESS_MEMO`, `FETCH_MEMO`, `SPECIALIZE_INDICES`, `SHADER_STATE_MEMO`, `SKIP_VERTEX_BINDS`, `SAMPLER_MEMO`, `SPARSE_HASH_MEMO`, `SHADER_KEY_DIRTY`, `REUSE_VERTEX_SNAPSHOTS`, `VERTEX_HISTORY_REUSE` (lista de `report_header.cpp:27-31`); também `WWHD_VK_LAZY_DRAW_DONE=0` (referência do valor do lazy, `gx2_core.cpp:392-398`) e `WWHD_VK_BUFFER_CACHE=0` (`buffer_cache.cpp:135-139`). Útil para saber quanto cada um vale no S23.

Ordem sugerida: 1 → 3 → 2 (A/B) → 4 → 5 → 8; em paralelo, 7 e 10 (baixo risco); 6 como principal trabalho de CPU; 9 só com flag e captura visual; 12 só se 6+7 não bastarem.

## 6. O que não pude verificar

- Em qual núcleo cada thread roda e se o relógio cai (thermal) nas janelas lentas; origem exata dos 3,5-5 ms de parede sem CPU (suspeitos: bloqueio em `vkQueuePresent`/aquisição com FIFO, preempção, driver). Falta rodar 1 e 3.
- Quais dos 6 `GX2DrawDone` do jogo são os 2/frame e o que o jogo faz com a memória logo depois (a pista é `f_023594E0`, `code_029.c:4946`).
- Taxa de alocação de descritores, número de passes/frame e motivos de quebra de passe, contagem exata de uploads/full checks por frame, `protect_failures`.
- Extensões do driver Adreno 740 (push descriptors, descriptor buffer, multi-draw, EDS, dynamic rendering unused attachments): o runtime não loga a lista.
- `maxMemoryAllocationCount` do aparelho e custo real de `vkAllocateMemory` no Adreno.
- Todas as estimativas de ganho em ms são aritmética sobre os números medidos, não resultado de teste.
