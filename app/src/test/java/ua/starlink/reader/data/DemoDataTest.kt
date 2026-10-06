package ua.starlink.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.starlink.reader.util.Sharing

class DemoDataTest {

    @Test
    fun `scanned demo codes survive normalisation unchanged`() {
        // Демо-код іде тим самим шляхом, що й справжній, — через normalizeScan.
        QrStep.entries.forEach { step ->
            val value = DemoData.scanValue(step, 3)
            assertEquals(value, Sharing.normalizeScan(step, value))
        }
    }

    @Test
    fun `every demo identifier is visibly marked as demo`() {
        val values = QrStep.entries.map { DemoData.scanValue(it, 7) } +
            DemoData.starlinkId(7) + DemoData.routerId(7)
        values.forEach { assertTrue(it, it.contains("demo", ignoreCase = true)) }
    }

    @Test
    fun `demo mac comes from the documentation range`() {
        val mac = DemoData.mac(3)
        assertEquals("00:00:5E:00:53:03", mac)
        assertTrue(mac.startsWith("00:00:5E:00:53:"))
    }

    @Test
    fun `identifiers keep the length of the real ones`() {
        // ut + 8 + 8 + 8 символів через дефіси; Router- і 24 символи.
        assertEquals(28, DemoData.starlinkId(1).length)
        assertEquals(31, DemoData.routerId(1).length)
    }

    @Test
    fun `different demo kits never look like a repeat of each other`() {
        assertNotEquals(DemoData.starlinkId(1), DemoData.starlinkId(2))
        assertNotEquals(DemoData.scanValue(QrStep.KIT, 1), DemoData.scanValue(QrStep.KIT, 2))
    }

    @Test
    fun `router answers with a mac only when it was asked for`() {
        assertEquals(DemoData.mac(4), DemoData.router(4, withMac = true).macAddress)
        assertNull(DemoData.router(4, withMac = false).macAddress)
        assertNull(DemoData.dish(4).macAddress)
    }

    @Test
    fun `sample reading is filled in completely`() {
        val reading = DemoData.reading(uid = "u1", seq = 2, timestamp = 1_000L, note = "roof")

        assertEquals(DemoData.scanValue(QrStep.KIT, 2), reading.kitNumber)
        assertEquals(DemoData.scanValue(QrStep.DISH_SERIAL, 2), reading.dishSerial)
        assertEquals(DemoData.scanValue(QrStep.MODEM_SERIAL, 2), reading.modemSerial)
        assertEquals(DemoData.starlinkId(2), reading.starlinkId)
        assertEquals(DemoData.routerId(2), reading.routerId)
        assertEquals(DemoData.mac(2), reading.effectiveMac)
        assertEquals("roof", reading.note)
    }

    @Test
    fun `sample reading respects the capture settings`() {
        val settings = CaptureSettings(kitNumber = false, starlinkId = false, modemMac = false)
        val reading = DemoData.reading(
            uid = "u2", seq = 5, timestamp = 1_000L, note = "", settings = settings,
        )

        assertEquals("", reading.kitNumber)
        assertNull(reading.dish)
        assertEquals(DemoData.routerId(5), reading.routerId)
        assertEquals("", reading.effectiveMac)
    }
}
