import SwiftUI
import GoogleMobileAds

/// Банер унизу головного екрана.
///
/// Свідомо тільки тут: на екрані сканера й у картці комплекту реклами немає —
/// там людина працює з даними, і банер поверх серійника це помилка, а не дохід.
///
/// Банер завантажується у фоні одразу, як тільки згода дозволяє рекламу, але
/// поки не прийшла відповідь — не займає жодного пікселя. Якщо заповнення не
/// прийшло (немає інтернету — звична річ на монтажі, або AdMob не дав
/// заповнення), так і лишається без місця на екрані.
struct AdBanner: View {
    @ObservedObject private var consent = AdsConsent.shared
    @State private var loaded = false

    var body: some View {
        if AppConfig.showAds, consent.canRequestAds {
            GeometryReader { geo in
                BannerContainer(width: geo.size.width, loaded: $loaded)
            }
            .frame(height: loaded ? Self.maxBannerHeight : 0)
        }
    }

    /// Стеля висоти банера в points. Більше — більший дохід, менше екрана для роботи.
    private static let maxBannerHeight: CGFloat = 60
}

private struct BannerContainer: UIViewRepresentable {
    let width: CGFloat
    @Binding var loaded: Bool

    func makeUIView(context: Context) -> BannerView {
        let adSize = currentOrientationInlineAdaptiveBanner(width: max(width, 320))
        let banner = BannerView(adSize: adSize)
        banner.adUnitID = AppConfig.adUnitID
        banner.delegate = context.coordinator
        banner.load(Request())
        return banner
    }

    func updateUIView(_ uiView: BannerView, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(loaded: $loaded) }

    final class Coordinator: NSObject, BannerViewDelegate {
        @Binding var loaded: Bool
        init(loaded: Binding<Bool>) { _loaded = loaded }

        func bannerViewDidReceiveAd(_ bannerView: BannerView) {
            loaded = true
        }

        func bannerView(_ bannerView: BannerView, didFailToReceiveAdWithError error: Error) {
            loaded = false
        }
    }
}

/// Пункт «Налаштування конфіденційності». GDPR вимагає дати можливість
/// змінити рішення будь-коли, тому кнопка зʼявляється сама там, де згода була
/// потрібна. Поза ЄС її не видно.
struct PrivacyOptionsEntry: View {
    @ObservedObject private var consent = AdsConsent.shared

    var body: some View {
        if AppConfig.showAds, consent.privacyOptionsRequired {
            Button {
                AdsConsent.shared.showPrivacyOptions()
            } label: {
                Text(NSLocalizedString("privacy_options", comment: ""))
                    .font(.subheadline)
                    .foregroundColor(Brand.accent)
            }
        }
    }
}
