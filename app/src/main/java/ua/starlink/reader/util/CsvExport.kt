package ua.starlink.reader.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import ua.starlink.reader.R
import ua.starlink.reader.data.Reading
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Експорт історії в CSV, який Excel і Google Таблиці відкривають без
 * майстра імпорту: UTF-8 з BOM (інакше Excel псує кирилицю) і крапка з
 * комою як роздільник — так Excel з українськими чи європейськими
 * налаштуваннями одразу розкладає стовпці.
 */
object CsvExport {

    private const val SEPARATOR = ';'

    /**
     * Чиста функція — перевіряється юніт-тестом. Записи від старіших до новіших,
     * як у звичайному журналі обліку. Усі поля виводяться завжди, навіть якщо
     * якесь не знімали, — щоб стовпці в таблиці не «їхали».
     *
     * @param headers 9 підписів стовпців у потрібній мові.
     */
    fun build(readings: List<Reading>, headers: List<String>, formatDate: (Long) -> String, formatTime: (Long) -> String): String =
        buildString {
            append('﻿')
            appendRow(headers)
            readings.sortedBy { it.timestamp }.forEach { r ->
                appendRow(
                    listOf(
                        formatDate(r.timestamp), formatTime(r.timestamp),
                        r.kitNumber, r.dishSerial, r.modemSerial,
                        r.starlinkId, r.routerId, r.effectiveMac,
                        r.note.trim(),
                    )
                )
            }
        }

    private fun StringBuilder.appendRow(cells: List<String>) {
        cells.joinTo(this, SEPARATOR.toString()) { escape(it) }
        append("\r\n")
    }

    /** Лапки, роздільник чи перенос рядка всередині значення — беремо в лапки. */
    internal fun escape(value: String): String {
        val needsQuotes = value.any { it == SEPARATOR || it == '"' || it == '\n' || it == '\r' }
        // Excel сприймає «=…», «+…», «-…», «@…» як формулу — це і псує вигляд,
        // і є відомою дірою (CSV injection). Апостроф на початку робить це текстом.
        val safe = if (value.firstOrNull() in setOf('=', '+', '-', '@')) "'$value" else value
        return if (needsQuotes) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    /** Пише файл у кеш і відкриває системне вікно «Поділитися» з ним. */
    fun share(context: Context, readings: List<Reading>) {
        val headers = listOf(
            R.string.csv_date, R.string.csv_time, R.string.share_kit, R.string.menu_dish_serial,
            R.string.menu_modem_serial, R.string.menu_starlink_id, R.string.menu_router_id,
            R.string.menu_mac, R.string.share_note,
        ).map { context.getString(it).trimEnd(':') }

        val csv = build(
            readings, headers,
            formatDate = { format("dd.MM.yyyy", it) },
            formatTime = { format("HH:mm", it) },
        )

        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        // Старі експорти не накопичуємо: у кеші лежить лише останній.
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(Date())
        val file = File(dir, "starlink_$stamp.csv").apply { writeText(csv, Charsets.UTF_8) }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.export", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_chooser)))
    }

    private fun format(pattern: String, timestamp: Long): String =
        SimpleDateFormat(pattern, Locale.getDefault()).format(Date(timestamp))
}
