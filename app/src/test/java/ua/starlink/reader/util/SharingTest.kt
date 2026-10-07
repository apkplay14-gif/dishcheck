package ua.starlink.reader.util

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.starlink.reader.data.QrStep
import ua.starlink.reader.data.Reading

/** Тільки чисті функції — ті, що потребують Context, лишаються без юніт-тестів. */
class SharingTest {

    @Test
    fun `kit code is extracted from a bare KIT string`() {
        assertEquals("KIT1234567", Sharing.normalizeScan(QrStep.KIT, "KIT1234567"))
    }

    @Test
    fun `kit code is uppercased and trimmed`() {
        // Регекс шукає літеральний префікс "KIT" (у верхньому регістрі), а вже
        // знайдений збіг переводить у верхній регістр цілком.
        assertEquals("KITABC1234", Sharing.normalizeScan(QrStep.KIT, "  KITabc1234  "))
    }

    @Test
    fun `kit code is found inside a longer label string`() {
        assertEquals("KIT1234567", Sharing.normalizeScan(QrStep.KIT, "S/N: KIT1234567 Rev A"))
    }

    @Test
    fun `kit falls back to url-style extraction when no KIT pattern matches`() {
        assertEquals("1234567", Sharing.normalizeScan(QrStep.KIT, "https://starlink.com/kit/1234567"))
    }

    @Test
    fun `dish serial takes the last url segment`() {
        assertEquals(
            "DISHSERIAL1",
            Sharing.normalizeScan(QrStep.DISH_SERIAL, "https://starlink.com/dish/DISHSERIAL1?ref=box"),
        )
    }

    @Test
    fun `dish serial with trailing slash still resolves to the real segment`() {
        assertEquals(
            "DISHSERIAL1",
            Sharing.normalizeScan(QrStep.DISH_SERIAL, "https://starlink.com/dish/DISHSERIAL1/"),
        )
    }

    @Test
    fun `plain non-url serial is returned unchanged`() {
        assertEquals("MODEM555", Sharing.normalizeScan(QrStep.MODEM_SERIAL, "MODEM555"))
    }

    @Test
    fun `blank input stays blank rather than crashing`() {
        assertEquals("", Sharing.normalizeScan(QrStep.MODEM_SERIAL, "   "))
    }

    @Test
    fun `formatTime renders hours and minutes`() {
        // 2024-01-01 00:00:00 UTC — перевіряємо лише формат (HH:mm), а не конкретний часовий пояс.
        val formatted = Sharing.formatTime(0L)
        assertEquals(5, formatted.length)
        assertEquals(':', formatted[2])
    }

    @Test
    fun `dayOf converts a timestamp to the local calendar date deterministically`() {
        val day1 = Sharing.dayOf(1_700_000_000_000L)
        val day2 = Sharing.dayOf(1_700_000_000_000L)
        assertEquals(day1, day2)
    }

    private fun kit(uid: String, timestamp: Long, kit: String) =
        Reading(uid = uid, timestamp = timestamp, kitNumber = kit)

    @Test
    fun `kit list goes from newest to oldest, one per line`() {
        val readings = listOf(
            kit("a", timestamp = 1_000L, kit = "KIT0000001"),
            kit("b", timestamp = 3_000L, kit = "KIT0000003"),
            kit("c", timestamp = 2_000L, kit = "KIT0000002"),
        )
        assertEquals("KIT0000003\nKIT0000002\nKIT0000001", Sharing.buildKitList(readings))
    }

    @Test
    fun `kit list skips records without a kit number`() {
        val readings = listOf(
            kit("a", timestamp = 1_000L, kit = "KIT0000001"),
            kit("b", timestamp = 2_000L, kit = "   "),
            kit("c", timestamp = 3_000L, kit = ""),
        )
        assertEquals(listOf("KIT0000001"), Sharing.kitNumbers(readings))
    }

    @Test
    fun `kit read twice appears once and values are trimmed`() {
        val readings = listOf(
            kit("a", timestamp = 1_000L, kit = "KIT0000001"),
            kit("b", timestamp = 2_000L, kit = " KIT0000001 "),
        )
        assertEquals(listOf("KIT0000001"), Sharing.kitNumbers(readings))
    }

    @Test
    fun `kit list is empty when nothing selected has a kit`() {
        assertEquals("", Sharing.buildKitList(listOf(kit("a", timestamp = 1L, kit = ""))))
    }
}
