# sm3dw: identidade, recursos exclusivos do Zelda e higiene legal (estudo)

Estudo somente leitura da branch `sm3dw` (remote `origin` = BelmanteGu/Mario3DWorldRecomp, `upstream` = ZeldaWWHDRecomp/ZeldaWWHDRecomp).
Diferença atual contra upstream: 35 arquivos, +882/-31 (`git diff --stat upstream/main...HEAD`). Meta: manter esse número baixo.
Convenção: `arq:linha` relativo à raiz; "[não verificado]" = não confirmado por leitura/execução.

## 0. Como o build sm3dw é escolhido hoje

- CMake: `WWHD_GAME_RPX` (`CMakeLists.txt:74-75`); se for `RedCarpet.rpx` define `WWHD_USER_DIR_CAPS/LOWER`, `WWHD_PERF_OVERLAY_DEFAULT`, `WWHD_ASPECT_DEFAULT_WINDOW`, `WWHD_NATIVE_60` (`CMakeLists.txt:76-78`) e compila `runtime/src/sm3dw/aspect_sm3dw.cpp` (`CMakeLists.txt:256-258`). Default em `runtime/src/runtime.h:137-139`.
- Recompilador: `RECOMP_GAME=sm3dw` troca title id/sha256 e só lê `hooks_sm3dw*.txt` (`tools/recomp/builds.py:26-28,137-150,225-229`); `tools/recomp/hooks_sm3dw.txt` tem 2 hooks (`023D7724`, `0263530C`).
- Consequência importante: no sm3dw **nenhum hook Zelda existe no código gerado**, então `hook_XXXXXXXX` do runtime nunca é chamado. O custo dos módulos Zelda é binário/tempo de link/risco, não CPU por frame (ver seção 3).
- Fluxo sm3dw não usa setup/instalador/release (`android/build_sm3dw_apk.sh`, `docs/sm3dw-study.md:89`): tudo o que é `tools/installer`/`tools/release` só precisa ser corrigido se um dia houver release.

## 1. Inventário

Prioridade: **A** = errado/perigoso para o jogo (escreve/lê memória Zelda, rede, dados), **B** = texto visível ao usuário, **C** = interno/dev (log, env, nomes de símbolo; baixa prioridade, não mexer por causa de merge).

### 1.1 Identidade: textos, janelas, arquivos

| Pri | arquivo:linha | Hoje | Por que está errado | Correção proposta |
|---|---|---|---|---|
| B | `runtime/src/main.cpp:465` | `LOG("[boot] Wind Waker HD %s (%s)...` | Banner do log (também nos crash logs) diz Zelda | `"[boot] " GAME_NAME " %s..."` |
| B | `runtime/src/report_header.cpp:181` | `"Wind Waker HD " + in.version...` | Cabeçalho de relatório de desempenho | `GAME_NAME " " + ...` |
| B | `runtime/src/gfx/vulkan/backend.cpp:3633` | `SDL_CreateWindow("Wind Waker HD — Vulkan"...` | Título da janela | `GAME_NAME " — Vulkan"` |
| B | `runtime/src/gfx/vulkan/backend.cpp:4086` | `"The Legend of Zelda: The Wind Waker HD (Vulkan) — %.0f fps..."` | Título dinâmico (fps) | `GAME_TITLE_FULL " (Vulkan) — ..."` |
| C | `runtime/src/gfx/vulkan/backend.cpp:3231` | `ai.pApplicationName = "WWHD Vulkan"` | Nome da app no driver/RenderDoc | `GAME_SHORT_UP " Vulkan"` |
| B | `runtime/src/gfx/display.mm:426` | `@"The Legend of Zelda: The Wind Waker HD (recompiled)"` | Título janela macOS | `@GAME_TITLE_FULL " (recompiled)"` |
| B | `runtime/src/gfx/menu.mm:61` | idem em `kTitle` | Título | idem |
| B | `runtime/src/gfx/menu.mm:419` | `@"Quit Wind Waker HD"` | Menu do app | `@"Quit " GAME_NAME` |
| B | `runtime/src/gfx/quit_prompt.mm:91` | `@"Quit Wind Waker HD?"` | Diálogo | idem (cabeçalhos `quit_prompt.mm:3`, `quit_prompt.h:2` são comentários) |
| B | `runtime/src/gfx/renderer.cpp:259,270` | `SDL_ShowSimpleMessageBox(... "Wind Waker HD" ...)` | Título de caixas de erro | `GAME_NAME` |
| B | `runtime/src/overlay/overlay.cpp:1982` | `ImGui::Begin("Wind Waker HD  -  Settings"...` | Título do overlay (**também é a chave do ImGui; mudar reseta posição salva em `imgui.ini`**, aceitável) | `GAME_NAME "  -  Settings"` |
| B | `runtime/src/overlay/overlay.cpp:1870` | `"The Legend of Zelda: The Wind Waker HD - native port (...)"` | Aba About | `GAME_TITLE_FULL " - native port ..."` |
| B | `runtime/src/screenshot.cpp:128` | `"WindWakerHD_%Y-%m-%d_..."` | Nome dos PNG | `GAME_FILE_PREFIX "_%Y-..."` |
| B | `runtime/src/portable_state.cpp:209-210` | `"# Wind Waker HD portable save state..."`, "together with your cking.sav" | Cabeçalho do arquivo de estado portátil | `GAME_NAME`, `GAME_SAVE_FILE` |
| B | `runtime/src/gfx/vulkan/overlay_sdl.cpp:65` | `"# Wind Waker HD settings (settings overlay, F1)"` | Cabeçalho do settings.ini | `GAME_NAME` |
| B | `runtime/src/main.cpp:343-348` | `captures/wwhd.log`, `wwhd-previous.log` | Nome do log (o overlay documenta em `overlay.cpp:998-999`) | `GAME_SHORT ".log"` ou manter (C) e só ajustar texto |
| A | `runtime/src/gfx/display.mm:112` | `.../Application Support/wwhd/display.plist` | Pasta de dados **não** usa o macro (`host.h:230` usa `WWHD_USER_DIR_CAPS`): sm3dw divide `display.plist` com o WWHD | `WWHD_USER_DIR_LOWER` como já em `savestate.cpp:212` |
| A | `runtime/src/gfx/shader_prebuild.h:414` | `Library/Caches/wwhd/shaders.bin` | Runtime grava em `Library/Caches/sm3dw` (`gfx/metal_draw.mm:1350`) mas o prebuild/migração olha `wwhd` | `WWHD_USER_DIR_LOWER` |
| A | `runtime/src/gfx/menu.mm:63-66` | domínio de `NSUserDefaults` `wwhd` | Preferências compartilhadas com o WWHD no mesmo usuário | domínio por `GAME_SHORT` [não verificado onde o domínio é montado] |
| B | `runtime/src/mods/packages.cpp:671-672`; `runtime/src/overlay/mods_tab.h:823,830,835` | "GameCube Wind Waker disc..." | Texto da aba Mods | some junto com a aba (1.2) |
| C | `runtime/src/platform/https_win.cpp:32` | User-Agent `"WWHD Mod Catalogue"` | Só rede de catálogo | some junto com a rede |
| C | `runtime/src/savestate.cpp:458-459` | nomes de fases (Great Sea, Link's House...) | Só mapeia stage Zelda para texto de estado | inerte; guardar atrás de `GAME_HAS_ZELDA_STATE` se desejado |

### 1.2 Recursos exclusivos do Zelda ativos no sm3dw

| Pri | arquivo:linha | Hoje | Por que está errado | Correção proposta |
|---|---|---|---|---|
| A | `runtime/src/mods/cheats.cpp:44,47,69-92`; `runtime/src/overlay/overlay.cpp:1262-1286` (cheats), `runtime/src/overlay/mods_tab.h:1517` | Botões "Give all items", "20 hearts, 5000 rupees", "All Triforce shards"; escrevem em memória guest a partir do ponteiro lido em `GD(0x101F84DC)` | No 3D World `0x101F84DC` é outro dado: o clique **escreve em memória arbitrária do jogo** (corrupção/crash). Ativo na aba Mods | Esconder aba Mods (ver abaixo) e `request_cheat` vira no-op sob `!GAME_HAS_ZELDA_GUEST` |
| A | `runtime/src/overlay/overlay.cpp:180,1315,2010`; `runtime/src/overlay/extensions.h:55` | Aba "Mods" sempre presente (Saves, Graphics, Display, Audio, **Mods**, Controls, About) | Ver todas as linhas desta tabela sobre Mods. **Já existe `overlay::ext::hide_tab("mods")`** (`extensions.h:1,55`; registro só no início, `extensions.h:5-9`): zero edição em `overlay.cpp` | No `aspect_sm3dw.cpp` (ou novo `sm3dw/platform_sm3dw.cpp`) um registrador estático chama `ext::hide_tab("mods")` |
| A | `runtime/src/overlay/mods_tab.h:64`; `runtime/src/mods/required.cpp:45`; `runtime/src/overlay/catalogue_loader.h:50`; `runtime/src/overlay/mods_model.h:425` | `https://raw.githubusercontent.com/ZeldaWWHDRecomp/ZeldaWWHDMods/.../index.json`, busca **automática** (`automatic = true`, a cada 1 h) ao abrir a aba | Rede para o catálogo de mods Zelda em um jogo da Nintendo diferente; mods Zelda nunca servem ao 3D World | Aba oculta resolve o disparo; defensivo: `kDefaultCatalogue` e `catalogue_url()` vazios em sm3dw e `catalogue_loader` não busca com URL vazia |
| A | `runtime/src/main.cpp:515-520,556-580`; `runtime/src/mods/traversal_settings.cpp:35`; `runtime/src/mods/manager.cpp:50` | `saves::startup`, `manager::load_saved`, `packages::initialize`, `--install-story-notebook`, `guestmods::init()`, "story system" ligado por default (`WWHD_STORY_SYSTEM`) | Inicialização (disco, threads) de recursos sem hook; `story_system()` default true | Default `false` sob `!GAME_HAS_ZELDA_GUEST` (1 linha em `traversal_settings.cpp:35`) |
| B | `runtime/src/hle/fs.cpp:133-134,155,167-169,239-242`; `runtime/src/mods/story_store.cpp:96-99` | Hook de FS de "story block" | **Inerte**: só casa `/vol/save/cking.sav` | Nada a fazer; só confirmar (o save do 3D World não tem esse nome) |
| B | `runtime/src/overlay/overlay.cpp:1241-1254` | Opções "ladder / sidle / Normal sail / Swift Sail" | Sem efeito (hooks `hooks_traversal.txt` não existem no sm3dw) | Cobertas pela aba Mods oculta |
| A | `runtime/src/overlay/overlay.cpp:773-817` | Graphics: "30 fps (original)", "60/120/240 fps interpolation", "True 60 (experimental)", "Keep game speed" | 3D World já roda a 60 (`WWHD_NATIVE_60`, `perf_hint.cpp:77-85,213`). Escolher uma opção liga `interp`/`true60` (`interp.cpp:103-110`), altera ritmo do swap (`gx2_core.cpp:606`, `padscore.cpp:78-80`) sem hooks que segurem a lógica: jogo errado/lento; as stubs de `true60` abortam com `ppc_unimplemented` (`tools/recomp/sm3dw_stubs.py:5-9`) se algum caminho chamar | Ocultar essa seção (`#if !GAME_NATIVE_60`) e forçar `interp::set_mode(0)` no boot; `hostui` salvo de outro build não pode reativar |
| B | `runtime/src/overlay/overlay.cpp:833`; `runtime/src/aspect.cpp:74-90` | Slider de FOV "make Link..." e `gameplay_fov_scale()` lê `play=GD(0x1046F0B0)`, `stage`, `link` | Texto Zelda; função só leitura de memória Zelda (não chamada: sem hook) | `#if GAME_HAS_ZELDA_GUEST` na seção do slider |
| B | `runtime/src/overlay/overlay.cpp:526,1455,1456,1484,1726,1761`; `runtime/src/gfx/controls_ui.mm:1073,1098` | Ajuda de Controls: "bow, hookshot, telescope, Picto Box", "Wind Waker baton", "where Link stands" | Texto de controles do Zelda; estados de salvamento dizem "Link" | Strings por macro `GAME_HELP_*` (ou ocultar linhas sob `GAME_HAS_ZELDA_GUEST`) |
| B | `runtime/src/camera_invert.h:55`; `runtime/src/overlay/overlay.cpp:1455-1484` (câmera/gyro) | Tabela de funções do Zelda para inverter câmera/gyro | Sem hooks (`hooks_gyro.txt`, `hooks_climb.txt`...); opções aparecem e não fazem nada | Ocultar itens sob a macro [não verificado quais itens dependem só de hooks] |
| B | `runtime/src/overlay/overlay.cpp:1567` + `runtime/src/button_glyphs.cpp:20-28` + `tools/recomp/hooks_glyphs.txt` | "Face buttons" (cores Xbox / formas PlayStation) via hooks em `CharWriter::Print`, `TextBox`, `lyt::Picture::DrawSelf` | Hooks são de endereços Zelda; sem eles a opção não faz nada. Glifos do HUD do 3D World precisariam de hooks próprios | Ocultar a opção; decidir depois se vale portar para o 3D World |
| A | `runtime/src/gfx/gamepad_ui_state.cpp:21-33`; `runtime/src/gfx/auto_pip.h:19-30` (54 descritores `GD(0x1049EBD4...)`); `runtime/src/gx2/gx2_core.cpp:924`; `runtime/src/gfx/display_modes.h:15-16` | Modo GamePad "auto" lê `GD(0x101F8344)`, `GD(0x101F5088)`, `0x101F8348` e objetos do menu Zelda | No 3D World isso é leitura de memória sem sentido: PiP abre/fecha aleatoriamente. `display_read_gamepad_ui()` só roda se o modo for auto (`gamepad_ui_state.cpp:16`) | Remover "auto" das opções (`kDrcAuto`) e default `window`/`off`; os outros modos (window, pip, off, gamepad) são genéricos e servem ao 3D World |
| B | `runtime/src/overlay/overlay.cpp:1053,1456`; `docs/gamepad-display.md` | "In the game, Minus switches to Off-TV Play", "Pro Controller" | Texto do Off-TV do WWHD; no 3D World o Off-TV é outro | Ajustar textos ou usar `GAME_*` |
| B | `runtime/src/overlay/overlay.cpp:~1860-1880`; `runtime/src/game_languages.cpp:21-30` (pacotes `0x1048DD4C`); `runtime/src/language_region.cpp`; `tools/recomp/hooks_language.txt` | "Language packs" / região do console (leitor de idioma do Zelda) | Seleção de idioma por hooks Zelda não existe no sm3dw; no 3D World o idioma vem do HLE de `nn_*`/SysSettings | Esconder o seletor sob a macro; idioma do 3D World por HLE separado [não verificado como é hoje] |
| B | `runtime/src/rtl_text_hooks.cpp`, `runtime/src/rtl_text.cpp`; `tools/recomp/hooks_rtl.txt` | Texto árabe/hebraico RTL via hooks do renderer de texto Zelda | Sem hooks = inerte | Nada (ou ocultar opção) |
| B | `runtime/src/savestate.cpp:269,630,631,717,1423`; `runtime/src/verify_tap.cpp:68,81` | Estados "portáteis" e contadores usam `GD(0x1046F0B0)`, `0x101F84DC`, `0x101EA069`, `0x101FF560`, `0x101FF558` | Estado **completo** (snapshot) é genérico; o **portátil** e o `capture` por contador (`gx2_core.cpp:941`) lêem endereços Zelda | Deixar o snapshot completo; desligar "portable state" sob a macro |
| B | `runtime/src/mods/packages.h:11-19` | `kGameId="wwhd"` em manifestos de mods | Idem mods | oculto com a aba |
| C | `runtime/assets/npc_catalogue/windfall.recipe.json`; `CMakeLists.txt:152-160` | Receita de NPC (Windfall) embutida no binário via `npc_recipes.cpp` | Peso morto e referência ao Zelda no executável | Pular o `add_custom_command` sob a macro |

### 1.3 Android

| Pri | arquivo:linha | Hoje | Problema | Correção |
|---|---|---|---|---|
| A | `android/app/src/main/java/org/wwhdrecomp/wwhd/AndroidUserDirectories.java:11` | `new File(externalFiles, "config/wwhd")` | O nativo usa `config/sm3dw` (`runtime/src/platform/host.h:219`). Java (pré-compilação de shader, `shaderPrebuild`) aponta para a pasta errada: **bug real do sm3dw no Android** | Passar o nome por `BuildConfig` (flavor) ou `resValue` |
| A | `android/app/src/main/res/xml/full_backup_content.xml:5`; `data_extraction_rules.xml:8,12` | `config/wwhd/settings.ini` | Backup do Android inclui a pasta errada | Idem (arquivo por flavor) |
| B | `android/app/src/main/res/values/strings.xml:2,11`; `values-es/strings.xml:64`; `values-pt/strings.xml:10` | `app_name` "Wind Waker HD"; `save_not_a_save` "...Wind Waker HD save (user/cking.sav)" | `android/build_sm3dw_apk.sh:9-10` reescreve só `app_name` com `sed` num arquivo rastreado e restaura com `trap`: frágil, e o save continua dizendo Zelda/`cking.sav` | Flavor `sm3dw` com `src/sm3dw/res/values*/strings.xml` (sobrepõe `main`); ver 2.4 |
| B | `android/app/src/main/java/org/wwhdrecomp/wwhd/ExportSaveActivity.java:30`; `SaveFiles.java:61-67` | `WWHD-save-<t>.zip`; procura `user/cking.sav` | Export/import de save: o save do 3D World não tem `cking.sav` ⇒ "esse zip não tem save" e export sem arquivo [não verificado o nome do save do 3D World] | Nome do arquivo de save e prefixo por `BuildConfig` |
| C | `android/app/build.gradle:8,13`; `AndroidManifest.xml:19,53,81`; `shortcuts.xml:9-26` | `namespace`/`applicationId` `org.wwhdrecomp.wwhd` | `applicationId` já é parametrizado (`-PwwhdAppId=org.sm3dwrecomp.sm3dw`); `namespace` (pacote Java) pode ficar: renomear quebra merges | Manter |
| B | `android/app/src/main/AndroidManifest.xml:40` | Comentário sm3dw: `SetupActivity` sem `LAUNCHER` | Sem categoria LAUNCHER, o ícone é do jogo (`WwhdActivity`); ok | Passar para o manifest do flavor (menos diff) |
| A | `android/app/src/main/res/` `local-res` (gerado por `android/make_icon.py`, ver `android/.gitignore:6`) | Ícone vem de `game3dw/meta/iconTex.tga` | Derivado do jogo; já fora do git; **nunca** distribuir `dist/SM3DW-test.apk` | Ver seção 4 |

### 1.4 Rede, relatório de falhas, atualização

- **Verificação de atualização**: não encontrei (`grep -rniE "update check|releases/latest|new version"` em `runtime/src` e `android/app/src`). Nada a desativar.
- **Rede em runtime**: só catálogo/mods/story notebook (`mods_tab.h:64`, `mods/required.cpp:45`, `platform/https*.cpp`) e DSU local (`motion/dsu.cpp`). Resolve com a aba Mods oculta + URL vazia.
- **URL de relatório de crash**: nenhuma no runtime (`grep -ni "github\|issues" runtime/src` só acha comentários e créditos). No Android há `FileProvider` de logs (`AndroidManifest.xml:~30`, `WwhdActivity.java:147`): compartilha arquivo, sem URL.
- **GitHub**: `.github/ISSUE_TEMPLATE/*.yml`, `.github/workflows/*.yml` são Zelda; `tools/release/fetch_shader_template.py:12,16` só roda quando `GITHUB_REPOSITORY == ZeldaWWHDRecomp/ZeldaWWHDRecomp` (não afeta o fork). Template de shader privado: `/tools/shadercache/template.bin` ignorado (`.gitignore`).

### 1.5 Instalador, release, Windows, docs

| Pri | arquivo:linha | Hoje | Correção |
|---|---|---|---|
| C | `tools/installer/setup.py:2,184-194,1023-1186` | `APP_NAME="Wind Waker HD"`, title ids Zelda, `cking.rpx`, mensagens "this disc is not The Wind Waker HD" | Fora de escopo enquanto não houver release do sm3dw; usar `game_id.json` gerado (2.3) se virar release |
| C | `tools/release/package.py:17,67-90,100-126,543,659` | Nomes `WindWakerHD-*`, `wind-waker-hd`, `CFBundleIdentifier io.github.zeldawwhdrecomp.wwhd` | idem |
| C | `cmake/windows/app.rc.in:18-26` | `CompanyName "ZeldaWWHDRecomp"`, `Comments "...github.com/ZeldaWWHDRecomp/ZeldaWWHDRecomp"` | Parametrizar com `GAME_*` do CMake; antes de qualquer `.exe` público |
| C | `cmake/WindowsResources.cmake`, `cmake/SetupGui.cmake`; `tools/installer/gui/setup_gui.cpp:1` | Nomes de produto | idem |
| B | `README.md:1-60` (título, "Legal notice" cita Zelda/Wind Waker/Wii U, link Releases), `CHANGELOG.md`, `docs/*.md` (mods, language packs, button glyphs, gamepad...) | Tudo Zelda | Ver 4 (README do sm3dw em arquivo próprio, sem tocar o README upstream) |

## 2. Mecanismo único em tempo de compilação

### 2.1 Princípio

Um único cabeçalho **novo** (arquivo novo = nunca conflita) `runtime/src/game_id.h`, alimentado por **uma** definição de CMake. O default (nenhuma macro) reproduz exatamente os textos atuais do WWHD, então o build upstream não muda. Cada edição em arquivo existente é a troca de **um literal por macro**, mesma linha (diff de 1 linha, o que o `git merge upstream/main` resolve sozinho, salvo se o upstream mexer na mesma linha).

```cpp
// runtime/src/game_id.h  (novo; ver CMake abaixo)
#pragma once
#if defined(WWHD_GAME_SM3DW)
#  define GAME_NAME           "Super Mario 3D World"
#  define GAME_TITLE_FULL     "Super Mario 3D World"
#  define GAME_SHORT          "sm3dw"
#  define GAME_SHORT_UP       "SM3DW"
#  define GAME_FILE_PREFIX    "SM3DW"
#  define GAME_RPX            "RedCarpet.rpx"
#  define GAME_TITLE_ID       "0005000010145c00"
#  define GAME_SAVE_FILE      "<save do 3D World>"     /* [não verificado] */
#  define GAME_HAS_ZELDA_GUEST 0   /* hooks, mods, story, cheats, glifos, idiomas, true60/interp */
#  define GAME_NATIVE_60       1
#  define GAME_PERF_OVERLAY_DEFAULT true
#  define GAME_ASPECT_DEFAULT_WINDOW 1
#else
#  define GAME_NAME           "Wind Waker HD"
#  define GAME_TITLE_FULL     "The Legend of Zelda: The Wind Waker HD"
#  define GAME_SHORT          "wwhd"
#  define GAME_SHORT_UP       "WWHD"
#  define GAME_FILE_PREFIX    "WindWakerHD"
#  define GAME_RPX            "cking.rpx"
#  define GAME_TITLE_ID       "0005000010143500"
#  define GAME_SAVE_FILE      "cking.sav"
#  define GAME_HAS_ZELDA_GUEST 1
#endif
// os macros antigos continuam valendo, definidos aqui a partir dos novos (nenhum código existente muda):
#ifndef WWHD_USER_DIR_CAPS
#  define WWHD_USER_DIR_CAPS  GAME_SHORT_UP
#  define WWHD_USER_DIR_LOWER GAME_SHORT
#endif
```

CMake (substitui as 5 definições de `CMakeLists.txt:77` por uma e mantém `WWHD_GAME_RPX`):

```cmake
if(WWHD_GAME_RPX STREQUAL "RedCarpet.rpx")
  add_compile_definitions(WWHD_GAME_SM3DW=1)
endif()
```

`runtime/src/runtime.h:137-139` passa a incluir `game_id.h` (hoje define `WWHD_GAME_RPX` com default). Para Python/Gradle/Java a mesma fonte é um arquivo gerado no configure: `${CMAKE_BINARY_DIR}/generated/game_id.json` (chaves iguais), lido por `tools/installer/setup.py`, `tools/release/package.py` e `android/app/build.gradle` (propriedade `-PwwhdGame=sm3dw`), evitando literais duplicados.

### 2.2 Lista mínima de edições (arquivos existentes, uma linha cada, salvo nota)

| Arquivo | Edição |
|---|---|
| `CMakeLists.txt:76-78` | trocar 5 defs por `WWHD_GAME_SM3DW=1` (já é bloco sm3dw: sem risco) |
| `runtime/src/runtime.h:137` | `#include "game_id.h"` e usar `GAME_RPX` como `WWHD_GAME_RPX` |
| `runtime/src/main.cpp:343-348,465` | `GAME_SHORT ".log"` (opcional) e `GAME_NAME` no banner |
| `runtime/src/report_header.cpp:181` | `GAME_NAME` |
| `runtime/src/gfx/vulkan/backend.cpp:3231,3633,4086` | macros |
| `runtime/src/gfx/display.mm:112,426`; `menu.mm:61,419`; `quit_prompt.mm:91` | macros (`@GAME_NAME` funciona em clang/ObjC: `@"a" "b"` concatena) |
| `runtime/src/gfx/renderer.cpp:259,270` | `GAME_NAME` |
| `runtime/src/gfx/shader_prebuild.h:414` | `WWHD_USER_DIR_LOWER` |
| `runtime/src/overlay/overlay.cpp:1870,1982` | macros (2 linhas) |
| `runtime/src/gfx/vulkan/overlay_sdl.cpp:65`; `screenshot.cpp:128`; `portable_state.cpp:209-210` | macros |
| `runtime/src/overlay/overlay.cpp` seção FPS (773-817), FOV (833), cheats (1262-1286), Face buttons (1567), idioma (~1860) | `#if GAME_HAS_ZELDA_GUEST ... #endif` em torno de cada bloco (mudança aditiva: 2 linhas por bloco, conflito só se o upstream editar as bordas) |
| `runtime/src/mods/traversal_settings.cpp:35` | default de `g_story_system` = `GAME_HAS_ZELDA_GUEST` |
| `runtime/src/mods/cheats.cpp` (função `request_cheat`, `cheats.cpp:135`) | `#if !GAME_HAS_ZELDA_GUEST return; #endif` |
| `runtime/src/mods/required.cpp:45`; `overlay/mods_tab.h:64` | URL vazia sob `!GAME_HAS_ZELDA_GUEST` |
| `runtime/src/gfx/gamepad_ui_state.cpp:16` | `if (!GAME_HAS_ZELDA_GUEST) return;` (uma linha) |
| `runtime/src/sm3dw/aspect_sm3dw.cpp` (arquivo sm3dw, não conflita) | registrar `overlay::ext::hide_tab("mods")` num inicializador estático, forçar `interp::set_mode(0)`, esconder opção auto do GamePad se possível |
| `android/app/build.gradle` | `productFlavors { wwhd {}; sm3dw { applicationIdSuffix/ID, buildConfigField "GAME_SHORT" } }` ou `sourceSets` por `-PwwhdGame`; novo `android/app/src/sm3dw/res/values{,-es,-pt}/strings.xml` e `res/xml/full_backup_content.xml`, `data_extraction_rules.xml` (arquivos novos) |
| `android/.../AndroidUserDirectories.java:11`, `SaveFiles.java:61-67`, `ExportSaveActivity.java:30` | ler de `BuildConfig.GAME_SHORT` / `GAME_SAVE_FILE` |
| `android/build_sm3dw_apk.sh:9-10` | remover o `sed` + `trap` (a flavor resolve) |

Total estimado: ~30 linhas em ~16 arquivos existentes + 4-6 arquivos novos. Os blocos `#if GAME_HAS_ZELDA_GUEST` são a parte com maior risco de conflito futuro; a alternativa sem conflito é escondê-los pelo mecanismo já existente de extensões (`hide_tab`, `Features`, `extensions.h:1-9,55`), sempre que a UI for uma aba/recurso inteiro.

### 2.3 O que NÃO mudar (evitar conflito de merge)

Nomes `wwhd_*`, `WWHD_*` (variáveis de ambiente, `wwhd_mod.h`, ABI de mods), namespaces, `full_state_header.h:28` (`WWHDSTAT`, formato), alvo CMake `wwhd` (`CMakeLists.txt:243-246`), pacote Java `org.wwhdrecomp.wwhd`, `thread_sampler.cpp:117`, `warm_progress.h:33` (`WWHD-PROGRESS`, lido pelo instalador). Prefixo `WWHD_` é interno; só o texto exibido e os nomes de pasta/arquivo do usuário mudam.

## 3. Peso morto e custo no build sm3dw

### 3.1 O que é compilado sem nenhum hook guest

- Referências a endereços Zelda no runtime: `f_XXXXXXXX`/`_orig`/`hook_` em 20 arquivos: `interp_fx.cpp` (124), `interp.cpp` (107), `true60.cpp` (95), `true60_sites.cpp` (66), `true60_test.cpp` (26), `true60_link.cpp` (26), `rtl_text_hooks.cpp` (25), `mods/turbo.cpp` (25), `aspect.cpp` (22), `mods/camera.cpp` (20), `mods/climb.cpp` (18), `interp_reticle.cpp`, `countdown.cpp`, `mods/traversal.cpp`, `button_glyphs.cpp`, `mods/mods.cpp`, `hooks_audio.cpp`, `controller_selection.cpp`, `language_region.cpp`, `peekz.cpp`, `model_probe.cpp`, `motion/game_hooks.cpp`, `hle/system_stubs.cpp:56-59` (contagem por `grep -c`).
- Em tempo de execução: o gerador só emite chamadas a `hook_XXXXXXXX` para endereços listados em `hooks*.txt`; com `RECOMP_GAME=sm3dw` só `hooks_sm3dw*.txt` é lido (`tools/recomp/builds.py:225-229`). Logo **nenhum** `hook_*` do Zelda roda: custo de CPU por frame desses módulos = 0. Também não há custo de swap relevante em `interp::`: `interp_on()` depende de `WWHD_INTERP`/`WWHD_INTERP_FPS` (`interp.cpp:100-110`) e `true60::g_on` de `WWHD_TRUE60` (`true60.cpp:81`); ambos desligados por padrão.
- Custo real residual: (a) binário maior e link mais lento; (b) por swap `gx2_core.cpp:924` (retorna cedo fora do modo auto), `interp::logic_steps()` (`gx2_core.cpp:930`), `repeat_input()` por leitura de pad (`hle/system_stubs.cpp:101-106`, `hle/padscore.cpp:78`): atômicos/branches, desprezível; (c) 1132 bytes de receita Windfall + `mods::*::initialize` na inicialização (`main.cpp:515-520,579-580`), e `guestmods::init()`; (d) opções de UI que **podem ligar** `interp`/`true60` (ver 1.2), esse é o risco real.
- Desligar por macro é seguro **para os blocos de UI e inicialização** (ficam sem efeito porque nada os chama). É **inseguro** desligar por macro o mínimo compartilhado: `interp::mode()/set_mode/fps/display_hz/vsync_rate` e `true60::enabled()` são referenciados fora dos módulos Zelda (`hle/ax.cpp:785`, `hle/coreinit_misc.cpp:231-234`, `platform/perf_hint.cpp:35-44,74-110,212-216`, `platform/display_rate.cpp:66-76`, `gx2/gx2_core.cpp:606`, `platform/input_sdl.cpp:867-900`); manter e fixar em modo 0.
- `WWHD_NATIVE_60` só afeta `perf_hint.cpp:77-85,213` (retorna cedo). Não oculta a UI de interpolação nem protege `interp::set_mode`.

### 3.2 `tools/recomp/sm3dw_stubs.py` e as 187 stubs

- Confirmado: `build/gen3dw/code_zstubs.c` tem 187 definições: **179** `f_XXXXXXXX_orig` + **8** `f_XXXXXXXX` (`f_020CC020`, `f_02555D0C`, `f_0257E7C0`, `f_025D42EC`, `f_025D57E0`, `f_025DF940`, `f_02627734`, `f_026E9BC4`); 0 `hook_`/`site_`. Cada uma chama `ppc_unimplemented(c, addr, 0)` (`tools/recomp/sm3dw_stubs.py:30-32`, `runtime/src/core.cpp:322`), isto é, **para o processo** se for executada.
- Fragilidade: o nome da stub vem do log do linker (`sm3dw_stubs.py:20-26`), então a lista muda a cada merge do upstream (novo hook ⇒ novo símbolo ⇒ falha de link manual).
- **Risco de erro silencioso (colisão de endereço)**: a stub só define o que o código gerado *não* define. Em endereço onde o 3D World **também** tem uma função (`0x02xxxxxx` é o `.text` dos dois jogos), o linker liga `f_XXXXXXXX_orig` do runtime à função **do 3D World** sem avisar. Só não acontece hoje porque nenhum hook chama isso, mas é a razão para eliminar o mecanismo.
- Como remover a necessidade de forma limpa (em ordem de preferência):
  1. **Curto prazo (zero conflito)**: gerar `code_zstubs.c` no `recomp.py` quando `RECOMP_GAME != wwhd`, a partir de um **scan determinístico de `runtime/src`** (regex `\b(f|hook|site)_[0-9A-F]{8}(_orig)?\b`), não do log do linker, e marcar as stubs `__attribute__((weak))` (assim uma função real do jogo vence sem erro). Elimina o passo manual e o `LINKLOG`.
  2. **Médio prazo**: `option(WWHD_GUEST_ZELDA ON)`; com `OFF` o CMake remove de `RUNTIME_SOURCES` (`CMakeLists.txt:173-178`) os TUs-folha com `list(FILTER ... EXCLUDE REGEX)`: `true60*.cpp`, `interp_fx.cpp`, `interp_reticle.cpp`, `rtl_text_hooks.cpp`, `countdown.cpp`, `button_glyphs.cpp`, `hooks_audio.cpp`, `controller_selection.cpp`, `language_region.cpp`, `peekz.cpp`, `model_probe.cpp`, `motion/game_hooks.cpp`, `mods/{turbo,climb,camera,traversal,cheats}.cpp`. Antes é preciso dar um *stub de estado* (modo 0) para o que os demais arquivos usam (`true60::enabled()` etc.) em um único `true60_stub.cpp` novo. **Não** dividir `interp.cpp`/`aspect.cpp` ao meio no ramo: conflito certo no merge. [não verificado: quais desses TUs exportam símbolos usados por arquivos que continuam no build; checar com `nm`/`-Wl,-why_live`]
  3. **Não recomendado sem teste**: `-Wl,-undefined,dynamic_lookup` (Mach-O), `--unresolved-symbols=ignore-all` (lld), ou depender de `--gc-sections`/`-dead_strip` para dispensar stubs: o comportamento depende do linker e da plataforma (no Android o `.so` é SHARED e o carregador pode exigir resolução imediata) [não verificado].
- O recomendado: passo 1 agora, passo 2 junto com a `WWHD_GUEST_ZELDA`/`GAME_HAS_ZELDA_GUEST` da seção 2 (mesma macro, para o código e para o CMake).

## 4. Licença e higiene legal (repo privado que pode virar público)

Verificado:

- `.gitignore` (raiz) bloqueia `/game`, `game.wux`, `game.key`, `*.rpx`, `*.wud`, `*.wux`, `*.wua`, `*.key`, `*.iso`, `*.rvz`, `template.bin`, `shaderprep_template.bin`, `headstart.bin`, `*.log`, `*.metal`, `*.tsv`, `/wwhd_src/`, `/game3dw/`, `/build/`, `/dist/`, `captures/`, `/save/`, `trace_dump.txt` (`git check-ignore -v` confirmou `game3dw/code/RedCarpet.rpx`, `build/`, `dist/`, `failed_shader_*.metal`, `android/app/local-res`). `.gitignore` sm3dw tem 15 linhas novas.
- `git ls-files`: nenhum `.rpx/.wua/.wud/.tga/.bfres/.szs/.h264/.bfma`; os PNG rastreados são ícones `android/.../mipmap-*/ic_launcher.png` e `examples/guest-mods/**/assets/*.png` (genéricos; **[não verificado visualmente]** que os ícones são os do SDL e não do jogo). `git log --all --diff-filter=A` não mostra nenhum desses tipos já adicionados.
- No disco, fora do git (ignorados, mas **existem** e são derivados do jogo): `game3dw/` (rpx, `meta/*.tga`, `bootMovie.h264`, `Manual.bfma`), `failed_shader_*.metal` (8 na raiz), `trace_dump.txt`, `captures/`, `save/`, `dist/`, `build/gen3dw/*.c` (código recompilado). Nunca empacotar a pasta do projeto inteira nem `dist/SM3DW-test.apk` (o ícone vem de `iconTex.tga`, `android/make_icon.py:2-9`).

Riscos/ações:

| Item | Evidência | Ação |
|---|---|---|
| Submódulo `decomp` (gitlink `160000`) aponta para `github.com/ZeldaWWHDDecomp/wwhd` | `.gitmodules`, `git ls-files -s decomp` | Zelda-only e peso morto no sm3dw; para um repo público, remover o gitlink da branch ou deixar claro que é upstream. Código decompilado de jogo comercial fica em repositório de terceiros; este fork só referencia |
| `runtime/guest/include/wwhd/{functions,bindings,...}.h` (3,2 MB + 2,7 MB, "Generated from ZeldaWWHDDecomp/wwhd", CC0, `functions.h:1-5`) e `tools/decomp/manual_names.tsv` (nomes verificados do binário Zelda) | `git ls-files -s`, cabeçalho | Zelda-only; manter como upstream (não editar), avaliar excluí-los do ramo público para reduzir superfície |
| `hooks_sm3dw.txt`, `docs/sm3dw-*.md`, comentários em `aspect_sm3dw.cpp` | Endereços e nomes de função (`sead::PerspectiveProjection`, `023D7724`) extraídos do binário de 3D World | São fatos de compatibilidade (endereço/nome), não código nem assets; aceitável, mas **não colar trechos de disassembly, dumps de shader ou texto do jogo** nos docs; revisar `docs/sm3dw-study.md` e logs antes de publicar |
| `tools/recomp/builds.py` `_GAMES` | title id `0005000010145c00` e SHA-256 do `RedCarpet.rpx` | Identificadores, ok |
| `README.md:26-39` | Aviso legal cita Zelda; "contains no game code..." é válido para o fork se mantido | Criar `README.sm3dw.md` (não editar o README upstream): aviso "fan project, não afiliado à Nintendo; 'Super Mario 3D World', 'Wii U' são marcas dos donos; sem código/asset/chaves; dump próprio", como o texto atual |
| `LICENSE` (MPL-2.0) e créditos `README.md:770-800` | Cemu (MPL-2.0), metal-cpp, fmt, ImGui, zstd, GreenNaugahyde/rhemfur/ElFDA | Manter todos os créditos e o `LICENSE`; arquivos modificados do upstream continuam MPL-2.0 (código-fonte disponível) |
| Não há `NOTICE`/`THIRD_PARTY` consolidado | `git ls-files | grep -iE "NOTICE|THIRD"` só acha licenças em subpastas | Antes de publicar: `NOTICE` curto apontando `runtime/third_party/*/LICENSE*`, `android/app/src/main/assets/licenses/*`, `tools/release/appimage-runtime-licenses/` |
| Metadados com identidade do upstream | `cmake/windows/app.rc.in:18-26`, `tools/release/package.py:89` | Não distribuir binários com `CompanyName "ZeldaWWHDRecomp"`/URL do upstream |
| Marcas/ícone | "Super Mario 3D World" no nome do app/janela | Texto nominativo ("compatível com") em vez de logotipo; sem ícone da Nintendo no repo; ícone adaptativo gerado localmente, nunca versionado |
| Segredos | `android/app/build.gradle:23-40` lê keystore por ambiente; `tools/release/fetch_shader_template.py:12` (repo privado do upstream via SSH) | Garantir que nenhuma chave/keystore entre no repo; o script só roda no repo upstream |
| `*.tsv` ignorado mas `tools/decomp/manual_names.tsv` rastreado | `.gitignore` + `git ls-files` | Conferir intencionalidade (adicionado com `-f`) |

## 5. Ordem sugerida de trabalho

1. `game_id.h` + 1 define CMake + trocas de literal da tabela 1.1 (uma só PR no fork; baixa chance de conflito).
2. `hide_tab("mods")` + `interp::set_mode(0)` + remoção da seção de FPS + gamepad auto desligado (corrige os itens **A**).
3. Corrigir `AndroidUserDirectories.java:11`/backup XML/saves via flavor `sm3dw` (bug real, independe do branding).
4. Stubs geradas por scan (3.2, passo 1); depois `GAME_HAS_ZELDA_GUEST`/`WWHD_GUEST_ZELDA` (passo 2).
5. `README.sm3dw.md`, `NOTICE`, decisão sobre o submódulo `decomp` e os cabeçalhos `runtime/guest/include/wwhd/` antes de qualquer publicação.
