package ua.starlink.reader.net

import com.google.protobuf.CodedInputStream
import com.google.protobuf.WireFormat

/**
 * Універсальний обхід protobuf по wire-формату, без знання схеми.
 * Використовується як запасний варіант, коли пристрій не віддає рефлексію:
 * ми просто збираємо всі рядкові поля відповіді та впізнаємо потрібні за виглядом.
 */
object WireWalk {

    data class Item(val path: String, val value: String)

    fun strings(data: ByteArray): List<Item> {
        val out = ArrayList<Item>()
        walk(data, "", 0, out)
        return out
    }

    private fun walk(data: ByteArray, prefix: String, depth: Int, out: MutableList<Item>) {
        if (depth > 8) return
        val input = CodedInputStream.newInstance(data)
        try {
            while (true) {
                val tag = input.readTag()
                if (tag == 0) return
                val field = WireFormat.getTagFieldNumber(tag)
                val path = if (prefix.isEmpty()) "$field" else "$prefix.$field"
                when (WireFormat.getTagWireType(tag)) {
                    WireFormat.WIRETYPE_VARINT -> input.readInt64()
                    WireFormat.WIRETYPE_FIXED64 -> input.readFixed64()
                    WireFormat.WIRETYPE_FIXED32 -> input.readFixed32()
                    WireFormat.WIRETYPE_LENGTH_DELIMITED -> {
                        val bytes = input.readByteArray()
                        val text = asText(bytes)
                        if (text != null) {
                            out.add(Item(path, text))
                        } else {
                            walk(bytes, path, depth + 1, out)
                        }
                    }

                    else -> input.skipField(tag)
                }
            }
        } catch (_: Exception) {
            // Пошкоджений або невпізнаний фрагмент — просто зупиняємось на цій гілці.
        }
    }

    /** Повертає рядок, якщо байти виглядають як друкований UTF-8 текст. */
    private fun asText(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > 512) return null
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v < 0x20 || v == 0x7F) return null
        }
        val s = String(bytes, Charsets.UTF_8)
        return if (s.isBlank()) null else s
    }
}
