# Configurações dentro do jogo: estudo

Pergunta: tirar as configurações do menu de ImGui (a janela com abas aberta por cima do jogo) e levá-las para "as
configurações do próprio jogo". Estudo de viabilidade; nada foi implementado aqui. Marcado **[não verificado]** o que veio de
dedução e não de leitura ou de execução.

## 1. O que o menu atual tem

`runtime/src/overlay/overlay.cpp` (2.415 linhas), uma janela ImGui com abas. Em ordem: Saves, Graphics, Display, Audio, Controls,
About (Mods fica escondida no 3D World). O título da janela ainda diz "Wind Waker HD  -  Settings" (`overlay.cpp:2018`).

| Aba | O que tem | No celular |
|---|---|---|
| Saves | salvar e carregar estado, cópia do save para relatório de erro, pasta de capturas | exportar e importar save faz sentido; estados salvos, não (o estudo do 3D World já diz que não funcionam) |
| Graphics | driver de GPU (ZIP), renderizador, taxa de quadros (60, "True 60", sem limite), resolução interna, FOV, razão de aspecto, 3D estéreo, HDR, bloom, AO, FXAA, escolha de GPU, modo de apresentação, contador de desempenho, relatório de bugs | resolução interna, razão de aspecto e contador de FPS; o resto é de PC ou é do Zelda |
| Display | tamanho da interface, botões de toque, velocidade de câmera, sprint, vela rápida, sensibilidade | tamanho da interface e botões de toque; velocidade, sprint e vela são do Zelda |
| Audio | volume mestre, alto-falantes (estéreo ou 5.1), teste de alto-falantes | volume |
| Controls | mapeamento de botões, zona morta, cores dos botões | mapeamento de controle; zona morta |
| About | versão, licenças | sim |

Ou seja: das dezenas de controles, os que o jogo no S23 precisa são uns 10 a 15. O resto não deveria nem aparecer.

## 2. O que o jogo tem de interface de configuração

Lido dos arquivos do jogo (`game3dw/content/LayoutData/*.szs`) e das strings do RPX:

- **Menu de pausa** (`PauseMenu.szs`): botões de ícone, com os ícones `NyIconControllerChange` (trocar controle),
  `NyIconCameraChange` (trocar câmera) e `NyIconNintendoNetwork`. A lista de botões vem de `InitButtonGroup.byml`.
- **Janelas** `WindowCameraChange` e `WindowControllerChange`: as únicas telas do jogo que parecem configuração.
- **Componente de seleção** `ButtonConfigParts.szs`: moldura (`NyConfigFrame`), janela de valor (`NyConfigWindow`) e setas
  (`NyButtonArrow`), com animações de seleção, desabilitado e troca para a esquerda e para a direita. É o estilo "`< valor >`".
- Existem as strings `OptionButton` (`102A43F8`, `102A5062`, junto das de `FileSelect_*`); **[não verificado]** quando esse botão
  aparece (a captura da seleção de arquivo, sem saves, não o mostra).

Ou seja: **o jogo não tem um menu de configurações geral** (som, vídeo, controles). Tem a troca de câmera e de controle. Qualquer
"configuração dentro do jogo" é algo que criamos, não algo em que se acrescentam linhas.

## 3. O que o projeto já tem para mexer na interface do jogo

- `runtime/src/sm3dw/aspect_sm3dw.cpp`: ganchos no motor de painéis `nw::lyt` (CalculateMtx, Draw, DrawSelf) e uma tabela de regras
  por painel. Prova que dá para interceptar o desenho da interface do jogo por endereço.
- `mods::content::replacement(caminho)` (`runtime/src/mods/content.cpp`): troca um arquivo do jogo por outro na leitura.
- `overlay/game_font.cpp` e a leitura de texturas do jogo: o ImGui já consegue desenhar com assets do próprio jogo.
- `overlay/gamepad_nav.h`: navegação do menu pelo controle.

## 4. Três caminhos

### A. Layout nativo (de verdade "dentro do jogo")

Criar uma janela nova no motor do jogo, com os mesmos componentes (`ButtonConfigParts` etc.), e um botão novo no menu de pausa.

Exige: gerar arquivos binários de layout (`.bflyt`, `.bflan`) e de texto (`.msbt`), um `.szs` novo, trocar o `InitButtonGroup.byml`
do menu de pausa e escrever o código que cria e comanda a janela, tudo por ganchos em código recompilado, **sem decompilação nem
símbolos**. Cada função do menu do jogo teria que ser localizada na mão no código recompilado.

- Custo: semanas. Risco: alto (um erro derruba o jogo no menu de pausa, dentro da fase).
- Ganho: aparência e comportamento 100% nativos; mexe com toque, GamePad e controle sem trabalho extra.
- Fora de alcance de verificar sem jogar: cada passo precisa ser testado pausando no meio de uma fase.

### B. Painel com a aparência do jogo, aberto pelo menu de pausa (recomendado)

Manter a lógica das configurações e desenhar um painel com os assets do jogo (moldura, setas `< valor >`, fonte, cores, ícones),
aberto por um botão do próprio menu de pausa (por exemplo no lugar do `Nintendo Network`, que offline não serve para nada) e
navegável pelo controle e pelo toque.

Exige: localizar a função do menu de pausa que trata o botão apertado (uma função) e desenhar o painel em ImGui com as
texturas do jogo.

- Custo: dias. Risco: médio-baixo (o gancho é um só, o painel é nosso e não toca no estado do jogo).
- Ganho: parece e se comporta como parte do jogo, inclusive abrir de dentro da fase, e não uma janela de depuração por cima.
- Limite: não é a interface do jogo por dentro; é uma camada com a mesma cara.

### C. Só reorganizar o menu atual

Renomear, esconder tudo o que é de PC ou do Zelda, deixar o que serve ao celular e abrir por gesto. Custo: horas. Risco: nenhum.
Não resolve "dentro do jogo", mas resolve "feio e cheio de coisa que não serve".

## 5. Recomendação

**C agora, B em seguida, A só se B não bastar.**

1. **C** (horas): título do 3D World, esconder o que é de PC ou do Zelda no Android, agrupar as 10 a 15 opções que importam.
2. **B** (dias): painel com a cara do jogo e botão no menu de pausa. Primeiro marco: achar e interceptar o botão do menu de pausa.
3. **A** só se for importante a janela ser realmente do motor do jogo.

## 6. O que falta saber antes de começar B

- Qual função trata a decisão de botão do menu de pausa e como saber qual botão foi apertado. **[não verificado]**
- Se o botão `Nintendo Network` pode ser reaproveitado sem que o jogo tente abrir o Miiverse. **[não verificado]**
- Como o menu de pausa se comporta em fase com vários jogadores e com o GamePad. **[não verificado]**
