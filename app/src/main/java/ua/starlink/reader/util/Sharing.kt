package ua.starlink.reader.util

import android.content.Context
import android.content.Intent
import ua.starlink.reader.R
import ua.starlink.reader.data.QrStep
import ua.starlink.reader.data.Reading
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

object Sharing {

    // Формати створюємо на місці, а не один раз назавжди: мову застосунку можна
    // змінити на льоту (Android 13+), і закешований SimpleDateFormat лишився б
    // із попередньою локаллю.
    private fun format(pattern: String, timestamp: Long): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))

    fun formatDate(timestamp: Long): String = format("dd.MM.yyyy HH:mm", timestamp)

    fun formatTime(timestamp: Long): String = format("HH:mm", timestamp)

    /** День зчитування — за ним історія групується. */
    fun dayOf(timestamp: Long): LocalDate =
        Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()

    /** Підпис дня: «Сьогодні», «Вчора» або дата. */
    fun dayLabel(context: Context, day: LocalDate, today: LocalDate = LocalDate.now()): String =
        when (day) {
            today -> context.getString(R.string.day_today)
            today.minusDays(1) -> context.getString(R.string.day_yesterday)
            else -> format(
                "dd.MM.yyyy",
                day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            )
        }

    fun recordsPlural(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.records, count, count)

    private fun kitsPlural(context: Context, count: Int): String =
        context.resources.getQuantityString(R.plurals.kits, count, count)

    /**
     * Текст для WhatsApp / Signal / Telegram. Порядок рядків збігається з порядком
     * зчитування, і виводяться лише ті пункти, які були обрані в меню.
     */
    fun buildText(context: Context, reading: Reading): String = buildString {
        appendLine(context.getString(R.string.share_header_one))
        appendLine(context.getString(R.string.share_date, formatDate(reading.timestamp)))
        appendLine()
        appendReading(context, reading)
    }.trimEnd()

    /**
     * Текст для кількох комплектів одразу: згруповані по днях, у кожному дні —
     * від новішого до старішого. Один комплект віддається у звичному вигляді.
     */
    fun buildText(context: Context, readings: List<Reading>): String {
        if (readings.isEmpty()) return ""
        if (readings.size == 1) return buildText(context, readings.first())

        val sorted = readings.sortedByDescending { it.timestamp }
        val today = LocalDate.now()

        return buildString {
            appendLine(
                context.getString(R.string.share_header_many, kitsPlural(context, sorted.size))
            )
            appendLine(
                context.getString(R.string.share_generated, formatDate(System.currentTimeMillis()))
            )

            var currentDay: LocalDate? = null
            sorted.forEachIndexed { index, reading ->
                val day = dayOf(reading.timestamp)
                if (day != currentDay) {
                    appendLine()
                    appendLine("--- ${dayLabel(context, day, today)} ---")
                    currentDay = day
                }
                appendLine()
                appendLine("[${index + 1}] ${formatTime(reading.timestamp)}")
                appendReading(context, reading)
            }
        }.trimEnd()
    }

    /** Тіло одного запису: тільки поля, без заголовка й дати. */
    private fun StringBuilder.appendReading(context: Context, reading: Reading) {
        val settings = reading.settings

        if (settings.kitNumber) {
            appendValue(context.getString(R.string.share_kit), reading.kitNumber)
        }
        if (settings.dishSerial) {
            appendValue(context.getString(R.string.menu_dish_serial), reading.dishSerial)
        }
        if (settings.modemSerial) {
            appendValue(context.getString(R.string.menu_modem_serial), reading.modemSerial)
        }
        if (settings.starlinkId) {
            appendValue(context.getString(R.string.menu_starlink_id), reading.starlinkId)
        }
        if (settings.routerId) {
            appendValue(context.getString(R.string.menu_router_id), reading.routerId)
        }
        if (settings.modemMac) {
            appendValue(context.getString(R.string.menu_mac), reading.effectiveMac)
        }

        if (reading.note.isNotBlank()) {
            appendLine(context.getString(R.string.share_note))
            appendLine(reading.note.trim())
        }
    }

    private fun StringBuilder.appendValue(label: String, value: String) {
        appendLine("$label: ${value.ifBlank { "—" }}")
    }

    /**
     * Позначка зверху для всього, що надсилається з демо-режиму: переслане
     * далі повідомлення з вигаданими номерами не має виглядати як справжнє.
     */
    fun markDemo(context: Context, text: String): String =
        context.getString(R.string.demo_share_mark, context.getString(R.string.app_name)) +
            "\n\n" + text

    fun share(context: Context, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_header_one))
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.share_chooser))
        )
    }

    /**
     * Приводить сканований код до потрібного вигляду. На коробці буває як голий
     * номер, так і посилання чи рядок з кількох полів, тому для KIT спершу
     * шукаємо шаблон KIT…, а для серійників беремо останній непорожній сегмент URL.
     */
    fun normalizeScan(step: QrStep, raw: String): String {
        val trimmed = raw.trim()
        return when (step) {
            QrStep.KIT -> KIT_REGEX.find(trimmed)?.value?.uppercase(Locale.US) ?: fromUrl(trimmed)
            QrStep.DISH_SERIAL, QrStep.MODEM_SERIAL -> fromUrl(trimmed)
        }
    }

    /** Якщо код — посилання, беремо останній сегмент; інакше повертаємо як є. */
    private fun fromUrl(text: String): String {
        if (!text.startsWith("http://") && !text.startsWith("https://")) return text
        return text.substringBefore('?')
            .trimEnd('/')
            .substringAfterLast('/')
            .ifBlank { text }
    }

    private val KIT_REGEX = Regex("KIT[0-9A-Za-z]{6,}")
}
