package mod.pumpbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.pumpbt.core.BasalChangeEvent
import tk.glucodata.mod.pumpbt.core.BolusEvent
import tk.glucodata.mod.pumpbt.core.DeliveryResumeEvent
import tk.glucodata.mod.pumpbt.core.DeliveryStopEvent
import tk.glucodata.mod.pumpbt.core.HistoryReferenceLocal
import tk.glucodata.mod.pumpbt.core.MealEvent
import tk.glucodata.mod.pumpbt.core.PumpEvents
import tk.glucodata.mod.pumpbt.core.PumpEventsResult
import tk.glucodata.mod.pumpbt.core.PumpIdentity
import tk.glucodata.mod.pumpbt.core.RawHistoryRecord
import tk.glucodata.mod.pumpbt.core.bytesOf
import tk.glucodata.mod.pumpbt.core.le32
import java.time.LocalDateTime

/**
 * PumpEvents: bolus parts, dating and the "held" rule. All bytes are made up (record layout per OpenMinimed and the
 * 740G trial of 27.11, without its values).
 */
class PumpEventsTest {

    private fun raw(type: Int, seq: Long, offset: Int, payloadHex: String): RawHistoryRecord =
        RawHistoryRecord.fromPlain(bytesOf(type and 0xFF, type shr 8) + le32(seq) + bytesOf(offset and 0xFF, offset shr 8) + hex(payloadHex))!!

    /** Reference time (0xF00E) on 2026-10-15 at [hour]:[minute]:00, reason periodic (0x3C) unless given. */
    private fun ref(seq: Long, offset: Int, hour: Int, minute: Int = 0, reason: Int = 0x3C): RawHistoryRecord =
        raw(0xF00E, seq, offset, "%02x".format(reason) + "ea070a0f" + "%02x%02x00".format(hour, minute))

    private fun at(hour: Int, minute: Int, second: Int = 0): LocalDateTime = LocalDateTime.of(2026, 10, 15, hour, minute, second)

    /** FLOAT (IEEE-11073) of [tenths] / 10 units, little-endian hex. */
    private fun units(tenths: Int): String = "%02x%02x%02xff".format(tenths and 0xFF, (tenths shr 8) and 0xFF, (tenths shr 16) and 0xFF)

    private fun bolusBody(id: Int, tenthsFast: Int, tenthsExt: Int = 0, minutes: Int = 0, type: Int = 0x33): String =
        "%02x%02x".format(id and 0xFF, id shr 8) + "%02x".format(type) + units(tenthsFast) + units(tenthsExt) +
            "%02x%02x".format(minutes and 0xFF, minutes shr 8)

    private fun programmed(seq: Long, offset: Int, id: Int, tenths: Int) = raw(0x005A, seq, offset, bolusBody(id, tenths))
    private fun delivered(seq: Long, offset: Int, id: Int, tenths: Int) = raw(0x0069, seq, offset, bolusBody(id, tenths))

    /** 0x0066 as the 740G sends it: flags 0x04 + activation. */
    private fun programmedDetails(seq: Long, offset: Int, activation: Int = 0x3C) = raw(0x0066, seq, offset, "04%02x".format(activation))

    /** 0x0096 as the 740G sends it: flags 0x02 + start offset + end reason. */
    private fun deliveredDetails(seq: Long, offset: Int, endReason: Int = 0x0F) = raw(0x0096, seq, offset, "02" + "00000000" + "%02x".format(endReason))

    private fun boluses(r: PumpEventsResult): List<BolusEvent> = r.events.filterIsInstance<BolusEvent>()

    @Test
    fun programmedAndDeliveredInDifferentBuildsGiveOneBolus() {
        val first = listOf(
            ref(100, 3600, 10),
            programmed(101, 60, id = 7, tenths = 25),
            programmedDetails(102, 60),
        )
        val running = PumpEvents.build(first, null, contiguousTo = 102)
        val b0 = boluses(running).single()
        assertFalse(b0.completed)
        assertEquals(101L, b0.sequence)
        assertEquals(at(10, 1), b0.at)
        assertEquals(2.5, b0.programmedUnits!!, 0.0)
        assertNull(b0.deliveredUnits)
        assertNull(b0.deliveredSequence)
        assertEquals(0x3C, b0.activationType)
        assertEquals(0x33, b0.bolusType)

        // the next cycle: the memory is the union, "delivered" came 140 s later
        val memory = first + listOf(delivered(103, 200, id = 7, tenths = 25), deliveredDetails(104, 200))
        val done = PumpEvents.build(memory, null, contiguousTo = 104)
        val b = boluses(done).single()
        assertTrue(b.completed)
        assertEquals(101L, b.sequence)
        assertEquals(at(10, 1), b.at)
        assertEquals(101L, b.requestSequence)
        assertEquals(103L, b.deliveredSequence)
        assertEquals(at(10, 3, 20), b.deliveredAt)
        assertEquals(2.5, b.deliveredUnits!!, 0.0)
        assertEquals(0x0F, b.endReason)
        assertFalse(b.timeFromDelivery)
        assertEquals(0, done.heldBoluses)
        assertEquals(HistoryReferenceLocal(100, at(10, 0)), done.lastReference)
    }

    @Test
    fun stoppedAndZeroBoluses() {
        // format per OpenMinimed, not seen on a 740G: end reason 0x3C (cancelled), less delivered than programmed
        val stopped = PumpEvents.build(
            listOf(ref(10, 3600, 9), programmed(11, 10, 1, 40), programmedDetails(12, 10), delivered(13, 70, 1, 15), deliveredDetails(14, 70, 0x3C)),
            null, 14,
        )
        val s = boluses(stopped).single()
        assertTrue(s.completed)
        assertEquals(4.0, s.programmedUnits!!, 0.0)
        assertEquals(1.5, s.deliveredUnits!!, 0.0)
        assertEquals(0x3C, s.endReason)

        // format per OpenMinimed, not seen on a 740G: nothing delivered
        val zero = PumpEvents.build(listOf(ref(10, 3600, 9), programmed(11, 10, 2, 20), delivered(12, 15, 2, 0)), null, 12)
        val z = boluses(zero).single()
        assertTrue(z.completed)
        assertEquals(2.0, z.programmedUnits!!, 0.0)
        assertEquals(0.0, z.deliveredUnits!!, 0.0)
        assertNull("no 0x0096 next to it", z.endReason)
    }

    @Test
    fun recordsBeforeTheFirstReferenceAreDatedBack() {
        val r = PumpEvents.build(
            listOf(
                raw(0xF005, 98, 2990, "2d00"),              // 21:00 - 3600 s + 2990 s = 20:49:50
                programmed(99, 3000, 3, 50),                // 20:50:00
                ref(100, 3600, 21),
                raw(0xF005, 101, 120, "1400"),              // 21:02:00
            ),
            null, 101,
        )
        assertEquals(listOf(98L, 99L, 101L), r.events.map { it.sequence })
        assertEquals(at(20, 49, 50), r.events[0].at)
        assertEquals(45.0, (r.events[0] as MealEvent).grams, 0.0)
        assertEquals(at(20, 50), r.events[1].at)
        assertEquals(at(21, 2), r.events[2].at)
        assertTrue(r.undated.isEmpty())
    }

    @Test
    fun aReferenceOfAnotherReasonCountsTheSame() {
        // reason 0x33 "time set"
        val r = PumpEvents.build(listOf(raw(0xF005, 5, 600, "2d00"), ref(6, 1200, 12, reason = 0x33), raw(0xF00A, 7, 60, "02")), null, 7)
        assertEquals(at(11, 50), r.events[0].at)
        assertEquals(at(12, 1), r.events[1].at)
        assertEquals(DeliveryStopEvent(7, at(12, 1), 0x02), r.events[1])
    }

    @Test
    fun storedReferenceAndUndated() {
        val records = listOf(raw(0xF00A, 201, 60, "02"), raw(0xF00B, 202, 900, "01"))
        val stored = HistoryReferenceLocal(200, at(8, 0))
        val r = PumpEvents.build(records, stored, 202)
        assertEquals(listOf(DeliveryStopEvent(201, at(8, 1), 0x02), DeliveryResumeEvent(202, at(8, 15), 0x01)), r.events)
        assertEquals(stored, r.lastReference)

        // no reference in memory and none stored: no time, nothing made up
        val none = PumpEvents.build(records, null, 202)
        assertTrue(none.events.isEmpty())
        assertEquals(listOf(201L, 202L), none.undated)
        assertNull(none.lastReference)

        // a stored reference newer than the record does not date it; an offset larger than the next reference's neither
        val later = PumpEvents.build(listOf(raw(0xF005, 50, 4000, "2d00"), ref(51, 3600, 9)), HistoryReferenceLocal(60, at(10, 0)), 51)
        assertEquals(listOf(50L), later.undated)
        assertEquals(HistoryReferenceLocal(60, at(10, 0)), later.lastReference)
    }

    @Test
    fun anExtraByteIsUnparsedAndTheRecordIsNotLost() {
        val r = PumpEvents.build(
            listOf(ref(1, 3600, 7), raw(0x005A, 2, 30, bolusBody(4, 10) + "00"), delivered(3, 90, 4, 10)),
            null, 1,
        )
        assertEquals(1, r.unparsed.size)
        assertEquals(2L, r.unparsed[0].sequence)
        assertEquals(0x005A, r.unparsed[0].type)
        assertEquals("1 bytes too many", r.unparsed[0].reason)
        // its "delivered" has no partner and the memory is not complete up to it: held, not written
        assertTrue(r.events.isEmpty())
        assertEquals(1, r.heldBoluses)
    }

    @Test
    fun deliveredAfterAGapIsHeldUntilTheGapIsSettled() {
        // 101..104 missing ("programmed" may be among them)
        val records = listOf(ref(100, 3600, 14), delivered(105, 300, 9, 30), deliveredDetails(106, 300))
        val held = PumpEvents.build(records, null, contiguousTo = 100)
        assertTrue(held.events.isEmpty())
        assertEquals(1, held.heldBoluses)
        assertEquals(1, PumpEvents.build(records, null, contiguousTo = null).heldBoluses)

        // the gap is marked lost (the memory number moved to 104): out with the time of "delivered"
        val out = PumpEvents.build(records, null, contiguousTo = 104)
        val b = boluses(out).single()
        assertEquals(0, out.heldBoluses)
        assertTrue(b.timeFromDelivery)
        assertTrue(b.completed)
        assertEquals(105L, b.sequence)
        assertEquals(at(14, 5), b.at)
        assertEquals(at(14, 5), b.deliveredAt)
        assertNull(b.requestSequence)
        assertNull(b.programmedUnits)
        assertEquals(3.0, b.deliveredUnits!!, 0.0)
        assertEquals(0x0F, b.endReason)
    }

    @Test
    fun twoDeliveredWithOneIdTakeDifferentProgrammed() {
        // one after the other
        val sequential = PumpEvents.build(
            listOf(ref(1, 3600, 6), programmed(2, 10, 7, 10), delivered(3, 80, 7, 10), programmed(4, 600, 7, 20), delivered(5, 700, 7, 20)),
            null, 5,
        )
        val s = boluses(sequential)
        assertEquals(2, s.size)
        assertEquals(listOf(2L to 3L, 4L to 5L), s.map { it.requestSequence to it.deliveredSequence })
        assertEquals(listOf(1.0, 2.0), s.map { it.deliveredUnits })

        // interleaved: each "programmed" is taken once, each "delivered" gets one
        val interleaved = PumpEvents.build(
            listOf(ref(1, 3600, 6), programmed(2, 10, 7, 10), programmed(3, 20, 7, 20), delivered(4, 80, 7, 10), delivered(5, 90, 7, 20)),
            null, 5,
        )
        val i = boluses(interleaved)
        assertEquals(2, i.size)
        assertTrue(i.all { it.completed })
        assertEquals(setOf(2L, 3L), i.map { it.requestSequence }.toSet())
        assertEquals(setOf(4L, 5L), i.map { it.deliveredSequence }.toSet())
        assertEquals(0, interleaved.heldBoluses)
    }

    @Test
    fun bolusesWithOneIdFarApartAreNotJoined() {
        val r = PumpEvents.build(
            listOf(ref(99, 3600, 6), programmed(100, 10, 7, 10), ref(2150, 3600, 9), delivered(2200, 60, 7, 30)),
            null, 2200,
        )
        val b = boluses(r)
        assertEquals(2, b.size)
        assertEquals(100L, b[0].requestSequence)
        assertFalse(b[0].completed)
        assertNull(b[0].deliveredSequence)
        assertEquals(2200L, b[1].sequence)
        assertTrue(b[1].timeFromDelivery)
        assertNull(b[1].requestSequence)
    }

    @Test
    fun aRepeatedRecordCountsOnce() {
        val p = programmed(2, 10, 7, 10)
        val r = PumpEvents.build(listOf(ref(1, 3600, 6), p, p, programmed(2, 10, 7, 10), delivered(3, 80, 7, 10), delivered(3, 80, 7, 10)), null, 3)
        val b = boluses(r).single()
        assertTrue(b.completed)
        assertEquals(0, r.heldBoluses)
    }

    @Test
    fun specialValuesAreNullOrUnparsed() {
        val r = PumpEvents.build(
            listOf(
                ref(1, 3600, 6),
                raw(0x005A, 2, 10, "0700" + "33" + "ffff7f00" + "00000000" + "0000"), // fast dose NaN
                raw(0xF005, 3, 11, "ff07"),                                         // grams NaN
                raw(0x0099, 4, 12, "00" + "00008000" + "050000ff"),                 // old rate NRes
            ),
            null, 4,
        )
        val b = boluses(r).single()
        assertNull(b.programmedUnits)
        assertEquals(0.0, b.programmedExtendedUnits!!, 0.0)
        assertEquals(listOf(3L), r.unparsed.map { it.sequence })
        assertTrue(r.events.none { it is MealEvent })
        assertEquals(BasalChangeEvent(4, at(6, 0, 12), null, 0.5), r.events.single { it is BasalChangeEvent })
    }

    @Test
    fun unknownAndKnownTypes() {
        val r = PumpEvents.build(
            listOf(
                ref(1, 3600, 6),
                raw(0x1234, 2, 0, "aabb"),     // unknown
                raw(0xF00F, 3, 0, "01000200"), // annunciation cleared: skipped on purpose
                raw(0xF004, 4, 0, "02050000ff"),
                raw(0xF001, 5, 0, "01050000fe"),
                raw(0xF077, 6, 0, ""),         // unknown
            ),
            null, 6,
        )
        assertEquals(setOf(0x1234, 0xF077), r.unknownTypes)
        assertTrue(r.events.isEmpty())
        assertTrue(r.unparsed.isEmpty())
        assertTrue(0xF004 in PumpEvents.KNOWN_TYPES && 0xF010 in PumpEvents.KNOWN_TYPES && 0x0066 in PumpEvents.KNOWN_TYPES)
    }

    @Test
    fun eventsByTimeThenSequence() {
        val r = PumpEvents.build(
            listOf(ref(1, 3600, 6), raw(0xF00B, 2, 300, "01"), raw(0xF00A, 3, 300, "02"), raw(0xF005, 4, 100, "1400")),
            null, 4,
        )
        assertEquals(listOf(4L, 2L, 3L), r.events.map { it.sequence })
    }

    @Test
    fun pumpIdentityHashIgnoresNulAndBlanksAtTheEnds() {
        val h = PumpIdentity.hash("NG0000000X")
        assertEquals(64, h.length)
        assertTrue(h.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(h, PumpIdentity.hash(" NG0000000X\u0000\u0000"))
        assertNotEquals(h, PumpIdentity.hash("NG0000000Y"))
    }
}
