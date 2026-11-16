import Foundation

// iPhone -> Android. An ordered EOF marker acknowledges logical stream completion
// without closing a Bluetooth OutputStream while the OS still has queued radio data.
enum ResponseFrames {
    static let end = Data([0xD2, 0, 0])
    static func encode(_ payload: Data) -> Data {
        var result = Data()
        var offset = 0
        let bytes = [UInt8](payload)
        while offset < bytes.count {
            let count = min(16384, bytes.count - offset)
            result.append(contentsOf: [0xD1, UInt8(count >> 8), UInt8(count & 255)])
            result.append(contentsOf: bytes[offset..<(offset + count)])
            offset += count
        }
        return result
    }
}
