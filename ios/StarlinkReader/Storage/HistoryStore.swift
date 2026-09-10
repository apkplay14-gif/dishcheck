import Foundation

/// Локальне сховище історії. Дані нікуди не надсилаються — це звичайний JSON-файл
/// у приватній папці додатка (Application Support), як і на Android.
final class HistoryStore {
    private let fileURL: URL
    private let lock = NSLock()

    init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("history.json")
    }

    func load() -> [Reading] {
        lock.lock()
        defer { lock.unlock() }
        guard let data = try? Data(contentsOf: fileURL) else { return [] }
        return (try? JSONDecoder().decode([Reading].self, from: data)) ?? []
    }

    func save(_ readings: [Reading]) {
        lock.lock()
        defer { lock.unlock() }
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted]
        // Немає сенсу падати через збій запису — історія не критична для читання даних.
        guard let data = try? encoder.encode(readings) else { return }
        try? data.write(to: fileURL, options: .atomic)
    }
}
