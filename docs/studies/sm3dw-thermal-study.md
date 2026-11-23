# SM3DW no S23: desempenho sustentado, energia e térmica

Estudo somente leitura, branch `sm3dw`. Nada foi compilado nem executado, sem adb. Caminhos relativos a `runtime/src/` salvo indicação.
Tudo que é hipótese ou depende do aparelho está marcado **[não verificado]**. Base: `docs/sm3dw-perf-study.md`, `docs/sm3dw-study.md` (tabela Android S23) e `build/run_prof.txt`.

## 0. Resumo

| # | Achado | Evidência |
|---|---|---|
| 1 | O gargalo é a render thread: 13,7 a 14,0 ms de CPU por frame, para 16,7 ms de orçamento (margem de ~18 %). Com o SoC estrangulado (clock cai ~40 a 55 %, ver nota do 4,47 -> 1,96 GHz) o mesmo trabalho passa de 16,7 ms e o fps cai. A thread do jogo fica bloqueada 6,4 a 10,1 ms/frame no `DrawDone`. | `build/run_prof.txt` (frames 2160 e 2280), `gx2/gx2_core.cpp:896-901` |
| 2 | Só 2 threads são fixadas (jogo e render), nos "5 cores mais rápidos" (cpu3 a cpu7 no S23 = 4 médios + X3). Como afinidade é herdada no `pthread_create`/`std::thread`, **todas as threads criadas depois por elas herdam essa máscara** (guest, alarm, sched tick, compiladores Vulkan). Elas disputam os mesmos 5 cores com a render thread. | `platform/perf_hint.cpp:185-195`, `threads.cpp:609`, `gx2/gx2_core.cpp:206-209` |
| 3 | A premissa "4,47 -> 1,96 GHz" é do Snapdragon 8 Elite (comentário em `perf_hint.cpp:90-93,145-147`), não do 8 Gen 2 do S23 (X3 até ~3,36 GHz no Galaxy). A razão de queda medida no S23 não está nos logs. **[não verificado]** | `perf_hint.cpp:90-93` |
| 4 | `reportActualWorkDuration` recebe o **intervalo entre frames** (inclui o sono até o vsync), não o tempo de trabalho. Com o jogo limitado por vsync o intervalo é sempre ~16,7 ms: o ADPF só "vê" atraso depois que o frame já estourou. | `perf_hint.cpp:203-206,233-234` |
| 5 | Os limiares 0,72/0,60 e o 33 ms estão **mortos** no build SM3DW (`WWHD_NATIVE_60` retorna antes, `perf_hint.cpp:77-85`; alvo fixo 16,67 ms em `:213-214`). Mas a decisão "o que fazer quando esquentar" não existe: só se loga o headroom. | `perf_hint.cpp:77-85` |
| 6 | O app nunca pede taxa de tela de 60 Hz. `display_rate::poll` só pede modo quando interpolação > 60 fps (`want = 0` = escolha do sistema). Num S23 em modo adaptativo 48-120 Hz o SurfaceFlinger pode manter 120 Hz para o jogo. **[não verificado]** | `platform/display_rate.cpp:63-69` |
| 7 | Não há espera ocupada no caminho normal do Android (vsync dorme; `DrawDone` é `condition_variable`). Há três fontes de acordar frequente: AX a cada 3 ms, tick do scheduler a cada 500 us quando há contenção, e passo de 250 us enquanto um flip está atrasado (justo quando está lento). | seção 2 |
| 8 | Overlay de FPS é ligado por padrão no SM3DW (ImGui + queries de timestamp por frame) e `FLAG_KEEP_SCREEN_ON` mantém a tela acesa: gasto que não ajuda o jogo. | `CMakeLists.txt:77`, `WwhdActivity.java:115` |

## 1. Threads, núcleos e prioridades

### 1.1 Inventário (código)

| Thread (nome) | Criada em | Quantas | O que faz | Placement/prioridade hoje |
|---|---|---|---|---|
| jogo principal (primeira thread guest sem nome) | `threads.cpp:609`, `thread_main` | 1 | roda o código recompilado; monta os comandos GX2 do frame | `sched_setaffinity` nos N cores de maior `cpuinfo_max_freq` (>= 2 cores, `perf_hint.cpp:152-183`); entra na sessão ADPF; `setpriority(-5)` (`platform/host.h:84-103`) |
| guest nomeadas / outras | `threads.cpp:651` (`pthread_create`, pilha 64 MiB) | N do jogo (**contagem do SM3DW não verificada**: não há log `[thread] start` em `build/`) | threads do jogo (IO, jobs, áudio do jogo) | só `setpriority(-5)` (`threads.cpp:617`). Afinidade herdada do criador |
| "GX2 render" | `gx2/gx2_core.cpp:206-209` | 1 | executa a fila GX2, grava Vulkan (13,7 ms/frame) | pino nos cores rápidos, ADPF, `setpriority(-5)` |
| "AX frame" | `hle/ax.cpp:540-570` | 1 | mixa áudio a cada 3 ms e executa callbacks guest | `setpriority(-5)` (via `make_service_cpu`, `threads.cpp:742`); herda máscara |
| "alarm" | `threads.cpp:1160,1210,1682` | 1 | callbacks de alarmes OS | idem |
| "sched tick" | `threads.cpp:303-337` | 1 | pede preempção por fatia de 500 us | herda, sem prioridade explícita |
| "Vulkan demand", "Vulkan translate", "Vulkan SPIR-V", "Vulkan warmup", "Vulkan state warmup", "Vulkan recipe save" | `gfx/vulkan/pipeline_demand.cpp:71`, `shader_translation.cpp:38`, `shader_compiler.cpp:102`, `pipeline_worker.cpp:242,415,128` | 1 cada (warmup usa até `hardware_concurrency`, `pipeline_worker.cpp:449-455`) | compilação de shader/pipeline | **sem prioridade nem afinidade própria**; herdam {cpu3-7} |
| present worker | `gfx/vulkan/present_worker.h` | até 1 por janela secundária | apresentação do GamePad | herdada |
| threads do driver Vulkan/Qualcomm, SDL, UI Java, SurfaceFlinger | fora do app | ? | submissão de fila, composição | fora do controle do app |

Mapeamento "core guest 0/1/2": **não é afinidade de CPU host**. `g_sched[3]` (`threads.cpp:154-170`) é só exclusão mútua por core emulado: no máximo 1 thread guest roda por core guest (3 ao todo), com prioridade estrita e fatia de 500 us entre iguais (`threads.cpp:230-238`). O core guest vem da máscara do jogo (`core_from_affinity`, `threads.cpp:129-134`; padrão core 1). Portanto ao host chegam no máximo 3 threads guest ativas mais render, AX, alarm, etc.

### 1.2 A thread do jogo cai num core pequeno?

- Com o pino ativo, não: o log `[perf] thread N on the 5 fastest cores: yes` (`perf_hint.cpp:193`) confirma que o jogo e a render ficam em {cpu3..cpu7}. No S23, `fastest_cores` começa com o piso = máximo (1 core, o X3), vê `n < 2` e desce ao próximo degrau, resultando em 5 cores (`:169-179`). Os 3 A510 (cpu0-2) ficam fora. **[não verificado: máscara real do aparelho, ver comandos 1.4]**
- Dentro dos 5 cores o escalonador (EAS) decide: a render thread, que usa 13,7 ms de 16,7 ms (utilização ~82 %), pode ser colocada num A710/A715 de 2,8 GHz em vez do X3, e dividir o core com outra thread herdada.
- O pino é só para **duas** threads; qualquer thread guest extra pesada pode cair em core pequeno? Não, herdam {3-7}. O oposto é o problema: threads de fundo (compilação, AX, alarm, tick) podem disputar o X3/mid com a render.
- `setpriority(PRIO_PROCESS, tid, -5)` (`host.h:101`) exige RLIMIT_NICE; em apps Android costuma funcionar, mas **[não verificado]** (ver coluna NI nos comandos).

### 1.3 Placement proposto (Snapdragon 8 Gen 2: cpu0-2 A510, cpu3-4 A715, cpu5-6 A710, cpu7 X3)

| Thread | Máscara | Prioridade | Motivo |
|---|---|---|---|
| GX2 render | cpu7 (X3) fixo; variante B: cpu5-7 | nice -5 (manter) | é o caminho crítico (13,7 ms), mais 20 % de clock do X3 vs mids vira ~2 a 3 ms |
| jogo principal | cpu3-6 (mids) | nice -5 | bloqueia 6 a 10 ms/frame no DrawDone; não precisa do X3 e não disputa com a render |
| outras threads guest (jobs/IO) | cpu3-6 | padrão | trabalho de jogo em rajadas |
| AX frame, alarm, sched tick | cpu0-2 (A510) | nice 0 | acordam 333/s, 1 a 2000/s: trabalho leve, pior para energia se acordar um core grande |
| Vulkan demand | cpu3-4 | nice 0 | a render **espera** por ele (`pipeline_demand.cpp`), então não deve ir ao A510 |
| Vulkan translate/SPIR-V/warmup/recipe save | cpu0-2 | nice +10 | trabalho de fundo, sem prazo de frame |
| ADPF | sessão com game + render (como hoje); testar adicionar a "Vulkan demand" | alvo 16,67 ms | |

Como implementar (sem editar neste estudo): uma função `perf_hint::place(role)` chamada em `host::set_thread_name` ou nos sites já listados; **tem de zerar a herança**, chamando `sched_setaffinity` logo no início de cada thread nova (o `fastest_cores` atual só conta 5 cores genéricos; precisa de máscaras por cluster lendo `cpuinfo_max_freq`: maior valor = prime; segundo degrau = mids; resto = littles). Testar A/B: (A) atual, (B) render cpu7 + jogo cpu3-6 + fundo cpu0-2, (C) B com render cpu5-7. Risco: pino rígido no X3 compete com a UI do sistema e térmica concentrada; o X3 sozinho esquenta mais, mas executa o mesmo trabalho em menos tempo; só o teste de 10+ min mostra o que ganha. `WWHD_FAST_CORES=0` já permite o controle "sem pino" (`perf_hint.cpp:186`).

### 1.4 Verificação (comandos exatos)

Pacote: `org.sm3dwrecomp.sm3dw` (`docs/sm3dw-study.md`, tabela Android). APK é debuggable.

```sh
PKG=org.sm3dwrecomp.sm3dw
PID=$(adb shell pidof $PKG)

# 0. topologia: frequência máxima por cpu (identifica littles/mids/prime)
adb shell 'for c in /sys/devices/system/cpu/cpu[0-9]*; do echo "$(basename $c) $(cat $c/cpufreq/cpuinfo_max_freq) $(cat $c/cpufreq/scaling_cur_freq)"; done'

# 1. por thread: nome, máscara de afinidade, nice, cpu atual, utime+stime (em ticks)
adb shell "for t in /proc/$PID/task/*; do tid=\${t##*/}; \
  printf '%s %-16s mask=%s ' \$tid \"\$(cat \$t/comm)\" \"\$(taskset -p \$tid 2>&1 | sed 's/.*: //')\"; \
  sed 's/.*) //' \$t/stat | awk '{print \"cpu=\"\$37\" nice=\"\$17\" ticks=\"\$12+\$13}'; done"
#  (stat: depois do ')' o campo 3 vira \$1, então processor(39)=\$37, nice(19)=\$17, utime(14)=\$12, stime(15)=\$13)

# 2. amostra 1 Hz por 60 s: em que cpu cada thread de interesse roda
adb shell "for i in \$(seq 60); do for t in /proc/$PID/task/*; do \
  n=\$(cat \$t/comm); case \"\$n\" in 'GX2 render'|guest|main|'AX frame'|'Vulkan demand') \
  echo \"\$i \$n \$(sed 's/.*) //' \$t/stat | awk '{print \$37}')\";; esac; done; sleep 1; done" > cpu_trace.txt

# 3. contadores por thread (app debuggable: simpleperf do app via run-as)
adb shell run-as $PKG simpleperf stat --app $PKG --per-thread --duration 20 \
  -e cpu-cycles,instructions,context-switches,cpu-migrations
#    cpu-migrations altos na render = EAS mudando de cluster; ciclos/seg dividido pelo clock = ocupação

# 4. quem ocupa o X3 e quanto tempo (perfetto, 15 s): sched + frequência + térmica
adb shell perfetto -o /data/misc/perfetto-traces/t.pftrace -t 15s sched freq idle thermal
adb pull /data/misc/perfetto-traces/t.pftrace   # abrir em ui.perfetto.dev: aba CPU slices por thread

# 5. runtime: logs já existentes
#   env.txt do app:  WWHD_PROFILE=1   WWHD_SCHED_STATS=2   WWHD_TICK_STATS=1   WWHD_SYNC_STATS=1
#   (SCHED_STATS=2 imprime "[sched] [nome cN pP run% cpu% wait%]" por thread guest a cada 5 s: threads.cpp:305,370-374)
adb logcat -s wwhd | grep -E "\[perf\] thread|\[sched\]|\[tick\]|render_sync"
```

Observação: `thread_sampler` (`WWHD_SAMPLE_THREADS`, indicado em `docs/sm3dw-perf-study.md` §2.1) só existe em **Windows ARM64** (`platform/thread_sampler.cpp:3-7`); no Android é inerte. Use simpleperf/perfetto. Para um log permanente, a mudança mínima é, em `thread_main` e a cada 5 s, imprimir `sched_getaffinity` + `sched_getcpu()` + `getpriority` por thread (**não implementado**).

## 2. Espera ocupada, acordar e limite de frame

| Local | Comportamento | Custo/energia | Ação |
|---|---|---|---|
| Espera de vsync do jogo: `GX2WaitForVsync` -> `threads::park_sleep_until(deadline, preciseSleep)` (`gx2/gx2_core.cpp:1024-1050`) | No Android `preciseSleep` é falso salvo `WWHD_VSYNC_PRECISE=1` (`:1024-1035`): cai em `host::sleep_until` -> `std::this_thread::sleep_until` (`platform/sleep.h:17`). **A thread do jogo dorme até o vsync virtual, não gira.** | bom | conferir que `env.txt` do aparelho não tem `WWHD_VSYNC_PRECISE` |
| Variante precisa: `while (now() < tp) {}` (`threads.cpp:578`) | gira 0,5 a 2 ms por vsync, só com `WWHD_VSYNC_PRECISE=1` (padrão em macOS) | 3 a 12 % de um core grande | manter desligado no Android |
| Flip atrasado: `wait_for_late_flip` (`gx2_core.cpp:752-767`) | laço de `park_sleep_until` em passos de **250 us** (até 4000 acordadas/s) enquanto o frame não ficou pronto | acontece justamente quando está lento (render atrasada): cada acordar tira o core de C-state | passar para espera por `condition_variable` do `frames_completed` ou passo de 1 ms |
| `GX2DrawDone` (`gx2_core.cpp:896-901`) | `render_sync` -> `g_q_done_cv.wait` (`:240-253`): **bloqueia sem girar**; 2 por frame, 6,4 a 10,1 ms | nulo de CPU, mas a thread dormente vê o governor de frequência decair | ADPF deve incluir esse tempo no "trabalho" do jogo (ver 3.2) |
| Fila da render | `g_q_cv.wait` (`gx2_core.cpp:214-220`) | bloqueia | ok |
| "sched tick" (`threads.cpp:303-337`) | `wait_idle` dorme 100 ms se não há contenção; com algum guest pronto dorme `kSlice` = **500 us** em laço (até 2000 acordadas/s) | pequeno, mas constante em cargas com 3 threads em 3 cores guest | `WWHD_TICK_STATS=1` mede; fatia de 1 ms reduz pela metade |
| "AX frame" (`hle/ax.cpp:~540-575`) | acorda a cada **3 ms** (333/s) sempre, executa callbacks guest, `sleep_until(next)` | 1 a 3 % de um core, mas desperta contínuo | colocar em cpu0-2; considerar 6 ms com 2 quadros por acordada **[não verificado: áudio]** |
| `OSYieldThread` (`threads.cpp:795-799`) | `std::this_thread::yield()` = `sched_yield`: para o jogo que gira em `while(flag) OSYieldThread()` é uma espera ocupada cara (o CFS devolve na hora) | 100 % de um core se o jogo girar | **medir**; se aparecer, trocar por `sleep_for(50us)` após N yields |
| Spin guest `while(*lock)` | `PPC_LOOP()` é só barreira de compilador (`include/ppc.h:90`); só vira preempção com contendor pronto no mesmo core guest (fatia 500 us) | sem contendor, gira para sempre dentro do core | **[não medido no SM3DW]**: `WWHD_SCHED_STATS=2` (cpu% alto + wait baixo em thread sem trabalho útil) ou simpleperf por thread |
| Teste | `test_frame_load` (`gx2_core.cpp:433`) | só com `WWHD_TEST_*_LOAD_MS` | n/a |

**Limitador de frame**: tempo do jogo = relógio de vsync **virtual** de 59,94 Hz (`kVsyncPeriod`, `gx2_core.cpp:583`), com swap interval do jogo (`:579,986`). O flip só executa no vsync virtual seguinte; se o frame chega tarde, a grade **desloca** (`vsync_pacing::flip_executed`, `gx2_core.cpp:717-722`, `g_vsync_shift`) em vez de manter a cadência. Resultado observado: o fps oscila contínuo (`[fps] 59,8 -> 51,5 -> 42,9 -> 51,5`, `build/run_prof.txt`), sem travar em 30. Além disso o presente real é FIFO na tela (60 ou 120 Hz) com relógio virtual de 59,94: há um quadro duplicado ou perdido a cada ~17 s mesmo sem carga. **[não verificado: duplicação]**

## 3. Estratégia térmica para 60 fps

### 3.1 Estado atual

| Item | Hoje | Referência |
|---|---|---|
| Sessão ADPF | jogo + render, alvo 16,67 ms fixo no SM3DW; recriada se muda o conjunto de threads | `perf_hint.cpp:202-231` |
| Duração reportada | intervalo entre `frame_done` (inclui o sono do vsync) | `:233-234` |
| Headroom | só log a cada 15 s (`getThermalHeadroom(…, 10)`), sem ação | `:80-84` |
| 0,72/0,60 (pausar interpolação) | código morto no SM3DW (antes do `return`) | `:86-109` |
| Resolução | `WWHD_RES_SCALE` aceita 0,5-4 (Gráficos 0,75); não ajudou o fps (CPU-bound) | `docs/sm3dw-study.md` |
| Modo de jogo | manifesto tem `isGame` e `appCategory=game`; **sem** `game_mode_config`, **sem** `GameManager`, **sem** `setSustainedPerformanceMode`, **sem** `setFrameRate` | `AndroidManifest.xml:26-27`, busca em `android/app/src/main` e `runtime/src` |
| Térmica Java | `PowerManager.getCurrentThermalStatus` e temperatura da bateria só para exibição | `PerformanceThermals.java:15-37` |

### 3.2 ADPF

- **Alvo**: 16,67 ms está certo. Mas o dado reportado deve ser o **tempo de trabalho** do frame, não o intervalo. Proposta: por frame, `work = (fim do swap executado na render) - (início do frame na thread do jogo)`, sem contar o sono até o vsync, e reportar isso com alvo 16,67 ms (ou 0,9x para forçar folga). Assim o governor vê "ocupação 85 %" e mantém o clock; hoje vê "100 % no alvo" mesmo com folga de 10 % e só reage depois de estourar.
- Usar o 2º reporte com CPU e GPU separados (`APerformanceHint_reportActualWorkDuration2` + `AWorkDuration`, API 35) e `APerformanceHint_setPreferPowerEfficiency` (API 35, núcleo "eficiente" quando há folga) e, onde existir, `notifyWorkloadIncrease` (API 36) em cargas de cena. **[não verificado: nomes/níveis de API; conferir `performance_hint.h` do NDK usado e o Android do S23]**
- Incluir na sessão a thread "Vulkan demand" durante carga de cena (a render fica esperando por ela).
- `APerformanceHint_getPreferredUpdateRateNanos` e `APerformanceHint_createSession` já usados; faltam `APerformanceHint_setThreads` (API 34) para atualizar a lista sem recriar a sessão.

### 3.3 O que substitui 0,72/0,60 (meta térmica de 60 fps)

Headroom no S23 já vale 0,77 com o aparelho frio e 0,94 quente (`docs/sm3dw-study.md`): limiar fixo em temperatura **dispararia sempre** (como o `60 fps paused` de 0,79 a 0,89 no log antigo). A decisão deve ser pelo **efeito medido** (orçamento do frame), com a temperatura só como tendência:

| Sinal | Fonte | Uso |
|---|---|---|
| Folga do frame | `render_thread_cpu_ms/frame` (já medido em `rprof`, 13,7 ms) + espera do jogo | descer um nível quando `> 15,5 ms` por 5 s; subir quando `< 11 ms` por 90 s |
| fps p10 de janelas de 1 s | contador `[fps]` já existente | descer quando p10 < 50 por 10 s |
| Inclinação do headroom | `AThermal_getThermalHeadroom(mgr, 30)` - valor atual, em 30 s | decidir descer **antes** de estrangular quando sobe > 0,05/min |
| Status do sistema | `AThermal_registerThermalStatusListener` ou `PowerManager.addThermalStatusListener` (já usado em `SetupService.java:67`) | `MODERATE` e acima: nível 2 imediato; `SEVERE`: nível 3 |
| Limiares do aparelho | `AThermal_getThermalHeadroomThresholds` (API 35) | mapear headroom <-> status em vez de constantes. **[não verificado]** |

Histerese: descer rápido (5 s), subir devagar (90 s, dobrando se voltar a descer em < 5 min, como `cooldown` em `perf_hint.cpp:65,118`).

### 3.4 Níveis de qualidade (o que reduz CPU, não GPU)

Como o limite é CPU da render, resolução não ajuda (medido). Cortes que reduzem **trabalho da render thread** (13,7 ms; 500 a 585 draws/frame, 17 mil escritas de registrador, 440 invalidates, `docs/sm3dw-perf-study.md` §1.1):

| Nível | Ação | Ganho esperado | Risco | Estado |
|---|---|---|---|---|
| T0 | cheio | n/a | n/a | atual |
| T1 | atualizar o mapa de sombras a cada 2 frames: descartar draws em alvo só-depth (≈ 220 de ~500 draws, perf-study §1.3) nos frames ímpares e manter o conteúdo anterior (também ignorar o clear) | até ~4 ms CPU (≈ 40 % dos draws) | sombra com 1 frame de atraso; conflito se o guest reaproveita o mesmo alvo para outra coisa | **[não verificado: ≈ 220 são shadow]**, exige log de alvos depth-only |
| T2 | T1 + `WWHD_RES_SCALE` 0,75 + aniso limitada a 2x | corta GPU e energia (menos calor), CPU ~0 | pouco | existe parcialmente; efeito em temperatura sustentada **[não medido]** |
| T3 | cadência de 30 fps com `swap interval 2` (ver abaixo) | reduz CPU pela metade por segundo, cai a potência | jogo a 30 fps | hook existe (`GX2SetSwapInterval`, `gx2_core.cpp:986`) |
| T3b | lógica 60, **desenhar** 1 de 2 frames (a render ignora os draws do frame par) | metade da CPU de render sem mudar a velocidade do jogo | tearing visual de 30 fps; alvos persistentes, `CopySurface` e feedback do guest quebram facilmente | alto risco **[não verificado]** |
| Outros | desligar overlay (`WWHD_PERF_OVERLAY_DEFAULT=true` no CMake:77), `WWHD_PROFILE`, timestamps de GPU em sessões longas | pequeno, mas grátis | nenhum | ver 3.6 |
| Já medido pela fase | `textures` 2,0 a 3,0 ms, `pass` 1,75 a 2,3, `descriptors` 1,1 a 1,6, `regs` 2,1 a 2,4 | os memos do perf-study §5 itens 9 e 10 cortam CPU sem mudar a imagem | baixo-médio | **melhor relação custo/benefício, prioridade sobre T1** |

**Limite de 45/30 fps com cadência correta**: a tela do S23 é 48-120 Hz adaptativa (**[não verificado]** quais modos fixos existem: `dumpsys display | grep -i mode`). Com 60 Hz, 30 fps = swap interval 2 (quadros de 33,3 ms uniformes). O jogo precisa do relógio de jogo desacelerado ou ele corre na metade da velocidade: já existe `game_clock::rate()` (usado em `gx2_core.cpp` e `threads.cpp`) mas o SM3DW é 60 fps nativo, então é preciso verificar se a lógica é por frame ou por tempo (**[não verificado]**, teste: forçar interval 2 e medir o tempo de um nível). 40 fps (3 vsyncs a 120 Hz) usa o mesmo mecanismo; 45 fps não tem divisor de 60 nem de 120 (só com tela de 90 Hz, que o S23 não oferece **[não verificado]**), então o intermediário "sem drift" é 40 fps a 120 Hz via `requestRefreshRate(120)` e `vsync_rate` 2 do `interp` (infraestrutura pronta, `platform/display_rate.cpp`), não 45.

Substituir o deslocamento contínuo da grade: quando o nível desce (T3) o governor muda o swap interval de forma discreta (60 -> 30) e **não** deixa `g_vsync_shift` derivar; no nível 60 só há deslocamento se um frame atrasa de verdade.

### 3.5 Taxa da tela, modo de jogo e sustentado

| Recurso | Faz | Recomendação | Risco/Nota |
|---|---|---|---|
| Pedir 60 Hz | `Window.preferredDisplayModeId` (já há `requestRefreshRate`, `WwhdActivity.java:229-253`) ou `ANativeWindow_setFrameRate(win, 60, ANATIVEWINDOW_FRAME_RATE_FIXED_SOURCE)` (API 30) ou `Surface.setFrameRate` | **chamar com 60 sempre que interpolação não pede mais** (hoje `want = 0`, `display_rate.cpp:65`). Menos energia de tela e composição, e cadência regular com o relógio virtual de 59,94 | verificar `dumpsys SurfaceFlinger` que o modo ativo era 120 Hz |
| `GameManager` / `game_mode_config` (API 33+) | `<meta-data android:name="android.game_mode_config">` com modos `standard/performance/battery`, `fpsOverride`, `allowGameDownscaling` (Android 13/14) e `GameManager.getGameMode()` para ler o que o usuário/OEM escolheu | declarar os 3 modos; em `BATTERY` começar em T2/T3; em `PERFORMANCE` nenhum corte. Usa o Samsung Game Booster que já reconhece `isGame` | o OEM pode reaplicar clocks próprios; **[não verificado no S23]** |
| `GameManager.setGameState` (API 33) | avisa carga x jogo | marcar `isLoading` nas cargas de cena (as de 4-7 ms/frame de textura e pipeline) | pequeno ganho em stutter |
| `Window.setSustainedPerformanceMode(true)` (API 24) | pede ao sistema um teto sustentável de CPU/GPU, evitando oscilar | **A/B obrigatório**: troca pico por constância; só vale se `PowerManager.isSustainedPerformanceModeSupported()` for verdadeiro (muitos aparelhos devolvem falso) | pode piorar o fps frio (hoje 55-59) |
| Sessão de nível de potência do ADPF | ver 3.2 | | |
| Tela | `FLAG_KEEP_SCREEN_ON` mantém brilho/tela ligados (`WwhdActivity.java:115`) | manter, mas sugerir brilho médio e modo "Padrão" (60 Hz) | a tela é parte relevante do calor |
| GPU clock | sem API pública; só via ADPF/Game Mode. Ler `kgsl` exige root/SELinux | **[não verificado]** acesso a `/sys/class/kgsl/kgsl-3d0/gpuclk` e `devfreq` sem root |

### 3.6 Bateria, USB e resfriamento

- Carregando por USB o aparelho dissipa calor extra (perda de conversão, 5 a 15 W conforme o carregador) e a bateria a 43 C leva o gerenciador térmico a cortar clock (headroom 0,94). É a explicação mais provável para os ~42 fps medidos, e as medições "frias" de 55-59 fps são o limite real do código. Conclusão: **benchmark de desempenho sustentado deve ser feito sem carregar**.
- Recomendação para o usuário: jogar sem cabo (ou com "Proteger bateria" limitando a 80-85 %, que reduz o calor de carga), sem capa, tela com brilho moderado; carga por modo bypass quando existir no aparelho (**[não verificado no S23]**).
- Procedimento de **cool-down** entre execuções: aguardar até `dumpsys battery` `temperature` <= 330 (33 C) e headroom < 0,70 (ou 5 min com a tela apagada e o app fechado); só então iniciar a próxima corrida. Registrar no relatório se a corrida começou com o aparelho morno.
- Para medir sem cabo: `adb tcpip 5555; adb connect <ip>:5555`, tirar o cabo e `adb shell dumpsys battery unplug` apenas se for necessário simular (`dumpsys battery reset` ao final).
- `AThermal`/`PowerManager`: o app **pode** mostrar um aviso quando status >= `MODERATE`: "aparelho quente; considere desconectar o carregador/ativar 30 fps". Nada no código atual age sobre isso.

## 4. Protocolo de benchmark reproduzível

Objetivo: comparar variantes (pino, ADPF, T1-T3) sob condições térmicas controladas, sem overhead.

Protocolo:
1. Build igual, `env.txt` com `WWHD_PROFILE=1` **só** no modo diagnóstico; para a medida de fps usar apenas o log `[fps]` e `WWHD_PERF_OVERLAY_DEFAULT` desligado (overlay oculto). Anotar o `env.txt` usado.
2. Aparelho **sem carregador**, brilho fixo (p.ex. 50 %), modo avião, outros apps fechados, `adb shell settings put system screen_off_timeout 1800000`.
3. Cool-down até bateria <= 33 C e headroom < 0,70 (script abaixo).
4. Iniciar o app, entrar **na mesma cena** (mesma fase/ponto de reaparecer; usar o ponto de 600-1080 frames do `run_prof.txt` como referência ou um save state do jogo), deixar 90 s de aquecimento (descartar).
5. Medir 10 min (variantes) / 20 min (confirmação). Registrar a cada 15 s: headroom (log), temperatura da bateria, `scaling_cur_freq` dos cpu0/3/5/7, status térmico; a cada 1 s: `[fps]`.
6. Relatório: fps médio, p1/p5/p10/p50, mínimo, % de janelas de 1 s abaixo de 55/45/30, e **comparação do primeiro vs último minuto** (queda por estrangulamento); temperatura inicial/final; headroom máximo.
7. 3 repetições por variante, alternando a ordem (ABCABC), cool-down entre elas.

Script (esboço, `tools/bench_thermal.sh` + parser; **não existe no repo**, escrito aqui como roteiro):

```sh
#!/bin/sh
# uso: bench_thermal.sh <rótulo> [minutos=10]
PKG=org.sm3dwrecomp.sm3dw
LABEL=$1; MIN=${2:-10}; OUT=bench/$LABEL-$(date +%m%d-%H%M); mkdir -p $OUT

bat_temp() { adb shell dumpsys battery | awk '/temperature/{print $2}'; }       # décimos de C
usb()      { adb shell dumpsys battery | awk '/USB powered|AC powered/{printf "%s=%s ",$1$2,$NF}'; }

# 1. cool-down: espera bateria <= 330 e headroom(logado) < 0.70 ou 5 min parado
adb shell am force-stop $PKG; sleep 5
until [ "$(bat_temp)" -le 330 ]; do sleep 20; done

# 2. snapshot estático
adb shell 'for c in /sys/devices/system/cpu/cpu[0-9]*; do echo $(basename $c) $(cat $c/cpufreq/cpuinfo_max_freq); done' > $OUT/cpus.txt
adb shell dumpsys display | grep -iE "mode|refresh" > $OUT/display.txt
echo "temp_inicio=$(bat_temp) $(usb)" > $OUT/meta.txt
adb shell cat /sdcard/Android/data/$PKG/files/env.txt >> $OUT/meta.txt 2>/dev/null   # env usado

# 3. início
adb logcat -c
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 90   # aquecimento descartado; entrar na cena de teste (manual ou save state)
adb logcat -v time -s wwhd > $OUT/logcat.txt &  LOGPID=$!
T0=$(date +%s)

# 4. amostragem a cada 15 s
PID=$(adb shell pidof $PKG)
while [ $(( $(date +%s) - T0 )) -lt $((MIN*60)) ]; do
  TS=$(( $(date +%s) - T0 ))
  F=$(adb shell 'for c in 0 3 5 7; do printf "%s " $(cat /sys/devices/system/cpu/cpu$c/cpufreq/scaling_cur_freq); done')
  TH=$(adb shell dumpsys thermalservice | grep -m1 -i "Thermal Status")
  echo "$TS $(bat_temp) $F ${TH:-?}" >> $OUT/samples.txt
  # (GPU) opcional se legível: adb shell cat /sys/class/kgsl/kgsl-3d0/gpuclk
  # (limite de CPU por thread) adb shell top -H -b -n 1 -p $PID | head -20 >> $OUT/top_$TS.txt
  sleep 15
done
kill $LOGPID
echo "temp_fim=$(bat_temp)" >> $OUT/meta.txt
adb shell am force-stop $PKG
python3 tools/bench_parse.py $OUT
```

Parser (`tools/bench_parse.py`, esboço do que lê):
- `[fps] (\d+\.\d) swaps/s` -> série de fps 1 Hz do `logcat.txt`; calcular média, mínimo, p1/p5/p10/p50 (numpy), fração < 55/45/30, e as mesmas por minuto (para ver a queda ao longo do tempo).
- `\[perf\] thermal headroom ([\d.]+)` -> série de headroom a cada 15 s (já impresso por `perf_hint.cpp:82`, a cada 15 janelas de 1 s).
- `samples.txt` -> temperatura da bateria (décimos), `scaling_cur_freq` por cluster, status térmico; correlacionar queda de fps com queda de clock.
- Saída: uma linha CSV por corrida (`label, avg, min, p1, p5, p10, p50, t_inicio, t_fim, headroom_max, fps_min1, fps_ultimo_min`) para comparar variantes.
- Se `WWHD_PROFILE=1` estiver ligado, extrair também `render thread CPU (\d+\.\d+) ms/frame` e `DrawDone (\d+\.\d+) ms` das linhas `[prof] frame` (`build/run_prof.txt`).

Limitações: `dumpsys thermalservice` e `cpufreq` podem exigir shell privilegiado; `kgsl` provavelmente não é legível sem root. `adb shell` com USB gera calor e energia: usar wireless adb para corridas sem carga.

## 5. Próximos passos (ordem recomendada)

| # | Passo | Esforço | Risco | Ganho esperado |
|---|---|---|---|---|
| 1 | **Protocolo de benchmark** (seção 4) com corridas sem carregador, 10 min, ABCABC; confirma o limite real sustentado | baixo (script) | nenhum | decide tudo abaixo |
| 2 | Pedir 60 Hz à tela (`requestRefreshRate(60)` / `setFrameRate(60, FIXED_SOURCE)`) quando não há interpolação > 60 fps | muito baixo | baixo (verificar o modo ativo com `dumpsys display`) | menos energia e calor; cadência regular **[não medido]** |
| 3 | Overlay oculto, `WWHD_PROFILE` desligado e `FLAG_KEEP_SCREEN_ON` mantido, `WWHD_VK_GPU_TIMESTAMPS` fora na medição | muito baixo | nenhum | remove ruído e custo por frame |
| 4 | ADPF: reportar tempo de trabalho (não intervalo), incluir "Vulkan demand" na sessão, adicionar `setPreferPowerEfficiency` e 2º reporte (API 35) se existir no NDK | médio | baixo | clocks corretos sob folga; menos picos |
| 5 | Placement por cluster (render cpu7, jogo cpu3-6, fundo cpu0-2) + zerar herança de afinidade + log de `sched_getaffinity` | médio | médio (o X3 sozinho esquenta; testar A/B) | cabe menos disputa de cores; ganho CPU 5 a 15 % **[não medido]** |
| 6 | Memos de CPU do render (`SAMPLER_MEMO`, `SKIP_REDUNDANT_BINDS`, texturas por descritor, etc., perf-study §5 itens 9 e 10) | baixo-médio | baixo-médio | maior corte direto na render (até ~2 a 3 ms) |
| 7 | Governor de qualidade (3.3 e 3.4): níveis T1 (sombra a cada 2 frames) e T2 (res 0,75), com histerese, ativado por `render ms` + p10 fps + status térmico | médio-alto | médio (artefato visual) | segura ~50+ fps sem derivar, medir sustentado |
| 8 | Cadência discreta 30 fps (ou 40 fps a 120 Hz) em `SEVERE`, sem deslocar a grade de vsync | alto (relógio de jogo) | alto (velocidade do jogo, áudio) | piso estável de energia |
| 9 | `game_mode_config` + `GameManager.getGameMode`/`setGameState`, e A/B de `setSustainedPerformanceMode` | baixo (manifesto) a médio | baixo | integra com Game Booster; A/B decide o sustentado |
| 10 | Reduzir acordadas: tick 1 ms, passo 1 ms em flip atrasado, AX nos cores pequenos | baixo | baixo | energia/calor menores, ganho de fps pequeno |
| 11 | Aviso ao jogador ao entrar em `MODERATE`/USB carregando, e receita de cool-down | baixo | nenhum | evita o cenário de 42 fps |

## 6. O que não pude verificar

- Máscaras de afinidade, cores reais e `nice` das threads em execução; a lista de threads guest do SM3DW (não há log `[thread] start` em `build/`).
- Que o `setpriority(-5)` é aceito no Android 14/15 do S23; que o pino no S23 produz exatamente cpu3-7.
- A queda real de clock (a nota 4,47 -> 1,96 GHz é do 8 Elite), a frequência da tela e o modo ativo (60/120 Hz) durante o jogo.
- Quais APIs de ADPF (API 34-36) o NDK do projeto expõe e o Android do aparelho; suporte do S23 a `setSustainedPerformanceMode`, a modos de jogo e a GPU clock via sysfs.
- Que ~220 draws sejam sombra, e que a lógica do SM3DW aceita `swap interval 2` sem correr em câmera lenta.
- Quantos ciclos o jogo gasta em espera ocupada guest (`while(*flag)`, `OSYieldThread`).
- Nenhum número novo foi medido neste estudo: os de fps e CPU vêm de `docs/sm3dw-study.md` e `build/run_prof.txt` (que é de uma build anterior ao `WWHD_NATIVE_60`, tem a pausa de 60 fps ativa).
