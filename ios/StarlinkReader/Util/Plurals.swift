import Foundation

/// Класичні .strings-файли не мають форм множини (на відміну від Android
/// `<plurals>`), тому множина рахується тут вручну — за тими самими правилами
/// CLDR і з тими самими перекладами, що й у `values-*/strings.xml`.
enum Plurals {
    static func records(_ count: Int) -> String {
        format(count, table: recordsTable)
    }

    static func kits(_ count: Int) -> String {
        format(count, table: kitsTable)
    }

    private static func format(_ count: Int, table: [String: [String: String]]) -> String {
        let lang = Locale.current.language.languageCode?.identifier ?? "en"
        let forms = table[lang] ?? table["en"]!
        let template = forms[category(count, lang: lang)] ?? forms["other"] ?? "%d"
        return String(format: template, count)
    }

    /// Категорія множини CLDR для потрібних нам мов. Слов'янські (uk, pl) мають
    /// one/few/many/other; решта — просто one/other.
    private static func category(_ n: Int, lang: String) -> String {
        let mod10 = n % 10, mod100 = n % 100
        switch lang {
        case "uk":
            if mod10 == 1 && mod100 != 11 { return "one" }
            if (2...4).contains(mod10) && !(12...14).contains(mod100) { return "few" }
            return "many"
        case "pl":
            if n == 1 { return "one" }
            if (2...4).contains(mod10) && !(12...14).contains(mod100) { return "few" }
            return "many"
        default:
            return n == 1 ? "one" : "other"
        }
    }

    private static let recordsTable: [String: [String: String]] = [
        "en": ["one": "%d record", "other": "%d records"],
        "uk": ["one": "%d запис", "few": "%d записи", "many": "%d записів", "other": "%d записів"],
        "pl": ["one": "%d wpis", "few": "%d wpisy", "many": "%d wpisów", "other": "%d wpisu"],
        "es": ["one": "%d registro", "other": "%d registros"],
        "de": ["one": "%d Eintrag", "other": "%d Einträge"],
    ]

    private static let kitsTable: [String: [String: String]] = [
        "en": ["one": "%d kit", "other": "%d kits"],
        "uk": ["one": "%d комплект", "few": "%d комплекти", "many": "%d комплектів", "other": "%d комплектів"],
        "pl": ["one": "%d zestaw", "few": "%d zestawy", "many": "%d zestawów", "other": "%d zestawu"],
        "es": ["one": "%d kit", "other": "%d kits"],
        "de": ["one": "%d Kit", "other": "%d Kits"],
    ]
}
