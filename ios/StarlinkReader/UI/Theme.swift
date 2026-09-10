import SwiftUI

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// Палітра «Є Starlink»: майже чорне тло, холодне світло, один синій акцент.
/// Тема навмисно лише темна — так само виглядає офіційний застосунок Starlink,
/// і на вулиці вдень темний екран з високим контрастом читається краще.
enum Brand {
    static let ink = Color(hex: 0x06080D)
    static let surface = Color(hex: 0x0E1119)
    static let surfaceRaised = Color(hex: 0x151A24)
    static let hairline = Color(hex: 0x222937)

    static let textPrimary = Color(hex: 0xF3F6FB)
    static let textMuted = Color(hex: 0x8A93A6)

    static let accent = Color(hex: 0x4C8DFF)
    static let accentSoft = Color(hex: 0x13233F)
    static let online = Color(hex: 0x3DDC97)
    static let waiting = Color(hex: 0xFFB443)
    static let alert = Color(hex: 0xFF6B6B)

    /// Текст/іконка поверх акентних кнопок.
    static let onAccent = Color(hex: 0x04070E)
}

/// Моноширинний стиль для ідентифікаторів — щоб символи не «пливли».
extension Font {
    static let monoValue = Font.system(size: 14, weight: .regular, design: .monospaced)
}
