import Foundation
import CoreBluetooth
import Network

// One encrypted L2CAP credit-based channel per SOCKS session. No ATT fragmentation
// on the data path. Stream and NWConnection queues apply bounded backpressure.
final class L2CAPRelay: NSObject, StreamDelegate {
    private var channel: CBL2CAPChannel?
    private let inputStream: InputStream
    private let outputStream: OutputStream
    private let requireWiFi: Bool
    private var input = [UInt8]()
    private var output = Data()
    private var connection: NWConnection?
    private var udpConnections = [String: NWConnection]()
    private var phase = 0 // greeting, request, connecting, TCP, UDP
    private var sending = false
    private var receiving = false
    private var closed = false
    private var closingAfterDrain = false
    private var inputEnded = false
    private var lastActivity = Date()
    private func trace(_ text: String) { if ProcessInfo.processInfo.environment["BT6_TRACE"] == "1" { FileHandle.standardError.write(Data((text + "\n").utf8)) } }
    private var timer: Timer?
    var onClose: (() -> Void)?
    var onBytes: ((Int) -> Void)?

    convenience init(channel: CBL2CAPChannel) {
        self.init(inputStream: channel.inputStream, outputStream: channel.outputStream)
        self.channel = channel
    }
    // Socket streams in CI exercise the same relay; real Bluetooth always requires Wi-Fi.
    init(inputStream: InputStream, outputStream: OutputStream, requireWiFi: Bool = true) {
        self.inputStream = inputStream
        self.outputStream = outputStream
        self.requireWiFi = requireWiFi
        super.init()
        inputStream.delegate = self
        outputStream.delegate = self
        inputStream.schedule(in: .main, forMode: .common)
        outputStream.schedule(in: .main, forMode: .common)
        inputStream.open()
        outputStream.open()
        timer = Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { [weak self] _ in
            guard let self = self else { return }
            if Date().timeIntervalSince(self.lastActivity) > (self.phase < 3 ? 20 : 120) { self.close() }
        }
    }

    func close() {
        guard !closed else { return }
        closed = true
        timer?.invalidate(); timer = nil
        connection?.cancel(); connection = nil
        for c in udpConnections.values { c.cancel() }
        udpConnections.removeAll()
        inputStream.close(); outputStream.close()
        inputStream.remove(from: .main, forMode: .common)
        outputStream.remove(from: .main, forMode: .common)
        inputStream.delegate = nil; outputStream.delegate = nil
        input.removeAll(); output.removeAll()
        onClose?(); onClose = nil
    }

    func stream(_ stream: Stream, handle event: Stream.Event) {
        if event.contains(.errorOccurred) { close(); return }
        if event.contains(.hasBytesAvailable) { readInput() }
        if event.contains(.hasSpaceAvailable) { flush() }
        if event.contains(.endEncountered) {
            if stream === inputStream && phase == 3 {
                inputEnded = true
                finishInputIfNeeded()
            } else { close() }
        }
    }

    private func enqueue(_ bytes: Data) {
        guard !closed else { return }
        guard output.count + bytes.count <= 131072 else { close(); return }
        output.append(bytes); flush()
    }
    private func flush() {
        guard !closed else { return }
        while !output.isEmpty && outputStream.hasSpaceAvailable {
            let n = output.withUnsafeBytes { raw in
                outputStream.write(raw.bindMemory(to: UInt8.self).baseAddress!, maxLength: min(output.count, 16384))
            }
            if n < 0 { close(); return }
            if n == 0 { break }
            output.removeFirst(n); lastActivity = Date(); onBytes?(n)
        }
        if output.isEmpty && closingAfterDrain { close(); return }
        receiveTCP()
    }

    private func readInput() {
        guard !closed, !sending, phase != 2, !closingAfterDrain else { return }
        var buffer = [UInt8](repeating: 0, count: 16384)
        while inputStream.hasBytesAvailable && !sending && phase != 2 && !closed {
            let n = inputStream.read(&buffer, maxLength: buffer.count)
            if n < 0 { close(); return }
            if n == 0 { break }
            lastActivity = Date(); onBytes?(n)
            input.append(contentsOf: buffer.prefix(n))
            guard input.count <= 131072 else { close(); return }
            process()
        }
    }
    private func reject(_ code: UInt8) {
        closingAfterDrain = true
        enqueue(Data([5, code, 0, 1, 0, 0, 0, 0, 0, 0]))
    }
    private func process() {
        guard !closed else { return }
        do {
            if phase == 0 {
                guard input.count >= 2 else { return }
                guard input[0] == 5, input[1] > 0 else { close(); return }
                let size = Int(input[1]) + 2
                guard input.count >= size else { return }
                guard input[2..<size].contains(0) else {
                    closingAfterDrain = true; enqueue(Data([5,255])); return
                }
                input.removeFirst(size); phase = 1; enqueue(Data([5,0]))
            }
            if phase == 1 {
                guard input.count >= 4 else { return }
                guard input[0] == 5, input[2] == 0 else { reject(1); return }
                let command = input[1]
                guard command == 1 || command == 5 else { reject(7); return }
                guard let address = try SocksProtocol.address(input, offset: 3) else { return }
                input.removeFirst(3 + address.size)
                if command == 5 {
                    phase = 4; enqueue(Data([5,0,0,1,0,0,0,0,0,0]))
                } else {
                    guard address.port != 0 else { reject(4); return }
                    phase = 2
                    let parameters = NWParameters.tcp
                    if requireWiFi { parameters.requiredInterfaceType = .wifi }
                    let c = NWConnection(host: NWEndpoint.Host(address.host), port: NWEndpoint.Port(rawValue: address.port)!, using: parameters)
                    connection = c
                    c.stateUpdateHandler = { [weak self] state in
                        guard let self = self, !self.closed else { return }
                        self.trace("TCP state: \(state)")
                        switch state {
                        case .ready:
                            self.phase = 3
                            self.enqueue(Data([5,0,0,1,0,0,0,0,0,0]))
                            self.process(); self.readInput(); self.receiveTCP()
                        case .waiting, .failed:
                            if self.phase == 3 { self.close() } else { self.reject(4) }
                        default: break
                        }
                    }
                    c.start(queue: .main)
                    DispatchQueue.main.asyncAfter(deadline: .now() + 8) { [weak self] in
                        guard let self = self, !self.closed, self.phase == 2 else { return }
                        c.cancel(); self.reject(4)
                    }
                }
            }
            if phase == 3 && !sending && !input.isEmpty {
                let chunk = Data(input); input.removeAll(keepingCapacity: true); sending = true
                connection?.send(content: chunk, completion: .contentProcessed { [weak self] error in
                    guard let self = self, !self.closed else { return }
                    self.sending = false
                    if error != nil { self.close(); return }
                    self.process(); self.readInput(); self.finishInputIfNeeded()
                })
            }
            if phase == 4 {
                while !sending, let (target, payload) = try SocksProtocol.takeUDP(&input) {
                    sendUDP(target, payload)
                }
            }
        } catch { close() }
    }
    private func finishInputIfNeeded() {
        if inputEnded && !sending && input.isEmpty {
            connection?.send(content: nil, isComplete: true, completion: .contentProcessed { [weak self] error in
                if error != nil { self?.close() }
            })
            inputEnded = false
        }
    }
    private func receiveTCP() {
        guard !closed, phase == 3, !receiving, !closingAfterDrain, output.count < 32768 else { return }
        receiving = true
        connection?.receive(minimumIncompleteLength: 1, maximumLength: 16384) { [weak self] data, _, complete, error in
            guard let self = self, !self.closed else { return }
            self.receiving = false
            if complete || error != nil { self.closingAfterDrain = true }
            if let data = data { self.enqueue(data) } else { self.flush() }
            self.receiveTCP()
        }
    }
    private func sendUDP(_ target: SocksProtocol.Address, _ payload: Data) {
        guard target.port != 0 else { return }
        trace("UDP payload: \(payload.count)")
        let key = "\(target.host):\(target.port)"
        let c: NWConnection
        if let existing = udpConnections[key] { c = existing }
        else {
            guard udpConnections.count < 32 else { close(); return }
            let p = NWParameters.udp; if requireWiFi { p.requiredInterfaceType = .wifi }
            c = NWConnection(host: NWEndpoint.Host(target.host), port: NWEndpoint.Port(rawValue: target.port)!, using: p)
            udpConnections[key] = c
            c.stateUpdateHandler = { [weak self, weak c] state in
                guard let self = self, let c = c, !self.closed else { return }
                switch state {
                case .ready: self.trace("UDP ready"); self.receiveUDP(c, target)
                case .waiting, .failed: self.udpConnections.removeValue(forKey: key); c.cancel()
                default: break
                }
            }
            c.start(queue: .main)
        }
        // Only one datagram queued to Network at a time; L2CAP credits provide flow control.
        sending = true
        c.send(content: payload.isEmpty ? nil : payload, contentContext: .defaultMessage, isComplete: true, completion: .contentProcessed { [weak self] error in
            guard let self = self, !self.closed else { return }
            self.trace("UDP completion: \(String(describing: error))")
            self.sending = false
            // UDP is best effort. Oversize datagrams (EMSGSIZE) are dropped;
            // an individual UDP error must not destroy other destinations.
            self.process(); self.readInput()
        })
    }
    private func receiveUDP(_ c: NWConnection, _ target: SocksProtocol.Address) {
        guard !closed else { return }
        c.receiveMessage { [weak self, weak c] data, _, _, error in
            guard let self = self, let c = c, !self.closed else { return }
            if let data = data, let frame = try? SocksProtocol.udpFrame(address: target.wire, payload: data) {
                self.enqueue(frame)
            }
            if error == nil { self.receiveUDP(c, target) }
        }
    }
}
