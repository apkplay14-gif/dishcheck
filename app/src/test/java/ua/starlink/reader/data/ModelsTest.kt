package ua.starlink.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingTest {

    @Test
    fun `empty reading has no title and is empty`() {
        val reading = Reading(uid = "u1", timestamp = 0L)
        assertTrue(reading.isEmpty)
        assertEquals(null, reading.titleOrNull)
    }

    @Test
    fun `title prefers kit number over everything else`() {
        val reading = Reading(
            uid = "u1",
            timestamp = 0L,
            kitNumber = "KIT111",
            dishSerial = "DISH222",
        )
        assertEquals("KIT111", reading.titleOrNull)
        assertFalse(reading.isEmpty)
    }

    @Test
    fun `title falls back through the priority chain when kit is blank`() {
        val reading = Reading(
            uid = "u1",
            timestamp = 0L,
            dish = DeviceData(id = "STARLINK-ID-1"),
        )
        // kitNumber і dishSerial порожні — наступний за пріоритетом це starlinkId.
        assertEquals("STARLINK-ID-1", reading.titleOrNull)
    }

    @Test
    fun `effective mac prefers manually entered value over router-reported one`() {
        val reading = Reading(
            uid = "u1",
            timestamp = 0L,
            modemMac = "AA:BB:CC:DD:EE:FF",
            router = DeviceData(macAddress = "11:22:33:44:55:66"),
        )
        assertEquals("AA:BB:CC:DD:EE:FF", reading.effectiveMac)
    }

    @Test
    fun `effective mac falls back to router mac when not entered manually`() {
        val reading = Reading(
            uid = "u1",
            timestamp = 0L,
            router = DeviceData(macAddress = "11:22:33:44:55:66"),
        )
        assertEquals("11:22:33:44:55:66", reading.effectiveMac)
    }

    @Test
    fun `with field only changes the targeted field`() {
        val reading = Reading(uid = "u1", timestamp = 0L, kitNumber = "OLD")
        val updated = reading.with(EditableField.NOTE, "hello")
        assertEquals("OLD", updated.kitNumber)
        assertEquals("hello", updated.note)
    }

    @Test
    fun `with step maps to the matching qr field`() {
        val reading = Reading(uid = "u1", timestamp = 0L)
        val updated = reading
            .with(QrStep.KIT, "KIT999")
            .with(QrStep.DISH_SERIAL, "DISH999")
            .with(QrStep.MODEM_SERIAL, "MODEM999")
        assertEquals("KIT999", updated.kitNumber)
        assertEquals("DISH999", updated.dishSerial)
        assertEquals("MODEM999", updated.modemSerial)
    }
}

class CaptureSettingsTest {

    @Test
    fun `all fields enabled by default`() {
        val settings = CaptureSettings()
        assertEquals(6, settings.enabledCount)
        assertTrue(settings.anyEnabled)
        assertEquals(listOf(QrStep.KIT, QrStep.DISH_SERIAL, QrStep.MODEM_SERIAL), settings.qrSteps)
        assertTrue(settings.needsDish)
        assertTrue(settings.needsRouter)
        assertTrue(settings.needsNetwork)
    }

    @Test
    fun `qr steps follow declaration order regardless of which ones are enabled`() {
        val settings = CaptureSettings(kitNumber = false, dishSerial = true, modemSerial = true)
        assertEquals(listOf(QrStep.DISH_SERIAL, QrStep.MODEM_SERIAL), settings.qrSteps)
    }

    @Test
    fun `no network calls needed when starlink and router ids and mac are all off`() {
        val settings = CaptureSettings(starlinkId = false, routerId = false, modemMac = false)
        assertFalse(settings.needsDish)
        assertFalse(settings.needsRouter)
        assertFalse(settings.needsNetwork)
    }

    @Test
    fun `router is still polled for mac even if router id itself is not requested`() {
        val settings = CaptureSettings(routerId = false, modemMac = true)
        assertTrue(settings.needsRouter)
    }

    @Test
    fun `nothing enabled means anyEnabled is false`() {
        val settings = CaptureSettings(
            kitNumber = false,
            dishSerial = false,
            modemSerial = false,
            starlinkId = false,
            routerId = false,
            modemMac = false,
        )
        assertEquals(0, settings.enabledCount)
        assertFalse(settings.anyEnabled)
    }
}
