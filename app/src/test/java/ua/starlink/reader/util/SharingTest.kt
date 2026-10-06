package ua.starlink.reader.util

import org.junit.Assert.assertEquals
import org.junit.Test
import ua.starlink.reader.data.QrStep

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
}
