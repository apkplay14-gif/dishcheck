import SwiftUI

enum Screen: Equatable {
    case home
    case history
    case detail(uid: String)
}

/// Той самий простий стейт-машин, що й AppRoot у Screens.kt — без
/// NavigationStack, бо екранів лише три і переходи не потребують стека.
struct AppRootView: View {
    @EnvironmentObject var state: AppState
    @State private var screen: Screen = .home
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            switch screen {
            case .home:
                HomeScreen(onOpenHistory: { screen = .history })
            case .history:
                HistoryScreen(onBack: { screen = .home }, onOpen: { uid in screen = .detail(uid: uid) })
            case .detail(let uid):
                if state.history.contains(where: { $0.uid == uid }) {
                    DetailScreen(uid: uid, onBack: { screen = .history })
                } else {
                    // Запис видалили з екрана деталей — показуємо список.
                    HistoryScreen(onBack: { screen = .home }, onOpen: { uid in screen = .detail(uid: uid) })
                }
            }
        }
        .preferredColorScheme(.dark)
        .fullScreenCover(item: $state.pendingScan) { pending in
            QrScannerView(
                title: NSLocalizedString(pending.step.titleKey, comment: ""),
                progress: pending.progress,
                onFinish: { value in
                    state.completeScan(uid: pending.uid, step: pending.step, rawValue: value)
                }
            )
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                Task { await state.refreshLink() }
            } else if phase == .background {
                state.flushPersist()
            }
        }
    }
}
