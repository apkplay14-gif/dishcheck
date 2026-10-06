package ua.starlink.reader.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Легкий тест швидкості поверх публічних ендпойнтів speed.cloudflare.com —
 * тих самих, якими користується офіційний віджет Cloudflare Speed Test.
 * Власний бекенд чи ключ API не потрібні.
 *
 * Сокет свідомо прив'язується до переданої мережі (Wi-Fi), так само як
 * GrpcChannel прив'язується до неї при опитуванні тарілки: інакше на
 * телефоні з увімкненими мобільними даними Android може мовчки повести тест
 * через стільникову мережу, і результат не матиме стосунку до Starlink.
 */
class SpeedTestClient(socketFactory: SocketFactory) {

    private val client = OkHttpClient.Builder()
        .socketFactory(socketFactory)
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** Найкращий (найменший) з кількох маленьких запитів — один випадковий сплеск не зіпсує результат. */
    fun measurePingMs(samples: Int = 4): Long {
        var best = Long.MAX_VALUE
        repeat(samples) {
            val start = System.nanoTime()
            val request = Request.Builder().url("$HOST/__down?bytes=0").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                response.body?.bytes()
            }
            best = minOf(best, (System.nanoTime() - start) / 1_000_000)
        }
        return best
    }

    fun measureDownloadMbps(bytes: Long = DOWNLOAD_BYTES): Double {
        val request = Request.Builder().url("$HOST/__down?bytes=$bytes").build()
        val start = System.nanoTime()
        var total = 0L

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val source = response.body?.source() ?: throw IOException("empty body")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = source.read(buffer)
                if (read == -1) break
                total += read
            }
        }
        return mbps(total, System.nanoTime() - start)
    }

    fun measureUploadMbps(bytes: Long = UPLOAD_BYTES): Double {
        val body = ByteArray(bytes.toInt()).toRequestBody(OCTET_STREAM)
        val request = Request.Builder().url("$HOST/__up").post(body).build()

        val start = System.nanoTime()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.bytes()
        }
        return mbps(bytes, System.nanoTime() - start)
    }

    private fun mbps(bytes: Long, elapsedNanos: Long): Double {
        val seconds = elapsedNanos / 1_000_000_000.0
        if (seconds <= 0.0) return 0.0
        return (bytes * 8.0 / seconds) / 1_000_000.0
    }

    private companion object {
        const val HOST = "https://speed.cloudflare.com"
        const val DOWNLOAD_BYTES = 10_000_000L
        const val UPLOAD_BYTES = 4_000_000L
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}
