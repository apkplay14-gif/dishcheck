import Foundation
import Network

/// Стан підключення до мережі Starlink.
enum LinkState {
    /// Телефон не в Wi-Fi.
    case noWifi
    /// Wi-Fi є, але тарілка на 192.168.100.1 не відповідає.
    case wifiNoDish
    /// Тарілка відповідає.
    case ready
}

enum NetUtil {
    /// Швидка TCP-перевірка, чи слухає порт. Прив'язка саме до Wi-Fi потрібна
    /// тому, що телефон може лишити мобільний інтернет активним і відправити
    /// запит на 192.168.100.1 через стільникову мережу, де його ніхто не почує.
    static func reachable(host: String, port: Int, timeoutMs: Int = 1500) async -> Bool {
        guard let nwPort = NWEndpoint.Port(rawValue: UInt16(clamping: port)) else { return false }

        return await withCheckedContinuation { continuation in
            let params = NWParameters.tcp
            params.requiredInterfaceType = .wifi
            params.prohibitExpensivePaths = false

            let connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: params)

            let lock = NSLock()
            var resumed = false
            func finish(_ result: Bool) {
                lock.lock()
                let already = resumed
                resumed = true
                lock.unlock()
                guard !already else { return }
                connection.cancel()
                continuation.resume(returning: result)
            }

            connection.stateUpdateHandler = { state in
                switch state {
                case .ready: finish(true)
                case .failed, .cancelled: finish(false)
                default: break
                }
            }
            connection.start(queue: .global(qos: .utility))
            DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + .milliseconds(timeoutMs)) {
                finish(false)
            }
        }
    }

    /// Чи є взагалі активний Wi-Fi інтерфейс.
    static func isOnWifi() async -> Bool {
        await withCheckedContinuation { continuation in
            let monitor = NWPathMonitor(requiredInterfaceType: .wifi)
            let lock = NSLock()
            var resumed = false
            monitor.pathUpdateHandler = { path in
                lock.lock()
                let already = resumed
                resumed = true
                lock.unlock()
                guard !already else { return }
                monitor.cancel()
                continuation.resume(returning: path.status == .satisfied)
            }
            monitor.start(queue: .global(qos: .utility))
        }
    }

    static func linkState() async -> LinkState {
        guard await isOnWifi() else { return .noWifi }
        let dishUp = await reachable(host: StarlinkClient.dishHost, port: StarlinkClient.dishPort)
        return dishUp ? .ready : .wifiNoDish
    }
}
