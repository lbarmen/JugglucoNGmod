package mod.pumpbt

import kotlinx.coroutines.runBlocking
import mod.carelink.CareLinkJournalTestApp
import mod.carelink.CareLinkJournalTestEnv
import mod.carelink.CareLinkJournalTestEnv.app
import mod.carelink.CareLinkTestNatives
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.mod.carelink.CareLinkIntake
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.carelink.IntakeOutcome
import tk.glucodata.mod.carelink.IntakeSource
import tk.glucodata.mod.carelink.PumpSourceSwitch
import tk.glucodata.mod.pumpbt.PumpBtBridge
import tk.glucodata.mod.pumpbt.core.PumpCycle

/**
 * Pump over Bluetooth, critic of the joints 1 (B-1, C-1): cycles of the link through the bridge's snapshot and the
 * common intake into upstream's real journal with the real basal writer - a percentage temporary basal is a percentage
 * of the normal rate, a cycle without the basal answer does not split the basal segment, a real break of over 30 min
 * still is a gap. Made-up rates and times only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CareLinkJournalTestApp::class, shadows = [CareLinkTestNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PumpBtBasalJournalTest {

    private val now = 1_800_000_000_000L
    private val t0 = now - 3 * 3_600_000L
    private val min = 60_000L

    @Before
    fun setUp() {
        CareLinkJournalTestEnv.setUp { now }
        CareLinkIntake.resetForTest()
        CareLinkIntake.writeJournal = { _, _, _ -> }
        CareLinkIntake.publishIob = { }
        PumpSourceSwitch.set(app, true, t0 - 10 * min)
    }

    @After
    fun tearDown() {
        PumpSourceSwitch.set(app, false, now)
        CareLinkIntake.resetForTest()
        CareLinkJournalTestEnv.tearDown()
    }

    /** A cycle read at [m] minutes after t0: running, normal 0.8 U/h, active insulin 1 U. */
    private fun cycle(m: Long) = PumpCycle(
        readAtMs = t0 + m * min, serialHash = B2Fixtures.HASH, therapyState = 0x55, iobUnits = 1.0, basalRate = 0.8,
        basalRead = true,
    )

    /** The cycle as the source feeds it: the bridge's snapshot into the intake, no events. */
    private fun feed(c: PumpCycle) = runBlocking {
        CareLinkIntake.accept(app, IntakeSource.BLUETOOTH, emptyList(), PumpBtBridge.toPumpNow(c), c.readAtMs)
    }

    private fun basal(): List<JournalEntryEntity> =
        CareLinkJournalTestEnv.rows(JournalEntryType.TEMP_BASAL.storageValue).sortedBy { it.timestamp }

    private fun JournalEntryEntity.startMin() = (timestamp - t0) / min

    @Test
    fun aPercentageTemporaryBasalIsAPercentageOfTheNormalRateInTheJournal() {
        feed(cycle(0))
        feed(cycle(5).copy(tempBasalRate = 150.0, tempBasalType = 0x3C, tempBasalMinutesLeft = 120))
        val b = basal()
        assertEquals(2, b.size)
        assertEquals(0.8f, b[0].amount!!, 1e-6f)
        assertEquals("150 % of 0.8 U/h, not 150 U/h", 1.2f, b[1].amount!!, 1e-6f)
        assertEquals(5L, b[1].startMin())
    }

    @Test
    fun aTemporaryBasalOfAnUnknownTypeWritesNoRate() {
        feed(cycle(0))
        feed(cycle(5).copy(tempBasalRate = 150.0, tempBasalType = null))
        // the basal is not known: the writer is not called (the segment written ahead stays until a known rate or the
        // gap rule closes it at its last data) - nothing at 150 U/h
        val b = basal()
        assertEquals(1, b.size)
        assertEquals(0.8f, b.single().amount!!, 1e-6f)
    }

    @Test
    fun aCycleWithoutTheBasalAnswerBetweenTwoGoodOnesDoesNotSplitTheSegment() {
        assertEquals(IntakeOutcome.WRITTEN, feed(cycle(0)).outcome)
        // the link broke after the active insulin, before the basal answer
        val partial = feed(cycle(5).copy(basalRead = false, basalRate = null, iobUnits = 1.4))
        assertEquals(IntakeOutcome.WRITTEN, partial.outcome)
        assertEquals("the snapshot stands", 1.4, partial.state.pumpNow!!.iobUnits!!, 1e-9)
        assertEquals(t0 + 5 * min, partial.state.lastDataAtMs)
        // a cycle that read nothing: no snapshot, the data time stays
        val empty = feed(PumpCycle(readAtMs = t0 + 7 * min, serialHash = B2Fixtures.HASH))
        assertEquals(t0 + 5 * min, empty.state.lastDataAtMs)
        assertEquals(t0 + 5 * min, CareLinkStateStore(app).read().lastDataAtMs)
        feed(cycle(10))
        val b = basal()
        assertEquals("one segment, no hole and no second row", 1, b.size)
        assertEquals(0L, b.single().startMin())
        assertEquals(0.8f, b.single().amount!!, 1e-6f)
    }

    @Test
    fun aRealBreakOfOverThirtyMinutesBetweenGoodCyclesIsStillAGap() {
        feed(cycle(0))
        feed(cycle(5))
        feed(cycle(10).copy(basalRead = false, basalRate = null))
        feed(cycle(45))
        val b = basal()
        assertEquals(2, b.size)
        assertEquals("closed at its last basal data, not run on over the break", 5, b[0].durationMinutes)
        assertEquals(45L, b[1].startMin())
        assertNull(b.firstOrNull { it.startMin() in 1L..44L })
    }
}
