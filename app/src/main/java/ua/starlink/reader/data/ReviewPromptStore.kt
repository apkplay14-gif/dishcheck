package ua.starlink.reader.data

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/** Лічильники запиту відгуку — теж локально, поруч з історією й налаштуваннями. */
class ReviewPromptStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val serializer = ReviewPromptState.serializer()

    fun load(): ReviewPromptState {
        if (!file.exists()) return ReviewPromptState()
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            ReviewPromptState()
        }
    }

    fun save(state: ReviewPromptState) {
        try {
            file.writeText(json.encodeToString(serializer, state))
        } catch (e: Exception) {
            // Втрата лічильника не критична — щонайбільше зайвий раз спитаємо відгук.
        }
    }

    private companion object {
        const val FILE_NAME = "review_prompt.json"
    }
}
