# Controles, toque, giroscópio, vibração e sopro

## Desktop
F1 abre as configurações (no controle: Home ou segurar Select/Minus; D-pad/analógico, A seleciona, B volta, L/R trocam de
aba). Mouse, teclado e controle SDL3 são mapeados na aba Controles (botões e teclas configuráveis). Modos da imagem do
GamePad (`WWHD_DRC_MODE`): `window`, `pip`, `auto`, `off` (não desenha, -0,3 ms), `gamepad` (tela cheia).

## Android
* **Controles na tela** aparecem se não houver controle externo; só controles externos os escondem.
* **Tocar na imagem do jogo** equivale a tocar a tela do GamePad (coordenadas normalizadas dentro da caixa da imagem,
  sem as barras pretas); só vale enquanto o jogo lê o toque (`VPADGetTPCalibratedPoint`); o overlay e o botão de visão têm
  prioridade. Menu Controles > "tocar na imagem".
* **Vibração**: o rumble do GamePad aciona o vibrador do celular quando não há controle com motor (liga por padrão).
* **Giroscópio**: o giroscópio/acelerômetro do celular (sensores SDL, girados para a tela) alimenta a fonte de giro do
  controle (desligado por padrão). Para o jogo, `WWHD_GYRO=off|controller|cemuhook|mouse` escolhe a fonte; desktop aceita
  giro de controle (DualSense, Switch Pro, Steam Deck), servidor Cemuhook (DSU, UDP 26760) e mouse.
* **Sopro (microfone do GamePad)**: menu Controles > "Sopro": Desligado (padrão) / Microfone (pede a permissão
  de gravar áudio nessa hora; fala e barulho são filtrados) / Botão (botão redondo ≈ na tela, segure para soprar).
  Sensibilidade 25-400 %. Usado em dentes-de-leão, arbustos, Toads e nos elevadores de sopro da fase 2-2.
  Detalhes: docs/mic.md. No desktop: F1 > Controls > "Blow (GamePad mic)", tecla B.
* O que mais o jogo lê do GamePad e o estado de cada coisa: docs/gamepad-features.md.
* Pendências: controles por contexto e layout por tamanho de tela (issue #8), perfis de energia (#10).

Mais detalhes de design: `docs/studies/sm3dw-input-study.md`.
