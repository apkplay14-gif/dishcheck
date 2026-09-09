package ua.starlink.reader.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/** Галочки «що зчитувати» — теж локально, поруч з історією. */
class SettingsStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val serializer = CaptureSettings.serializer()

    fun load(): CaptureSettings {
        if (!file.exists()) return CaptureSettings()
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            CaptureSettings()
        }
    }

    fun save(settings: CaptureSettings) {
        try {
            file.writeText(json.encodeToString(serializer, settings))
        } catch (e: Exception) {
            // Втрата налаштувань не критична — наступного разу візьмуться типові.
        }
    }

    private companion object {
        const val FILE_NAME = "capture_settings.json"
    }
}
