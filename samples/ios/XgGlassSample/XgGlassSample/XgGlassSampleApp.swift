import SwiftUI
import XgGlassMetaTesting

@main
struct XgGlassSampleApp: App {
    init() {
        do {
            try MetaDATRuntime.configureIfNeeded()
        } catch {
            print("Meta DAT configure failed: \(error.localizedDescription)")
        }
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    Task {
                        try? await MetaDATRuntime.handleOpenURL(url)
                    }
                }
        }
    }
}
