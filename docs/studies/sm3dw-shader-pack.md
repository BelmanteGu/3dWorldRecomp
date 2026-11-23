# Tabela de shaders pré-traduzidos (shader pack)

Primeiro passo do plano para o nível 4 ([native-levels.md](native-levels.md)): o aparelho deixa de rodar o decompilador
Latte do Cemu para os shaders que a tabela cobre. Código: `runtime/src/gfx/vulkan/shader_pack.{h,cpp}` e o gancho em
`translate()` (`shaders.cpp`).

## Por que o cache antigo não bastava

O cache de SPIR-V (`spirv.bin`) tem como chave o **texto GLSL**. Para obter o GLSL é preciso rodar o decompilador, então
ele só poupava a etapa seguinte (GLSL para SPIR-V). Além disso, os metadados que o renderizador lê do decompilador
(bindings, listas de uniformes, unidades de textura) eram sempre reconstruídos.

## O que a tabela guarda

A chave é a **entrada**, os mesmos hashes que `translate()` já calcula sem o decompilador: hash dos bytes do programa,
do fetch shader e dos registradores de ligação (`linkage`), mais o estado de texturas e exports (`variant_hash`). São
hashes de conteúdo, iguais em qualquer máquina para o mesmo dump do jogo, então uma tabela feita num PC serve o celular.

Por shader, a tabela guarda o que o renderizador usa do decompilador e nada mais:

- `mapping`, `uniforms` e `descriptorRanks` (bytes crus, com o tamanho das structs na impressão digital do arquivo);
- máscara de saída de cor, exports do vertex shader, flag de streamout;
- unidades de textura, amostradores e comparação de profundidade;
- as listas de uniformes remapeados (registradores e grupos de buffer);
- `outputHash` e `pipelineId`, que no lugar do texto GLSL dão a identidade do shader (o GLSL só era lido para isso);
- o SPIR-V, comprimido com zlib e compartilhado entre shaders iguais.

Tamanho medido: 783 shaders em 7,5 MB (23,8 MB de SPIR-V descomprimido).

## Como o runtime usa

1. `translate()` calcula `linkage`. Se a tabela conhece o programa, preenche o uso (`ProgramUse`) sem decompilar.
2. Calcula a chave da variante e procura a tabela. Acerto: monta o `Shader` direto (SPIR-V descomprimido na hora) e
   pula decompilador, glslang e cache de disco. Erro: segue o caminho antigo (decompilador).
3. Cada shader decompilado vira um registro novo, gravado quando o SPIR-V fica pronto, de forma assíncrona e atômica
   (`shaderpack.bin` ao lado do `spirv.bin`). O celular grava só o que a tabela de base não tinha.

Onde a tabela é lida: `<jogo>/shadercache/shaderpack.bin`, o asset `shaderpack.bin` do APK (base, nunca regravada) e o
arquivo vivo no diretório de cache. `WWHD_VK_SHADER_PACK=0` desliga; `=caminho` usa só esse arquivo. Com gráficos
customizados do Cemu (`mods::cemu::has_shaders()`) a tabela fica desligada.

## Segurança

- **Impressão digital:** versão do tradutor (`kTranslatorVersion`), receita de compilação e versão do glslang, e o
  tamanho das structs. Arquivo de outro tradutor é ignorado com uma linha de log. **Aumente `kTranslatorVersion`**
  sempre que mudar o decompilador ou o que `translate()` acrescenta à saída.
- **Verificação:** `WWHD_VK_SHADER_PACK_VERIFY=1` traduz de novo cada acerto com o decompilador e compara metadados,
  hashes de identidade e SPIR-V. Qualquer diferença vira `[shader pack] MISMATCH` no log.
- **Modo estrito:** `WWHD_VK_SHADER_PACK_STRICT=1` proíbe o decompilador. Shader fora da tabela vira erro registrado
  (`[shader pack] missing ...`) e o draw é pulado. É o modo do nível 4 e mede a cobertura.

## Resultado medido (PC, janela escondida, caches vazios)

Roteiro de 3.600 quadros (título, menus, abertura), 783 shaders:

| | Com decompilador | Com a tabela (estrito) |
|---|---|---|
| Shaders servidos pela tabela | 0 | 783 de 783 |
| Verificação contra o decompilador | n/a | 783 conferidos, 0 diferentes |
| fps nos quadros 2100 a 3300 (cenas novas) | 36, 15, 28, 11 | 60, 58, 58, 60 |
| Capturas dos quadros 900 e 1700 | iguais | iguais byte a byte |

As capturas dos quadros 2100 e 3000 diferem só na fase da animação (pose dos personagens, posição dos fogos), porque sem
as travadas de compilação o tempo do jogo corre diferente. A comparação decisiva é a verificação acima.

## Fluxo

```sh
tools/sm3dw_shader_pack.sh record 14000   # roteiro até o mapa; acumula em build/shaderpack/shaderpack.bin
tools/sm3dw_shader_pack.sh verify         # deve dizer: 0 different
tools/sm3dw_shader_pack.sh strict         # o que ainda falta
tools/sm3dw_shader_pack.sh pull           # junta o que o celular gravou
tools/sm3dw_shader_pack.sh apk            # coloca a tabela no APK; depois android/build_sm3dw_apk.sh
tools/shaderpack.py info|merge ...        # inspeciona e junta tabelas
```

## Usando o cache do Cemu (sem jogar)

O Cemu guarda, por shader, o programa e o estado de registradores, e por pipeline, o par vertex+pixel com os
registradores do desenho (`shaderCache/transferable/<id>_shaders.bin` e `_vkpipeline.bin`; formato lido do código-fonte
dele, ver `tools/cemu_cache.py`). Cada pipeline vira um registro "shader ligado" (`tools/cemu_to_headstart.py`) e o
modo de aquecimento do próprio runtime o traduz, gravando a tabela:

```sh
tools/sm3dw_shader_pack.sh cemu 0005000010145c00_shaders.bin 0005000010145c00_vkpipeline.bin
```

Dois detalhes que o formato exigiu (e que a medição apontou):

- O fetch shader do nosso runtime é o formato compacto (`WWFS`), o do Cemu é o programa real da GPU. A chave do vertex
  shader passou a usar o hash dos **atributos** do fetch shader, igual nas duas representações (o divisor de instância só
  conta para dados de instância: o compacto guarda 0 onde o parser do Cemu devolve -1).
- Registros que o glslang recusa (`overlapping use of location`, cerca de 1%) viram falhas registradas, não encerram o
  aquecimento.

### Medido (caches USA e EUR do Cemu, 3.929 + 4.058 shaders, 8.040 + 7.320 pipelines)

Todos os 140 vertex e 154 pixel shaders que o nosso roteiro usou estão nos caches (bytes idênticos). Cobertura das
**chaves** que o nosso jogo gerou no roteiro de 14.000 quadros (1.055 vertex, 230 pixel):

| Fonte | Vertex | Pixel |
|---|---|---|
| Cemu USA | 53,2% | 100% |
| Cemu EUR | 48,0% | 98,7% |
| Os dois | 55,1% | 100% |

Roteiro de 3.600 quadros só com a tabela do Cemu e o decompilador como reserva: 555 de 783 shaders vieram prontos (71%),
e nos quadros em que cenas novas travavam o jogo o fps foi de 36, 15, 28 e 11 para 56, 58, 60 e 60.

O que sobra dos vertex shaders são variantes reais que o jogador do Cemu não percorreu: o mesmo programa com outro
layout de vértices (62 chaves) ou outra tabela `SQ_VTX_SEMANTIC` (171 chaves). Isso se resolve com mais gravações
(`pull` do celular, tabelas de outras pessoas), não com mais conversão.

## Cobertura: o que falta para o nível 4 completo

A tabela só contém as variantes que alguém **usou** (o estado de registradores de cada draw). Não existe emulador que
saiba, sem jogar, quais variantes de shader o jogo vai pedir; o Cemu tem o mesmo limite e por isso se compartilham caches.
O modelo de estados do `shaderprep.py build` não traz registros de shaders (a saída diz `known records 0`), então não dá
para enumerar as variantes só pelos arquivos do jogo.

Por isso o decompilador continua no APK como reserva: um shader fora da tabela é traduzido na hora (os draws esperam,
sem travar) e fica gravado para a próxima vez. O modo estrito (`WWHD_VK_SHADER_PACK_STRICT=1`) mede o que ainda falta e
é o que permitirá tirar o decompilador do build quando a cobertura for completa.
