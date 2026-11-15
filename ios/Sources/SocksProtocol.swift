import Foundation
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

// Pure wire codec shared by the real relay and offline tests. All integers are network byte order.
enum SocksProtocol {
    struct Address {
        let host: String
        let port: UInt16
        let wire: Data
        let size: Int
    }
    enum WireError: Error { case invalid }

    static func address(_ bytes: [UInt8], offset: Int) throws -> Address? {
        guard offset < bytes.count else { return nil }
        var length: Int
        var host: String
        switch bytes[offset] {
        case 1:
            length = 7
            guard bytes.count - offset >= length else { return nil }
            host = bytes[(offset+1)...(offset+4)].map(String.init).joined(separator: ".")
        case 4:
            length = 19
            guard bytes.count - offset >= length else { return nil }
            var raw = Array(bytes[(offset+1)...(offset+16)])
            var text = [CChar](repeating: 0, count: Int(INET6_ADDRSTRLEN))
            guard inet_ntop(AF_INET6, &raw, &text, socklen_t(text.count)) != nil else { throw WireError.invalid }
            host = String(cString: text)
        case 3:
            guard bytes.count > offset + 1 else { return nil }
            let count = Int(bytes[offset+1])
            guard count > 0 else { throw WireError.invalid }
            length = count + 4
            guard bytes.count - offset >= length else { return nil }
            guard let name = String(bytes: bytes[(offset+2)..<(offset+2+count)], encoding: .utf8),
                  !name.contains("\0"), !name.contains("/"), !name.contains(":") else { throw WireError.invalid }
            host = name
        default: throw WireError.invalid
        }
        let port = UInt16(bytes[offset+length-2]) << 8 | UInt16(bytes[offset+length-1])
        return Address(host: host, port: port, wire: Data(bytes[offset..<(offset+length)]), size: length)
    }

    // HEV command 5: length:u16, headerLength:u8, SOCKS address, payload.
    static func udpFrame(address: Data, payload: Data) throws -> Data {
        guard payload.count <= 65507, address.count + 3 <= 255 else { throw WireError.invalid }
        return Data([UInt8(payload.count >> 8), UInt8(payload.count & 255), UInt8(address.count + 3)]) + address + payload
    }
    static func takeUDP(_ bytes: inout [UInt8]) throws -> (Address, Data)? {
        guard bytes.count >= 3 else { return nil }
        let length = Int(bytes[0]) << 8 | Int(bytes[1])
        let header = Int(bytes[2])
        guard header >= 10, length <= 65507 else { throw WireError.invalid }
        guard let target = try address(bytes, offset: 3) else { return nil }
        guard header == target.size + 3 else { throw WireError.invalid }
        guard bytes.count >= header + length else { return nil }
        let payload = Data(bytes[header..<(header+length)])
        bytes.removeFirst(header+length)
        return (target, payload)
    }
}
