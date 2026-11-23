# Preset "Intel 60" (GPU integrada, 60 fps travados) — issue #17

`WWHD_PRESET=intel60` (ou `--intel60`). **Padrão automático**: no desktop (Linux/Windows), quando a GPU Vulkan
escolhida é integrada (`VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU`), os mesmos padrões entram sozinhos, cada um só
onde o jogador não escolheu nada (variável `WWHD_*` ou valor salvo pelo overlay em `settings.ini` vencem). O log
diz `[auto-intel60] integrated GPU (...)`. `WWHD_PRESET=none` desliga até o automático. Não vale no Android (lá
toda GPU é integrada e o app tem seus padrões: S23 120/auto, genérico 60) nem no benchmark, a menos que o preset
seja pedido.

| Variável | Valor | Efeito |
|---|---|---|
| `WWHD_FPS` | 60 | sem quadros intermediários (o jogo roda nativo a 60) |
| `WWHD_AUTO_RES` | 60 | resolução interna dinâmica, alvo 60 fps; teto = resolução escolhida; 1.25 (900p interno) se nada foi escolhido |
| `WWHD_AUTO_RES_HEADROOM` | 0.85 | a GPU pode usar 85% de 16,7 ms (14,2 ms): a GPU integrada divide energia/calor com a CPU e o clock cai quando o notebook esquenta |
| `WWHD_MUL_MODE` | fast | multiplicação simples nos shaders (+20–25% de GPU; ver intel120.md) |
| `WWHD_VK_NARROW_BARRIERS` | 1 | barreiras com estágios/acessos exatos em vez de esperar a GPU inteira (−3,5% de GPU). Só desktop (Adreno perde o device, #104) |
| `WWHD_DRC_MODE` | off | a imagem do GamePad não é desenhada |

Base de shaders correspondente (multiplicação rápida): `tools/sm3dw_shader_pack.sh baseline fast build/vk/wwhd`
(gera `game3dw/shadercache/shaderpack-<impressão>.bin`; o log mostra `[shaders] translator options: multiplication fast`
e o pacote carregado).

## Medidas

Máquina: i5-12450HX + Intel UHD (ADL-S GT0.5, 16 EUs), Mesa 26.2.5 (ANV), Wayland, tela 144 Hz. Varredura completa
`tools/bench/sm3dw_stutter.sh -g Intel` (título, mapa-múndi, carregamento, circo 1-5 por 1800 quadros; ~5000 quadros,
120 primeiros descartados), cache quente, 60 fps (`WWHD_FPS=60`, `fifo`). Colunas de atraso contra o período de 16,7 ms
(`frame_causes_summary.py --fps=60`): >17,5 ms = depois do seu vsync; >25 ms = um quadro mostrado duas vezes.

Sessão inteira (todas as fases):

| Configuração | escala | fps médio | p50 | p99 | p99.9 | máx | >17,5 ms | >25 ms | >33,4 ms | draws pulados |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| antes: padrão (1.0x fixo, mul padrão) | 1.0 | 56,4 | 17,70 | 24,19 | 42,4 | 73,8 | 3066 | 49 | 7 | 284 |
| preset, 1ª versão (teto 1.5, sem as correções 4–5 nem o gatilho de 3 quadros) | auto | 59,1 | 16,69 | 26,25 | 58,5 | 169,8 | 470 | 57 | 20 | 358 |
| preset, teto 1.5 | auto | 59,3 | 16,69 | 23,01 | 51,1 | 119,5 | 395 | 39 | 18 | 0 |
| **preset, teto 1.25 (padrão)** | **auto** | **59,4** | **16,69** | **20,97** | **50,9** | **127,7** | **401** | **26** | **14** | **0** |
| preset, teto 1.0 | auto | 59,5 | 16,69 | 19,32 | 49,0 | 105,7 | 332 | 28 | 17 | 0 |
| preset padrão, binário final (outra execução) | auto | 59,4 | 16,70 | 21,64 | 49,3 | 111,2 | 440 | 34 | 17 | 0 |

Só a fase (circo 1-5 jogando, 1800 quadros; 440–490 draws):

| Configuração | escala na fase | fps médio | p50 | p99 | p99.9 | máx | >17,5 ms | >25 ms |
|---|---|---:|---:|---:|---:|---:|---:|---:|
| antes: padrão 1.0x | 1.0 | 55,1 | 17,93 | 26,29 | 28,4 | 28,6 | 1521 | 26 |
| **preset (teto 1.25)** | 1.0–1.05 | **59,9** | **16,69** | **18,59** | **21,3** | **21,5** | 119 | **0** |
| preset (teto 1.5) | 1.0–1.05 | 59,9 | 16,71 | 18,43 | 20,3 | 20,5 | 114 | 0 |
| preset padrão, binário final | 1.0–1.05 | 59,9 | 16,69 | 18,46 | 21,1 | 22,2 | 119 | 0 |

GPU sem limite de quadros no circo a 1.0x (`sm3dw_bench.sh`, mediana do intervalo de submissões): padrão 16,5 ms →
mul rápida 13,3 ms → + barreiras precisas 12,9 ms. A escala estável na fase e no mapa fica em ~1.0 (o 720p do Wii U),
1.25 nas telas leves.

Entre execuções iguais a contagem de quadros >25 ms da sessão varia ±8 (26 e 34 com o mesmo preset). Os quadros >25 ms que restam estão todos na abertura do mapa e na carga da fase (texturas novas, trabalho do jogo no
mesmo quadro e a troca de resolução entre tela leve e cena pesada); na fase jogando não há nenhum. A coluna >33,4 ms
ficou **pior** que a do 1.0x fixo (14 contra 7): cada troca de resolução realoca os alvos de tela e a primeira cena
pesada depois de uma tela leve ainda roda 3–5 quadros acima do orçamento até a escala descer. Quem prefere zero
trocas usa `WWHD_RES_SCALE=1.0` (teto 1.0: 17 quadros >33 ms, p99 19,3 ms) ou desliga a automática no overlay.

Notas:
* Os quadros entre 17,5 e 21 ms na fase são jitter do ritmo do jogo (o seguinte vem com ~15,5 ms; média 59,9 fps), não
  quedas: nenhum quadro da fase passa de 21 ms com o preset.
* O notebook entra em limitação de energia em toda execução (contador `package_throttle_count` sobe 60–200 por
  execução mesmo começando a 58 °C); as execuções comparadas tiveram contagens parecidas.

## O que mudou (#17)

1. **Preset e padrão automático** (`runtime/src/gpu_preset.cpp`): detecção do tipo da GPU logo após a escolha do
   device, antes de carregar as opções salvas.
2. **Resolução automática reage na hora a cena pesada**: 3 quadros acima de 140% do orçamento (ou 6 acima de 125%)
   descem a escala num passo só, em vez de esperar 30+ quadros (cada um uma queda) ao abrir o mapa ou começar a fase.
3. **Barreiras precisas** na Intel: GPU 13,3 → 12,9 ms no circo a 1.0x (duas execuções de cada).
4. **Pré-aquecimento de pipelines**: o limite de 1024 pipelines prontos descartava ao acaso um terço das receitas
   quando o banco tinha duas configurações (mul padrão e rápida, 1546 receitas): `served 443, not warmed 323` → agora
   `served 766, not warmed 0` e **0 draws pulados** na sessão inteira (antes 220–358).
5. **Destile de texturas mais rápido** (pendência do #16): caminho rápido por micro-tile 8x8 para texturas 2D tiled
   (como o `AddrLibFastDecode.h` do Cemu) e linhas divididas entre até 3 threads auxiliares para níveis grandes.
   No quadro de abertura da fase: destile 49 → 19–25 ms, upload total 67 → 33–48 ms. Verificado byte a byte contra o
   caminho geral em 800+ níveis (`WWHD_VK_DETILE_VERIFY=1`: 0 diferenças).

## Perfil de GPU (1.0x, mul rápida, barreiras precisas, `WWHD_VK_GPU_PASS_TIMESTAMPS=1`)

Soma das submissões 12,8 ms/quadro. G-buffer principal 1000x720 (4 RT + D32F, 108 draws, partido em 6,3 passes por
quadro): 3,7 ms; passe de cor com depth (30 draws): 2,3 ms; G-buffer 640x320: 0,9 ms; pós 1368x720 (18 draws): 0,8 ms;
cópia de depth para amostragem (feedback): 0,4 ms; cubemap 250x180x6: 0,4+0,25 ms; sombra 1024²: 0,12 ms.

### Testes de qualidade × custo (circo 1.0x, sem limite, mul rápida + barreiras precisas)

| Mudança | GPU p50 | fps | Decisão |
|---|---:|---:|---|
| referência | 12,83 ms | 70,7 | — |
| `WWHD_AO_HIRES=0` (AO com depth em meia resolução) | 12,80 ms | 70,9 | sem ganho: fica ligado |
| `WWHD_NO_RT_MIPS=1` (não regenera mips de RT) | 12,14 ms | 76,6 | **rejeitado**: muda a imagem (PSNR 28,5 dB contra a referência; duas execuções iguais dão 49,7 dB): o brilho/desfoque das luzes e o fundo ficam serrilhados |
| `WWHD_VK_NARROW_BARRIERS=1` | 13,34 → 12,90 ms | 68,6 → 70,6 | **no preset** |

Capturas em `../shots/intel60/` (fora do git): `shot_detile1` (referência), `shot_detile0` (destile antigo),
`shot_normips` (sem mips de RT).

## O que não deu / pendente

* **RelaxedPrecision/mediump**: não tentado nesta rodada. Exige marcar o GLSL gerado pelo decompilador Latte e
  validar a iluminação shader a shader; o ganho no ANV (Mesa Intel) é incerto, porque o driver precisa rebaixar as
  operações `RelaxedPrecision` para fp16 por conta própria.
* **Formatos de RT menores** (R11G11B10 etc.): o G-buffer já usa RGBA8/RGB10A2/RG16F/R11G11B10F; o custo escala com a
  ALU (cai 20–25% com mul rápida), não com a banda, e os formatos são os do jogo (mudar exige reinterpretar o que os
  shaders do jogo leem). Não tentado.
* **Os 6 cortes do passe G-buffer** por quadro vêm de texturas amostradas pela primeira vez no quadro (transição de
  layout) e de cadeias de mip de RT regeneradas no meio do passe; reordenar exige transições antecipadas por draw.
* **FSR1/EASU+RCAS**: não implementado. O upscale `quality` existente (Catmull-Rom + nitidez, 5 amostras por pixel de
  saída) custa, medido agora a 1.0x interno → 1080p de saída: GPU 12,94 → 14,11 ms (+1,2 ms, 70,2 → 64,9 fps sem
  limite). Com o orçamento de 14,2 ms isso baixa a escala da fase de ~1.0 para ~0,95; fica opt-in (overlay "Picture
  scaling" ou `WWHD_SCALE_FILTER=quality`), o preset continua com bilinear. Um RCAS na resolução interna antes do
  bilinear (metade dos pixels) seria o próximo passo barato.
* **Decodificação de texturas numa thread separada (assíncrona)**: não feita; foi acelerada e paralelizada dentro do
  quadro. Os quadros de 90–120 ms restantes são a abertura do mapa e a tela de carregamento da fase (upload de
  ~20–28 MB de texturas + trabalho do jogo no mesmo quadro).
* **Tela de 144 Hz**: 60 fps numa tela de 144 Hz sem VRR alternam 2 e 3 vblanks (13,9/20,8 ms na tela) — judder que
  nenhum modo de apresentação resolve. Para 60 perfeitos use a tela a 60 ou 120 Hz, ou VRR. O preset mantém `fifo`.
* **Espera no vkAcquireNextImage em host híbrido** (NVIDIA renderiza, Intel apresenta): não investigado nesta rodada;
  com a própria Intel renderizando, `acquire` fica em 0,06 ms.
