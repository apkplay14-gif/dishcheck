import Foundation
import UIKit

enum Sharing {
    private static func dateFormatter(_ format: String) -> DateFormatter {
        let f = DateFormatter()
        f.dateFormat = format
        f.locale = Locale.current
        return f
    }

    /// Reading.timestamp зберігається як мілісекунди від епохи (як в Android).
    private static func date(_ timestampMs: Double) -> Date {
        Date(timeIntervalSince1970: timestampMs / 1000)
    }

    static func formatDate(_ timestampMs: Double) -> String {
        dateFormatter("dd.MM.yyyy HH:mm").string(from: date(timestampMs))
    }

    static func formatTime(_ timestampMs: Double) -> String {
        dateFormatter("HH:mm").string(from: date(timestampMs))
    }

    /// День зчитування — за ним історія групується.
    static func dayOf(_ timestampMs: Double) -> Date {
        Calendar.current.startOfDay(for: date(timestampMs))
    }

    /// Підпис дня: «Сьогодні», «Вчора» або дата.
    static func dayLabel(_ day: Date, today: Date = Calendar.current.startOfDay(for: Date())) -> String {
        let calendar = Calendar.current
        if day == today { return NSLocalizedString("day_today", comment: "") }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: today), day == yesterday {
            return NSLocalizedString("day_yesterday", comment: "")
        }
        return dateFormatter("dd.MM.yyyy").string(from: day)
    }

    /// Текст для WhatsApp / Signal / Telegram. Порядок рядків збігається з
    /// порядком зчитування, і виводяться лише ті пункти, які були обрані в меню.
    static func buildText(_ reading: Reading) -> String {
        var lines: [String] = []
        lines.append(NSLocalizedString("share_header_one", comment: ""))
        lines.append(String(format: NSLocalizedString("share_date", comment: ""), formatDate(reading.timestamp)))
        lines.append("")
        lines.append(contentsOf: readingLines(reading))
        return lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Текст для кількох комплектів одразу: згруповані по днях, у кожному дні —
    /// від новішого до старішого. Один комплект віддається у звичному вигляді.
    static func buildText(_ readings: [Reading]) -> String {
        guard !readings.isEmpty else { return "" }
        if readings.count == 1 { return buildText(readings[0]) }

        let sorted = readings.sorted { $0.timestamp > $1.timestamp }
        let today = Calendar.current.startOfDay(for: Date())
        let nowMs = Date().timeIntervalSince1970 * 1000

        var lines: [String] = []
        lines.append(String(format: NSLocalizedString("share_header_many", comment: ""), Plurals.kits(sorted.count)))
        lines.append(String(format: NSLocalizedString("share_generated", comment: ""), formatDate(nowMs)))

        var currentDay: Date?
        for (index, reading) in sorted.enumerated() {
            let day = dayOf(reading.timestamp)
            if day != currentDay {
                lines.append("")
                lines.append("--- \(dayLabel(day, today: today)) ---")
                currentDay = day
            }
            lines.append("")
            lines.append("[\(index + 1)] \(formatTime(reading.timestamp))")
            lines.append(contentsOf: readingLines(reading))
        }
        return lines.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Тіло одного запису: тільки поля, без заголовка й дати.
    private static func readingLines(_ reading: Reading) -> [String] {
        var lines: [String] = []
        let settings = reading.settings

        func value(_ labelKey: String, _ value: String) {
            let label = NSLocalizedString(labelKey, comment: "")
            lines.append("\(label): \(value.isEmpty ? "—" : value)")
        }

        if settings.kitNumber { value("share_kit", reading.kitNumber) }
        if settings.dishSerial { value("menu_dish_serial", reading.dishSerial) }
        if settings.modemSerial { value("menu_modem_serial", reading.modemSerial) }
        if settings.starlinkId { value("menu_starlink_id", reading.starlinkId) }
        if settings.routerId { value("menu_router_id", reading.routerId) }
        if settings.modemMac { value("menu_mac", reading.effectiveMac) }

        let note = reading.note.trimmingCharacters(in: .whitespacesAndNewlines)
        if !note.isEmpty {
            lines.append(NSLocalizedString("share_note", comment: ""))
            lines.append(note)
        }
        return lines
    }

    @MainActor
    static func share(_ text: String) {
        let activity = UIActivityViewController(activityItems: [text], applicationActivities: nil)
        guard
            let scene = UIApplication.shared.connectedScenes
                .first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene,
            let root = scene.keyWindow?.rootViewController
        else { return }

        var top = root
        while let presented = top.presentedViewController { top = presented }

        if let popover = activity.popoverPresentationController {
            popover.sourceView = top.view
            popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
            popover.permittedArrowDirections = []
        }
        top.present(activity, animated: true)
    }

    /// Приводить сканований код до потрібного вигляду. На коробці буває як голий
    /// номер, так і посилання чи рядок з кількох полів, тому для KIT спершу
    /// шукаємо шаблон KIT…, а для серійників беремо останній непорожній сегмент URL.
    static func normalizeScan(_ step: QrStep, _ raw: String) -> String {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        switch step {
        case .kit:
            if let range = trimmed.range(of: "KIT[0-9A-Za-z]{6,}", options: .regularExpression) {
                return String(trimmed[range]).uppercased()
            }
            return fromUrl(trimmed)
        case .dishSerial, .modemSerial:
            return fromUrl(trimmed)
        }
    }

    /// Якщо код — посилання, беремо останній сегмент; інакше повертаємо як є.
    private static func fromUrl(_ text: String) -> String {
        guard text.hasPrefix("http://") || text.hasPrefix("https://") else { return text }

        var s = text
        if let q = s.firstIndex(of: "?") { s = String(s[s.startIndex..<q]) }
        while s.hasSuffix("/") { s.removeLast() }

        let result: String
        if let slash = s.lastIndex(of: "/") {
            result = String(s[s.index(after: slash)...])
        } else {
            result = s
        }
        return result.trimmingCharacters(in: .whitespaces).isEmpty ? text : result
    }
}
