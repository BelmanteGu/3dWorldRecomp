# Roadmap e issues

Acompanhado no GitHub (milestones M1-M7). Estado nesta branch (`sm3dw-android-opt`):

| Milestone | Issues | Estado |
|---|---|---|
| M1 Medir o processador | #1 perfil simpleperf no Redmi 10C; #2 hotspots à mão (NEON) | aberto: depende do celular |
| M2 Texturas nativas | #3 BC1-BC7 -> ASTC/ETC2 na importação | aberto |
| M3 GPU de celular | #4 load/store e transient attachments; #5 Turnip no Adreno 610 | aberto: depende do celular |
| M4 Jogabilidade | #6 toque na imagem, #7 giroscópio/vibração (implementados, falta validar no aparelho); #8 controles por contexto | #6/#7 aguardam teste |
| M5 Android | #9 Swappy; #10 perfis de energia; #11 save na nuvem | aberto |
| M6 120 fps | #12 terminar interpolação de câmera e ossos | parcial: câmera 0,25 do passo em vez de 0,5 |
| M7 Pendências | #13 importação perde a URI; #14 limpeza do Zelda (feita nesta branch); #15 nomes dos botões | #14 entregue |

Já feito e medido: harness de benchmark (`docs/benchmark.md`), resolução automática, preset Intel 120
(`docs/intel120.md`), `MUL_MODE=fast`, frame skip (`docs/frameskip.md`), toque/vibração/giroscópio do celular.
