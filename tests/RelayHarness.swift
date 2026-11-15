import Foundation

@main struct RelayHarness {
    static func main() {
        var input: InputStream?
        var output: OutputStream?
        Stream.getStreamsToHost(withName: "127.0.0.1", port: Int(CommandLine.arguments[1])!, inputStream: &input, outputStream: &output)
        let relay = L2CAPRelay(inputStream: input!, outputStream: output!, requireWiFi: false)
        relay.onClose = { exit(0) }
        withExtendedLifetime(relay) { RunLoop.main.run() }
    }
}
