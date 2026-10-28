package ua.starlink.reader.data

import java.util.Locale

/**
 * Чи той самий це комплект, що вже є в історії. Спершу за Starlink ID —
 * він належить залізу й не залежить від того, чи сканували коробку. Якщо
 * по мережі нічого не зняли (лише QR, або складський режим), — за KIT-номером.
 *
 * @return найсвіжіший такий запис або null.
 */
fun findDuplicate(fresh: Reading, history: List<Reading>): Reading? {
    val others = history.filter { it.uid != fresh.uid }
    val starlinkId = fresh.starlinkId.trim()
    val kit = fresh.kitNumber.trim()

    val match = when {
        starlinkId.isNotEmpty() -> others.filter { it.starlinkId.trim().equals(starlinkId, ignoreCase = true) }
        kit.isNotEmpty() -> others.filter { it.kitNumber.trim().equals(kit, ignoreCase = true) }
        else -> emptyList()
    }
    return match.maxByOrNull { it.timestamp }
}

/** Ідентифікатор, за яким знайшли повтор, — його й показуємо в діалозі. */
fun duplicateKey(fresh: Reading): String =
    fresh.starlinkId.trim().ifEmpty { fresh.kitNumber.trim() }

/**
 * Пошук в історії: шматок тексту в будь-якому ідентифікаторі чи примітці,
 * без урахування регістру. У MAC двокрапки ігноруються — «5E0053» теж знайде.
 */
fun Reading.matches(query: String): Boolean {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return true
    val compact = q.replace(":", "").replace("-", "")
    val fields = listOf(kitNumber, dishSerial, modemSerial, starlinkId, routerId, effectiveMac, note)
    return fields.any { field ->
        val f = field.lowercase(Locale.ROOT)
        f.contains(q) || (compact.isNotEmpty() && f.replace(":", "").replace("-", "").contains(compact))
    }
}
