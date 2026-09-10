import Foundation

/// Галочки «що зчитувати» — теж локально, поруч з історією.
final class SettingsStore {
    private let fileURL: URL

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("capture_settings.json")
    }

    func load() -> CaptureSettings {
        guard let data = try? Data(contentsOf: fileURL) else { return CaptureSettings() }
        return (try? JSONDecoder().decode(CaptureSettings.self, from: data)) ?? CaptureSettings()
    }

    func save(_ settings: CaptureSettings) {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted]
        // Втрата налаштувань не критична — наступного разу візьмуться типові.
        guard let data = try? encoder.encode(settings) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}
