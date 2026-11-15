# BT6 — prototipo experimental iPhone XR -> Redmi Note 11

## Escopo exato da v0.1 (15/11/2026)

Esta v0.1 NOVA **nao substitui** o aplicativo Windows/Android Leoleki Internet Bluetooth v0.2 que ja existia. Usa **Bluetooth Low Energy** (BLE), nao portas COM/RFCOMM nem PAN. O APK antigo nao consegue conversar com este app de iPhone; ha um Android de testes especifico na pasta `android`.

A finalidade desta etapa e **somente provar comunicacao direta** sem PC durante o uso: o Redmi envia PING, o iPhone devolve PONG; depois o Redmi pede ao iPhone para acessar `https://example.com` e recebe o status HTTP e um trecho do conteudo de volta por Bluetooth. O iPhone precisa estar com o Wi-Fi funcionando e o app aberto. Isso **nao significa** que os apps do Redmi ja navegam pela internet. Para internet geral sera necessario criar uma camada de proxy com tunel VPN no Android, transporte bidirecional e resolver as restricoes de segundo plano do iOS. Nao promessa de sucesso ou velocidade util.

## Arquivos

- `ios/Sources/`: app iOS SwiftUI + CoreBluetooth no modo periferico BLE.
- `project.yml`: gera projeto de Xcode com XcodeGen na nuvem.
- `android/`: app Android de diagnostico BLE (separado do APK atual).
- `.github/workflows/compilar-iphone.yml`: compilacao iOS nao assinada em runner macOS.

## Compilar o IPA sem Mac local, sem gastar

1. Crie repositório **publico** apenas se aceitar que o codigo-fonte BT6 seja visivel a qualquer pessoa (nao colocar senhas, tokens, chaves e dados pessoais). Extrair o conteudo desta pasta na raiz do novo repositorio; **inclusive a pasta oculta `.github`**.
2. No GitHub, abra **Actions > BT6 - Compilar iPhone (IPA sem assinatura) > Run workflow**.
3. Quando concluir, abra a execucao e baixe o artefato `BT6-iPhone-IPA-sem-assinatura`.
4. Extraia o ZIP do artefato: dentro ha `BT6-iPhone-unsigned.ipa`. Essa IPA NAO roda sem ser assinada; isso e normal.

GitHub Actions em repos publicos com runner padrao pode compilar sem consumir a franquia de minutos privados, segundo documentacao oficial consultada em nov/2026. Nao se promete uma compilacao feita: este codigo precisa de teste em runner real.

## Instalar no iPhone XR usando Windows

1. Instale o Sideloadly oficial de https://sideloadly.io e os componentes Apple para Windows conforme instrucoes do programa.
2. Conecte o iPhone XR por cabo USB ao Windows e toque em **Confiar** caso apareca.
3. No Sideloadly, selecione o iPhone, arraste `BT6-iPhone-unsigned.ipa`, informe sua Conta Apple **somente no programa local** e instale; nao enviar credenciais para GitHub ou neste projeto.
4. Se necessario, habilite **Modo de Desenvolvedor** (Ajustes > Privacidade e Seguranca) e confie no perfil em Ajustes > Geral > VPN e Gerenciamento de Dispositivo.
5. Em conta gratuita, o app vence em aproximadamente **7 dias**. Ative a opcao de renovacao automatica no Sideloadly. O PC ainda precisa estar acessivel ao iPhone por Wi-Fi na mesma rede (quando possivel) ou por USB para renovacao; esse PC **nao precisa ficar ligado para o teste Bluetooth** entre os celulares.

## Testar no Redmi Note 11 (Android 13/HyperOS)

1. Abra a pasta `android` no Android Studio para sincronizar o Gradle. Use Java 17/21 e Android SDK 35. **Observacao:** este pacote inclui a configuracao do projeto, mas nao os binarios/scripts Gradle Wrapper (`gradlew` e `gradle-wrapper.jar`). Se o Android Studio nao conseguir importar, sera preciso gerar/copiar o Wrapper de um projeto Android anterior (Gradle 8.9 ou superior) antes de sincronizar. Compile `app` para produzir `app-debug.apk` (ou instale via Android Studio).
2. Instale esta nova APK de teste no Redmi. Ela tem outro identificador (`app.leoleki.bt6.redmi`) e nao substitui o app `Leoleki Internet Bluetooth` anterior.
3. iPhone: ligue Bluetooth e Wi-Fi, abra **BT6 iPhone** e veja `Anunciando BT6`. Deixe a tela do app aberta.
4. Redmi: ligue Bluetooth, abra **BT6 Redmi**; conceda permissoes de dispositivos proximos; toque **1. Buscar iPhone BT6**. Nao precisa emparelhar pelo menu de configuracoes. Se conectar, toque **2. Testar Bluetooth (PING)**, depois **3. Testar internet do iPhone**.
5. Resultado esperado, ainda NAO validado em hardware: `PONG` e `HTTP 200`/resposta HTTP, respectivamente. Se falhar, anotar o texto mostrado por ambos.

## Limites e seguranca

- Nenhuma VPN criada e nenhuma configuracao de rede do Redmi alterada. Nenhuma chave pessoal deve entrar no repositório publico.
- Apenas endereco HTTPS fixo de teste: `https://example.com`; nao funciona como proxy web livre nem recebe URLs arbitrarias.
- BLE pode ser lento e iOS suspende tarefas em segundo plano. Mesmo com permissao Bluetooth, iOS nao garante sessao continua para uma ponte de internet geral.
- O Redmi pode estar com Bluetooth emparelhado com o iPhone no sistema, mas este teste usa GATT/BLE, que e independente do pareamento antigo.
- Codigo **preparado**, nao ha compilacao nem teste fisico comprovado. O primeiro resultado a validar sera um PONG em ambos os aparelhos.
