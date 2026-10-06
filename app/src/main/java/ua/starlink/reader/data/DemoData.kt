package ua.starlink.reader.data

import java.util.Locale

/**
 * Вигадані, але правдоподібні дані для демо-режиму. Потрібні тим, у кого під
 * рукою немає Starlink: рецензенту Google Play, запису відео для сторінки в
 * магазині й людині, яка хоче спершу подивитись, як усе працює.
 *
 * Кожне значення навмисно містить «DEMO», а MAC узято з діапазону, який
 * RFC 7042 відводить під документацію, — навіть пересланий текст неможливо
 * сплутати зі справжнім комплектом.
 *
 * Усе будується від порядкового номера комплекту [seq], тож скановані коди й
 * відповіді «тарілки» одного запису завжди збігаються між собою, а різні
 * демо-комплекти не вважаються повтором одне одного.
 */
object DemoData {

    /** Код, який демо-сканер «знаходить» на кроці [step]. */
    fun scanValue(step: QrStep, seq: Int): String = when (step) {
        QrStep.KIT -> "KITDEMO%05d".format(Locale.US, seq)
        QrStep.DISH_SERIAL -> "DEMO-DISH-%05d".format(Locale.US, seq)
        QrStep.MODEM_SERIAL -> "DEMO-ROUTER-%05d".format(Locale.US, seq)
    }

    fun starlinkId(seq: Int): String = "ut00000000-00000000-demo%04d".format(Locale.US, seq)

    fun routerId(seq: Int): String = "Router-0000000000000000demo%04d".format(Locale.US, seq)

    fun mac(seq: Int): String = "00:00:5E:00:53:%02X".format(Locale.US, seq and 0xFF)

    /** Відповідь «тарілки» на опитування. */
    fun dish(seq: Int): DeviceData = device(starlinkId(seq), mac = null)

    /** Відповідь «роутера»; MAC — лише якщо його просили, як і в справжньому опитуванні. */
    fun router(seq: Int, withMac: Boolean): DeviceData =
        device(routerId(seq), mac = if (withMac) mac(seq) else null)

    private fun device(id: String, mac: String?) = DeviceData(
        id = id,
        macAddress = mac,
        fields = mapOf("id" to id, "hardware_version" to "demo", "software_version" to "demo"),
    )

    /** Уже знятий комплект — для прикладів в історії. */
    fun reading(
        uid: String,
        seq: Int,
        timestamp: Long,
        note: String,
        settings: CaptureSettings = CaptureSettings(),
    ): Reading {
        val empty = Reading(uid = uid, timestamp = timestamp, note = note, settings = settings)
        val scanned = settings.qrSteps.fold(empty) { reading, step ->
            reading.with(step, scanValue(step, seq))
        }
        return scanned.copy(
            dish = if (settings.needsDish) dish(seq) else null,
            router = if (settings.needsRouter) router(seq, withMac = settings.modemMac) else null,
            diagnostics = LOG,
        )
    }

    /** Журнал «опитування» — щоб у розділі «Додатково» було видно, що мережі не було. */
    const val LOG = "== demo ==\nno network requests: the demo answers instead of the dish and router"
}
