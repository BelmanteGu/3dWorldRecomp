# SM3DW: estudo de imports faltantes, áudio (AX), saves, conta/online e threads

Estudo estático (nenhum fonte editado, nada executado: sem build, sem jogo, sem adb). Base: `docs/sm3dw-study.md`,
`runtime/src/**`, `build/gen3dw/code_*.c` (recompilado de `game3dw/code/RedCarpet.rpx`). Endereços `0x02xxxxxx` são
guest (código), `0x10xxxxxx` guest (rodata/data). Scripts de apoio em `/private/tmp/claude-501/.../scratchpad`
(`diff.py`, `calls.py`, `fn.py`, `strs.py`; só leitura).

## 0. Recontagem dos imports (hoje)

- `rpxinfo.py -v`: 549 imports reais (574 do estudo anterior incluem 25 marcadores de seção `.fimport_*`).
- Casando nomes contra `HLE(lib, nome)` / `imp_lib_nome` em `runtime/src`: **196 sem implementação**, dos quais
  **8 são falsos positivos** (imports de DADOS, preenchidos em `runtime/src/main.cpp:241 init_data_imports` e
  `hle/coreinit_mem.cpp:181`): `MEMAllocFromDefaultHeap`, `...Ex`, `MEMFreeToDefaultHeap`, `__gh_FOPEN_MAX`
  (=20), `__atexit_cleanup`, `__cpp_exception_{init,cleanup}_ptr`, `_iob` (ficam zerados). Restam **188 reais**.
  Limite do método: casa só o nome (não a lib); o doc antigo dizia 243 antes de ~47 implementações novas.
- Por lib (reais faltando): coreinit 27, gx2 9, mic 8, nlibcurl 15, nn_ac 2, nn_acp 2, nn_act 10, nn_boss 3,
  nn_fp 7, nn_ndm 2, nn_olv 37, nn_save 5, nsysnet 22, padscore 6, proc_ui 2, snd_core 8, snd_user 16,
  sysapp 1, vpad 1, zlib125 5 (camera: 0 imports reais).
- Comportamento atual de um import ausente: `hle_unimplemented` (`runtime/src/core.cpp:351`) loga 3x e faz só
  `r3=0`. Isso é "sucesso" para quase toda API de status, mas **deixa buffers de saída sem escrever**
  (lixo de pilha lido pelo jogo). É a causa de risco de vários itens abaixo.

Legenda "Chamado?": contagem de call sites diretos em `build/gen3dw/code_*.c`; `(tail)` = só wrapper
`MUSTTAIL` (quem chama é via vtable/tabela, não resolvido estaticamente).

## 1. Áudio (snd_core / snd_user)

### 1.1 Arquitetura atual (`runtime/src/hle/ax.cpp`)

- 96 voices (`kMaxVoices`), frame de 96 amostras a 32 kHz (3 ms), thread de host "AX frame" (`frame_thread`,
  ax.cpp:540) que: mixa voices (`process_voices`, :275: ADPCM/PCM16/PCM8, SRC, envelope, biquad, LPF) em 4
  buses x 6 canais (TV) e 4 canais (DRC); roda callbacks aux (`run_aux_callbacks`) e de app-frame
  (`g_app_frame_cb`) via `guest_call` sob o core 0 (`threads::ensure_core`); upsample 32->48 kHz; final-mix
  callbacks; empurra para `audio::push`. Estado do VPB guest (`+0x04 state`, `+0x34 offsets`) é espelhado.
- Streaming BFSTM: voices tipo stream + `AXSetVoiceLoopOffsetEx/EndOffsetEx/GetVoiceCurrentOffsetEx` estão
  implementados; o motor lê a posição pelo VPB/`AXGetVoiceOffsets` (polling), então não depende de callback
  de voice para avançar blocos. Isso bate com o design de Cemu (ax.cpp cita seguir ax_mix/ax_ist/ax_aux).
- Threads do jogo (strings): `Hsead::AudioTaskThread` (0x1035C22F), `AudioLaunchThread` (0x1035BC6C),
  `AudioDrcVsCtrlSubCoreThread` (0x1035C36C), `nw::snd::SoundThread` (0x103A8634), `nw::snd::TaskThread`
  (0x103A8788). São `sead::Thread` (criação em `0x02386204`, entry `0x02386168`, nome `0x02386240`,
  afinidade por máscara `0x023862A4`). Arquivos: `SoundData/BgmData.bfsar`, `SoundData/SeData.bfsar`,
  `LuigiBros/snd/wiiu_shared.bfsar` (121 `.bfstm` em `game3dw/content`).

### 1.2 snd_core faltando (8): o que o jogo faz e o que devolver

Todos os 8 são chamados em dois lugares: um módulo de "voice relógio" em `0x02312E68..0x02314740` (init em
`f_02312AD8..0x0231330C`; start/stop `0x02313E84..0x02314440`; shutdown `0x02314704`) e, só para
`AXSetPpcLoadLimit`, um wrapper `0x02388AF4`.

| Import | Call sites | O que o jogo faz | Retorno correto |
|---|---|---|---|
| `AXAcquireVoice(prio, cb, ctx)` | 4: 0x02312FE0, 0x023131D8, 0x02314184, 0x02314384 (prio 0x0F, cb=0, ctx=0) | Adquire 2 voices (TV e DRC) de um relógio: loop PCM16 silencioso (buffer `0xC00` amostras zeradas em 0x02312F54), `MIXAssignChannel`, `AXSetVoiceOffsets` com `data` no struct (+0x10). Hoje devolve 0 => o ramo vai para `L_02313188` e o módulo fica sem voice | Igual `AXAcquireVoiceEx` (ax.cpp:825), mas grava o callback (não-Ex) em `vpb+0x20`; devolver o `AXVPB*` |
| `AXRegisterCallback(cb)` | 3: 0x023132C8 (registra `0x02312D74`), 0x02313F8C e 0x02314474 (restaura o anterior no shutdown/restart) | Callback por frame de áudio. `f_02312D74` roda `MIXSetInput`/`MIXUpdateSettings` e lê `AXGetVoiceOffsets` para manter posição (campo +0x30 do módulo) | Guardar 1 slot global, devolver o **callback anterior** (0 na 1a vez); chamar no `frame_thread` junto de `g_app_frame_cb`; aceitar 0 = desregistrar |
| `AXIsInit()` | 2: 0x02312F74, 0x02352AE0 | `if (!AXIsInit()) { AXInit(); AXSetDefaultMixerSelect(0); }`. Com 0 sempre, chama `AXInit` 3x (inócuo: guard `g_running`, ax.cpp:656) mas reexecuta `MIXInit`/`AXSetDRCVSMode(2)` | `g_running` (1/0) |
| `AXIsVoiceRunning(vpb)` | 4: 0x02313FC0/0x02313FF8/0x02314064/0x0231409C | Espera (`OSSleepTicks` ~3 ms em loop) a voice parar após `AXSetVoiceState(0)` | `voice(vpb)->acquired && state==1` (hoje 0 => sai do loop na hora; correto por sorte) |
| `AXSetVoiceSamplesAddr(vpb, ptr)` | 4, sempre antes de `AXSetVoiceOffsets` | Define a base de amostras | Rebase de `loop_abs/end_abs/cur_abs` preservando os offsets relativos (`base_units(fmt, novo) - base_units(fmt, velho)`), `write_offsets`; o `AXSetVoiceOffsets` seguinte (ax.cpp:902) sobrescreve de qualquer forma |
| `AXSetMasterVolume(u32)` | 2 (0x02312FB4 com 0x8000; 0x02314158 no shutdown) | Volume mestre | Guardar e aplicar como ganho `v/32768` no `output_frame` (0x8000 = unidade) |
| `AXSetMode(u32)` | 2 (arg 0) | Modo do mixer | Guardar; retorno 0 |
| `AXSetPpcLoadLimit(u32)` | 1 tail (0x02388AF4) | Limite de CPU do DSP | no-op, retorno 0 |

Risco de áudio real: **baixo-médio** para som audível (as voices de SFX/BGM do nw::snd passam por
`AXAcquireVoiceEx` @0x0266A020 com prio 0x11/0x1F e callbackEx `0x02669DC4`, que só zera `+8` do wrapper
quando a voice é "derrubada"). O que quebra sem os 8: o relógio de voice nunca roda (`AXRegisterCallback`
ausente) e `MIX*` não é mixado. Esse módulo parece medir posição de reprodução para sincronismo; se alguma
espera depende do contador que o callback atualiza, trava; **não verifiquei** nenhuma espera dessas (não vi
spin no código lido).

### 1.3 snd_user (MIX*, AXFX): todos chamados, nenhum implementado

| Grupo | Call sites | Uso | Implementação sugerida |
|---|---|---|---|
| `MIXInit/AssignChannel/ReleaseChannel/InitInputControl/InitDeviceControl/SetDeviceAux/SetInput/SetSoundMode/UpdateSettings` | só dentro do módulo-relógio (0x02312DDC..0x02314274) | Controle de mix das 2 voices relógio (que tocam silêncio) | Stub retornando 0 (ou 1); **não** mapear para mix real: as voices reais do jogo usam `AXSetVoiceDeviceMix` direto (14 sites em 0x0266A7D4+, já implementado) |
| `AXFXChorusExp{GetMemSize,Init,SettingsUpdate,Callback,Shutdown}`, `AXFXGet/SetHooks` | 0x02664920..0x02664FA4 | FX chorus do nw::snd (o `Init` volta `r31!=0` => bool) | `GetMemSize` pequeno (ex. 0x1000), `Init`/`Update`/`Shutdown` devolvem 1, `SetHooks/GetHooks` no-op. **O callback deve zerar os buffers aux** (e não deixá-los intactos): `merge_tv` soma o retorno aux ao bus principal, então um callback no-op duplicaria o sinal seco dos sends ao chorus. Alternativa: `Init` devolver 0 e deixar o nw::snd desistir do FX (não verifiquei se ele tolera) |

### 1.4 Outros achados de áudio

1. **`read_game_faders` (ax.cpp:343) usa endereços do Wind Waker** (`GD(0x1018EC64)`, +0x10, +4, faders +0x194/+0x1AC).
   Em SM3DW `0x1018EC64` cai em rodata; `guest_obj()` exige ponteiro em MEM2, então na prática `ok=false` e
   `OutputSelect::update` fica **TV only** (audio_output_mode.h). Consequência: em *Off-TV Play* (jogo manda o
   som só ao GamePad e zera o TV) o host ficaria **mudo**. Correção independente do jogo: tocar DRC quando a
   energia do mix TV é ~0 por N frames e a do DRC não, ou TV+DRC com ganho por bus. (P1.)
2. **`AXAcquireVoiceEx` não rouba voices** (ax.cpp:825-873: devolve 0 e loga "out of voices" quando as 96 estão
   tomadas). Cafe roda o `callbackEx` (aqui `0x02669DC4`) da voice de menor prioridade e a reentrega. O nw::snd
   trata `NULL` (pula o som), então é perda de SFX em cenas densas, não crash. `AXSetVoicePriority` é no-op
   (ax.cpp:1106). (P2.)
3. `AXDecodeAdpcmData` (ax.cpp:1112) só loga; é chamado de 0x02661E94 (função `0x02661D88`, usada de
   0x0266813C e 0x026738C0): decodificação ADPCM na CPU. Assinatura (struct de parâmetros, 14 bytes/frame
   em r5=0xE, saída r6) não verificada. (P2: implementar com o decodificador de `next_sample`.)
4. `AXQuit` (0x02314718) só é chamado no desligamento do módulo-relógio (0x02314704), não em jogo normal.
5. `hooks_audio.cpp` e `audio_output_mode.h` citam endereços/estruturas da WWHD; confirme que não são
   compilados no alvo sm3dw (o `sm3dw_stubs.py` só cobre símbolos do runtime).
6. Latência: o `frame_thread` pace por `audio::buffered_frames()` (ajuste ±5%) e usa `service_begin/end`
   para congelar em save state; sem relação com o relógio de voices acima.

## 2. Saves (nn_save + FS direto)

### 2.1 Onde grava e o que o jogo usa

- `config::save_dir` (core.cpp:31, "save"; `--save`); no Android o `chdir` vai para
  `Android/data/org.sm3dwrecomp.sm3dw/files` (main.cpp:391-399), logo `.../files/save`. `/vol/save/...` ->
  `<save_dir>/...` (fs.cpp:75). `SAVE*` mapeia `slot==0xFF` -> `/vol/save/common`, qualquer outro slot ->
  `/vol/save/user` (`save_path`, fs.cpp:351). `nn_act::GetSlotNo` devolve 1 (system_stubs.cpp:21) => `user`.
- A camada de save do jogo é `LuigiBros/src/LuigiBrosFiledevice207.cpp` (strings 0x1033D2C7..0x1033D870): thread de
  comandos `found, mount, initsavedir, unmount, end, save, load, filelist, delete`, função principal
  `f_02339B5C` (0x02339B5C..~0x0233B0xx). `save`: `FSOpenFile`/`SAVEOpenFile` modo `"w"` (0x1033D3A4), escreve em
  pedaços com `FSWriteFile` (0x02339EBC/0x02339F20), `FSCloseFile`. Cabeçalhos `SaveDataHeader /
  WholeSaveDataHeader / AbortSaveDataHeader` (0x1032CA48..0x1032DAA0) indicam validação/estado de aborto
  próprios. O modo (SAVE* vs FS com caminho `/vol/save/`) depende de `[obj+0xB0]` (0x02339E2C): se o jogo
  alternar entre os dois, os dois mapeamentos têm de cair na mesma pasta (hoje: SAVE* -> `user`, FS ->
  caminho literal). **Não verifiquei qual modo é o de produção**; ligar `--trace` (`TRACE("[fs] open ...")`) num
  boot com save resolve em 1 minuto.
- Nomes de arquivo do save do próprio jogo: não identificados estaticamente (montados em runtime).
  `rp_savedata.dat` (0x1032B6B4) **não** é o save do SM3DW: é o arquivo de OUTRO app lido por `SAVE*OtherApplication`.
- `0x022FC20C`: `SAVEGetStatOtherApplication` + `SAVEOpenFileOtherApplication` com title ids `00050000-10101C00/10142200/1014B600`
  (JP), `...10101D00/10142300/1014B700` (US), `...10101E00/10142400/1014B800` (EU), tabela em 0x1032B658.
  Região vem de `f_02484968` (1/2/4). Pelo padrão de ids (de memória, **não verificado**) são NSMBU / NSLU /
  NSMBU+NSLU, **não** dados do menu/Mii. O jogo lê `rp_savedata.dat` (6 offsets 0x61C, 0x820, 0xA24, 0x1234, 0x1438,
  0x163C; busca byte `==1`) e retorna 1/0: um bônus opcional por "ter save de outro jogo". Não afeta 1-1.

### 2.2 nn_save / FS faltando

| Import | Call sites | O que o jogo faz | Retorno correto / risco hoje |
|---|---|---|---|
| `SAVEGetFreeSpaceSize(client, blk, slot, u64* out, err)` | 0x026B7B74 (2º dispositivo de arquivos, `f_026B7B38`) | Guarda o status e `out` em `[obj+0x54]`; usa para checar espaço antes de salvar | Escrever `u64` BE (hi,lo) com espaço livre (ex. `statvfs` do `save_dir`, mínimo 0x40000000) e devolver 0. **Hoje: `out` não escrito => o jogo pode ver "sem espaço" e recusar salvar** (P0) |
| `SAVEGetStatOtherApplication(client, blk, titleId r5:r6, slot r7, path r8, stat* r9, err r10)` | 3 (0x022FC2A0/2F8/350) | Sonda NSMBU/NSLU | `-6` (FS_NOT_FOUND). Hoje devolve 0 (sucesso) sem preencher o stat, e segue para abrir/ler com handle de pilha (P0 por determinismo) |
| `SAVEOpenFileOtherApplication(...)` | 0x022FC388 | idem | `-6`, sem escrever handle |
| `SAVERemove(client, blk, slot, path, err)` | 0x0233A8C4 (comando `delete`) | Apagar arquivo/pasta de save | `unlink`/`rmdir` do `save_path()` mapeado; 0 ok, -6 se não existe. Hoje "sucesso" sem apagar (P1) |
| `FSRemove(client, blk, path, err)` | 0x0233A8F0 | idem (ramo FS direto) | idem |
| `SAVEGetSharedSaveDataPath(...)` | tail 0x02621DC0 | Caminho do banco de Miis do sistema (`db/FFL_ODB.dat`, 0x103A23D4; recursos `FFLResMiddle.dat` etc.) | Escrever um caminho que não existe (ex. `/vol/storage_mlc01/usr/save/...`) e devolver 0 ou erro; hoje o buffer fica sem escrever (FFL abre string vazia/lixo). `SAVEGetSharedDataTitlePath` já existe (system_stubs.cpp:210) |
| `FSChangeDir(client, blk, path, err)` | 0x02352B48 | **Ver 2.3** | **P0 de áudio** |
| `FSReadFileWithPos(client, blk, buf, size, count, pos, handle, flag, err)` | 0x0261E7D8 (wrapper `f_0261E798`, chamado de 0x026247F8 e por vtable) | Leitura posicional (sem `FSSetPosFile`) | `fseek(pos)` + `fread`, devolver `count` lido (igual `FSReadFile`, fs.cpp:247, respeitando `BlockingScope`). Hoje devolve 0 itens: leitura "vazia" |
| `FSFlushQuota` | tail 0x0261E844 | flush | devolver 0 |
| `FSGetErrorCodeForViewer`, `FSGetLastError` | 0x02339A48, 0x0261E5C8 | só log/erro | 0 |

Os handlers existentes (`FSOpenFile` etc.) já batem com as assinaturas (args 2..5 = path/modo/handle).

### 2.3 Descoberta: cwd por FSClient (`FSChangeDir`)

`f_02352AC4` (0x02352AC4, init de áudio do nw::snd) faz `AXIsInit/AXInit`, aloca **um `FSClient` próprio**
(`FSAddClient` em 0x02352B1C) e chama `FSChangeDir(client, cmd, "/vol/content/LuigiBros")` (string 0x10343510,
vizinha de `"snd/wiiu_shared.bfsar"` 0x103434F8). No Cafe o diretório corrente é **por cliente**. O runtime
trata todo caminho relativo como `<game>/content/<p>` (fs.cpp:62-77) e `FSGetCwd` devolve `/vol/content`
fixo (fs.cpp:221). Logo `snd/wiiu_shared.bfsar` (que existe em `content/LuigiBros/snd/`) resolve para
`content/snd/...` e dá `NOT_FOUND`. Os `SoundData/*.bfsar` ficam em `content/SoundData`, ou seja, o cliente de áudio
não pode usar o cwd LuigiBros para esses (provavelmente abertos por outro cliente ou caminho absoluto) —
**precisa mapear cwd por ponteiro de client** (`map<client, string>`; `FSAddClient` já recebe o ponteiro,
`FSInitCmdBlock` zera 0xA80 bytes). Implementação: `FSChangeDir` grava `cwd[client]`; `host_path_exact` recebe
o client e prefixa; `FSGetCwd` devolve o do client. Verificar com `--trace` se `snd/wiiu_shared.bfsar` aparece como
"not found" hoje (não pude executar). **Prioridade P0 para áudio.**

### 2.4 Integridade e perda de save (Android)

| Risco | Evidência | Mitigação |
|---|---|---|
| Escrita não atômica: modo `"w"` trunca o arquivo e grava em pedaços; kill do processo/queda de bateria no meio => arquivo parcial | `open_file` (fs.cpp:159), `FSWriteFile` só `fwrite+fflush` (fs.cpp:280); jogo escreve em loop (0x02339ED4) | Em `FSOpenFile/SAVEOpenFile` com `w`: abrir `<arq>.part`; em `FSCloseFile`: `fflush`, `fsync`, `rename` sobre o destino, guardar o anterior como `.bak` (o código `story_store` que faz algo parecido só vale para `cking.sav`, store.cpp:96) |
| Sem `fsync` | idem | `fsync` no close de arquivos sob `/vol/save` |
| Dados em `Android/data/<pkg>/files/save`: **somem ao desinstalar** ou "limpar dados"; `adb install -r` mantém | main.cpp:391 | Backup periódico para `getExternalFilesDir` irmão ou export (`ExportSaveActivity` já existe no Java) e aviso na UI |
| Backups existentes (`mods/save_backup.h`) só são feitos manualmente / antes de mods experimentais | save_backup.h:1-18 | Backup automático (rotação) ao fechar o jogo e ao `onPause` |
| Pause/resume: não há nada que force flush no pause | `ProcUIProcessMessages` sempre 0 (system_stubs.cpp:75) | ver 3.3 |
| `GetPersistentIdEx` sem implementação (0): o id é gravado em `[obj+0x28]` (0x0235089C) e logado | pode entrar no cabeçalho do save; constante => consistente | devolver `0x80000001` e manter fixo |

## 3. Conta, Mii, online, ProcUI, sysapp

### 3.1 nn_act (10 faltando; Result: 0 = sucesso nos testes do jogo, `bne` => erro)

| Import | Call sites | Uso | Retorno |
|---|---|---|---|
| `GetMiiName(wchar16* out)` | 0x0223602C (`f_02235E24`) | Nome do Mii da conta para o perfil/entrada de jogador | escrever UTF-16BE `"Player"` + NUL (buffer de 11+1), devolver 0. Hoje: 0 sem escrever => nome lido de pilha |
| `GetMii(FFLStoreData* out)` (0x60 bytes) | 0x02236060 | Dados Mii crus; depois `f_026273AC` (cria/guarda) | Duas opções: (a) devolver `!=0` (ex. 0xA0000000): o jogo vai para `L_0223608C` (limpa) e retorna 0 sem Mii; (b) escrever um Mii padrão com CRC16 correto. (a) é mais seguro de implementar. **Não confirmei qual UI mostra esse Mii** |
| `GetPersistentIdEx(slot)` | 0x0235089C | id da conta | 0x80000001 |
| `GetAccountId(char*)`, `GetTransferableId(Ex)`, `AcquireNexServiceToken`, `GetErrorCode`, `HasNfsAccount`, `IsNetworkAccount` | só em código online (0x024F2440, 0x02602E84.., 0x026038C4, 0x02604678, 0x02626108) | Rede/login | falha (`HasNfsAccount`/`IsNetworkAccount` = 0; `GetErrorCode` = 0) |

O jogo embute FFL (`db/FFL_ODB.dat`, `FFLRes*.dat`) mas **não importa libFFL**; Mii só aparece em Miiverse/ perfil.
Sem os arquivos de sistema do Mii, o comportamento esperado é "sem Mii" (como no Cemu sem sys files).

### 3.2 nn_fp / nn_olv / nn_boss / nn_ac / nn_ndm / nlibcurl / nsysnet / nn_acp / sysapp

- Todos só são alcançados em código de rede/Miiverse (`0x024F1xxx..0x024F5xxx` Miiverse/amigos, `0x025A8xxx..0x025B2xxx`
  nn::nex + curl + NSSL, `0x0256xxxx` sockets). **Stubar como "indisponível" é seguro, com uma exceção grave:**
- **`nn_fp::LoginAsync(cb, userdata)` (0x024F1CE4) seguido de `OSWaitEvent(0x10E09D00)` (0x024F1CF0)**: a função
  `f_024F1CC4` espera o callback `0x024F1CB0` sinalizar o evento. Hoje `LoginAsync` não chama o callback
  => a thread que chamou **dorme para sempre** (chamador: `f_024F1D08`, de 0x024F4220; em qual thread, não
  verifiquei). Correção: `LoginAsync` e `RegisterAccountAsync` agendam `cb(Result_falha, userdata)` num
  service CPU (padrão de `alarm_thread`, threads.cpp:1163) ou numa thread de host curta; `ResultToErrorCode` -> 0;
  `GetMyPrincipalId` -> 0; `GetFriendList` -> falha; `Initialize` já é ignorado pelo chamador; `IsInitialized` -> 0.
- `nn_ac::GetAssignedAddress/Subnet` (0x024F1D6C, 0x02596EA0): só depois de `Connect` ter sucesso (o HLE falha) =>
  nunca; devolver falha por segurança.
- `nn_olv` (37 faltando): `Initialize` falha (já), `IsInitialized` 0; o restante (setters/getters de posts) só roda com
  dados baixados. **Risco de spin**: o Miiverse manager pode ter uma máquina de estados que re-posta a si mesma
  (análogo ao `OliveOperationMgrThread` da WWHD: `hook_0203DEEC`, system_stubs.cpp:57, endereço Zelda, inútil
  aqui). Procurar com `WWHD_THREAD_DUMP`/`WWHD_SCHED_STATS=2` (já existem) uma thread a ~100% de CPU com `[sched]
  run 100%` parada em 0x024F2xxx..0x024F5xxx. **Não verificado.**
- `nn_boss` (`Unregister` 0x025314DC, `TaskResult::ErrorCode` 0x02531488): só com Task registrada; `Initialize`/`Register` já falham.
- `nn_ndm::ResumeDaemons/Finalize` (0x02594820/0x02594824): shutdown; stub 0.
- `nlibcurl` (15 faltando; `curl_easy_setopt` 38 sites, `curl_multi_perform` 2) e `nsysnet` (22): só dentro do nex
  (0x0256xxxx/0x025Axxxx). `curl_global_init_mem`, `socket_lib_init`, `NSSLInit` já retornam 0; sem `Connect`
  nenhum handle é criado. Stub = `curl_easy_init` -> NULL (0), `socket` -> -1, `socketlasterr` -> 0.
- `nn_acp::ACPMiiWrap/Unwrap` (0x0262A5C0/0x0262A61C): conversão Mii p/ Miiverse; stub falha.
- `sysapp::_SYSGetSystemApplicationTitleId` (tail 0x02621D88): só ao lançar outro app; `SYSLaunchSettings` (0x0230FAA0,
  menu de pausa) já existe.
- `mic` (8): `sead::MicDevice::init` (0x02351F00): `MICInit(…, &size=0xC00, &err)` com **resultado por out-param**
  (`[r1+8]` testado em 0x02351F48). Sem implementação o out-param é lixo: se por acaso 0, segue como "mic
  presente" e chama `MICGetStatus/Open` (0x02351F6C/0x02351F94) e depois `MICGetState`x12 (0x02352074...) numa
  thread `sead::MicConnectThread` (0x103B260F). Correção: `MICInit` escreve `*err = (u32)-6` (qualquer erro) e devolve -1;
  `MICUninit/Close` 0. Recurso de sopro no microfone fica indisponível (não verifiquei se o jogo usa em 1-1).
  `camera`: nenhum import real.

### 3.3 proc_ui, `OSEnableForegroundExit`, sysapp (ciclo de vida / pausa Android)

- Atual (system_stubs.cpp:70-75): `ProcUIInit/Shutdown/RegisterCallback/DrawDoneRelease` no-op,
  `ProcUIProcessMessages` sempre `0` (IN_FOREGROUND). `ProcUIInitEx` e `ProcUIClearCallbacks` faltam, ambos
  chamados uma vez (0x0233B330 com callback de save `0x0233B2CC`; 0x022FC1C8 no shutdown) e inócuos.
- Dois loops de mensagens: `f_0233B360` (0x0233B3FC/0x0233B400: `ProcUIDrawDoneRelease(); ProcUIProcessMessages(1)`)
  e `f_024DC...` (0x024DC334). Estados tratados pelo jogo: `3` => ramo de saída (`f_0233B354`), `2`
  (RELEASING) => limpa flag em `0x10890000+0x2870+0x38` e chama o vtable `+0x54(heap, 0)` (libera GX2), de volta
  ao foreground chama `+0x54(…, 1)`. O jogo registra 3 callbacks em 0x024DC198/0x024DC1B0/0x024DC1C8 (que o
  runtime ignora) e usa `OSSavesDone_ReadyToRelease` (0x0233B2D8, 0x024DC078; já existe, no-op).
- **Para pausa/retomada no Android** (hoje o jogo nunca sabe que foi para segundo plano; o backend Vulkan só
  trata `SDL_EVENT_WILL_ENTER_BACKGROUND`, gfx/vulkan/backend.cpp:1436): implementar máquina de estados:
  fundo => `ProcUIProcessMessages` devolve 2 uma vez (chamando antes os callbacks `ProcUIRegisterCallback` de tipo
  RELEASE e esperando `OSSavesDone_ReadyToRelease`), depois 1 bloqueando em `BlockingScope` até voltar ao primeiro
  plano (devolve 0 e chama os callbacks ACQUIRE); `onDestroy` => 3. Mínimo viável: no `onPause` fazer flush/
  fsync dos saves e silenciar `audio::push` (hoje continua rodando guest e áudio em segundo plano).
- `OSEnableForegroundExit` (0x023513E4), `OSSetScreenCapturePermission` (0x0230F9C8), `IMIsAPDEnabledBySysSettings`
  (0x021306BC): chamados 1x no init; no-op/0 é correto. `OSEnableHomeButtonMenu` já existe (coreinit_misc.cpp:332).

## 4. coreinit: lacunas (27 reais) e se são alcançadas

| Import | Call sites | Alcançado em jogo normal? | Implementação / risco |
|---|---|---|---|
| `MEMInitBlockHeap(hdr, start, end, track, tsize, flags)` | 0x02351BE8 (`f_02351B30`) | **Sim, no boot**: cria o "main" heap do sead (nome em 0x10343498; vtable 0x10343478 com alloc `0x02351DA8`, free `0x02351E1C`) sobre MEM2 (`MEMGetBaseHeapHandle(1)`, resto do frm heap) e o bucket (arena 8) | Devolver `hdr`; manter um alocador exp/frm sobre `[start,end)` (reusar `create_exp/exp_alloc`, coreinit_mem.cpp) |
| `MEMAllocFromBlockHeapEx(heap, size, align)` | 0x02351DFC (`f_02351DA8`, via vtable) | Sim, é o `alloc` do heap acima | `exp_alloc(heap, size, align)`; hoje devolve **NULL**. Não sei explicar por que o jogo chegou ao título (talvez esse heap só atenda parte das alocações, ou o log do estudo foi de antes). **Verificar primeiro.** |
| `MEMFreeToBlockHeap` | tail 0x02351E20 | Sim (free) | `exp_free` |
| `MEMAddBlockHeapTracking(heap, mem, size)` | 0x02351C28 | boot | devolver 0 |
| `MEMGetTrackingLeftInBlockHeap(heap)` | 0x02351DD0 | só em log (OSReport) | grande (0x1000) |
| `MEMFreeToFrmHeap` | 0x022FC1B0, 0x022FC1C0 | só shutdown | `frm_free`/reset |
| `OSIsThreadTerminated(thr)` | 0x02339768 (`f_02339758`: `isRunning = !terminated` do thread do filedevice em obj+0xB8) | Sim (parar/aguardar a thread de save) | `ht->exited` / `kState==MORIBUND` (threads.cpp:`thread_main`). Hoje 0 => "ainda rodando" sempre: risco de **espera infinita** ao encerrar/desmontar (P1) |
| `OSSetThreadPriority(thr, prio)` | tail 0x02567524 (wrapper do sead::Thread) | Sim (sead) | Atualizar `HostThread::prio`, `kBasePrio/kEffPrio`, forçar `g_core_preempt`; devolver 1 |
| `OSInitCond/OSWaitCond/OSSignalCond` | 0x025A873C / 0x025AB6BC / 0x025AB524 | Só no nex (rede) | `HCond` com cv: `OSWaitCond(cond, mutex)` solta o mutex inteiro (count), espera, readquire; `Signal` acorda todos (semântica Cafe) |
| `OSCancelAlarms(tag)` | 0x02588248 | Raro (nex) | Cancelar todos os alarmes com `ld32(alarm+0x10)==tag` (`OSSetAlarmTag` não é importado => tag 0) |
| `OSReadRegister16/OSWriteRegister16/__OSRead/WriteRegister32Ex/OSEnforceInorderIO/OSDriver_Register/bspGetHardwareVersion` | biblioteca de registradores/driver 0x026ABE74..0x026AC998 (OSEnforceInorderIO x15, OSReadRegister16 x4, `__OSReadRegister32Ex` x9) | Não identifiquei o chamador de topo (init `f_026ABE74`) | Stub 0; `bspGetHardwareVersion` deve preencher o out (`[r3]`=0) senão o jogo lê lixo (0x026AC994) |
| `FSChangeDir`, `FSReadFileWithPos`, `FSRemove`, `FSFlushQuota`, `FSGetErrorCodeForViewer` | ver 2.2 | Sim | ver 2.2 |
| `MEMAllocFromDefaultHeap*`, `MEMFreeToDefaultHeap`, `_iob`, `__cpp_exception_*` | — | dados (ok) | já tratados |

Observação: o `FSSetCmdPriority`, `FSSetStateChangeNotification`, `FSGetVolumeState` do runtime estão ok.

## 5. Threading / scheduler: sync primitives vs. Cafe OS

Importados e usados: `OSInitEvent/Signal/Reset/Wait/WaitWithTimeout`, `OSInitMutex/Lock/TryLock/Unlock`,
`OSInitMessageQueue/Send/Receive`, `OSInitRendezvous/WaitRendezvous`, alarmes (`OSCreateAlarm/SetAlarm/SetPeriodicAlarm/
CancelAlarm`), `OSCreateThread/Resume/Join/Exit/SetThreadName/SetThreadAffinity/Specific`, `OSYieldThread`,
`OSSleepTicks`, `OSDisable/RestoreInterrupts`. **Não importados** (portanto irrelevantes): `OSSleepThread`,
`OSWakeupThread`, `OSSuspendThread`, semáforos (`OSInitSemaphore...`), `OSInitCond` fora do nex. Em
`threads.cpp` `os_wakeup_thread_queue` (:1091) nunca é chamado e é código morto para este jogo.

| # | Ponto | Código | Semântica Cafe / hazard | Prioridade |
|---|---|---|---|---|
| H1 | **Sem herança de prioridade** em `OSLockMutex` | `mutex_lock` (threads.cpp:~885) e escalonador estrito por prioridade (`should_yield`, :~265) | Cafe eleva a prioridade do dono do mutex. Aqui um thread de baixa prioridade segurando um `sead::CriticalSection` pode ficar **faminto** na mesma core por um thread de prioridade média que não bloqueia (round-robin de 500 us só vale para prioridades iguais). Casos prováveis: threads de carga/decomp (`HDecompThread`, `Prepare Thread`) vs. audio/main na mesma core | P1 |
| H2 | Despertar por **FIFO**, não por prioridade | `OSSignalEvent` auto-reset acorda `waiters.front()` (:~985); fila de mensagens usa `notify_all` e quem ganhar a corrida; mutex idem | Cafe acorda o waiter de maior prioridade. Para `nw::snd::TaskThread`/`SoundThread` (poucos waiters) sem efeito; com muitos workers pode inverter ordem | P2 |
| H3 | `OSSetThreadPriority` ausente (ver 4) | — | sead muda prioridades em runtime | P2 |
| H4 | Callbacks de alarme e do AX rodam **num único host thread cada** | `alarm_thread` (:1163), `frame_thread` (ax.cpp:540) | Um callback que bloqueia (OSWaitEvent, `OSSendMessage` bloqueante, lock de mutex disputado) **congela todos os alarmes / o áudio** (no Cafe também não pode bloquear, mas o `guest_call` aqui toma o core 0 com prioridade -1: se a thread dona de um mutex estiver preemptada por ele há inversão). Os callbacks do jogo vistos (`0x02312D74`) não bloqueiam | P2 |
| H5 | `OSWaitRendezvous` usa máscara de cores e `r->arrived` só zera em `OSInitRendezvous` | threads.cpp:1115 | Corrigido (era o crash do boot). Se o jogo reinicia o mesmo rendezvous sem `OSInitRendezvous`, o bit de core fica "armado" | P3 |
| H6 | `OSSetThreadAffinity` mudando o core "no próximo schedule" | :814 | sead chama depois de `OSCreateThread` (0x023862A4) e antes do resume => ok; máscara `7` ou `0` cai no core do criador (`core_from_affinity`, :99): threads "any core" do sead ficam no core do **criador**, podendo juntar todos na core 1 e agravar H1 | P2 |
| H7 | `OSYieldThread` = `BlockingScope` + `std::this_thread::yield` | :795 | Solta o core e recoloca no fim da fila: ok para espera ocupada de mesma prioridade; não cede a prioridades menores (igual Cafe) | ok |
| H8 | `OSWaitEventWithTimeout`/`OSSleepTicks` usam relógio de host com `game_clock::rate()` | :1006 | ok; sob `fast_forward` usam espera virtual de 1 ms | ok |
| H9 | Thread que **termina** (`OSExitThread`) não libera mutexes que mantém, e `OSJoinThread` espera `exited` | :777, :781 | Igual Cafe (mutex dono morto trava); `OSIsThreadTerminated` ausente (ver 4) | P1 |
| H10 | Spin do Miiverse | ver 3.2 | Se existir, compete com o core do jogo | P2 (a verificar) |

Todos os waits passam por `park_wait`/`BlockingScope`, que liberam o core virtual (`block_begin`), então
**deadlock puro por core ocupado** não aparece nas primitivas lidas; o fix do rendezvous é o único caso
conhecido de espera ignorando core.

## 6. Backlog priorizado (stage 1-1 jogável, com som e saves)

| # | Item | Esforço | Por quê / critério de pronto |
|---|---|---|---|
| 1 | **Rodar com `--trace`/`WWHD_AX_STATS=1`/`WWHD_THREAD_DUMP=5` e capturar** o log de `[hle] unimplemented`, `[fs] open ... not found`, threads a 100%. | S | Valida/derruba as hipóteses marcadas "não verifiquei" (heap, cwd por client, spin do Miiverse). Sem log novo no repo (`captures/wwhd.log` é de teste do ELF Zelda) |
| 2 | **Block heap**: `MEMInitBlockHeap/AllocFromBlockHeapEx/FreeToBlockHeap/AddBlockHeapTracking/GetTrackingLeftInBlockHeap` sobre `coreinit_mem.cpp` | S/M | Alocação do heap "main" do sead deixa de devolver NULL |
| 3 | **`FSChangeDir` por client + `FSGetCwd`** (fs.cpp:62,221) | S | `snd/wiiu_shared.bfsar` abre; trace sem "not found" no áudio |
| 4 | **snd_core**: `AXAcquireVoice`, `AXRegisterCallback` (+ chamada no `frame_thread`), `AXIsInit`, `AXIsVoiceRunning`, `AXSetVoiceSamplesAddr`, `AXSetMasterVolume` (ganho), `AXSetMode`, `AXSetPpcLoadLimit`; `snd_user` MIX* stubs | S | O módulo relógio de voice inicializa e roda; sem log de unimplemented em AX |
| 5 | **Saves**: `SAVEGetFreeSpaceSize` (escreve u64), `SAVEGetStat/OpenFile OtherApplication` = -6, `SAVERemove`/`FSRemove`, `FSReadFileWithPos`, `OSIsThreadTerminated`, `SAVEGetSharedSaveDataPath` | S | Salvar/carregar/apagar funciona, sem "sem espaço" falso, sem handle de pilha |
| 6 | **Escrita atômica** de `/vol/save` (`.part`+fsync+rename+`.bak`) | M | Kill no meio do save não corrompe; teste: matar o processo durante 1000 saves |
| 7 | **nn_fp `LoginAsync`/`RegisterAccountAsync` chamam o callback com falha**; `nn_act` `GetMiiName/GetMii/GetPersistentIdEx`; demais nn_* como falha | S | Nenhuma thread dorme em `OSWaitEvent(0x10E09D00)`; nome "Player" no perfil |
| 8 | **zlib125** (`inflateInit2_/inflate/inflateEnd/compress/uncompress`) com o `ZLIB::ZLIB` já linkado | M | Callers em 0x0234F104/0x0234F120 (descompressor com `DCFlushRange`, thread de decomp) e 0x02627114.. : marshalling BE do `z_stream` (next_in 0, avail_in 4, total_in 8, next_out 0xC, avail_out 0x10, total_out 0x14, msg 0x18, state 0x1C, zalloc 0x20, zfree 0x24, opaque 0x28, data_type 0x2C, adler 0x30; 0x38 bytes) guardando um `z_stream` de host por endereço guest. Não confirmei que a fase 1-1 chega a usar |
| 9 | **Saída DRC/TV sem endereços Zelda** (ax.cpp:343): decidir pela energia dos mixes | M | Off-TV Play não fica mudo |
| 10 | **ProcUI** (estados 0/1/2/3 + callbacks) ligado a `SDL_EVENT_WILL_ENTER_BACKGROUND/FOREGROUND` e ao `onPause` do Android | M | Pausa/retomada sem rodar guest nem áudio; flush de saves no fundo |
| 11 | `OSSetThreadPriority`, herança de prioridade no `OSLockMutex` (H1), acordar por prioridade (H2) | M | Menos fome de threads de baixa prioridade |
| 12 | **Voice stealing + callbackEx** em `AXAcquireVoiceEx` (ax.cpp:825) e `AXSetVoicePriority` real | M | SFX mantém prioridade em cenas densas |
| 13 | `AXFXChorusExp*` (callback que zera aux), `AXDecodeAdpcmData`, MIC (erro limpo), `OSInitCond/WaitCond/SignalCond`, `OSCancelAlarms`, `bspGetHardwareVersion` | S cada | Cobertura de bordas; rede só se for ligar online |
| 14 | Stubs finais: nlibcurl/nsysnet/nn_olv/nn_boss/nn_ac/nn_ndm/nn_acp/sysapp (retorno falha) | S | Elimina ruído de log; nenhum afeta jogo normal |

Ordem sugerida de execução: 1 -> (2, 3, 4, 5 em paralelo, todos S) -> 7 -> 6 -> 8 -> 9 -> 10 -> restante.

## 7. O que NÃO consegui verificar

- Nada foi executado: sem log de boot do SM3DW no repo; todo "chamado em jogo normal" vem de call sites estáticos
  e leitura do código recompilado, não de execução. Importações chamadas por `vtable` (ex.
  `FSReadFileWithPos`, `MEMAllocFromBlockHeapEx`) têm o chamador de topo não resolvido.
- Por que o jogo chegou ao título com block heap/`AXAcquireVoice` ausentes; qual thread chama `f_024F1D08`
  (login síncrono do fp); quem chama a função de `OSIsThreadTerminated` e se ela espera em loop.
- Identidade dos title ids `10101C/D/E00`, `10142200/300/400`, `1014B600/700/800` (inferida de memória como NSMBU/NSLU) e o
  que o bônus de `rp_savedata.dat` desbloqueia.
- Nomes/layout dos arquivos de save do SM3DW e qual ramo (SAVE* vs FS direto, `[obj+0xB0]`) é o de produção.
- Se o jogo usa o microfone no 1-1; se o nw::snd tolera `AXFXChorusExpInit` = 0; assinatura exata de
  `AXDecodeAdpcmData`; se o Cemu chama callbacks de voice além do "drop"; se zlib é usado em 1-1.
- Prioridades/afinidades efetivas das threads do jogo (os valores vêm de campos do objeto `sead::Thread`
  em runtime; `[thread] start "..." core N prio P` no log mostraria).
- Comportamento real do `hooks_audio.cpp` e demais hooks Zelda no alvo sm3dw.
