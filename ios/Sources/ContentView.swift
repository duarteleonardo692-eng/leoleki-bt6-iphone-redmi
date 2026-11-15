import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var bridge: BT6PeripheralBridge

    var body: some View {
        NavigationStack {
            Form {
                Section("Ponte Bluetooth BT6 v0.3") {
                    Text("iPhone XR → Redmi Note 11 sem PC durante a comunicacao")
                        .font(.subheadline)
                    LabeledContent("Bluetooth", value: bridge.bluetoothStatus)
                    LabeledContent("Servidor BLE", value: bridge.serverStatus)
                    LabeledContent("Redmi", value: bridge.clientStatus)
                    Button("Reiniciar anuncio BLE") { bridge.restartAdvertising() }
                }
                Section("Transferencia de dados") {
                    Text("Ultimo pedido: \(bridge.lastRequest)")
                    Text("Ultima resposta: \(bridge.lastResponse)")
                    LabeledContent("Bytes por BLE", value: "\(bridge.transferredBytes)")
                    Text("No Redmi, escolha um site e toque em Ler texto da internet. O iPhone busca o conteudo via Wi-Fi e envia o texto de volta em varios pacotes Bluetooth.")
                        .font(.footnote)
                }
                Section("Limites desta versao") {
                    Text("Esta e uma demonstracao de conteudo real dentro do aplicativo BT6 Redmi. Apenas HTTPS de example.com, www.iana.org e pt.wikipedia.org.")
                    Text("Ainda nao fornece internet geral ao Chrome, WhatsApp ou outros aplicativos. O iOS pode limitar tarefas de rede e Bluetooth em segundo plano.")
                        .font(.footnote)
                }
            }
            .navigationTitle("BT6 iPhone v0.3")
        }
    }
}
