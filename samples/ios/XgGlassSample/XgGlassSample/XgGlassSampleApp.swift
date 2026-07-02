import MWDATCore
import SwiftUI

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
                        _ = try? await Wearables.shared.handleUrl(url)
                    }
                }
        }
    }
}
