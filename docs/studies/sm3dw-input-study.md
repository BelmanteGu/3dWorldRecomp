# Entrada do 3D World no runtime: estudo

Estudo estático (nada foi executado). Endereços `0x02......` são guest (RedCarpet.rpx, desmontados com `tools/ppcdis.py`). "Não verificado" marca o que só uma execução prova.

## 1. Por que o jogo avança sem controle

### O que o runtime entrega hoje (sem teclado/controle, `pro_controller()=false`)

| Chamada | Retorno do HLE | Ref |
|---|---|---|
| `VPADRead(0)` | 1 amostra, `hold=0`, `err=0`, sticks 0, toque `touched=0 validity=3` | `runtime/src/hle/system_stubs.cpp:78-185` |
| `VPADRead(chan!=0)` | 0 amostras, `err=-2` | `system_stubs.cpp:95-98` |
| `WPADProbe(chan)` | `-1`, tipo `0xFD` (nenhum) | `padscore.cpp:58-65` |
| `KPADReadEx(chan)` | 0 amostras, `err=-2`; o buffer do jogo não é tocado | `padscore.cpp:67-75` |
| `connected(chan)` | só `chan==0 && input::pro_controller()` | `padscore.cpp:19`, `input_sdl.cpp:930` |

Ou seja, o jogo vê o GamePad como único controle, sempre presente e parado. Nenhum código do runtime injeta botão sozinho: `WWHD_PRESS/KEYS/TEST_PRESS` só valem com variável de ambiente (`input_sdl.cpp:757,775,843`); `crashrec` só reproduz com `WWHD_REPLAY` (`crashrec.cpp:85,111`).

### Como o jogo lê (verificado no código recompilado)

| Função guest | Papel |
|---|---|
| `0x0230E174` (`VPADRead` em `0x0230E1BC`) | Gerente do GamePad. `VPADRead(0, obj, 0x10, &err)`; guarda a contagem em `obj+0x46C0`, estado em `obj+0x4760`. `err==0` caminho normal (`0x0230E260`); `err==-2` marca `obj+0x46E0=1` e conta timeout (`0x0230E768`); outro erro só decrementa o contador `+0x98`. |
| `0x0236BAE8` (`KPADReadEx` em `0x0236BB38`) | Laço dos 4 canais: `KPADReadEx(chan, buf, 16, &err)`; slot `n` fica em `mgr+0x1118+n*0xF08`, contagem em `+0xF00`, erro em `+0xF04`. Chan é recuperado por divisão do ponteiro (`mulhwu`). |
| `0x0236BC74` | Por jogador: lê o canal em `player+0x15C` (`<4`). Conectado se `count>0` ou `err==-1`, e `devType(+0x5C)!=0xFD` (`0x0236BD1C-0x0236BD40`). Senão grava `+0x160=0xFD`, `+0x163=0` (`0x0236C3BC`). Tipos tratados: `0x1F` Pro (usa `hold` em `+0x60`, `0x0236BD58`), `2`/`7` Classic (lê `hold` em `+0`, `0x0236BFD0`), `1`/`6` com Nunchuk (`0x0236C174`, `0x0236C1F4`), demais como Remote. |
| `0x02526E48` | Status por canal: `WPADProbe(chan,&type)` em `0x02526E78`; `-1` = desconectado, zera `+0x163`, timer `+0x168=0x3C` (60 quadros) e `+0x160=0xFD`. |
| `0x0238AF4C` | `conectado = (WPADProbe != -1)`: retornos `0` e `-2` (conectando) contam como conectado. |
| `0x02495B68` | Outra sondagem: `WPADProbe==0 && type!=0xFD` chama `0x02704670` (provavelmente `WPADDisconnect`/reconexão; **não verificado**). |
| `0x0230DF84-0x0230DFB8` | Init: `VPADInit`, `WPADInit`, `KPADInitEx` (todos os dois primeiros caem em `hle_unimplemented` e retornam 0, `core.cpp:351`, inofensivo). |

### Causa do avanço: **não comprovada**

O HLE de entrada não explica o avanço (nenhum bit é gerado sem tecla). Hipóteses, em ordem de probabilidade:

| # | Hipótese | Como confirmar |
|---|---|---|
| H1 | Timer próprio do jogo (título ocioso -> demo/"attract"). O jogo nunca vê um botão. | Rodar com `WWHD_PAD_TRACE=/tmp/pad.txt` (`system_stubs.cpp:134`): se todas as linhas têm `hold=0` e o jogo avança, é H1. Cemu ocioso no título mostra o mesmo? (oráculo) |
| H2 | `trig` ou `hold` espúrio vindo do host (mouse sobre a janela DRC, `set_touch`, mods do Zelda ainda ativos: `mods::filter_pad`, `fast_forward_input`, roda-do-mouse -> R3, `input_sdl.cpp:954`). Os arquivos de hook estão desligados, mas as chamadas a `mods::*` no VPADRead/KPAD continuam compiladas. | `WWHD_LOG_BUTTONS=1` (`input_sdl.cpp:964`) e `WWHD_NO_HOST_INPUT=1`; comparar. |
| H3 | Toque: o jogo lê `validity` (`0x52/0x5A/0x62`). Ocioso mandamos `validity=3` (XY inválido); o real pode mandar 0 com `touched=0`. Um valor que o jogo trate como "tocado" faria "A Start" disparar por toque. | Trocar para `validity=0` ocioso e comparar (`system_stubs.cpp:173`). Não verificado qual é o valor real do hardware. |
| H4 | O jogo quer o canal KPAD 0 e cai num caminho de fallback (ex.: `err=-2` vs `-1`). Há risco no `KPADReadEx` desconectado não zerar o buffer do jogo (`+0x5C` fica 0 = "Wii Remote core", não `0xFD`), mas o jogo só consulta o buffer com `count>0 || err==-1` (`0x0236BD2C`), então é seguro hoje. | Log `[pad] KPADReadEx ... connected=0` já existe (`padscore.cpp:70`). |

Recomendação de diagnóstico (barata, antes de qualquer implementação): uma execução de 60 s no título com `WWHD_PAD_TRACE`, `WWHD_LOG_BUTTONS=1`, `WWHD_NO_HOST_INPUT=1` e `WWHD_TRACE_VPAD=20` (`system_stubs.cpp:82`) para ver quem chama e se algum `hold` não-zero aparece.

## 2. Mapa de controles

Estado do runtime: o teclado/controle host e o pad virtual do Android viram **um `PadState` de GamePad** (`input.h:18`) e vão para `VPADRead`; só com "Pro Controller" ligado (menu Input) o mesmo estado vai para `KPADReadEx` canal 0 como tipo `0x1F` e o GamePad fica sem botões (`system_stubs.cpp:114`). Android: `TouchControls.java` -> `nativeSetPad` -> joystick SDL virtual (`touch_pad.cpp:40`) -> `input_map::controller_state` -> `PadState`.

| Controle do 3D World | Entrada do jogo | Runtime hoje | Falta |
|---|---|---|---|
| GamePad: sticks, A B X Y, L R ZL ZR, +, -, D-pad | `VPADRead` `hold` | OK (teclado, controle SDL, toque Android) | nada |
| GamePad: tela de toque (blocos, atirar/atordoar) | `VPADRead` toque + `VPADGetTPCalibratedPoint` | macOS: janela DRC (`display_modes.cpp:278,369`). Android: 2a tela (`backend.cpp:3749-3801`) ou modo `gamepad` na janela principal | No celular de 1 tela, não há como tocar a tela do GamePad enquanto joga com os botões virtuais. Precisa de modo "alternar" (botão que mostra o DRC em tela cheia e envia toque, `kDrcGamePad`). Não verificado se o jogo usa `VPADGetTPCalibratedPointEx` (não está nos imports; só `VPADGetTPCalibratedPoint`). |
| GamePad: giroscópio | `VPADRead` `gyro/angle/dir` (`+0x38/+0x44/+0x6C`) | `motion::vpad` já preenche (`system_stubs.cpp:150-160`), fontes: controle, DSU, mouse | Não verificado se/onde o 3D World lê (não procurei os acessos em `0x230E174`). Fonte "sensor do celular" no Android não existe (`motion.h` lista só controller/cemuhook/mouse). |
| GamePad: microfone (soprar) | `MIC*` | stubs | fora do escopo de entrada; não verificado. |
| Pro Controller | `KPADReadEx` tipo `0x1F`, `hold` em `+0x60`, sticks `+0x6C/+0x74` | Só canal 0 e só com o menu Input em Pro (`padscore.cpp:19`). Layout confere com o jogo (`0x0236BD58`). | `WPADProbe`/`KPAD` por slot (ver 3); `WPADProbe` real devolve `0` conectado. |
| Classic Controller | `KPADReadEx` tipo `2`/`7`, `hold` em `+0` | Não existe. | Perfil Classic: tipo `2`, preencher `hold` (+0) e `ex_status.cl` (sticks/gatilhos em `+0x60..`), `data_format` `0x1/0x2`... **não verificado**: a máscara de bits exata que o jogo decodifica em `0x0236C000-0x0236C0FF` (usa bits 0x400/0x200/0x100/0x80/0x1000/0x2000/0x8/0x800 de `hold`). |
| Wii Remote de lado | `KPADReadEx` tipo `0`, `hold` em `+0` (Remote: D-pad, 1, 2, A, B, +, -, Home), acelerômetro `+0x8` para agitar | Não existe. | Perfil Remote: `devType=0`, `hold` com máscara Wii Remote, `acc` para o movimento de agitar (rolar/atacar com sacudida; **não verificado** se o jogo usa mais que `hold`). |
| Remote + Nunchuk | tipo `1`/`6`, stick do Nunchuk em `+0x5E` área ext., C/Z em `hold` | Não existe. | Perfil Nunchuk; sticks e C/Z. Não verificado o offset exato (o jogo lê `+0x5E` em `0x0236C154`). |
| Ponteiro IR (DPD) | `KPADEnableDPD/DisableDPD` | importados, sem HLE | stub; o 3D World não usa ponteiro IR nos menus (não verificado). |

Teclado macOS: já mapeado em `input_map.cpp`/`input_sdl.cpp` para os botões do GamePad/Pro (configurável em controls.json). Sugestão de perfis: "GamePad" (atual), "Pro", "Remote sideways" (teclas -> D-pad/1/2/A/B/+/-), "Classic".

### Imports ausentes (stubs a escrever)

| Import | Chamador guest | Ação |
|---|---|---|
| `VPADInit` | `0x0230DF8C` | stub `ret 0` explícito (hoje cai em `hle_unimplemented`, log só). |
| `WPADInit` | `0x0230DFA4` | stub; ao implementar multi-slot, marcar subsistema iniciado. |
| `WPADRegisterAllocator` | n/d | guardar alocador, não chamar. |
| `KPADEnableDPD`, `KPADDisableDPD` | n/d | stub. |
| `WENCGetEncodeData` | n/d | retornar 0/erro; só importa para o alto-falante do Remote. |
| `WPADGetSpeakerVolume` | n/d | retornar 0. |
| `ProcUIInitEx`, `ProcUIClearCallbacks` | n/d | stub; `ProcUIProcessMessages` já retorna "em primeiro plano" (`system_stubs.cpp:74`). |

Todos retornam 0 por padrão (`core.cpp:358`), então não bloqueiam; escrever HLE explícito só tira o ruído do log e dá ponto de gancho.

## 3. Multijogador (até 4) com celulares

O jogo já suporta 4 slots KPAD (laço `0x0236BAE8`, stride `0xF08`) mais o GamePad separado. O runtime só tem 1 slot de estado (`input::read()`, `PadState` único; `padscore.cpp` tem `static last` único).

Arquitetura proposta:

| Peça | Descrição |
|---|---|
| Servidor no host (macOS/Android) | HTTP + WebSocket (ou UDP) na rede local; mostra QR com URL. Cada celular abre uma página web (sem app) com botões, stick, gesto de toque, e `DeviceMotion` para giro/agitar. |
| Modelo | `input::read(slot)` com `slot 0..3`; slot 0 continua sendo teclado/controle/touch local (GamePad), slots 1..3 e extras vêm da rede. Cada celular escolhe perfil: Pro (`0x1F`, mais simples), Remote sideways (D-pad + 1/2 + sacudir). |
| HLE | `WPADProbe(chan)` devolve `0` e tipo `0x1F`/`0`/`2` se o slot está preenchido; `KPADReadEx(chan)` preenche `KPADStatus` do slot. Substituir o `static last` por array de 4 e `connected(chan)`. |
| Reconexão | `err=-2` sem amostra desconecta; o jogo já trata timer de 60 quadros (`0x02526E8C`) e tem tela de reconexão. |
| Pareamento | O jogo pergunta o número de jogadores/controles no menu (Wii Remote "1,2,3,4"); o slot do celular deve aparecer só após conexão para não bloquear o título. |
| Opção sem rede | Controles SDL físicos adicionais mapeados a slots 1..3 (`input_sdl.cpp` já abre vários, hoje funde em um `g_pad`). |
| Riscos | Latência Wi-Fi (usar UDP/WebSocket e enviar estado, não eventos); relógio do jogo roda por passes lógicos (`interp::repeat_input`), então a amostra de cada slot precisa seguir a mesma regra (`padscore.cpp:78`). Não verificado: quantos jogadores o 3D World deixa ativar só com Pro; assumido 4. |

## 4. Plano

Ordem (cada passo testável sozinho):

| # | Mudança | Arquivos / funções | Teste |
|---|---|---|---|
| 0 | Diagnóstico H1-H3 sem mudar código: rodar 60 s no título com `WWHD_PAD_TRACE`, `WWHD_LOG_BUTTONS=1`, `WWHD_NO_HOST_INPUT=1`. Se for H3, mudar `validity` ocioso para `0` (`system_stubs.cpp:173`). Se for H2, compilar fora `mods::*` do ramo sm3dw. | `system_stubs.cpp`, `input_sdl.cpp` | macOS: título fica parado ociosamente por 2 min; `A` (teclado) avança. |
| 1 | HLE explícito dos imports ausentes (tabela da seção 2) | `padscore.cpp`, `system_stubs.cpp` | log sem `[hle] unimplemented` de pad. |
| 2 | Slots: `connected(chan)` e `KPADReadEx` por canal; `last` por canal; `input::read(slot)` | `padscore.cpp:19,67-113`, `input.h`, `input_sdl.cpp:935` | teste unitário (`runtime/tools/input_map_test.cpp`); `WWHD_PRO_CONTROLLER=1` ainda funciona no canal 0. |
| 3 | Perfis KPAD: Pro (já), Remote (tipo 0), Remote+Nunchuk (1/6), Classic (2/7): preencher `devType`, `data_format`, `hold`/`ex_status` | `padscore.cpp` (novo `fill_remote`, `fill_classic`), mapeamento em `input_map.cpp` | `WWHD_KPAD_TRACE` (`padscore.cpp:103`) compara com Cemu; ver o jogo mostrar o ícone de controle correto. |
| 4 | Toque do GamePad no celular: botão para alternar para a tela do GamePad em tela cheia (`kDrcGamePad`) e mapear toque; opcional: sobreposição PiP tocável | `gfx/display_modes.cpp:278`, `gfx/vulkan/backend.cpp:3749-3801`, `TouchControls.java` | Android: tocar blocos de toque; macOS: `WWHD_TEST_TOUCH` (`display_modes.cpp:346`). |
| 5 | Giro do celular como fonte de `motion` (Android `SensorManager` -> `motion`) | `runtime/src/motion/motion.cpp`, `motion.h` (nova fonte `kPhone`), `TouchControls.java` | `WWHD_GYRO_LOG=1`, `WWHD_TEST_GYRO`. |
| 6 | Servidor de controles de celular (WebSocket) e página web; slots 1..3 | novo `runtime/src/platform/phone_pad.cpp` (+ página estática) | host + 2 celulares no título: ambos aparecem; desconectar um e reconectar. |

Como testar:

- **macOS (teclado):** `WWHD_PAD_TRACE`/`WWHD_LOG_BUTTONS` no passo 0; `WWHD_PRO_CONTROLLER=1` para o caminho KPAD; `WWHD_KEYS=1200-1210:K` para teclas roteirizadas (`input_sdl.cpp:775`); `WWHD_TEST_TOUCH` para o toque.
- **Android:** APK `dist/SM3DW-test.apk`; ver `[input] touch controls on` (`touch_pad.cpp:28`); testar botões virtuais (VPAD), depois o modo `gamepad` para toque, depois um segundo celular.

## Não verificado

- Causa real do avanço sem entrada (nenhuma execução foi feita); só hipóteses H1-H4.
- Máscara de bits do Classic/Remote que o jogo decodifica (apenas localizei `0x0236BFD0-0x0236C1F4`).
- Uso real de gyro, microfone e acelerômetro de Remote pelo 3D World; se `VPADGetTPCalibratedPointEx` é chamado (não está nos imports).
- Valor de `validity` de toque do hardware ocioso.
- Funções `0x02704670` e `0x02704808` são stubs de import (`WPADProbe` = `0x02704808` confirmado pelas chamadas; `0x02704670` não identificada).
- Comportamento real no Cemu (oráculo) do título ocioso.
