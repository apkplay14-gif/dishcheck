import Foundation

/// Крок сканування QR / штрихкоду. Порядок збігається з порядком у меню.
enum QrStep: String, Codable, CaseIterable {
    case kit, dishSerial, modemSerial

    var order: Int {
        switch self {
        case .kit: return 1
        case .dishSerial: return 2
        case .modemSerial: return 3
        }
    }

    var titleKey: String {
        switch self {
        case .kit: return "step_kit"
        case .dishSerial: return "step_dish_serial"
        case .modemSerial: return "step_modem_serial"
        }
    }
}

/// Поля, які можна правити руками, якщо код не зчитався.
enum EditableField {
    case kit, dishSerial, modemSerial, modemMac, note
}

/// Що саме знімати з комплекту. Порядок полів = порядок зчитування на екрані.
struct CaptureSettings: Codable, Equatable {
    var kitNumber: Bool = true
    var dishSerial: Bool = true
    var modemSerial: Bool = true
    var starlinkId: Bool = true
    var routerId: Bool = true
    var modemMac: Bool = true

    var enabledCount: Int {
        [kitNumber, dishSerial, modemSerial, starlinkId, routerId, modemMac].filter { $0 }.count
    }

    var anyEnabled: Bool { enabledCount > 0 }

    /// Кроки сканування QR, які потрібно пройти, у правильному порядку.
    var qrSteps: [QrStep] {
        var steps: [QrStep] = []
        if kitNumber { steps.append(.kit) }
        if dishSerial { steps.append(.dishSerial) }
        if modemSerial { steps.append(.modemSerial) }
        return steps
    }

    /// Чи треба опитувати тарілку по мережі.
    var needsDish: Bool { starlinkId }

    /// Чи треба опитувати роутер по мережі.
    var needsRouter: Bool { routerId || modemMac }

    var needsNetwork: Bool { needsDish || needsRouter }
}

/// Дані одного пристрою (тарілка або роутер).
struct DeviceData: Codable, Equatable {
    var id: String?
    var macAddress: String?
    /// Усі решта прочитаних полів — для довідки та діагностики.
    var fields: [String: String] = [:]

    var isEmpty: Bool {
        (id?.isEmpty ?? true) && (macAddress?.isEmpty ?? true)
    }
}

/// Одне збережене зчитування комплекту.
struct Reading: Codable, Equatable, Identifiable {
    var uid: String
    var timestamp: Double
    var kitNumber: String = ""
    var dishSerial: String = ""
    var modemSerial: String = ""
    var modemMac: String = ""
    var note: String = ""
    var dish: DeviceData?
    var router: DeviceData?
    /// Набір галочок, з якими знімався саме цей комплект.
    var settings: CaptureSettings = CaptureSettings()
    var diagnostics: String = ""

    var id: String { uid }

    var starlinkId: String { dish?.id ?? "" }
    var routerId: String { router?.id ?? "" }

    /// MAC із роутера, а якщо його не віддали — введений руками.
    var effectiveMac: String {
        modemMac.isEmpty ? (router?.macAddress ?? "") : modemMac
    }

    var isEmpty: Bool {
        kitNumber.isEmpty && dishSerial.isEmpty && modemSerial.isEmpty &&
            starlinkId.isEmpty && routerId.isEmpty && effectiveMac.isEmpty
    }

    /// Найінформативніший ідентифікатор запису; nil — якщо нічого не знято.
    var titleOrNull: String? {
        [kitNumber, dishSerial, starlinkId, modemSerial, routerId].first { !$0.isEmpty }
    }

    func value(_ field: EditableField) -> String {
        switch field {
        case .kit: return kitNumber
        case .dishSerial: return dishSerial
        case .modemSerial: return modemSerial
        case .modemMac: return effectiveMac
        case .note: return note
        }
    }

    func with(_ field: EditableField, _ text: String) -> Reading {
        var copy = self
        switch field {
        case .kit: copy.kitNumber = text
        case .dishSerial: copy.dishSerial = text
        case .modemSerial: copy.modemSerial = text
        case .modemMac: copy.modemMac = text
        case .note: copy.note = text
        }
        return copy
    }

    func with(_ step: QrStep, _ text: String) -> Reading {
        var copy = self
        switch step {
        case .kit: copy.kitNumber = text
        case .dishSerial: copy.dishSerial = text
        case .modemSerial: copy.modemSerial = text
        }
        return copy
    }
}
