import Foundation

/// Щойно зчитаний комплект збігся за Starlink ID з тим, що вже є в історії.
struct DuplicatePrompt: Equatable {
    let freshUid: String
    let existingUid: String
    let starlinkId: String
    let existingTimestamp: Double
}

/// Крок сканування, який зараз має бути відкритий на екрані.
struct PendingScan: Identifiable, Equatable {
    let id = UUID()
    let uid: String
    let step: QrStep
    let progress: String
}

/// Об'єднує MainViewModel і "ланцюжок кроків" з MainActivity на Android —
/// у SwiftUI немає окремої Activity, тож усе логічно живе в одному сховищі стану.
@MainActor
final class AppState: ObservableObject {
    @Published var link: LinkState = .noWifi
    @Published var checkingLink = false
    @Published var settings: CaptureSettings
    /// Триває мережеве опитування тарілки/роутера.
    @Published var reading = false
    @Published var currentUid: String?
    @Published var history: [Reading] = []
    @Published var error: String?
    @Published var diagnostics: String?
    @Published var allowRawProbe = false
    @Published var duplicate: DuplicatePrompt?
    @Published var pendingScan: PendingScan?

    var current: Reading? { history.first { $0.uid == currentUid } }

    private let store = HistoryStore()
    private let settingsStore = SettingsStore()
    private var persistTask: Task<Void, Never>?

    /// Крок, ланцюжок сканування, у якому зараз перебуваємо.
    private var captureUid: String?
    private var remainingSteps: [QrStep] = []
    private var chainMode = false
    private var totalSteps = 0
    private var doneSteps = 0

    init() {
        settings = settingsStore.load()
        history = store.load()
        Task { await refreshLink() }
    }

    // MARK: - налаштування

    func updateSettings(_ newSettings: CaptureSettings) {
        settings = newSettings
        settingsStore.save(newSettings)
    }

    func setAllowRawProbe(_ enabled: Bool) {
        allowRawProbe = enabled
    }

    func refreshLink() async {
        guard !checkingLink else { return }
        checkingLink = true
        link = await NetUtil.linkState()
        checkingLink = false
    }

    // MARK: - ланцюжок сканування

    /// Створює порожній запис і починає повний прохід: сканування → мережа.
    func startCapture() {
        guard settings.anyEnabled, !reading else { return }

        let newReading = Reading(uid: UUID().uuidString, timestamp: nowMs(), settings: settings)
        history.insert(newReading, at: 0)
        persist()
        currentUid = newReading.uid
        error = nil
        diagnostics = nil

        captureUid = newReading.uid
        chainMode = true
        remainingSteps = settings.qrSteps
        doneSteps = 0
        totalSteps = settings.qrSteps.count + (settings.needsNetwork ? 1 : 0)
        advance()
    }

    /// Сканування одного окремого поля (кнопка біля конкретного рядка картки).
    func scanSingle(uid: String, step: QrStep) {
        captureUid = uid
        chainMode = false
        remainingSteps = []
        totalSteps = 1
        doneSteps = 1
        pendingScan = PendingScan(uid: uid, step: step, progress: "")
    }

    private func advance() {
        guard let uid = captureUid else {
            remainingSteps = []
            return
        }

        if !remainingSteps.isEmpty {
            let step = remainingSteps.removeFirst()
            doneSteps += 1
            let progress = totalSteps > 1
                ? String(format: NSLocalizedString("step_progress", comment: ""), doneSteps, totalSteps)
                : ""
            pendingScan = PendingScan(uid: uid, step: step, progress: progress)
            return
        }

        captureUid = nil
        if chainMode {
            chainMode = false
            Task { await runNetworkStep(uid: uid, isNewCapture: true) }
        }
    }

    /// Викликається екраном сканера з результатом (або nil — пропуск/скасування).
    /// Пропуск чи скасування не обриває ланцюжок — просто йдемо далі.
    func completeScan(uid: String, step: QrStep, rawValue: String?) {
        if let rawValue {
            let normalized = Sharing.normalizeScan(step, rawValue)
            update(uid: uid) { $0.with(step, normalized) }
        }
        pendingScan = nil

        if chainMode {
            advance()
        } else {
            captureUid = nil
        }
    }

    func setField(uid: String, field: EditableField, value: String) {
        update(uid: uid) { $0.with(field, value) }
    }

    // MARK: - мережа

    /// Мережева частина: Starlink ID, Router ID, MAC.
    func runNetworkStep(uid: String, isNewCapture: Bool = false) async {
        guard let target = history.first(where: { $0.uid == uid }) else { return }
        let captureSettings = target.settings

        if !captureSettings.needsNetwork {
            finishCapture(uid: uid, networkFailed: false, diagnostics: nil, isNewCapture: isNewCapture)
            return
        }
        guard !reading else { return }

        reading = true
        error = nil

        let allowRaw = allowRawProbe
        var log = ""

        let dish: DeviceData? = captureSettings.needsDish
            ? await StarlinkClient.read(
                host: StarlinkClient.dishHost, port: StarlinkClient.dishPort,
                log: &log, wantMac: false, allowRawProbe: allowRaw
            )
            : nil

        let router: DeviceData? = captureSettings.needsRouter
            ? await StarlinkClient.read(
                host: StarlinkClient.routerHost, port: StarlinkClient.routerPort,
                log: &log, wantMac: captureSettings.modemMac, allowRawProbe: allowRaw
            )
            : nil

        let trimmedLog = log.trimmingCharacters(in: .whitespacesAndNewlines)
        update(uid: uid) { r in
            var copy = r
            copy.dish = dish
            copy.router = router
            copy.diagnostics = trimmedLog
            return copy
        }
        reading = false
        finishCapture(
            uid: uid,
            networkFailed: dish == nil && router == nil,
            diagnostics: trimmedLog,
            isNewCapture: isNewCapture
        )
        await refreshLink()
    }

    /// Прибирає порожній запис і показує помилку, якщо зняти не вдалося нічого.
    private func finishCapture(uid: String, networkFailed: Bool, diagnostics: String?, isNewCapture: Bool) {
        guard let target = history.first(where: { $0.uid == uid }) else { return }

        if target.isEmpty {
            history.removeAll { $0.uid == uid }
            persist()
            currentUid = nil
            self.diagnostics = diagnostics
            error = NSLocalizedString("error_nothing_captured", comment: "")
            return
        }

        self.diagnostics = diagnostics ?? self.diagnostics
        error = networkFailed ? NSLocalizedString("error_network_silent", comment: "") : nil

        if isNewCapture { checkDuplicate(target) }
    }

    // MARK: - повторні зчитування

    /// Той самий комплект могли знімати раніше. Порівнюємо за Starlink ID —
    /// єдиним значенням, яке належить залізу й не залежить від того, чи
    /// відсканували коробку.
    private func checkDuplicate(_ fresh: Reading) {
        let starlinkId = fresh.starlinkId
        guard !starlinkId.isEmpty else { return }

        guard let existing = history
            .filter({ $0.uid != fresh.uid && $0.starlinkId == starlinkId })
            .max(by: { $0.timestamp < $1.timestamp })
        else { return }

        duplicate = DuplicatePrompt(
            freshUid: fresh.uid,
            existingUid: existing.uid,
            starlinkId: starlinkId,
            existingTimestamp: existing.timestamp
        )
    }

    /// Лишити обидва записи. Безпечний варіант, тому він і за замовчуванням.
    func keepDuplicateAsNew() {
        duplicate = nil
    }

    /// Влити свіже зчитування в наявний запис. Нові значення перекривають старі,
    /// але порожні нічого не затирають.
    func mergeDuplicate() {
        guard let prompt = duplicate,
              let fresh = history.first(where: { $0.uid == prompt.freshUid }),
              let existing = history.first(where: { $0.uid == prompt.existingUid })
        else { return }

        var merged = existing
        merged.timestamp = fresh.timestamp
        merged.kitNumber = fresh.kitNumber.isEmpty ? existing.kitNumber : fresh.kitNumber
        merged.dishSerial = fresh.dishSerial.isEmpty ? existing.dishSerial : fresh.dishSerial
        merged.modemSerial = fresh.modemSerial.isEmpty ? existing.modemSerial : fresh.modemSerial
        merged.modemMac = fresh.modemMac.isEmpty ? existing.modemMac : fresh.modemMac
        merged.dish = fresh.dish ?? existing.dish
        merged.router = fresh.router ?? existing.router
        merged.note = Self.mergeNotes(existing.note, fresh.note)
        merged.settings = fresh.settings
        merged.diagnostics = fresh.diagnostics

        var updated = history.filter { $0.uid != prompt.freshUid }
        if let index = updated.firstIndex(where: { $0.uid == prompt.existingUid }) {
            updated[index] = merged
        }
        updated.sort { $0.timestamp > $1.timestamp }

        history = updated
        persist()
        currentUid = merged.uid
        duplicate = nil
    }

    private static func mergeNotes(_ existing: String, _ fresh: String) -> String {
        let trimmedFresh = fresh.trimmingCharacters(in: .whitespacesAndNewlines)
        let trimmedExisting = existing.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedFresh.isEmpty { return existing }
        if trimmedExisting.isEmpty { return fresh }
        if trimmedExisting == trimmedFresh { return existing }
        return existing + "\n\n" + trimmedFresh
    }

    func dismissError() {
        error = nil
    }

    // MARK: - історія

    func deleteAll(_ uids: [String]) {
        guard !uids.isEmpty else { return }
        let doomed = Set(uids)
        history.removeAll { doomed.contains($0.uid) }
        persist()
        if let uid = currentUid, doomed.contains(uid) { currentUid = nil }
    }

    func delete(_ uid: String) {
        history.removeAll { $0.uid == uid }
        persist()
        if currentUid == uid { currentUid = nil }
    }

    private func update(uid: String, _ transform: (Reading) -> Reading) {
        guard let index = history.firstIndex(where: { $0.uid == uid }) else { return }
        history[index] = transform(history[index])
        persist()
    }

    /// Запис у файл із невеликою затримкою: примітки правляться посимвольно, і
    /// перезаписувати весь JSON на кожну літеру немає сенсу.
    private func persist() {
        let snapshot = history
        persistTask?.cancel()
        persistTask = Task { [store] in
            try? await Task.sleep(nanoseconds: 400_000_000)
            guard !Task.isCancelled else { return }
            store.save(snapshot)
        }
    }

    /// Негайний запис без дебаунсу — на випадок згортання застосунку.
    func flushPersist() {
        persistTask?.cancel()
        store.save(history)
    }

    private func nowMs() -> Double {
        Date().timeIntervalSince1970 * 1000
    }
}
