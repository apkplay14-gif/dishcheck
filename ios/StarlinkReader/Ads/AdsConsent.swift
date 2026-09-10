import Foundation
import GoogleMobileAds
import UserMessagingPlatform

/// Збір згоди на обробку даних через Google UMP.
///
/// Порядок навмисно саме такий: спершу питаємо згоду, і лише коли UMP каже
/// «можна» — ініціалізуємо SDK реклами й вантажимо банер. Якщо зробити
/// навпаки, реклама встигне звернутись до Google до того, як користувач щось
/// вирішив.
///
/// За межами ЄС форма не показується взагалі: UMP сам визначає регіон і одразу
/// повертає дозвіл. Тобто для України користувач нічого не побачить.
@MainActor
final class AdsConsent: ObservableObject {
    static let shared = AdsConsent()

    /// Чи можна вантажити рекламу. Банер зʼявиться сам, коли стане true.
    @Published private(set) var canRequestAds = false

    /// Чи треба показувати пункт «Налаштування конфіденційності» — вимога GDPR.
    @Published private(set) var privacyOptionsRequired = false

    private var adsInitialized = false

    private init() {}

    func gather() {
        guard AppConfig.showAds else { return }

        let parameters = RequestParameters()
        parameters.isTaggedForUnderAgeOfConsent = false

        if AppConfig.isDebug, !AppConfig.consentTestDeviceID.isEmpty {
            let debugSettings = DebugSettings()
            debugSettings.geography = .EEA
            debugSettings.testDeviceIdentifiers = [AppConfig.consentTestDeviceID]
            parameters.debugSettings = debugSettings
        }

        ConsentInformation.shared.requestConsentInfoUpdate(with: parameters) { [weak self] error in
            guard let self else { return }

            if let error {
                // Без відповіді від UMP реклама просто не показується — на
                // роботу застосунку це не впливає ніяк.
                print("consent request: \(error.localizedDescription)")
                Task { @MainActor in self.onConsentResolved() }
                return
            }

            Task { @MainActor in
                do {
                    try await ConsentForm.loadAndPresentIfRequired(from: nil)
                } catch {
                    print("consent form: \(error.localizedDescription)")
                }
                self.onConsentResolved()
            }
        }
    }

    /// Повторний показ форми: користувач має право змінити рішення будь-коли.
    func showPrivacyOptions() {
        Task { @MainActor in
            do {
                try await ConsentForm.presentPrivacyOptionsForm(from: nil)
            } catch {
                print("privacy options: \(error.localizedDescription)")
            }
            onConsentResolved()
        }
    }

    private func onConsentResolved() {
        privacyOptionsRequired = ConsentInformation.shared.privacyOptionsRequirementStatus == .required

        guard ConsentInformation.shared.canRequestAds else {
            canRequestAds = false
            return
        }

        if !adsInitialized {
            adsInitialized = true
            // Ініціалізація SDK помітно довга — не блокує головний потік,
            // бо сама функція вже викликається з MainActor-задачі, а не з UI.
            MobileAds.shared.start()
        }
        canRequestAds = true
    }
}
