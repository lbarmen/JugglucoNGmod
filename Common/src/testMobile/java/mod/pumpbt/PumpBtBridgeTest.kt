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
            basalRate = 0.8, basalRead = true, tempBasalRate = 1.2, tempBasalMinutesLeft = 20, batteryPercent = 70,
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
    fun anEventOlderThan48HoursWithARowIsAcceptedNotFed() {
        val m = mem(oneOldBolus(), enabledAt = ms(12, 0, day = 13), boundaryMs = ms(11, 0, day = 13), backfill = BackfillState.CONFIRMED)
        val nowMs = ms(12, 0, day = 17)
        // no row: a long break, it is fed
        assertEquals(listOf(k1), plan(m, nowMs = nowMs).toWriter.map { it.key })
        // its row is there (the person may have edited it): not fed, accepted
        val row = cloudRow(7, ms(10, 1), 2.5, "2026-10-15T10:01:00")
        val p = plan(m, listOf(row), nowMs = nowMs)
        assertTrue(p.toWriter.isEmpty())
        assertEquals(setOf(k1), p.memory.acceptedAt.keys)
        // a bolus still running after two days is not waited for
        val running = mem(listOf(ref(100, 3600, 10), programmed(101, 60, 1, 25)), enabledAt = ms(12, 0, day = 13), backfill = BackfillState.CONFIRMED)
        val pr = plan(running, nowMs = nowMs)
        assertTrue(pr.toWriter.isEmpty())
        assertEquals(setOf(k1), pr.memory.acceptedAt.keys)
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

    private fun rows(vararg r: JournalRow) {
        PumpBtBridge.loadRows = { _, from, to -> r.filter { it.atMs in from..to } }
    }

    @Test
    fun theBoundaryIsTheLatestCloudRowOrDataTimeMinusTenMinutes() = runBlocking {
        rows(
            JournalRow(1, ClKind.BOLUS, enabled - 2 * 3_600_000L, 2.0, "carelink:INSULIN:2026-10-15T10:00:00:0:insulin"),
            JournalRow(2, ClKind.BOLUS, enabled - 3_600_000L, 1.0, null), // the person's
            JournalRow(3, ClKind.MEAL, enabled - 11 * 24 * 3_600_000L, 20.0, "carelink:MEAL:2026-10-04T12:00:00:0:carbs"), // older than 10 days
        )
        assertEquals(enabled - 2 * 3_600_000L - 600_000L, PumpBtBridge.boundary(app, enabled))
        CareLinkStateStore(app).write(CareLinkState(lastDataAtMs = enabled - 30 * 60_000L))
        assertEquals(enabled - 40 * 60_000L, PumpBtBridge.boundary(app, enabled))
    }

    @Test
    fun noCloudTraceGivesTheEnableMinus24Hours() = runBlocking {
        rows(JournalRow(2, ClKind.BOLUS, enabled - 3_600_000L, 1.0, "manual"))
        assertEquals(enabled - 24 * 3_600_000L, PumpBtBridge.boundary(app, enabled))
        // a failing journal read counts as no rows
        PumpBtBridge.loadRows = { _, _, _ -> throw IllegalStateException("db") }
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
}
