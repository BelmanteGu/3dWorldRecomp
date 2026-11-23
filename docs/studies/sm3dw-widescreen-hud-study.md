# Super Mario 3D World: HUD, wipes e menus em widescreen (estudo)

Estudo somente leitura, branch `sm3dw`. Nada do jogo e copiado aqui: so enderecos, nomes de panes e numeros.
Tudo marcado "(V)" foi verificado no codigo recompilado / nos .bflyt; "(NV)" nao foi verificado (precisa de execucao).
Scripts e dumps de apoio (volateis): `/private/tmp/claude-501/scr/` (`bflyt.py`, `anim.py`, `ppd.py`, `xr.py`, `vc.py`; dumps de panes em `out/*.txt`, 110 arquivos).

## 1. Resumo

- O nw::lyt do 3D World tem **o mesmo layout de campos do Pane do WWHD** (V). Os hooks do WWHD portam quase 1:1, so mudam os enderecos.
- `0263530C` (ja hookado) **e o `DrawInfo::LoadProjectionMtx`** (V): so falta o resto do conjunto: `Pane::CalculateMtx 02636D34`, `Pane::Draw 02637768`, texto `0262D5CC`.
- Diferenca grande no **conteudo**, nao no motor: o HUD do 3D World nao e filho direto da raiz. Fica um nivel abaixo, dentro de um pane "wrapper" com o nome do layout (`RootPane > StageSceneLayout > CounterCoin`...) e **os nomes de panes se repetem entre layouts** (`Trans`, `Button`, `Base`, `Counter`). A tabela de regras precisa de uma chave de layout e de profundidade.
- O HUD entra por **animacao de translate X** (`StageSceneLayout_Appear.bflan`: -810 -> -434 em 15 frames). Hoje, sem recorte, o estado "escondido" aparece nas margens (visivel ja no S23 19.5:9, meia-largura 780 contra -810 +- 155 de conteudo) (V pelos dados, NV na tela).
- Ordem proposta: diagnostico (arvore + log), refatorar o motor de panes para receber os enderecos, depois HUD, fills/wipes, barras, marcadores, recorte.

## 2. Pergunta 1: nw::lyt neste jogo

### 2.1 Mapa de funcoes (endereco guest, RedCarpet.rpx USA v1)

Achados por: magics `FLYT/pan1/pic1/txt1/wnd1/bnd1/prt1` (varredura de `lis`+`addi`), vtables a partir dos construtores, referencias cruzadas de `bl`/`bctrl` (slot de vtable), e leitura do codigo.

| Papel | Endereco | Evidencia |
|---|---|---|
| Validacao do cabecalho `FLYT` | `0263BFFC` | unica funcao com a constante `FLYT` (chamada de 0263C0C4) |
| `Layout::Build` (le lyt1/txl1/mat1/pan1/.../grp1) | `0263C0C4` | unica com `lyt1/mat1/grp1/pas1/pae1` |
| Fabrica de panes por magic | `0263C86C` | `pan1`->ctor `026365E8` (0xA4 bytes), `bnd1`->`02640004` (0xA4), `pic1`->`02635858` (0xC0), `txt1`->`02637FC4` (0x104), `wnd1`->`02640198` (0xD8), `prt1`->`0263FE54` (0xB0) |
| Animacao `FLAN/pai1/pat1` | `026336F4` | unica com essas constantes |
| `DrawInfo` ctor (0xC8 bytes) | `02635058` | zera +0xB3..+0xB7, +0xAC; identidade em +0 (chamado de 0234F200, 024ECB04) |
| **`DrawInfo::LoadProjectionMtx`** | **`0263530C`** | `GX2SetVertexUniformReg(loc, 16, this)`; so um chamador (`0263F918`, setup de material) |
| `DrawInfo::LoadMtxModelView` | `02635348` | flag +0xB6, 12 regs de +0x70 |
| Contexto de calculo (0x18 bytes) | `026379C0` | montado em `Layout::Calculate` a partir do DrawInfo |
| `Layout::Calculate` | `0263BBE0` | raiz em Layout+0x0C, `bctrl` do slot 0x84 |
| `Layout::Draw` | `0263BC44` | grava Layout em DrawInfo+0xAC, estado +0xB3..+0xB7, `bctrl` slot 0x8C na raiz |
| **`Pane::CalculateMtx`** (slot vtable 0x84) | **`02636D34`** | le flag +0x44 (visivel 0x01, dirty 0x10), +0x47 origem, +0x1C/+0x34, escreve +0x48; args `(pane, ctx, parentDirty)` |
| **`Pane::Draw`** (slot 0x8C) | **`02637768`** | testa +0x44/+0x46, `bctrl` DrawSelf (slot 0x94) em `026377B4`, depois percorre filhos (slot 0x8C) |
| `Pane::DrawSelf` base (vazio) | `0263781C` | `blr` |
| `Pane::LoadMtx` (slot 0x9C) | `02637820` | copia +0x44..0x74 para DrawInfo+0x6C |
| `FindPaneByName` / `FindMaterialByName` | `02637860` / `026378F8` | slots 0xA4 / 0xAC |
| `Picture::DrawSelf` | `02635A60` | slot 0x94 de Picture |
| `Window::DrawSelf` / `Bounding::DrawSelf` | `0264435C` / `02640078` | idem |
| `TextBox::CalculateMtx` (override) | `026389E0` | chama `02636D34` direto (`026389FC`): hook na base cobre |
| `TextBox::DrawSelf` | `02638C14` | monta "content" na pilha (+4 = DrawInfo, +8 = view) |
| **Texto (nw::font)** | **`0262D5CC`** | multiplica DrawInfo+0 x view (psq), unico upload de 16 regs em `0262D9B4`; chamado de `02638C00` e `026390C0` |
| Parts (prt1) `DrawSelf` | `0263781C` | e o vazio da base: Parts e so contenedor |

Vtables (V): Pane `103A6484`, Picture `103A6330`, TextBox `103A6564`, Window `103A7CF4`, Bounding `103A7B5C`, Parts `103A7AAC`. Todas usam o mesmo `Pane::Draw` (slot 0x8C) e (menos TextBox) o mesmo `CalculateMtx` (slot 0x84). Servem para o runtime reconhecer o tipo do pane pelo ponteiro em +0x08 (util para regras de Fill: so Picture/Window).

### 2.2 Layout do Pane: igual ao WWHD (V no construtor `026365E8`)

| Campo | WWHD (aspect.cpp:276-282) | 3D World |
|---|---|---|
| +0x08 vtable | sim | sim (`stw r10,8(r31)`) |
| +0x0C parent | sim | sim (zerado em `026363EC`) |
| +0x10 contagem / +0x14 sentinela de filhos | sim | sim (lista circular em `02636640-0263666C`; leaf = `[+0x14]==+0x14`) |
| +0x1C translate xyz | sim | sim (`0263672C`) |
| +0x28 rotate xyz | sim | sim |
| +0x34 scale xy | sim | sim |
| +0x3C size wh | sim | sim |
| +0x44 flags (0x01 visivel, 0x02 herda alpha, 0x04 location adjust, 0x10 dirty) | sim | sim (`02636DC4`, `02637018: andi 0xEF`) |
| +0x45 alpha, +0x46 alpha global, +0x47 origem (bits 0-3 propria, 4-7 do pai) | sim | sim |
| +0x48 matriz global 3x4 | sim | sim (psq_st em `02636F98..`) |
| +0x7C userdata, +0x80 nome (24 bytes), +0x99 | +0x80 nome | sim (`026364A4`: copia 0x18 bytes para +0x80) |
| Tamanho do Pane | n/d | 0xA4 |

Diferencas para o hook: (a) o 2o arg de CalculateMtx e um contexto de 0x18 bytes (nao o DrawInfo); o hook do WWHD nao toca em r4, entao nao importa; (b) `r5` = `parentDirty` (bool), mesma semantica (`02636EE8..02636EFC`), logo `c->r[5]=1` funciona; (c) CalculateMtx **recalcula a partir de +0x1C/+0x34 sempre que o dirty 0x10 esta ligado** (`02637010..`), como no WWHD: mexer em translate/scale e forcar o bit 0x10 funciona; (d) ha um ramo com matriz de usuario em +0x78 (flags 0x20/0x40, `02636F00`, `02636F54`): (NV) pode ser o mecanismo que o jogo usa para ancorar layouts no mundo.

Origem dos filhos: coordenadas locais sao relativas ao **centro** do pai quando origem do pai = Center. No .bflyt, byte de origem: Center=0, Left/Top=1, Right/Bottom=2 (bits 0-1 X, 2-3 Y, 4-5 X do pai, 6-7 Y do pai; o runtime le `(+0x47>>4)&3` como origem do pai em `02637050`). Quase tudo no jogo e `CC/CC`.

### 2.3 Estrutura do jogo (V nos .bflyt)

- Todos os 109 arquivos tem tela 1280x720 (`lyt1`), nenhum com outra.
- `LayoutData/<Nome>.szs` = Yaz0 -> SARC com `layout.lyarc` (SARC interno com `blyt/*.bflyt`, `anim/*.bflan`, texturas) + `Init*.byml` (framework de LayoutActor, nao relevante aqui).
- `RootPane` (1280x720, em 0,0) sempre; abaixo, **um wrapper com o nome do layout** (ou, em menus, panes soltos na raiz: `GameOverMenu`, `PlayerEntry`, `WindowConfirm*`, `TitleLogo`). Typos preservados nos nomes: `CountrerPlayer`, `WipwCurtain`, `WipeCurrainRanking`.
- `prt1` (Parts) = sub-layout (ex.: `ParCounterCoin` -> `CounterCoinParts.szs`). (NV) Se a raiz do sub-layout vira filha do Parts em runtime: ver passo 0.

## 3. Pergunta 2: quais layouts sao o que

### 3.1 Classificacao (por nome e por conteudo dos .bflyt)

| Categoria | Layouts | Observacoes |
|---|---|---|
| **HUD de jogo (bordas)** | `StageSceneLayout` (+ partes `CounterPlayerParts`, `CounterCoinParts`, `CounterGreenStarParts`, `CounterStampParts`, `CounterTimerParts`, `CounterScoreParts`, `ButtonItemStockParts`), `CameraIcon`, `CameraLimit`, `CameraGyroDRC` (GamePad), `CounterSlot`, `CounterWarpCube`, `CounterMysteryBox`, `CounterLock`, `CounterGreenStarAppend`, `CounterRecordParts` | StageSceneLayout: esquerda `CountrerPlayer`, `CounterCoin`, `CounterGreenStar`, `CounterStamp`, `ItemStock`; direita `CounterTime`, `CounterScore` |
| **Overlays de mapa (bordas)** | `CourseSelectSceneLayout` (wrapper `All`) | `CountersBase` (-554,-216), `CounterGreenStarTotal` (-568,-38), `World` (-580,279), `ParGuideStart` (495,-170), `ParGuideSelect` (495,-224), `Miiverse` (524,265) |
| **Faixas de largura total (barras)** | `GuideWindowParts` (`PicWindow/Shadow/Edge`, 1280 x 104/16), `GuideMessage` (`PicWinT/PicWinB`, 1280), `PauseMenu`/`PauseMenuMap` (`FrameT/FrameB`: `PicFrameT` 1280x198, `PicFrameB` 1280x120), `ListStamp*`/`ListClearStar` (`PicListBGEdge`, `PicListStampEdge`, 1280x40), `MapMenu` (`PicMapEdge` 1280x10), `CameraLimit` (4 cantos 1152x128 / 592x128) | estender em X |
| **Fundos tela cheia (Fill)** | `PicBG` em `PauseMenu`, `PauseMenuMap`, `FileSelect`, `GameOverMenu`, `PlayerEntry`, `WindowCameraChange`, `WindowConfirmDouble/Report/Single`, `WindowControllerChange/Guide`, `WindowMiiSetUp`, `WindowMiiverseDetail`, `WindowProcessing`, `ListStampResult`, `WipeCurtainResult`; `PicBase` em `CounterPlayerInfo`, `HeadGameOver`, `WipeMiss`, `StaffRollNintendo`; `PicWindowBoot`; `PicPaperBase` (`ListStamp`); `PicMapBase` (`MapMenu`); `PicBG` 1280x780 (`ListClearStar`); `WinBG` 1352x840 / `WinBase` 1280x770 (`ListStampParts`) | todos 1280x720 (ou maiores), centro em 0,0 global. Atencao: `StaffRollNintendo/PicBase` (0,321) e `MapMenu/PicMapBase` (0,-250) so estao no centro no sistema global (pai desloca); o teste local `|x|<8` do WWHD falha |
| **Wipes / transicoes** | `WipeFadeBlack`, `WipeFadeWhite` (`PicWipeFade` 1280x720); cortinas `WipeCurtainContinue/Demo/Opening/Ranking/Result/Start` (`PicCurtain` 1300x720, 1300x960; `PicKinopioCurtain` 1308x866; sombras em +-664); iris/formas `WipeCircle` (`WinCircle` 547 x escala 3), `WipeCasino`/`WipeFairy`/`WipeKinoko`/`WipeMystery` (janelas 1600x1600), `WipeMiss` (`WinMiss` 1085x2170 escala 2x1, mais `PicBase` 1280x720), `FilterTelescope` (`PicTelescopeL/R` 512x512 escala 1.5x1.42) | cortinas e fades: cobrir; iris: manter redondo |
| **Marcadores no mundo (suposicao pelo nome)** | `TalkBalloon`, `GuideBalloon`, `PopScoreNumber`, `PopRedCoinNumber`, `PopGreenCoinNumber`, `MiiNameplate`, `BalloonMiiverse`/`New`/`Parts`, `GuideGoal`, `GuideFrameOut` (seta na borda para personagem fora da tela), `CourseSelectInfo*` (balao com `PicBeak` sobre o no do mapa), `TouchEffect` (GamePad) | (NV) nao se sabe qual pane o codigo move (raiz ou wrapper) |
| **Menus (ficam centrados)** | `PauseMenu`, `PauseMenuMap`, `MapMenu` (+ `MapMenuPlayerParts`), `FileSelect`, `PlayerEntry`/`Mini`/`Parts`, `GameOverMenu`, `TitleLogo`, `Window*` (13), `List*`, `StaffRoll*`, `Head*` (banners centrais), `StageStartEventTimer`, botoes `Button*Parts` | conteudo = recorte na regiao 16:9 (WWHD) |

Painel lateral de menu: `PauseMenu/FrameSetting` em (536,-30) e `ButtonBackParts/Button` em (-640,-360) (canto inferior esquerdo, dentro de `ParBack*`, partes de 1360x942) sao itens de borda dentro de menus.

### 3.2 Posicoes de repouso do HUD (V, `anim.py` sobre `StageSceneLayout_Appear/Wait.bflan`)

| Pane | x escondido (frame 0) | x repouso (frame 15) | y (estatico) |
|---|---|---|---|
| CountrerPlayer | -810 | -434 | 297 |
| CounterCoin | -866 | -490 | 229 |
| CounterGreenStar | -851 | -475 | 172 |
| CounterStamp | -944 | -568 | -226 |
| ItemStock | -924 | -548 | -144 |
| CounterTime | 884 | 508 | 305 |
| CounterScore | 884 | 508 | 257 |

(Animacao so em X, chaves hermite de 12 bytes; Y vem do .bflyt.) Consequencia: a regra por posicao do WWHD (`anchor_at`: |x|>=300 e |y|>=200, aspect_panes.h:24) classifica todos corretamente tanto escondidos como em repouso, e como o deslocamento e o mesmo nos dois estados, **ancorar tambem os escondidos mantem a mesma distancia da borda que no 16:9** (170-300 unidades fora), sem precisar de "parked_hud" para eles.

### 3.3 Formato proposto da tabela de regras (extensao de `aspect_panes.h`)

O formato do WWHD (`PaneRule{name, where, rule, child, min_y, anchor, path, side}`, aspect_panes.h:67-76) serve; faltam tres campos e tres regras:

```cpp
enum class Where { RootChild, Any, Wrapper };   // Wrapper: filho do wrapper (profundidade 2) ou da raiz (1)
struct PaneRule {
  std::string_view layout;   // NOVO: nome do wrapper (1o filho da raiz). vazio = qualquer layout
  std::string_view name;     // nome do pane
  Where where; Rule rule;
  std::string_view child = {}; float min_y = -1e9f;
  Anchor anchor = {}; Slide path = {}; Side side = Side::Top;
  bool center_test = true;   // NOVO: Fill testa centro local ~0 (false: aceitar qualquer, por layout)
};
enum class Rule { Hud, Anchored, Slide, Fill, Frame, Projected, Marker,
                  Bar,        // NOVO: sx *= cover(kx, 1280, w); ancora vertical pela posicao (faixa de largura total)
                  FillAny,    // NOVO: Fill que aceita pane com filhos (cortinas), via vtable Picture/Window
                  Uniform };  // NOVO: escala x e y por kx (iris redondo)
```

`rule_for` ganha `layout` (comparado com o nome do wrapper) e `where==Wrapper` (profundidade <= 2). Chave de layout em runtime: nome do 1o filho da raiz (`[root+0x14]`), com o fallback `""`. Isso resolve a colisao de nomes (`Trans`, `Button`, `Base`, `Counter`).

### 3.4 Primeiro conjunto de regras

| Layout (wrapper) | Pane | Regra | Motivo |
|---|---|---|---|
| StageSceneLayout | CountrerPlayer, CounterCoin, CounterGreenStar, CounterStamp, ItemStock, CounterTime, CounterScore | `Hud` (Wrapper) | por posicao; ver 3.2 |
| CourseSelectSceneLayout (`All`) | CountersBase, CounterGreenStarTotal, World, ParGuideStart, ParGuideSelect, Miiverse | `Hud` (Any) | contadores/botoes de borda (CountersBase fica dentro de `Counters`, que esta em 0,0) |
| CameraIcon, FilterTelescope | NormalCamera, GyroCamera (556,200) | `Hud` | canto superior direito |
| CameraLimit | PicCameraLimitT/B/L/R | `Hud` (visual a confirmar) | cantos |
| PauseMenu, PauseMenuMap | FrameSetting | `Hud` | painel lateral direito (536,-30) |
| PauseMenu, PauseMenuMap | FrameT, FrameB (wrappers) / PicFrameT, PicFrameB | `Bar` | faixa de 1280 |
| GuideWindowParts | PicWindow, PicWindowShadow, PicWindowEdge | `Bar` | faixa superior |
| GuideMessage | PicWinT, PicWinB | `Bar` | faixa inferior |
| ListStamp*, ListClearStar, MapMenu | PicListStampEdge, PicListBGEdge, PicMapEdge | `Bar` | faixas |
| (qualquer) | PicBG, PicBase, PicWindowBoot, PicWipeFade, PicPaperBase, PicMapBase, PicPaperBaseB | `Fill`, `center_test=false` | fundos e fades (tamanho >= 1270x710) |
| ListStampParts | WinBG, WinBase | `Fill` | |
| WipeCurtain* | PicCurtain, PicCurtain_00, PicKinopioCurtain | `FillAny` (x) | cortina cobre; filhos L/R seguem a borda |
| WipeCircle, WipeCasino, WipeFairy, WipeKinoko, WipeMystery, WipeMiss | WinCircle, WinCasino, WinFairy, WinKinoko, WinMystery, WinMiss | `Uniform` (primeira tentativa) ou `Fill` com `cover` do WWHD (distorce 5% a 21:9, 60% a 32:9) | iris deve continuar redondo |
| TalkBalloon, GuideBalloon, PopScoreNumber, PopRedCoinNumber, PopGreenCoinNumber, MiiNameplate, BalloonMiiverse | wrapper (`TalkBalloon`, `GuideBalloon`, `Trans`, `MiiNameplate`...) | `Marker` + `Projected` | posicao projetada do mundo, escalar por kx (NV: qual pane o jogo move) |
| restante (PlayerEntry, FileSelect, Window*, Title, StaffRoll, Head*, ...) | | sem regra = conteudo | centrado e recortado |

Casos que precisam de regra propria depois de ver na tela: `FilterTelescope` (duas metades de 768 de largura cada: nao cobrem 21:9; precisa de extensao, nao de esticar o circulo), `GuideFrameOut` (a posicao na borda e calculada pelo codigo do jogo contra 1280x720; so um hook no calculo move a seta para a nova borda), `PlayerEntry/ParPlayer3` (384): **nao** aplicar `Hud` por posicao em menus (afastaria so os paineis das pontas).

## 4. Pergunta 3: plano de implementacao

### 4.0 Estado atual (V)

`hooks_sm3dw.txt` so liga `023D7724` (projecao sead 3D) e `0263530C` (projecao do layout, tag `OP_SET_PROJ_REGS`, gx2_core.cpp:497-509). Nao ha hook de Pane, de texto nem de recorte: `g_content_clip` fica falso (aspect.cpp:558-560; consumo em metal_draw.mm:1755 e vulkan/draw.cpp:2500), logo nada e recortado e nada e ancorado. Texto: **o texto nao passa pelo tag** (so `0263530C`), entao em widescreen o texto fica esticado em relacao as imagens (NV na tela, V no codigo: `0262D5CC` faz o proprio upload de 16 regs e nao e hookado).

### 4.1 Ordem de trabalho

| # | Passo | Onde | Verificacao |
|---|---|---|---|
| 0 | **Diagnostico sem mudar comportamento**: hookar `02637768` so para tracing (`WWHD_ASPECT_TRACE_FRAME`, ja existe aspect.cpp:470-482) e logar quantas vezes `hook_023D7724` ve um objeto com fovy ~40 / near do layout (ver risco R1) | `hooks_sm3dw.txt`, `aspect_sm3dw.cpp` | arvore de panes do HUD no frame de uma fase: confirmar wrapper, profundidade, translates e flags; ver se a raiz de um Parts e filha do Parts; ver quem translada raiz/wrapper em Pop*, TalkBalloon, CourseSelectInfo |
| 1 | **Texto**: hook `0262D5CC` com `aspect::tv_projection_call` (igual ao `028F8250` do WWHD, aspect.cpp:526-528) | `hooks_sm3dw.txt`, `aspect_sm3dw.cpp` | texto do HUD com a mesma proporcao das imagens a 21:9 |
| 2 | **Refatorar o motor de panes** (aspect.cpp:270-556) para receber `orig` por ponteiro: `lyt_calculate(Cpu*, void(*orig)(Cpu*))`, `lyt_draw(...)`, `lyt_clip_begin/end(Cpu*)`; os hooks do WWHD (`hook_028766CC`, `hook_02877100`, `site_0287714C/02877150`) viram wrappers de 1 linha. `button_glyphs::calculate_pane` fica fora do ramo sm3dw. A tabela vira parametro (`kRulesWwhd` / `kRulesSm3dw`, novo `runtime/src/sm3dw/aspect_panes_sm3dw.h`) | `runtime/src/aspect.cpp`, `aspect_panes.h`, `aspect.h` | build do WWHD identico (mesma saida de frame a 16:10 e 21:9); build sm3dw compila |
| 3 | Hooks de Pane do 3D World: `02636D34` (CalculateMtx), `02637768` (Draw), e sites `@026377B4` / `@026377B8` (antes e depois do `bctrl` do DrawSelf; r3 = pane ate `026377B0`) | `tools/recomp/hooks_sm3dw.txt` + `aspect_sm3dw.cpp` | com tabela vazia: nenhuma mudanca de imagem alem do recorte (menus/HUD recortados em 16:9 centrado) |
| 4 | Regras `Hud` do HUD e do overlay de mapa (3.4); constantes de offset iguais (`kPaneParent` etc., aspect.cpp:297-298) | `aspect_panes_sm3dw.h` | 1-1 a 21:9: contadores nas bordas, sem fatia do HUD escondido na margem |
| 5 | `Fill`/`FillAny`/`Uniform` e `Bar` | idem; `rule_for` com `layout` e `center_test` | `WipeFade*`, cortinas, iris, pausa e file select sem faixa preta |
| 6 | Marcadores `Marker`/`Projected` (apos passo 0) | idem | Pop*Number e TalkBalloon sobre o ator a 21:9 |
| 7 | `parked_hud`/recorte fino: so o que sobrar visivel (ex.: `WipeCurtainResult/Curtain` parqueada em x=+1350, largura 1300, borda em 700 < 853 a 21:9) | `aspect_panes.h:167-169` | idem |
| 8 | Regressao 16:9 e S23 (19.5:9), 32:9 | | frames identicos a 16:9 (`WWHD_ASPECT=16:9` com os hooks ligados) |

Observacoes de implementacao: hooks de funcao usam o par `hook_XXXXXXXX` / `f_XXXXXXXX_orig` (padrao de `aspect_sm3dw.cpp`); `tools/recomp/sm3dw_stubs.py` ja stuba os simbolos do WWHD, entao os `hook_028766CC` etc. permanecem linkados sem uso. Os enderecos do WWHD (`028xxxxx`) nao colidem com texto do 3D World (`02000020-026FEF14`), mas `site_024FFA98` e similares do WWHD caem dentro do texto do 3D World: **nunca** ligar `hooks_aspect.txt` no sm3dw (hoje desligado por `builds.hook_files`, tools/recomp/builds.py:225).

### 4.2 Como verificar com o dump de frames

Variaveis (confirmadas no codigo): `WWHD_TEST_SCREENSHOT=f1,f2,...` (screenshot.cpp:55-66, usa a contagem de frames do TV), `WWHD_SCREENSHOT_DIR=<dir>` (screenshot.cpp:152), `WWHD_ASPECT=21:9|16:10|32:9|<w:h>` (aspect.cpp:48-65; no sm3dw o padrao e `window`), `WWHD_ASPECT_AT=frame:aspecto` (troca em tempo de execucao, aspect.cpp:186), `WWHD_ASPECT_LOG=1` (log de panes movidos/esticados, aspect.cpp:410, 445), `WWHD_ASPECT_TRACE_FRAME=N` (arvore de panes, aspect.cpp:473), `WWHD_PRESS=ini-fim:mascara_hex` (VPAD, input_sdl.cpp:753), `WWHD_KEYS=ini-fim:Tecla` (input_sdl.cpp:769), `WWHD_STATE_SAVE_AT/LOAD_AT=frame:slot` (savestate.cpp:1121).

Roteiro (macOS):

1. Sem save state de sm3dw existente (`~/Library/Application Support/sm3dw` so tem ModManager e shader-prebuild): chegar a 1-1 com `WWHD_PRESS` (A = `8000`) e salvar um estado (`WWHD_STATE_SAVE_AT`); depois usar `WWHD_STATE_LOAD_AT` para pular direto ao jogo. (NV: os numeros de frame; medir uma vez.)
2. Para cada tela alvo (HUD em jogo, pausa, file select, wipe), duas execucoes com a mesma lista de frames: `WWHD_ASPECT=16:9` (baseline) e `WWHD_ASPECT=21:9`; `WWHD_ASPECT_LOG=1` e `WWHD_ASPECT_TRACE_FRAME=<frame>` na segunda.
3. Criterios: (a) 16:9 com hooks ligados == 16:9 antes (diff de pixels = 0); (b) 21:9: contadores colados nas bordas com a mesma margem do 16:9, sem fatia do HUD durante `Appear`; fundos/wipes cobrem a largura toda; texto com a proporcao das imagens; menus centrados e sem lixo fora da regiao 16:9; (c) um frame no meio de `StageSceneLayout_Appear` (frames 1-14 da animacao) e um de `End`.
4. Wipes: pegar frames de transicao (morte, inicio de fase, resultado) com `WWHD_TEST_SCREENSHOT` em intervalo curto (ex.: 10 frames) porque os wipes duram poucos frames.
5. S23: repetir com `WWHD_ASPECT=2340:1080` (custom) e no APK.

## 5. Pergunta 4: riscos

| # | Risco | Impacto / mitigacao |
|---|---|---|
| R1 | `hook_023D7724` troca o aspecto 16:9 de **qualquer** `sead::PerspectiveProjection` (aspect_sm3dw.cpp:15-24). Se a camada de layout usa uma (no WWHD as camadas de layout usam uma projecao em perspectiva fovy 40, decomp-notes.md:~826), o layout seria estreitado duas vezes (1/kx^2: uma pelo hook 3D e outra pelo tag de `0263530C`). (NV) | Passo 0: logar fovy/near/far por chamada; se houver uma projecao de layout, filtra-la (ex.: por fovy/near, ou so a camera principal). Sem isso toda regra de HUD sai errada. Os produtores de DrawInfo+0 (chamadores de `02635058`: `0234F200`, `024ECB04`) nao foram rastreados ate a projecao |
| R2 | Nomes duplicados entre layouts e profundidade 2 | tratado pela chave de layout e `Where::Wrapper`; cuidado com wrappers de nome generico (`All`, `Window`, `Guide`): validar a unicidade com o dump |
| R3 | Sub-layouts (Parts): a raiz do sub-layout pode ser raiz de si mesma (parent==0) e disparar o ramo "root" do hook (que decide o layout inteiro e emite `OP_LAYOUT_ROOT`) | (NV) passo 0: confirmar pelo trace; se Parts tem raiz propria, o criterio de raiz precisa de "parent==0 e sem Parts acima" |
| R4 | Marcadores/projetados: nao se sabe se o jogo posiciona raiz, wrapper ou usa a matriz de usuario +0x78 (flags 0x20/0x40, `02636F00`), nem se converte NDC para layout assumindo 1280 de largura | `Projected` so apos o log do passo 0; senao os numeros de pontuacao saem deslocados em widescreen |
| R5 | `StageSceneLayout` no GamePad: TV e DRC compartilham DrawInfos e threads de gravacao (aspect.cpp:280-282) | manter `OP_LAYOUT_ROOT` (gx2_core.cpp:513-517) e `root_on_tv`; o 3D World usa o GamePad como unico controle (docs/sm3dw-input-study.md:17) e exibe `PlayerEntry/ParPlayerDRC` |
| R6 | Animacoes que movem panes de dentro da regiao 16:9 para fora (cortinas, `Curtain` em +1350) e wipes cujo estado final "cobre" so o 16:9 | `Fill/Uniform` e `parked_hud`; verificar frame a frame |
| R7 | `Fill` do WWHD exige folha e centro local ~0 (aspect_panes.h:141): falha com cortinas (tem filhos) e com `StaffRollNintendo/PicBase`, `MapMenu/PicMapBase` | `FillAny` e `center_test=false` por layout, ou checar tipo por vtable |
| R8 | Elementos 2D fora do nw::lyt (letterbox de cutscene, filtros de pos-processo, fade em shader, Mii/preview) nao sao pegos por nenhum hook de pane | (NV) so se descobre em execucao; checar demos e a tela de resultado |
| R9 | Tabela por nome e fragil entre versoes do jogo (hash/atualizacoes): o estudo cobre USA v1 (`813d9063...`) | manter rastreavel; endereco so no `hooks_sm3dw.txt` |
| R10 | Regra por posicao (`Hud`) em menus afasta so alguns elementos (ex.: `ParPlayer3` em +384) | aplicar `Hud` apenas via regra explicita por layout; nunca como padrao |
| R11 | Refatoracao do motor no aspect.cpp toca o WWHD (merge com o upstream) | embrulhar sem mudar a logica; testar o build WWHD no passo 2 |
| R12 | Recorte de conteudo (`OP_LAYOUT_CONTENT`) usa a regiao 16:9 centrada (`panes::clip`, aspect_panes.h:172-181) | conteudo que o jogo ja desenha alem das bordas 16:9 (sombras de cortina em +-664) some; avaliar caso a caso |

## 6. O que nao foi verificado

- Nada foi executado (sem build, sem jogo, sem adb): todas as conclusoes sobre aparencia sao inferidas do codigo e dos .bflyt/.bflan.
- Quem preenche DrawInfo+0 (projecao) e se alguma camada de layout passa por `023D7724` (R1).
- Se a raiz do sub-layout de um Parts vira filha do Parts; qual pane (raiz/wrapper) o jogo translada nos marcadores; se Window/Parts/Bounding sobrescrevem o `Draw` (os vtables mostram que nao: slot 0x8C = `02637768` em todos os seis).
- Semantica exata das flags 0x20/0x40 do Pane (ramo da matriz +0x78 em `02636F00`).
- Se os numeros de frame de `WWHD_TEST_SCREENSHOT` correspondem aos swaps do TV (leitura de screenshot.cpp indica que sim; nao confirmado) e como chegar a uma fase de forma roteirizada (nao ha save state de sm3dw).
- As chaves de animacao foram lidas so de `StageSceneLayout`; as dos outros layouts (inclusive wipes) nao foram extraidas.
- A classificacao "marcador no mundo" e por nome de pane/layout.
