# BT6 v0.5 — VPN LAB isolada (Android)

## Estado
Esta versao serve para provar a **captura de pacotes IP do Android** usando a API oficial `VpnService`. **NAO fornece internet ao Chrome, WhatsApp, YouTube ou a todo o aparelho.** O iPhone BT6 **v0.4** continua igual; nao precisa reassinar ou instalar um IPA novo.

## Preservacao
- Branch: `feature/bt6-v05-vpn-lab`
- Android package distinto: `app.leoleki.bt6.redmi.v05`; a v0.4 permanece instalada
- UUIDs BLE A21/A22/A23 **iguais aos da v0.4**, pois estamos testando apenas o Android neste marco
- `main` / `feature/bt6-v04-tcp-relay` nao sao alteradas
- O recurso VPN LAB so inicia com acao e consentimento explicitos do usuario

## Escopo do tunnel LAB
O usuario autoriza a VPN na caixa de dialogo oficial do Android. A VPN instala somente:
- Endereco da interface TUN: `10.77.0.2/32`
- Rota de teste: `198.18.0.0/15` (rede reservada para benchmark e diagnostico)
- Aplicativo selecionado: `addAllowedApplication(getPackageName())` — apenas o proprio BT6
- Captura de contagem IPv4, TCP e UDP; **nao grava payload ou enderecos dos pacotes**

O usuario pode parar pelo botao **DESLIGAR VPN LAB** ou pela acao da notificacao. **Ativar qualquer VPN, mesmo uma VPN isolada, pode encerrar outra VPN existente no Android.** Nao usar ao mesmo tempo que Gnirehtet/TLS Tunnel/HTTP Injector ou outro VPN.

## Sequencia de testes
1. Baixar e instalar o APK v0.5 (sem remover v0.4); usar iPhone BT6 v0.4 normalmente.
2. Testar Bluetooth e TCP como na v0.4.
3. No Redmi BT6 v0.5, ler a descricao da VPN LAB; se desejar continuar, tocar **Iniciar VPN LAB (somente teste)** e aprovar a solicitacao do Android.
4. Quando estiver ativa, tocar **Gerar pacote TCP de teste**. O destino fixo `198.18.0.1:80` nao responde; timeout e esperado.
5. Verificar contadores de pacotes IPv4/TCP. O marco e **capturar a tentativa de conexao** no TUN; nao obter resposta HTTP.
6. Tocar **DESLIGAR VPN LAB**. Testar que contagem permanece e a VPN desapareceu do sistema.

Se a VPN interferir com outro aplicativo, interromper imediatamente.

## Arquitetura futura para internet real (NAO incluida ainda)
- Protocolo BLE confiavel e autenticado com controle de janela, fluxo e retransmissoes
- Um stack tun2socks / TCP-IP para remontar sockets, DNS e UDP/QUIC ou fallback
- Sessao TCP/TLS bidirecional generalizada, resolucao DNS segura e limitações de destinos
- Mecanismo de protecao de sockets contra loops, monitoramento, reconexao e bateria
- Regras de cancelamento, isolamento e seguranca para nao bloquear redes existentes
- Validacao de politicas e limites da Apple: `bluetooth-peripheral` **nao** garante execucao de trafego de internet indefinidamente em background

## Alertas de honestidade
O fato de a v0.4 ter enviado 971 bytes com integridade OK e a v0.3 ter mostrado Example Domain comprova testes pontuais. Não comprova que uma VPN full-device usando BLE + iOS pode manter WhatsApp/Chrome online de forma estável. Temos que validar as limitações antes de prometer esse resultado.
