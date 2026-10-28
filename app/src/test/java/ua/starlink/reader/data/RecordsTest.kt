package ua.starlink.reader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ua.starlink.reader.util.CsvExport

class RecordsTest {

    private fun reading(
        uid: String,
        time: Long = 1L,
        kit: String = "",
        starlinkId: String = "",
        note: String = "",
        mac: String = "",
    ) = Reading(
        uid = uid,
        timestamp = time,
        kitNumber = kit,
        modemMac = mac,
        note = note,
        dish = if (starlinkId.isEmpty()) null else DeviceData(id = starlinkId),
    )

    // ---------------------------------------------------------------- повтори

    @Test
    fun `duplicate is found by starlink id first`() {
        val old = reading("a", time = 1, kit = "KIT1", starlinkId = "ut1")
        val fresh = reading("b", time = 2, kit = "KIT2", starlinkId = "ut1")
        assertEquals("a", findDuplicate(fresh, listOf(old, fresh))?.uid)
        assertEquals("ut1", duplicateKey(fresh))
    }

    @Test
    fun `without starlink id the kit number decides`() {
        val old = reading("a", kit = "KITABC1234")
        val fresh = reading("b", kit = " kitabc1234 ")
        assertEquals("a", findDuplicate(fresh, listOf(old, fresh))?.uid)
        assertEquals("kitabc1234", duplicateKey(fresh))
    }

    @Test
    fun `same kit but different starlink id is not a repeat`() {
        // Starlink ID надійніший: коробку могли переплутати, тарілку — ні.
        val old = reading("a", kit = "KIT1", starlinkId = "ut1")
        val fresh = reading("b", kit = "KIT1", starlinkId = "ut2")
        assertNull(findDuplicate(fresh, listOf(old, fresh)))
    }

    @Test
    fun `newest of several matches is returned`() {
        val older = reading("a", time = 1, kit = "KIT1")
        val newer = reading("b", time = 5, kit = "KIT1")
        val fresh = reading("c", time = 9, kit = "KIT1")
        assertEquals("b", findDuplicate(fresh, listOf(older, newer, fresh))?.uid)
    }

    @Test
    fun `empty reading never matches`() {
        val fresh = reading("b")
        assertNull(findDuplicate(fresh, listOf(reading("a"), fresh)))
    }

    // ---------------------------------------------------------------- пошук

    @Test
    fun `search matches any identifier or note, case-insensitive`() {
        val r = reading("a", kit = "KITP00123", starlinkId = "ut01-abc", note = "Склад №2")
        assertTrue(r.matches("p001"))
        assertTrue(r.matches("UT01"))
        assertTrue(r.matches("склад"))
        assertFalse(r.matches("магазин"))
        assertTrue(r.matches("   "))
    }

    @Test
    fun `mac is found with or without colons`() {
        val r = reading("a", mac = "00:00:5E:00:53:05")
        assertTrue(r.matches("5e:00:53"))
        assertTrue(r.matches("5E0053"))
    }

    // ---------------------------------------------------------------- CSV

    private val headers = listOf("Дата", "Час", "KIT", "A", "B", "C", "D", "MAC", "Примітка")

    @Test
    fun `csv has bom, header, oldest first and semicolons`() {
        val csv = CsvExport.build(
            listOf(reading("new", time = 2, kit = "KIT2"), reading("old", time = 1, kit = "KIT1")),
            headers,
            formatDate = { "d$it" },
            formatTime = { "t$it" },
        )
        assertTrue(csv.startsWith("﻿"))
        val lines = csv.removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals(headers.joinToString(";"), lines[0])
        assertTrue(lines[1].startsWith("d1;t1;KIT1;"))
        assertTrue(lines[2].startsWith("d2;t2;KIT2;"))
    }

    @Test
    fun `csv quotes values with separators, quotes and line breaks`() {
        assertEquals("\"a;b\"", CsvExport.escape("a;b"))
        assertEquals("\"say \"\"hi\"\"\"", CsvExport.escape("say \"hi\""))
        assertEquals("\"line1\nline2\"", CsvExport.escape("line1\nline2"))
        assertEquals("plain", CsvExport.escape("plain"))
    }

    @Test
    fun `csv neutralises values that excel would run as formulas`() {
        assertEquals("'=1+1", CsvExport.escape("=1+1"))
        assertEquals("'+380", CsvExport.escape("+380"))
    }
}
