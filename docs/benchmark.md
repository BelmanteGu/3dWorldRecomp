# Benchmark do Super Mario 3D World (PC, sem humano)

Mede o quadro a quadro da fase do circo (mundo 1-5, "Labirinto de interruptores", arquivo `FlipCircusStageMap1`) com
entradas roteirizadas e termina sozinho.

## Uso

```sh
tools/bench/sm3dw_bench.sh [-o SAIDA] [-g GPU] [-r ESCALA] [-f QUADROS] [-s FRAGMENTO_DA_FASE] [-- VAR=valor ...]
# padrão: GPU "Intel" (a integrada), escala 1.5 (= 1920x1080 internos), 1800 quadros, fase "Circus"
tools/bench/sm3dw_bench.sh -o bench_out/antes -f 1800
tools/bench/sm3dw_bench.sh -g NVIDIA -r 3 -- WWHD_BENCH_SHOTS=1      # outra GPU, 4K interno, com capturas
```

Pré-requisitos: build Release (`build/vk/wwhd`, `WWHD_BIN` para outro caminho), jogo em `../game3dw_eur` (`GAME=`),
save em `../save-backup-s23/user/GameData.bin` (`SAVE_SRC=`; é **copiado** para `../bench-work/user` a cada execução, o
original nunca é tocado). Configurações/cache de shaders ficam em `../bench-work/config` (a primeira execução compila
shaders e é mais lenta; ela só afeta o tempo de carga, não a medição).

## O que acontece

1. O modo é ligado por `WWHD_BENCH=circus` (o script faz isso). Sem limite de quadros (`WWHD_UNCAPPED=1`), apresentação
   `immediate`, janela de 1920x1080, sem entrada do teclado/controle do host.
2. O runtime pressiona A a cada 100 quadros (título, arquivo, personagem). Quando uma tela ocupada (>=100 draws por 900
   quadros: o mapa) aparece, segura "para frente" 40 quadros e aperta A (o Mario do save está parado no caminho, em frente
   à tenda do circo). O arquivo `StageData/*Circus*Map1.szs` sendo aberto pelo jogo identifica a fase (log `[bench]`).
3. A tela de título da fase espera um botão; A continua sendo apertado. Quando a fase passa a desenhar >=200 draws por
   120 quadros, ela está na tela. Espera-se 600 quadros (introdução) e começa a medição.
4. Rota fixa por contagem de quadros (correr com B, direcional com curvas, pulo com A a cada 70 quadros). Mede
   `WWHD_BENCH_FRAMES` quadros e grava `bench.csv` (por quadro: `dt_ms`, `gpu_ms`, `render_cpu_ms`, `draws`),
   `summary.txt` (fps médio, p50/p95/p99 de quadro, GPU, CPU de render, draws) e `run.log`.

`gpu_ms` é o intervalo de timestamps da submissão do quadro (inclui esperas de fila), serve para comparar antes/depois,
não é "GPU ocupada" estrita. Com GPU e CPU em paralelo, `dt_ms` ~ max(CPU, GPU).

## Varredura de travadas (stutter)

`tools/bench/sm3dw_stutter.sh [-c]` roda este mesmo benchmark gravando **todos** os quadros da sessão (título,
mapa-múndi, carregamento e fase) a 120 fps, com o tempo de cada quadro atribuído a causas (tradução de shader,
pipeline, textura, esperas de GPU/apresentação, lógica do jogo) e um resumo por fase (quadros >10/16/33 ms, p99,
p99.9, máx). `-c` começa com o cache vazio. Detalhes, causas encontradas e correções: [stutter.md](stutter.md).

## Variáveis

`WWHD_BENCH=circus|live`, `WWHD_BENCH_FRAMES`, `WWHD_BENCH_OUT`, `WWHD_BENCH_STAGE` (fragmento do nome da fase),
`WWHD_BENCH_SETTLE`, `WWHD_BENCH_CAPPED=1` (respeita o limite de 60), `WWHD_BENCH_SHOTS=1` (2 capturas PNG na saída),
`WWHD_BENCH_STAGE_DRAWS`, `WWHD_BENCH_LOAD_MIN`, `WWHD_BENCH_GIVE_UP` (quadro de desistência, 60000),
`WWHD_BENCH_LOG_FILES=1` (loga os arquivos de `StageData` abertos), `WWHD_BENCH_ALL=1` (todos os quadros, com
colunas de causa e fase), `WWHD_BENCH_STAGELIST=<arquivo>` (lista de fases substituta: o nó 1-5 abre outra fase;
`tools/bench/sm3dw_stage_tour.sh` percorre todas), `WWHD_BENCH_CAP_STAGE=1` (menus sem limite, fase em tempo real),
`WWHD_BENCH_LOAD_MAX` (quadros até começar uma fase que nunca desenha 200 coisas, 3000). Modo `live`: mede a partir do quadro
`WWHD_BENCH_START` com a entrada que o ambiente definir (`WWHD_PRESS`, `WWHD_STICK`...).

## Resolução automática

`WWHD_AUTO_RES=120` (ou `60`) liga o controlador de resolução dinâmica (`runtime/src/autores.cpp`; também na aba de
gráficos do overlay: "Resolução automática"). Usa o intervalo de GPU medido por quadro (p75 das últimas 24 amostras) e
move o fator de resolução em passos de 0.05 (custo de GPU ~ a + b*escala^2) entre o piso (`WWHD_AUTO_RES_MIN`, 0.4) e a
resolução escolhida, que passa a ser o teto. `WWHD_AUTO_RES_LOG=1` registra cada mudança. No benchmark use
`-r 1.5 -- WWHD_AUTO_RES=120`.

## Armadilhas encontradas

* Mudar `WWHD_STRICT_MUL` / `WWHD_NAN_GUARD` e reaproveitar o mesmo diretório de trabalho enganava: o pacote de shaders
  guardava o SPIR-V da primeira escolha. Agora essas opções entram na impressão digital do cache.
* A primeira execução em um diretório de trabalho novo compila shaders (muito mais lenta para carregar).
* O mapa-múndi (CourseSelect) também é pesado (~490 draws) e roda a ~29 fps em 1.5x nesta iGPU.

Capturas de tela do jogo não vão para o git (use `../shots/perf/`).

## Multiplicação: `WWHD_STRICT_MUL` / `WWHD_MUL_MODE=fast`

Medido no circo 1-5, iGPU Intel, 1.5x, 900 quadros (fps médio): padrão 27.5; `WWHD_STRICT_MUL=0` 32.7; `WWHD_MUL_MODE=fast`
33.1-34.4 (+20-25%). O custo do padrão vem de `mul_nonIEEE` em ~87% das multiplicações (impede fusão em mad). Alternativas
descartadas (medidas): `isnan(a*b)` em vez do desvio (+3%), `mul_nonIEEE` só em shaders com RECIP/RSQ/LOG/EXP (+0%: quase
todo shader de luz normaliza vetores).
`fast` = multiplicação simples + `±inf` de RECIP/RECIPSQRT/EXP/LOG vira `±FLT_MAX` por seleção (sem desvio; NaN preservado),
de modo que `0*(1/0)` continua 0. Não cobre NaN de sqrt/rsqrt de
argumento negativo nem estouro finito. Diferença visual: PSNR 50-57 dB no quadro do meio, restrita a um triângulo de brilho num painel do
circo (vetor quase degenerado) e ao brilho dos olhos; por isso continua opt-in. Comparar capturas só entre execuções com
o mesmo binário: há ruído de execução para execução (animações) de ~37 dB no primeiro quadro.
