# 30 fps (velocidade total) — issue #18

Modo para celulares e GPUs fracas: o jogo continua a **60 passos de lógica por segundo** (velocidade normal, áudio e
física iguais) e só **um quadro a cada dois é desenhado e apresentado** (30 apresentações por segundo, ritmo regular).
Sem ele, um aparelho que só alcança 30 quadros deixa o jogo inteiro em câmera lenta, porque a lógica do Super Mario 3D
World anda um passo por quadro.

## Como ligar

| Onde | Como |
|---|---|
| Android | Opções > Gráficos > **Taxa de quadros**: `30 (velocidade total)` / `60` / `120`. Vale na hora e fica salvo. |
| Android | **Cair para 30 quando lento** (Desligado / Automático; padrão Automático): em 60 ou 120, se o jogo não aguentar 60 quadros por segundo, passa a desenhar um a cada dois enquanto precisar (o antigo "Pular quadros", [frameskip.md](frameskip.md)). Em 30 não tem efeito. A antiga escolha "Pular quadros: Sempre" vira Taxa de quadros 30. |
| Android | **Resolução automática**: `Manter 30` (opcional) baixa a resolução interna até a GPU caber em 33 ms por quadro apresentado. |
| Desktop | `WWHD_FPS=30`, ou o preset `WWHD_PRESET=intel30` / `--intel30` (30 fps, resolução automática alvo 30, teto 1.5x). No overlay (F1) a resolução automática tem "keep 30 fps". |

Padrões por build (não mudaram): Galaxy S23 120 fps com resolução automática 120; genérico (Redmi etc.) 60 com "Cair para
30 quando lento" automático. Quem tem aparelho fraco escolhe 30 no menu.

O contador de desempenho (Opções > Tela > fps) mostra, quando os dois diferem, `30 fps | logic 60/s`: quadros apresentados
e passos de lógica por segundo (60 = velocidade total). Fica âmbar quando a lógica cai abaixo de 55 ou no modo 1 em 3.

## Como funciona

O laço do jogo (`procFrame_`, ver [sm3dw-120fps.md](sm3dw-120fps.md)) desenha em cada quadro a display list gravada no
quadro anterior (`procDraw_`), roda a lógica (`procCalc_`) e grava a lista do quadro (`record`). No modo 30:

* **Quadro não apresentado**: roda a lógica e grava a lista; `procDraw_` não roda. Nada vai para a thread de render nem
  para a GPU, além do swap. O índice da lista fica onde está.
* **Quadro apresentado**: desenha a lista gravada no quadro anterior e roda a lógica; não grava (nenhum quadro
  apresentado desenharia essa lista).
* Assim cada lista gravada é desenhada **inteira** num quadro apresentado: HUD, partículas e efeitos não somem, só
  aparecem 30 vezes por segundo. A gravação (emissão de comandos GX2 pelo jogo), a execução na thread de render (estado,
  uploads, descritores) e o trabalho da GPU caem pela metade.
* O swap de um quadro não apresentado não chega ao sistema (`render::g_present_skip`): a submissão é feita, o quadro
  conta como concluído (o flip do jogo segue no ritmo de 59,94 Hz), mas não há present, overlay nem composição.
  São 30 apresentações por segundo, não 60 com repetições.
* O ritmo vem do vsync virtual do jogo (`gx2_core.cpp`, espera precisa com `park_sleep_until`): a lógica não roda
  acima de 60 passos e os quadros apresentados saem a cada 2 vsyncs.
* Uma mudança de divisor só começa num quadro apresentado (o anterior sempre gravou), então trocar 30/60 ao vivo não
  mostra lista velha. Fora do modo de listas do jogo (telas de carga) e no caminho de 120 fps, a thread de render faz o
  pulo antigo (omite draws dos alvos "estáveis" e a cópia para a tela).
* **Degradação elegante**: se mesmo assim a lógica ficar abaixo de 57 passos/s por 3 s seguidos (GPU que não cabe em
  33 ms, por exemplo), o jogo passa a apresentar **um quadro a cada três** (20 fps) mantendo os 60 passos; o log diz
  `[30fps] ... one frame in 3 (20 fps)` e o contador fica âmbar. Depois de 20 s a velocidade total ele tenta um a cada
  dois de novo (a espera dobra até 160 s se falhar). Se nem assim a lógica alcança 60 (CPU lenta demais para a própria
  lógica), o jogo fica mais lento e o contador mostra quantos passos/s está fazendo; a lógica nunca pula passos.

Código: `runtime/src/sm3dw/interp_sm3dw.cpp` (`plan_frame`, ganchos de `procDraw_` e `record`), `runtime/src/gx2/gx2_core.cpp`
(`OP_FRAME_PLAN`, `fs_frame_end`: divisor, automático, degradação, medidas), `gx2.h` (`half_rate`, `present_divisor`,
`logic_steps_per_s`, `presents_per_s`), `gfx/vulkan/backend.cpp` (swap sem present), `overlay/overlay.cpp` (contador),
`android/.../MenuOptions.java`, `app_menu.cpp`.

## Medidas (PC)

i5-12450HX + Intel UHD (ADL-S GT0.5), Mesa 26.2.5, circo 1-5, `tools/bench/sm3dw_bench.sh -f 1200 -- WWHD_BENCH_CAPPED=1
WWHD_LOG_PRESENT_PACING=1` (com limite de 60, como no jogo de verdade). "Passos/s" = quadros do jogo por segundo de
relógio (60 = velocidade real); CPU de render = tempo de CPU da thread de render por segundo; GPU = intervalo da
submissão por quadro apresentado (p50). Script: `shots/30fps/analyze.py` (fora do git).

| Configuração | passos/s | apresentados/s | intervalo entre apresentações | CPU de render | GPU por apresentado |
|---|---|---|---|---|---|
| 1.0x, 60 | 55,0 (câmera lenta 8%) | 55,0 | p50 18,0 ms, p99 27 ms | 673 ms/s (12,2 por quadro) | 16,8 ms |
| **1.0x, 30** | **59,94** | **29,97** | **média 33,37, desvio 1,1, p50 33,37, p99 36,3–36,8 ms** | **303 ms/s (−55%)** | 18,4 ms |
| 1.5x, 60 | 27,7 (meia velocidade) | 27,7 | p50 35,5, p99 68,7 ms | 369 ms/s | 34,7 ms |
| 1.5x, 30 → caiu para 1 em 3 | **59,4** | 19,8 | média 50,5, desvio 3,8, p50 50,0, p99 79 ms | 220 ms/s | 35,9 ms |

* 1.5x: a GPU precisa de ~35 ms por imagem, mais que os 33,4 ms de dois vsyncs; com um a cada dois a lógica fez 52–57
  passos/s, e depois de 3 s abaixo de 57 o modo passou sozinho a um a cada três (20 fps) com a lógica a 59,4. "Manter
  30" (resolução automática) é a saída para ficar em 30 nesse caso.
* Trabalho do jogo (thread do jogo, até a espera do vsync): 987 → 593 ms/s em 1.0x (a gravação das listas cai pela
  metade); o resto do tempo a thread dorme até o vsync.
* Imagem: capturas de 12 imagens apresentadas seguidas no circo em 30 fps (`shots/30fps/seq30/`, quadros 4600–4622: só os pares,
  os apresentados, são capturados) e as do benchmark (`half10/`, `half15/`): HUD (vidas, moedas, estrelas, tempo,
  pontos), sombras, brilhos e partículas presentes em todas; nada some, a cena só anda 2 passos por imagem.
* CPU limitada (`taskset -c 0,1`, o processo inteiro em 2 núcleos, 1.0x): 60 fps fez 50,7 passos/s (câmera lenta 15%);
  30 fps fez **59,95 passos/s** e 29,97 apresentados/s (p50 33,4 ms, p99 37–46 ms), CPU de render 353 → 202 ms/s e
  trabalho do jogo 974 → 354 ms/s. A queda para 1 em 3 não foi necessária aqui; ela foi exercitada no caso de GPU
  (1.5x) acima.

## Depende de validação no celular

* Redmi 10C (Adreno 610): passos/s e ritmo com Taxa de quadros 30, com e sem "Manter 30"; se a queda para 1 em 3
  aparece; temperatura/bateria contra o antigo "Pular quadros: Sempre".
* Galaxy S23: trocar 120 -> 30 -> 60 ao vivo; a tela de 120 Hz fica em 60 Hz no modo 30 (`display_rate.cpp`).
* Tela secundária (GamePad em outro display) também a 30.
