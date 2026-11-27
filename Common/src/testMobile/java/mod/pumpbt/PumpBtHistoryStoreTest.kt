package mod.pumpbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tk.glucodata.mod.carelink.ClEvent
import tk.glucodata.mod.carelink.ClKind
import tk.glucodata.mod.pumpbt.BackfillState
import tk.glucodata.mod.pumpbt.PumpBtBridge
import tk.glucodata.mod.pumpbt.PumpBtHistoryStore
import tk.glucodata.mod.pumpbt.PumpHistoryCounters
import tk.glucodata.mod.pumpbt.PumpHistoryMemory
import tk.glucodata.mod.pumpbt.PumpHistoryRules
import tk.glucodata.mod.pumpbt.core.BolusEvent
import tk.glucodata.mod.pumpbt.core.HistoryRead
import tk.glucodata.mod.pumpbt.core.HistoryReadStatus
import tk.glucodata.mod.pumpbt.core.HistoryReferenceLocal
import tk.glucodata.mod.pumpbt.core.PumpCycle
import tk.glucodata.mod.pumpbt.core.PumpEvents
import tk.glucodata.mod.pumpbt.core.PumpEventsResult
import tk.glucodata.mod.pumpbt.core.RawHistoryRecord
import tk.glucodata.mod.pumpbt.core.bytesOf
import tk.glucodata.mod.pumpbt.core.le32
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Made-up history records for the B2 tests (record layout per OpenMinimed and the 740G trial of 27.11, none of its
 * values): reference times on 2026-10-[day], boluses, meals, sensor values.
 */
internal object B2Fixtures {
    val ZONE: ZoneId = ZoneOffset.ofHours(3)
    const val HASH = "pump-a"

    fun raw(type: Int, seq: Long, offset: Int, payloadHex: String): RawHistoryRecord =
        RawHistoryRecord.fromPlain(bytesOf(type and 0xFF, type shr 8) + le32(seq) + bytesOf(offset and 0xFF, offset shr 8) + hex(payloadHex))!!

    /** Reference time (0xF00E, periodic) on 2026-10-[day] at [hour]:[minute]:00. */
    fun ref(seq: Long, offset: Int, hour: Int, minute: Int = 0, day: Int = 15): RawHistoryRecord =
        raw(0xF00E, seq, offset, "3c" + "ea070a%02x".format(day) + "%02x%02x00".format(hour, minute))

    fun at(hour: Int, minute: Int, second: Int = 0, day: Int = 15): LocalDateTime = LocalDateTime.of(2026, 10, day, hour, minute, second)

    fun ms(t: LocalDateTime): Long = t.atZone(ZONE).toInstant().toEpochMilli()

    fun ms(hour: Int, minute: Int, second: Int = 0, day: Int = 15): Long = ms(at(hour, minute, second, day))

    /** FLOAT (IEEE-11073) of [tenths] / 10 units, little-endian hex. */
    private fun units(tenths: Int): String = "%02x%02x%02xff".format(tenths and 0xFF, (tenths shr 8) and 0xFF, (tenths shr 16) and 0xFF)

    fun bolusBody(id: Int, tenthsFast: Int, tenthsExt: Int = 0, minutes: Int = 0, type: Int = 0x33): String =
        "%02x%02x".format(id and 0xFF, id shr 8) + "%02x".format(type) + units(tenthsFast) + units(tenthsExt) +
            "%02x%02x".format(minutes and 0xFF, minutes shr 8)

    fun programmed(seq: Long, offset: Int, id: Int, tenths: Int, ext: Int = 0, minutes: Int = 0, type: Int = 0x33) =
        raw(0x005A, seq, offset, bolusBody(id, tenths, ext, minutes, type))

    fun delivered(seq: Long, offset: Int, id: Int, tenths: Int, ext: Int = 0, minutes: Int = 0, type: Int = 0x33) =
        raw(0x0069, seq, offset, bolusBody(id, tenths, ext, minutes, type))

    fun programmedDetails(seq: Long, offset: Int) = raw(0x0066, seq, offset, "043c")

    fun deliveredDetails(seq: Long, offset: Int) = raw(0x0096, seq, offset, "02" + "00000000" + "0f")

    /** Meal (0xF005), [grams] as an SFLOAT with exponent 0. */
    fun meal(seq: Long, offset: Int, grams: Int) = raw(0xF005, seq, offset, "%02x%02x".format(grams and 0xFF, (grams shr 8) and 0x0F))

    /** A sensor value (0xF00C): a record without journal events. */
    fun sensor(seq: Long, offset: Int) = raw(0xF00C, seq, offset, "0000" + "7800" + "0000" + "0000")

    /** A finished bolus: programmed, its details, delivered [deliverSec] later, its details (four records from [seq]). */
    fun bolus(seq: Long, offset: Int, id: Int, tenths: Int, deliverSec: Int = 120): List<RawHistoryRecord> = listOf(
        programmed(seq, offset, id, tenths), programmedDetails(seq + 1, offset),
        delivered(seq + 2, offset + deliverSec, id, tenths), deliveredDetails(seq + 3, offset + deliverSec),
    )

    fun cycle(
        records: List<RawHistoryRecord>, readAtMs: Long = ms(12, 0), hash: String? = HASH,
        from: Long? = records.minOfOrNull { it.sequence }, to: Long? = records.maxOfOrNull { it.sequence },
        contiguousTo: Long? = to, oldest: Long? = null, newest: Long? = to,
        status: HistoryReadStatus = HistoryReadStatus.COMPLETE,
    ) = PumpCycle(
        readAtMs = readAtMs, serialHash = hash, records = records,
        history = HistoryRead(status, from, to, oldest, newest, contiguousTo),
    )

    fun build(m: PumpHistoryMemory): PumpEventsResult = PumpEvents.build(m.records, m.reference, m.contiguousTo)

    /** The ClEvents of the boluses and meals of [m], as the bridge would feed them (keys without aliases). */
    fun clEvents(m: PumpHistoryMemory): List<ClEvent> =
        PumpBtBridge.eventKeys(build(m).events).mapNotNull { (e, k) -> PumpBtBridge.toClEvent(e, m.aliases[k] ?: k, ZONE) }
}

/**
 * PumpHistoryRules and PumpBtHistoryStore: the number never passes a hole, lost and skipped records, another pump,
 * pruning only from the start at a reference time, the file. Made-up records only.
 */
class PumpBtHistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val now = B2Fixtures.ms(12, 0)

    private fun seqs(m: PumpHistoryMemory) = m.records.map { it.sequence }

    private fun sensors(range: LongRange) = range.map { B2Fixtures.sensor(it, 10) }

    private fun memory(cur: Long?, records: List<RawHistoryRecord> = emptyList()) =
        PumpHistoryMemory(pumpHash = B2Fixtures.HASH, records = records, contiguousTo = cur, enabledAtMs = now - 3_600_000L, boundaryMs = now - 7_200_000L)

    // --- the number -------------------------------------------------------------------------------------------------

    @Test
    fun theNumberStopsAtAHoleAndGoesOnWhenItIsFilled() {
        val m = memory(100, sensors(95L..100L))
        val a = PumpHistoryRules.applyCycle(m, B2Fixtures.cycle(sensors(101L..102L) + sensors(104L..105L), contiguousTo = 102), now)
        assertEquals(102L, a.memory.contiguousTo)
        assertEquals(listOf(95L, 96, 97, 98, 99, 100, 101, 102, 104, 105), seqs(a.memory))
        assertEquals(1, a.memory.stuckCount)
        assertFalse(a.firstReadComplete)
        assertEquals(103L, PumpHistoryRules.nextFrom(a.memory))

        val b = PumpHistoryRules.applyCycle(a.memory, B2Fixtures.cycle(sensors(103L..105L)), now)
        assertEquals(105L, b.memory.contiguousTo)
        assertEquals(0, b.memory.stuckCount)
        assertTrue(b.firstReadComplete)
        assertEquals(0L, b.gapRecords)
    }

    @Test
    fun aTimeOutHalfWayMovesTheNumberOnlyAsFarAsTheRecordsCame() {
        val m = memory(100)
        val a = PumpHistoryRules.applyCycle(
            m, B2Fixtures.cycle(sensors(101L..105L), from = 101, to = 110, contiguousTo = 105, newest = 110, status = HistoryReadStatus.TIMED_OUT), now,
        )
        assertEquals(105L, a.memory.contiguousTo)
        assertFalse(a.firstReadComplete)
        assertEquals(0, a.memory.stuckCount)
    }

    @Test
    fun aFailedReadWithoutRecordsChangesNothingButTheHash() {
        val m = memory(100, sensors(99L..100L)).copy(pumpHash = null)
        val a = PumpHistoryRules.applyCycle(m, B2Fixtures.cycle(emptyList(), from = 101, to = 110, contiguousTo = null, newest = 110, status = HistoryReadStatus.FAILED), now)
        assertEquals(100L, a.memory.contiguousTo)
        assertEquals(B2Fixtures.HASH, a.memory.pumpHash)
        assertFalse(a.newPump)
    }

    @Test
    fun threeFailedCyclesOnAHoleMarkItLost() {
        var m = memory(100)
        val first = PumpHistoryRules.applyCycle(m, B2Fixtures.cycle(sensors(101L..102L) + sensors(105L..106L), contiguousTo = 102), now)
        m = first.memory
        assertEquals(102L, m.contiguousTo)
        assertEquals(1, m.stuckCount)
        val second = PumpHistoryRules.applyCycle(m, B2Fixtures.cycle(sensors(105L..107L), from = 103, contiguousTo = null), now)
        assertEquals(2, second.memory.stuckCount)
        assertNull(second.skippedStuck)
        val third = PumpHistoryRules.applyCycle(second.memory, B2Fixtures.cycle(sensors(105L..107L), from = 103, contiguousTo = null), now)
        assertEquals(103L, third.skippedStuck)
        assertEquals(2L, third.gapRecords) // 103 and 104
        assertEquals(107L, third.memory.contiguousTo)
        assertEquals(2L, third.memory.counters.gaps)
        assertEquals(0, third.memory.stuckCount)
    }

    @Test
    fun aCycleWithoutRecordsPastTheHoleDoesNotCount() {
        val m = memory(102, sensors(101L..102L) + sensors(105L..106L)).copy(stuckCount = 2)
        // a read that timed out at once: nothing past the hole came this time
        val a = PumpHistoryRules.applyCycle(m, B2Fixtures.cycle(emptyList(), from = 103, to = 106, contiguousTo = null, newest = 106, status = HistoryReadStatus.TIMED_OUT), now)
        assertEquals(2, a.memory.stuckCount)
        assertEquals(102L, a.memory.contiguousTo)
    }

    @Test
    fun theOldestRecordPastTheNumberMakesItJump() {
        val m = memory(100, sensors(99L..100L))
        val a = PumpHistoryRules.applyCycle(m, B2Fixtures.cycle(sensors(150L..152L), oldest = 150), now)
        assertEquals(152L, a.memory.contiguousTo)
        assertEquals(49L, a.gapRecords)
        assertEquals(49L, a.memory.counters.gaps)
        assertTrue(a.firstReadComplete)
    }

    @Test
    fun anEmptyMemoryStartsAtTheFirstRecordAsked() {
        val a = PumpHistoryRules.applyCycle(PumpHistoryMemory(), B2Fixtures.cycle(sensors(40L..60L), oldest = 40), now)
        assertEquals(60L, a.memory.contiguousTo)
        assertEquals(B2Fixtures.HASH, a.memory.pumpHash)
        assertFalse(a.newPump) // the first hash of an empty memory is taken
        assertTrue(a.firstReadComplete)
        // no enable moment yet (a broken file the source did not re-enable): now, boundary now - 24 h
        assertEquals(now, a.memory.enabledAtMs)
        assertEquals(now - 24 * 3_600_000L, a.memory.boundaryMs)
    }

    @Test
    fun recordsAtOrBelowTheNumberAreNeverTakenAgain() {
        val m = memory(210, sensors(200L..210L))
        val a = PumpHistoryRules.applyCycle(m, B2Fixtures.cycle(sensors(150L..212L)), now)
        assertEquals((200L..212L).toList(), seqs(a.memory))
        assertEquals(212L, a.memory.contiguousTo)
    }

    @Test
    fun noHashChangesNothing() {
        val m = memory(100, sensors(99L..100L))
        val a = PumpHistoryRules.applyCycle(m, B2Fixtures.cycle(sensors(101L..103L), hash = null), now)
        assertTrue(a.ignored)
        assertEquals(m, a.memory)
        assertFalse(a.firstReadComplete)
    }

    @Test
    fun anotherPumpStartsTheMemoryAgain() {
        val m = memory(100, sensors(99L..100L)).copy(
            acceptedAt = mapOf("INSULIN|2026-10-15T10:01:00|0" to 1L), backfill = BackfillState.CONFIRMED, seenUnknownTypes = setOf(0x1234),
        )
        val other = PumpHistoryRules.applyCycle(m, B2Fixtures.cycle(sensors(101L..103L), hash = "pump-b"), now)
        assertTrue(other.newPump)
        assertEquals(PumpHistoryMemory(pumpHash = "pump-b", enabledAtMs = now, boundaryMs = now, seenUnknownTypes = setOf(0x1234)), other.memory)
        assertNull(PumpHistoryRules.nextFrom(other.memory))

    }

    /** The same hash, the pump's newest record (20) below the number (100) - maybe a late, false answer. */
    private fun backCycle() = B2Fixtures.cycle(sensors(18L..20L), from = 18, to = 20, contiguousTo = 20, newest = 20)

    @Test
    fun aNewestNumberBelowTheMemoryIsBelievedOnlyOnTheThirdCycleInARow() {
        val m = memory(100, sensors(99L..100L))
        val first = PumpHistoryRules.applyCycle(m, backCycle(), now)
        assertFalse(first.newPump)
        assertFalse(first.firstReadComplete)
        assertEquals(m.copy(newestBackCount = 1), first.memory) // number and records unchanged, the cycle's history ignored
        val second = PumpHistoryRules.applyCycle(first.memory, backCycle(), now)
        assertFalse(second.newPump)
        assertEquals(2, second.memory.newestBackCount)
        assertEquals(100L, second.memory.contiguousTo)
        val third = PumpHistoryRules.applyCycle(second.memory, backCycle(), now)
        assertTrue(third.newPump)
        assertTrue(third.memory.records.isEmpty())
        assertNull(third.memory.contiguousTo)
        assertEquals(0, third.memory.newestBackCount)
        assertEquals(now, third.memory.enabledAtMs)
        assertEquals(now, third.memory.boundaryMs)
    }

    @Test
    fun aNormalCycleClearsTheSuspicion() {
        val m = memory(100, sensors(99L..100L))
        val two = PumpHistoryRules.applyCycle(PumpHistoryRules.applyCycle(m, backCycle(), now).memory, backCycle(), now).memory
        assertEquals(2, two.newestBackCount)
        // a normal read: taken as usual, the count back to 0
        val normal = PumpHistoryRules.applyCycle(two, B2Fixtures.cycle(sensors(101L..102L)), now)
        assertEquals(0, normal.memory.newestBackCount)
        assertEquals(102L, normal.memory.contiguousTo)
        // so the next low answer is again only the first one
        val again = PumpHistoryRules.applyCycle(normal.memory, backCycle(), now)
        assertFalse(again.newPump)
        assertEquals(1, again.memory.newestBackCount)
        // a cycle without a hash changes nothing, the count included
        assertEquals(again.memory, PumpHistoryRules.applyCycle(again.memory, backCycle().copy(serialHash = null), now).memory)
        // another hash is another pump at once
        assertTrue(PumpHistoryRules.applyCycle(m, B2Fixtures.cycle(sensors(101L..102L), hash = "pump-b"), now).newPump)
    }

    // --- enable, accepted --------------------------------------------------------------------------------------------

    @Test
    fun enableGivesANewMomentAndCardButKeepsWhatWasAccepted() {
        val m = memory(100).copy(
            backfill = BackfillState.CONFIRMED, backfillShown = listOf("k"), aliases = mapOf("a" to "b"),
            acceptedAt = mapOf("b" to 5L), backfillWritten = listOf("carelink:x"),
        )
        val e = PumpHistoryRules.enable(m, now + 1000, now - 500)
        assertEquals(now + 1000, e.enabledAtMs)
        assertEquals(now - 500, e.boundaryMs)
        assertEquals(BackfillState.NOT_READY, e.backfill)
        assertTrue(e.backfillShown.isEmpty())
        assertEquals(m.aliases, e.aliases)
        assertEquals(m.acceptedAt, e.acceptedAt)
        assertEquals(m.backfillWritten, e.backfillWritten)
        // a boundary later than the moment is cut to it
        assertEquals(now, PumpHistoryRules.enable(m, now, now + 60_000).boundaryMs)
    }

    @Test
    fun aRunningBolusIsNotAcceptedAMealIsAndFirstCatchUpKeysAreKept() {
        val m = memory(100).copy(backfillShown = listOf("INSULIN|2026-10-15T09:00:00|0"))
        val running = ClEvent(ClKind.BOLUS, "INSULIN|2026-10-15T10:00:00|0", 1L, programmedUnits = 2.0, completed = false)
        val meal = ClEvent(ClKind.MEAL, "MEAL|2026-10-15T09:59:58|0", 1L, grams = 30.0)
        val old = ClEvent(ClKind.BOLUS, "INSULIN|2026-10-15T09:00:00|0", 1L, programmedUnits = 1.0, deliveredUnits = 1.0, completed = true)
        val a = PumpHistoryRules.markAccepted(m, listOf(running, meal, old), now)
        assertEquals(setOf(meal.key, old.key), a.acceptedAt.keys)
        assertEquals(listOf("carelink:INSULIN:2026-10-15T09:00:00:0:insulin"), a.backfillWritten)
        // accepted again later: the first time stays
        val b = PumpHistoryRules.markAccepted(a, listOf(meal), now + 99)
        assertEquals(now, b.acceptedAt[meal.key])
    }

    // --- prune ------------------------------------------------------------------------------------------------------

    /** Three days old: 2026-10-15; now - 2026-10-18 12:00. */
    private val later = B2Fixtures.ms(12, 0, day = 18)

    private fun accepted(m: PumpHistoryMemory): PumpHistoryMemory =
        PumpHistoryRules.markAccepted(m, B2Fixtures.clEvents(m), later)

    private fun pruned(m: PumpHistoryMemory, nowMs: Long = later): PumpHistoryMemory =
        PumpHistoryRules.prune(m, B2Fixtures.build(m), nowMs, B2Fixtures.ZONE)

    /** ref 100 (10:00), bolus 101-104, sensor 105, ref 106 (11:00), sensor 107, ref 108 (12:00), sensor 109. */
    private fun dayRecords(): List<RawHistoryRecord> =
        listOf(B2Fixtures.ref(100, 3600, 10)) + B2Fixtures.bolus(101, 60, id = 1, tenths = 25) +
            listOf(B2Fixtures.sensor(105, 300), B2Fixtures.ref(106, 3600, 11), B2Fixtures.sensor(107, 60), B2Fixtures.ref(108, 3600, 12), B2Fixtures.sensor(109, 60))

    @Test
    fun aRecordStaysUntilTheIntakeAnsweredWritten() {
        val m = memory(109, dayRecords()).copy(enabledAtMs = B2Fixtures.ms(9, 0), boundaryMs = B2Fixtures.ms(8, 0))
        // the bolus is not accepted: nothing from it on may go
        assertEquals(seqs(m), seqs(pruned(m)))
        // after WRITTEN everything below the newest reference goes; the reference stays the oldest record
        val p = pruned(accepted(m))
        assertEquals(listOf(108L, 109L), seqs(p))
        assertEquals(HistoryReferenceLocal(108, B2Fixtures.at(12, 0)), p.reference)
        assertEquals(109L, p.contiguousTo)
        // the bolus left the memory: its acceptance is no longer kept
        assertTrue(p.acceptedAt.isEmpty())
    }

    @Test
    fun notOlderThan48Hours() {
        val m = accepted(memory(109, dayRecords()).copy(enabledAtMs = B2Fixtures.ms(9, 0)))
        // exactly 48 h after the first record: nothing goes
        assertEquals(seqs(m), seqs(pruned(m, B2Fixtures.ms(10, 0, day = 17))))
        // 48 h after the bolus but not after the reference at 11:00: the cut is at 106
        val p = pruned(m, B2Fixtures.ms(10, 30, day = 17))
        assertEquals(listOf(106L, 107L, 108L, 109L), seqs(p))
    }

    @Test
    fun anUnacceptedRecordHoldsEverythingAboveIt() {
        // a second bolus at 11:01 is not accepted; the first one is
        val recs = listOf(B2Fixtures.ref(100, 3600, 10)) + B2Fixtures.bolus(101, 60, id = 1, tenths = 25) +
            listOf(B2Fixtures.ref(105, 3600, 11)) + B2Fixtures.bolus(106, 60, id = 2, tenths = 15) +
            listOf(B2Fixtures.ref(110, 3600, 12), B2Fixtures.sensor(111, 60))
        val base = memory(111, recs).copy(enabledAtMs = B2Fixtures.ms(9, 0))
        val firstOnly = PumpHistoryRules.markAccepted(base, B2Fixtures.clEvents(base).filter { it.key.contains("10:01") }, later)
        assertEquals((105L..111L).toList(), seqs(pruned(firstOnly)))
    }

    @Test
    fun aBolusAcrossAReferenceIsNotCutApart() {
        // programmed 101 (10:59), a reference at 11:00 between it and delivered 104 (11:02)
        val recs = listOf(
            B2Fixtures.ref(100, 3600, 10), B2Fixtures.programmed(101, 3540, 1, 25), B2Fixtures.programmedDetails(102, 3540),
            B2Fixtures.ref(103, 3600, 11), B2Fixtures.delivered(104, 120, 1, 25), B2Fixtures.deliveredDetails(105, 120),
            B2Fixtures.sensor(106, 300),
        )
        val m = accepted(memory(106, recs).copy(enabledAtMs = B2Fixtures.ms(9, 0)))
        assertTrue(B2Fixtures.build(m).events.filterIsInstance<BolusEvent>().single().completed)
        // the only reference above the start is crossed by the bolus: nothing goes
        assertEquals(seqs(m), seqs(pruned(m)))
        // with a later reference the whole bolus goes at once
        val more = accepted(m.copy(records = recs + listOf(B2Fixtures.ref(107, 3600, 12), B2Fixtures.sensor(108, 1)), contiguousTo = 108))
        assertEquals(listOf(107L, 108L), seqs(pruned(more)))
    }

    @Test
    fun theRecordsThatStayKeepTheirTimeAndKey() {
        val recs = dayRecords() + B2Fixtures.bolus(110, 120, id = 2, tenths = 10) // 12:02, after the reference at 12:00
        val m = accepted(memory(113, recs).copy(enabledAtMs = B2Fixtures.ms(9, 0)))
        val before = PumpBtBridge.eventKeys(B2Fixtures.build(m).events).entries.first { (e, _) -> e.sequence == 110L }
        val p = pruned(m)
        assertEquals((108L..113L).toList(), seqs(p))
        val after = PumpBtBridge.eventKeys(B2Fixtures.build(p).events).entries.single()
        assertEquals(before.key.at, after.key.at)
        assertEquals(before.value, after.value)
        assertEquals("INSULIN|2026-10-15T12:02:00|0", after.value)
        assertEquals(p.acceptedAt.keys, setOf(after.value))
    }

    @Test
    fun anUnansweredCardKeepsTheOldRecords() {
        // the bolus is before the enable moment: it is old; the card is waiting
        val m = memory(109, dayRecords()).copy(
            enabledAtMs = B2Fixtures.ms(13, 0), boundaryMs = B2Fixtures.ms(9, 0), backfill = BackfillState.WAITING,
            backfillShown = listOf("INSULIN|2026-10-15T10:01:00|0"),
        )
        assertEquals(seqs(m), seqs(pruned(m)))
        // confirmed: the listed bolus must be written first
        assertEquals(seqs(m), seqs(pruned(m.copy(backfill = BackfillState.CONFIRMED))))
        // declined: it may go
        assertEquals(listOf(108L, 109L), seqs(pruned(m.copy(backfill = BackfillState.DECLINED, backfillShown = emptyList()))))
        // confirmed, but not listed (matched, or before the boundary): it may go
        assertEquals(listOf(108L, 109L), seqs(pruned(m.copy(backfill = BackfillState.CONFIRMED, backfillShown = emptyList()))))
    }

    @Test
    fun nothingPastTheNumberIsPruned() {
        // 105 never came: the records past it stay even when old, only below the number can go
        val recs = listOf(B2Fixtures.ref(100, 3600, 10), B2Fixtures.sensor(101, 1), B2Fixtures.ref(102, 3600, 11), B2Fixtures.sensor(103, 1),
            B2Fixtures.ref(104, 3600, 12), B2Fixtures.ref(106, 3600, 13), B2Fixtures.sensor(107, 1))
        val m = memory(104, recs)
        assertEquals(listOf(104L, 106L, 107L), seqs(pruned(m)))
        val m2 = memory(103, recs)
        assertEquals(listOf(102L, 103L, 104L, 106L, 107L), seqs(pruned(m2)))
    }

    @Test
    fun anUndatedRecordGoesWhenALaterDatedOneIsOld() {
        // 100 has no reference before it and cannot be dated back (its offset is larger than the next reference's)
        val recs = listOf(B2Fixtures.sensor(100, 4000), B2Fixtures.ref(101, 3600, 10), B2Fixtures.sensor(102, 1), B2Fixtures.ref(103, 3600, 11), B2Fixtures.sensor(104, 1))
        val p = pruned(memory(104, recs))
        assertEquals(listOf(103L, 104L), seqs(p))
    }

    // --- the file ---------------------------------------------------------------------------------------------------

    @Test
    fun theMemorySurvivesTheFile() {
        val f = File(tmp.root, "mod-pumpbt/history.json")
        val store = PumpBtHistoryStore(f)
        val m = PumpHistoryMemory(
            pumpHash = "abc", records = dayRecords(), reference = HistoryReferenceLocal(108, B2Fixtures.at(12, 0)),
            contiguousTo = 109, stuckCount = 2, newestBackCount = 1, enabledAtMs = 11L, boundaryMs = 7L, backfill = BackfillState.WAITING,
            backfillShown = listOf("INSULIN|2026-10-15T10:01:00|0"), backfillWritten = listOf("carelink:MEAL:2026-10-15T10:00:58:0:carbs"),
            aliases = mapOf("INSULIN|2026-10-15T10:01:00|0" to "INSULIN|2026-10-15T10:01:30|0"),
            acceptedAt = mapOf("MEAL|2026-10-15T10:00:58|0" to 99L),
            counters = PumpHistoryCounters(1, 2, 3, 4, 5, 6, 7), seenUnknownTypes = setOf(0x1234, 0x0042),
        )
        store.save(m)
        assertFalse(File(f.parentFile, "history.json.tmp").exists())
        val back = PumpBtHistoryStore(f)
        assertEquals(m, back.load())
        assertFalse(back.loadFailed)
        // an empty memory too
        store.save(PumpHistoryMemory())
        assertEquals(PumpHistoryMemory(), store.load())
    }

    @Test
    fun aBrokenFileIsAnEmptyMemoryAndSaysSo() {
        val f = File(tmp.root, "history.json")
        val store = PumpBtHistoryStore(f)
        assertEquals(PumpHistoryMemory(), store.load())
        assertFalse(store.loadFailed) // no file - not broken

        f.writeText("{\"v\":1,\"records\":[\"zz\"]")
        assertEquals(PumpHistoryMemory(), store.load())
        assertTrue(store.loadFailed)

        // a record shorter than its header breaks the whole file, not just that record
        store.save(memory(109, dayRecords()))
        f.writeText(f.readText().replace(Regex("\"records\":\\[\"[0-9a-f]+\""), "\"records\":[\"0e\""))
        assertEquals(PumpHistoryMemory(), store.load())
        assertTrue(store.loadFailed)

        store.save(memory(109, dayRecords()))
        assertNotNull(store.load().contiguousTo)
        assertFalse(store.loadFailed)
    }
}
