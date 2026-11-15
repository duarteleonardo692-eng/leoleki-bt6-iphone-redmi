import Foundation
import CoreBluetooth
import Network
import Combine

/// BT6 v0.4: retransmissao TCP de diagnostico e texto HTTPS via pacotes BLE.
final class BT6PeripheralBridge: NSObject, ObservableObject, CBPeripheralManagerDelegate {
    static let serviceUUID = CBUUID(string: "AD08C143-7581-4612-9D63-20D9540C4A41")
    static let requestUUID = CBUUID(string: "AD08C143-7581-4612-9D63-20D9540C4A42")
    static let responseUUID = CBUUID(string: "AD08C143-7581-4612-9D63-20D9540C4A43")
    static let psmUUID = CBUUID(string: "AD08C143-7581-4612-9D63-20D9540C4A44")
    @Published private(set) var internetStatus = "Preparando canal L2CAP"
    private var psm: CBL2CAPPSM?
    private var relays = [UUID: L2CAPRelay]()
    private var internetBytes = 0
    private static let permittedHosts: Set<String> = ["example.com", "www.iana.org", "pt.wikipedia.org"]

    @Published private(set) var bluetoothStatus = "Verificando"
    @Published private(set) var serverStatus = "Aguardando"
    @Published private(set) var clientStatus = "Aguardando Redmi"
    @Published private(set) var lastRequest = "Nenhum"
    @Published private(set) var lastResponse = "Nenhuma"
    @Published private(set) var transferredBytes = 0

    private var manager: CBPeripheralManager!
    private var responseCharacteristic: CBMutableCharacteristic?
    private var subscribers = Set<UUID>()
    private var outgoing = [Data]()
    private var incomingURL = Data()
    private var receivingURL = false
    private var browsingURL = false
    private var browsingData = Data()
    private var requestGeneration = 0
    private var tcpConnection: NWConnection?
    private var tcpFinished = false

    override init() {
        super.init()
        manager = CBPeripheralManager(delegate: self, queue: .main)
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            bluetoothStatus = "Ligado"
            createService()
        case .poweredOff:
            bluetoothStatus = "Desligado"
            serverStatus = "Ative o Bluetooth"
            subscribers.removeAll()
            stopInternet()
        case .unauthorized:
            bluetoothStatus = "Sem permissao"
            serverStatus = "Autorize Bluetooth nos Ajustes"
        case .unsupported:
            bluetoothStatus = "Nao suportado"
        default:
            bluetoothStatus = "Aguardando"
        }
    }

    private func createService() {
        stopInternet()
        manager.stopAdvertising()
        manager.removeAllServices()
        outgoing.removeAll()
        subscribers.removeAll()
        incomingURL.removeAll()
        receivingURL = false

        let request = CBMutableCharacteristic(type: Self.requestUUID, properties: [.write], value: nil, permissions: [.writeable])
        let response = CBMutableCharacteristic(type: Self.responseUUID, properties: [.notify], value: nil, permissions: [])
        responseCharacteristic = response
        let service = CBMutableService(type: Self.serviceUUID, primary: true)
        let endpoint = CBMutableCharacteristic(type: Self.psmUUID, properties: [.read], value: nil, permissions: [.readable])
        service.characteristics = [request, response, endpoint]
        manager.add(service)
        serverStatus = "Configurando servico"
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            serverStatus = "Falha no servico: \(error.localizedDescription)"
            return
        }
        peripheral.publishL2CAPChannel(withEncryption: true)
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID],
            CBAdvertisementDataLocalNameKey: "BT6-LEOLEKI"
        ])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        serverStatus = error == nil ? "Anunciando BT6 v0.7" : "Falha no anuncio: \(error!.localizedDescription)"
    }

    func restartAdvertising() {
        guard manager.state == .poweredOn else {
            serverStatus = "Bluetooth indisponivel"
            return
        }
        createService()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        guard characteristic.uuid == Self.responseUUID else { return }
        subscribers.insert(central.identifier)
        clientStatus = "Redmi conectado (BLE)"
        flushPackets()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        guard characteristic.uuid == Self.responseUUID else { return }
        subscribers.remove(central.identifier)
        if subscribers.isEmpty {
            outgoing.removeAll()
            clientStatus = "Redmi desconectado"
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager,
                           didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            guard request.characteristic.uuid == Self.requestUUID,
                  request.offset == 0, let bytes = request.value,
                  !bytes.isEmpty, bytes.count <= 20 else {
                peripheral.respond(to: request, withResult: .invalidAttributeValueLength)
                continue
            }

            let opcode = bytes[0]
            if ((opcode == 0x44 && (!receivingURL || incomingURL.count + bytes.count - 1 > 220)) ||
                (opcode == 0x5C && (!browsingURL || browsingData.count + bytes.count - 1 > 220))) {
                peripheral.respond(to: request, withResult: .invalidAttributeValueLength)
                receivingURL = false
                incomingURL.removeAll()
                browsingURL = false
                browsingData.removeAll()
                continue
            }
            peripheral.respond(to: request, withResult: .success)

            switch opcode {
            case 0x50: // PING v0.1
                lastRequest = "PING"
                sendResponse("PONG | iPhone recebeu o pedido Bluetooth")
            case 0x47: // Teste HTTP de compatibilidade
                fetchPage("https://example.com", summaryOnly: true)
            case 0x54: // T - TCP nativo (somente example.com:80)
                runTcpRelayDiagnostic()
            case 0x56: // V - UDP LAB v0.6, confirmado pelo servidor HTTPS
                runUdpLabProbe()
            case 0x55: // Início de URL (U)
                incomingURL.removeAll()
                receivingURL = true
                lastRequest = "Recebendo endereco HTTPS"
            case 0x44: // Dados da URL (D), no maximo 17 bytes
                if receivingURL { incomingURL.append(bytes.dropFirst()) }
            case 0x5B: // Iniciar pedido do navegador ("[")
                browsingData.removeAll()
                browsingURL = true
                lastRequest = "Navegador: recebendo endereco"
            case 0x5C: // Dados do endereco do navegador
                if browsingURL { browsingData.append(bytes.dropFirst()) }
            case 0x5D: // Fim do pedido do navegador
                guard browsingURL else {
                    sendResponse("ERRO BROWSER | inicio ausente")
                    break
                }
                browsingURL = false
                let targetURL = String(data: browsingData, encoding: .utf8) ?? ""
                browsingData.removeAll()
                fetchBrowserPage(targetURL)
            case 0x45: // Fim da URL (E)
                guard receivingURL else {
                    sendResponse("ERRO | inicio de URL ausente")
                    break
                }
                receivingURL = false
                let urlString = String(data: incomingURL, encoding: .utf8) ?? ""
                incomingURL.removeAll()
                fetchPage(urlString, summaryOnly: false)
            default:
                sendResponse("ERRO | comando nao reconhecido")
            }
        }
    }

    // Primeira camada de uma ponte TCP: comando vem do Android via BLE;
    // iPhone abre um socket TCP real, lê a resposta do servidor e devolve seus bytes.
    // Nao intercepta nem roteia pacotes IP de outros aplicativos Android.
    // Resposta de laboratorio: o Redmi gera um datagrama UDP real na VPN,
    // o iPhone consulta um endereco HTTPS publico, e o Android injeta um
    // datagrama de resposta local ao socket original. Não é proxy geral.
    private func runUdpLabProbe() {
        requestGeneration += 1
        let generation = requestGeneration
        lastRequest = "VPN LAB UDP pela ponte Bluetooth"
        serverStatus = "Verificando internet HTTPS para responder UDP LAB"
        guard let url = URL(string: "https://example.com/") else {
            sendResponse("ERRO UDP-LAB | URL de teste invalida")
            return
        }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 12)
        request.httpMethod = "GET"
        URLSession(configuration: wifiOnlyConfiguration()).dataTask(with: request) { [weak self] _, response, error in
            DispatchQueue.main.async {
                guard let self = self, generation == self.requestGeneration else { return }
                if let error = error {
                    self.sendResponse("ERRO UDP-LAB | " + error.localizedDescription)
                    return
                }
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                self.sendResponse("BT6-UDP-RELAY | HTTPS example.com | HTTP \(code) | iPhone recebeu pedido da VPN Android e devolveu a resposta via BLE")
            }
        }.resume()
    }

    private func runTcpRelayDiagnostic() {
        requestGeneration += 1
        let generation = requestGeneration
        tcpConnection?.cancel()
        tcpFinished = false
        let host = "example.com"
        let port = NWEndpoint.Port(rawValue: 80)!
        let connection = NWConnection(host: NWEndpoint.Host(host),
                                      port: port, using: wifiTCP())
        tcpConnection = connection
        lastRequest = "TCP 80 example.com via BLE"
        serverStatus = "Abrindo conexao TCP real"

        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self = self, let connection = connection,
                  generation == self.requestGeneration, !self.tcpFinished else { return }
            switch state {
            case .ready:
                self.serverStatus = "TCP conectado a example.com:80"
                // Conteudo nao sensivel; deliberadamente HTTP na porta 80 para provar TCP cru.
                let request = "GET / HTTP/1.1\r\nHost: example.com\r\nAccept: text/plain, text/html\r\nConnection: close\r\n\r\n"
                connection.send(content: Data(request.utf8),
                                completion: .contentProcessed { [weak self] error in
                    guard let self = self else { return }
                    if let error = error,
                       generation == self.requestGeneration, !self.tcpFinished {
                        self.tcpFinished = true
                        connection.cancel()
                        self.sendResponse("ERRO TCP ENVIO | \(error.localizedDescription)")
                    }
                })
                self.readTcpResponse(connection, generation: generation, buffer: Data())
            case .failed(let error):
                self.tcpFinished = true
                self.tcpConnection = nil
                self.sendResponse("ERRO TCP | \(error.localizedDescription)")
            default:
                break
            }
        }
        connection.start(queue: .main)
        DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self, weak connection] in
            guard let self = self, generation == self.requestGeneration,
                  !self.tcpFinished else { return }
            self.tcpFinished = true
            connection?.cancel()
            self.tcpConnection = nil
            self.sendResponse("ERRO TCP | tempo limite 15 s")
        }
    }

    private func readTcpResponse(_ connection: NWConnection, generation: Int, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 4096) {
            [weak self, weak connection] data, _, isComplete, error in
            guard let self = self, let connection = connection,
                  generation == self.requestGeneration, !self.tcpFinished else { return }
            var received = buffer
            if let data = data { received.append(data) }
            if isComplete || error != nil || received.count >= 5500 {
                self.tcpFinished = true
                self.tcpConnection = nil
                connection.cancel()
                if received.isEmpty {
                    self.sendResponse("ERRO TCP | Nenhum byte recebido: \(error?.localizedDescription ?? "conexao encerrada")")
                    return
                }
                let raw = String(decoding: received.prefix(5500), as: UTF8.self)
                self.sendResponse("TCP REAL example.com:80 | \(received.count) bytes brutos\n" + raw)
            } else {
                self.readTcpResponse(connection, generation: generation, buffer: received)
            }
        }
    }

    // Navegacao HTTPS basica dentro do app Android. Nao cria proxy IP de
    // sistema: solicita HTML de websites publicos, sem JS/imagens externas.
    private func isAllowedBrowserURL(_ url: URL) -> Bool {
        guard url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased(),
              !host.isEmpty,
              url.user == nil, url.password == nil,
              url.port == nil || url.port == 443 else { return false }
        if host == "localhost" || host.hasSuffix(".local") ||
           host.hasSuffix(".internal") || host.hasSuffix(".localhost") ||
           host.hasSuffix(".onion") || host.hasSuffix(".test") ||
           host.hasSuffix(".invalid") || host.contains("%") {
            return false
        }
        // Avoid local IP literals. DNS-resolved private addresses must be
        // separately blocked in a future generic proxy.
        if host.contains(":") || host.allSatisfy({ $0.isNumber || $0 == "." }) {
            return false
        }
        return host.contains(".") && host.count <= 253
    }

    private func browserFailure(_ message: String) {
        sendResponse("BT6-BROWSER-ERROR\n" + message)
    }

    private func fetchBrowserPage(_ value: String) {
        guard value.utf8.count <= 220, let url = URL(string: value),
              isAllowedBrowserURL(url) else {
            browserFailure("Apenas sites HTTPS publicos, sem IP local ou porta personalizada.")
            return
        }
        requestGeneration += 1
        let generation = requestGeneration
        lastRequest = "BROWSER GET \(url.host ?? "")"
        serverStatus = "Navegador: lendo HTTPS do iPhone"
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 25)
        request.httpMethod = "GET"
        request.setValue("text/html,text/plain;q=0.9", forHTTPHeaderField: "Accept")
        request.setValue("BT6-LEOLEKI/0.7", forHTTPHeaderField: "User-Agent")
        // No cookies or stored credentials; public pages only.
        URLSession(configuration: wifiOnlyConfiguration()).dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self = self, generation == self.requestGeneration else { return }
                if let error = error {
                    self.browserFailure("Erro HTTPS: " + error.localizedDescription)
                    return
                }
                guard let http = response as? HTTPURLResponse,
                      let finalURL = http.url,
                      self.isAllowedBrowserURL(finalURL) else {
                    self.browserFailure("Redirecionamento nao permitido")
                    return
                }
                let raw = data ?? Data()
                if raw.count > 220_000 {
                    self.browserFailure("Pagina grande demais. Tente site com texto mais leve.")
                    return
                }
                let mime = (http.mimeType ?? "").lowercased()
                guard mime == "text/html" || mime == "text/plain" else {
                    self.browserFailure("Este navegador experimental aceita apenas HTML ou texto.")
                    return
                }
                var html = String(decoding: raw, as: UTF8.self)
                // Sanitize active elements. JS also disabled on Android.
                html = html.replacingOccurrences(
                    of: "(?is)<(script|style|iframe|object|embed|video|audio|svg|form|noscript)[^>]*>.*?</\\1>",
                    with: " ", options: .regularExpression)
                html = html.replacingOccurrences(
                    of: "(?is)<(img|link|meta|base|source|input|button)[^>]*>",
                    with: " ", options: .regularExpression)
                html = html.replacingOccurrences(
                    of: "(?i)\\s+on[a-z]+\\s*=\\s*(\"[^\"]*\"|'[^']*'|[^\\s>]+)",
                    with: "", options: .regularExpression)
                let compact = html.replacingOccurrences(of: "\\s{2,}", with: " ",
                                                        options: .regularExpression)
                let htmlFragment = String(compact.prefix(10000))
                let header = "BT6-BROWSER-OK\n" + finalURL.absoluteString + "\n"
                self.sendResponse(header + "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width, initial-scale=1'></head><body>" +
                                  "<p><small>BT6 via Bluetooth · HTTP \(http.statusCode)</small></p>" +
                                  htmlFragment + "</body></html>")
            }
        }.resume()
    }

    private func fetchPage(_ value: String, summaryOnly: Bool) {
        requestGeneration += 1
        let generation = requestGeneration
        guard let url = URL(string: value),
              url.scheme?.lowercased() == "https",
              let host = url.host?.lowercased(),
              Self.permittedHosts.contains(host),
              url.user == nil, url.password == nil,
              (url.port == nil || url.port == 443) else {
            lastRequest = "URL nao permitida"
            sendResponse("ERRO | Apenas HTTPS de example.com, www.iana.org ou pt.wikipedia.org.")
            return
        }
        lastRequest = "GET \(url.absoluteString)"
        serverStatus = "Buscando pagina via Wi-Fi do iPhone"

        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 15)
        request.httpMethod = "GET"
        request.setValue("text/html,text/plain", forHTTPHeaderField: "Accept")
        URLSession(configuration: wifiOnlyConfiguration()).dataTask(with: request) { [weak self] data, response, error in
            DispatchQueue.main.async {
                guard let self = self, generation == self.requestGeneration else { return }
                if let error = error {
                    self.sendResponse("ERRO INTERNET | \(error.localizedDescription)")
                    return
                }
                let code = (response as? HTTPURLResponse)?.statusCode ?? 0
                let body = String(decoding: data ?? Data(), as: UTF8.self)
                // Leitor de texto: remove scripts, CSS e marcacao HTML, sem baixar imagens.
                let withoutScripts = body.replacingOccurrences(
                    of: "(?is)<(script|style|noscript)[^>]*>.*?</\\1>",
                    with: " ", options: .regularExpression)
                let noTags = withoutScripts.replacingOccurrences(
                    of: "(?s)<[^>]*>", with: " ", options: .regularExpression)
                let decoded = noTags
                    .replacingOccurrences(of: "&nbsp;", with: " ")
                    .replacingOccurrences(of: "&amp;", with: "&")
                    .replacingOccurrences(of: "&lt;", with: "<")
                    .replacingOccurrences(of: "&gt;", with: ">")
                    .replacingOccurrences(of: "&quot;", with: "\"")
                    .replacingOccurrences(of: "&#39;", with: "'")
                    .replacingOccurrences(of: "\\s+", with: " ", options: .regularExpression)
                let maxText = summaryOnly ? 180 : 5700
                let text = String(decoded.prefix(maxText))
                self.sendResponse("HTTP \(code) | \(host)\n\(text)")
            }
        }.resume()
    }

    private static func fnv1a(_ bytes: Data) -> UInt32 {
        var hash: UInt32 = 2166136261
        for byte in bytes {
            hash = (hash ^ UInt32(byte)) &* 16777619
        }
        return hash
    }

    private func sendResponse(_ message: String) {
        lastResponse = String(message.prefix(150))
        guard !subscribers.isEmpty, responseCharacteristic != nil else {
            serverStatus = "Nenhum Redmi inscrito nas notificacoes"
            return
        }
        // Resposta completa (limite 8 KiB); um pacote por notificacao ATT de ate 20 bytes.
        let payload = Data(message.utf8.prefix(14500))
        transferredBytes = payload.count
        outgoing.removeAll()
        outgoing.append(Data([0x01, 0x03]))
        let bytes = [UInt8](payload)
        var cursor = 0
        while cursor < bytes.count {
            let end = min(cursor + 17, bytes.count)
            outgoing.append(Data([0x02] + Array(bytes[cursor..<end])))
            cursor = end
        }
        let hash = Self.fnv1a(payload)
        let length = UInt32(payload.count)
        var endPacket: [UInt8] = [0x03]
        for shift in stride(from: 0, through: 24, by: 8) {
            endPacket.append(UInt8(truncatingIfNeeded: hash >> shift))
        }
        for shift in stride(from: 0, through: 24, by: 8) {
            endPacket.append(UInt8(truncatingIfNeeded: length >> shift))
        }
        outgoing.append(Data(endPacket))
        serverStatus = "Enviando \(payload.count) bytes ao Redmi"
        flushPackets()
    }

    private func flushPackets() {
        guard !subscribers.isEmpty, let characteristic = responseCharacteristic else { return }
        while !outgoing.isEmpty {
            let packet = outgoing[0]
            guard manager.updateValue(packet, for: characteristic, onSubscribedCentrals: nil) else { return }
            outgoing.removeFirst()
        }
        serverStatus = "Dados transmitidos por BLE"
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        flushPackets()
    }
    private func wifiTCP() -> NWParameters {
        let p = NWParameters.tcp; p.requiredInterfaceType = .wifi; return p
    }
    private func wifiOnlyConfiguration() -> URLSessionConfiguration {
        let c = URLSessionConfiguration.ephemeral
        c.allowsCellularAccess = false
        return c
    }
    private func stopInternet() {
        let active = Array(relays.values)
        relays.removeAll()
        for relay in active { relay.close() }
        if let old = psm { manager.unpublishL2CAPChannel(old) }
        psm = nil
        internetStatus = "Canal de internet desligado"
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didPublishL2CAPChannel PSM: CBL2CAPPSM, error: Error?) {
        guard error == nil else { internetStatus = "Falha L2CAP: \(error!.localizedDescription)"; return }
        psm = PSM
        internetStatus = "Internet pronta · L2CAP criptografado · Wi-Fi obrigatorio"
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveRead request: CBATTRequest) {
        guard request.characteristic.uuid == Self.psmUUID, let port = psm else {
            peripheral.respond(to: request, withResult: .attributeNotFound); return
        }
        let value = Data([0x08, UInt8(port & 255), UInt8(port >> 8)])
        guard request.offset <= value.count else {
            peripheral.respond(to: request, withResult: .invalidOffset); return
        }
        request.value = Data(value.dropFirst(request.offset))
        peripheral.respond(to: request, withResult: .success)
    }
    func peripheralManager(_ peripheral: CBPeripheralManager, didOpen channel: CBL2CAPChannel?, error: Error?) {
        guard error == nil, let channel = channel else { internetStatus = "Falha ao abrir canal de internet"; return }
        guard relays.count < 12 else { channel.inputStream.close(); channel.outputStream.close(); return }
        let id = UUID()
        let relay = L2CAPRelay(channel: channel)
        relays[id] = relay
        relay.onClose = { [weak self] in
            guard let self = self else { return }
            self.relays.removeValue(forKey: id)
            self.internetStatus = "Canais ativos: \(self.relays.count) · \(self.internetBytes) bytes"
        }
        relay.onBytes = { [weak self] n in self?.internetBytes += n }
        internetStatus = "Canais ativos: \(relays.count) · Wi-Fi obrigatorio"
    }

}
