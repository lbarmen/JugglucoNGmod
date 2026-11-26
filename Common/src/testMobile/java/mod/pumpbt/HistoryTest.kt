// SPDX-License-Identifier: GPL-3.0-or-later
// Based on iPump-mini (https://github.com/sgitaize/iPump-mini, commit 29aa932),
// core/MiniMedKitKotlin/src/test/kotlin/de/sgitaize/minimedkit/HistoryTest.kt, by Simon Gutjahr.
// Modified for JugglucoNGmod: package; JUnit asserts instead of kotlin.test; the Nightscout treatment and pump alarm
// cases removed (those parts are not ported); trial history lines added.
package mod.pumpbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import tk.glucodata.mod.pumpbt.PumpBtHistory
import tk.glucodata.mod.pumpbt.PumpBtHistoryLine
import tk.glucodata.mod.pumpbt.core.HistoryEvent
import tk.glucodata.mod.pumpbt.core.HistoryRecord
import tk.glucodata.mod.pumpbt.core.HistoryTimeline
import tk.glucodata.mod.pumpbt.core.ParseError
import tk.glucodata.mod.pumpbt.core.StatusChangedFlags
import tk.glucodata.mod.pumpbt.core.bytesOf
import tk.glucodata.mod.pumpbt.core.le32
import java.time.Instant
import java.time.ZoneOffset

/** Builds a record: type (u16), sequence (u32), relative offset (u16), payload. */
private fun record(type: Int, seq: Long, offset: Int, payloadHex: String): ByteArray =
    bytesOf(type and 0xFF, type shr 8) + le32(seq) + bytesOf(offset and 0xFF, offset shr 8) + hex(payloadHex)

class HistoryTest {
    @Test
    fun parsesEvents() {
        // Reference time 2026-10-15 08:30:00, reason periodic (0x3c)
        val ref = HistoryRecord.parse(record(0xF00E, 100, 0, "3c" + "ea07" + "0a" + "0f" + "08" + "1e" + "00"))
        val event = ref.event as HistoryEvent.ReferenceTime
        assertEquals(2026, event.time.year)
        assertEquals(8, event.time.hour)
        assertEquals(30, event.time.minute)

        // Bolus delivered: id 7, fast 2.5 U (f32 0xff000019 = 25e-1), ext 0, duration 0
        assertEquals(
            HistoryEvent.BolusDelivered(7, 2.5, 0.0, 0),
            HistoryRecord.parse(record(0x0069, 101, 60, "0700" + "33" + "190000ff" + "00000000" + "0000")).event,
        )
        // Auto basal 0.05 U (f32: m=5, e=-2 → 0xfe000005)
        assertEquals(HistoryEvent.AutoBasal(0.05), HistoryRecord.parse(record(0xF001, 102, 120, "01" + "050000fe")).event)
        // Meal 45 g (SFLOAT 0x002d)
        assertEquals(HistoryEvent.Meal(45.0), HistoryRecord.parse(record(0xF005, 103, 130, "2d00")).event)
        // BG 120 mg/dL = 0.0012 kg/L → SFLOAT m=12, e=-4 → 0xC00C
        assertEquals(HistoryEvent.BgReading(120.0), HistoryRecord.parse(record(0xF007, 104, 140, "0000" + "0cc0")).event)
        assertEquals(HistoryEvent.Calibration(120.0, true), HistoryRecord.parse(record(0xF008, 105, 150, "0000" + "0cc0")).event)
        // SG 142 and special value 0x0301 (sensor starting)
        assertEquals(HistoryEvent.SensorGlucose(142), HistoryRecord.parse(record(0xF00C, 106, 160, "0000" + "8e00" + "0000" + "0000")).event)
        assertEquals(HistoryEvent.SensorGlucose(null), HistoryRecord.parse(record(0xF00C, 107, 170, "0000" + "0103" + "0000" + "0000")).event)
        // Basal rate changed with context byte
        assertEquals(
            HistoryEvent.BasalRateChanged(0.0, 0.05),
            HistoryRecord.parse(record(0x0099, 108, 180, "01" + "00000000" + "050000fe" + "55")).event,
        )
        assertEquals(HistoryEvent.Other(0x1234), HistoryRecord.parse(record(0x1234, 109, 0, "aabb")).event)
        try {
            HistoryRecord.parse(record(0xF005, 110, 0, "2d0000"))
            fail("trailing bytes must fail")
        } catch (_: ParseError) {
        }
    }

    @Test
    fun timelineUsesLatestReference() {
        val records = listOf(
            HistoryRecord.parse(record(0xF001, 99, 30, "01050000fe")),      // before any reference → dropped
            HistoryRecord.parse(record(0xF00E, 100, 0, "3cea070a0f081e00")), // 08:30:00
            HistoryRecord.parse(record(0xF005, 101, 300, "2d00")),           // 08:35:00
            HistoryRecord.parse(record(0xF00E, 102, 0, "3cea070a0f0a0000")), // 10:00:00
            HistoryRecord.parse(record(0xF001, 103, 90, "01050000fe")),      // 10:01:30
        )
        val (events, ref) = HistoryTimeline.resolve(records.shuffled(), null, ZoneOffset.UTC)
        assertEquals(listOf(101L, 103L), events.map { it.sequence })
        assertEquals(Instant.parse("2026-10-15T08:35:00Z"), events[0].date)
        assertEquals(Instant.parse("2026-10-15T10:01:30Z"), events[1].date)
        assertEquals(102L, ref?.sequence)

        // Continuing with a stored reference
        val (next, _) = HistoryTimeline.resolve(listOf(HistoryRecord.parse(record(0xF005, 104, 600, "1400"))), ref, ZoneOffset.UTC)
        assertEquals(Instant.parse("2026-10-15T10:10:00Z"), next[0].date)
    }

    @Test
    fun autoCorrectionActivationType() {
        // P2: flags 0x03 (activation + end reason), start offset 0, activation, end reason 0x33
        val p2Auto = HistoryRecord.parse(bytesOf(0x96, 0x00, 11, 0, 0, 0, 0, 0, 0x03, 0, 0, 0, 0, 0x5A, 0x33))
        assertEquals(HistoryEvent.BolusDeliveredDetails(0x5A), p2Auto.event)
    }

    @Test
    fun parsesAnnunciationWithAuxData() {
        // flags 0x01, id 0x0102, type 0xF069 (low reservoir), status pending, timestamp, aux data
        val raw = bytesOf(0x10, 0xF0, 5, 0, 0, 0, 10, 0, 0x01, 0x02, 0x01, 0x69, 0xF0, 0x33, 1, 2, 3, 4, 0xAA, 0xBB)
        assertEquals(HistoryEvent.Annunciation(0x0102, 0x0069, 0x33), HistoryRecord.parse(raw).event)
        assertTrue(StatusChangedFlags.ANNUNCIATION in StatusChangedFlags.parse(bytesOf(0x08, 0x00))!!)
    }

    @Test
    fun trialLinesKeepRecordsBeforeAReference() {
        val records = listOf(
            HistoryRecord.parse(record(0xF005, 99, 30, "2d00")),             // no reference yet → time unknown
            HistoryRecord.parse(record(0xF00E, 100, 0, "3cea070a0f081e00")), // 08:30:00
            HistoryRecord.parse(record(0xF001, 101, 90, "01050000fe")),      // 08:31:30
        )
        val (lines, ref) = PumpBtHistory.lines(records.reversed(), null, ZoneOffset.UTC)
        assertEquals(listOf(99L, 100L, 101L), lines.map { it.sequence })
        assertNull(lines[0].timeMs)
        assertEquals("Meal", lines[0].kind)
        assertEquals("45 g", lines[0].value)
        assertEquals(Instant.parse("2026-10-15T08:31:30Z").toEpochMilli(), lines[2].timeMs)
        assertEquals("0.05 U", lines[2].value)
        assertEquals(100L, ref?.first)

        // a stored reference gives times to the next read
        val (next, _) = PumpBtHistory.lines(listOf(HistoryRecord.parse(record(0xF005, 102, 600, "1400"))), ref, ZoneOffset.UTC)
        assertEquals(Instant.parse("2026-10-15T08:40:00Z").toEpochMilli(), next[0].timeMs)
    }

    @Test
    fun trialLinesMergeNewestFirstAtMost20() {
        val old = (1L..15L).map { PumpBtHistoryLine(it, null, "Event", "") }
        val new = (10L..30L).map { PumpBtHistoryLine(it, null, "Event", "new") }
        val merged = PumpBtHistory.merge(old, new)
        assertEquals(20, merged.size)
        assertEquals(30L, merged.first().sequence)
        assertEquals(11L, merged.last().sequence)
        assertEquals("new", merged.first { it.sequence == 12L }.value)
    }
}
