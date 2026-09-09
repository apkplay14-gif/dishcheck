package ua.starlink.reader.data

import androidx.annotation.StringRes
import kotlinx.serialization.Serializable
import ua.starlink.reader.R

/** Крок сканування QR / штрихкоду. Порядок збігається з порядком у меню. */
enum class QrStep(val order: Int, @StringRes val titleRes: Int) {
    KIT(1, R.string.step_kit),
    DISH_SERIAL(2, R.string.step_dish_serial),
    MODEM_SERIAL(3, R.string.step_modem_serial),
}

/** Поля, які можна правити руками, якщо код не зчитався. */
enum class EditableField { KIT, DISH_SERIAL, MODEM_SERIAL, MODEM_MAC, NOTE }

/**
 * Що саме знімати з комплекту. Порядок полів = порядок зчитування на екрані.
 */
@Serializable
data class CaptureSettings(
    val kitNumber: Boolean = true,
    val dishSerial: Boolean = true,
    val modemSerial: Boolean = true,
    val starlinkId: Boolean = true,
    val routerId: Boolean = true,
    val modemMac: Boolean = true,
) {
    val enabledCount: Int
        get() = listOf(kitNumber, dishSerial, modemSerial, starlinkId, routerId, modemMac)
            .count { it }

    val anyEnabled: Boolean get() = enabledCount > 0

    /** Кроки сканування QR, які потрібно пройти, у правильному порядку. */
    val qrSteps: List<QrStep>
        get() = buildList {
            if (kitNumber) add(QrStep.KIT)
            if (dishSerial) add(QrStep.DISH_SERIAL)
            if (modemSerial) add(QrStep.MODEM_SERIAL)
        }

    /** Чи треба опитувати тарілку по мережі. */
    val needsDish: Boolean get() = starlinkId

    /** Чи треба опитувати роутер по мережі. */
    val needsRouter: Boolean get() = routerId || modemMac

    val needsNetwork: Boolean get() = needsDish || needsRouter
}

/** Дані одного пристрою (тарілка або роутер). */
@Serializable
data class DeviceData(
    val id: String? = null,
    val macAddress: String? = null,
    /** Усі решта прочитаних полів — для довідки та діагностики. */
    val fields: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = id.isNullOrBlank() && macAddress.isNullOrBlank()
}

/** Одне збережене зчитування комплекту. */
@Serializable
data class Reading(
    val uid: String,
    val timestamp: Long,
    val kitNumber: String = "",
    val dishSerial: String = "",
    val modemSerial: String = "",
    val modemMac: String = "",
    val note: String = "",
    val dish: DeviceData? = null,
    val router: DeviceData? = null,
    /** Набір галочок, з якими знімався саме цей комплект. */
    val settings: CaptureSettings = CaptureSettings(),
    val diagnostics: String = "",
) {
    val starlinkId: String get() = dish?.id.orEmpty()
    val routerId: String get() = router?.id.orEmpty()

    /** MAC із роутера, а якщо його не віддали — введений руками. */
    val effectiveMac: String
        get() = modemMac.ifBlank { router?.macAddress.orEmpty() }

    val isEmpty: Boolean
        get() = kitNumber.isBlank() && dishSerial.isBlank() && modemSerial.isBlank() &&
            starlinkId.isBlank() && routerId.isBlank() && effectiveMac.isBlank()

    /** Найінформативніший ідентифікатор запису; null — якщо нічого не знято. */
    val titleOrNull: String?
        get() = listOf(kitNumber, dishSerial, starlinkId, modemSerial, routerId)
            .firstOrNull { it.isNotBlank() }

    fun value(field: EditableField): String = when (field) {
        EditableField.KIT -> kitNumber
        EditableField.DISH_SERIAL -> dishSerial
        EditableField.MODEM_SERIAL -> modemSerial
        EditableField.MODEM_MAC -> effectiveMac
        EditableField.NOTE -> note
    }

    fun with(field: EditableField, text: String): Reading = when (field) {
        EditableField.KIT -> copy(kitNumber = text)
        EditableField.DISH_SERIAL -> copy(dishSerial = text)
        EditableField.MODEM_SERIAL -> copy(modemSerial = text)
        EditableField.MODEM_MAC -> copy(modemMac = text)
        EditableField.NOTE -> copy(note = text)
    }

    fun with(step: QrStep, text: String): Reading = when (step) {
        QrStep.KIT -> copy(kitNumber = text)
        QrStep.DISH_SERIAL -> copy(dishSerial = text)
        QrStep.MODEM_SERIAL -> copy(modemSerial = text)
    }
}
