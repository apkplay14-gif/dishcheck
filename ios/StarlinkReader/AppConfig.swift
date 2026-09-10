import Foundation

/// Аналог полів BuildConfig з app/build.gradle.kts на Android.
enum AppConfig {
    /// Один вимикач на всю рекламу: false — банера немає взагалі.
    static let showAds = true

    /// ТЕСТОВІ ідентифікатори Google (офіційні, з developers.google.com/admob).
    ///
    /// AdMob видає окремий App ID і ad unit для КОЖНОЇ платформи — Android
    /// ідентифікатори з app/build.gradle.kts на iOS не працюють. Перш ніж
    /// вмикати бойову рекламу тут:
    ///   1. Створіть окрему заявку типу iOS у консолі AdMob для цього ж додатку.
    ///   2. Підставте її App ID у Info.plist (ключ GADApplicationIdentifier,
    ///      див. ios/project.yml) та ad unit нижче.
    /// Показувати чи тиснути власну бойову рекламу зі свого телефона не можна —
    /// один клік по власному оголошенню блокує акаунт AdMob довічно.
    static let adUnitID = "ca-app-pub-3940256099942544/2435281174"

    /// Форма згоди GDPR показується лише в ЄС. Щоб побачити її з іншого регіону,
    /// впишіть сюди хеш тестового пристрою (UMP друкує його в консолі під час
    /// запуску: "To enable debug mode for this device...").
    static let consentTestDeviceID = ""

    static var isDebug: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }
}
