import SwiftUI

/// Знак застосунку: літера Є у темному квадраті з підсвіткою — те саме, що
/// ic_logo.xml на Android, тут просто як текстовий гліф замість SVG.
struct AppLogo: View {
    var size: CGFloat = 36

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: size * 0.28)
                .fill(
                    LinearGradient(
                        colors: [Color(hex: 0x0B1220), Color(hex: 0x1B2C4C)],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
            Text("Є")
                .font(.system(size: size * 0.52, weight: .bold))
                .foregroundColor(Brand.textPrimary)
        }
        .frame(width: size, height: size)
    }
}

/// Логотип із назвою: «Є» акцентом, «Starlink» звичайним.
struct WordMark: View {
    var body: some View {
        HStack(spacing: 10) {
            AppLogo(size: 30)
            (Text("Є ").foregroundColor(Brand.accent).fontWeight(.bold) +
                Text("Starlink").foregroundColor(Brand.textPrimary).fontWeight(.semibold))
                .font(.title3)
        }
    }
}

/// Ледь помітні орбіти на тлі шапки. Єдина декорація в застосунку — далі все
/// підпорядковано читабельності даних на сонці.
struct OrbitBackdrop: View {
    var body: some View {
        GeometryReader { geo in
            let center = CGPoint(x: geo.size.width * 0.78, y: geo.size.height * 0.35)
            ZStack {
                ForEach(Array(zip([0.55, 0.85, 1.15], [0.16, 0.07, 0.05])), id: \.0) { scale, alpha in
                    let w = geo.size.width * scale
                    let h = w * 0.34
                    Ellipse()
                        .stroke(Brand.accent.opacity(alpha), lineWidth: 1.2)
                        .frame(width: w, height: h)
                        .rotationEffect(.degrees(-22), anchor: .center)
                        .position(center)
                }
            }
        }
        .allowsHitTesting(false)
    }
}

/// Базова картка: поверхня + волосяна рамка, без важких тіней.
struct BrandCard<Content: View>: View {
    var accent: Bool = false
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity)
            .background(accent ? Brand.accentSoft : Brand.surface)
            .overlay(
                RoundedRectangle(cornerRadius: 18)
                    .stroke(accent ? Brand.accent.opacity(0.35) : Brand.hairline, lineWidth: 1)
            )
            .clipShape(RoundedRectangle(cornerRadius: 18))
    }
}

/// Дрібна велика мітка секції: РОЗДІЛ.
struct SectionLabel: View {
    let text: String

    var body: some View {
        Text(text.uppercased())
            .font(.caption2.weight(.medium))
            .tracking(1.4)
            .foregroundColor(Brand.textMuted)
    }
}

/// Чіп статусу з кольоровою крапкою.
struct StatusPill: View {
    let color: Color
    let text: String

    var body: some View {
        HStack(spacing: 7) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(text).font(.subheadline.weight(.semibold)).foregroundColor(color)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(color.opacity(0.12))
        .clipShape(Capsule())
    }
}

/// Номер кроку 1..6 у кружечку.
struct StepBadge: View {
    let number: Int
    let active: Bool

    var body: some View {
        ZStack {
            Circle().fill(active ? Brand.accent.opacity(0.18) : Brand.surfaceRaised)
            Text("\(number)")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(active ? Brand.accent : Brand.textMuted)
        }
        .frame(width: 24, height: 24)
    }
}

/// Іконка 20pt у приглушеному кольорі — типовий елемент рядка даних.
struct RowIcon: View {
    let systemName: String
    var tint: Color = Brand.textMuted
    var size: CGFloat = 20

    var body: some View {
        Image(systemName: systemName)
            .resizable()
            .scaledToFit()
            .foregroundColor(tint)
            .frame(width: size, height: size)
    }
}

/// SF Symbols замінюють векторні іконки Android — платформово ідіоматичний
/// відповідник для кожного пункту меню.
enum AppIcon {
    static let box = "shippingbox"
    static let dish = "dot.radiowaves.left.and.right"
    static let router = "wifi.router"
    static let signal = "antenna.radiowaves.left.and.right"
    static let id = "number"
    static let mac = "network"
    static let note = "note.text"
    static let qr = "qrcode.viewfinder"
    static let share = "square.and.arrow.up"
    static let delete = "trash"
    static let back = "chevron.left"
    static let history = "clock.arrow.circlepath"
    static let refresh = "arrow.clockwise"
}
