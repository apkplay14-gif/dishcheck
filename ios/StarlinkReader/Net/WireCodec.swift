import Foundation

/// Низькорівневий читач protobuf wire-формату: без жодної схеми, лише варінти й
/// довжини. Використовується і евристичним [WireWalk], і схемним [ReflectionClient].
struct WireReader {
    let data: [UInt8]
    var pos: Int = 0

    init(_ data: [UInt8]) { self.data = data }

    var isAtEnd: Bool { pos >= data.count }

    enum WireError: Error { case truncated, malformedVarint }

    mutating func readVarint() throws -> UInt64 {
        var result: UInt64 = 0
        var shift: UInt64 = 0
        while true {
            guard pos < data.count else { throw WireError.truncated }
            let byte = data[pos]; pos += 1
            result |= UInt64(byte & 0x7F) << shift
            if byte & 0x80 == 0 { return result }
            shift += 7
            if shift >= 64 { throw WireError.malformedVarint }
        }
    }

    /// Тег: номер поля + тип у нижніх 3 бітах. Повертає nil на кінці буфера
    /// (аналог tag == 0 у CodedInputStream).
    mutating func readTag() throws -> (field: Int, wireType: Int)? {
        if isAtEnd { return nil }
        let tag = try readVarint()
        if tag == 0 { return nil }
        return (Int(tag >> 3), Int(tag & 0x7))
    }

    mutating func readFixed32() throws -> [UInt8] {
        guard pos + 4 <= data.count else { throw WireError.truncated }
        let bytes = Array(data[pos..<pos + 4]); pos += 4
        return bytes
    }

    mutating func readFixed64() throws -> [UInt8] {
        guard pos + 8 <= data.count else { throw WireError.truncated }
        let bytes = Array(data[pos..<pos + 8]); pos += 8
        return bytes
    }

    mutating func readLengthDelimited() throws -> [UInt8] {
        let len = try readVarint()
        guard len <= UInt64(Int.max), pos + Int(len) <= data.count else { throw WireError.truncated }
        let bytes = Array(data[pos..<pos + Int(len)]); pos += Int(len)
        return bytes
    }

    /// Пропускає значення поля цього типу, не інтерпретуючи його.
    mutating func skip(wireType: Int) throws {
        switch wireType {
        case 0: _ = try readVarint()
        case 1: _ = try readFixed64()
        case 2: _ = try readLengthDelimited()
        case 5: _ = try readFixed32()
        default: throw WireError.malformedVarint
        }
    }
}

/// Кодує порожнє повідомлення у полі [fieldNumber]: varint-тег (wire type 2,
/// length-delimited) + нульова довжина. Те саме, що надсилає Android-версія в
/// резервному режимі — запит "прочитай мені get_device_info / get_status" без
/// жодних додаткових даних усередині.
func encodeEmptyMessageField(_ fieldNumber: Int) -> [UInt8] {
    var out: [UInt8] = []
    var tag = UInt64((fieldNumber << 3) | 2)
    while tag & ~0x7F != 0 {
        out.append(UInt8((tag & 0x7F) | 0x80))
        tag >>= 7
    }
    out.append(UInt8(tag))
    out.append(0) // довжина 0
    return out
}

/// Кодує рядкове поле [fieldNumber] = [value] (wire type 2). Потрібно лише для
/// рефлексії (ServerReflectionRequest.file_containing_symbol / file_by_filename).
func encodeStringField(_ fieldNumber: Int, _ value: String) -> [UInt8] {
    var out: [UInt8] = []
    var tag = UInt64((fieldNumber << 3) | 2)
    while tag & ~0x7F != 0 {
        out.append(UInt8((tag & 0x7F) | 0x80))
        tag >>= 7
    }
    out.append(UInt8(tag))
    let bytes = Array(value.utf8)
    var len = UInt64(bytes.count)
    while len & ~0x7F != 0 {
        out.append(UInt8((len & 0x7F) | 0x80))
        len >>= 7
    }
    out.append(UInt8(len))
    out.append(contentsOf: bytes)
    return out
}

/// Універсальний обхід protobuf по wire-формату, без знання схеми.
/// Використовується як запасний варіант, коли пристрій не віддає рефлексію:
/// ми просто збираємо всі рядкові поля відповіді та впізнаємо потрібні за виглядом.
enum WireWalk {
    struct Item { let path: String; let value: String }

    static func strings(_ data: [UInt8]) -> [Item] {
        var out: [Item] = []
        walk(data, prefix: "", depth: 0, out: &out)
        return out
    }

    private static func walk(_ data: [UInt8], prefix: String, depth: Int, out: inout [Item]) {
        if depth > 8 { return }
        var reader = WireReader(data)
        do {
            while let tag = try reader.readTag() {
                let path = prefix.isEmpty ? "\(tag.field)" : "\(prefix).\(tag.field)"
                switch tag.wireType {
                case 0: _ = try reader.readVarint()
                case 1: _ = try reader.readFixed64()
                case 5: _ = try reader.readFixed32()
                case 2:
                    let bytes = try reader.readLengthDelimited()
                    if let text = asText(bytes) {
                        out.append(Item(path: path, value: text))
                    } else {
                        walk(bytes, prefix: path, depth: depth + 1, out: &out)
                    }
                default:
                    try reader.skip(wireType: tag.wireType)
                }
            }
        } catch {
            // Пошкоджений або невпізнаний фрагмент — просто зупиняємось на цій гілці.
        }
    }

    /// Повертає рядок, якщо байти виглядають як друкований UTF-8 текст.
    private static func asText(_ bytes: [UInt8]) -> String? {
        if bytes.isEmpty || bytes.count > 512 { return nil }
        for b in bytes where b < 0x20 || b == 0x7F { return nil }
        guard let s = String(bytes: bytes, encoding: .utf8) else { return nil }
        return s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : s
    }
}
