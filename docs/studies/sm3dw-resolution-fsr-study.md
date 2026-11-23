# Resolução interna e FSR 1: estudo de projeto

Estudo somente leitura (nenhum código alterado, nada executado). Contexto de medições em `docs/sm3dw-study.md`, `sm3dw-perf-study.md`, `sm3dw-render-cpu-study.md`; não repetido aqui. Caminhos relativos a `runtime/src/` salvo indicação. Marcas: **[V]** verificado no código lido; **[NV]** não verificado (memória, estimativa ou depende de rodar o jogo).

## Resumo

| Tema | Conclusão |
|---|---|
| Ponto de inserção | O `compose()` do Vulkan (`gfx/vulkan/present.cpp:358`) já desenha o quad da TV **e depois** o ImGui no mesmo passe. O upscaler entra antes disso: EASU num passe prévio para uma imagem intermediária do tamanho do quad; RCAS dentro do fragment do quad, sem passe extra. O overlay continua nítido. |
| Estado atual | Existe um filtro final de um passe só (bilinear / "sharp bilinear" / inteiro, caixa de 4 taps ao reduzir, FXAA), em GLSL (`present.cpp:40-100`) e MSL (`gfx/display.mm:518-570`). Não há bicúbico nem FSR. |
| <1x | No Vulkan o clamp já é 0,5..4 (`gfx/vulkan/surfaces.cpp:90-101`); **no Metal continua 1..4** (`gfx/metal_surfaces.mm:46-56`). O alvo só depende de `target_scale` + `screen_shaped`, mas há riscos específicos abaixo de 1x (seção C.4). |
| Caso S23 | Tela 2340x1080 e alvo 1559x720 a 1x: razão 1,5 = "Quality" do FSR 1. A 0,75x (1169x540): razão 2,0 = "Performance". 0,5x: razão 3,0, fora do uso recomendado do FSR 1. |
| Ganho esperado | FSR não aumenta fps (CPU-bound). Serve para qualidade a custo de GPU baixo, ou para trocar GPU/calor por resolução menor. Custo do FSR no Adreno 740: **[NV]** estimado 0,5 a 1 ms/frame para EASU+RCAS em 2340x1080; medir com A/B. |
| Licença FSR 1 | MIT (AMD FidelityFX FSR 1.0, `ffx_a.h` + `ffx_fsr1.h`) **[NV: confirmar o cabeçalho de cada arquivo ao vendorizar]**. Compatível com o MPL-2.0 do projeto (`LICENSE`); manter o aviso de copyright. |

---

## A. Mapa do pipeline atual

### A.1 Vulkan (Android e Windows/Linux; macOS via MoltenVK)

| # | Etapa | Onde | Observações |
|---|---|---|---|
| 1 | Alvos de render do jogo | `surfaces.cpp:604` (`s->scale = target_scale(s)`), extensão em `:605-616`: `ceil(w*scale*ax - 0.01)` x `ceil(h*scale*ay - 0.01)` | `ax/ay` = kx/ky do aspecto só para alvos `screen_shaped` (`:141-163`). `sx/sy` = extensão real / tamanho guest, guardados em cada `Surface`. |
| 2 | Draws | `draw.cpp:2472-2498` | Viewport e scissor multiplicados por `sx/sy` do alvo, com tolerância 0,01 (`lo`/`hi`). `aspect::panes::clip` aplica recorte de conteúdo. |
| 3 | Uniformes dependentes de escala | `shaders.cpp:1276-1279` (`fragCoordScale = 1/scale`), `draw.cpp:1875-1879` (`uf_texNScale = {sx,sy}` por unidade de textura; sobrescreve o `{1,1}` de `shaders.cpp:1282`, cujo comentário está defasado) | `gl_FragCoord` volta ao espaço guest; `texelFetch/LD` multiplica por `texScale` (decompilador, `LatteDecompilerEmitGLSL.cpp:2444-2458`). |
| 4 | Fim do quadro do jogo: `GX2CopyColorBufferToScanBuffer` | `gx2/gx2_core.cpp:473` -> `gfx/vulkan/backend.cpp:2328` `copy_to_scan` | Cria `buildingScan` com a **extensão do alvo** (já em resolução interna x kx), `scale=1`, copia por `resample` (blit/cópia), desenha o HUD de mods (`draw_mod_overlay`) em cima, marca `buildingScanSrgb = s.srgb` (`:2352`, vindo de `set_tv_format`, bit 0x400 do formato, `backend.cpp:3018`). |
| 5 | Troca no `swap()` | `backend.cpp:2605-2612` | `scan <-> buildingScan`; `scanSrgb` publicado. **A imagem final sem UI é `R.tv.scan` (`Surface` com `view`, `usage` SAMPLED)**: já é textura amostrável. |
| 6 | Plano de layout | `gfx/display_modes.cpp:204-232` (`fit`/`layout`) | Escala de ajuste `min(dw/tw, dh/th)` (float qualquer); modo "integer" faz `floor`; barras pretas (letterbox) aparecem quando as razões diferem; caixa arredondada com `roundf`. PIP/GamePad no mesmo plano. |
| 7 | Composição + escala + overlay | `present.cpp:358` `compose()`; quads em `screen_quads_mono` (`:485-537`) | Um `vkCmdBeginRendering` na imagem do swapchain; para cada quad: pipeline `kImage` (fragment `present.cpp:40-100`), sampler linear ou nearest; `present_params` (`:162`) calcula `sharp`/`foot` a partir de `scale = box/extent`. Depois `overlay_draw` (ImGui, GamePad HUD) no mesmo passe. |
| 8 | Swapchain | `backend.cpp:1186-1260` | Host SDL (Android): `B8G8R8A8_SRGB` preferido (`:1243`); host AppKit: sRGB só se `scanSrgb`; HDR scRGB `R16G16B16A16_SFLOAT` opcional (`hdr::resolve_output`). 2340x1080 no S23 (**[NV]** o valor exato vem de `swapExtent`, não lido em execução). |
| 9 | Caminhos paralelos | `draw_present_screen` falha para blit se swapchain não é "drawable" (`present.cpp:562-575`); `compose_offscreen` (`:722`) para dumps; `record_screenshot` (`:738`) no tamanho do scan; `IndependentPresenter`/`AndroidIsolatedPresenter` para a 2ª tela (GamePad) | Qualquer upscaler novo precisa decidir o que fazer em cada um (ver B.6). |

**Espaço de cor.** `conv` (`present_params`) converte entre "scan codificado" e "alvo linear" (swapchain sRGB). Se o scan é UNORM com valores já codificados (caso `scanSrgb=false`), o shader faz `toLinear` e o hardware reencoda ao escrever no swapchain sRGB (ida e volta em 8 bits). Se `scanSrgb=true` e a imagem é UNORM com valores lineares, passa direto. **Qual dos dois o SM3DW usa não foi verificado [NV]**: depende do `format` passado a `GX2SetTVBuffer` (`gx2_core.cpp:1065`). Medir: logar `scanSrgb` e `scan->fmt.pixel` uma vez (uma linha em `swap()`).
FXAA hoje roda sobre o que `texture()` devolve; com view sRGB isso é linear, com UNORM é codificado (o `toLinear` vem depois). Para FSR isso importa: ver B.3.

### A.2 Metal (macOS)

| # | Etapa | Onde |
|---|---|---|
| 1 | Alvos | `gfx/metal_surfaces.mm:182-200` (`rescale`), `make_texture` `:158-180`; `target_scale` `:110-122`; `screen_shaped` `:96-103` (mesma regra) |
| 2 | Cópia para scan | `gfx/metal_main.mm:283-306`: textura `R.tv.tex` com o tamanho/formato do alvo, blit, `mods::draw_overlay` |
| 3 | Composição | `gfx/display.mm:756-780` `compose_tv`: `draw_image` (`:721-750`) com `present_fs` (MSL, `:518-570`), pipeline por formato (`:572-600`), depois ImGui no mesmo encoder |
| 4 | Latch | `metal_main.mm:492` `latch_res_scale()` a cada quadro; Vulkan: `backend.cpp:2792` |

Mesma estrutura: o quad da TV e o ImGui dividem um encoder. A imagem pré-UI (`R.tv.tex`) é textura amostrável.

### A.3 Onde entra o upscaler

```
alvos do jogo (guest*res*kx) -> copy_to_scan -> scan (R.tv.scan / R.tv.tex)
     [novo, só se razão >= ~1,05: passe EASU -> imagem intermediária (tamanho do quad)]
     -> compose(): quad TV (RCAS no fragment, ou o filtro atual) -> GamePad/PIP -> ImGui
     -> swapchain (sRGB ou HDR)
```
Nada do que vem depois do quad da TV (ImGui, botão de vista, moldura do PIP) passa pelo FSR. O HUD do jogo e o HUD de mods já estão dentro do scan e **serão** upscalados (inevitável sem alvo separado para a UI do jogo).

---

## B. Projeto do FSR 1

### B.1 Estruturas atuais úteis (e o que já existe como "upscaler simples")

| Já existe | Onde | Serve para |
|---|---|---|
| Bilinear com sampler linear | `present.cpp:226-233`, `display.mm:597-603` | filtro padrão |
| "Sharp bilinear" (`filter==1`: banda de mistura reduzida pelo `scale`) | `present.cpp:62-66`, `present_params` `:162-166` | pixel-art/nitidez barata |
| Inteiro (`filter==2`, `scale = floor`, nearest efetivo `sharp=1e4`) | `present_rect` `:156-161`, `display_modes.cpp:204` | |
| Caixa 4 taps ao reduzir (`foot = 1/scale`, `>1,25`) | `present.cpp:69-73` | 2x/3x em tela menor |
| FXAA | `present.cpp:46-61`, `display.mm:533-546` | |
| Setting `scale_filter` 0/1/2, env `WWHD_SCALE_FILTER=smooth\|sharp\|integer`, salvo como `scaleFilter` | `settings.cpp:23`, `overlay_sdl.cpp:196,226`, `display_modes.cpp` | ponto de extensão natural |
| Não existe | bicúbico/Catmull-Rom/Lanczos, EASU/RCAS, `shaderFloat16` (nenhum `float16` em `backend.cpp`) | |

**Alternativa simples (fase 1):** Catmull-Rom em 9 taps bilineares (técnica de Jimenez/Filmic, 5 taps efetivos) como novo valor de `scale_filter` (3 = "bicubic"). Custo de ~1 ALU de peso por pixel, sem passe extra, sem intermediário, mesma push constant (`PresentParams`), trivial no MSL. Ganho visual sobre bilinear em razão 1,5-2 é claro; sobre FSR, menor. É também o fallback quando EASU não puder rodar.

### B.2 Inserção do EASU/RCAS (Vulkan)

**Escolha: fragment passes, não compute.** Motivos: `present.cpp` só tem pipelines gráficos com `VK_KHR_dynamic_rendering`; a imagem do swapchain no Android nem sempre tem `STORAGE` (usa-se `COLOR_ATTACHMENT`, `backend.cpp:1260-1330` **[NV: flags exatas]**); a intermediária pode ser attachment normal; Adreno tem desempenho equivalente em fragment/compute para um filtro de 12 taps. Compute só se a medição mostrar vantagem (a AMD usa 64 threads/ wave, `A_GPU` + `FsrEasuF` em CS).

**Passes:**

| Passe | Entrada | Saída | Quando |
|---|---|---|---|
| (opcional, fase 4) FXAA pré-EASU | scan | intermediária do tamanho do scan | FSR ligado e FXAA ligado: o FSR 1 espera entrada já com AA; hoje FXAA roda no mesmo fragment da escala |
| **EASU** | scan (view UNORM, ver B.3), sampler linear/gather | `fsrTarget` (RGBA8 UNORM, `box.w x box.h`) | `box/extent` entre ~1,05 e ~4 |
| **RCAS + conversão final** | `fsrTarget` por `texelFetch` (alinhado a pixel: `fetch = gl_FragCoord - box.xy`) | direto no quad do `compose()` | sempre que FSR ligado (com razão ~1: só RCAS = nitidez) |
| ImGui, PIP, botões | | mesmo passe do RCAS | inalterado |

Razão <1 (internal 2x/3x em tela menor): não usar FSR; manter caixa 4 taps. FSR 1 não reduz.

**Implementação (arquivos):** `present.cpp` ganha `Kind` novos (`kEasu`, `kRcas`) em `enum Kind` (`:167`) e fontes GLSL; `pipeline(format,kind)` (`:260`) já indexa por (formato, tipo) e escolhe o fonte pelo tipo: acrescentar os ramos. `compose()` (`:358`) recebe, para o quad da TV, um passo anterior ao `vkCmdBeginRendering`: `record_easu(quad, target)` com barreiras (scan SHADER_READ -> fsrTarget COLOR_ATTACHMENT -> SHADER_READ). O `fsrTarget` segue o padrão de `resources.diagnosticTarget` (`:342`, `:603-612`): `Surface` criada com `create_surface_image(&t,true,VkExtent3D{w,h,1})`, recriada se `box` muda (`destroy_surface_image` já adia, `surfaces.cpp:671-677`), liberada em `reset_present_resources` (`:338`).

**Constantes / descritores:**
- Layout de descritores inalterado: 1 `COMBINED_IMAGE_SAMPLER` (`present.cpp:263-267`); EASU e RCAS usam o mesmo.
- Push constants: o range é `sizeof(PresentParams)=48` bytes (`present.h:66-72`, `static_assert` em `present.cpp:154`) e `pad[3]` está livre. EASU calcula `con0..con3` no shader a partir de `textureSize(image,0)` (entrada = tamanho do scan inteiro, viewport = imagem toda) e do tamanho de saída em `pad[0..1]`; RCAS usa `pad[2]` para a nitidez (`FsrRcasCon`). Sem mudar o layout compartilhado com `IndependentPresenter`.
- Constantes de EASU/RCAS dependem só de tamanhos: nada de UBO.

### B.3 Espaço de cor e formatos (ponto de cuidado)

FSR 1 trabalha em espaço perceptual (gama ~2,0-2,2): EASU assume entrada gama, RCAS também. Regras:
1. Amostrar o scan por uma **view UNORM** (o `Surface` é criado com `VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT`, `surfaces.cpp:~660` **[V para a flag; NV que a view UNORM de um scan sRGB já existe]**): se o scan guarda valores codificados (provável) basta usar a view atual; se for `R8G8B8A8_SRGB` (hardware decodifica), criar a view UNORM.
2. Se os valores do scan são lineares (`scanSrgb=true`, UNORM): converter para sRGB (ou `sqrt`) antes do EASU e voltar depois do RCAS. 
3. EASU escreve `fsrTarget` UNORM no mesmo "estado" de codificação do scan; `ComposeQuad.sourceLinear` do quad passa a descrever o intermediário, e o `conv` existente trata o resto.
4. HDR (`hdrOutput`): FSR roda em SDR no scan; o mapeamento HDR (`present.cpp:78-90`) fica no fragment final junto do RCAS.
5. FXAA: ver B.4.

### B.4 Posição do RCAS e do FXAA

| Ordem recomendada | Racional |
|---|---|
| (FXAA opcional, na resolução interna) -> EASU -> RCAS -> overlay | FSR 1 não faz anti-aliasing; a AMD recomenda entrada já suavizada. Em 540p o serrilhado é forte. |
| Fase 1 simplificada | FSR ligado desliga o FXAA (mutuamente exclusivos na UI). Menos um passe. |
| RCAS nunca antes do EASU, nunca depois do overlay | RCAS no ImGui destruiria texto. |

### B.5 Custo estimado no Adreno 740 **[NV]**

| Cenário | Pixels de saída | Passes | Estimativa | Observação |
|---|---|---|---|---|
| 720p -> 1080p (1559x720 -> 2340x1080, razão 1,5) | 2,53 M | EASU + RCAS | 0,5 a 1,0 ms | extrapolação: EASU ~12 gathers + ~150 ALU/pixel em fp32; RCAS ~5 fetch + ~50 ALU. Sem número publicado confiável de memória para Adreno 740. |
| 540p -> 1080p (1169x540 -> 2340x1080, razão 2,0) | 2,53 M | EASU + RCAS | idêntico em custo do filtro (depende da saída, não da entrada) | a economia vem de render 0,56x pixels no passe principal (7,6 ms a 1559x720) |
| Largura de banda | | | escrita 10 MB + leitura ~10 MB/frame (~1,2 GB/s a 60 fps) | desprezível; mas energia por passe extra de tela cheia existe |

A AMD publica tabelas de custo para GPUs desktop (RDNA) **[NV: não lembro os valores; não citar sem consultar o README do repositório FidelityFX-FSR]**. **Medir:** `WWHD_VK_GPU_TIMESTAMPS=1` (intervalo da submissão) e, melhor, abrir escopos de GPU para os passes de upscale: hoje `begin_gpu_scope` só cobre passes de render do jogo e `feedback` (`backend.cpp:806-856`, `GpuScopeMetadata.kind`); acrescentar `kind=2 "upscale"` em `compose` para aparecer em `WWHD_VK_GPU_PASS_TIMESTAMPS=1`. Comparar FSR on/off com o mesmo save/cena.

### B.6 Precisão (fp16) e variantes

- Adreno 7xx executa `mediump`/`float16` com taxa dobrada. Nenhuma feature `shaderFloat16`/`storage16` é ativada hoje (sem ocorrência em `backend.cpp`), e `vk::compile_glsl` (`shaders.cpp:791`) compila GLSL em tempo de execução com glslang.
- Plano: v1 em fp32 (`FsrEasuF`/`FsrRcasF`), com `mediump` nas variáveis internas (o driver pode usar half; sem exigir feature). v2 opcional: `FsrEasuH` (pacotes `AH2`) condicionada a `VkPhysicalDeviceShader16BitStorage/ShaderFloat16Int8` ligadas na criação do device; só se a medição justificar.
- RCAS e EASU em RGBA8 podem causar degraus em gradientes suaves (céu). Se visível, `fsrTarget` em `A2B10G10R10_UNORM` (suporte de attachment obrigatório) é a mitigação barata.

### B.7 Vendorização, licença, cache

| Item | Proposta |
|---|---|
| Arquivos | `runtime/third_party/fsr1/ffx_a.h`, `ffx_fsr1.h`, `LICENSE.txt` (MIT, texto da AMD), `README` com commit/versão de origem. Só as partes GLSL (`A_GLSL`, `FSR_EASU_F`, `FSR_RCAS_F`) são usadas; **[NV]** tamanho e se glslang do projeto compila `ffx_a.h` sem ajuste. |
| Embutir | os shaders de `present.cpp` são raw strings; concatenar as fontes de `ffx_*.h` via CMake (`file(READ)` + `configure_file` em um `fsr1_glsl.inc`), sem depender de `#include` em runtime (glslang precisa de includer). |
| Metal | AMD só distribui GLSL/HLSL. Duas rotas: portar EASU/RCAS para MSL (mecânico: `AF2`->`float2`, gather -> `tex.gather(s,p,component::x)`; escrever o núcleo como macros comuns), ou usar `MTLFXSpatialScaler` (macOS 13+; o SO desta máquina é Darwin 23.3 = macOS 14) **[NV: não testado; é caixa-preta, sem RCAS e sem comparabilidade com o Android]**. Como o macOS também executa o renderer Vulkan (MoltenVK, `WWHD_RENDERER_RUNTIME=vulkan` ou `--renderer=vulkan`, `renderer.cpp:182-196`), **o FSR do Vulkan é testável no Mac**; o Metal fica para depois. |
| Licença do projeto | `LICENSE` é MPL-2.0; MIT é compatível. Atualizar a tela de créditos/NOTICE se existir **[NV]**. |
| Pipelines | criar `kEasu/kRcas` ao ligar a opção ou ao criar o swapchain, não no primeiro quadro (o `pipeline()` registra tempo de criação em `R.pipelineCreateNs`, `present.cpp:318-322`): evita engasgo. O `R.pipelineCache` já persiste. |

### B.8 Tornar opcional

| Item | Proposta |
|---|---|
| Enum | `scale_filter`: 0 smooth, 1 sharp, 2 integer, **3 bicubic (Catmull-Rom), 4 FSR 1** (`gfx/display_modes.h:21` `enum Filter`; `settings.cpp:23`; `normalize(v)` em `settings.cpp:~30` hoje é módulo 3: ampliar). Ou setting separado `upscaler` e manter o filtro para o modo inteiro. Preferível: **setting `upscaler`** (0 = filtro atual, 1 = bicúbico, 2 = FSR 1), mais `fsrSharpness`. |
| Env (`env.txt` do Android, formato `NOME=valor`, `main.cpp:407-417`) | `WWHD_UPSCALER=bilinear\|bicubic\|fsr1`, `WWHD_FSR_SHARPNESS=0..2` (stops de RCAS, padrão ~0,25 **[NV: valor típico da amostra da AMD]**), `WWHD_FSR_MIN_RATIO=1.05`. Regra existente: a env vence e não é salva (`put(..., {env}, v)` em `overlay_sdl.cpp:176-196`). |
| Disponibilidade | `GraphicsFeature` ganha `Upscaler` (`settings.h:19`), marcada indisponível quando a swapchain não é "drawable" (mesmo fallback do FXAA, `present.cpp:565-575`) ou o scan não é filtrável; `render::kFeatureScaleFilter` (`renderer.h:78`) reaproveitável. |
| UI | `overlay/overlay.cpp:~918` (checkbox FXAA): radio "Upscaler: Padrão / Bicúbico / FSR 1" + slider de nitidez; `gfx/vulkan/graphics_menu.mm` e `gfx/menu.mm` (Metal) em paridade. |
| Persistência | `overlay_sdl.cpp:176-196` (`graphics_changed`) e `:217-226` (`load_saved_options`); AppKit: `menu.mm:104-130`. Chaves novas `upscaler`, `fsrSharpness`. |

---

## C. Sistema de resolução interna

### C.1 Modelo de dados

Hoje: um único `float` (fator sobre 1280x720 guest), `g_res_requested` -> `g_res_frame` (latch no limite de quadro, `surfaces.cpp:97-119`), todos os alvos usam `res_scale()` (`:171-183`).

Proposta: manter **o fator como a única grandeza interna** e resolver os modos para ele no latch:

```
struct ResolutionSetting { enum Mode { Scale, FixedHeight, FitScreen } mode; float value; };
  Scale       : s = value                                  (0,5 / 0,67 / 0,75 / 1 / 1,5 / 2 / 3)
  FixedHeight : s = value / 720                            (540 / 720 / 900 / 1080 / 1440)
  FitScreen   : s = (screen_h / value) / 720               (value = razão FSR: 1,3 / 1,5 / 1,7 / 2,0; "tela / N")
effective = clamp(round_to_even_height(s*720)/720, 0.5, 4)   // H par, múltiplo de 2 (ou 8 para blocos)
```

- `kx` não entra na altura: `FixedHeight 1080` com aspecto 19,5:9 gera 2340x1080 (alvo = guest * s * kx, `surfaces.cpp:605`; ver C.3). O "tamanho de saída fixo" é por **altura**, a largura sai do aspecto, igual às telas que variam.
- `FitScreen` precisa da altura do swapchain da TV (`R.tv.swapExtent.height`, `backend.cpp:1388`) já disponível na thread de render onde roda `latch_res_scale`; no Metal, `target.height` do drawable. Reavaliar só em mudança de tamanho/seleção (não por quadro): trocar de fator realoca **todos** os alvos via `rescale()` (`surfaces.cpp:1008-1030`, `resample` de cada superfície) e esvazia views (`forget_texture_views`): é um engasgo; histerese e nunca em auto contínuo.
- Valores novos exatos sugeridos (todos produzem altura par): 0,5 (360p), 0,67 (482 -> 480p; preferir 0,6667 -> 480), 0,75 (540p), 1 (720p), 1,25 (900p), 1,5 (1080p), 2 (1440p), 3 (2160p). Presets de altura: 540/720/900/1080/1440.

### C.2 Pontos que precisam mudar

| Item | Arquivo | Mudança |
|---|---|---|
| `parse_scale` clamp | Metal `metal_surfaces.mm:46-56` ainda 1..4 | alinhar com Vulkan (0,5..4); testar no Metal (itens C.4) |
| Cíclo `R` | `vulkan/settings.cpp:63` (0,75/1/1,5/2/3), `menu.mm:40` (1/1,5/2/3), `vulkan/graphics_menu.mm:10` (1/1,5/2/3), `overlay.cpp:828` (1/1,5/2/3, nomes com "1280x720" fixo) | uma única tabela compartilhada (ex.: `gfx/res_presets.h`) com fatores, rótulos "720p (1x)" e a resolução real calculada com `kx` ("1559x720") |
| Rótulos da UI | `overlay.cpp:829` | calcular `W = round(1280*kx*s) x H = round(720*s)`; hoje mostram tamanhos de 16:9 mesmo em 21:9 |
| Modo e persistência | `overlay_sdl.cpp:176-226` (chave `resScale`), `menu.mm:106,124` | novas chaves `resMode`, `resValue`; `resScale` continua lida (migração: `Scale`, valor antigo) |
| Env | `WWHD_RES_SCALE` (mantido, vence), novos `WWHD_RES_HEIGHT=1080`, `WWHD_RES_FIT=1.5` | `surfaces.cpp:90`, igual em `metal_surfaces.mm` |
| Crash/relatório | `crash_context.cpp:68`, `overlay.cpp:700,1929,2220` | imprimir também altura efetiva e modo |
| Auto por classe de dispositivo | ver C.5 | |

### C.3 Interação com a largura ampliada (kx)

- Fórmula atual: alvo = `ceil(w_guest * res * kx - 0,01)` x `ceil(h_guest * res * ky - 0,01)` para `screen_shaped` (`surfaces.cpp:605`, `target_aspect` `:155-164`). `kx = A/(16/9)` com A o aspecto da janela (SM3DW usa `WWHD_ASPECT_DEFAULT_WINDOW`: segue a janela, `aspect.cpp:~50`; no S23 A=2340/1080=2,1667 -> kx=1,2188 -> 1559x720 a 1x, **[V]** pelos documentos, 1560 pela conta direta, a diferença de 1 px vem do aspecto lido da janela).
- Dos tamanhos de reduções do jogo (640x360, 320x180...): `ceil(640*res*kx - 0,01)` não é metade exata de `ceil(1280*res*kx)`; é inócuo porque o jogo usa UV normalizada, mas cria **tamanhos ímpares/arredondados diferentes entre alvos "irmãos"**: a 0,75x, 1280 -> 1170 (`1169,25`) e 640 -> 585 (`584,6`); se o jogo copia/resolve entre esses (GX2CopySurface com rescale), o tamanho é recalculado por `sx/sy` (`surfaces.cpp:1337-1339`). Checar quando houver `Draw`/`Blit` de tamanhos que dão `sw!=tw`: o caminho blit tem filtro linear, mas o de **depth** usa `draw_depth_copy` (nearest) ou `clear_unscalable`.
- Para modos de altura fixa, `FixedHeight` e `FitScreen` devem escolher `s` sem olhar para `kx` (o `kx` só alarga). Alternativa pedida ("render at screen size / N"): `FitScreen` com N=1 = resolução nativa da tela (2340x1080 exatos; **a largura 1559*1,5=2338,5->2339**, não 2340: o aspecto do alvo usa o aspecto da janela, então 2339x1080 na prática; o último pixel é coberto pelo `fit` com `roundf`/clamp; **[NV]** conferir que não há faixa de 1 px: aspecto = largura/altura da janela deveria dar `kx` que fecha 2340 exatos se o arredondamento do alvo for `round`, não `ceil-0,01`).
- Resultado prático para a tela (`fit` em `display_modes.cpp:204`): razão de upscale `min(dw/W, dh/H)`; se `W` for ligeiramente menor que `dw` (1 px), aparece coluna preta de 1 px à direita. **Auditar** com `WWHD_DUMP_PRESENT` (D).

### C.4 Auditoria para tamanhos abaixo de 1x e acima de 1x

| # | Risco | Onde / por quê | Ação |
|---|---|---|---|
| 1 | **Todo alvo renderizável escala**, não só os da tela | `target_scale` retorna `res_scale()` para qualquer alvo sem mips/compressão (`surfaces.cpp:171-183`). Inclui GamePad 854x480 (a regra `screen_shaped` só exclui do **kx**, não da escala), alvos pequenos (refletores, 256x256, 64x64) e shadow maps. A 0,5x um 1024x1024 vira 512x512; a 3x vira 3072x3072 | Introduzir **classes de alvo**: `Screen` (res), `GamePad` (min(res,1) ou 1), `Shadow` (alvo depth quadrado/array: própria escala; `WWHD_SHADOW_SCALE` hoje só vale para `isDepth && slices>1`, `:176-181`; o sombreador do SM3DW é 1024x1024 depth-only e **[NV] se é array ou 2D simples**; se for 2D simples a variável não vale), `Other` (1x ou limitado). Registrar uma vez cada classe encontrada (`WWHD_LOG_RESCALE` existe só no Metal, `metal_surfaces.mm:193,412`; trazer para o Vulkan) para listar os alvos do SM3DW. |
| 2 | Memória a 3x e 4x | 4 attachments RGBA + depth em 4677x2160 (~40 MB cada attachment RGBA8, mais se float) | já há nota de memória em `surfaces.cpp:166-170`; manter `WWHD_SHADOW_SCALE=1` como padrão a >=2x |
| 3 | **LOD explícito em alvos com cadeia de mips** | `with_mip_chain` (`surfaces.cpp:287-355`) reconstrói a cadeia sobre o tamanho escalado; o nível N do jogo equivale ao guest N, mas a base é `res`x maior/menor: `SAMPLE_L`/`SAMPLE_LB` com LOD constante (compilado pelo decompilador) amostra o nível errado (a 0,5x o LOD 1 do jogo cai em 1/4 do guest) | somar `-log2(sx)` ao LOD explícito/bias em unidades `texScale`, ou documentar. **[NV]** se o SM3DW usa LOD explícito nos alvos de 640x360 com 4 níveis (relatório render-cpu item 7). Testar com `WWHD_NO_RT_MIPS=1` como controle. |
| 4 | **Taps de blur/gather em espaço guest** | `gfx/area_sample.h` corrige, no Wind Waker HD, taps do blur (shader hash `44B3AB00/44B39A00`, buffer 960x540) e o espaçamento de `gather` do mask de sombra; os hashes **não existem no SM3DW**, então nada disso protege o SM3DW. Efeito geral: offsets constantes em UV (1,36 texels guest) e `textureGather` (2x2 em texels do host) divergem da geometria guest: a `res>1` o blur cobre menos área; a `res<1` o gather cobre mais (normais reconstruídas inclinadas para o outro lado, issue #135 invertida) | Achar os shaders do SM3DW que usam `uf_texNScale`/gather/`textureSize` (instrumentar: logar hash dos pixel shaders com `offset_texScale>=0` ou com `textureGather`) antes de liberar <0,75x; só então estender `area_sample` com os hashes do SM3DW. **[NV]** se o jogo faz SSAO/mask por gather. |
| 5 | Padrões por pixel (ruído, dither, grade 4x4) | `gl_FragCoord` já volta ao espaço guest (`fragCoordScale`), mas um padrão de ruído 4x4 em coordenadas guest passa a cobrir pixels fracionários: a `res<1` aliasing | verificar visualmente (D) em cenas com AO/sombra suave; `foot` do blur compensa só para o presente final |
| 6 | `texelFetch`/`LD` | multiplica por `texScale` (OK, `LatteDecompilerEmitGLSL.cpp:2444`); `GET_TEXTURE_RESINFO` devolve `textureSize` **escalado** (`:2709-2715`), sem dividir por `texScale` | se o shader do jogo usa resinfo para 1/tamanho de texel é coerente em UV; se usa para lógica inteira (loops sobre texels) quebra. **[NV]** onde o SM3DW usa resinfo (grep em shaders carregados) |
| 7 | `GX2CopySurface`/resolve entre superfícies de escalas diferentes | `surfaces.cpp:1307-1400`: `sw/sh` e `tw/th` por `lround(cw*sx)` (`:1337-1339`); cor usa blit LINEAR (`:1364`), depth usa draw/nearest, stencil copia 1 bit por passe; sem `Blit` -> `clear_unscalable` (`:944`) | testar copia depth escalada em 0,75 e 0,5 (arredondamentos ímpares); um destino mais largo que 1 px quebra `uMax/vMax` |
| 8 | Leituras de volta para a memória guest | `write_back_linear_copy` (`surfaces.cpp:1273`) escreve cópias para superfícies lineares; o renderer "nunca escreve resultados de GPU na memória guest" salvo `OP_PEEK_Z` (`peekz.cpp`, **hook do WWHD (0252E388), não aplicável ao SM3DW**; `depth_peek::pixel` já escala `extent/guestExtent`, `gfx/depth_peek.h:13-15`) | confirmar no SM3DW se alguma efeito lê depth/cor da CPU (mosaicos de oclusão, flares): [NV] |
| 9 | Alvos < 32 px e razão | `screen_shaped` exige `width>=32` e razão 16:9 ±3% (`:141-147`); níveis 1/32 e menores ficam sem kx; 854x480 (e `+1`) excluído | a 0,5x o `ceil` mantém >=1 px; verificar o menor alvo `screen_shaped` (40x22 -> 20x12) |
| 10 | `ceil(...-0,01)` e `lo/hi` com tolerância | `draw.cpp:2486-2498` já cobre arredondamentos a 1,5x/3x; fatores como 0,67 geram frações (857,6) | usar fatores "redondos" (altura par) e re-testar a linha inferior de passes de tela cheia |
| 11 | 60 fps real / interpolação | SM3DW roda 60 fps nativo (sem interpolação, `overlay.cpp:~812`); `interp` só para o Zelda | nada a fazer; mas o `interp_fx`/`aspect` não podem assumir res 1 |
| 12 | Mods, HUD e screenshot | `draw_mod_overlay` desenha no scan (res interna); `record_screenshot` salva o scan sem upscaler (`present.cpp:738`, `screenshot.h`) | decidir: screenshot = interno (hoje) ou pós-FSR (`compose_offscreen`); documentar |
| 13 | Stereo 3D e 2ª tela | `stereo_screen_target` (`surfaces.cpp:148`); `IndependentPresenter` | FSR só para o TV primário mono; modos stereo e a 2ª tela mantêm o filtro simples |
| 14 | Troca de resolução em jogo | `rescale` por superfície (`surfaces.cpp:1008`) em 1 quadro | testar `WWHD_RES_SCALE_AT=300:0.75,600:1.5` (já existe, `surfaces.cpp:103-118`) |

### C.5 Seleção automática por classe de dispositivo

Hoje não há tabela de classes: só `R.properties.deviceName/vendorID` (`backend.cpp:3074-3075`, `:3336`) e padrões por plataforma (`kHiresDefault`, `settings.cpp:11-17`). Proposta mínima:

| Classe | Critério | Padrão sugerido |
|---|---|---|
| Android, tela <= 1080p, Adreno 7xx | `vendorID==0x5143` e `deviceName` contém "Adreno (TM) 7" | `Scale 1` + FSR ligado (razão 1,5); `Scale 0,75` + FSR 2,0 como "Economia" (sem ganho de fps no S23; ganho de calor, **[NV]**) |
| Android, tela 1440p (ex.: S23 Ultra) | `swapExtent.height >= 1300` | `FitScreen 1,5` (internal 960p) |
| Android, GPU antiga | resto | `Scale 0,75`, FSR ligado |
| Desktop | | `Scale 1`; FSR desligado |
Seleção só no primeiro início (não grava no arquivo de settings enquanto o jogador não mexer), via `WWHD_RES_AUTO=0|1`. Como o S23 é CPU-bound (`sm3dw-perf-study`), o benefício da resolução baixa é energia/temperatura (`sm3dw-thermal-study.md`), não fps; em outros telefones a GPU pode limitar, então decidir com medição (`WWHD_PROFILE=1`).

---

## D. Plano de teste

### D.1 No Mac, sem janela

Base (de `docs/sm3dw-study.md`, "Como testar no Mac sem abrir janela"):
```sh
WWHD_HIDDEN_WINDOWS=1 WWHD_NO_HOST_INPUT=1 WWHD_ASPECT=21:9 \
WWHD_PRESS="700-706:8000,1000-1006:8000,1400-1406:8000" \
WWHD_SCREENSHOT_DIR=/tmp/shots WWHD_TEST_SCREENSHOT=900,1300,1700 \
WWHD_RES_SCALE=0.75 build/cmake3dw/wwhd --game game3dw
```
- `WWHD_TEST_SCREENSHOT` salva o scan no tamanho **interno** (`screenshot.h:5-9`), sem upscaler nem overlay. Serve para comparar resolução interna e FXAA.
- Para ver o **resultado final** (FSR, letterbox, overlay): `WWHD_DUMP_FRAMES=900,1300 WWHD_DUMP_PRESENT=1 WWHD_SIM_SCREEN=2340x1080` gera `frame_<n>_present.png` com a composição (`backend.cpp:2755-2776` -> `compose_offscreen` -> o mesmo `compose()` que o swapchain), no tamanho simulado de tela (`display_modes.cpp:258-268`). **[NV]** que `WWHD_SIM_SCREEN` com a janela oculta vale para o host Vulkan SDL (o texto de `display_modes.cpp:10-12` diz "present dumps and touch mapping"); no AppKit o diagnóstico exige 1280x720 (`display.mm:904`).
- Combinar com `WWHD_RES_SCALE_AT=...` e `WWHD_ASPECT=16:9|21:9|16:10` (a regra `kx` muda o tamanho do alvo e a razão do FSR).
- Para testar o FSR no Mac, usar o renderer Vulkan (MoltenVK): `--renderer=vulkan`.

Matriz mínima (todas com o passeio de A: título -> arquivo -> personagem -> abertura -> 1-1):
| Eixo | Valores |
|---|---|
| Escala | 0,5 / 0,67 / 0,75 / 1 / 1,5 / 2 / 3 |
| Aspecto | 16:9, 21:9, 19.5:9, 4:3 (ky) |
| Upscaler | bilinear, bicúbico, FSR (sharpness 0 / 0,25 / 1) |
| FXAA | off / on |

### D.2 Métricas de imagem

Referência: o mesmo quadro (mesmo `WWHD_PRESS`/frame; estado determinístico por `WWHD_STATE_LOAD_AT` se disponível **[NV]**) renderizado em `WWHD_RES_SCALE=3` e reduzido (área) ao tamanho de saída = "verdade". Medir contra ela:
| Métrica | Uso |
|---|---|
| PSNR / SSIM (ex.: `skimage.metrics`, `ffmpeg -lavfi ssim`) | ranking bilinear < bicúbico < FSR em cada escala |
| FLIP / LPIPS (se houver) | diferenças perceptuais |
| Diferença por região (HUD, texto, bordas, céu degradê) | bandas com RGBA8 (B.6) e halos do RCAS |
| Diferença 1x interno vs 1x após o refactor | regressão: com `Scale 1` e upscaler padrão as imagens devem ser **idênticas bit a bit** |
| Linha/coluna de borda | detectar 1 px preto (C.3) e a última linha não desenhada (C.4 #10) |

### D.3 No telefone

- `env.txt` (um `NOME=valor` por linha, `main.cpp:407-417`): `WWHD_RES_SCALE=0.75`, `WWHD_UPSCALER=fsr1`, `WWHD_PROFILE=1`, `WWHD_VK_GPU_TIMESTAMPS=1`, `WWHD_VK_GPU_PASS_TIMESTAMPS=1`.
- Mesma cena e save do estudo de desempenho; 3 execuções por configuração; registrar fps, `render` ms, GPU ms (escopos de upscale, B.5), temperatura (`sm3dw-thermal-study.md`).
- Overlay: seletor de resolução e upscaler (`overlay.cpp`); capturar com o botão de screenshot do overlay e com `adb exec-out screencap` para ver o resultado **pós-FSR**.
- Critérios: sem queda de fps com FSR ligado (esperado: +0,5-1 ms de GPU escondido pela CPU); calor igual ou menor a 0,75x+FSR do que 1x sem FSR; sem artefatos nos shadow maps, bloom e AO nas escalas novas.

---

## E. Plano de implementação (ordem, esforço, risco)

| # | Etapa | Esforço | Risco | Arquivos |
|---|---|---|---|---|
| 1 | **Auditar e liberar <1x de verdade**: classes de alvo (`Screen/GamePad/Shadow/Other`), `WWHD_LOG_RESCALE` no Vulkan, clamp 0,5 no Metal, LOD/gather (C.4 #1,3,4), tabela única de presets, rótulos com `kx` | 2-3 dias + auditoria visual | Médio (shaders do SM3DW desconhecidos) | `gfx/vulkan/surfaces.cpp:90-183,604-616`, `gfx/metal_surfaces.mm:46-122`, `gfx/vulkan/settings.cpp:63`, `gfx/menu.mm:40`, `gfx/vulkan/graphics_menu.mm:10`, `overlay/overlay.cpp:826-835`, novo `gfx/res_presets.h` |
| 2 | **Bicúbico (Catmull-Rom) como `scale_filter`/`upscaler` 1** e razão <1 mantendo caixa 4 taps | 1 dia | Baixo | `gfx/vulkan/present.cpp:40-100,162-166`, `gfx/display.mm:518-570`, `gfx/display_modes.h:21`, `gfx/vulkan/settings.cpp`, `overlay_sdl.cpp`, `menu.mm`, `overlay.cpp` |
| 3 | **FSR 1 (EASU+RCAS) no Vulkan**: vendorizar, `Kind kEasu/kRcas`, `fsrTarget`, push em `pad[]`, barreiras, setting `upscaler=2`, `fsrSharpness`, fallback/availability, FXAA exclusivo | 4-6 dias | Médio (cor/gama, barreiras, `IndependentPresenter`, pipeline warm-up) | `runtime/third_party/fsr1/*`, CMake, `gfx/vulkan/present.cpp`, `present.h`, `settings.*`, `backend.cpp` (escopos), `overlay_sdl.cpp`, `overlay/overlay.cpp`, `renderer.h` |
| 4 | Medição no S23 (A/B) e decisão fp16 / compute | 1 dia | Baixo | `backend.cpp:806-856` (escopo `upscale`) |
| 5 | Modos `FixedHeight` e `FitScreen`; `resMode/resValue`; env `WWHD_RES_HEIGHT/FIT`; rótulos com resolução real | 2 dias | Médio (mudança de swapchain dispara `rescale` global) | `surfaces.cpp:97-119`, `backend.cpp:2792`, `metal_main.mm:492`, settings/UI |
| 6 | Seleção automática por classe (`WWHD_RES_AUTO`) | 1 dia | Baixo (depende da medição de 4) | `backend.cpp` (info de device), `overlay_sdl.cpp` |
| 7 | FXAA pré-EASU (passe na resolução interna) | 1-2 dias | Baixo | `present.cpp` |
| 8 | Paridade Metal: porte EASU/RCAS para MSL (ou MetalFX) | 3-4 dias | Médio | `gfx/display.mm`, `gfx/metal_surfaces.mm` |
| 9 | Ajustes do SM3DW para alta/baixa res: hashes em `area_sample.h`/`shader_identity.h`, LOD/texScale (C.4 #3,4) | variável | Alto (precisa de captura de shaders do jogo) | `gfx/area_sample.h`, `gfx/shader_identity.h`, `gfx/vulkan/shaders.cpp` |

Ordem recomendada: **1 -> 2 -> 3 -> 4 -> 5 -> 6**, depois 7-9 conforme os resultados. A etapa 1 é a única que pode revelar defeito visual no jogo; o FSR (3) é isolado no passe final e reversível por setting.

---

## F. O que não pude verificar

1. Formato e codificação reais do scan do SM3DW (`GX2SetTVBuffer`), logo se o scan é sRGB/linear/UNORM (A.1, B.3).
2. Valor exato do swapchain no S23 e flags de uso (`STORAGE`?), e se a view UNORM do scan existe sem custo.
3. Quais alvos, de quais tamanhos e classes o SM3DW cria (sombra: array ou 2D?), se há blur/gather/resinfo/LOD explícito sensíveis à escala, e se alguma CPU lê depth/cor de volta (C.4).
4. Custo real do EASU/RCAS no Adreno 740 e números publicados da AMD (citados como estimativa).
5. Texto exato da licença dos arquivos FSR 1 e compilação com a versão de glslang do projeto.
6. Funcionamento de `WWHD_SIM_SCREEN` com janela oculta no host Vulkan SDL e determinismo do passeio.
7. Disponibilidade e comportamento de `MTLFXSpatialScaler` neste Mac.
8. Nada foi executado: todas as linhas citadas foram lidas no código atual, mas nenhum comportamento foi reproduzido.
