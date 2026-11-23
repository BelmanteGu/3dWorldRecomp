# Quedas de quadro (stutter) no PC — causas, correções e como medir

Issue #16. Sintoma relatado: jogando a 120 fps numa RTX 3050 (1080p), trechos com 31–39 fps entre amostras de 120,
mesmo sobrando GPU. O log mostrava `[pipelines] N draws skipped while their pipelines compile` e, ao sair,
`[shader pack] saved: 0 hits, 1253 misses` e `[vulkan warmup] saved 1157 recipes`.

## O que foi encontrado

| Causa | Efeito | Correção |
| --- | --- | --- |
| As receitas de pipeline (`shader-prebuild/live/vk-*.bin.recipes`) tinham na impressão digital o commit, a versão e `WWHD_PIPELINE_RUNTIME_ID` (hash de **todo** o código do runtime). | Todo build novo ignorava as receitas (`local recipes ignored: incompatible pipeline recipe database`): o pré-aquecimento servia 0 pipelines e a primeira sessão de cada build era fria. | A impressão digital ficou só dispositivo + esquema. Nada da receita é confiado: chave, SPIR-V, layouts e estado fixo são comparados na entrega (`take_warm_pipeline`). Segunda sessão: 793 de 793 pipelines servidos, 0 draws pulados. |
| O pacote de shaders de base (`game3dw/shadercache/shaderpack.bin`) era de um tradutor antigo (`ignored (other translator or struct layout)`), e um único arquivo só serve uma configuração (`WWHD_MUL_MODE`, `WWHD_STRICT_MUL`, `WWHD_NAN_GUARD`, versão do tradutor/glslang entram na impressão digital). | Primeira sessão traduzia ~1000 shaders na thread de render (decompilador Latte + GLSL): quadros de 300–770 ms ao abrir o mapa e as fases. | Base por configuração: `shaderpack-<impressão>.bin` (pasta do jogo e assets do APK), depois o nome antigo. `tools/sm3dw_shader_pack.sh baseline default|fast` gera a partir do head start do Cemu e instala. |
| Sessão do relato rodou com um diretório de usuário novo (`play-work`, criado no minuto da sessão). | 0 hits é o esperado num diretório vazio sem base válida. | Use sempre o mesmo `--user-dir` (ou o padrão `~/.config/sm3dw`); com a base válida, mesmo um diretório novo começa quente nos shaders. |
| Fila de compilação de pipelines por demanda limitada a 16. | O mapa pede ~150 pipelines num quadro; o excedente era compilado **síncrono** na thread de render (137 ms num quadro). | Fila de 1024 (`WWHD_VK_PIPELINE_QUEUE`), 4 threads em CPUs de 12+ núcleos (`WWHD_VK_PIPELINE_THREADS`). |
| Draw com pipeline pendente era pulado na hora. | Objetos apareciam alguns quadros depois (pop-in), mesmo quando a compilação levaria <1 ms (cache do driver quente). | Espera curta e limitada por quadro antes de pular: `WWHD_VK_PENDING_WAIT_MS` (padrão 3 ms; 0 = pula na hora). |
| Upload de textura criava e liberava um buffer de staging próprio (vkAllocateMemory por textura). | Custo extra em cargas de cena (centenas de texturas). | Staging pela arena de upload do quadro (até 8 MiB por textura). |
| CPU do notebook em limitação térmica/energia durante as medições (contador `thermal_throttle` > 50 mil; 800 MHz–1,9 GHz sob carga em vez de 4,4 GHz). | O tempo de CPU da thread de render chegou a 14–20 ms/quadro na mesma cena que normalmente leva 3,8 ms: a 120 fps isso vira quedas para 60 (os quadros intermediários são pausados) sem nenhuma relação com shaders. | Não é software: verificar refrigeração/perfil de energia. Ver "Medindo" para reconhecer. |

O que **não** é causa: E/S de arquivo (as leituras acontecem nas threads de carga do jogo, a coluna `file_io` fica
fora do tempo do quadro), espera de GPU (`gpu_wait` ~0 na RTX), GC de caches.

O que continua: (1) um shader realmente novo (fora da base) ainda é traduzido na thread de render — 2–5 ms cada;
uma cena inteira nova sem base ainda dá um quadro longo; (2) texturas grandes ainda são decodificadas na thread de
render no primeiro uso (dezenas de ms na tela de carregamento da fase); (3) com 120 fps ligados, quadros de
10–12 ms aparecem quando o passo de lógica (4–8 ms de CPU do jogo) e o quadro intermediário caem juntos — é o ritmo
do 120 (lógica a 60), não travada.

## Medindo

```sh
tools/bench/sm3dw_stutter.sh -c -o ../shots/stutter/frio      # cache vazio (primeira execução)
tools/bench/sm3dw_stutter.sh    -o ../shots/stutter/quente    # de novo, mesmo diretório de trabalho
# opções: -g GPU (padrão NVIDIA), -r escala (1.5 = 1080p), -f quadros medidos na fase (3600), -w diretório
# variáveis extras depois de --, ex.: -- WWHD_VK_PENDING_WAIT_MS=0 ; WWHD_VK_PRESENT_MODE=immediate|fifo
```

Roda o benchmark do circo (docs/benchmark.md) com `WWHD_BENCH_ALL=1`: **todos** os quadros da sessão (título,
mapa-múndi, carregamento, fase), a 120 fps (`WWHD_FPS=120`, `WWHD_BENCH_CAPPED=1`). `bench.csv` ganha, por quadro, a
fase e o tempo atribuído a cada causa (`runtime/src/frame_causes.h`):

`shader_translate` (shader fora do pacote), `shader_pack` (shader tirado do pacote), `pipeline_create`,
`pipeline_wait` (draw esperou pipeline), `texture_upload`, `gpu_wait`, `acquire`, `present`, `game_idle` (thread de
render esperando comandos: lógica atrasada), `game_work` (trabalho do passo do jogo, máximo do quadro), `file_io`
(qualquer thread), e contadores `skipped_draws`, `new_shaders`, `new_pipelines`, `warm_hits`.

`tools/bench/frame_causes_summary.py bench.csv` imprime, por fase: p50/p99/p99.9/máx, quadros >10/16/33 ms, a causa
principal de cada quadro >10 ms (a maior coluna, se passar de 30 % do quadro) e os piores quadros. Fora do
benchmark, `WWHD_FRAME_CAUSES=1` liga só os contadores. Ao sair, o log diz quantos pipelines o pré-aquecimento
serviu: `[vulkan warmup] served N pipelines; not warmed A, other programs B, other state C`.

Reconhecendo limitação de CPU: `render_cpu_ms` sobe em todos os quadros por igual (todas as fases do draw ficam
mais lentas), sem `new_shaders`/`new_pipelines`. Confira `cat /sys/devices/system/cpu/cpu0/thermal_throttle/*` e a
frequência sob carga antes de comparar execuções.

## Gerando a base de shaders

```sh
tools/sm3dw_shader_pack.sh cemu <shaders.bin> <vkpipeline.bin>     # uma vez: head start do cache do Cemu
tools/sm3dw_shader_pack.sh baseline default build/vk/wwhd [PACKS...]   # multiplicação padrão
tools/sm3dw_shader_pack.sh baseline fast    build/vk/wwhd [PACKS...]   # WWHD_MUL_MODE=fast (preset intel120)
```

`PACKS` são pacotes vivos da mesma configuração (por ex. `../bench-stutter/config/shader-prebuild/live/vulkan-shaders/shaderpack.bin`
depois de uma varredura) para cobrir o que o cache do Cemu não tem. O resultado vai para
`game3dw/shadercache/shaderpack-<impressão>.bin`. É derivado dos arquivos do jogo: fica fora do git, como o APK.

Também foi corrigido: com 120 fps, **um único** quadro longo (shader novo, carga de cena) fazia a média da janela
de 240 quadros passar do limite e pausava os quadros intermediários por 3 s, depois 6 s, 12 s… até 1 min a 60 fps
(as sequências de `60.0 swaps/s` do log do relato). Agora cada quadro entra na média limitado a dois períodos; o
quadro longo conta como um atraso e lentidão contínua ainda pausa.

## Números (RTX 3050 Laptop, 1080p, 120 fps, varredura do circo, 2026-11-09)

Sessão inteira (~8000 quadros: título, mapa-múndi, carregamento, fase; os 120 primeiros descartados).
"Antes quente" = segunda execução no mesmo diretório com o código antigo (as receitas eram descartadas a cada
build, então na prática só os shaders estavam quentes). "Depois frio" usa a base de shaders nova e o cache vazio.

| execução | quadros >10 ms | >16 ms | >33 ms | p99 | p99.9 | máx | draws pulados | tradução de shader na thread de render |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| antes, frio | 1476 | 933 | 16 | 28,0 | 199 | 770 ms | 2982 | 2154 ms |
| antes, quente | 1108 | 693 | 16 | 28,1 | 71,5 | 430 ms | 1597 | 0 |
| depois, frio (só mapa + carga) | 176 | 15 | 8 | 12,7 | 68,4 | 201 ms | 1596 | 0 |
| depois, quente (immediate) | 394 | 13 | 5 | 11,5 | 23,1 | 103 ms | 0 | 0 |
| depois, quente (fifo, como o jogador) | 546 | 20 | 6 | 12,3 | 23,9 | 107 ms | 0 | 0 |

Na fase (3600 quadros jogando): depois/quente máx 15,4 ms, 0 quadros >16 ms, 119,9 fps médios. Os quadros de
10–12 ms restantes são o ritmo do 120 (passo de lógica + quadro intermediário). Os 5–6 quadros >33 ms que sobram
estão na abertura do mapa e na tela de carregamento da fase: decodificação de texturas na thread de render (55–76 ms).
As execuções feitas com a CPU em limitação térmica (render 3× mais lento em todos os quadros) foram descartadas da
tabela; os CSV de todas estão em `../shots/stutter/` (fora do git), comparação em `comparison.txt`.

## Primeira visita a uma área: cobertura (pacote de receitas), tour de fases e espera (2026-11-09)

Relato seguinte (sessão longa, cache quente): 126 linhas `[pipelines] N draws skipped`, 2645 draws pulados (pico de
121 num segundo), `served 856 pipelines; not warmed 655`, `[shader pack] 1503 hits, 423 misses`. As 862 receitas
salvas foram todas servidas: o problema era **cobertura** — o jogador entrou em fases que nenhuma sessão anterior
tinha visto, e cada pipeline novo era compilado no meio do jogo (draw pulado = objeto some por alguns quadros).

### O que mudou

| Mudança | Detalhe |
| --- | --- |
| Pacote de receitas | `<jogo>/shadercache/pipelines/*.recipes` (`WWHD_VK_RECIPE_PACK`): bancos de receitas lidos depois das receitas do próprio jogador e compilados no aquecimento. Arquivos de outra GPU/driver são ignorados em silêncio; `<impressão do tradutor>-*.recipes` só vale para aquela configuração (multiplicação strict/fast). As receitas do pacote não são regravadas no banco do jogador. |
| Pipelines do head start do Cemu | Cada registro de pipeline do cache do Cemu traz os registradores do draw (formatos dos alvos, blend, profundidade, rasterização, primitiva, strides). `--warm-shaders` agora monta a receita exata desse draw (`speculative_pipeline_recipe`: o mesmo `pipeline()` do jogo, em modo só-receita) e a compila: **3592 pipelines distintos** sem ninguém jogar. |
| Formato 2 do banco de receitas | Cada SPIR-V uma vez numa tabela, comprimido: 1903 receitas eram 112 MB (cada receita repetia os dois programas; ~4300 receitas passariam do limite de 256 MB e seriam recusadas), agora 3592 receitas = 51 MB. Limite de contagem 16384 → 65536; o SPIR-V (compartilhado) não conta mais no orçamento de bytes. Arquivos do formato 1 continuam lidos. |
| Pool de pipelines prontos | Cheio (4096 no desktop, 256 no Android), não descarta mais ao acaso: ficam os primeiros (os do jogador); o resto continua nos caches de pipeline (driver + `vk-*.bin`), e o draw que precisar compila em ~1 ms. |
| Espera por pipeline pendente | `WWHD_VK_PENDING_WAIT_MS` padrão 3 → **50 ms por quadro no desktop** (Android continua 3): um quadro longo na primeira aparição é melhor que vários quadros com geometria faltando. |
| Tour de fases | `tools/bench/sm3dw_stage_tour.sh`: as 128 fases do `StageList` (mundos 1–8, Bowser, castelos, casas do Toad, Captain Toad, bosses, mundos 9–12/coroa), uma execução do benchmark cada, numa pasta de trabalho comum (soma receitas e shaders). |

### Tour de fases

O benchmark entra no nó 1-5 do mapa-múndi. `tools/bench/stage_list_patch.py` faz uma cópia de
`SystemData/StageList.szs` em que o 1-5 aponta para outra fase (troca o índice da string `StageName`; nada se
move), lida pelo jogo via `WWHD_BENCH_STAGELIST`. Redirecionar só os arquivos da fase (`StageData/*Map1.szs`)
derrubava o jogo (o nome da fase decide outras tabelas); trocando o nome na lista, tudo concorda. Menus correm
sem limite (`WWHD_BENCH_CAP_STAGE=1` volta ao tempo real quando a fase abre), a fase roda a 60 fps com o trajeto
do benchmark (correr, virar, pular). Cada fase leva ~30 s.

```sh
tools/bench/sm3dw_stage_tour.sh -c -w ../bench-tour -o ../shots/stutter2/tour   # todas as fases, cache vazio
tools/bench/sm3dw_stage_tour.sh -w ../bench-tour-x -o OUT SnowBallParkStage KoopaLastStage WWHD_VK_RECIPE_PACK=0
```

`tour.txt`: por fase, quadros, pipelines novos, shaders novos, draws pulados, quadros >25 ms e >50 ms (60 fps),
máximo, e se a fase carregou. Limites: só a área inicial de cada fase e o trajeto fixo (sub-áreas por cano/porta,
chefes no fim e as fases que precisam de outra entrada ficam de fora); as `*Zone` que não são fases (salas
de bônus, sub-áreas) só entram quando a fase as carrega no começo.

### Gerando o pacote

```sh
tools/sm3dw_recipe_pack.sh build fast NVIDIA ../bench-tour/config ../play-work/config ../play-work3/config
tools/sm3dw_recipe_pack.sh build default NVIDIA ../bench-stutter2/config
tools/sm3dw_recipe_pack.sh shaders fast ../bench-tour/config/shader-prebuild/live/vulkan-shaders/shaderpack.bin \
    ../play-work/config/shader-prebuild/live/vulkan-shaders/shaderpack.bin    # os shaders traduzidos em jogo -> baseline
```

`build MODE GPU [RECIPES...]`: pipelines dos registros ligados do head start do Cemu (`--warm-shaders`), mais o
pacote já instalado, mais os bancos de receitas dados (pastas de usuário, pastas do tour; de outra GPU são
ignorados), tudo mesclado (`WWHD_VK_RECIPE_MERGE=1`) e instalado como
`game3dw/shadercache/pipelines/<impressão>-<gpu>.recipes`. Fica fora do git (derivado do jogo), como os
pacotes de shaders. Depois de atualizar o driver, gere de novo (a impressão do banco inclui a versão do driver).

### Receitas são portáveis entre dispositivos?

Não. O cabeçalho do banco (`backend.cpp`) tem vendor, device, versão do driver, `pipelineCacheUUID`, as features
ligadas e limites: uma receita só vale na mesma GPU **e** driver (os formatos de alvo, o layout dinâmico de
uniformes e o SPIR-V dependem do que o dispositivo suporta). Por isso o pacote não vai no APK: o celular
precisaria do seu próprio, gerado nele. O que é portável é o head start (registros de shader + registradores do
draw): o caminho para o Android seria rodar o `speculative_pipeline_recipe` no primeiro início do app a partir do
head start embarcado; não foi feito aqui. O Android continua como estava (espera de 3 ms, pool de 256).

### Estado (WIP, 2026-11-09)

Feito e medido até aqui: pacote de receitas (código), 3592 pipelines tirados do head start do Cemu (NVIDIA, modo
fast, 35 s), formato 2 (594 receitas = 7 MB; 3592 = 51 MB), tour funcionando (8 das 128 fases percorridas a frio,
sem pacote, em `../shots/stutter2/tour-cold-nopack/tour.txt`: 54–93 pipelines novos e 1–208 draws pulados por
fase). Circo a frio sem pacote (binário novo, espera ainda 3 ms): 2031 draws pulados, 804 pipelines novos na sessão.
Falta: tour completo, instalar os pacotes (`sm3dw_recipe_pack.sh build`/`shaders`), validar com pasta de usuário
vazia + pacote (meta 0 draws pulados), medir a espera de 50 ms contra 3 ms, e os números finais.
