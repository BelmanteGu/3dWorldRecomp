# Importar e exportar o save (Android)

O save do jogo fica em `user/GameData.bin` (pasta de dados do app). Na tela inicial do app:

* **Exportar save**: gera um `.zip` com `user/GameData.bin` em Downloads e oferece "Enviar para...".
* **Importar save**: aceita um `.zip` com `user/GameData.bin` (senão avisa "este zip não tem save do Super Mario 3D World"). O
  save anterior é guardado numa pasta de backup e o caminho é mostrado.
* Importar o **jogo** é outro botão ("Importar jogo"): `.wux`/`.wud` com `keys.txt`, `.wua` ou pasta. A URI escolhida
  sobrevive a um kill do processo (`:import`), ver issue #13.
* No desktop o save mora na pasta passada por `--save DIR` (`DIR/user/GameData.bin`); copie o arquivo à mão. O benchmark
  usa uma cópia nova a cada execução e nunca toca o original.
* Nuvem (Google Drive): ainda não existe (issue #11).

Nunca publique saves junto com dados do jogo; o `.gitignore` ignora `save/`, `*.key`, `*.wux`, `*.rpx`.
