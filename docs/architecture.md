# Arquitetura: recompilação -> runtime GX2 -> Vulkan

Nada do jogo está neste repositório. Tudo o que depende do jogo (código C gerado, shaders traduzidos, dados) é
produzido na máquina do jogador a partir do dump dele e fica fora do git (`build/gen3dw/`, `game3dw/`, caches).

```
RedCarpet.rpx (PowerPC)            seu dump do jogo (Wii U, EUR Rev 1 testado)
      |  tools/recomp/recomp.py  (RECOMP_GAME=sm3dw)
      v
código C do jogo (54 075 funções, ~61 arquivos)   -> libgamecode.a
      |  linka com
      v
runtime (runtime/src)
  core.cpp, threads.cpp, mem*     CPU/memória/threads do Espresso emulados "por ABI": o código recompilado roda nativo
  hle/*.cpp                       Cafe OS reimplementado (coreinit, FS, AX/áudio, VPAD/KPAD, MCP, SAVE ...)
  gx2/*.cpp                       camada GX2: lista de comandos, registradores Latte, tela virtual (vsync), frameskip
  sm3dw/*.cpp                     ganchos do jogo: widescreen/HUD (aspect_sm3dw), 120 fps (interp_sm3dw)
      |  comandos de desenho (registradores Latte + shaders)
      v
gfx/vulkan/*.cpp                  GX2 -> Vulkan 1.3: pipelines, passes, cache de buffers/texturas, apresentação
runtime/third_party/cemu          decompilador de shaders Latte -> GLSL/SPIR-V (MPL-2.0, vendored)
      v
SDL3 (janela, entrada, áudio)     Linux/Windows/macOS (MoltenVK)  |  Android (libmain.so + app Java)
```

## Etapas

1. **Recompilação estática** (`tools/recomp`). `recomp.py` lê o `.rpx`, descobre as funções, traduz cada instrução PPC
   para C (`ppc2c.py`, ponto flutuante bit a bit como o Espresso: `-ffp-contract=off`, sem fast-math) e escreve
   `code_*.c`, `table.c` (endereço -> função), `imports.c`. `tools/recomp/hooks_sm3dw*.txt` lista as funções do jogo
   que o runtime substitui (`hook_XXXXXXXX`, com `f_XXXXXXXX_orig` para chamar o original).
2. **Runtime/HLE.** O jogo chama as bibliotecas do Cafe OS por imports; `runtime/src/hle` os implementa em C++ nativo.
   Threads do jogo são threads do host; o "vsync" é um relógio virtual (`gx2/gx2_core.cpp`).
3. **GX2.** O jogo grava comandos (PM4/Latte) e display lists; `gx2` decodifica registradores de desenho e entrega
   cada draw ao renderizador.
4. **Vulkan.** `gfx/vulkan` traduz shaders Latte com o decompilador do Cemu (GLSL -> SPIR-V via glslang), cria pipelines
   sob demanda, mantém cache de pipelines/shaders em disco, escala a resolução interna (`WWHD_RES_SCALE`, auto-res) e
   apresenta com o filtro escolhido. Em Android há `libadrenotools` para drivers Turnip alternativos.
5. **Plataforma.** SDL3 no desktop; no Android o app Java (`android/`) importa o jogo, escolhe driver, mostra o menu,
   controles na tela, toque/giroscópio/vibração e carrega `libmain.so`.

## Importação do jogo
O jogador fornece o próprio dump (`.wux`/`.wud` + `keys.txt`, ou `.wua`, ou pasta). O app (`libm3dwimport.so`,
`tools/wudextract`) extrai só o necessário para a pasta do app; o `RedCarpet.rpx` precisa ter o SHA-256 do código
recompilado (EUR Rev 1: ver `android/build_sm3dw_apk.sh`). Ver `docs/android-build.md`.

## Por que o prefixo WWHD_ e `org.wwhdrecomp.wwhd`
O projeto nasceu do porte do Wind Waker HD. Todo código específico daquele jogo foi removido (issue #14), mas os nomes de
variáveis de ambiente (`WWHD_*`), o executável `wwhd` e o pacote Java ficaram para não quebrar scripts, configurações e
instalações existentes. `GAME_HAS_ZELDA_GUEST` é 0 neste alvo.

Estudos de design e medições antigas: `docs/studies/`. Os documentos `vulkan.md` e `android-gpu.md` foram escritos quando o
projeto mirava outro jogo; a parte do renderizador continua válida, os nomes de jogo/endereços não.
