# Changelog

O que muda em cada versão do port de Super Mario 3D World. O estado atual e o roadmap estão em
[docs/roadmap.md](docs/roadmap.md).

## Next update

- **Limpeza:** todo o código, assets, hooks, menus, ferramentas de instalação, CI e documentos específicos do Wind Waker HD
  foram removidos (issue #14); o alvo é só o Super Mario 3D World (EUR Rev 1).
- **Desempenho:** harness de benchmark sem humano, resolução automática, preset Intel 120, filtro de upscale "quality",
  `WWHD_MUL_MODE=fast`, interpolação de 120 fps (parcial), pular quadros e resolução 0.25x.
- **Android:** toque na imagem do jogo, vibração e giroscópio do celular, exportar/importar save, importar jogo no app.

- **Sistema completo:** os imports do jogo que ainda devolviam 0 agora têm implementação (heap de blocos, diretório por cliente,
  leitura posicional, remoção de arquivos, zlib, GX2R, condições e prioridade de threads, AXFX, conta local, rede indisponível). Dos 549,
  só 2 (que o jogo não chama) seguem vazios. .
- **Saves seguros:** o save novo é escrito num arquivo ao lado e só substitui o anterior depois de gravado no disco.
- **Shaders pré-traduzidos** (`docs/studies/sm3dw-shader-pack.md`): o aparelho lê os shaders já traduzidos no PC em vez de rodar o
  decompilador Latte, o que tira as travadas de cena nova. A tabela vai no APK e o celular grava o que faltar. Também
  se monta a partir de um cache de shaders do Cemu (`tools/sm3dw_shader_pack.sh cemu`), sem jogar.
- **Widescreen:** fades, cortinas e transições em círculo (íris) cobrem a tela inteira em ultrawide. Barras de
  guia ocupam a largura da tela e "Back" fica ancorado na borda esquerda.
- **Vulkan:** a compilação de pipelines roda num pool de threads e os draws esperam o pipeline em vez de travar
  o quadro. O cache de pipelines é salvo a cada checkpoint. Uma cópia de superfície ainda não escrita é pulada em
  vez de derrubar o jogo.
- **Áudio:** primeiras chamadas AX (`AXIsInit`, `AXSetMode`, `AXSetMasterVolume`, `AXRegisterCallback`,
  `AXAcquireVoice`, `AXIsVoiceRunning`).
- **Sistema:** stubs explícitos de conta, Miiverse e `sysapp`; o log de boot fica sem `unimplemented`.
- **Controles:** vários controles físicos (o primeiro é o GamePad, os outros são Pro Controllers nos canais
  KPAD 1-3).
- **Android:** APK próprio com o ícone do jogo, 60 Hz, sem botões de toque, contador de FPS compacto e tuning
  para o Cortex-A78.
- **Ferramentas:** `tools/sm3dw_play.sh`, `tools/sm3dw_tour.sh`, `tools/android_bench.sh`.
- **Repositório:** removido o que não é usado pelo 3D World (decompilação, mods de exemplo, catálogo, CI e docs
  de outro jogo).
