package mod.carelink

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import mod.carelink.CareLinkJournalTestEnv.app
import mod.carelink.CareLinkJournalTestEnv.rows
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.drivers.api.ApiIobSnapshot
import tk.glucodata.mod.carelink.CareLinkBasalWriter
import tk.glucodata.mod.carelink.CareLinkIob
import tk.glucodata.mod.carelink.CareLinkPoller
import tk.glucodata.mod.carelink.CareLinkSettings
import tk.glucodata.mod.carelink.PumpNow
import tk.glucodata.mod.carelink.TempBasal
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * JugglucoNGmod, CareLink 4d: the pump's basal as TEMP_BASAL segments in upstream's real journal - a rate change
 * closes and opens, the running segment is written ahead and moved on, a gap closes at the last data, a stop writes 0
 * and a note, a percentage temporary basal by the last normal rate (none - nothing), an absolute one without
 * `basalRate`, source by the switch; the pump's active insulin into upstream's IoB snapshot (a time from the future
 * dropped). Made-up rates and times only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CareLinkJournalTestApp::class, shadows = [CareLinkTestNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkBasalWriterTest {

    private val now = 1_800_000_000_000L
    private val t0 = now - 3 * 3_600_000L
    private val min = 60_000L

    @Before
    fun setUp() {
        CareLinkJournalTestEnv.setUp { now }
        ApiIobSnapshot.clear()
    }

    @After
    fun tearDown() {
        CareLinkJournalTestEnv.tearDown()
        ApiIobSnapshot.clear()
    }

    private fun write(p: PumpNow?) = runBlocking { CareLinkBasalWriter.apply(app, p, now) }

    private fun at(m: Long, rate: Double? = 0.8, temp: TempBasal? = null, suspended: Boolean? = false) =
        PumpNow(t0 + m * min, basalRate = rate, tempBasal = temp, suspended = suspended, basalKnown = true)

    private fun basal(): List<JournalEntryEntity> =
        rows(JournalEntryType.TEMP_BASAL.storageValue).sortedBy { it.timestamp }

    private fun notes() = rows(JournalEntryType.NOTE.storageValue)

    private fun JournalEntryEntity.startMin() = (timestamp - t0) / min

    private fun hhmm(m: Long) =
        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(t0 + m * min))

    @Test
    fun rateChangeClosesTheSegmentAndTheRunningOneIsWrittenAhead() {
        write(at(0))
        assertEquals(30, basal().single().durationMinutes)
        assertEquals(0.8f, basal().single().amount!!, 1e-6f)
        assertEquals("carelink:basal:$t0:0:basal", basal().single().sourceRecordId)
        write(at(5))
        assertEquals("25 min left - not moved", 30, basal().single().durationMinutes)
        write(at(25))
        assertEquals("5 min left - moved 30 min past the data", 55, basal().single().durationMinutes)
        write(at(30, rate = 1.05))
        val b = basal()
        assertEquals(2, b.size)
        assertEquals("closed at the change", 30, b[0].durationMinutes)
        assertEquals(30L, b[1].startMin())
        assertEquals(1.05f, b[1].amount!!, 1e-6f)
        assertEquals(30, b[1].durationMinutes)
        assertEquals("switch off - API", JournalEntrySource.API.storageValue, b[1].source)
    }

    @Test
    fun emptyAnswerIsAGapNotAContinuation() {
        write(at(0))
        write(at(5))
        write(null)
        assertEquals("closed at the last data", 5, basal().single().durationMinutes)
        write(at(40))
        val b = basal()
        assertEquals(2, b.size)
        assertEquals(40L, b[1].startMin())
    }

    @Test
    fun longPauseBetweenSnapshotsIsAGap() {
        write(at(0))
        write(at(10))
        write(at(45))
        val b = basal()
        assertEquals(2, b.size)
        assertEquals("closed at its last data", 10, b[0].durationMinutes)
        assertEquals(45L, b[1].startMin())
    }

    @Test
    fun noBasalObjectIsAGap() {
        write(at(0))
        write(at(5))
        write(PumpNow(t0 + 10 * min, reservoirUnits = 100.0, basalKnown = false))
        assertEquals(5, basal().single().durationMinutes)
    }

    @Test
    fun stopWritesZeroAndANoteWhoseEndComesWithTheRestart() {
        write(at(0))
        write(at(10, rate = null, suspended = true))
        val note = notes().single()
        assertEquals("Pump stopped", note.title)
        assertEquals("Stop ${hhmm(10)}–…", note.note)
        assertEquals(t0 + 10 * min, note.timestamp)
        write(at(25, rate = null, suspended = true))
        write(at(40, rate = 0.8, suspended = false))
        val b = basal()
        assertEquals(3, b.size)
        assertEquals(10, b[0].durationMinutes)
        assertEquals(0.0f, b[1].amount!!, 1e-6f)
        assertEquals(30, b[1].durationMinutes)
        assertEquals(40L, b[2].startMin())
        assertEquals("Stop ${hhmm(10)}–${hhmm(40)}", notes().single().note)
        // upstream's chart chip of a note is its first 10 characters (journalMarkerDetail): the word and the start time
        // fit, the end does not (dispatcher 17.11: "12:02–12:2" was cut in the middle)
        assertEquals("Stop ${hhmm(10)}", notes().single().note!!.take(10))
    }

    @Test
    fun percentageTemporaryBasalUsesTheLastNormalRate() {
        write(at(0, rate = 1.0))
        write(at(5, rate = 1.0, temp = TempBasal(0.0, 80, 60)))
        val b = basal()
        assertEquals(2, b.size)
        assertEquals(0.8f, b[1].amount!!, 1e-6f)
    }

    @Test
    fun percentageTemporaryBasalWithoutANormalRateIsAGap() {
        write(at(0, rate = 0.7, temp = TempBasal(0.0, 80, 60)))
        assertTrue(basal().isEmpty())
    }

    @Test
    fun absoluteTemporaryBasalWithoutBasalRate() {
        // critic 4c, C4: the basal object came without basalRate - the temporary basal is not lost
        write(at(0, rate = null, temp = TempBasal(0.4, null, 30)))
        assertEquals(0.4f, basal().single().amount!!, 1e-6f)
    }

    @Test
    fun sourceFollowsTheSwitch() {
        CareLinkSettings(app).sendToNightscout = true
        write(at(0))
        write(at(10, rate = null, suspended = true))
        assertTrue(basal().all { it.source == JournalEntrySource.HEALTH_CONNECT.storageValue })
        assertEquals(JournalEntrySource.HEALTH_CONNECT.storageValue, notes().single().source)
    }

    @Test
    fun durationIsAtLeastOneMinute() {
        // critic 4d, N8: a real 20-second segment (two snapshots 20 s apart with different rates), not the same key twice
        write(at(0))
        write(PumpNow(t0 + 20_000L, basalRate = 1.2, suspended = false, basalKnown = true))
        val b = basal()
        assertEquals(2, b.size)
        assertEquals("20 s - rounded up to 1 min", 1, b[0].durationMinutes)
        assertEquals(30, b[1].durationMinutes)
    }

    @Test
    fun temporaryBasalWithoutANumber() {
        // critic 4d, C2: absolute without tempBasalRate - basalRate; type unknown and 0 - not believed; absolute 0 - 0
        val base = PumpNow(t0, basalRate = 0.9, suspended = false, basalKnown = true)
        assertEquals(0.9, CareLinkBasalWriter.rateOf(base.copy(tempBasal = TempBasal(null, null, 30, absolute = true)), null)!!, 1e-9)
        assertEquals(0.9, CareLinkBasalWriter.rateOf(base.copy(tempBasal = TempBasal(0.0, null, 30)), null)!!, 1e-9)
        assertEquals(0.4, CareLinkBasalWriter.rateOf(base.copy(tempBasal = TempBasal(0.4, null, 30)), null)!!, 1e-9)
        assertEquals(0.0, CareLinkBasalWriter.rateOf(base.copy(tempBasal = TempBasal(0.0, null, 30, absolute = true)), null)!!, 1e-9)
        // and in the journal: an absolute temporary basal without its number is no gap
        write(at(0, rate = 0.9, temp = TempBasal(null, null, 30, absolute = true)))
        assertEquals(0.9f, basal().single().amount!!, 1e-6f)
    }

    @Test
    fun percentageTemporaryBasalAfterAGapInTheDataIsAGap() {
        // critic 4d, C3: the normal rate of before a night without data is not the base of a percentage one
        write(at(0, rate = 1.0))
        write(at(10, rate = 1.0))
        write(at(45, rate = 1.0, temp = TempBasal(0.0, 80, 60)))
        assertEquals("only the first segment", 1, basal().size)
        assertEquals("closed at its last data", 10, basal().single().durationMinutes)
    }

    @Test
    fun percentageTemporaryBasalOfHoursKeepsItsBase() {
        write(at(0, rate = 1.0))
        for (m in 5L..180L step 5L) write(at(m, rate = 1.0, temp = TempBasal(0.0, 80, 600)))
        val b = basal()
        assertEquals(2, b.size)
        assertEquals(0.8f, b[1].amount!!, 1e-6f)
        assertTrue("runs on to the last data", b[1].durationMinutes!! >= 175)
    }

    @Test
    fun signOutClosesTheSegmentAndTheStopNoteAtTheirLastData() {
        // critic of the joints, C2; critic 4d, N5
        write(at(0))
        write(at(10, rate = null, suspended = true))
        write(at(15, rate = null, suspended = true))
        runBlocking { CareLinkBasalWriter.close(app, now) }
        val b = basal()
        assertEquals(2, b.size)
        assertEquals(10, b[0].durationMinutes)
        assertEquals("the stop segment ends at its last data, not 30 min ahead", 5, b[1].durationMinutes)
        assertEquals("Stop ${hhmm(10)}–${hhmm(15)}", notes().single().note)
        // the next sign-in: a new segment, the old note stays as it was
        write(at(160))
        assertEquals(3, basal().size)
        assertEquals("Stop ${hhmm(10)}–${hhmm(15)}", notes().single().note)
    }

    @Test
    fun stopNoteAfterAGapEndsAtTheLastStoppedSnapshot() {
        write(at(0))
        write(at(10, rate = null, suspended = true))
        write(at(15, rate = null, suspended = true))
        write(at(100, rate = 0.8, suspended = false))
        assertEquals("not the time of the next data hours later", "Stop ${hhmm(10)}–${hhmm(15)}", notes().single().note)
    }

    @Test
    fun runningAgainWithoutTheBasalAnswerEndsTheStop() {
        // pump over Bluetooth, critic of the joints 1 (P-N1): "delivery runs" without the basal answer closes the stop
        // note with this cycle; the next segment starts only with a basal answer
        write(at(0, rate = null, suspended = true))
        write(at(5, rate = null, suspended = true))
        runBlocking { CareLinkBasalWriter.endStop(app, PumpNow(t0 + 10 * min, suspended = false, basalKnown = false), now) }
        assertEquals("Stop ${hhmm(0)}–${hhmm(10)}", notes().single().note)
        assertEquals("0 U/h closed at its last data", 5, basal().single().durationMinutes)
        write(at(15))
        assertEquals(15L, basal().last().startMin())
        // nothing open: nothing written
        runBlocking { CareLinkBasalWriter.endStop(app, PumpNow(t0 + 20 * min, suspended = false, basalKnown = false), now) }
        assertEquals(2, basal().size)
    }

    @Test
    fun stopNoteEndingOnAnotherDayHasTheDate() {
        // t0 is 05:00 UTC: 23:55 to 00:05 of the next day
        write(at(1135, rate = null, suspended = true))
        write(at(1140, rate = null, suspended = true))
        write(at(1145, rate = 0.8, suspended = false))
        val end = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(t0 + 1145 * min))
        assertEquals("Stop ${hhmm(1135)}–$end", notes().single().note)
        assertTrue(end.endsWith("00:05"))
    }

    @Test
    fun personsChangedStopNoteIsKept() {
        write(at(0, rate = null, suspended = true))
        val note = notes().single()
        Thread.sleep(5)
        runBlocking {
            // upstream's editor: no source (MANUAL), no key
            JournalRepository().upsertEntry(
                JournalEntryInput(
                    id = note.id, timestamp = note.timestamp, type = JournalEntryType.NOTE, title = note.title,
                    note = "made-up own note",
                )
            )
        }
        write(at(10, rate = 0.8, suspended = false))
        assertEquals("made-up own note", notes().single().note)
    }

    // ---------------------------------------------------------------- CareLinkIob

    @Test
    fun activeInsulinGoesToTheIobSnapshotWithItsOwnTime() {
        CareLinkIob.publish(PumpNow(now - 2 * min, iobUnits = 2.5, iobAtMs = now - 3 * min))
        val s = ApiIobSnapshot.fresh(now)!!
        assertEquals(2.5f, s.iobUnits, 1e-6f)
        assertEquals(now - 3 * min, s.timestampMillis)
        assertTrue("their own eIOB stays", s.eiobUnits.isNaN())
        assertTrue("their own COB stays", s.cobGrams.isNaN())
    }

    @Test
    fun activeInsulinFromTheFutureIsDroppedNearFutureStampedNow() {
        CareLinkIob.publish(PumpNow(now, iobUnits = 1.0, iobAtMs = now + 10 * min))
        assertNull(ApiIobSnapshot.fresh(now))
        CareLinkIob.publish(PumpNow(now, iobUnits = 1.0, iobAtMs = now + 2 * min))
        assertEquals(now, ApiIobSnapshot.fresh(now)!!.timestampMillis)
        CareLinkIob.publish(null)
        assertEquals(1.0f, ApiIobSnapshot.fresh(now)!!.iobUnits, 1e-6f)
    }

    @Test
    fun signOutTakesThePumpIobOffThePlateEvenWhenClosingTheBasalFails() {
        // critic 4d, N3 and N7: the clearing after a sign-out runs whether or not closing the basal worked
        CareLinkIob.publish(PumpNow(now - 2 * min, iobUnits = 2.5))
        assertNotNull(ApiIobSnapshot.fresh(now))
        runBlocking { CareLinkPoller.closeAfterSignOut(app).join() }
        assertNull("the basal closed fine", ApiIobSnapshot.fresh(now))
        CareLinkIob.publish(PumpNow(now - min, iobUnits = 1.5))
        assertNotNull(ApiIobSnapshot.fresh(now))
        CareLinkPoller.closeBasal = { throw IllegalStateException("made-up") }
        try {
            runBlocking { CareLinkPoller.closeAfterSignOut(app).join() }
        } finally {
            CareLinkPoller.resetForTest()
        }
        assertNull("closing the basal failed", ApiIobSnapshot.fresh(now))
    }

    @Test
    fun aNewSignInBeforeTheClosingKeepsItsIob() {
        // critic of the joints 4e, N1: signed out and in again within seconds - the closing left for later must not
        // close the new session's segment or take its IoB off
        CareLinkIob.publish(PumpNow(now - min, iobUnits = 1.5))
        var closedCount = 0
        CareLinkPoller.closeBasal = { closedCount++ }
        tk.glucodata.mod.carelink.CareLinkSettings(app).enabled = true
        try {
            runBlocking { CareLinkPoller.closeAfterSignOut(app).join() }
        } finally {
            tk.glucodata.mod.carelink.CareLinkSettings(app).enabled = false
            CareLinkPoller.resetForTest()
        }
        assertEquals(0, closedCount)
        assertNotNull(ApiIobSnapshot.fresh(now))
    }
}
