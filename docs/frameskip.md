# Pular quadros (frame skip)

GPUs lentas (Adreno 610, por exemplo) fazem o jogo, que trava a lógica ao quadro, rodar a meia velocidade. Com frame skip o
jogo desenha e apresenta um quadro sim, um não, e a lógica continua a 60 passos por segundo. Desde o #18 o frame skip é o
mesmo mecanismo do modo **30 fps (velocidade total)**, descrito em [30fps.md](30fps.md): o que muda é quando ele liga.

| Modo | Efeito |
|---|---|
| 0 desligado | sempre 60 (ou 120) apresentados; num aparelho lento, câmera lenta |
| 1 automático | liga abaixo de ~54 quadros de jogo por segundo e volta a tentar sem ele (a espera dobra até 2 min) |
| 2 sempre | como `WWHD_FPS=30`, mas sem a queda para 1 em 3 (mantido para scripts antigos) |

* Variável: `WWHD_FRAMESKIP=0|1|2` no início.
* Android: o menu tem **Taxa de quadros 30 (velocidade total) / 60 / 120** e, abaixo, **Cair para 30 quando lento**
  (Desligado / Automático, padrão Automático), ambos ao vivo e salvos. "Cair para 30" só age em 60 e 120; em 30 o jogo já
  apresenta um quadro a cada dois. A antiga escolha "Sempre" é migrada para Taxa de quadros 30 na primeira abertura.
* Como funciona: o próprio jogo planeja cada quadro (`OP_FRAME_PLAN`, `sm3dw/interp_sm3dw.cpp`). No modo de display lists
  do jogo, o quadro não apresentado não executa lista nenhuma (nada para a thread de render nem para a GPU) e o
  apresentado não grava lista; cada lista gravada é desenhada inteira. O quadro não apresentado também não é apresentado
  ao sistema (30 apresentações por segundo, não 60 com repetições). Fora do modo de listas (telas de carga) e sem plano (o
  caminho de 120 fps) a thread de render faz o pulo antigo: omite draws e clears dos alvos "estáveis" (os que o último
  quadro desenhado preencheu) e a cópia para a tela; alvos de uso único, cópias e sincronizações continuam.
* Implementação: `runtime/src/gx2/gx2_core.cpp` (decisão, `fs_frame_end`, `OP_FRAME_PLAN`), `gx2.h`
  (`frameskip()` / `set_frameskip`, `present_divisor()`), `sm3dw/interp_sm3dw.cpp` (`plan_frame`),
  `gfx/vulkan/backend.cpp` (`render::g_present_skip`).
* Medido (Redmi 10C, modo "sempre", versão anterior ao #18, só o pulo na thread de render): 57-60 quadros de jogo por
  segundo onde antes faziam 30. Uma resolução interna de 0.25x também existe para essas GPUs.
* No 120 fps (`docs/sm3dw-120fps.md`) o automático continua valendo pelo caminho antigo (thread de render).
