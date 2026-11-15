import Foundation

// Bounded local diagnostics: lifecycle/byte counts only, no URLs or payloads.
enum RelayDiagnostics {
    private static var url: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("bt6-relay.log")
    }
    static func record(_ event: String) {
        let line = String(format: "%.3f ", Date().timeIntervalSince1970) + event + "\n"
        guard let bytes = line.data(using: .utf8) else { return }
        do {
            try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            if !FileManager.default.fileExists(atPath: url.path) { try Data().write(to: url) }
            let handle = try FileHandle(forWritingTo: url)
            defer { try? handle.close() }
            let size = try handle.seekToEnd()
            if size > 65536 {
                let recent = try Data(contentsOf: url).suffix(32768)
                try handle.truncate(atOffset: 0)
                try handle.seek(toOffset: 0)
                try handle.write(contentsOf: recent)
            }
            try handle.write(contentsOf: bytes)
        } catch { /* Diagnostics must never interrupt the relay. */ }
    }
}
