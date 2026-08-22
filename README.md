# Central Lite v0.2

Launcher automotivo leve para tablet Android antigo (pensado para o Galaxy Tab A SM-T280 / Android 5.1).

## O que foi ajustado nesta versão
- Layout redesenhado inspirado no mockup do Ford Fusion 2014 prata.
- Relógio grande e data em português.
- Velocímetro GPS em destaque com visual circular.
- Cartões laterais de bateria e status do GPS.
- Linha inferior com 7 atalhos:
  - Waze
  - Google Maps
  - Spotify
  - Bluetooth
  - Música
  - Configurações
  - Aplicativos
- Mantido como launcher HOME e LANDSCAPE.
- Estrutura leve: sem mapas embutidos e sem animações pesadas.

## Observação importante
Esta versão usa desenho por código para o tema visual do Fusion. Se quiser, na próxima etapa podemos trocar o desenho do carro por:
1. uma foto real do seu Ford Fusion prata, ou
2. uma imagem ilustrativa em `res/drawable`.

## Como abrir no Android Studio
1. Extraia a pasta `CentralLite`.
2. Abra o projeto no Android Studio.
3. Aguarde o Gradle sincronizar.
4. Gere o APK normalmente.

## Próxima etapa sugerida
- Substituir o desenho do carro por imagem real do seu Fusion.
- Adicionar tema escuro/azul ainda mais próximo do painel original.
- Colocar botão dedicado para câmera/reversa ou OBD2 no futuro.

## Gerar APK pelo GitHub Actions
1. Envie todos os arquivos deste projeto para um repositório GitHub.
2. Abra a aba **Actions**.
3. Escolha **Build Central Lite APK**.
4. Clique em **Run workflow**.
5. Ao terminar, baixe o artefato **CentralLite-Fusion-SM-T280**.
6. Dentro dele estará `app-debug.apk`, pronto para instalar no tablet.
