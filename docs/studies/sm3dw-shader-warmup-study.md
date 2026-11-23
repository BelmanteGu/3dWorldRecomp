# SM3DW: por que o head start Vulkan não ajudou e como fazer um passo "Preparing shaders"

Estudo somente leitura (nenhum código-fonte alterado; sem build, jogo ou adb). Branch `sm3dw`. Caminhos relativos a `runtime/src/` salvo indicação. **Não verifiquei nada no aparelho**: tudo abaixo vem do código e dos arquivos em `build/vkcache/`.

## 0. Conclusão curta

1. **O `headstart_vk.bin` foi ignorado silenciosamente por construção.** Medi o arquivo: registros tipo 1 (1.036), 2 (3.787), 3 (5.593) e 5 (1.387), **zero tipo 4**. O caminho de jogo normal (thread "Vulkan warmup") só lê o **tipo 4** e descarta todo o resto sem log (`gfx/vulkan/pipeline_worker.cpp:220-223`, `:266`). Os tipos 1/3/5/6 só são interpretados no modo `--warm-shaders` (`gfx/vulkan/headstart.cpp:158-209`), que o jogo normal nunca executa.
2. **O tipo 4 não pode vir do template**: carrega SPIR-V local e o fingerprint do aparelho+driver+**commit do build** (`backend.cpp:256-276`; checado em `pipeline_worker.cpp:234`). Só é gerado no aparelho (`WWHD_VK_RECIPE_RECORD`, `headstart.cpp:104-115`, ou o arquivo `.recipes` local, que usa o mesmo formato).
3. **A rota certa é o modo warm no aparelho**: o template (tipo 5, sem código) + jogo extraído -> `shaderprep.py build` -> `wwhd --warm-shaders` compila tudo, grava o cache nativo do driver, o cache SPIR-V e o `.recipes` com o fingerprint do próprio build. Toda essa infraestrutura já existe (seção 4) e só está desligada no APK de teste do SM3DW.
4. Mesmo com recipes certos, **o worker em jogo é um otimizador frágil** (retém só 256 pipelines no Android, pausa quando o compilador roda, exige SPIR-V já pronto). Para o stall da primeira cena o que resolve é pré-compilar **antes** do jogo, com N threads, não aquecer em segundo plano.

## 1. Como o Vulkan consome `headstart.bin`

| Etapa | O que faz | Evidência |
|---|---|---|
| Caminho | `WWHD_HEADSTART` ou `<game_dir>/shadercache/headstart.bin` | `backend.cpp:279-282` |
| Condição para a thread existir | `pipelineCachePath` não vazio **e** `WWHD_VK_PIPELINE_WARMUP` != `0` | `backend.cpp:256`, `:277-278`; `pipeline_worker.cpp:326` (`path.empty()` retorna) |
| Fingerprint (48 B) | vendorID, deviceID, driverVersion, UUID do cache, hash de features + `build::commit()` + `build::version()` + `WWHD_PIPELINE_RUNTIME_ID`, limite de UBO dinâmico, schema, flags de portabilidade | `backend.cpp:257-276` |
| Leitura do head start | percorre todos os registros; **só o tipo 4 é lido** (`type != 4` -> `fseek` e `continue`); tipos fora de 1..6 abortam | `pipeline_worker.cpp:216-223` |
| Tipo 4 com fingerprint diferente | `continue` sem erro e sem log | `pipeline_worker.cpp:234` |
| Nenhuma receita carregada | `if (loaded.empty()) { w.result = {0, failed}; return; }`: **sem nenhuma linha de log** | `pipeline_worker.cpp:266` |
| Receitas locais | `<cache>.recipes`, mesmo fingerprint, via `decode_pipeline_recipes` | `pipeline_worker.cpp:203` |
| Limites | `headstart_limits.h` está em `gfx/headstart_limits.h` (não em `gfx/vulkan/`): 2 GiB, 4 M registros, 16 MiB/registro; `kPipelineRecipeMaxCount` limita receitas | `gfx/headstart_limits.h`; `pipeline_worker.cpp:216`, `:236` |

Consequências para o arquivo testado:

| Tipo | Quem lê no jogo normal | Quem lê em `--warm-shaders` | Presente em `headstart_vk.bin` |
|---|---|---|---|
| 1 shader / 3 especulativo / 6 shader ligado | ninguém | `headstart.cpp:211-241` (traduz e salva SPIR-V) | 1.036 / 5.593 / 0 |
| 2 Metal | ninguém | contado e pulado (`:209`) | 3.787 (lixo para Vulkan) |
| 4 banco Vulkan (local) | `pipeline_worker.cpp:220` | contado, "owned startup worker validates" (`:210`) | **0** |
| 5 estado sem código (WWVKST01) | ninguém | `decode_code_free_pipeline_recipe` -> resolve shaders por `find_pipeline_shader` -> `warm_resolved_pipeline_recipes` (`:208`, `:243-296`) | 1.387 |

Escrita: `shaderprep.py cmd_build` emite `5` para pipelines Vulkan do template (`tools/shaderprep.py:1045`, `:882`); `REC_VULKAN_PIPELINE = 4` só aparece como **entrada** (um `.recipes` local, `shaderprep.py:437-449`). O comentário do cabeçalho confirma: "Build resolves program hashes from the player's own archives and writes a LOCAL code-carrying headstart" (`shaderprep.py:54-57`).

**Pergunta 1b: o template gera registros que o loader aceita?** Sim, mas só os **tipo 5 para o modo warm**; nunca o tipo 4. O loader do jogo normal exige registros produzidos no aparelho com o fingerprint do build. O modo warm (`main.cpp:620-628`, `gfxvk::warm_headstart_shaders`) é quem converte tipo 5 em pipelines reais no aparelho e, ao fim, chama `save_pipeline_cache()` (`headstart.cpp:288`) que grava o cache nativo e o `.recipes` com o fingerprint certo (`pipeline_worker.cpp:378-394`). No próximo start normal, as receitas locais (tipo "local recipes", `read_recipes(w,false)`) entram no worker.

Pontos que ficam em aberto **[não verificado]**:
- No SM3DW, `warm_resolved_pipeline_recipes` só aceita uma receita se `find_pipeline_shader(id)` achar VS e PS traduzidos, descritores iguais e `uniformCompatible` (`headstart.cpp:249-283`). A taxa de "unresolved" para os 1.387 pipelines depende de os shaders do template existirem nos 8.608 do arquivo; a linha `[vulkan state warmup] identity resolution: absent VS ... PS ...` (`:287`) diz isso, mas é `fprintf(stderr)`.
- Os caminhos precisam coincidir: o warm e o jogo têm de usar o mesmo `pipelineCachePath`. Com `WWHD_VK_PIPELINE_CACHE_DIR` o caminho vem de `pipeline_cache_override` (`backend.cpp:192-199`); sem a variável, no Android vai para `drivers::pipeline_directory()` (`:205-216`). No fluxo oficial `bind_live_caches` define os dois lados (`gfx/shader_prebuild.h:419-432`); em teste manual é fácil divergir.

## 2. Por que o stall da primeira cena continuou

| Hipótese | Veredito | Evidência |
|---|---|---|
| Worker pré-criou os pipelines e o draw os reutilizou | **Não neste teste**: nenhuma receita foi carregada (seção 1). Mesmo no caso feliz há 3 travas abaixo | |
| Chave igual entre worker e draw | Provável. A chave do draw troca `vs/ps` pelo `pipelineId` portátil e compara bytes (`draw.cpp:773-777`); o worker indexa por `identity(recipe->key)` (`pipeline_worker.cpp:289`) e `take_warm_pipeline` ainda revalida SPIR-V, descritores e o **estado fixo** completo (`:351-354`) | `draw.cpp:773-777`, `:1073-1074` |
| Trava A: só 256 pipelines retidos no Android | **Sim**. `maxUnusedWarmPipelines = 256` (`:70`) e, cheio, destrói uma vítima **arbitrária** (`unordered_map::begin()`, `:76-78`). Com 1.387 receitas, a maior parte do que o worker compilou é descartada antes de a cena pedir | `pipeline_worker.cpp:69-80` |
| Trava B: worker cede ao compilador de demanda | **Sim**. Enquanto `pipeline_demand_active() || shader_compiler_active()` ele dorme em passos de 1 ms (`:285-288`). Na carga de cena os dois estão ativos o tempo todo, então o aquecimento praticamente para quando mais importa | `pipeline_worker.cpp:283-288` |
| Trava C: só tenta o worker se o SPIR-V já está em memória | **Provável** (precisa teste). `take_warm_pipeline` só é chamado se `!recipeSpirv[0].empty() && !recipeSpirv[1].empty()` (`draw.cpp:1073`). Num start com cache SPIR-V vazio, o shader acabou de ir para o tradutor assíncrono (`shaders.cpp:1068-1086`, `shader->binary`) e `vs->spirv` ainda está vazio; o draw vai direto ao compilador de demanda | `draw.cpp:1073`, `shaders.cpp:43`, `:1062-1086` |
| Compilador de demanda é gargalo | **Sim**: uma thread (`pipeline_demand.cpp:59`, `:137`), fila máx. 8 (`:225`). A 113 ms/quadro de pipeline e 100+ pipelines novos, são segundos em série. Sem receitas, não há nada a reaproveitar | `pipeline_demand.cpp:225` |
| Admissão bloqueia o draw | **Sim**. Com fila cheia: `finish_exact()` e `build_pipeline_async` em laço de 1 ms; o único alívio é `presentWhilePending` (reapresenta o quadro anterior) | `draw.cpp:1115-1134`; `pipeline_demand.cpp:233-264` |
| Espera real do draw | Em modo "exato" a espera é adiada para o replay (`emit_owned`), então o bloqueio acontece na thread de execução, não na de emissão | `draw.cpp:2314`, `:2426-2428` |
| Tradução GX2->SPIR-V também pesa | **Provável e não medido**: `shader_compiler.cpp` é outra thread única ("Vulkan SPIR-V"). O perfil `pipeline 113 ms/frame` (`R.pipelineCreateNs`) soma só `vkCreateGraphicsPipelines`, não a tradução | `shader_compiler.cpp:94-110`; `draw.cpp:1125` |

Por que o segundo start cai para ~1 s: não é o worker, é o cache nativo do driver (`VkPipelineCache` semeado em `backend.cpp:240-250` e no compilador de demanda, `pipeline_demand.cpp:77-84`) mais o `spirv.bin` em disco. O fix `checkpoint_pipeline_demand` (`pipeline_demand.cpp:278-285`, `backend.cpp:386-396`) é o que faz esse cache sobreviver.

## 3. Logs visíveis e o que registrar

Causa: `log_msg` (macro `LOG`) escreve em `__android_log_vprint(..., "wwhd", ...)` no Android (`core.cpp:62-66`), mas `fprintf(stderr, ...)` não chega ao logcat. Os 12 `fprintf(stderr` de `gfx/vulkan/pipeline_worker.cpp` e os 9 de `gfx/vulkan/headstart.cpp` (`[vulkan warmup]`, `[vulkan state warmup]`, `[vulkan headstart]`) ficam invisíveis; só as linhas via `LOG` (`[vulkan cache]`, `[vulkan warmup] could not start worker`, `backend.cpp:250-282`) aparecem. Por isso "nenhuma linha `[vulkan warmup]`" **não** prova que o worker não rodou.

Mudança mínima (não aplicada): em `pipeline_worker.cpp` e `headstart.cpp`, trocar `std::fprintf(stderr, "...\n", ...)` por `LOG("...", ...)` (sem `\n`; `runtime.h:159-161` já está incluído em `headstart.cpp`, falta em `pipeline_worker.cpp`). Alternativa sem código: `adb shell setprop log.redirect-stdio true` + reiniciar o app (não testei; depende da versão do Android).

Linhas novas para verificar (todas com `LOG`):

| Onde | Linha | Responde |
|---|---|---|
| `pipeline_worker.cpp:257-266` | `head start: arquivo=%s tamanho=%zu registros por tipo 1/2/3/4/5/6=... tipo4 aceitos=%zu fingerprint_diferente=%zu` | o arquivo foi aberto? o tipo 4 existe e casa? |
| `:266` (antes do `return`) | `warmup: 0 receitas (locais=%zu, headstart=%zu)` | elimina o caminho silencioso |
| `:281` | já existe: `exact pipeline recipes queued` | worker começou |
| `:299-307` | contadores `ready`, `evictions` e `paused_ms` (tempo dormindo no laço de `:285`) | prova as travas A e B |
| `take_warm_pipeline` (`:332`) | a cada 100 chamadas: `hit/miss/no_ready/mismatch` | prova a reutilização |
| `draw.cpp:1073` | contador de vezes em que `recipeSpirv` estava vazio | prova a trava C |
| já existe | evento `startup_pipeline_hit` (`draw.cpp:1081-1084`) e `startup_pipeline`/`startup_warmup` (`pipeline_worker.cpp:182`, `:250`) em `WWHD_VK_STUTTER_TRACE=<arquivo>` | contagem de acertos sem alterar código |

## 4. Projeto: passo "Preparing shaders" na primeira execução

### O que já existe (e o que falta no SM3DW)

| Peça | Estado | Evidência |
|---|---|---|
| UI de progresso | Existe: `ShaderPrepareActivity` (processo `:shaderprep`, SDL isolado), texto "Preparing shaders for this Android device: N% (detalhe)", lê `cache/shader-warm-progress.txt` a cada 500 ms, Back pula | `android/.../ShaderPrepareActivity.java:10-35`, `:73-83`; `AndroidManifest.xml:76` |
| Progresso nativo | `warmprogress::report` escreve `WWHD-PROGRESS` e o arquivo `WWHD_WARM_PROGRESS_FILE` (<=10 Hz) | `gfx/warm_progress.h:25-40` |
| Argumentos do modo warm | `--warm-shaders --shader-prebuild-root ... --shader-prebuild-result ...` | `ShaderPrepareActivity.java:57-65`; `main.cpp:431`, `:596-632` |
| Orquestração | `SetupService` roda `shader_prebuild.prepare` (gera `headstart.bin` por geração) e, se `warm_pending`, abre a activity | `SetupService.java:197-229`; `tools/installer/shader_prebuild.py:361-483` |
| Overlay/fluxo de setup | `overlay/setup_flow.h` não existe neste repo (**não encontrei**); o fluxo é o Java acima | busca em `runtime/src` |
| No APK do SM3DW | **Desligado**: o manifest diz "setup is not used", o jogo (`WwhdActivity`) é o único ícone; os dados vão por `adb push` | `AndroidManifest.xml:40`, `docs/sm3dw-study.md` (tabela Android) |
| Template no APK | **Não existe**: `grep template` em `android/app/src/main/java` não acha; `tools/shadercache/` só tem `README.md` e `template.ref` | `ls tools/shadercache` |

### Desenho proposto

1. **Gatilho.** No primeiro `WwhdActivity.onCreate` (ou antes, na própria tela de início do SM3DW), se não existir `shader-prebuild/active.json` pronto para a identidade (aparelho+driver+commit+template), iniciar `ShaderPrepareActivity` e voltar ao jogo ao terminar (resultado em `--shader-prebuild-result`). Back = pular (já implementado, grava `skip.json`).
2. **Etapa A, montar o head start local** (CPU, Python embutido): `shaderprep.py build <gen>/headstart.bin --game <game> --template <template_vk.bin> --progress` (`shader_prebuild.py:473`). Saída: tipos 1/6 (shaders do jogo) + 5 (estados do template). Proporção da barra: 35% (`ASSEMBLY_SHARE`).
3. **Etapa B, pré-compilar tudo** no processo isolado `:shaderprep` com `--warm-shaders`. `warm_resolved_pipeline_recipes` já usa até `min(4, cores-2)` threads (`pipeline_worker.cpp:449-455`); a tradução de shaders em `headstart.cpp:211-241` é **serial** (uma thread). Melhorias, em ordem de ganho: (i) paralelizar a tradução (N threads, o tradutor tem estado global em `vk::translate`, **verificar segurança de threads antes**); (ii) subir o teto de 4 para `cores-1` no modo warm (nada mais roda); (iii) prioridade normal, sem nice, já que a tela é só progresso.
4. **Persistência.** Ao fim, `save_pipeline_cache()` grava cache nativo, `.recipes` e `spirv.bin` (`headstart.cpp:288-293`). O jogo normal usa o mesmo `pipelineCachePath` (`bind_live_caches`, `shader_prebuild.h:419-432`): garantir `WWHD_VK_PIPELINE_CACHE_DIR`/`WWHD_VK_SHADER_CACHE` iguais nos dois processos. Como o fingerprint inclui o commit do build, **cada APK novo repete o passo** (esperado; ~minutos).
5. **Reprodutibilidade do progresso.** Contar etapas por tipo (shaders = traduzidos; pipelines = compilados) já existe via `progressTotal` (`headstart.cpp:177-185`); exibir "N de M" no texto da activity (já exibe o detalhe).
6. **Corte de tempo (opcional).** Se o primeiro `warm` passar de X min, o usuário toca em Back e o jogo continua com compilação sob demanda (comportamento atual).

### Template distribuível e regra legal

| Arquivo | Conteúdo | Pode ir no APK? |
|---|---|---|
| `build/vkcache/template_vk.bin` (61.930 B, WWHT0003) | só hashes de programa, pares de registro, atributos semânticos e estado fixo; `validate_vulkan` rejeita código (`shaderprep_format.py:228-269`, `vulkan_recipe(codefree=True)` falha com `code in stripped Vulkan recipe`, `:289`) | **Sim** (assets/ do APK) |
| `build/vkcache/template_both.bin` (1,06 MB) | mistura Mac (4.850 estados) + Vulkan; também code-free | Sim, mas carrega estados Metal inúteis no Android |
| `build/vkcache/headstart_vk.bin` (10 MB) | microcódigo e SPIR-V derivados do jogo | **Nunca** (`shaderprep.py:41-58`) |
| `vk-*.bin.recipes` (93,8 MB) | SPIR-V + fingerprint do aparelho | **Nunca** |

Como produzir `template_vk.bin` limpo: `shaderprep.py template <vk-*.bin.recipes> build/vkcache/template_vk.bin [--merge ...]` (usa o caminho `.recipes` -> tipo 4 -> `vulkan_database_pipelines` -> estados, `shaderprep.py:586-663`) e conferir com `shaderprep.py export`/`merge` que nada além de campos estritos entra. Aviso de qualidade: o template foi destilado de **1.387** pipelines vistos na demo de atração; cobre a primeira cena e pouco mais. Para cobrir o jogo, juntar templates de várias sessões com `merge` (limite 16.384 estados por banco, `shaderprep_format.py:311`).

## 5. Próximos passos ordenados

| # | Passo | Esforço | Ganho esperado | Risco |
|---|---|---|---|---|
| 1 | Rotear os `fprintf(stderr)` de `pipeline_worker.cpp`/`headstart.cpp` para `LOG` e adicionar as linhas da seção 3 | ~1 h | Torna tudo mensurável; sem isso, qualquer teste é cego | Nenhum |
| 2 | Experimento A/B (abaixo) para provar se o worker pré-cria e reaproveita | ~1 h no aparelho | Decide se vale investir em aquecimento em jogo | Nenhum |
| 3 | Habilitar o passo de preparação no APK do SM3DW: embutir `template_vk.bin`, chamar `ShaderPrepareActivity` na primeira execução e compartilhar o diretório de cache | 1-2 dias | Elimina o stall de 3-6 s da primeira cena na **primeira** execução, sem enviar código do jogo | Médio (paths de cache, fluxo de Setup desativado no manifest) |
| 4 | Paralelizar a tradução em `warm_headstart_shaders` e subir o teto de threads do warm | 1-2 dias | Reduz o tempo do passo de preparação | Médio (tradutor pode não ser thread-safe: verificar) |
| 5 | No worker em jogo: ordenar por prioridade (receitas da cena atual), trocar a vítima aleatória por LRU/FIFO e subir `maxUnusedWarmPipelines` | 0,5 dia | Só importa se o passo 2 provar que o worker acerta | Baixo; mais memória de driver |
| 6 | Pool de 2-3 threads no compilador de demanda e fila maior que 8 | 1-2 dias | Reduz o stall residual de cenas novas fora do que foi pré-compilado | Médio (ordem/merge do cache, `pipeline_demand.cpp:278-297`) |
| 7 | Expandir o template com mais sessões e níveis | contínuo | Cobertura do warm | Baixo |

## 6. Experimento exato para provar ou refutar "o worker pré-cria pipelines"

Pré-requisito: passo 1 (logs visíveis) **ou** `adb shell setprop log.redirect-stdio true`. Mesmo APK/commit nas duas rodadas (o commit entra no fingerprint).

Via `env.txt` do app: `WWHD_VK_STUTTER_TRACE=<arquivo>` e `WWHD_VK_STATS=1`. Alternar `WWHD_VK_PIPELINE_WARMUP` (`backend.cpp:277`).

| Rodada | Preparo | Esperado se o worker funciona |
|---|---|---|
| R0 controle | cache limpo, `WWHD_VK_PIPELINE_WARMUP=0`, start e cena | `startup_pipeline` = 0; stall inalterado |
| R1 | jogar 3 min (grava `.recipes` local, `[vulkan warmup] saved N recipes`), **fechar normalmente**, apagar **apenas** `vk-*.bin` (cache nativo) e `spirv.bin`, manter `vk-*.bin.recipes`, `WWHD_VK_PIPELINE_WARMUP=1` | `exact pipeline recipes queued` > 0, depois `N ready`; eventos `startup_pipeline` crescendo antes da cena |
| R2 | igual a R1 mas **sem apagar** `spirv.bin` | trava C aliviada: mais `startup_pipeline_hit` do que em R1 |
| R3 | igual a R2 com `maxUnusedWarmPipelines` subido (p. ex. 2048, requer build) | mais acertos: confirma a trava A |

Métricas para comparar nas 3 primeiras cargas de cena (trace + `[fps]`): soma de `pipeline_wait`/`pipeline_pending` (`pipeline_demand.cpp:166-184`), contagem de `startup_pipeline_hit` vs `guest_pipeline`, quadros abaixo de 30 fps, e o tempo dormindo em `pipeline_demand_active()`. **Refutação**: `queued` > 0 mas `startup_pipeline_hit` ~ 0 e `guest_pipeline` alto => travas A/C ou chave diferente (comparar `identity(key)` com a do draw); `queued` = 0 => receita ignorada (fingerprint/arquivo).

## 7. O que não consegui verificar

- Nada foi executado: não sei se `headstart_vk.bin` foi aberto pelo worker no aparelho (inferido pela estrutura do arquivo e pelo código).
- Se `fprintf(stderr)` realmente é descartado nesse aparelho/versão do Android (comportamento padrão conhecido, não medido aqui).
- A taxa real de "unresolved identities" do warm com os 1.387 estados do template, e a segurança de threads de `vk::translate`.
- Se a trava C ocorre de fato (depende do estado de `vs->spirv` no momento do draw após a limpeza de cache).
- `overlay/setup_flow.h`: não existe neste repositório; o progresso de setup é o Java mencionado.
