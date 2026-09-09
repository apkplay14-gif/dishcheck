package ua.starlink.reader.data

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Локальне сховище історії. Дані нікуди не надсилаються — це звичайний JSON-файл
 * у приватній папці додатка.
 */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val listSerializer = ListSerializer(Reading.serializer())

    @Synchronized
    fun load(): List<Reading> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(listSerializer, file.readText())
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(readings: List<Reading>) {
        try {
            file.writeText(json.encodeToString(listSerializer, readings))
        } catch (e: Exception) {
            // Немає сенсу падати через збій запису — історія не критична для читання даних.
        }
    }

    private companion object {
        const val FILE_NAME = "history.json"
    }
}
