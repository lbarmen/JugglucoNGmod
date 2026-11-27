package mod.pumpbt

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import mod.pumpbt.B2Fixtures.HASH
import mod.pumpbt.B2Fixtures.ZONE
import mod.pumpbt.B2Fixtures.at
import mod.pumpbt.B2Fixtures.bolus
import mod.pumpbt.B2Fixtures.meal
import mod.pumpbt.B2Fixtures.ms
import mod.pumpbt.B2Fixtures.programmed
import mod.pumpbt.B2Fixtures.programmedDetails
import mod.pumpbt.B2Fixtures.ref
import tk.glucodata.data.journal.JournalEntryEntity
import org.json.JSONObject
import tk.glucodata.mod.carelink.CareLinkBasalWriter
import tk.glucodata.mod.carelink.CareLinkPumpNow
import tk.glucodata.mod.carelink.CareLinkState
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.carelink.ClEvent
import tk.glucodata.mod.carelink.ClKind
import tk.glucodata.mod.pumpbt.BackfillState
import tk.glucodata.mod.pumpbt.CyclePlan
import tk.glucodata.mod.pumpbt.JournalRow
import tk.glucodata.mod.pumpbt.PumpBtBridge
import tk.glucodata.mod.pumpbt.PumpHistoryMemory
import tk.glucodata.mod.pumpbt.PumpHistoryRules
import tk.glucodata.mod.pumpbt.PumpWarning
import tk.glucodata.mod.pumpbt.core.BasalChangeEvent
import tk.glucodata.mod.pumpbt.core.BolusEvent
import tk.glucodata.mod.pumpbt.core.MealEvent
import tk.glucodata.mod.pumpbt.core.PumpCycle
import tk.glucodata.mod.pumpbt.core.RawHistoryRecord

/**
 * PumpBtBridge: keys in the cloud's form, the writers' models, what each cycle feeds the journal, the first catch-up
 * (matching by the journal, boundary, card, "Write" writes exactly what was shown) and the warnings. Made-up records,
 * doses and rows only; the pump clock and the phone zone are UTC+3.
 */
class PumpBtBridgeTest {

    /** The source was turned on at 12:00 (2026-10-15); the boundary of the old is 09:00; now is 12:30. */
    private val enabled = ms(12, 0)
    private val boundary = ms(9, 0)
    private val now = ms(12, 30)

    private val k1 = "INSULIN|2026-10-15T10:01:00|0"

    private fun mem(
        records: List<RawHistoryRecord>, enabledAt: Long? = enabled, boundaryMs: Long? = boundary,
        backfill: BackfillState = BackfillState.NOT_READY, shown: List<String> = emptyList(),
        cur: Long? = records.maxOfOrNull { it.sequence },
    ) = PumpHistoryMemory(
        pumpHash = HASH, records = records, contiguousTo = cur, enabledAtMs = enabledAt, boundaryMs = boundaryMs,
        backfill = backfill, backfillShown = shown,
    )

    private fun plan(m: PumpHistoryMemory, rows: List<JournalRow> = emptyList(), nowMs: Long = now, first: Boolean = true): CyclePlan =
        PumpBtBridge.planCycle(m, B2Fixtures.build(m), rows, nowMs, ZONE, first)

    /** ref 100 at 10:00 and a finished 2.5 U bolus at 10:01 (delivered 10:03). */
    private fun oneOldBolus(): List<RawHistoryRecord> = listOf(ref(100, 3600, 10)) + bolus(101, 60, id = 1, tenths = 25)

    private fun cloudRow(id: Long, atMs: Long, units: Double, pumpTime: String) =
        JournalRow(id, ClKind.BOLUS, atMs, units, "carelink:INSULIN:$pumpTime:0:insulin")

    // --- keys and models --------------------------------------------------------------------------------------------

    @Test
    fun keysAreInTheCloudsForm() {
        val recs = listOf(
            ref(100, 3600, 10), meal(101, 58, 30), programmed(102, 60, 1, 25), programmedDetails(103, 60),
            meal(104, 58, 12), B2Fixtures.raw(0x0099, 105, 70, "00" + "050000ff" + "080000ff"),
        )
        val events = B2Fixtures.build(mem(recs)).events
        val keys = PumpBtBridge.eventKeys(events)
        assertEquals(k1, keys[events.filterIsInstance<BolusEvent>().single()])
        val meals = events.filterIsInstance<MealEvent>().sortedBy { it.sequence }
        assertEquals("MEAL|2026-10-15T10:00:58|0", keys[meals[0]])
        assertEquals("MEAL|2026-10-15T10:00:58|1", keys[meals[1]]) // same second: n by record number
        assertTrue(events.any { it is BasalChangeEvent })
        assertEquals(3, keys.size) // the basal change has no key
        // zero seconds are written out
        assertTrue(keys.values.all { it.split('|')[1].length == 19 })

        assertEquals("carelink:INSULIN:2026-10-15T10:01:00:0:insulin", PumpBtBridge.journalKey(ClKind.BOLUS, k1))
        assertEquals("carelink:MEAL:2026-10-15T10:00:58:1:carbs", PumpBtBridge.journalKey(ClKind.MEAL, "MEAL|2026-10-15T10:00:58|1"))
        assertEquals(k1, PumpBtBridge.cloudKey("carelink:INSULIN:2026-10-15T10:01:00:0:insulin", ClKind.BOLUS))
        assertNull(PumpBtBridge.cloudKey("carelink:INSULIN:2026-10-15T10:01:00:0:insulin", ClKind.MEAL))
        assertNull(PumpBtBridge.cloudKey("carelink:basal:1700000000000:0:basal", ClKind.BOLUS))
        assertNull(PumpBtBridge.cloudKey(null, ClKind.BOLUS))
        assertNull(PumpBtBridge.cloudKey("manual-123", ClKind.BOLUS))
    }

    private fun bolusEvent(type: Int?, programmed: Double?, ext: Double?, completed: Boolean = true, delivered: Double? = programmed) = BolusEvent(
        sequence = 1, at = at(10, 1), bolusId = 1, requestSequence = 1, deliveredSequence = if (completed) 3 else null,
        deliveredAt = if (completed) at(10, 3) else null, bolusType = type, programmedUnits = programmed,
        deliveredUnits = if (completed) delivered else null, programmedExtendedUnits = ext, deliveredExtendedUnits = if (completed) ext else null,
        durationMinutes = 0, completed = completed, endReason = null, activationType = null,
    )

    @Test
    fun bolusesAndMealsBecomeTheWritersModel() {
        val running = PumpBtBridge.toClEvent(bolusEvent(0x33, 2.5, 0.0, completed = false), k1, ZONE)!!
        assertEquals(ClKind.BOLUS, running.kind)
        assertEquals(k1, running.key)
        assertEquals(ms(10, 1), running.atMs)
        assertEquals(false, running.completed)
        assertEquals(2.5, running.programmedUnits!!, 0.0)
        assertNull(running.deliveredUnits)
        assertEquals(2.5, running.dose!!, 0.0)
        assertEquals(ClEvent.BOLUS_FAST, running.bolusType)

        val stopped = PumpBtBridge.toClEvent(bolusEvent(0x33, 2.5, 0.0, delivered = 1.2), k1, ZONE)!!
        assertEquals(true, stopped.completed)
        assertEquals(1.2, stopped.dose!!, 0.0)
        assertTrue(stopped.interrupted)

        // rounded as the cloud's sums
        assertEquals(0.3, PumpBtBridge.toClEvent(bolusEvent(0x33, 0.1 + 0.2, 0.0), k1, ZONE)!!.programmedUnits!!, 0.0)

        fun type(t: Int?, p: Double, ext: Double) = PumpBtBridge.toClEvent(bolusEvent(t, p, ext), k1, ZONE)!!.bolusType
        assertEquals("FAST", type(0x33, 2.0, 0.0))
        assertEquals("FAST", type(0x0F, 2.0, 0.0))
        assertEquals("FAST", type(null, 2.0, 0.0))
        assertEquals("EXTENDED", type(0x3C, 2.0, 2.0))
        assertEquals("DUAL", type(0xFF, 3.0, 1.0))
        assertEquals("EXTENDED", type(0x0F, 2.0, 2.0))
        assertEquals("DUAL", type(0x0F, 3.0, 1.0))
        assertTrue(PumpBtBridge.toClEvent(bolusEvent(0x0F, 3.0, 1.0), k1, ZONE)!!.extended)

        val meal = PumpBtBridge.toClEvent(MealEvent(5, at(10, 0, 58), 30.0), "MEAL|2026-10-15T10:00:58|0", ZONE)!!
        assertEquals(ClKind.MEAL, meal.kind)
        assertEquals(30.0, meal.grams!!, 0.0)
        assertEquals(ms(10, 0, 58), meal.atMs)
        assertNull(PumpBtBridge.toClEvent(BasalChangeEvent(6, at(10, 2), 0.5, 0.8), "x", ZONE))
    }

    @Test
    fun theCycleBecomesTheWritersSnapshot() {
        val c = PumpCycle(
            readAtMs = 5_000L, serialHash = HASH, therapyState = 0x3C, reservoirUnits = 120.04, iobUnits = 1.25,
            basalRate = 0.8, basalRead = true, tempBasalRate = 1.2, tempBasalType = 0x33, tempBasalMinutesLeft = 20,
            batteryPercent = 70,
        )
        val p = PumpBtBridge.toPumpNow(c)!!
        assertEquals(5_000L, p.dataAtMs)
        assertEquals(5_000L, p.iobAtMs)
        assertEquals(1.25, p.iobUnits!!, 0.0)
        assertEquals(120.04, p.reservoirUnits!!, 0.0)
        assertNull(p.reservoirPercent)
        assertEquals(70, p.batteryPercent)
        assertEquals(0.8, p.basalRate!!, 0.0)
        assertEquals(1.2, p.tempBasal!!.rate!!, 0.0)
        assertTrue(p.tempBasal!!.absolute)
        assertNull(p.tempBasal!!.percent)
        assertEquals(20, p.tempBasal!!.minutesLeft)
        assertEquals(true, p.suspended) // paused
        assertTrue(p.basalKnown)

        assertEquals(true, PumpBtBridge.toPumpNow(c.copy(therapyState = 0x33))!!.suspended)
        assertEquals(false, PumpBtBridge.toPumpNow(c.copy(therapyState = 0x55))!!.suspended)
        assertNull(PumpBtBridge.toPumpNow(c.copy(therapyState = null))!!.suspended)
        val nothing = PumpBtBridge.toPumpNow(c.copy(basalRead = false, basalRate = null, tempBasalRate = null, iobUnits = null))!!
        assertFalse(nothing.basalKnown) // no basal answer: a gap, not a stop
        assertNull(nothing.iobAtMs)
        assertNull(nothing.tempBasal)
        assertNull(PumpBtBridge.toPumpNow(c.copy(serialHash = null)))
    }

    @Test
    fun aPercentageTemporaryBasalIsAPercentageNotUnitsPerHour() {
        // critic of the joints 1, B-1: 150 % must not become 150 U/h
        val normal = PumpCycle(readAtMs = 5_000L, serialHash = HASH, therapyState = 0x55, basalRate = 0.8, basalRead = true)
        val pct = normal.copy(readAtMs = 65_000L, tempBasalRate = 150.0, tempBasalType = 0x3C, tempBasalMinutesLeft = 90)
        val p = PumpBtBridge.toPumpNow(pct)!!
        assertNull(p.tempBasal!!.rate)
        assertEquals(150, p.tempBasal!!.percent)
        assertFalse(p.tempBasal!!.absolute)
        assertEquals(90, p.tempBasal!!.minutesLeft)
        assertTrue(p.basalKnown)
        // the basal writer makes of it what it makes of the cloud's percentage temporary basal: 0.8 U/h x 150 % = 1.2 U/h
        val lastNormal = CareLinkBasalWriter.rateOf(PumpBtBridge.toPumpNow(normal)!!, null)
        assertEquals(0.8, lastNormal!!, 1e-9)
        assertEquals(1.2, CareLinkBasalWriter.rateOf(p, lastNormal)!!, 1e-9)
        // 70 % of 0.8 - 0.56 U/h, not 70 U/h
        val low = PumpBtBridge.toPumpNow(pct.copy(tempBasalRate = 70.0))!!
        assertEquals(0.56, CareLinkBasalWriter.rateOf(low, lastNormal)!!, 1e-9)
    }

    @Test
    fun aTemporaryBasalOfAnUnknownTypeIsABasalGap() {
        val c = PumpCycle(readAtMs = 5_000L, serialHash = HASH, therapyState = 0x55, iobUnits = 1.0, basalRate = 0.8,
            basalRead = true, tempBasalRate = 1.2, tempBasalMinutesLeft = 20)
        for (type in listOf(null, 0x0F, 0x55)) {
            val p = PumpBtBridge.toPumpNow(c.copy(tempBasalType = type))!!
            assertNull("type $type", p.tempBasal)
            assertFalse("type $type", p.basalKnown)
            assertNull("type $type: a gap, not a rate", CareLinkBasalWriter.rateOf(p, 0.8))
            assertEquals(1.0, p.iobUnits!!, 0.0) // the rest of the snapshot stands
        }
        // a broken value of a known type: no temporary basal and the basal not known either
        val nan = PumpBtBridge.toPumpNow(c.copy(tempBasalType = 0x3C, tempBasalRate = Double.NaN))!!
        assertNull(nan.tempBasal)
        assertFalse(nan.basalKnown)
        // no temporary basal at all: the normal rate
        val none = PumpBtBridge.toPumpNow(c.copy(tempBasalRate = null, tempBasalMinutesLeft = null))!!
        assertNull(none.tempBasal)
        assertTrue(none.basalKnown)
        assertEquals(0.8, CareLinkBasalWriter.rateOf(none, null)!!, 1e-9)
    }

    @Test
    fun aCycleWithoutOneSnapshotValueGivesNoSnapshot() {
        // critic of the joints 1, C-1: a cycle cut short neither breaks the basal nor counts as fresh data
        val empty = PumpCycle(readAtMs = 5_000L, serialHash = HASH)
        assertNull(PumpBtBridge.toPumpNow(empty))
        assertNull(PumpBtBridge.toPumpNow(empty.copy(records = oneOldBolus())))
        // any one value is a snapshot
        assertEquals(true, PumpBtBridge.toPumpNow(empty.copy(therapyState = 0x33))!!.suspended)
        assertEquals(1.0, PumpBtBridge.toPumpNow(empty.copy(iobUnits = 1.0))!!.iobUnits!!, 0.0)
        assertEquals(100.0, PumpBtBridge.toPumpNow(empty.copy(reservoirUnits = 100.0))!!.reservoirUnits!!, 0.0)
        assertTrue(PumpBtBridge.toPumpNow(empty.copy(basalRead = true))!!.basalKnown)
        assertEquals(50, PumpBtBridge.toPumpNow(empty.copy(batteryPercent = 50))!!.batteryPercent)
    }

    // --- what is fed --------------------------------------------------------------------------------------------------

    @Test
    fun aRunningBolusGetsItsResultUnderTheSameKeyInALaterCycle() {
        val start = listOf(ref(100, 3600, 10), programmed(101, 60, 1, 25), programmedDetails(102, 60))
        val m1 = mem(start, enabledAt = ms(9, 0))
        val p1 = plan(m1, nowMs = ms(10, 2))
        val running = p1.toWriter.single()
        assertEquals(k1, running.key)
        assertEquals(false, running.completed)
        assertEquals(2.5, running.dose!!, 0.0)
        // a running bolus is not accepted
        val a1 = PumpHistoryRules.markAccepted(p1.memory, p1.toWriter, ms(10, 2))
        assertTrue(a1.acceptedAt.isEmpty())

        val m2 = a1.copy(records = start + listOf(B2Fixtures.delivered(103, 200, 1, 25), B2Fixtures.deliveredDetails(104, 200)), contiguousTo = 104)
        val p2 = plan(m2, nowMs = ms(10, 5))
        val done = p2.toWriter.single()
        assertEquals(k1, done.key)
        assertEquals(true, done.completed)
        assertEquals(2.5, done.deliveredUnits!!, 0.0)
        val a2 = PumpHistoryRules.markAccepted(p2.memory, p2.toWriter, ms(10, 5))
        assertEquals(setOf(k1), a2.acceptedAt.keys)
        // within 24 h it is still fed every cycle (the writer does not write the same twice)
        assertEquals(listOf(k1), plan(a2, nowMs = ms(11, 0)).toWriter.map { it.key })
    }

    @Test
    fun aBolusFiveMinutesAfterTheEnableIsWrittenWhateverTheCardSays() {
        val recs = listOf(ref(100, 3600, 11)) + bolus(101, 60, id = 1, tenths = 25) + listOf(ref(105, 3600, 12)) + bolus(106, 300, id = 2, tenths = 15)
        val newKey = "INSULIN|2026-10-15T12:05:00|0"
        val oldKey = "INSULIN|2026-10-15T11:01:00|0"
        val m = mem(recs)
        assertEquals(listOf(newKey), plan(m, first = false).toWriter.map { it.key }) // history not read through
        val waiting = plan(m)
        assertEquals(BackfillState.WAITING, waiting.memory.backfill)
        assertEquals(listOf(oldKey), waiting.card!!.toWrite.map { it.key })
        assertEquals(listOf(newKey), waiting.toWriter.map { it.key })
        val confirmed = PumpBtBridge.confirmBackfill(waiting.memory, listOf(oldKey))!!
        assertEquals(listOf(oldKey, newKey), plan(confirmed).toWriter.map { it.key })
        val declined = PumpBtBridge.declineBackfill(waiting.memory)
        assertEquals(enabled, declined.boundaryMs)
        assertEquals(listOf(newKey), plan(declined).toWriter.map { it.key })
    }

    @Test
    fun aLongBreakFeedsOlderEventsUntilAccepted() {
        // enabled on the 13th; a bolus on the 15th at 10:01 and one on the 16th at 10:01; now - the 16th, 13:00
        val recs = oneOldBolus() + listOf(ref(105, 3600, 10, day = 16)) + bolus(106, 60, id = 2, tenths = 15)
        val m = mem(recs, enabledAt = ms(12, 0, day = 13), boundaryMs = ms(11, 0, day = 13), backfill = BackfillState.CONFIRMED)
        val nowMs = ms(13, 0, day = 16)
        val p = plan(m, nowMs = nowMs)
        val day16 = "INSULIN|2026-10-16T10:01:00|0"
        assertEquals(listOf(k1, day16), p.toWriter.map { it.key })
        val a = PumpHistoryRules.markAccepted(p.memory, p.toWriter, nowMs)
        // older than 24 h and accepted - no more; within 24 h - every cycle
        assertEquals(listOf(day16), plan(a, nowMs = nowMs + 60_000).toWriter.map { it.key })
    }

    @Test
    fun anEventOlderThan47HoursWithARowWaitsForAFullReadThenKeepsThePersonsEdit() {
        // a stopped bolus: 2.5 U programmed, 1.2 U delivered; written as "running" (2.5 U) before a break of the link
        val recs = listOf(ref(100, 3600, 10), programmed(101, 60, 1, 25), programmedDetails(102, 60),
            B2Fixtures.delivered(103, 200, 1, 12), B2Fixtures.deliveredDetails(104, 200))
        val m = mem(recs, enabledAt = ms(12, 0, day = 13), boundaryMs = ms(11, 0, day = 13), backfill = BackfillState.CONFIRMED)
        val nowMs = ms(12, 0, day = 17)
        // no row: a long break, it is fed
        assertEquals(listOf(k1), plan(m, nowMs = nowMs).toWriter.map { it.key })
        // our "running" row, not changed by the person: the result goes in
        val running = cloudRow(7, ms(10, 1), 2.5, "2026-10-15T10:01:00")
        val fed = plan(m, listOf(running), nowMs = nowMs)
        val result = fed.toWriter.single()
        assertEquals(true, result.completed)
        assertEquals(1.2, result.dose!!, 0.0)
        assertTrue(fed.memory.acceptedAt.isEmpty())
        // the history not read through: a decision waits - not fed, not accepted
        val waits = plan(m, listOf(running), nowMs = nowMs, first = false)
        assertTrue(waits.toWriter.isEmpty())
        assertTrue(waits.memory.acceptedAt.isEmpty())
        // the person changed the dose: not touched, accepted
        val edited = plan(m, listOf(running.copy(amount = 2.0)), nowMs = nowMs)
        assertTrue(edited.toWriter.isEmpty())
        assertEquals(setOf(k1), edited.memory.acceptedAt.keys)
        // 46 h old: still within the writer's memory, fed as usual
        assertEquals(listOf(k1), plan(m, listOf(running.copy(amount = 2.0)), nowMs = ms(8, 1, day = 17)).toWriter.map { it.key })
        // 47.5 h old: decided already (critic B2, C3: an hour short of the writer's 48 h)
        assertTrue(plan(m, listOf(running.copy(amount = 2.0)), nowMs = ms(9, 31, day = 17)).toWriter.isEmpty())
    }

    @Test
    fun programmedBeforeABreakAndDeliveredAfterItGetTheResult() {
        // critic B2, B1: a break of more than 48 h; the catch-up stops between "programmed" and "delivered"
        val part = listOf(ref(100, 3600, 10), programmed(101, 60, 1, 25), programmedDetails(102, 60))
        val m1 = mem(part, enabledAt = ms(12, 0, day = 13), backfill = BackfillState.CONFIRMED)
        val nowMs = ms(12, 0, day = 17)
        val p1 = plan(m1, nowMs = nowMs, first = false)
        val running = p1.toWriter.single() // no row yet: fed running, the programmed dose
        assertEquals(false, running.completed)
        assertTrue(PumpWarning.NO_RESULT_BOLUS in p1.warnings)
        val a1 = PumpHistoryRules.markAccepted(p1.memory, p1.toWriter, nowMs)
        assertTrue(a1.acceptedAt.isEmpty())
        // the writer made the "running" row; the next cycle brings "delivered" and reads through
        val row = cloudRow(7, ms(10, 1), 2.5, "2026-10-15T10:01:00")
        val m2 = a1.copy(records = part + listOf(B2Fixtures.delivered(103, 200, 1, 25), B2Fixtures.deliveredDetails(104, 200)), contiguousTo = 104)
        val p2 = plan(m2, listOf(row), nowMs = nowMs + 60_000)
        val done = p2.toWriter.single()
        assertEquals(k1, done.key)
        assertEquals(true, done.completed)
        assertEquals(2.5, done.deliveredUnits!!, 0.0)
        assertEquals(setOf(k1), PumpHistoryRules.markAccepted(p2.memory, p2.toWriter, nowMs).acceptedAt.keys)
    }

    @Test
    fun aMealGoesTheWayOfItsBolus() {
        // critic B2, C1: a long break, the meal (2 s before its bolus) comes in one cycle, the bolus in the next
        val withMeal = listOf(ref(100, 3600, 10), meal(101, 58, 40))
        val m1 = mem(withMeal, enabledAt = ms(12, 0, day = 13), backfill = BackfillState.CONFIRMED)
        val nowMs = ms(12, 0, day = 16)
        val p1 = plan(m1, nowMs = nowMs, first = false)
        assertEquals(listOf("MEAL|2026-10-15T10:00:58|0"), p1.toWriter.map { it.key })
        val a1 = PumpHistoryRules.markAccepted(p1.memory, p1.toWriter, nowMs)
        assertTrue(a1.acceptedAt.isEmpty()) // its bolus was not fed with it
        val m2 = a1.copy(records = withMeal + bolus(102, 60, id = 1, tenths = 25), contiguousTo = 105)
        val p2 = plan(m2, nowMs = nowMs)
        assertEquals(listOf("MEAL|2026-10-15T10:00:58|0", k1), p2.toWriter.map { it.key })
        assertEquals(setOf("MEAL|2026-10-15T10:00:58|0", k1), PumpHistoryRules.markAccepted(p2.memory, p2.toWriter, nowMs).acceptedAt.keys)

        // its bolus accepted earlier and no longer fed: the meal is accepted with it, not fed alone
        val p3 = plan(m2.copy(acceptedAt = mapOf(k1 to 1L)), nowMs = nowMs)
        assertTrue(p3.toWriter.isEmpty())
        assertTrue("MEAL|2026-10-15T10:00:58|0" in p3.memory.acceptedAt)

        // no bolus at all: accepted on a history read through, not before
        val alone = mem(listOf(ref(100, 3600, 10), meal(101, 1800, 20)), enabledAt = ms(9, 0), backfill = BackfillState.CONFIRMED)
        assertTrue(plan(alone, nowMs = ms(10, 35), first = false).memory.acceptedAt.isEmpty())
        assertTrue(plan(alone, nowMs = ms(10, 35)).memory.acceptedAt.isEmpty()) // 5 min: its bolus may still come
        assertEquals(setOf("MEAL|2026-10-15T10:30:00|0"), plan(alone, nowMs = ms(10, 45)).memory.acceptedAt.keys)
    }

    @Test
    fun theCardDoesNotPromiseAMealWhoseBolusIsThePersons() {
        val recs = listOf(ref(100, 3600, 10), meal(101, 58, 40)) + bolus(102, 60, id = 1, tenths = 25)
        val person = JournalRow(8, ClKind.BOLUS, ms(10, 1, 20), 2.5, null)
        val p = plan(mem(recs), listOf(person))
        assertTrue(p.card!!.toWrite.isEmpty())
        assertEquals(2, p.card!!.alreadyInJournal) // the bolus and its meal
        // the bolus listed: the meal is listed with it
        assertEquals(listOf("MEAL|2026-10-15T10:00:58|0", k1), plan(mem(recs)).card!!.toWrite.map { it.key })
        // the bolus at the boundary (not later): neither is written
        val before = plan(mem(recs, boundaryMs = ms(10, 1)))
        assertTrue(before.card!!.toWrite.isEmpty())
        assertEquals(2, before.card!!.oldNotFound)
    }

    @Test
    fun aBolusTimedByDeliveryFindsTheCloudRowAtItsRequest() {
        // critic B2, C4: "programmed" (101) lost; "delivered" at 10:06:40 dated back from the reference at 11:00
        val recs = listOf(ref(100, 3600, 10), B2Fixtures.delivered(102, 400, 1, 25), ref(103, 3600, 11))
        val m = mem(recs, cur = 103)
        val b = B2Fixtures.build(m).events.filterIsInstance<BolusEvent>().single()
        assertTrue(b.timeFromDelivery)
        val row = cloudRow(7, ms(10, 1), 2.5, "2026-10-15T10:01:00") // the cloud wrote it at the request
        val p = plan(m, listOf(row))
        assertEquals(1, p.matchedByTime)
        assertTrue(p.card!!.toWrite.isEmpty())
        assertEquals(mapOf("INSULIN|2026-10-15T10:06:40|0" to k1), p.memory.aliases)
        // far before the delivery minus 10 min: not the same bolus
        assertEquals(1, plan(m, listOf(row.copy(atMs = ms(9, 56)))).card!!.toWrite.size)
    }

    @Test
    fun aBolusFedBeforeWhoseRowIsGoneIsNotMadeAgain() {
        // critic B2, N-a / N-b: fed "running" on the 15th, then the person deleted its row; a break of the link
        val part = listOf(ref(100, 3600, 10), programmed(101, 60, 1, 25), programmedDetails(102, 60))
        val m1 = mem(part, enabledAt = ms(12, 0, day = 13), backfill = BackfillState.CONFIRMED)
        val p1 = plan(m1, nowMs = ms(10, 5))
        assertEquals(listOf(k1), p1.toWriter.map { it.key })
        val a1 = PumpHistoryRules.markAccepted(p1.memory, p1.toWriter, ms(10, 5))
        assertEquals(setOf(k1), a1.fedBoluses)
        assertTrue(a1.acceptedAt.isEmpty())
        val full = part + listOf(B2Fixtures.delivered(103, 200, 1, 25), B2Fixtures.deliveredDetails(104, 200))
        val later = a1.copy(records = full, contiguousTo = 104)
        // within 47 h the writer still remembers the deletion: fed as usual
        assertEquals(listOf(k1), plan(later, nowMs = ms(12, 0, day = 16)).toWriter.map { it.key })
        // older than 47 h, no row under its key: accepted, never made again (on any read)
        for (first in listOf(true, false)) {
            val p = plan(later, nowMs = ms(12, 0, day = 17), first = first)
            assertTrue(p.toWriter.isEmpty())
            assertEquals(setOf(k1), p.memory.acceptedAt.keys)
        }
        // never fed before: a long break, fed
        assertEquals(listOf(k1), plan(later.copy(fedBoluses = emptySet()), nowMs = ms(12, 0, day = 17)).toWriter.map { it.key })
    }

    @Test
    fun aCancelledBolusTakesItsUntouchedCarbsAlong() {
        // critic B2, N-c: 2.5 U programmed, cancelled at 0; written "running" with its 40 g before a long break
        val recs = listOf(ref(100, 3600, 10), meal(101, 58, 40), programmed(102, 60, 1, 25), programmedDetails(103, 60),
            B2Fixtures.delivered(104, 200, 1, 0), B2Fixtures.deliveredDetails(105, 200))
        val m = mem(recs, enabledAt = ms(12, 0, day = 13), backfill = BackfillState.CONFIRMED)
        val nowMs = ms(12, 0, day = 17)
        val bolusRow = cloudRow(7, ms(10, 1), 2.5, "2026-10-15T10:01:00")
        val carbsRow = JournalRow(8, ClKind.MEAL, ms(10, 0, 58), 40.0, "carelink:MEAL:2026-10-15T10:00:58:0:carbs")
        val p = plan(m, listOf(bolusRow, carbsRow), nowMs = nowMs)
        assertEquals(listOf("MEAL|2026-10-15T10:00:58|0", k1), p.toWriter.map { it.key })
        assertEquals(0.0, p.toWriter.last().dose!!, 0.0)
        // the person changed the carbs: they stay, the bolus still gets its result
        val edited = plan(m, listOf(bolusRow, carbsRow.copy(amount = 30.0)), nowMs = nowMs)
        assertEquals(listOf(k1), edited.toWriter.map { it.key })
        assertTrue("MEAL|2026-10-15T10:00:58|0" in edited.memory.acceptedAt)
    }

    @Test
    fun theCardListsNoMealThatWillNotBeWritten() {
        // critic B2, N-g: a stopped bolus (2.5 programmed, 1.2 delivered) the cloud wrote with its result, and its meal
        val recs = listOf(ref(100, 3600, 10), meal(101, 58, 40), programmed(102, 60, 1, 25), programmedDetails(103, 60),
            B2Fixtures.delivered(104, 200, 1, 12), B2Fixtures.deliveredDetails(105, 200))
        val row = cloudRow(7, ms(10, 1), 1.2, "2026-10-15T10:01:00")
        // enabled two days later: the bolus is older than 47 h, its row is not our "running" one - accepted, not fed;
        // its meal would never be written
        val late = plan(mem(recs, enabledAt = ms(11, 0, day = 17), boundaryMs = ms(9, 0)), listOf(row), nowMs = ms(12, 0, day = 17))
        assertTrue(late.card!!.toWrite.isEmpty())
        assertEquals(1, late.card!!.mealsNotWritten)
        // enabled the same day: the bolus is fed for its result, the meal is listed
        val soon = plan(mem(recs), listOf(row))
        assertEquals(listOf("MEAL|2026-10-15T10:00:58|0"), soon.card!!.toWrite.map { it.key })
        assertEquals(0, soon.card!!.mealsNotWritten)
        // the carbs switch off: no meal listed
        val noCarbs = PumpBtBridge.planCycle(mem(recs), B2Fixtures.build(mem(recs)), emptyList(), now, ZONE, true, writeCarbs = false)
        assertEquals(listOf(k1), noCarbs.card!!.toWrite.map { it.key })
        assertEquals(1, noCarbs.card!!.mealsNotWritten)
    }

    @Test
    fun aSquareBolusTimedByDeliveryTakesTheRowNearestItsRequest() {
        // critic B2, N-d: a 2 h square bolus, "programmed" lost, delivered at 12:00 -> requested about 10:00
        val recs = listOf(ref(100, 3600, 11), B2Fixtures.delivered(102, 3600, 1, 0, ext = 25, minutes = 120, type = 0x3C), ref(103, 3600, 12))
        val m = mem(recs, enabledAt = ms(14, 0), cur = 103)
        val b = B2Fixtures.build(m).events.filterIsInstance<BolusEvent>().single()
        assertTrue(b.timeFromDelivery)
        assertEquals(at(12, 0), b.at)
        val pen = JournalRow(9, ClKind.BOLUS, ms(11, 50), 2.5, null) // the person's pen near the delivery
        val cloud = cloudRow(7, ms(10, 0, 30), 2.5, "2026-10-15T10:00:30")
        val p = plan(m, listOf(pen, cloud), nowMs = ms(14, 30))
        assertEquals(mapOf("INSULIN|2026-10-15T12:00:00|0" to "INSULIN|2026-10-15T10:00:30|0"), p.memory.aliases)
    }

    @Test
    fun aCardWithAKeyGoneIsMadeAgain() {
        // critic B2, N1: the card listed a key no event has now
        val m = mem(oneOldBolus(), backfill = BackfillState.WAITING, shown = listOf("INSULIN|2026-10-15T10:00:59|0"))
        val p = plan(m)
        assertEquals(BackfillState.WAITING, p.memory.backfill)
        assertEquals(listOf(k1), p.memory.backfillShown)
        assertEquals(listOf(k1), p.card!!.toWrite.map { it.key })
        assertNull(PumpBtBridge.confirmBackfill(p.memory, listOf("INSULIN|2026-10-15T10:00:59|0")))
        assertEquals(BackfillState.CONFIRMED, PumpBtBridge.confirmBackfill(p.memory, listOf(k1))!!.backfill)
    }

    @Test
    fun anEventFromTheFutureIsNotFed() {
        // pump clock ahead: a bolus at 12:45 and one at 12:35, now 12:30
        val recs = listOf(ref(100, 3600, 12)) + bolus(101, 2700, id = 1, tenths = 10) + bolus(105, 2100, id = 2, tenths = 20)
        val p = plan(mem(recs, enabledAt = ms(9, 0)))
        assertEquals(listOf("INSULIN|2026-10-15T12:35:00|0"), p.toWriter.map { it.key })
        assertTrue(PumpWarning.FUTURE_EVENT in p.warnings)
        assertEquals(1, p.memory.counters.futureEvents)
        // and it is not accepted
        assertTrue(PumpHistoryRules.markAccepted(p.memory, p.toWriter, now).acceptedAt.keys.none { it.contains("12:45") })
    }

    // --- the first catch-up -------------------------------------------------------------------------------------------

    @Test
    fun noCardUntilTheHistoryIsReadThrough() {
        val p = plan(mem(oneOldBolus()), first = false)
        assertNull(p.card)
        assertEquals(BackfillState.NOT_READY, p.memory.backfill)
        assertTrue(p.toWriter.isEmpty())
        assertNull(PumpBtBridge.confirmBackfill(p.memory, emptyList()))
    }

    @Test
    fun nothingOldConfirmsAtOnce() {
        val p = plan(mem(oneOldBolus(), enabledAt = ms(9, 0)))
        assertNull(p.card)
        assertEquals(BackfillState.CONFIRMED, p.memory.backfill)
        assertEquals(listOf(k1), p.toWriter.map { it.key })
    }

    @Test
    fun aCloudRowGivesItsKey() {
        val row = cloudRow(7, ms(10, 1, 40), 2.5, "2026-10-15T10:01:40")
        val p = plan(mem(oneOldBolus()), listOf(row))
        val card = p.card!!
        assertEquals(1, card.bolusesBefore)
        assertEquals(0, card.mealsBefore)
        assertEquals(1, card.alreadyInJournal)
        assertTrue(card.toWrite.isEmpty())
        assertEquals(0, card.oldNotFound)
        assertEquals(1, p.matchedByTime)
        assertEquals(0, p.matchedByKey)
        assertEquals(0, p.fresh)
        assertEquals(mapOf(k1 to "INSULIN|2026-10-15T10:01:40|0"), p.memory.aliases)
        assertTrue(p.toWriter.isEmpty()) // the card waits
        // answered: the bolus goes on under the cloud row's key (its row is there - a result reaches it, no new row)
        val c = PumpBtBridge.confirmBackfill(p.memory, emptyList())!!
        val fed = plan(c, listOf(row)).toWriter.single()
        assertEquals("INSULIN|2026-10-15T10:01:40|0", fed.key)
        assertEquals(ms(10, 1), fed.atMs)
        val accepted = PumpHistoryRules.markAccepted(c, listOf(fed), now)
        assertEquals(setOf("INSULIN|2026-10-15T10:01:40|0"), accepted.acceptedAt.keys)
        assertTrue(accepted.backfillWritten.isEmpty()) // no row was made by the catch-up
    }

    @Test
    fun aRowOfThePersonMeansTheEventIsNotWritten() {
        val person = JournalRow(8, ClKind.BOLUS, ms(10, 2, 30), 2.5, null)
        val p = plan(mem(oneOldBolus()), listOf(person))
        assertEquals(1, p.card!!.alreadyInJournal)
        assertTrue(p.card!!.toWrite.isEmpty())
        assertTrue(p.memory.aliases.isEmpty())
        val c = PumpBtBridge.confirmBackfill(p.memory, emptyList())!!
        assertTrue(plan(c, listOf(person)).toWriter.isEmpty())
        // another dose, or outside the window: not the same bolus
        assertEquals(listOf(k1), plan(mem(oneOldBolus()), listOf(person.copy(amount = 2.0))).card!!.toWrite.map { it.key })
        assertEquals(listOf(k1), plan(mem(oneOldBolus()), listOf(person.copy(atMs = ms(10, 4, 31)))).card!!.toWrite.map { it.key })
        // a dose within the tolerance (the pump's number against the journal's decimal) is the same
        assertEquals(1, plan(mem(oneOldBolus()), listOf(person.copy(amount = 2.5 + 0.004))).card!!.alreadyInJournal)
    }

    @Test
    fun twoSameBolusesInOneCycleTakeOneRowEach() {
        // 10:01 and 10:04, both 2.5 U
        val recs = oneOldBolus() + bolus(105, 240, id = 2, tenths = 25)
        val k2 = "INSULIN|2026-10-15T10:04:00|0"
        val one = plan(mem(recs), listOf(cloudRow(7, ms(10, 1, 30), 2.5, "2026-10-15T10:01:30")))
        assertEquals(mapOf(k1 to "INSULIN|2026-10-15T10:01:30|0"), one.memory.aliases)
        assertEquals(listOf(k2), one.card!!.toWrite.map { it.key })
        assertEquals(1, one.matchedByTime)
        assertEquals(1, one.fresh)
        // two rows, given in reverse order, both in the first bolus' window: each bolus takes its nearest
        val rows = listOf(cloudRow(9, ms(10, 4, 10), 2.5, "2026-10-15T10:04:10"), cloudRow(7, ms(10, 1, 30), 2.5, "2026-10-15T10:01:30"))
        val two = plan(mem(recs), rows)
        assertEquals(mapOf(k1 to "INSULIN|2026-10-15T10:01:30|0", k2 to "INSULIN|2026-10-15T10:04:10|0"), two.memory.aliases)
        assertTrue(two.card!!.toWrite.isEmpty())
        assertEquals(2, two.card!!.alreadyInJournal)
    }

    @Test
    fun theExactKeyComesBeforeTheNearestTime() {
        // the second bolus' own row lies in the first one's window and nearer to it than any other: still the second's
        val recs = oneOldBolus() + bolus(105, 120, id = 2, tenths = 25) // 10:02
        val k2 = "INSULIN|2026-10-15T10:02:00|0"
        val own = cloudRow(9, ms(10, 2), 2.5, "2026-10-15T10:02:00")
        val p = plan(mem(recs), listOf(own))
        assertEquals(1, p.matchedByKey)
        assertEquals(0, p.matchedByTime)
        assertTrue(p.memory.aliases.isEmpty())
        assertEquals(listOf(k1), p.card!!.toWrite.map { it.key })
        // after "Write": the first is fed for a new row, the second (its row is ours) to get its result
        val c = PumpBtBridge.confirmBackfill(p.memory, listOf(k1))!!
        assertEquals(listOf(k1, k2), plan(c, listOf(own)).toWriter.map { it.key })
    }

    @Test
    fun sameBolusesInDifferentCyclesAfterTheEnableAreBothWritten() {
        // after the enable only the exact key counts: a second 2.5 U bolus 30 s after the first is not "matched"
        val first = listOf(ref(100, 3600, 12)) + bolus(101, 300, id = 1, tenths = 25)
        val p1 = plan(mem(first, backfill = BackfillState.CONFIRMED))
        val a1 = PumpHistoryRules.markAccepted(p1.memory, p1.toWriter, now)
        val firstRow = cloudRow(7, ms(12, 5), 2.5, "2026-10-15T12:05:00")
        val second = first + bolus(105, 330, id = 2, tenths = 25)
        val p2 = plan(a1.copy(records = second, contiguousTo = 108), listOf(firstRow))
        assertEquals(listOf("INSULIN|2026-10-15T12:05:00|0", "INSULIN|2026-10-15T12:05:30|0"), p2.toWriter.map { it.key })
        assertTrue(p2.memory.aliases.isEmpty())
    }

    @Test
    fun theBoundary() {
        // before the boundary and not in the journal: counted, not written
        val p = plan(mem(oneOldBolus(), boundaryMs = ms(10, 30)))
        assertEquals(1, p.card!!.oldNotFound)
        assertTrue(p.card!!.toWrite.isEmpty())
        val c = PumpBtBridge.confirmBackfill(p.memory, emptyList())!!
        assertTrue(plan(c).toWriter.isEmpty())
        // after the boundary: listed
        assertEquals(listOf(k1), plan(mem(oneOldBolus(), boundaryMs = ms(10, 0))).card!!.toWrite.map { it.key })
    }

    @Test
    fun writeWritesExactlyWhatWasShown() {
        // the card listed only the 10:01 bolus; a second old one (10:04) is in the memory now
        val recs = oneOldBolus() + bolus(105, 240, id = 2, tenths = 15)
        val m = mem(recs, backfill = BackfillState.WAITING, shown = listOf(k1))
        val p = plan(m)
        assertEquals(listOf(k1), p.card!!.toWrite.map { it.key })
        assertEquals(2.5, p.card!!.toWrite.single().units!!, 0.0)
        assertEquals(0, p.fresh) // the matching result is reported only once
        // another list than the one shown: show again
        assertNull(PumpBtBridge.confirmBackfill(p.memory, listOf(k1, "INSULIN|2026-10-15T10:04:00|0")))
        assertNull(PumpBtBridge.confirmBackfill(p.memory, emptyList()))
        val c = PumpBtBridge.confirmBackfill(p.memory, listOf(k1))!!
        assertEquals(BackfillState.CONFIRMED, c.backfill)
        assertEquals(c, PumpBtBridge.confirmBackfill(c, listOf(k1))) // pressed twice
        assertEquals(listOf(k1), plan(c).toWriter.map { it.key })
        // written: the row key is kept for a roll-back, and the bolus is no longer fed
        val a = PumpHistoryRules.markAccepted(c, plan(c).toWriter, now)
        assertEquals(listOf("carelink:INSULIN:2026-10-15T10:01:00:0:insulin"), a.backfillWritten)
        assertTrue(plan(a).toWriter.isEmpty())
    }

    @Test
    fun aNewEnableMakesANewCardOnlyForWhatWasNotAccepted() {
        // the 10:01 bolus was accepted in the first run; the 10:04 one was written then too but was still running
        // (its row is there under its own key); the 10:30 one was never written
        val recs = oneOldBolus() + listOf(programmed(105, 240, 2, 15), programmedDetails(106, 240), B2Fixtures.delivered(107, 600, 2, 15), B2Fixtures.deliveredDetails(108, 600)) +
            bolus(109, 1800, id = 3, tenths = 10)
        val k2 = "INSULIN|2026-10-15T10:04:00|0"
        val k3 = "INSULIN|2026-10-15T10:30:00|0"
        val firstRun = mem(recs, enabledAt = ms(9, 0), backfill = BackfillState.CONFIRMED).copy(acceptedAt = mapOf(k1 to 1L))
        val again = PumpHistoryRules.enable(firstRun, enabled, ms(10, 10))
        val row2 = cloudRow(9, ms(10, 4), 1.5, "2026-10-15T10:04:00")
        val p = plan(again, listOf(row2))
        val card = p.card!!
        assertEquals(2, card.bolusesBefore) // the accepted one is not asked again
        assertEquals(1, card.alreadyInJournal)
        assertEquals(1, p.matchedByKey)
        assertEquals(listOf(k3), card.toWrite.map { it.key })
        val c = PumpBtBridge.confirmBackfill(p.memory, listOf(k3))!!
        assertEquals(listOf(k2, k3), plan(c, listOf(row2)).toWriter.map { it.key })
    }

    @Test
    fun declinedOldOnesAreNotWrittenButOurOwnRowStillGetsItsResult() {
        val recs = oneOldBolus() + listOf(programmed(105, 240, 2, 15), programmedDetails(106, 240), B2Fixtures.delivered(107, 600, 2, 15), B2Fixtures.deliveredDetails(108, 600))
        val own = cloudRow(9, ms(10, 4), 1.5, "2026-10-15T10:04:00")
        val p = plan(mem(recs), listOf(own))
        assertEquals(listOf(k1), p.card!!.toWrite.map { it.key })
        val d = PumpBtBridge.declineBackfill(p.memory)
        assertEquals(BackfillState.DECLINED, d.backfill)
        assertEquals(enabled, d.boundaryMs)
        assertTrue(d.backfillShown.isEmpty())
        assertEquals(listOf("INSULIN|2026-10-15T10:04:00|0"), plan(d, listOf(own)).toWriter.map { it.key })
    }

    // --- warnings -----------------------------------------------------------------------------------------------------

    @Test
    fun warningsAndCounters() {
        val running = mem(listOf(ref(100, 3600, 10), programmed(101, 60, 1, 25), programmedDetails(102, 60)), enabledAt = ms(9, 0))
        val late = plan(running, nowMs = ms(10, 40), first = false)
        assertTrue(PumpWarning.NO_RESULT_BOLUS in late.warnings)
        assertEquals(1, late.memory.counters.noResultBoluses)
        assertEquals(listOf(k1), late.toWriter.map { it.key }) // the row stays as it is
        assertFalse(PumpWarning.NO_RESULT_BOLUS in plan(running, nowMs = ms(10, 20), first = false).warnings)

        // a meal 2 s before its bolus is paired; one at 10:30 is not
        val meals = mem(oneOldBolus() + listOf(meal(105, 58, 30), meal(106, 1800, 20)), enabledAt = ms(9, 0))
        val pm = plan(meals, first = false)
        assertTrue(PumpWarning.UNPAIRED_MEAL in pm.warnings)
        assertEquals(1, pm.memory.counters.unpairedMeals)

        // "delivered" after a hole the number has not passed: held
        val held = mem(listOf(ref(100, 3600, 10), B2Fixtures.delivered(102, 200, 1, 25)), enabledAt = ms(9, 0), cur = 100)
        val ph = plan(held, first = false)
        assertTrue(PumpWarning.HELD_BOLUS in ph.warnings)
        assertEquals(1, ph.memory.counters.heldBoluses)
        assertTrue(ph.toWriter.isEmpty())

        // the last reference 10:00: read at 12:00 (history read through) - clocks apart; at 10:30 - fine
        val m = mem(oneOldBolus(), enabledAt = ms(9, 0))
        assertTrue(PumpWarning.CLOCK_DRIFT in plan(m, nowMs = ms(12, 0)).warnings)
        assertFalse(PumpWarning.CLOCK_DRIFT in plan(m, nowMs = ms(10, 30)).warnings)
        assertFalse(PumpWarning.CLOCK_DRIFT in plan(m, nowMs = ms(12, 0), first = false).warnings)
        assertTrue(PumpWarning.CLOCK_DRIFT in plan(m, nowMs = ms(9, 59)).warnings) // pump ahead of the phone
        assertTrue(plan(m, nowMs = ms(10, 30)).warnings.isEmpty())
    }
}

/** PumpBtBridge.boundary and journalRows: the journal through the loaders, the cloud state in its preferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PumpBtBridgeJournalTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val enabled = ms(12, 0)

    @After
    fun tearDown() = PumpBtBridge.resetForTest()

    private fun entity(id: Long, atMs: Long, type: String, key: String?) = JournalEntryEntity(
        id = id, timestamp = atMs, sensorSerial = null, entryType = type, title = "t", note = null, amount = 1f,
        glucoseValueMgDl = null, durationMinutes = null, intensity = null, insulinPresetId = null, source = "api",
        sourceRecordId = key, createdAt = atMs, updatedAt = atMs,
    )

    /** The journal holds [r]: the boundary reads the times of its cloud rows in the asked span. */
    private fun rows(vararg r: JournalEntryEntity) {
        PumpBtBridge.loadCloudRowTimes = { _, from, to -> PumpBtBridge.cloudTimes(r.filter { it.timestamp in from..to }) }
    }

    @Test
    fun theBoundaryIsTheLatestCloudRowOrDataTimeMinusTenMinutes() = runBlocking {
        val bolusRow = entity(1, enabled - 3 * 3_600_000L, "insulin", "carelink:INSULIN:2026-10-15T09:00:00:0:insulin")
        val person = entity(2, enabled - 3_600_000L, "insulin", null)
        val tooOld = entity(3, enabled - 11 * 24 * 3_600_000L, "carbs", "carelink:MEAL:2026-10-04T12:00:00:0:carbs")
        val basal = entity(4, enabled - 2 * 3_600_000L, "temp_basal", "carelink:basal:1:0:basal")
        val stop = entity(5, enabled - 90 * 60_000L, "note", "carelink:stop:2:0:note")
        rows(bolusRow, person, tooOld)
        assertEquals(enabled - 3 * 3_600_000L - 600_000L, PumpBtBridge.boundary(app, enabled))
        // basal and stop rows of the cloud count too: the cloud delivered data up to them
        rows(bolusRow, person, tooOld, basal)
        assertEquals(enabled - 2 * 3_600_000L - 600_000L, PumpBtBridge.boundary(app, enabled))
        rows(bolusRow, person, tooOld, basal, stop)
        assertEquals(enabled - 100 * 60_000L, PumpBtBridge.boundary(app, enabled))
        CareLinkStateStore(app).write(CareLinkState(lastDataAtMs = enabled - 30 * 60_000L))
        assertEquals(enabled - 40 * 60_000L, PumpBtBridge.boundary(app, enabled))
    }

    @Test
    fun noCloudTraceGivesTheEnableMinus24Hours() = runBlocking {
        rows(entity(2, enabled - 3_600_000L, "insulin", "manual"))
        assertEquals(enabled - 24 * 3_600_000L, PumpBtBridge.boundary(app, enabled))
        // a failing journal read counts as no rows
        PumpBtBridge.loadCloudRowTimes = { _, _, _ -> throw IllegalStateException("db") }
        assertEquals(enabled - 24 * 3_600_000L, PumpBtBridge.boundary(app, enabled))
        CareLinkStateStore(app).write(CareLinkState(lastDataAtMs = enabled - 5 * 60_000L))
        assertEquals(enabled - 15 * 60_000L, PumpBtBridge.boundary(app, enabled))
    }

    @Test
    fun journalRowsTakesTheSpanAndTheKeys() = runBlocking {
        val recs = listOf(ref(100, 3600, 10)) + bolus(101, 60, id = 1, tenths = 25)
        val m = PumpHistoryMemory(pumpHash = HASH, records = recs, contiguousTo = 104, aliases = mapOf("INSULIN|2026-10-15T10:01:00|0" to "INSULIN|2026-10-15T10:01:40|0"))
        var span: Pair<Long, Long>? = null
        var keys: List<String>? = null
        PumpBtBridge.loadRows = { _, from, to -> span = from to to; listOf(JournalRow(1, ClKind.BOLUS, ms(10, 1), 2.5, null)) }
        PumpBtBridge.loadByKeys = { _, k -> keys = k; listOf(JournalRow(1, ClKind.BOLUS, ms(10, 1), 2.5, null), JournalRow(2, ClKind.BOLUS, ms(9, 0), 2.5, "x")) }
        val got = PumpBtBridge.journalRows(app, m, B2Fixtures.build(m), ZONE)
        assertEquals(ms(10, 1) - 90_000L to ms(10, 3) + 90_000L, span)
        assertEquals(listOf("carelink:INSULIN:2026-10-15T10:01:40:0:insulin"), keys)
        assertEquals(listOf(1L, 2L), got.map { it.id })
        assertNotNull(got)
    }

    @Test
    fun aPercentageTemporaryBasalIsWhatTheCloudsPercentageOneIs() {
        // critic of the joints 1, B-1: normal 0.8 U/h, temporary 150 % - the basal writer takes the same rate from the
        // Bluetooth snapshot as from the cloud's answer with such a temporary basal (1.2 U/h)
        val normal = PumpCycle(readAtMs = 5_000L, serialHash = HASH, therapyState = 0x55, basalRate = 0.8, basalRead = true)
        val bt = PumpBtBridge.toPumpNow(normal.copy(readAtMs = 65_000L, tempBasalRate = 150.0, tempBasalType = 0x3C))!!
        val cloud = CareLinkPumpNow.parse(
            JSONObject(
                """{"patientData": {"lastMedicalDeviceDataUpdateServerTime": 60000, "basal": {"basalRate": 0.8,
                   "tempBasalType": "PERCENTAGE", "tempBasalPercentage": 150, "tempBasalRate": 0}}}""",
            ),
            nowMs = 65_000L, phoneZone = ZONE,
        )!!
        assertEquals(150, cloud.tempBasal!!.percent)
        assertEquals(cloud.tempBasal!!.percent, bt.tempBasal!!.percent)
        val lastNormal = CareLinkBasalWriter.rateOf(PumpBtBridge.toPumpNow(normal)!!, null)
        assertEquals(1.2, CareLinkBasalWriter.rateOf(cloud, lastNormal)!!, 1e-9)
        assertEquals(CareLinkBasalWriter.rateOf(cloud, lastNormal)!!, CareLinkBasalWriter.rateOf(bt, lastNormal)!!, 1e-9)
    }

    @Test
    fun journalRowsReachesBackToTheRequestOfABolusTimedByDelivery() = runBlocking {
        // critic of the joints 1, N-1: the earliest event is a bolus timed by "delivered" (10:10, its "programmed" gone);
        // its cloud row may stand up to 10 min earlier - the rows are loaded from there, not from 10:10 - 90 s
        val recs = listOf(ref(100, 3600, 10), B2Fixtures.delivered(101, 600, id = 1, tenths = 25), B2Fixtures.deliveredDetails(102, 600))
        val m = PumpHistoryMemory(pumpHash = HASH, records = recs, contiguousTo = 102)
        val events = B2Fixtures.build(m)
        assertTrue((events.events.single() as BolusEvent).timeFromDelivery)
        var span: Pair<Long, Long>? = null
        PumpBtBridge.loadRows = { _, from, to -> span = from to to; emptyList() }
        PumpBtBridge.loadByKeys = { _, _ -> emptyList() }
        PumpBtBridge.journalRows(app, m, events, ZONE)
        assertEquals(ms(10, 0) to ms(10, 10) + 90_000L, span)
    }
}
