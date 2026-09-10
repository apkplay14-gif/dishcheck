import Foundation

/// Зчитування локального gRPC API Starlink: спершу через рефлексію схеми
/// (працює на будь-якій прошивці), а якщо пристрій її не віддає — резервний
/// режим із двома номерами полів, які означають читання (без ризику
/// випадково зачепити reboot/stow, які лежать у тому самому oneof).
enum StarlinkClient {
    static let service = "SpaceX.API.Device.Device"
    static let method = "Handle"

    static let dishHost = "192.168.100.1"
    static let dishPort = 9200
    static let routerHost = "192.168.1.1"
    static let routerPort = 9000

    private static let requestFields = ["get_device_info", "get_status", "dish_get_status", "wifi_get_status"]
    private static let rawReadOnlyFields = [1008, 1004]

    private static let idRegex = try! NSRegularExpression(pattern: "^[A-Za-z0-9]{2,}-[0-9a-fA-F]{6,}-[0-9a-fA-F]{6,}$")
    private static let macRegex = try! NSRegularExpression(pattern: "^([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}$")

    /// - Parameters:
    ///   - wantMac: шукати також MAC-адресу; вона лежить не в DeviceInfo, тож може
    ///     знадобитись додатковий запит (наприклад wifi_get_status).
    ///   - allowRawProbe: дозволити резервний режим без рефлексії.
    static func read(
        host: String,
        port: Int,
        log: inout String,
        wantMac: Bool,
        allowRawProbe: Bool
    ) async -> DeviceData? {
        let channel = GrpcChannel(host: host, port: port)
        defer { channel.close() }
        log += "== \(host):\(port) ==\n"

        if let data = await readViaReflection(channel: channel, log: &log, wantMac: wantMac) {
            return data
        }

        guard allowRawProbe else { return nil }
        return await readViaRawProbe(channel: channel, log: &log)
    }

    // MARK: - рефлексія

    private static func readViaReflection(channel: GrpcChannel, log: inout String, wantMac: Bool) async -> DeviceData? {
        let reflection = ReflectionClient(channel: channel)
        let methodInfo: ReflectionClient.MethodInfo
        do {
            methodInfo = try await reflection.loadMethod(service: service, method: method)
        } catch {
            log += "reflection: \(error)\n"
            return nil
        }

        var info: [String: String]?
        var mac: String?

        for fieldName in requestFields {
            if info != nil && (!wantMac || mac != nil) { break }
            guard let field = methodInfo.inputType.field.first(where: { $0.name == fieldName }),
                  field.type == .message else { continue }

            let requestBytes = encodeEmptyMessageField(Int(field.number))
            do {
                let responseBytes = try await channel.callSingle(
                    fullMethodName: methodInfo.fullMethodName,
                    message: requestBytes
                )

                if info == nil {
                    info = reflection.findDeviceInfo(responseBytes, descriptor: methodInfo.outputType)
                    if info != nil { log += "\(fieldName): DeviceInfo found\n" }
                }
                if mac == nil {
                    var strings: [(name: String, value: String)] = []
                    reflection.collectStrings(responseBytes, descriptor: methodInfo.outputType, into: &strings)
                    mac = pickMac(strings)
                    if mac != nil { log += "\(fieldName): MAC found\n" }
                }
                if info == nil && mac == nil {
                    log += "\(fieldName): nothing useful\n"
                }
            } catch {
                log += "\(fieldName): \(error)\n"
            }
        }

        guard info != nil || mac != nil else { return nil }
        return toDeviceData(fields: info ?? [:], mac: mac)
    }

    private static func pickMac(_ strings: [(name: String, value: String)]) -> String? {
        if let named = strings.first(where: { $0.name.lowercased().contains("mac") && matches(macRegex, $0.value) }) {
            return named.value
        }
        return strings.first(where: { matches(macRegex, $0.value) })?.value
    }

    private static func toDeviceData(fields: [String: String], mac: String?) -> DeviceData {
        let fallback = fields.first(where: { $0.key.lowercased().contains("mac") && matches(macRegex, $0.value) })?.value
        return DeviceData(id: fields["id"], macAddress: mac ?? fallback, fields: fields)
    }

    // MARK: - резервний режим

    private static func readViaRawProbe(channel: GrpcChannel, log: inout String) async -> DeviceData? {
        for fieldNumber in rawReadOnlyFields {
            do {
                let bytes = try await channel.callSingle(
                    fullMethodName: "\(service)/\(method)",
                    message: encodeEmptyMessageField(fieldNumber)
                )
                let items = WireWalk.strings(bytes)
                if items.isEmpty {
                    log += "field \(fieldNumber): no strings found\n"
                    continue
                }
                let data = fromHeuristics(items)
                if let id = data.id, !id.isEmpty {
                    log += "read via field \(fieldNumber) (fallback mode)\n"
                    return data
                }
                log += "field \(fieldNumber): \(items.count) strings, ID not recognised\n"
            } catch {
                log += "field \(fieldNumber): \(error)\n"
            }
        }
        return nil
    }

    private static func fromHeuristics(_ items: [WireWalk.Item]) -> DeviceData {
        let values = items.map(\.value)
        var fields: [String: String] = [:]
        for item in items { fields[item.path] = item.value }
        return DeviceData(
            id: values.first { matches(idRegex, $0) },
            macAddress: values.first { matches(macRegex, $0) },
            fields: fields
        )
    }

    private static func matches(_ regex: NSRegularExpression, _ value: String) -> Bool {
        let range = NSRange(value.startIndex..<value.endIndex, in: value)
        guard let match = regex.firstMatch(in: value, options: [], range: range) else { return false }
        return match.range == range
    }
}
