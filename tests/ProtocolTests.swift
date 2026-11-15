import Foundation

@main struct Tests {
    static func main() throws {
        let domain: [UInt8] = [3,11] + Array("example.com".utf8) + [1,187]
        let ipv4: [UInt8] = [1,8,8,8,8,0,53]
        let ipv6: [UInt8] = [4] + [UInt8](repeating: 0,count:15) + [1,0,53]
        for raw in [domain,ipv4,ipv6] {
            for split in 0..<raw.count {
                let partial = try SocksProtocol.address(Array(raw.prefix(split)),offset:0)
                precondition(partial == nil, "partial address")
            }
            let address = try SocksProtocol.address(raw,offset:0)!
            precondition(address.wire == Data(raw))
            let payload=Data((0..<4096).map {UInt8($0 % 256)})
            let frame=try SocksProtocol.udpFrame(address:address.wire,payload:payload)
            for split in 0..<frame.count {
                var partial=Array(frame.prefix(split))
                let fragment = try SocksProtocol.takeUDP(&partial)
                precondition(fragment == nil)
            }
            var input=Array(frame+frame)
            for _ in 0..<2 {
                let result=try SocksProtocol.takeUDP(&input)!
                precondition(result.0.host == address.host && result.1 == payload)
            }
            precondition(input.isEmpty)
        }
        let invalid: [[UInt8]] = [[2], [3,0], [3,1,0,0,53]]
        for raw in invalid {
            do { _ = try SocksProtocol.address(raw,offset:0); fatalError("invalid accepted") }
            catch SocksProtocol.WireError.invalid {}
        }
        var malformed: [UInt8] = [0,1,9] + ipv4 + [4]
        do { _ = try SocksProtocol.takeUDP(&malformed);fatalError("bad header") } catch SocksProtocol.WireError.invalid {}
        print("PASS: IPv4/IPv6/domain, all split positions, coalesced UDP frames, malformed address/header, 4096-byte payload integrity")
    }
}
