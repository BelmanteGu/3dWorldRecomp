# Super Mario 3D World: apresentar mais de 60 fps (estudo)

Estudo somente leitura, branch `sm3dw`. Nada foi compilado nem executado; tudo abaixo vem de leitura de código
(caminhos relativos à raiz do repositório). O que não pude confirmar está marcado como **NÃO VERIFICADO**.

## Resumo executivo

1. O código `interp`/`true60` **não** é geração de quadros em espaço de imagem. Ele **reexecuta o loop principal do Wind Waker HD**
   (passes "lógico" e "hold") e mistura **estado do jogo** (câmera, juntas de modelos, partículas) antes de desenhar cada passe.
   Depende de dezenas de endereços guest do WWHD (`hook_0203593C`, `hook_025F172C`, `hook_024FFC40`...). No sm3dw esses hooks
   estão desligados, então **nada disso funciona por flag ou env**.
2. Não existe no repositório nenhum código de reprojeção, vetor de movimento, fluxo óptico ou geração de quadros por cor+profundidade
   (busca por `reproject|motion vector|frame gen|optical flow|timewarp` em `runtime/src`, `runtime/include`, `docs`: zero resultados).
   `runtime/src/gfx/depth_peek.h` é só leitura pontual de profundidade para o retículo do Zelda.
3. A lógica do 3D World roda a 1 passo por vsync virtual de 59,94 Hz (o jogo pede swap interval 1). Apresentar 120 Hz exige
   **gerar quadros intermediários no lado do renderizador**, com a lógica ficando em 60.
4. Há um **risco ativo** hoje: no Android, `runtime/src/main.cpp:403` liga o modo interp (`interp::set_mode(1)`) por padrão.
   No sm3dw isso é quase inofensivo a 60, mas **`WWHD_INTERP_FPS=120` dobraria a velocidade do jogo** (seção 3.4). Não usar.
5. Recomendação: fazer (a) corretamente no Android/macOS (pedir modo 120 Hz, medir), depois um protótipo (b') de geração no
   renderizador com cor + profundidade + matriz da câmera lida do shader/uniform. Custo estimado em 5. Ganho real só vale se a GPU
   sobrar; hoje ela não sobra (13 ms por quadro a 60 fps).

## 1. O que o código atual faz

### 1.1 Modos

| Modo | O que faz | Onde |
|---|---|---|
| 30 fps | Jogo original do Zelda (swap interval 2) | `runtime/src/gx2/gx2_core.cpp:986` |
| Interp 60/120/240 (`mode()==1`) | Lógica a 30 Hz; N quadros "hold" sem lógica, desenhados com câmera/modelos/efeitos misturados. N = fps/30 - 1 | `interp.cpp:5-21, 136-138` |
| True 60 (`mode()==2`) | Lógica a 60 Hz só para processos convertidos (Link, câmera de seguimento), via ~200 pontos de patch por instrução (`site_XXXXXXXX`) | `true60.cpp:1-27`, `true60_sites.cpp` (gerado de `tools/true60/sites_*.txt`) |
| Pacing "paced" | Planeja quantos quadros in-between cabem no orçamento; descarta quando não cabem | `interp_pacing.h:70-148`, `interp.cpp:717-737` |

Relógio virtual: `gx2_core.cpp:583` fixa o vsync em 59,94 Hz (16,683 ms); a 120/240 fps o vsync virtual acelera 2x/4x
(`gx2_core.cpp:605-607`, `interp.cpp:178-179`) e `GX2SetSwapInterval` é reduzido pela metade (`interp.cpp:178`).

### 1.2 O que ele exige do jogo

| Necessidade | Como é obtida | Genérica? |
|---|---|---|
| Loop principal / função por quadro | Hooks em `0203593C`, `025F172C`, `025D42EC` e filhos (`interp.cpp:51-90, 980, 1108-1222`) | **Não, endereços WWHD** |
| Câmera (eye/center/up/fovy/bank) | Offsets de `camera_draw` `024FFC40` (+0xDC, +0xE8, +0xF4, +0xD4, +0x100) (`interp.cpp:24-25, 182`) | **Não** |
| Matrizes de modelo J3D | Hooks `027F55FC` (viewCalc), `027F5018` (`interp.cpp:581, 658`) | **Não** (J3D é do Zelda) |
| Efeitos (partículas, mar, céu, pano) | `interp_fx.cpp` (122 referências a endereços WWHD) | **Não** |
| Profundidade, vetores de movimento | **Não usa.** Nenhum dos dois é lido | n/a |
| Separação do HUD | Nenhuma. O HUD do Zelda é desenhado por hooks que o excluem da mistura; não há máscara em imagem | **Não** |
| Entrada | `repeat_input()` repete a amostra do VPAD/KPAD no passe hold (`interp.cpp:1324-1327`, `hle/system_stubs.cpp:104`, `hle/padscore.cpp:78`) | Parcial: só vale com `g_hold_next` ligado por hook |

Contagem de referências a endereços do jogo: `interp.cpp` 107, `interp_fx.cpp` 122, `true60.cpp` 94, `true60_link.cpp` 19,
`interp_reticle.cpp` 12 (grep por `hook_0|f_0XXXXXXX`).

### 1.3 Parte agnóstica de jogo (reaproveitável)

| Arquivo | Conteúdo reaproveitável |
|---|---|
| `runtime/src/interp_pacing.h` | Frações de mistura, `slerp_weights`, `cap_fps` (limita ao refresh do display), `plan_in_between`, médias móveis. Sem estado de jogo; tem teste `runtime/tools/interp_pacing_test.cpp` |
| `runtime/src/platform/display_rate.cpp` | Lê o refresh (Android via `WwhdActivity.displayRefreshRate/maxRefreshRate`; macOS `NSScreen.maximumFramesPerSecond`, `gfx/display.mm:264-269`) e **pede o modo de display** `requestRefreshRate` (`WwhdActivity.java:229-253`) |
| `runtime/src/gx2/gx2_core.cpp` + `gx2/vsync_pacing.h` | Relógio virtual de vsync, filas de flips, `GX2WaitForVsync` |
| `runtime/src/gfx/vulkan/settings.h:23-29`, `backend.cpp:1341-1369` | Modo de apresentação FIFO/MAILBOX/IMMEDIATE; `interp::set_present_vsync` |
| `runtime/src/platform/perf_hint.cpp` | `APerformanceHintSession`, afinidade de núcleos, headroom térmico (pausa interp a 72%, retoma a 60%, linhas ~85-100) |

### 1.4 Parte presa ao WWHD

`interp.cpp` (passes, câmera, modelos), `interp_fx.cpp`, `interp_reticle.cpp`, `true60*.cpp`, `true60_sites.cpp`,
`tools/true60/*`. Em `tools/recomp/sm3dw_stubs.py:1-30` os símbolos `f_XXXXXXXX`/`hook_*`/`site_*` do Zelda são substituídos por
stubs que **param o processo** (`ppc_unimplemented`) ou no-ops. Isso confirma que esses recursos têm de ficar desligados.
`tools/recomp/hooks_sm3dw.txt` tem só 2 hooks: `023D7724` (projeção sead) e `0263530C` (projeção de layout 2D).

## 2. A lógica do jogo é travada em 60?

| Fato | Evidência |
|---|---|
| O jogo chama `GX2SetSwapInterval(1)` com constante em `0x02359A14` | `build/gen3dw/code_029.c:5303` (`r3 = 1` em `02359A10`) |
| Existe outra chamada em `0x024DA808`, com valor vindo de uma chamada virtual | `build/gen3dw/code_042.c:9017`. **NÃO VERIFICADO** o valor; se o jogo usa 2 em cutscenes/carregamento, `effective_swap_interval` (`interp.cpp:178`) com interp ligado o forçaria a 1 |
| Espera de vsync: `GX2WaitForVsync` em `0x02375048` (rotina com swap em `0x0237514C`), em `0x023519C4`/`0x02351A1C` e em `0x02359E08` | `code_030.c:2951,3028`; `code_028.c:32354,32378`; `code_029.c:5572` |
| Swap: `GX2SwapScanBuffers` em `0x0237514C`, `0x0235A1A0`, `0x0235A1F0`, `0x024DB998` | `code_030.c:3028`; `code_029.c:5819,5840`; `code_042.c:10239` |
| No runtime, o vsync é virtual a 59,94 Hz e o flip só executa depois de `swap interval` vsyncs | `gx2_core.cpp:583, 630-634, 987-1051` |

Conclusão: o ritmo vem do **bloqueio em `GX2WaitForVsync` + swap interval 1**, ou seja 1 passo lógico por vsync virtual (59,94/s).
**NÃO VERIFICADO**: se a física usa `dt` variável ou passo fixo por quadro. O conhecimento geral (Cemu) é passo fixo por quadro a 60,
mas não li o código do jogo. Também não analisei onde o VPAD é amostrado (`VPADRead`/`KPADRead` na lógica) nem os `OSSleep*`/`OSWaitEvent`
(19 chamadas no código gerado, não classificadas). Recomendo um `WWHD_TRACE_SWAP=n` (`gx2_core.cpp:906`) para mapear o caminho de chamada do swap.

**Dá para ter lógica a 60 e apresentação a 120?** Sim, por construção do runtime: o jogo é consumidor do vsync virtual; o renderizador roda
em outra thread (`render::swap`, `gx2_core.cpp:494`; `render::frames_completed()`). Basta o **apresentador** (thread de render) emitir 2 quadros por swap
do jogo. O gerador precisaria de:

| Entrada | De onde | Dificuldade |
|---|---|---|
| Cor do quadro N-1 e N | Alvo de apresentação (já existe, composição em `display.mm`/`vulkan/backend.cpp`) | Baixa: manter duas texturas |
| Profundidade N (e N-1) | Buffer de profundidade do passe 3D principal; o renderizador o aloca, mas não o guarda após o passe | Média: copiar/manter e identificar o alvo "principal" |
| Matriz da câmera N-1 e N (view-proj) | Uniform do shader dos draws 3D, ou `0x023D7724` | Média-alta (seção 3.c) |
| Vetores de movimento por objeto | Não existem. Inferíveis só por fluxo óptico em imagem | Alta |
| Máscara do HUD | O HUD é um passe 2D separado em alvo (a verificar) ou desenhado direto no scan buffer; sem isso o HUD "borra" | Média |

## 3. Opções ranqueadas

### (a) Só pacing/apresentação a 120 Hz (sem ganho de fluidez)

- Android: `display_rate.cpp` só pede 120 Hz quando `interp::mode()==1 && fps>60` (linha ~58). Com interp desligado o sistema escolhe (`want=0`),
  tipicamente 60 Hz ou 120 adaptativo. Cada quadro de 60 fps aparece 2 vezes. **Nenhum ganho visível**; só vale como infraestrutura
  (pedir 120 Hz e ter FIFO estável é pré-requisito de (b)/(c)).
- Custo: ~0,5 dia (chamar `requestRefreshRate(120)` quando houver gerador ativo).

### (b) Geração genérica em espaço de imagem, só cor+profundidade

- **Não existe hoje.** O que o código chama de "frame interpolation" é (b'') abaixo. A flag `WWHD_INTERP_FPS` não ativa nada de imagem.
- Implementação nova no renderizador (Vulkan e Metal): reprojeção por profundidade com a matriz de movimento global (câmera) e, no mínimo,
  um "blend/warp" 1 quadro atrasado (interpola entre N-1 e N; adiciona 1 quadro de latência) ou extrapolação (sem latência, mais artefatos).
- Sem matrizes da câmera só dá fluxo óptico/estimativa de movimento por blocos em compute: qualidade baixa em plataformas com
  geometria fina (Mario 3D tem muitas), ghosting em alpha (água, vidro, partículas) e no HUD. Custo GPU: pelo menos 1-2 passes de compute em
  1080p e 1 passe de composição.
- Esforço: **3-4 semanas** para um protótipo (Vulkan Android + Metal), mais 2-4 semanas de ajuste de artefatos. Estimativa minha.

### (b'') Reaproveitar `interp.cpp` com endereços do 3D World

Teoricamente possível (a máquina de passes é boa), mas exige remapear: loop principal, `fapGm_Execute` equivalente, câmera, modelos, efeitos
e os ~1000 pontos que o `true60` usa. Equivale a refazer o port do Zelda para outro motor (o 3D World usa `sead`, não `J3D`/`fpc`). **Não recomendado**
(meses). Ganho adicional: quadros intermediários com geometria correta, sem artefatos de imagem.

### (c) Reprojeção por matriz da câmera (câmera + profundidade)

Mistura o melhor de (b) com custo menor: reprojeção por profundidade usando view-proj N-1 e N (movimento de câmera exato; objetos dinâmicos
ficam com erro e requerem fallback ou blend). É o esquema "timewarp espacial".

Como achar as matrizes no 3D World:

| Método | Detalhe | Estado |
|---|---|---|
| Hook de `sead::PerspectiveProjection` `0x023D7724` | Já ligado (`runtime/src/sm3dw/aspect_sm3dw.cpp:15-25`): `r3` = objeto, +0xE0 fovy, +0xE4 aspect, +0xE8 tan(fovy/2), +0x30 near. O hook chama `f_023D7724_orig`; **depois** dela a matriz de projeção está no objeto (offset **NÃO VERIFICADO**; ver `sead::Projection::mDeviceProjectionMatrix/mProjectionMatrix` nos headers sead/decomp e comparar com o dump de memória após a chamada) | Projeção: fácil. View: não |
| View (`sead::Camera`/`LookAtCamera`) | Procurar o chamador de `0x023D7724` (xrefs no `RedCarpet.rpx` com `tools/rpxinfo.py` e `build/gen3dw`); a projeção e a câmera costumam estar em `sead::Viewport/Camera` do mesmo contexto de desenho (`agl`/`sead::DrawContext`). Procurar `LookAtCamera::doUpdateMatrix` (duas escritas de 3x4 floats) | **NÃO VERIFICADO** |
| Sem endereços: capturar no GX2 | O `GX2SetVertexUniformReg`/`UniformBlock` de cada draw 3D carrega `viewProj`. Procurar o vec4x4 comum a >90% dos draws do passe principal. `0x0263530C` (hooks_sm3dw.txt:3) é só o 2D (layouts) e **marca** a projeção 2D; o renderizador já tem `aspect::tv_projection_call` e `OP_SET_PROJ_REGS` (`gx2_core.cpp:500`) | Viável e agnóstico, **NÃO VERIFICADO** nos shaders do 3D World |

- Esforço: (b) + 1-2 semanas para a captura e validação da matriz = **4-6 semanas** no total.
- Objetos em movimento (Mario, inimigos, plataformas): sem motion vector, erram até ~1 quadro de deslocamento; aceitável a 120 Hz se a
  magnitude for pequena, mas gera ghost nas bordas. Mitigação: detectar disoclusão e cair para blend simples.

### (d) Outras ideias

| Ideia | Comentário |
|---|---|
| Motion vectors "de graça" via skinning (matrizes de ossos N-1 e N) | Exigiria hook no código de animação do jogo (equivalente ao `hook_027F55FC`). Qualidade ótima, custo de reverse alto |
| Extrapolação (latência zero) em vez de interpolação | Sem +1 quadro de atraso, mas artefatos piores. Interessante para o Mario (jogo de precisão de salto) |
| True 60 sobre 120 Hz: rodar lógica a 120 | **Inviável**: o jogo assume passo fixo por quadro; reconverter integração (como `true60` faz para o Zelda) é o trabalho do item (b'') |
| Reduzir resolução interna para liberar GPU | `WWHD_RES_SCALE` (README:626; hoje o jogo roda a 1x). Passar de 1x para 0,75x pode dar a folga para o gerador |
| 90 Hz | O S23 tem modos 60/120 (e adaptativo). 90 não ajuda: o jogo é 60 |

## 4. Riscos

| Risco | Detalhe |
|---|---|
| **Aceleração do jogo (importante)** | Em `main.cpp:403` o Android liga `interp::set_mode(1)` por padrão (só se `WWHD_INTERP`/`WWHD_TRUE60` não estiverem no ambiente). Com `WWHD_INTERP_FPS=120`: `vsync_rate()=frames_per_step/2=2` (`interp.cpp:179`) e o vsync virtual passa a 119,88 Hz; como o 3D World não tem passes hold, **cada vsync vira um passo lógico: jogo a 2x**. Mesmo a 60 (padrão) o modo 1 altera `effective_swap_interval` (`interp.cpp:178`) e `perf_hint.cpp::adapt_60` (≈linhas 55-110) pode alternar o modo por thresholds feitos para 30 fps (`rate < 50`). Sugestão: no sm3dw forçar `interp::set_mode(0)` e desligar `adapt_60` |
| Contagem de passos | `g_logic_steps` só incrementa em `hook_0203593C` (`interp.cpp:1044`), que não roda no sm3dw: `interp::logic_steps()` fica 0 e `gx2_core.cpp:928-931` classifica **todos** os swaps como "hold" (usado no perfilador, e passado em `OP_SWAP`). **NÃO VERIFICADO** se o renderizador usa esse flag para algo além de perfil |
| Ghosting do HUD / 2D | O HUD do 3D World são layouts (`.bflyt`) desenhados com a projeção do `0x0263530C`. Sem máscara, um warp por câmera distorce o HUD. Mitigação: aplicar o warp só ao alvo 3D e compor o HUD (passe 2D) por cima no quadro gerado. Precisa confirmar se o HUD é desenhado em alvo separado ou direto no scan buffer (**NÃO VERIFICADO**) |
| Layout 2D / Hor+ | Já tratado em `aspect.cpp` + hooks; o gerador deve rodar depois do ajuste de aspecto |
| Latência de entrada | Interpolação: +1 quadro de 60 Hz (~16,7 ms) além do pipeline atual (cada swap já passa pelo vsync virtual e pela thread de render). Extrapolação: sem acréscimo |
| Térmica / GPU Adreno 740 | Já ~80% ocupada a 60 fps (13 ms de GPU por quadro a 1x, dado do pedido, não medido por mim). O gerador custa >= 1-2 ms de compute por quadro gerado, ou seja, sobram ~3 ms por 8,3 ms de orçamento a 120 Hz. O `perf_hint.cpp` (headroom 72/60%) pausa o modo; reutilizável |
| Bateria | Display a 120 Hz + GPU contínua: estimativa de +25-40% de consumo (estimativa minha, não medida) |
| Vsync Android/Vulkan | FIFO a 120 Hz exige `preferredDisplayModeId` (feito em `WwhdActivity.java:229-253`). O Android pode derrubar para 60 Hz sob térmica ou modo economia; o `display_rate.cpp` relê a taxa real |
| macOS/Metal | `displaySyncEnabled`+`presentDrawable` (`display.mm:810-824`); ProMotion apresenta até 120 Hz, mas só se a camada for apresentada continuamente a 120 (não no modo janela com ocultação). Metal tem folga de GPU maior (M-series) |

## 5. Plano recomendado

### Fase 0: higiene (0,5-1 dia)

| Arquivo | Mudança |
|---|---|
| `runtime/src/main.cpp:403` | Não ligar `interp::set_mode(1)` quando `WWHD_GAME_RPX == RedCarpet.rpx` |
| `runtime/src/platform/perf_hint.cpp` (`adapt_60`) | Pular quando o jogo é nativo 60 fps; manter só a parte térmica e o hint de sessão |
| `runtime/src/interp.cpp:100-105` | No sm3dw, ignorar `WWHD_INTERP`/`WWHD_INTERP_FPS`/`WWHD_TRUE60` e logar um aviso (evita 2x de velocidade) |
| `runtime/src/gx2/gx2_core.cpp:928-931` | Considerar o swap como "step" quando os hooks interp não existem |

### Fase 1: infraestrutura (a) (1-2 dias)

- `display_rate.cpp::poll`: pedir 120 Hz quando o gerador estiver ativo (flag nova, p.ex. `WWHD_GEN_FPS=120`).
- Apresentador: re-apresentar o último quadro 2x por swap do jogo com FIFO. Medir com `WWHD_LOG_FLIP_PACING=1` (`gx2_core.cpp:670`) e `WWHD_VK_GPU_TIMESTAMPS=1`.

### Fase 2: gerador em imagem (b) com cor+profundidade (3-4 semanas)

- Novo `runtime/src/gfx/framegen.{h,cpp}` (agnóstico), com backends `vulkan/framegen_vk.cpp` e `metal_framegen.mm`.
- Pontos de integração: `gfx/vulkan/backend.cpp` (perto de `1341-1369`, composição), `gfx/display.mm::present_to_layer` (`:806`), `gx2_core.cpp::OP_SWAP` (`:494`) para entregar "quadro do jogo" vs "quadro gerado".
- Manter cópia de cor e profundidade do passe principal (identificar o alvo pelo maior alvo com profundidade, ou por tag do draw).

### Fase 3: matriz da câmera (c) (+1-2 semanas)

- `runtime/src/sm3dw/aspect_sm3dw.cpp`: depois de `f_023D7724_orig`, ler a matriz de projeção do objeto sead e gravar em um buffer "prev/cur" para o renderizador; localizar `sead::Camera` (chamador de `0x023D7724`) e fazer o mesmo.
- Alternativa sem endereços: capturar o `viewProj` do uniform dominante nos draws 3D (a validar com `WWHD_DRAWLOG_*`-like).
- Adicionar hook(s) em `tools/recomp/hooks_sm3dw.txt` (somente arquivo valido para sm3dw) e regenerar com `RECOMP_GAME=sm3dw python3 tools/recomp/recomp.py`.

### Variáveis de ambiente para testar primeiro no telefone (`env.txt`, uma `NOME=valor` por linha, lido em `main.cpp:405-415`)

| Linha | Para quê |
|---|---|
| `WWHD_INTERP=0` | Evita o modo 1 padrão (`main.cpp:403`) e qualquer risco de 2x de velocidade. **Primeira linha a pôr** |
| `WWHD_PERF_HINT=1` (padrão) / `WWHD_PERF_HINT=0` | Compara com/sem `APerformanceHintSession` (`perf_hint.cpp:48`) |
| `WWHD_FAST_CORES=1` / `0` | Afinidade de núcleos (`perf_hint.cpp:177`) |
| `WWHD_VK_PRESENT_MODE=fifo` / `mailbox` | Modo de apresentação (`README.md:383`; `backend.cpp:1346-1361`); veja no log se o modo foi aceito |
| `WWHD_DISPLAY_HZ=120` | Força o refresh que o código assume (`interp.cpp:119`); só afeta o cap do interp, útil para testes |
| `WWHD_LOG_FLIP_PACING=1` | Intervalo entre flips (p50/p95/p99) a cada 300 flips (`gx2_core.cpp:670-692`) |
| `WWHD_LOG_SLOW_SWAP=20` | Loga swaps com mais de 20 ms (`gx2_core.cpp:960-967`) |
| `WWHD_LOG_VSYNC=1` | Estado de vsync a cada 60 chamadas (`gx2_core.cpp:1054`) |
| `WWHD_VK_GPU_TIMESTAMPS=1` | Intervalos de GPU (`docs/vulkan.md:877`; não é tempo ocupado, nem para aceitação) |
| `WWHD_RES_SCALE=1` (testar também 0.75 se aceito; valores documentados 1/1.5/2/3) | Margem de GPU (README:626). **0.75 NÃO VERIFICADO** como valor aceito |
| `WWHD_TRACE_SWAP=5` | Cadeia de chamada dos 5 primeiros swaps, para mapear o loop do jogo (`gx2_core.cpp:906`) |
| `WWHD_VK_ASYNC_PRESENT` (ver `backend.cpp:1447`) | Apresentação assíncrona; verificar o valor aceito no código antes |

**Não pôr**: `WWHD_INTERP_FPS`, `WWHD_TRUE60`, `WWHD_UNCAPPED` (este último acelera o jogo, `docs/vulkan.md:878`).

### Estimativa total

| Entrega | Esforço |
|---|---|
| Fase 0 + 1 (higiene + 120 Hz pedido, sem ganho) | 2-3 dias |
| + Fase 2 (gerador cor+profundidade, 1 backend) | +3-4 semanas |
| + Fase 3 (matriz de câmera) | +1-2 semanas |
| Ajuste de artefatos (HUD, alpha, disoclusão) e perf no S23 | +2-4 semanas |
| Port do `interp`/`true60` (b'') | Meses; não recomendado |

## 6. O que não pude verificar

- Nenhum dado de execução: GPU 13 ms, 80% e o custo térmico/bateria são os informados no pedido ou estimativas.
- Se a lógica do jogo usa `dt` ou passo fixo; quais `OSSleep*`/`VPADRead` estão no caminho do quadro; o valor do segundo `GX2SetSwapInterval` (`0x024DA808`).
- Offsets da matriz de projeção/view no objeto sead e o chamador de `0x023D7724`.
- Se o HUD é desenhado em alvo próprio; se o renderizador usa o flag `hold` do `OP_SWAP` além do perfil.
- Comportamento real do Android ao pedir 120 Hz com interp desligado (`display_rate.cpp` só pede com interp ligado).
