# Compilar o app Android

Pré-requisitos: Android SDK + NDK 28.2.13676358, CMake e Ninja, JDK 17, Python 3 com Pillow (ícone, `android/make_icon.py`),
e o **código gerado do seu jogo** em `build/gen3dw` (ver abaixo). O código do jogo e o APK resultante contêm material
protegido: **não distribua**.

1. Recompilar o jogo (uma vez, ~35 s): `RECOMP_GAME=sm3dw python3 tools/recomp/recomp.py game3dw/code/RedCarpet.rpx build/gen3dw`
   (`game3dw/` = seu dump extraído; EUR Rev 1, `RedCarpet.rpx` com SHA-256 `a254...4937`).
2. Biblioteca nativa:
   ```sh
   cd android
   GEN_DIR=$PWD/../build/gen3dw EXTRA_CMAKE_ARGS=-DWWHD_GAME_RPX=RedCarpet.rpx NDK_VERSION=28.2.13676358 \
     CPU=cortex-a78 JOBS=4 ./build_native.sh        # CPU=generic para qualquer arm64; a78 = Galaxy S23 (Cortex-A78/X3 mix)
   ```
   Cada variante sobrescreve `android/app/libs/arm64-v8a`: monte o APK logo depois.
3. APK: `JAVA_HOME=<jdk-17> APK_NAME=SM3DW-S23.apk VERSION_NAME=0.5 android/build_sm3dw_apk.sh` (usa `--max-workers=2`).
   Padrões do menu por variante: `DEFAULT_FPS=60|120` e `DEFAULT_AUTO_RES=off|60|120` (propriedades Gradle
   `wwhdDefaultFps` / `wwhdDefaultAutoRes`, recursos `variant_rate` / `variant_autores`, lidos por
   `MenuOptions.variant`). O build do Galaxy S23 usa `DEFAULT_FPS=120 DEFAULT_AUTO_RES=120`; o genérico fica no
   padrão (60, resolução automática desligada). O jogador muda os dois em Opções > Gráficos ("Taxa de quadros",
   "Resolução automática"), ao vivo; a escolha fica salva e vence o padrão da variante.
   O SHA-256 do RPX que o app aceita vem de `game3dw/code/RedCarpet.rpx` ou de `RPX_SHA256`. O pacote de shaders
   pré-traduzidos (`tools/sm3dw_shader_pack.sh apk`, opcional) acelera a primeira execução.

Memória: um build Java já foi morto por falta de memória; use `nice`, `JOBS=4` e `--max-workers=2`.
Variantes: `app_name` é trocado para "Super Mario 3D World" pelo script; o pacote continua `org.sm3dwrecomp.sm3dw`.
