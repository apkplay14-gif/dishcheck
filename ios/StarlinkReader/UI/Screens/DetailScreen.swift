import SwiftUI

struct DetailScreen: View {
    @EnvironmentObject var state: AppState
    let uid: String
    let onBack: () -> Void

    var body: some View {
        let reading = state.history.first(where: { $0.uid == uid })
        VStack(spacing: 0) {
            HStack {
                Button(action: onBack) {
                    RowIcon(systemName: AppIcon.back, tint: Brand.textPrimary, size: 20)
                }
                Spacer()
                if let reading {
                    Text(reading.titleOrNull ?? NSLocalizedString("record_untitled", comment: ""))
                        .font(.monoValue)
                        .lineLimit(1)
                        .foregroundColor(Brand.textPrimary)
                }
                Spacer()
                Color.clear.frame(width: 20, height: 20)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)

            if let reading {
                ScrollView {
                    VStack {
                        ReadingCard(reading: reading, showDelete: true)
                        Spacer(minLength: 16)
                    }
                    .padding(16)
                }
            }
        }
        .background(Brand.ink.ignoresSafeArea())
    }
}
