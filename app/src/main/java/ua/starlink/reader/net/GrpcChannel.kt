package ua.starlink.reader.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class GrpcException(val status: Int, val statusMessage: String) :
    IOException("gRPC status $status: $statusMessage")

/**
 * Мінімальний gRPC-клієнт поверх OkHttp з "prior knowledge" HTTP/2 без TLS (h2c),
 * саме так спілкується локальний API Starlink. Повноцінна grpc-java тут не потрібна:
 * кадрування gRPC — це 1 байт прапорця стиснення + 4 байти довжини + payload.
 */
class GrpcChannel(
    private val host: String,
    private val port: Int,
    socketFactory: SocketFactory,
    timeoutSeconds: Long = 8,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.H2_PRIOR_KNOWLEDGE))
        .socketFactory(socketFactory)
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    val target: String get() = "$host:$port"

    /**
     * Надсилає [messages] одним запитом і напівзакриває потік. Для unary-викликів це
     * звичайний виклик, для bidi-stream (рефлексія) сервер відповідає й закриває потік.
     */
    fun call(fullMethodName: String, messages: List<ByteArray>): List<ByteArray> {
        val body = object : RequestBody() {
            override fun contentType() = GRPC_PROTO
            override fun writeTo(sink: BufferedSink) {
                for (m in messages) {
                    sink.writeByte(0)
                    sink.writeInt(m.size)
                    sink.write(m)
                }
            }
        }

        val request = Request.Builder()
            .url("http://$host:$port/$fullMethodName")
            .header("te", "trailers")
            .header("grpc-accept-encoding", "identity")
            .header("user-agent", "starlink-reader-android/1.0")
            .post(body)
            .build()

        client.newCall(request).execute().use { response ->
            val payload = response.body?.bytes() ?: ByteArray(0)
            val status = response.header("grpc-status")
                ?: runCatching { response.trailers()["grpc-status"] }.getOrNull()
            val message = response.header("grpc-message")
                ?: runCatching { response.trailers()["grpc-message"] }.getOrNull()

            if (!response.isSuccessful) {
                throw GrpcException(-1, "HTTP ${response.code}")
            }
            if (status != null && status != "0") {
                throw GrpcException(status.toIntOrNull() ?: -1, decodePercent(message ?: ""))
            }
            return unframe(payload)
        }
    }

    fun callSingle(fullMethodName: String, message: ByteArray): ByteArray {
        val frames = call(fullMethodName, listOf(message))
        return frames.firstOrNull() ?: throw IOException("empty response from $target")
    }

    private fun unframe(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i + 5 <= data.size) {
            val compressed = data[i].toInt() and 0xFF
            val len = ((data[i + 1].toInt() and 0xFF) shl 24) or
                ((data[i + 2].toInt() and 0xFF) shl 16) or
                ((data[i + 3].toInt() and 0xFF) shl 8) or
                (data[i + 4].toInt() and 0xFF)
            i += 5
            if (len < 0 || i + len > data.size) break
            if (compressed == 0) out.add(data.copyOfRange(i, i + len))
            i += len
        }
        return out
    }

    private fun decodePercent(s: String): String = try {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                sb.append(Char(s.substring(i + 1, i + 3).toInt(16)))
                i += 3
            } else {
                sb.append(c); i++
            }
        }
        sb.toString()
    } catch (_: Exception) {
        s
    }

    private companion object {
        val GRPC_PROTO = "application/grpc+proto".toMediaType()
    }
}
