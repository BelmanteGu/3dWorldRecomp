# O que o Super Mario 3D World lê do GamePad (e dos outros controles) — inventário

Levantado nos imports do `RedCarpet.rpx` (EUR Rev 1), no código recompilado (`build/gen3dw`, fora do git) e nos
nomes de atores/estados das fases (`content/StageData`). "Celular" = APK Android; "PC" = desktop SDL3.
Validação no aparelho: o que diz "falta validar" ainda não foi testado no celular.

| Recurso | O que o jogo usa | PC | Celular | Issue |
|---|---|---|---|---|
| Botões e analógicos do GamePad | `VPADRead` (hold/trigger, sticks) | teclado, controles SDL, mapeáveis (F1) | botões de toque na tela ou controle externo | — |
| Tela de toque | `VPADGetTPCalibratedPoint` (7 chamadas): blocos de toque, atordoar inimigos, pisos/paredes de toque de `TouchAndMikeStage` (2-2), Capitão Toad, "DrcAssist" (o jogador do GamePad ajuda no multijogador) | janela do GamePad (`WWHD_DRC_MODE`), mouse | tocar na própria imagem do jogo vale como toque do GamePad (menu Controles > "Tocar na imagem do jogo") | #6 (falta validar) |
| Giroscópio / acelerômetro | `VPADRead` gyro/acc/ângulo/direção; HUD `CameraGyroDRC` (`GyroOn`/`GyroOff`): câmera que segue o movimento do GamePad | controle com giro (DualSense, Switch Pro, Steam Deck), Cemuhook/DSU, mouse | sensores do celular (menu Controles > "Giroscópio", desligado por padrão) | #7 (falta validar) |
| Vibração | `VPADControlMotor`/`VPADStopMotor` (GamePad), `WPADControlMotor` (Pro/Remote) | motor do controle | vibrador do celular (ou do controle) | #7 (falta validar) |
| **Microfone (soprar)** | libmic: `sead::MicDevice`, 32 kHz; dentes-de-leão (`FlowerDandelion`), arbustos (`Bush`), Toads/brigada do Capitão Toad, Rosalina, Plessie (`Raidon`), **elevadores de sopro `LiftMike*`** (2-2 `TouchAndMikeStage`, 1-8 `KinopioBrigadeTentenStage`, `ArrangeChikaAZone`) | microfone do PC ou tecla B (F1 > Controls > Blow) | microfone do celular (pede a permissão ao ligar) ou botão ≈ na tela (menu Controles > "Sopro") | #19 — [docs/mic.md](mic.md) (falta validar no celular) |
| Imagem do GamePad | o jogo desenha a tela do GamePad (mapa, toque, mapa da fase) | janela, PiP, automático, desligada, só GamePad | PiP / automático / desligada (menu Tela) | — |
| Som do GamePad | AX: saída DRC (`AXSetDRCVS*`) | misturado na saída do host (`runtime/src/hle/ax.cpp`) | idem | — |
| Fone no GamePad | `VPADBASEGetHeadphoneStatus` | 0 (sem fone): som pelo alto-falante/TV | idem | — |
| Pro Controller | `KPADReadEx` tipo `0x1F` | canal 0 quando o modo Pro está ligado (F1 > Controls) | idem | — |
| Wii Remote (deitado), + Nunchuk, Classic | `KPADReadEx` tipos 0/1/6/2/7; `KPADSetMplsWorkarea` (MotionPlus), alto-falante do Remote (`WPADControlSpeaker`, `WPADSendStreamData`, `WENCGetEncodeData`), `KPADEnable/DisableDPD` (ponteiro) | **não**: só o perfil Pro; o alto-falante e o ponteiro são stubs | **não** | #23 |
| Multijogador local (2-4 jogadores) | 4 canais KPAD (`0x0236BAE8`) + GamePad | **não**: um só estado de controle | **não** | #23 |
| Câmera do GamePad | a biblioteca `camera` aparece na tabela de imports, mas **nenhuma função** é importada | não usada | não usada | — |
| NFC / amiibo | nenhum import (`nn_nfp` ausente) | não usado pelo jogo | não usado | — |
| Botão TV / Power / HOME | HOME mapeado (tecla H, Guide); TV e Power são do sistema | — | — | — |
| Miiverse (carimbos, posts, fantasmas Mii) | `nn_olv` (Initialize falha de propósito) | indisponível (offline) | indisponível | fora do escopo |

## Entradas especiais do 3D World, por fase

- **2-2 (`TouchAndMikeStage`)**: a fase do toque e do microfone: pisos e paredes que se tocam (`TouchAndMikeTouchFloor*`,
  `TouchAndMikeTouchWall*`) e blocos que andam em trilhos enquanto se sopra (`LiftMikeBlockRail`). No celular: tocar na
  imagem + sopro (microfone ou botão ≈).
- **Capitão Toad** (`KinopioBrigade*Stage`): toque em blocos/plataformas, giro para a câmera (`CameraGyroDRC`), sopro nos
  elevadores `LiftMike` (1-8) e nos dentes-de-leão.
- **Dentes-de-leão e arbustos** em muitas fases (1-1, 1-2, 2-2, ...): soprar solta as sementes/folhas (moedas/itens).
- **Plessie (`Raidon`)**: reage ao sopro na largada (`MicStartWait`, `MicReadyWait`) e na despedida.

## Para testar no celular

1. Menu (pausa) > Opções > Controles > **Sopro**: Microfone → aceitar a permissão (ou Botão).
2. Entrar na 2-2 e soprar no microfone de baixo do celular (ou segurar ≈): os blocos em trilho devem andar.
3. `adb logcat | grep '\[mic\]'`: `MICInit succeeded`, `capture started`, `fed ... game consumed ...`.
4. Na mesma fase, tocar nos pisos de toque direto na imagem do jogo (#6) e, numa fase do Capitão Toad, ligar o
   giroscópio (#7) e mover o celular.
