package ua.starlink.reader.net

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.Descriptors
import com.google.protobuf.WireFormat
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Клієнт gRPC Server Reflection.
 *
 * Навіщо: номери полів у proto-схемі Starlink (get_status / get_device_info тощо)
 * не є публічно зафіксованими й змінювались між прошивками. Замість того щоб
 * "зашивати" їх у код, ми питаємо схему в самого пристрою і будуємо запит по
 * *імені* поля — це працює на будь-якій прошивці, що вміє рефлексію.
 */
class ReflectionClient(private val channel: GrpcChannel) {

    private val protoByName = LinkedHashMap<String, FileDescriptorProto>()
    private val built = HashMap<String, Descriptors.FileDescriptor>()
    private val requestedFiles = HashSet<String>()
    private var workingMethod: String? = null

    fun loadService(serviceFullName: String): Descriptors.ServiceDescriptor {
        fetch(encodeStringField(FIELD_CONTAINING_SYMBOL, serviceFullName))
        resolveDependencies()
        buildAll()
        return findService(serviceFullName)
            ?: throw IOException("service $serviceFullName not found in the device schema")
    }

    private fun findService(fullName: String): Descriptors.ServiceDescriptor? =
        built.values.firstNotNullOfOrNull { file ->
            file.services.firstOrNull { it.fullName == fullName }
        }

    private fun fetch(requestBytes: ByteArray) {
        val methods = workingMethod?.let { listOf(it) } ?: REFLECTION_METHODS
        var lastError: Exception? = null
        for (method in methods) {
            try {
                val frames = channel.call(method, listOf(requestBytes))
                workingMethod = method
                var stored = false
                for (frame in frames) stored = store(frame) || stored
                if (!stored && frames.isEmpty()) throw IOException("empty reflection response")
                return
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("reflection unavailable")
    }

    /** @return true, якщо у відповіді були дескриптори. */
    private fun store(frame: ByteArray): Boolean {
        var added = false
        val input = CodedInputStream.newInstance(frame)
        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            when (WireFormat.getTagFieldNumber(tag)) {
                FIELD_FILE_DESCRIPTOR_RESPONSE -> {
                    val payload = input.readByteArray()
                    val inner = CodedInputStream.newInstance(payload)
                    while (true) {
                        val innerTag = inner.readTag()
                        if (innerTag == 0) break
                        if (WireFormat.getTagFieldNumber(innerTag) == 1) {
                            val proto = FileDescriptorProto.parseFrom(inner.readByteArray())
                            if (protoByName.putIfAbsent(proto.name, proto) == null) added = true
                        } else {
                            inner.skipField(innerTag)
                        }
                    }
                }

                FIELD_ERROR_RESPONSE -> {
                    val payload = input.readByteArray()
                    throw IOException("reflection returned an error: ${readErrorMessage(payload)}")
                }

                else -> input.skipField(tag)
            }
        }
        return added
    }

    private fun readErrorMessage(payload: ByteArray): String {
        val input = CodedInputStream.newInstance(payload)
        var code = 0
        var message = ""
        while (true) {
            val tag = input.readTag()
            if (tag == 0) break
            when (WireFormat.getTagFieldNumber(tag)) {
                1 -> code = input.readInt32()
                2 -> message = input.readString()
                else -> input.skipField(tag)
            }
        }
        return "$code $message"
    }

    /** Дотягує імпортовані .proto-файли, доки замикання залежностей не стане повним. */
    private fun resolveDependencies() {
        repeat(MAX_DEPENDENCY_ROUNDS) {
            val missing = protoByName.values
                .flatMap { it.dependencyList }
                .distinct()
                .filter { it !in protoByName && it !in WELL_KNOWN && it !in requestedFiles }
            if (missing.isEmpty()) return
            for (name in missing) {
                requestedFiles.add(name)
                runCatching { fetch(encodeStringField(FIELD_BY_FILENAME, name)) }
            }
        }
    }

    private fun buildAll() {
        for (name in protoByName.keys.toList()) {
            runCatching { build(name, HashSet()) }
        }
    }

    private fun build(name: String, stack: MutableSet<String>): Descriptors.FileDescriptor? {
        built[name]?.let { return it }
        WELL_KNOWN[name]?.let { built[name] = it; return it }
        val proto = protoByName[name] ?: return null
        if (!stack.add(name)) return null
        val deps = proto.dependencyList.mapNotNull { build(it, stack) }.toTypedArray()
        // allowUnknownDependencies=true: якщо якийсь імпорт недоступний, решта схеми
        // все одно збереться, а нам потрібні лише рядкові поля DeviceInfo.
        val descriptor = Descriptors.FileDescriptor.buildFrom(proto, deps, true)
        stack.remove(name)
        built[name] = descriptor
        return descriptor
    }

    private fun encodeStringField(fieldNumber: Int, value: String): ByteArray {
        val out = ByteArrayOutputStream()
        val coded = CodedOutputStream.newInstance(out)
        coded.writeString(fieldNumber, value)
        coded.flush()
        return out.toByteArray()
    }

    private companion object {
        val REFLECTION_METHODS = listOf(
            "grpc.reflection.v1.ServerReflection/ServerReflectionInfo",
            "grpc.reflection.v1alpha.ServerReflection/ServerReflectionInfo",
        )

        const val FIELD_BY_FILENAME = 3
        const val FIELD_CONTAINING_SYMBOL = 4
        const val FIELD_FILE_DESCRIPTOR_RESPONSE = 4
        const val FIELD_ERROR_RESPONSE = 7
        const val MAX_DEPENDENCY_ROUNDS = 8

        val WELL_KNOWN: Map<String, Descriptors.FileDescriptor> = buildMap {
            put("google/protobuf/timestamp.proto", com.google.protobuf.Timestamp.getDescriptor().file)
            put("google/protobuf/duration.proto", com.google.protobuf.Duration.getDescriptor().file)
            put("google/protobuf/wrappers.proto", com.google.protobuf.BoolValue.getDescriptor().file)
            put("google/protobuf/any.proto", com.google.protobuf.Any.getDescriptor().file)
            put("google/protobuf/empty.proto", com.google.protobuf.Empty.getDescriptor().file)
            put("google/protobuf/struct.proto", com.google.protobuf.Struct.getDescriptor().file)
            put("google/protobuf/field_mask.proto", com.google.protobuf.FieldMask.getDescriptor().file)
            put("google/protobuf/descriptor.proto", com.google.protobuf.DescriptorProtos.getDescriptor())
        }
    }
}
