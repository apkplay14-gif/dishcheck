import SwiftUI

@main
struct StarlinkReaderApp: App {
    @StateObject private var state = AppState()

    var body: some Scene {
        WindowGroup {
            AppRootView()
                .environmentObject(state)
                .onAppear {
                    // Спершу згода, і лише потім SDK реклами — інакше він
                    // звернеться до Google раніше, ніж користувач щось вирішив.
                    Task { @MainActor in
                        AdsConsent.shared.gather()
                    }
                }
        }
    }
}
