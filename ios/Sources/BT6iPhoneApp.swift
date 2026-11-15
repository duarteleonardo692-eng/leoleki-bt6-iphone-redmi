import SwiftUI

@main
struct BT6iPhoneApp: App {
    @StateObject private var bridge = BT6PeripheralBridge()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bridge)
        }
    }
}
