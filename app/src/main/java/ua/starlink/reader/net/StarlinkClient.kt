package ua.starlink.reader.net

import com.google.protobuf.Descriptors
import com.google.protobuf.DynamicMessage
import com.google.protobuf.Message
import ua.starlink.reader.data.DeviceData
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.net.SocketFactory

/** Зчитування локального gRPC API Starlink. */
class StarlinkClient(private val socketFactory: SocketFactory) {

    /**
     * @param wantMac шукати також MAC-адресу; вона лежить не в DeviceInfo, тож може
     *   знадобитись додатковий запит (наприклад wifi_get_status).
     * @param allowRawProbe дозволити резервний режим без рефлексії (див. [readViaRawProbe]).
     */
    fun read(
        host: String,
        port: Int,
        log: StringBuilder,
        wantMac: Boolean,
        allowRawProbe: Boolean,
    ): DeviceData? {
        val channel = GrpcChannel(host, port, socketFactory)
        log.appendLine("== $host:$port ==")

        try {
            val data = readViaReflection(channel, log, wantMac)
            if (data != null) return data
        } catch (e: Exception) {
            log.appendLine("reflection: ${e.message}")
        }

        if (!allowRawProbe) return null

        return try {
            readViaRawProbe(channel, log)
        } catch (e: Exception) {
            log.appendLine("fallback mode: ${e.message}")
            null
        }
    }

    // ---------------------------------------------------------------- рефлексія

    private fun readViaReflection(
        channel: GrpcChannel,
        log: StringBuilder,
        wantMac: Boolean,
    ): DeviceData? {
        val service = ReflectionClient(channel).loadService(SERVICE)
        val method = service.findMethodByName(METHOD)
            ?: throw IOException("method $METHOD missing from the schema")
        val requestType = method.inputType
        val fullMethod = "${service.fullName}/${method.name}"

        var info: Map<String, String>? = null
        var mac: String? = null

        for (fieldName in REQUEST_FIELDS) {
            if (info != null && (!wantMac || mac != null)) break

            val field = requestType.findFieldByName(fieldName) ?: continue
            if (field.javaType != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue

            val request = DynamicMessage.newBuilder(requestType)
                .setField(field, DynamicMessage.getDefaultInstance(field.messageType))
                .build()

            try {
                val bytes = channel.callSingle(fullMethod, request.toByteArray())
                val response = DynamicMessage.parseFrom(method.outputType, bytes)

                val strings = ArrayList<Pair<String, String>>()
                collectStrings(response, 0, strings)

                if (info == null) {
                    info = findDeviceInfo(response, 0)
                    if (info != null) log.appendLine("$fieldName: DeviceInfo found")
                }
                if (mac == null) {
                    mac = pickMac(strings)
                    if (mac != null) log.appendLine("$fieldName: MAC found")
                }
                if (info == null && mac == null) {
                    log.appendLine("$fieldName: nothing useful (${strings.size} strings)")
                }
            } catch (e: Exception) {
                log.appendLine("$fieldName: ${e.message}")
            }
        }

        if (info == null && mac == null) return null
        return toDeviceData(info.orEmpty(), mac)
    }

    /** Шукає у відповіді вкладене повідомлення, схоже на DeviceInfo. */
    private fun findDeviceInfo(message: Message, depth: Int): Map<String, String>? {
        if (depth > MAX_DEPTH) return null
        val descriptor = message.descriptorForType
        val looksLikeDeviceInfo = descriptor.findFieldByName("id") != null &&
            (descriptor.findFieldByName("hardware_version") != null ||
                descriptor.findFieldByName("software_version") != null)

        if (looksLikeDeviceInfo) {
            val scalars = scalarFields(message)
            val id = scalars["id"]
            if (id != null && id.isNotBlank()) return scalars
        }

        for ((field, value) in message.allFields) {
            if (field.javaType != Descriptors.FieldDescriptor.JavaType.MESSAGE) continue
            if (field.isRepeated) {
                for (item in value as List<*>) {
                    val nested = item as? Message ?: continue
                    findDeviceInfo(nested, depth + 1)?.let { return it }
                }
            } else {
                val nested = value as? Message ?: continue
                findDeviceInfo(nested, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** Збирає всі рядкові поля відповіді разом з їхніми іменами. */
    private fun collectStrings(
        message: Message,
        depth: Int,
        out: MutableList<Pair<String, String>>,
    ) {
        if (depth > MAX_DEPTH) return
        for ((field, value) in message.allFields) {
            when {
                field.javaType == Descriptors.FieldDescriptor.JavaType.STRING -> {
                    if (field.isRepeated) {
                        for (item in value as List<*>) {
                            val text = item as? String ?: continue
                            out.add(field.name to text)
                        }
                    } else {
                        out.add(field.name to value.toString())
                    }
                }

                field.javaType == Descriptors.FieldDescriptor.JavaType.MESSAGE -> {
                    if (field.isRepeated) {
                        for (item in value as List<*>) {
                            val nested = item as? Message ?: continue
                            collectStrings(nested, depth + 1, out)
                        }
                    } else {
                        val nested = value as? Message ?: continue
                        collectStrings(nested, depth + 1, out)
                    }
                }
            }
        }
    }

    /** Спершу поле з «mac» в назві, інакше будь-який рядок формату MAC. */
    private fun pickMac(strings: List<Pair<String, String>>): String? {
        strings.firstOrNull { (name, value) ->
            name.contains("mac", ignoreCase = true) && MAC_REGEX.matches(value)
        }?.let { return it.second }
        return strings.firstOrNull { MAC_REGEX.matches(it.second) }?.second
    }

    private fun scalarFields(message: Message): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (field in message.descriptorForType.fields) {
            if (field.isRepeated) continue
            if (field.javaType == Descriptors.FieldDescriptor.JavaType.MESSAGE) continue
            if (!isSet(message, field)) continue
            val value = message.getField(field)?.toString().orEmpty()
            if (value.isNotBlank()) out[field.name] = value
        }
        return out
    }

    private fun isSet(message: Message, field: Descriptors.FieldDescriptor): Boolean = try {
        if (field.hasPresence()) message.hasField(field) else true
    } catch (e: Exception) {
        true
    }

    private fun toDeviceData(fields: Map<String, String>, mac: String?) = DeviceData(
        id = fields["id"],
        macAddress = mac ?: fields.entries.firstOrNull {
            it.key.contains("mac", ignoreCase = true) && MAC_REGEX.matches(it.value)
        }?.value,
        fields = fields,
    )

    // ------------------------------------------------------------ резервний режим

    /**
     * Резервний шлях, коли пристрій не віддає схему через рефлексію.
     *
     * Свідомо НЕ перебираємо номери полів наосліп: у тій самій oneof лежать і дії
     * (reboot, stow), тож випадкове влучання перезавантажило б тарілку. Пробуємо лише
     * два номери, які в усіх відомих прошивках означають читання інформації:
     * 1008 = get_device_info, 1004 = get_status.
     */
    private fun readViaRawProbe(channel: GrpcChannel, log: StringBuilder): DeviceData? {
        for (fieldNumber in RAW_READ_ONLY_FIELDS) {
            try {
                val bytes = channel.callSingle("$SERVICE/$METHOD", emptyMessageField(fieldNumber))
                val items = WireWalk.strings(bytes)
                if (items.isEmpty()) {
                    log.appendLine("field $fieldNumber: no strings found")
                    continue
                }
                val data = fromHeuristics(items)
                val id = data.id
                if (id != null && id.isNotBlank()) {
                    log.appendLine("read via field $fieldNumber (fallback mode)")
                    return data
                }
                log.appendLine("field $fieldNumber: ${items.size} strings, ID not recognised")
            } catch (e: Exception) {
                log.appendLine("field $fieldNumber: ${e.message}")
            }
        }
        return null
    }

    private fun fromHeuristics(items: List<WireWalk.Item>): DeviceData {
        val values = items.map { it.value }
        return DeviceData(
            id = values.firstOrNull { ID_REGEX.matches(it) },
            macAddress = values.firstOrNull { MAC_REGEX.matches(it) },
            fields = items.associate { it.path to it.value },
        )
    }

    /** Кодує порожнє повідомлення у полі [fieldNumber]: varint-тег + нульова довжина. */
    private fun emptyMessageField(fieldNumber: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var tag = (fieldNumber shl 3) or 2
        while (tag and 0x7F.inv() != 0) {
            out.write((tag and 0x7F) or 0x80)
            tag = tag ushr 7
        }
        out.write(tag)
        out.write(0)
        return out.toByteArray()
    }

    companion object {
        const val SERVICE = "SpaceX.API.Device.Device"
        const val METHOD = "Handle"

        const val DISH_HOST = "192.168.100.1"
        const val DISH_PORT = 9200
        const val ROUTER_HOST = "192.168.1.1"
        const val ROUTER_PORT = 9000

        private const val MAX_DEPTH = 8

        private val REQUEST_FIELDS = listOf(
            "get_device_info",
            "get_status",
            "dish_get_status",
            "wifi_get_status",
        )

        private val RAW_READ_ONLY_FIELDS = listOf(1008, 1004)

        private val ID_REGEX = Regex("^[A-Za-z0-9]{2,}-[0-9a-fA-F]{6,}-[0-9a-fA-F]{6,}$")
        private val MAC_REGEX = Regex("^([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}$")
    }
}
