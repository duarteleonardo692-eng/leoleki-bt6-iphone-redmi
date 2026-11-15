import Foundation
import CoreBluetooth
import Combine

/// BT6 v0.3: transmite texto real de páginas HTTPS por pacotes BLE.
final class BT6PeripheralBridge: NSObject, ObservableObject, CBPeripheralManagerDelegate {
    static let serviceUUID = CBUUID(string: "AD08C143-7581-4612-9D63-20D9540C4A11")
    static let requestUUID = CBUUID(string: "AD08C143-7581-4612-9D63-20D9540C4A12")
    static let responseUUID = CBUUID(string: "AD08C143-7581-4612-9D63-20D9540C4A13")
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
    private var requestGeneration = 0

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
        service.characteristics = [request, response]
        manager.add(service)
        serverStatus = "Configurando servico"
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        if let error = error {
            serverStatus = "Falha no servico: \(error.localizedDescription)"
            return
        }
        peripheral.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID],
            CBAdvertisementDataLocalNameKey: "BT6-LEOLEKI"
        ])
    }

    func peripheralManagerDidStartAdvertising(_ peripheral: CBPeripheralManager, error: Error?) {
        serverStatus = error == nil ? "Anunciando BT6 v0.3" : "Falha no anuncio: \(error!.localizedDescription)"
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
            if opcode == 0x44 && (!receivingURL || incomingURL.count + bytes.count - 1 > 220) {
                peripheral.respond(to: request, withResult: .invalidAttributeValueLength)
                receivingURL = false
                incomingURL.removeAll()
                continue
            }
            peripheral.respond(to: request, withResult: .success)

            switch opcode {
            case 0x50: // PING v0.1
                lastRequest = "PING"
                sendResponse("PONG | iPhone recebeu o pedido Bluetooth")
            case 0x47: // Teste antigo
                fetchPage("https://example.com", summaryOnly: true)
            case 0x55: // Início de URL (U)
                incomingURL.removeAll()
                receivingURL = true
                lastRequest = "Recebendo endereco HTTPS"
            case 0x44: // Dados da URL (D), no maximo 17 bytes
                if receivingURL { incomingURL.append(bytes.dropFirst()) }
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
        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
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
        let payload = Data(message.utf8.prefix(8000))
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
}
