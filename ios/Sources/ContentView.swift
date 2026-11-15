import SwiftUI

struct ContentView: View {
    @EnvironmentObject private var bridge: BT6PeripheralBridge

    var body: some View {
        NavigationStack {
            Form {
                Section("Ponte Bluetooth BT6 v0.7") {
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
                    Text("Na v0.7, o Redmi pode abrir paginas HTTPS dentro do navegador BT6 e seguir links, recebendo HTML simplificado pela ponte Bluetooth.")
                        .font(.footnote)
                }
                Section("Limites desta versao") {
                    Text("Navegador experimental para sites HTTPS publicos: texto e links. Nao suporta videos, JS, contas, uploads nem imagens externas.")
                    Text("Ainda nao fornece internet geral ao Chrome, WhatsApp ou outros aplicativos. O iOS pode limitar tarefas de rede e Bluetooth em segundo plano.")
                        .font(.footnote)
                }
            }
            .navigationTitle("BT6 iPhone v0.7")
        }
    }
}
