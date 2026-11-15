# BT6 v0.8 — teste de internet iPhone → Redmi por Bluetooth

Esta versao acrescenta uma ponte TCP/UDP para os outros aplicativos do Android.
Ela ainda precisa ser validada no iPhone XR e no Redmi Note 11. Testes em computadores
nao confirmam emparelhamento, velocidade de radio, funcionamento do TUN no celular
nem execucao do app iOS em segundo plano.

## Para usar quando os aparelhos estiverem disponiveis

1. Instale **os dois aplicativos v0.8**. O iPhone v0.6 nao oferece o novo canal.
2. O IPA esta sem assinatura: reassine e instale com o metodo pessoal ja utilizado
   no Windows. A autorizacao gratuita normalmente precisa ser renovada a cada 7 dias.
3. Conecte o iPhone ao Wi-Fi. Abra BT6 iPhone v0.8 e mantenha o aplicativo visivel.
4. No Redmi, abra BT6 v0.8 e autorize Bluetooth. Toque em **Buscar iPhone**.
5. Aguarde a mensagem de canal L2CAP pronto. Se aparecer pedido de emparelhamento,
   confira os aparelhos e aceite neles.
6. Toque em **ATIVAR INTERNET DO IPHONE** e aceite a VPN do Android.
7. Use **VER ESTADO DA INTERNET**. A ponte testa uma conexao HTTP antes de iniciar o motor VPN.
8. Desligue os dados moveis do Redmi durante a verificacao para confirmar a origem
   da internet. Abra um site HTTPS novo no Chrome. Confira mais de um site.
9. Teste por 10 minutos, depois bloqueie a tela do iPhone e observe se interrompe.
   O iOS pode suspender o aplicativo; o modo BLE em background nao garante um relay
   de internet continuo. Para o primeiro teste, deixe a tela do iPhone ligada.
10. Para parar, toque em **DESLIGAR INTERNET** ou na acao da notificacao Android.

Se o canal nao aparecer, reinicie o anuncio no iPhone e busque novamente no Redmi.
Se o pareamento falhar, registre o texto exato. Nao ha garantia de compatibilidade
L2CAP criptografado entre estes dois aparelhos ate este teste.

## O que mudou

- Descoberta por GATT, com PSM dinamico e identificacao de protocolo v0.8.
- Dados de internet por **BLE L2CAP criptografado**, sem fragmentar cada bloco em
  notificacoes ATT de 20 bytes como os prototipos anteriores.
- Android: VpnService → HEV tun2socks 2.20.0 → proxy limitado a 127.0.0.1 → L2CAP.
- iPhone: SOCKS5 CONNECT e extensao HEV UDP-in-TCP (comando 5) → Network.framework.
- TLS/HTTPS permanece nos aplicativos; a ponte nao instala certificados nem decifra HTTPS.
- Conexoes do novo relay iOS exigem interface Wi-Fi, sem fallback para dados moveis.
- DNS mapeado no Android: o iPhone resolve os nomes recebidos pelo proxy.
- IPv4 e IPv6 entram na VPN. O proprio BT6 Android e excluido para evitar recursao.
- Limites de sessoes, filas e tempos de espera; encerramento dos sockets ao parar.
- Navegador antigo e VPN LAB ficam em testes avancados, ocultos inicialmente.

## Limites conhecidos

- Velocidade real ainda nao medida. L2CAP evita o gargalo dos quadros ATT de 20 bytes,
  mas nao elimina os limites de BLE, interferencia e suspensao pelo iOS.
- Ate 10 sessoes do motor VPN e 12 canais simultaneos. O numero pratico aceito pelo
  iPhone pode ser menor e precisa ser medido. Nao foi implementado multiplexador
  para compartilhar um unico canal entre varias conexoes.
- UDP e de melhor esforco. Datagramas acima do limite aceito pela rede sao descartados.
  O MTU da VPN e 1280 bytes. Multicast, ICMP externo e protocolos IP fora de TCP/UDP
  nao sao encaminhados por esse proxy.
- O relay Android fecha as duas direcoes quando uma delas termina. Protocolos que
  dependem de half-close TCP podem precisar de uma camada adicional de sinalizacao.
- Nao e uma VPN com kill switch: ao desligar/revogar a VPN, o Android volta as redes
  normais. Recursos como VPN permanente nao sao oferecidos nesta versao.
- Nao foram testados chamadas, videos, jogos, WhatsApp, reconexao apos suspensao,
  impacto de bateria ou navegacao continua nos celulares.

## Reproduzir os testes

Android requer JDK 17+, Android SDK 34 e Gradle 8.7. Execute `python tools/dependencies.py`
para obter o AAR oficial com SHA256 fixo antes de compilar. Configure `android/local.properties`
com o caminho do SDK. Execute Gradle com `-p android :app:assembleDebug :app:lintDebug`.

Teste Java do relay real: compile `StreamBridge.java` e `tests/StreamBridgeTest.java`
com javac e execute `app.leoleki.bt6.redmi.StreamBridgeTest`.

O workflow `validar-v08.yml` compila o codec Swift, testa fragmentacao e integridade,
testa o relay real com streams de socket em macOS e compila o IPA com Xcode.
Ele usa o mesmo codigo de producao; somente os streams Bluetooth sao substituidos
por sockets, e a exigencia de Wi-Fi e desativada exclusivamente nesse harness de teste.

Os pacotes anteriores fornecidos foram preservados. O aplicativo Android usa o pacote
`app.leoleki.bt6.redmi.v08`; o iPhone usa `app.leoleki.bt6.iphone.v08`.

## Fontes tecnicas

- Apple L2CAP: https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/publishl2capchannel(withencryption:)
- Android L2CAP: https://developer.android.com/reference/android/bluetooth/BluetoothDevice#createL2capChannel(int)
- HEV: https://github.com/heiher/hev-socks5-tunnel/tree/2.20.0
- HEV framing UDP: https://github.com/heiher/hev-socks5-core/blob/b43fa857fb39c854da01d13bc55eded66b4f4a3b/include/hev-socks5-proto.h

Licencas e origem da dependencia: THIRD-PARTY.md e THIRD-PARTY-LICENSES.txt.
