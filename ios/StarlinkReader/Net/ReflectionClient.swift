import Foundation
import SwiftProtobuf

/// Клієнт gRPC Server Reflection.
///
/// Навіщо: номери полів у proto-схемі Starlink (get_status / get_device_info тощо)
/// не є публічно зафіксованими й змінювались між прошивками. Замість того щоб
/// "зашивати" їх у код, ми питаємо схему в самого пристрою і будуємо запит по
/// *імені* поля — це працює на будь-якій прошивці, що вміє рефлексію.
///
/// На відміну від Android-версії (`protobuf-java` з повноцінним `DynamicMessage`),
/// у публічному API SwiftProtobuf немає динамічних повідомлень. Тому тут: беремо
/// самі лише дескриптори (`Google_Protobuf_FileDescriptorProto` — це звичайний,
/// добре типізований месседж, який SwiftProtobuf уміє розбирати із коробки), а
/// значення реальних відповідей читаємо власним обходом wire-формату
/// ([WireReader]), звіряючись із дескриптором лише для того, щоб дізнатись імена
/// й типи полів.
final class ReflectionClient {
    private let channel: GrpcChannel

    private var filesByName: [String: Google_Protobuf_FileDescriptorProto] = [:]
    private var requestedFiles: Set<String> = []
    private var messagesByFullName: [String: Google_Protobuf_DescriptorProto] = [:]
    private var servicesByFullName: [String: Google_Protobuf_ServiceDescriptorProto] = [:]
    private var workingMethod: String?

    struct MethodInfo {
        let fullMethodName: String
        let inputType: Google_Protobuf_DescriptorProto
        let outputType: Google_Protobuf_DescriptorProto
    }

    init(channel: GrpcChannel) {
        self.channel = channel
    }

    /// Знаходить сервіс і повертає дескриптори вхідного й вихідного типів методу.
    func loadMethod(service serviceFullName: String, method methodName: String) async throws -> MethodInfo {
        try await fetch(encodeStringField(Self.fieldContainingSymbol, serviceFullName))
        try await resolveDependencies()
        buildIndex()

        guard let service = servicesByFullName[serviceFullName] else {
            throw GrpcError(message: "service \(serviceFullName) not found in the device schema")
        }
        guard let method = service.method.first(where: { $0.name == methodName }) else {
            throw GrpcError(message: "method \(methodName) missing from the schema")
        }
        guard let inputType = descriptor(forTypeName: method.inputType) else {
            throw GrpcError(message: "input type \(method.inputType) missing from the schema")
        }
        guard let outputType = descriptor(forTypeName: method.outputType) else {
            throw GrpcError(message: "output type \(method.outputType) missing from the schema")
        }
        return MethodInfo(
            fullMethodName: "\(serviceFullName)/\(method.name)",
            inputType: inputType,
            outputType: outputType
        )
    }

    func descriptor(forTypeName typeName: String) -> Google_Protobuf_DescriptorProto? {
        messagesByFullName[stripLeadingDot(typeName)]
    }

    // MARK: - мережа

    private func fetch(_ requestBytes: [UInt8]) async throws {
        let methods = workingMethod.map { [$0] } ?? Self.reflectionMethods
        var lastError: Error?
        for method in methods {
            do {
                let frames = try await channel.call(fullMethodName: method, messages: [requestBytes])
                workingMethod = method
                var stored = false
                for frame in frames { stored = store(frame) || stored }
                if !stored && frames.isEmpty {
                    throw GrpcError(message: "empty reflection response")
                }
                return
            } catch {
                lastError = error
            }
        }
        throw lastError ?? GrpcError(message: "reflection unavailable")
    }

    /// @return true, якщо у відповіді були дескриптори.
    @discardableResult
    private func store(_ frame: [UInt8]) -> Bool {
        var added = false
        var reader = WireReader(frame)
        while let tag = try? reader.readTag() {
            if tag.field == Self.fieldFileDescriptorResponse, tag.wireType == 2 {
                guard let payload = try? reader.readLengthDelimited() else { return added }
                var inner = WireReader(payload)
                while let innerTag = try? inner.readTag() {
                    if innerTag.field == 1, innerTag.wireType == 2,
                       let protoBytes = try? inner.readLengthDelimited(),
                       let proto = try? Google_Protobuf_FileDescriptorProto(serializedBytes: protoBytes) {
                        if filesByName[proto.name] == nil {
                            filesByName[proto.name] = proto
                            added = true
                        }
                    } else {
                        try? inner.skip(wireType: innerTag.wireType)
                    }
                }
            } else if tag.field == Self.fieldErrorResponse, tag.wireType == 2 {
                _ = try? reader.readLengthDelimited()
            } else {
                try? reader.skip(wireType: tag.wireType)
            }
        }
        return added
    }

    /// Дотягує імпортовані .proto-файли, доки замикання залежностей не стане повним.
    private func resolveDependencies() async throws {
        for _ in 0..<8 {
            let missing = Set(filesByName.values.flatMap { $0.dependency })
                .subtracting(filesByName.keys)
                .subtracting(requestedFiles)
            if missing.isEmpty { return }
            for name in missing {
                requestedFiles.insert(name)
                try? await fetch(encodeStringField(Self.fieldByFilename, name))
            }
        }
    }

    // MARK: - індекс типів

    private func buildIndex() {
        messagesByFullName.removeAll()
        servicesByFullName.removeAll()
        for file in filesByName.values {
            let pkgPrefix = file.package.isEmpty ? "" : ".\(file.package)"
            for message in file.messageType {
                indexMessage(message, prefix: pkgPrefix)
            }
            for service in file.service {
                let fullName = "\(pkgPrefix).\(service.name)"
                servicesByFullName[stripLeadingDot(fullName)] = service
            }
        }
    }

    private func indexMessage(_ message: Google_Protobuf_DescriptorProto, prefix: String) {
        let fullName = stripLeadingDot("\(prefix).\(message.name)")
        messagesByFullName[fullName] = message
        for nested in message.nestedType {
            indexMessage(nested, prefix: "\(prefix).\(message.name)")
        }
    }

    private func stripLeadingDot(_ s: String) -> String { s.hasPrefix(".") ? String(s.dropFirst()) : s }

    private static let reflectionMethods = [
        "grpc.reflection.v1.ServerReflection/ServerReflectionInfo",
        "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo",
    ]

    private static let fieldByFilename = 3
    private static let fieldContainingSymbol = 4
    private static let fieldFileDescriptorResponse = 4
    private static let fieldErrorResponse = 7
}

// MARK: - Читання значень по схемі

extension ReflectionClient {
    /// Шукає у відповіді вкладене повідомлення, схоже на DeviceInfo: поле "id"
    /// разом із "hardware_version" чи "software_version".
    func findDeviceInfo(_ bytes: [UInt8], descriptor: Google_Protobuf_DescriptorProto, depth: Int = 0) -> [String: String]? {
        if depth > 8 { return nil }
        let names = Set(descriptor.field.map(\.name))
        let looksLikeDeviceInfo = names.contains("id") &&
            (names.contains("hardware_version") || names.contains("software_version"))

        if looksLikeDeviceInfo {
            let scalars = scalarFields(bytes, descriptor: descriptor)
            if let id = scalars["id"], !id.isEmpty { return scalars }
        }

        var reader = WireReader(bytes)
        while let tag = try? reader.readTag() {
            guard tag.wireType == 2 else { try? reader.skip(wireType: tag.wireType); continue }
            guard let nestedBytes = try? reader.readLengthDelimited() else { break }
            guard let field = descriptor.field.first(where: { $0.number == Int32(tag.field) }),
                  field.type == .message,
                  let nestedDescriptor = self.descriptor(forTypeName: field.typeName) else {
                continue
            }
            if let found = findDeviceInfo(nestedBytes, descriptor: nestedDescriptor, depth: depth + 1) {
                return found
            }
        }
        return nil
    }

    /// Збирає всі рядкові поля відповіді разом з їхніми іменами (рекурсивно).
    func collectStrings(
        _ bytes: [UInt8],
        descriptor: Google_Protobuf_DescriptorProto,
        depth: Int = 0,
        into out: inout [(name: String, value: String)]
    ) {
        if depth > 8 { return }
        var reader = WireReader(bytes)
        while let tag = try? reader.readTag() {
            guard let field = descriptor.field.first(where: { $0.number == Int32(tag.field) }) else {
                try? reader.skip(wireType: tag.wireType)
                continue
            }
            if field.type == .string, tag.wireType == 2 {
                if let raw = try? reader.readLengthDelimited(), let text = String(bytes: raw, encoding: .utf8) {
                    out.append((field.name, text))
                }
            } else if field.type == .message, tag.wireType == 2 {
                if let nestedBytes = try? reader.readLengthDelimited(),
                   let nestedDescriptor = self.descriptor(forTypeName: field.typeName) {
                    collectStrings(nestedBytes, descriptor: nestedDescriptor, depth: depth + 1, into: &out)
                }
            } else {
                try? reader.skip(wireType: tag.wireType)
            }
        }
    }

    /// Скалярні (не message, не repeated) поля повідомлення як рядки — лише для
    /// відображення в діагностичному журналі, тому числові типи не потребують
    /// точного знакового розбору.
    func scalarFields(_ bytes: [UInt8], descriptor: Google_Protobuf_DescriptorProto) -> [String: String] {
        var out: [String: String] = [:]
        var reader = WireReader(bytes)
        while let tag = try? reader.readTag() {
            guard let field = descriptor.field.first(where: { $0.number == Int32(tag.field) }),
                  field.label != .repeated, field.type != .message else {
                try? reader.skip(wireType: tag.wireType)
                continue
            }
            guard let value = try? Self.decodeScalarAsString(&reader, wireType: tag.wireType, type: field.type) else {
                continue
            }
            if !value.isEmpty { out[field.name] = value }
        }
        return out
    }

    private static func decodeScalarAsString(
        _ reader: inout WireReader,
        wireType: Int,
        type: Google_Protobuf_FieldDescriptorProto.TypeEnum
    ) throws -> String {
        switch wireType {
        case 0:
            let v = try reader.readVarint()
            return type == .bool ? (v != 0 ? "true" : "false") : "\(v)"
        case 1:
            let bytes = try reader.readFixed64()
            return "\(readLE64(bytes))"
        case 5:
            let bytes = try reader.readFixed32()
            return "\(readLE32(bytes))"
        case 2:
            let bytes = try reader.readLengthDelimited()
            if type == .bytes { return bytes.map { String(format: "%02x", $0) }.joined() }
            return String(bytes: bytes, encoding: .utf8) ?? ""
        default:
            throw WireReader.WireError.malformedVarint
        }
    }
}

private func readLE32(_ b: [UInt8]) -> UInt32 {
    UInt32(b[0]) | (UInt32(b[1]) << 8) | (UInt32(b[2]) << 16) | (UInt32(b[3]) << 24)
}

private func readLE64(_ b: [UInt8]) -> UInt64 {
    var v: UInt64 = 0
    for i in (0..<8).reversed() { v = (v << 8) | UInt64(b[i]) }
    return v
}
