# Preset "Intel 120" (GPU integrada Intel UHD, alvo 120 fps)

`WWHD_PRESET=intel120` (ou `--intel120`). Cada parte continua sobrescrevível por uma variável já definida.

| Variável | Valor | Efeito |
|---|---|---|
| `WWHD_FPS` | 120 | quadros intermediários (60 -> 120 apresentados) |
| `WWHD_AUTO_RES` | 120 | resolução interna dinâmica, teto 1.5 (1080p interno) |
| `WWHD_AUTO_RES_HEADROOM` | 0.95 | usa 95% do período de 8.33 ms (antes 88%) |
| `WWHD_MUL_MODE` | fast | multiplicação simples (-20% de GPU) |
| `WWHD_DRC_MODE` | off | a imagem do GamePad não é desenhada (-0.3 ms) |

## Medidas (circo 1-5, Intel UHD ADL-S, tools/bench/sm3dw_bench.sh, sem limite de quadros, mediana)

| Configuração | escala | fps médio | quadro p50 / p99 |
|---|---|---|---|
| padrão 1.5x | 1.50 | 27.9 | 35.6 / 38.3 ms |
| Auto 120 (88%) | 0.55 | 136 | 7.3 / 7.75 ms |
| 0.75x padrão | 0.75 | 93 | 10.7 / 11.3 ms |
| 0.75x + `MUL_MODE=fast` | 0.75 | 117 | 8.55 / 9.4 ms |
| 0.75x + fast + DRC off | 0.75 | 120.4 | 8.3 / 8.6 ms |
| **preset (Auto 120, 95%)** | **0.70** | **131** | **7.6 / 8.0 ms** |

Fast mul e DRC off: +25% de fps em escala igual; com o preset a escala estável sobe de ~0.55 para 0.70.
Capturas do mapa-múndi com e sem `fast` (lado a lado) sem diferença visível.

## Perfil por passe (0.75x; `WWHD_VK_GPU_TIMESTAMPS=1 WWHD_VK_GPU_PASS_TIMESTAMPS=1`, agora 16 grupos)

G-buffer principal 750x540 (4 RT RGBA8, RGB10A2, RG16F, R11G11B10F + D32F, 109 draws): 3.4 ms (a metade do tempo
somado de ~8 ms); passe de cor único com depth (31 draws): 1.5 ms; G-buffer 480x240 (95 draws): 0.9 ms; pós 1026x540: 0.6 ms;
cubemap 188x135x6: 0.35 ms. O custo escala com os pixels e cai com `fast`, ou seja, é ALU (shaders), não banda de RT:
estreitar formatos não foi tentado por isso.

## Upscale de qualidade (`WWHD_SCALE_FILTER=quality`, aba "Picture scaling" do overlay)

Catmull-Rom com 5 taps bilineares + nitidez (máscara de nitidez limitada ao mín/máx dos taps, ideia do CAS), em
`present.cpp`. Custo medido a 1080p de saída: +1.7 ms de GPU (6.3 -> 8.0 ms a 0.6x; uma versão com 9+4 taps custou +2.1 ms).
Isso equivale a subir a escala interna em ~0.1 com a mesma GPU. Visualmente fica um pouco mais nítido que o bilinear a
0.6x, com mais serrilhado, e ainda muito atrás de 1.5x; por isso **não** é padrão no preset (ficou opt-in).

## O que não funcionou / pendente

* **120 fps reais (com limite de 60 lógico, `WWHD_BENCH_CAPPED=1`) não fecham**: o quadro intermediário gasta ~10 ms na
  thread do jogo (replay da lista de desenho) além dos ~13 ms do passo lógico; `[fps120] in-between pictures paused ...
  137 of 240 frames late`, saída acaba em ~60 fps e o controle de resolução oscila (0.4-1.5) porque as amostras de GPU
  com quadros atrasados não são estáveis. Os números acima são de GPU/render sem limite (como o benchmark do
  `docs/benchmark.md`). O gargalo para 120 reais é a CPU da thread do jogo, não a GPU.
* A interpolação re-renderiza a cena inteira em cada quadro intermediário: não poupa GPU.
* Iluminação (RT 0x1B, depth bias, sombras, AO, bloom, gama): não investigado.
* MUL_MODE=fast: continua opt-in globalmente; o preset o liga. Validado só no circo e mapa-múndi.
