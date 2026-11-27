// SPDX-License-Identifier: GPL-3.0-or-later
// Based on iPump-mini (https://github.com/sgitaize/iPump-mini, commit 29aa932),
// core/MiniMedKitKotlin/src/test/kotlin/de/sgitaize/minimedkit/HistoryTest.kt, by Simon Gutjahr.
// Modified for JugglucoNGmod: package; JUnit asserts instead of kotlin.test; the Nightscout treatment and pump alarm
// cases removed (those parts are not ported); trial history lines added; bolus parts, Therapy Context, Unparsed,
// special values and raw records added. All bytes are made up (layout per OpenMinimed), not from a pump.
package mod.pumpbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.pumpbt.PumpBtHistory
import tk.glucodata.mod.pumpbt.PumpBtHistoryLine
import tk.glucodata.mod.pumpbt.core.HistoryEvent
import tk.glucodata.mod.pumpbt.core.HistoryRecord
import tk.glucodata.mod.pumpbt.core.RawHistoryRecord
import tk.glucodata.mod.pumpbt.core.StatusChangedFlags
import tk.glucodata.mod.pumpbt.core.bytesOf
import tk.glucodata.mod.pumpbt.core.le32
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Builds a record: type (u16), sequence (u32), relative offset (u16), payload. */
private fun record(type: Int, seq: Long, offset: Int, payloadHex: String): ByteArray =
    bytesOf(type and 0xFF, type shr 8) + le32(seq) + bytesOf(offset and 0xFF, offset shr 8) + hex(payloadHex)

private fun parse(type: Int, payloadHex: String, seq: Long = 1, offset: Int = 0): HistoryEvent =
    HistoryRecord.parse(record(type, seq, offset, payloadHex)).event

class HistoryTest {
    @Test
    fun parsesEvents() {
        // Reference time 2026-10-15 08:30:00, reason periodic (0x3c)
        val ref = HistoryRecord.parse(record(0xF00E, 100, 0, "3c" + "ea07" + "0a" + "0f" + "08" + "1e" + "00"))
        val event = ref.event as HistoryEvent.ReferenceTime
        assertEquals(2026, event.time.year)
        assertEquals(8, event.time.hour)
        assertEquals(30, event.time.minute)

        // Bolus delivered: id 7, type normal, fast 2.5 U (f32 0xff000019 = 25e-1), ext 0, duration 0
        assertEquals(
            HistoryEvent.BolusDelivered(7, 0x33, 2.5, 0.0, 0),
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
        assertEquals(HistoryEvent.DeliveryStopped(0x02), parse(0xF00A, "02"))
        assertEquals(HistoryEvent.DeliveryRestarted(0x01), parse(0xF00B, "01"))
    }

    @Test
    fun aBodyThatDoesNotMatchIsUnparsedNotThrown() {
        // a byte too many: the record stays, with its header
        val extra = HistoryRecord.parse(record(0xF005, 110, 30, "2d0000"))
        assertEquals(110L, extra.sequence)
        assertEquals(0xF005, extra.eventType)
        assertEquals(30, extra.relativeOffset)
        val u = extra.event as HistoryEvent.Unparsed
        assertEquals(0xF005, u.type)
        assertEquals("1 bytes too many", u.reason)
        // a byte too few (bolus body of 12 instead of 13 bytes)
        assertTrue(parse(0x0069, "0700" + "33" + "190000ff" + "00000000" + "00") is HistoryEvent.Unparsed)
        // an invalid date in a reference time (month 13)
        assertEquals(HistoryEvent.Unparsed(0xF00E, "invalid date or length"), parse(0xF00E, "3cea070d0f081e00"))
        // an empty body where one is needed
        assertEquals(HistoryEvent.Unparsed(0xF00A, "body too short"), parse(0xF00A, ""))
    }

    @Test
    fun bolusProgrammedAndDetails() {
        // programmed: id 0x0102, square, fast 0, extended 3.0 U over 120 min
        assertEquals(
            HistoryEvent.BolusProgrammed(0x0102, 0x3C, 0.0, 3.0, 120),
            parse(0x005A, "0201" + "3c" + "00000000" + "1e0000ff" + "7800"),
        )
        // 0x0066 in 2 bytes, as the 740G sends it: flags 0x04 (activation) + activation "bolus wizard"
        assertEquals(HistoryEvent.BolusProgrammedDetails(0x04, 0x3C), parse(0x0066, "04" + "3c"))
        // all optional fields: delay, template, activation, reason flags without fields
        assertEquals(HistoryEvent.BolusProgrammedDetails(0x1F, 0x33), parse(0x0066, "1f" + "0500" + "02" + "33"))
        assertEquals(HistoryEvent.BolusProgrammedDetails(0x00, null), parse(0x0066, "00"))
        assertTrue(parse(0x0066, "04") is HistoryEvent.Unparsed)
    }

    @Test
    fun bolusDeliveredDetailsKeepAllFields() {
        // P2: flags 0x03 (activation + end reason), start offset 0, activation, end reason 0x33
        val p2Auto = HistoryRecord.parse(bytesOf(0x96, 0x00, 11, 0, 0, 0, 0, 0, 0x03, 0, 0, 0, 0, 0x5A, 0x33))
        assertEquals(HistoryEvent.BolusDeliveredDetails(0x03, 0, 0x5A, 0x33, null), p2Auto.event)
        // 6 bytes, as the 740G sends it: flags 0x02 (end reason), start offset 64 s, end reason undetermined
        assertEquals(HistoryEvent.BolusDeliveredDetails(0x02, 64, null, 0x0F, null), parse(0x0096, "02" + "40000000" + "0f"))
        // annunciation id
        assertEquals(HistoryEvent.BolusDeliveredDetails(0x06, 1, null, 0x3C, 0x0201), parse(0x0096, "06" + "01000000" + "3c" + "0102"))
    }

    @Test
    fun therapyContext() {
        // 5 bytes: flags 0x02 (basal running) + rate 0.5 U/h
        assertEquals(HistoryEvent.TherapyContext(0x02, 0.5, null, null, null), parse(0xF004, "02" + "050000ff"))
        // 6 bytes: flags 0x0A (basal + delivery stopped) + rate 0 + stop reason 0x02
        assertEquals(HistoryEvent.TherapyContext(0x0A, 0.0, 0x02, null, null), parse(0xF004, "0a" + "00000000" + "02"))
        // temp basal: flags 0x12, rate 0.5, type percent, value 50
        assertEquals(HistoryEvent.TherapyContext(0x12, 0.5, null, 0x3C, 50.0), parse(0xF004, "12" + "050000ff" + "3c" + "32000000"))
        assertTrue(parse(0xF004, "02" + "050000ff" + "00") is HistoryEvent.Unparsed)
    }

    @Test
    fun specialValuesAreNull() {
        // FLOAT NaN 0x007fffff as the fast dose, NRes 0x00800000 as the extended dose
        assertEquals(HistoryEvent.BolusProgrammed(1, 0x33, null, null, 0), parse(0x005A, "0100" + "33" + "ffff7f00" + "00008000" + "0000"))
        assertEquals(HistoryEvent.Meal(null), parse(0xF005, "ff07"))          // SFLOAT NaN
        assertEquals(HistoryEvent.AutoBasal(null), parse(0xF001, "01" + "feff7f00")) // +INF
        assertEquals(HistoryEvent.BgReading(null), parse(0xF007, "0000" + "0208")) // SFLOAT -INF
        assertEquals(HistoryEvent.BasalRateChanged(null, 0.05), parse(0x0099, "00" + "01008000" + "050000fe"))
        assertEquals(HistoryEvent.TherapyContext(0x02, null, null, null, null), parse(0xF004, "02" + "02008000"))
    }

    @Test
    fun rawRecordsKeepTheirBytes() {
        val plain = record(0x0069, 0x01020304, 0x0E10, "0700" + "33" + "190000ff" + "00000000" + "0000")
        val raw = RawHistoryRecord.fromPlain(plain)!!
        assertEquals(0x01020304L, raw.sequence)
        assertEquals(0x0069, raw.type)
        assertEquals(3600, raw.offsetSec)
        assertEquals(13, raw.body.size)
        assertArrayEquals(plain, raw.toPlain())
        assertEquals(HistoryEvent.BolusDelivered(7, 0x33, 2.5, 0.0, 0), raw.parse().event)

        // equal by content, not by array identity
        val same = RawHistoryRecord.fromPlain(plain.copyOf())!!
        assertEquals(raw, same)
        assertEquals(raw.hashCode(), same.hashCode())
        assertNotEquals(raw, RawHistoryRecord.fromPlain(record(0x0069, 0x01020304, 0x0E10, "0800" + "33" + "190000ff" + "00000000" + "0000")))
        assertTrue("toString holds no body bytes", !raw.toString().contains("190000ff"))

        // a header-only record is a record (empty body); shorter is not
        assertEquals(0, RawHistoryRecord.fromPlain(record(0xF00F, 5, 0, ""))!!.body.size)
        assertNull(RawHistoryRecord.fromPlain(bytesOf(1, 2, 3, 4, 5, 6, 7)))
        // parse never throws for a record from 8 bytes on
        assertTrue(RawHistoryRecord.fromPlain(record(0x0069, 9, 0, "07"))!!.parse().event is HistoryEvent.Unparsed)
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
            HistoryRecord.parse(record(0xF005, 99, 30, "2d00")),             // offset larger than the next reference's → time unknown
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
    fun trialLinesDateRecordsBackFromTheNextReference() {
        val records = listOf(
            HistoryRecord.parse(record(0xF005, 99, 3000, "2d00")),              // 21:00 - 3600 s + 3000 s = 20:50:00
            HistoryRecord.parse(record(0xF00E, 100, 3600, "3cea070a0f150000")), // 21:00:00, an hour after the previous one
        )
        val (lines, _) = PumpBtHistory.lines(records, null, ZoneOffset.ofHours(3))
        assertEquals(Instant.parse("2026-10-15T17:50:00Z").toEpochMilli(), lines[0].timeMs)
        assertEquals(Instant.parse("2026-10-15T18:00:00Z").toEpochMilli(), lines[1].timeMs)
    }

    @Test
    fun trialLinesDescribeTheNewRecords() {
        assertEquals("Bolus requested" to "2.5 U", PumpBtHistory.describe(parse(0x005A, "0700" + "33" + "190000ff" + "00000000" + "0000")))
        assertEquals(
            "Bolus delivered" to "0 U + 3 U over 120 min, square",
            PumpBtHistory.describe(HistoryEvent.BolusDelivered(1, 0x3C, 0.0, 3.0, 120)),
        )
        assertEquals("Bolus delivered" to "? U", PumpBtHistory.describe(HistoryEvent.BolusDelivered(1, 0x33, null, 0.0, 0)))
        assertEquals("Not readable 0x0069" to "body too short", PumpBtHistory.describe(HistoryEvent.Unparsed(0x0069, "body too short")))
        assertEquals(
            "Therapy context" to "flags 0x0a, basal 0 U/h, stopped 0x02",
            PumpBtHistory.describe(HistoryEvent.TherapyContext(0x0A, 0.0, 0x02, null, null)),
        )
        assertEquals("Reference time" to LocalDateTime.of(2026, 10, 15, 8, 30).toString(), PumpBtHistory.describe(HistoryEvent.ReferenceTime(LocalDateTime.of(2026, 10, 15, 8, 30))))
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
