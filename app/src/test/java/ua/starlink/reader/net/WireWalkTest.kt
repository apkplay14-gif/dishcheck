package ua.starlink.reader.net

import com.google.protobuf.CodedOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * WireWalk розбирає protobuf-відповідь пристрою наосліп, без .proto-схеми —
 * тому тут вручну кодуються сирі повідомлення й перевіряється, що розбір
 * знаходить текстові поля на будь-якій глибині й не падає на сміттєвих байтах.
 */
class WireWalkTest {

    private fun encode(block: CodedOutputStream.() -> Unit): ByteArray {
        val buffer = ByteArrayOutputStream()
        val out = CodedOutputStream.newInstance(buffer)
        out.block()
        out.flush()
        return buffer.toByteArray()
    }

    @Test
    fun `finds a top-level string field`() {
        val data = encode { writeString(1, "KIT1234567") }
        assertEquals(listOf(WireWalk.Item("1", "KIT1234567")), WireWalk.strings(data))
    }

    @Test
    fun `recurses into a nested message to find its string field`() {
        val nested = encode { writeString(1, "nested-value") }
        val data = encode {
            writeInt32(1, 7) // не рядок — не має потрапити у вивід
            writeByteArray(2, nested)
        }
        val items = WireWalk.strings(data)
        assertTrue(items.contains(WireWalk.Item("2.1", "nested-value")))
        assertTrue(items.none { it.path == "1" })
    }

    @Test
    fun `binary length-delimited bytes are not misread as text`() {
        // Перший байт < 0x20 — не друковний, тож не рядок; як вкладене
        // повідомлення це теж невалідно (tag = 0), просто нуль елементів.
        val binary = byteArrayOf(0x00, 0x01, 0x02, 0xFF.toByte())
        val data = encode { writeByteArray(1, binary) }
        assertTrue(WireWalk.strings(data).isEmpty())
    }

    @Test
    fun `malformed nested bytes are swallowed instead of throwing`() {
        // Тег коректний (поле 1, varint), але обірваний варінт без термінального
        // байта — CodedInputStream кине виняток, який WireWalk має проковтнути.
        val malformed = byteArrayOf(
            0x08,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(),
        )
        val data = encode { writeByteArray(1, malformed) }

        // Головне — що виклик не кидає виняток назовні.
        val items = WireWalk.strings(data)
        assertTrue(items.none { it.path.startsWith("1.") })
    }

    @Test
    fun `recursion stops at the depth limit instead of looping forever`() {
        var payload = encode { writeString(1, "deepest") }
        repeat(10) {
            payload = encode { writeByteArray(1, payload) }
        }
        // На такій глибині ліміт (8) обірве обхід раніше, ніж дійде до "deepest".
        assertTrue(WireWalk.strings(payload).none { it.value == "deepest" })
    }
}
