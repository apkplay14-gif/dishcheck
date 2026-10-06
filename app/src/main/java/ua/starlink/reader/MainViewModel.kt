package ua.starlink.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.starlink.reader.data.CaptureSettings
import ua.starlink.reader.data.DemoData
import ua.starlink.reader.data.EditableField
import ua.starlink.reader.data.HistoryStore
import ua.starlink.reader.data.QrStep
import ua.starlink.reader.data.Reading
import ua.starlink.reader.data.ReviewPromptState
import ua.starlink.reader.data.ReviewPromptStore
import ua.starlink.reader.data.SettingsStore
import ua.starlink.reader.net.LinkState
import ua.starlink.reader.net.NetUtil
import ua.starlink.reader.net.SpeedTestClient
import ua.starlink.reader.net.StarlinkClient
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

data class UiState(
    val link: LinkState = LinkState.NO_WIFI,
    val checkingLink: Boolean = false,
    val settings: CaptureSettings = CaptureSettings(),
    /** Триває мережеве опитування тарілки/роутера. */
    val reading: Boolean = false,
    val currentUid: String? = null,
    val history: List<Reading> = emptyList(),
    val error: String? = null,
    val diagnostics: String? = null,
    val allowRawProbe: Boolean = false,
    /** Знайдено повтор за Starlink ID — треба спитати користувача, що робити. */
    val duplicate: DuplicatePrompt? = null,
    val speedTest: SpeedTestUiState = SpeedTestUiState(),
    /** Демо-режим: вигадані дані замість тарілки й роутера, справжня історія відкладена. */
    val demo: Boolean = false,
) {
    val current: Reading? get() = history.firstOrNull { it.uid == currentUid }

    /** У демо картка підключення показує те, що людина бачила б біля справжнього Starlink. */
    val shownLink: LinkState get() = if (demo) LinkState.READY else link
}

/** Крок, на якому зараз перебуває тест швидкості інтернету. */
enum class SpeedTestPhase { IDLE, PING, DOWNLOAD, UPLOAD, DONE, ERROR }

data class SpeedTestUiState(
    val phase: SpeedTestPhase = SpeedTestPhase.IDLE,
    val pingMs: Long? = null,
    val downloadMbps: Double? = null,
    val uploadMbps: Double? = null,
    val error: String? = null,
)

/** Щойно зчитаний комплект збігся за Starlink ID з тим, що вже є в історії. */
data class DuplicatePrompt(
    val freshUid: String,
    val existingUid: String,
    val starlinkId: String,
    val existingTimestamp: Long,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = HistoryStore(app)
    private val settingsStore = SettingsStore(app)
    private val reviewStore = ReviewPromptStore(app)
    private var reviewPromptState = ReviewPromptState()
    private var persistJob: Job? = null
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Одноразова подія «покажи системний діалог оцінки» — ловить MainActivity. */
    private val _reviewRequests = Channel<Unit>(Channel.CONFLATED)
    val reviewRequests = _reviewRequests.receiveAsFlow()

    /** Справжня історія, відкладена на час демо. null — демо не запущене. */
    private var stashedHistory: List<Reading>? = null

    /** Порядковий номер кожного демо-комплекту: з нього складаються всі його значення. */
    private val demoSeq = HashMap<String, Int>()
    private var demoCounter = 0
    private var demoJob: Job? = null

    init {
        _state.value = _state.value.copy(
            history = store.load(),
            settings = settingsStore.load(),
        )
        reviewPromptState = reviewStore.load()
        refreshLink()
    }

    // ------------------------------------------------------------------ налаштування

    fun updateSettings(settings: CaptureSettings) {
        _state.value = _state.value.copy(settings = settings)
        viewModelScope.launch(Dispatchers.IO) { settingsStore.save(settings) }
    }

    fun setAllowRawProbe(enabled: Boolean) {
        _state.value = _state.value.copy(allowRawProbe = enabled)
    }

    fun refreshLink() {
        if (_state.value.checkingLink) return
        _state.value = _state.value.copy(checkingLink = true)
        viewModelScope.launch {
            val link = withContext(Dispatchers.IO) {
                NetUtil.linkState(getApplication<Application>())
            }
            _state.value = _state.value.copy(link = link, checkingLink = false)
        }
    }

    // ------------------------------------------------------------------ тест швидкості

    private var speedTestJob: Job? = null

    /**
     * Тест іде саме через Wi-Fi мережу (ту, до якої підключена тарілка), а не
     * через типове підключення телефона — інакше при увімкнених мобільних
     * даних результат показував би швидкість не Starlink, а стільникової
     * мережі. Немає Wi-Fi — тест і не починається.
     */
    fun runSpeedTest() {
        val busy = _state.value.speedTest.phase.let {
            it == SpeedTestPhase.PING || it == SpeedTestPhase.DOWNLOAD || it == SpeedTestPhase.UPLOAD
        }
        if (busy) return

        val network = NetUtil.wifiNetwork(getApplication())
        if (network == null) {
            _state.value = _state.value.copy(
                speedTest = SpeedTestUiState(
                    phase = SpeedTestPhase.ERROR,
                    error = string(R.string.speed_test_error_no_wifi),
                ),
            )
            return
        }

        _state.value = _state.value.copy(speedTest = SpeedTestUiState(phase = SpeedTestPhase.PING))

        speedTestJob?.cancel()
        speedTestJob = viewModelScope.launch {
            val client = SpeedTestClient(network.socketFactory)
            try {
                val ping = withContext(Dispatchers.IO) { client.measurePingMs() }
                _state.value = _state.value.copy(
                    speedTest = _state.value.speedTest.copy(phase = SpeedTestPhase.DOWNLOAD, pingMs = ping),
                )

                val download = withContext(Dispatchers.IO) { client.measureDownloadMbps() }
                _state.value = _state.value.copy(
                    speedTest = _state.value.speedTest.copy(
                        phase = SpeedTestPhase.UPLOAD,
                        downloadMbps = download,
                    ),
                )

                val upload = withContext(Dispatchers.IO) { client.measureUploadMbps() }
                _state.value = _state.value.copy(
                    speedTest = _state.value.speedTest.copy(phase = SpeedTestPhase.DONE, uploadMbps = upload),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    speedTest = _state.value.speedTest.copy(
                        phase = SpeedTestPhase.ERROR,
                        error = string(R.string.speed_test_error_generic),
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------------ демо-режим

    /**
     * Демо — окрема пісочниця в пам'яті: справжня історія відкладається вбік і
     * в файл не пишеться нічого, тож вийти з демо можна будь-коли без наслідків.
     * Два готові комплекти в історії потрібні, щоб одразу було що відкрити,
     * виділити по днях і переслати кілька разом.
     */
    fun startDemo() {
        if (_state.value.demo || _state.value.reading) return
        stashedHistory = _state.value.history
        demoSeq.clear()

        val yesterday = LocalDate.now().minusDays(1).atTime(15, 40)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val earlierToday = System.currentTimeMillis() - 90L * 60 * 1000
        val samples = listOf(
            demoSample(seq = 1, timestamp = yesterday, note = string(R.string.demo_note_1)),
            demoSample(seq = 2, timestamp = earlierToday, note = string(R.string.demo_note_2)),
        )
        demoCounter = samples.size

        _state.value = _state.value.copy(
            demo = true,
            history = samples.sortedByDescending { it.timestamp },
            currentUid = null,
            error = null,
            diagnostics = null,
            duplicate = null,
        )
    }

    private fun demoSample(seq: Int, timestamp: Long, note: String): Reading {
        val uid = UUID.randomUUID().toString()
        demoSeq[uid] = seq
        return DemoData.reading(uid = uid, seq = seq, timestamp = timestamp, note = note)
    }

    fun exitDemo() {
        val real = stashedHistory ?: return
        demoJob?.cancel()
        stashedHistory = null
        demoSeq.clear()
        _state.value = _state.value.copy(
            demo = false,
            history = real,
            reading = false,
            currentUid = null,
            error = null,
            diagnostics = null,
            duplicate = null,
        )
    }

    /** Що демо-сканер підставить на цьому кроці; null — демо не запущене. */
    fun demoScanValue(uid: String, step: QrStep): String? {
        if (!_state.value.demo) return null
        val seq = demoSeq[uid] ?: return null
        return DemoData.scanValue(step, seq)
    }

    // ------------------------------------------------------------------ зчитування

    /** Створює порожній запис і робить його поточним. Повертає його uid. */
    fun beginCapture(): String {
        val reading = Reading(
            uid = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            settings = _state.value.settings,
        )
        if (_state.value.demo) demoSeq[reading.uid] = ++demoCounter
        val history = listOf(reading) + _state.value.history
        persist(history)
        _state.value = _state.value.copy(
            history = history,
            currentUid = reading.uid,
            error = null,
            diagnostics = null,
        )
        return reading.uid
    }

    fun applyScan(uid: String, step: QrStep, value: String) = update(uid) { it.with(step, value) }

    fun setField(uid: String, field: EditableField, value: String) =
        update(uid) { it.with(field, value) }

    /**
     * Мережева частина: Starlink ID, Router ID, MAC. Викликається після кроків
     * сканування, коли вони увімкнені.
     */
    fun runNetworkStep(uid: String, isNewCapture: Boolean = false) {
        val reading = _state.value.history.firstOrNull { it.uid == uid } ?: return
        val settings = reading.settings

        if (!settings.needsNetwork) {
            finishCapture(uid, networkFailed = false, diagnostics = null, isNewCapture)
            return
        }
        if (_state.value.reading) return

        _state.value = _state.value.copy(reading = true, error = null)

        if (_state.value.demo) {
            runDemoNetworkStep(uid, settings, isNewCapture)
            return
        }

        viewModelScope.launch {
            val allowRaw = _state.value.allowRawProbe
            val result = withContext(Dispatchers.IO) {
                val log = StringBuilder()
                val factory = NetUtil.socketFactory(getApplication<Application>())
                val client = StarlinkClient(factory)

                val dish = if (settings.needsDish) {
                    client.read(
                        StarlinkClient.DISH_HOST, StarlinkClient.DISH_PORT,
                        log, wantMac = false, allowRawProbe = allowRaw,
                    )
                } else {
                    null
                }

                val router = if (settings.needsRouter) {
                    client.read(
                        StarlinkClient.ROUTER_HOST, StarlinkClient.ROUTER_PORT,
                        log, wantMac = settings.modemMac, allowRawProbe = allowRaw,
                    )
                } else {
                    null
                }

                Triple(dish, router, log.toString().trimEnd())
            }

            val (dish, router, log) = result
            update(uid) { it.copy(dish = dish, router = router, diagnostics = log) }
            _state.value = _state.value.copy(reading = false)
            finishCapture(
                uid,
                networkFailed = dish == null && router == null,
                diagnostics = log,
                isNewCapture = isNewCapture,
            )
            refreshLink()
        }
    }

    /**
     * Опитування без мережі: відповідають вигадані тарілка й роутер. Пауза — як
     * у справжнього опитування, інакше крок «Опитування пристроїв…» на відео
     * проскакує непомітно.
     */
    private fun runDemoNetworkStep(uid: String, settings: CaptureSettings, isNewCapture: Boolean) {
        val seq = demoSeq[uid] ?: 0
        demoJob = viewModelScope.launch {
            delay(DEMO_POLL_MS)
            val dish = if (settings.needsDish) DemoData.dish(seq) else null
            val router = if (settings.needsRouter) {
                DemoData.router(seq, withMac = settings.modemMac)
            } else {
                null
            }
            update(uid) { it.copy(dish = dish, router = router, diagnostics = DemoData.LOG) }
            _state.value = _state.value.copy(reading = false)
            finishCapture(uid, networkFailed = false, diagnostics = DemoData.LOG, isNewCapture)
        }
    }

    /** Прибирає порожній запис і показує помилку, якщо зняти не вдалося нічого. */
    private fun finishCapture(
        uid: String,
        networkFailed: Boolean,
        diagnostics: String?,
        isNewCapture: Boolean,
    ) {
        val reading = _state.value.history.firstOrNull { it.uid == uid } ?: return

        if (reading.isEmpty) {
            val history = _state.value.history.filterNot { it.uid == uid }
            persist(history)
            _state.value = _state.value.copy(
                history = history,
                currentUid = null,
                diagnostics = diagnostics,
                error = string(R.string.error_nothing_captured),
            )
            return
        }

        _state.value = _state.value.copy(
            diagnostics = diagnostics ?: _state.value.diagnostics,
            error = if (networkFailed) string(R.string.error_network_silent) else null,
        )

        if (isNewCapture) {
            checkDuplicate(reading)
            // Не питаємо відгук одночасно з діалогом «такий комплект вже є» —
            // це не той щасливий момент, коли варто відволікати проханням оцінити.
            // І не в демо: це ще не справжня робота, а рецензенту Google
            // прохання оцінити застосунок і поготів ні до чого.
            val happyEnding = !networkFailed && _state.value.duplicate == null
            if (happyEnding && !_state.value.demo) maybeRequestReview()
        }
    }

    /**
     * Консервативніший фільтр поверх власного ліміту Google: питаємо не на
     * першому ж скануванні (дати призвичаїтись), не частіше ніж раз на місяць
     * і не більше кількох разів за весь час життя застосунку.
     */
    private fun maybeRequestReview() {
        val decision = decideReviewPrompt(
            current = reviewPromptState,
            now = System.currentTimeMillis(),
            minScans = REVIEW_MIN_SCANS,
            cooldownMs = REVIEW_COOLDOWN_MS,
            maxPrompts = REVIEW_MAX_PROMPTS,
        )
        reviewPromptState = decision.nextState
        if (decision.shouldRequestReview) _reviewRequests.trySend(Unit)

        val toSave = reviewPromptState
        viewModelScope.launch(Dispatchers.IO) { reviewStore.save(toSave) }
    }

    // -------------------------------------------------------------- повторні зчитування

    /**
     * Той самий комплект могли знімати раніше. Порівнюємо за Starlink ID —
     * єдиним значенням, яке належить залізу й не залежить від того, чи
     * відсканували коробку.
     */
    private fun checkDuplicate(fresh: Reading) {
        val starlinkId = fresh.starlinkId
        if (starlinkId.isBlank()) return

        val existing = _state.value.history
            .filter { it.uid != fresh.uid && it.starlinkId == starlinkId }
            .maxByOrNull { it.timestamp }
            ?: return

        _state.value = _state.value.copy(
            duplicate = DuplicatePrompt(
                freshUid = fresh.uid,
                existingUid = existing.uid,
                starlinkId = starlinkId,
                existingTimestamp = existing.timestamp,
            )
        )
    }

    /** Лишити обидва записи. Безпечний варіант, тому він і за замовчуванням. */
    fun keepDuplicateAsNew() {
        _state.value = _state.value.copy(duplicate = null)
    }

    /**
     * Влити свіже зчитування в наявний запис. Нові значення перекривають старі,
     * але порожні нічого не затирають: якщо цього разу KIT не сканували, старий
     * лишається. Примітки зберігаються завжди.
     */
    fun mergeDuplicate() {
        val prompt = _state.value.duplicate ?: return
        val history = _state.value.history
        val fresh = history.firstOrNull { it.uid == prompt.freshUid } ?: return
        val existing = history.firstOrNull { it.uid == prompt.existingUid } ?: return

        val merged = existing.copy(
            // Час свіжий, тому запис підіймається в сьогоднішній день.
            timestamp = fresh.timestamp,
            kitNumber = fresh.kitNumber.ifBlank { existing.kitNumber },
            dishSerial = fresh.dishSerial.ifBlank { existing.dishSerial },
            modemSerial = fresh.modemSerial.ifBlank { existing.modemSerial },
            modemMac = fresh.modemMac.ifBlank { existing.modemMac },
            dish = fresh.dish ?: existing.dish,
            router = fresh.router ?: existing.router,
            note = mergeNotes(existing.note, fresh.note),
            settings = fresh.settings,
            diagnostics = fresh.diagnostics,
        )

        val updated = history
            .filterNot { it.uid == prompt.freshUid }
            .map { if (it.uid == prompt.existingUid) merged else it }
            .sortedByDescending { it.timestamp }

        persist(updated)
        _state.value = _state.value.copy(
            history = updated,
            currentUid = merged.uid,
            duplicate = null,
        )
    }

    private fun string(resId: Int): String = getApplication<Application>().getString(resId)

    fun dismissError() {
        _state.value = _state.value.copy(error = null)
    }

    // ------------------------------------------------------------------ історія

    fun deleteAll(uids: Collection<String>) {
        if (uids.isEmpty()) return
        val doomed = uids.toSet()
        val history = _state.value.history.filterNot { it.uid in doomed }
        persist(history)
        _state.value = _state.value.copy(
            history = history,
            currentUid = _state.value.currentUid?.takeIf { it !in doomed },
        )
    }

    fun delete(uid: String) {
        val history = _state.value.history.filterNot { it.uid == uid }
        persist(history)
        _state.value = _state.value.copy(
            history = history,
            currentUid = if (_state.value.currentUid == uid) null else _state.value.currentUid,
        )
    }

    private fun update(uid: String, transform: (Reading) -> Reading) {
        val history = _state.value.history.map { if (it.uid == uid) transform(it) else it }
        persist(history)
        _state.value = _state.value.copy(history = history)
    }

    /**
     * Запис у файл із невеликою затримкою: примітки правляться посимвольно, і
     * перезаписувати весь JSON на кожну літеру немає сенсу.
     */
    private fun persist(history: List<Reading>) {
        // Демо живе лише в пам'яті й файлу історії не торкається.
        if (_state.value.demo) return
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(PERSIST_DELAY_MS)
            store.save(history)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope тут уже скасовано, тож дописуємо останні правки напряму.
        // Під час демо в стані лежать вигадані записи — зберігаємо справжні.
        store.save(stashedHistory ?: _state.value.history)
    }

    private companion object {
        const val PERSIST_DELAY_MS = 400L

        /** Скільки триває «опитування» в демо — приблизно як справжнє. */
        const val DEMO_POLL_MS = 1_500L

        /** Не питаємо на першому скануванні — хай користувач спершу звикне. */
        const val REVIEW_MIN_SCANS = 2

        /** Не частіше ніж раз на місяць, навіть якщо Google і показав би. */
        const val REVIEW_COOLDOWN_MS = 30L * 24 * 60 * 60 * 1000

        /** Скільки разів за весь час життя застосунку взагалі просити. */
        const val REVIEW_MAX_PROMPTS = 3
    }
}

/**
 * Влиття нотатки при об'єднанні дублікатів. Винесено з класу — чиста функція,
 * тож перевіряється звичайним юніт-тестом без Android-контексту.
 */
internal fun mergeNotes(existing: String, fresh: String): String = when {
    fresh.isBlank() -> existing
    existing.isBlank() -> fresh
    existing.trim() == fresh.trim() -> existing
    else -> existing.trimEnd() + "\n\n" + fresh.trim()
}

/** Результат перевірки: чи варто зараз показати системний діалог оцінки. */
internal data class ReviewDecision(val nextState: ReviewPromptState, val shouldRequestReview: Boolean)

/**
 * Чиста версія рішення «питати відгук чи ні» — винесена з [MainViewModel], щоб
 * усі межові випадки (перше сканування, кулдаун, ліміт кількості) перевірялись
 * юніт-тестом, а не лише вручну на пристрої.
 */
internal fun decideReviewPrompt(
    current: ReviewPromptState,
    now: Long,
    minScans: Int,
    cooldownMs: Long,
    maxPrompts: Int,
): ReviewDecision {
    val incremented = current.copy(completedScans = current.completedScans + 1)
    val cooldownPassed = incremented.lastPromptAtMillis == 0L ||
        now - incremented.lastPromptAtMillis >= cooldownMs
    val shouldAsk = incremented.completedScans >= minScans &&
        incremented.promptCount < maxPrompts &&
        cooldownPassed

    val next = if (shouldAsk) {
        incremented.copy(lastPromptAtMillis = now, promptCount = incremented.promptCount + 1)
    } else {
        incremented
    }
    return ReviewDecision(next, shouldAsk)
}
