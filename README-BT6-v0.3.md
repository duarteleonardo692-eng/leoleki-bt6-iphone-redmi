# BT6 v0.3 — transferência de texto real via Bluetooth

Esta versão é **experimental**. Ela não fornece internet a WhatsApp, Chrome ou a todo o Android. O sistema iOS não disponibiliza tethering Bluetooth arbitrário a apps comuns. O objetivo desta etapa é provar a transferência bidirecional de conteúdo real em vez de apenas HTTP 200.

## Preservação dos aplicativos existentes

- Branch de trabalho: `feature/bt6-v03-transferencia-real`; `main` permanece na v0.2.
- IPA usa identificador `app.leoleki.bt6.iphone.v03` e nome BT6 iPhone v0.3.
- APK usa identificador `app.leoleki.bt6.redmi.v03` e nome BT6 Redmi v0.3.
- Os dois têm UUIDs BLE exclusivos terminados em A11/A12/A13, evitando contato com a versão antiga.
- A assinatura gratuita no iPhone, via Sideloadly, deve ser renovada após aproximadamente 7 dias.

## Protocolo BLE v0.3

No Redmi: comando P (PING), G (HTTP resumo) ou comando U (0x55) + diversos D (0x44 + até 17 bytes UTF-8) + E (0x45). O iPhone só aceita URLs HTTPS de `example.com`, `pt.wikipedia.org` e `www.iana.org`. Não há acesso a serviços locais, IPs privados ou HTTP.

Resposta do iPhone: frame de início `01 03`, fragmentos `02` + até 17 bytes, frame final `03` + FNV-1a 32-bit little-endian + comprimento 32-bit little-endian. O Android valida integridade antes de mostrar o texto. O iPhone envia até 8 KiB e remove tags/scripts do HTML (leitor de texto, não navegador completo).

## Como validar

1. Compilar IPA e APK pelas Actions da **branch de teste**, verificar success e Releases (o app antigo continua).
2. Instalar o IPA com Sideloadly e o APK no Redmi.
3. No iPhone BT6 v0.3, verificar "Anunciando BT6 v0.3". Com o novo APK, buscar o iPhone. Testar PING, HTTP e depois "Wikipedia Bluetooth" e "IANA".
4. No Redmi, verificar `Recebidos N bytes reais; integridade OK` e se o texto da página aparece.
5. Testar tela acesa e depois bloqueio de 30 segundos/5 minutos; a resposta pode falhar sob limites de segundo plano do iOS.

## Limites honestos

- Este protótipo é apenas uma ponte de textos HTTPS selecionados, e não uma VPN de sistema nem um proxy TCP/UDP. Internet geral requer outros módulos e não é garantida em iOS.
- O iOS pode suspender rede, publicidade e processamento; habilitar `bluetooth-peripheral` não concede execução permanente.
- Não há armazenamento de senhas nem dados pessoais. Conteúdo limitado a sites públicos de teste.
- A opção de compilação via GitHub Actions é gratuita em executores padrão de repositórios públicos, conforme condições do GitHub. A distribuição é via GitHub Releases, não via artefatos do Actions.
