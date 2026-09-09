package ua.starlink.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ua.starlink.reader.data.CaptureSettings
import ua.starlink.reader.data.EditableField
import ua.starlink.reader.data.HistoryStore
import ua.starlink.reader.data.QrStep
import ua.starlink.reader.data.Reading
import ua.starlink.reader.data.SettingsStore
import ua.starlink.reader.net.LinkState
import ua.starlink.reader.net.NetUtil
import ua.starlink.reader.net.StarlinkClient
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
) {
    val current: Reading? get() = history.firstOrNull { it.uid == currentUid }
}

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
    private var persistJob: Job? = null
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        _state.value = _state.value.copy(
            history = store.load(),
            settings = settingsStore.load(),
        )
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

    // ------------------------------------------------------------------ зчитування

    /** Створює порожній запис і робить його поточним. Повертає його uid. */
    fun beginCapture(): String {
        val reading = Reading(
            uid = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            settings = _state.value.settings,
        )
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

        if (isNewCapture) checkDuplicate(reading)
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

    private fun mergeNotes(existing: String, fresh: String): String = when {
        fresh.isBlank() -> existing
        existing.isBlank() -> fresh
        existing.trim() == fresh.trim() -> existing
        else -> existing.trimEnd() + "\n\n" + fresh.trim()
    }

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
        persistJob?.cancel()
        persistJob = viewModelScope.launch(Dispatchers.IO) {
            delay(PERSIST_DELAY_MS)
            store.save(history)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // viewModelScope тут уже скасовано, тож дописуємо останні правки напряму.
        store.save(_state.value.history)
    }

    private companion object {
        const val PERSIST_DELAY_MS = 400L
    }
}
