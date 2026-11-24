package mod.carelink

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import mod.carelink.CareLinkJournalTestEnv.app
import mod.carelink.CareLinkJournalTestEnv.dao
import mod.carelink.CareLinkJournalTestEnv.rows
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalRepository
import tk.glucodata.mod.carelink.CareLinkInsulin
import tk.glucodata.mod.carelink.CareLinkJournal
import tk.glucodata.mod.carelink.CareLinkJournalWriter
import tk.glucodata.mod.carelink.CareLinkSeenStore
import tk.glucodata.mod.carelink.CareLinkSettings
import tk.glucodata.mod.carelink.ClEvent
import tk.glucodata.mod.carelink.ClKind
import tk.glucodata.mod.carelink.SeenState

/**
 * JugglucoNGmod, CareLink 4d: pump boluses and calculator carbs in upstream's real journal (Room under Robolectric):
 * once per key, running -> finished in one row, a cancelled bolus removed (deletion queued for Nightscout), the
 * person's edit and deletion kept, square wave, source by the switch, a moved marker keeps its row. Made-up doses and
 * times only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CareLinkJournalTestApp::class, shadows = [CareLinkTestNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkJournalWriterTest {

    private val now = 1_800_000_000_000L
    private val t0 = now - 2 * 3_600_000L

    @Before
    fun setUp() = CareLinkJournalTestEnv.setUp { now }

    @After
    fun tearDown() {
        CareLinkJournalWriter.journal = { CareLinkJournal(it) }
        CareLinkJournalTestEnv.tearDown()
    }

    private fun bolus(
        sec: Int, programmed: Double, delivered: Double, completed: Boolean = true, type: String = "FAST",
    ) = ClEvent(
        ClKind.BOLUS, "INSULIN|2027-01-15T06:00:${"%02d".format(sec)}|0", t0 + sec * 1_000L,
        programmedUnits = programmed, deliveredUnits = delivered, completed = completed, bolusType = type,
    )

    private fun meal(sec: Int, grams: Double) =
        ClEvent(ClKind.MEAL, "MEAL|2027-01-15T06:00:${"%02d".format(sec)}|0", t0 + sec * 1_000L, grams = grams)

    private fun write(vararg events: ClEvent) = runBlocking { CareLinkJournalWriter.apply(app, events.toList(), false) }

    private fun insulin() = rows(JournalEntryType.INSULIN.storageValue)
    private fun carbs() = rows(JournalEntryType.CARBS.storageValue)

    @Test
    fun bolusIsWrittenOnceWithTheDefaultInsulin() {
        write(bolus(10, 2.0, 2.0))
        write(bolus(10, 2.0, 2.0))
        val row = insulin().single()
        assertEquals(2.0f, row.amount!!, 1e-6f)
        assertEquals(t0 + 10_000L, row.timestamp)
        assertEquals("carelink:INSULIN:2027-01-15T06:00:10:0:insulin", row.sourceRecordId)
        assertEquals("Pump bolus", row.title)
        assertNull(row.note)
        assertEquals("switch off - a mirror source, sent nowhere", JournalEntrySource.API.storageValue, row.source)
        val default = runBlocking { CareLinkInsulin.presetId(app) }
        assertNotNull(default)
        assertEquals(default, row.insulinPresetId)
    }

    @Test
    fun calculatorCarbsGoWithTheirBolus() {
        write(meal(8, 45.0), bolus(10, 4.5, 4.5), meal(30, 20.0), meal(100, 15.0), bolus(125, 1.0, 1.0))
        val c = carbs()
        assertEquals("meal 2 s before the bolus - paired; 25 s before - not; no bolus - not", 1, c.size)
        assertEquals(45.0f, c.single().amount!!, 1e-6f)
        assertEquals(t0 + 8_000L, c.single().timestamp)
        assertNull("upstream's default absorption time", c.single().durationMinutes)
        assertEquals("carelink:MEAL:2027-01-15T06:00:08:0:carbs", c.single().sourceRecordId)
        assertEquals("Pump calculator", c.single().title)
        assertEquals(2, insulin().size)
    }

    @Test
    fun carbsSwitchOffWritesNoNewCarbs() {
        CareLinkSettings(app).writeCarbs = false
        write(meal(8, 45.0), bolus(10, 4.5, 4.5))
        assertTrue(carbs().isEmpty())
        assertEquals(1, insulin().size)
    }

    @Test
    fun runningThenFinishedIsOneRow() {
        write(bolus(10, 3.0, 1.0, completed = false))
        val running = insulin().single()
        assertEquals("programmed while running", 3.0f, running.amount!!, 1e-6f)
        assertTrue(running.note!!.contains("in progress"))
        write(bolus(10, 3.0, 3.0))
        val done = insulin().single()
        assertEquals(running.id, done.id)
        assertEquals(3.0f, done.amount!!, 1e-6f)
        assertNull(done.note)
    }

    @Test
    fun interruptedBolusKeepsTheDeliveredDoseAndSaysSo() {
        write(bolus(10, 3.0, 1.2))
        val row = insulin().single()
        assertEquals(1.2f, row.amount!!, 1e-6f)
        assertTrue(row.note!!.contains("1.2") && row.note!!.contains("3"))
    }

    @Test
    fun cancelledBolusIsRemovedWithItsCarbsAndTheDeletionIsQueued() {
        CareLinkSettings(app).sendToNightscout = true
        write(meal(8, 30.0), bolus(10, 3.0, 0.0, completed = false))
        val ins = insulin().single()
        val c = carbs().single()
        // both already in Nightscout
        runBlocking {
            dao.markEntryUploadedToNightscout(ins.id, "made-up-remote-1", now)
            dao.markEntryUploadedToNightscout(c.id, "made-up-remote-2", now)
        }
        write(meal(8, 30.0), bolus(10, 3.0, 0.0, completed = true))
        assertTrue(insulin().isEmpty())
        assertTrue(carbs().isEmpty())
        val queued = runBlocking { dao.getPendingNightscoutDeletes() }.map { it.entryId }.toSet()
        assertEquals(setOf(ins.id, c.id), queued)
        // the next answers carry it still: not brought back
        write(meal(8, 30.0), bolus(10, 3.0, 0.0, completed = true))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun cancelledBeforeAnyPollIsNeverWritten() {
        write(meal(8, 30.0), bolus(10, 3.0, 0.0))
        assertTrue(rows().isEmpty())
    }

    @Test
    fun personsEditIsNotWrittenOver() {
        write(bolus(10, 3.0, 1.0, completed = false))
        val ours = insulin().single()
        Thread.sleep(5)
        runBlocking {
            JournalRepository().upsertEntry(
                JournalEntryInput(
                    id = ours.id, timestamp = ours.timestamp, type = JournalEntryType.INSULIN, title = ours.title,
                    note = "made-up own note", amount = 2.5f, insulinPresetId = ours.insulinPresetId,
                    source = JournalEntrySource.fromStorage(ours.source), sourceRecordId = ours.sourceRecordId,
                )
            )
        }
        write(bolus(10, 3.0, 3.0))
        val row = insulin().single()
        assertEquals(2.5f, row.amount!!, 1e-6f)
        assertEquals("made-up own note", row.note)
    }

    @Test
    fun personsDeletionIsNotBroughtBack() {
        write(meal(8, 20.0), bolus(10, 2.0, 2.0))
        val ins = insulin().single()
        runBlocking { JournalRepository().deleteEntry(ins.id) }
        write(meal(8, 20.0), bolus(10, 2.0, 2.0))
        assertTrue(insulin().isEmpty())
        assertEquals("the carbs were not deleted by the person - they stay", 1, carbs().size)
    }

    @Test
    fun squareWaveIsOneRowWithTheSumAndANote() {
        write(bolus(10, 3.0, 3.0, type = "SQUARE_WAVE"))
        val row = insulin().single()
        assertEquals(3.0f, row.amount!!, 1e-6f)
        assertTrue(row.note!!.contains("SQUARE_WAVE"))
    }

    @Test
    fun sourceFollowsTheSwitchForNewRowsAndNeverChangesForOldOnes() {
        write(bolus(10, 2.0, 1.0, completed = false))
        CareLinkSettings(app).sendToNightscout = true
        write(bolus(10, 2.0, 2.0), bolus(50, 1.0, 1.0))
        val byKey = insulin().associateBy { it.sourceRecordId }
        assertEquals(JournalEntrySource.API.storageValue, byKey["carelink:INSULIN:2027-01-15T06:00:10:0:insulin"]!!.source)
        assertEquals(2.0f, byKey["carelink:INSULIN:2027-01-15T06:00:10:0:insulin"]!!.amount!!, 1e-6f)
        assertEquals(
            JournalEntrySource.HEALTH_CONNECT.storageValue,
            byKey["carelink:INSULIN:2027-01-15T06:00:50:0:insulin"]!!.source,
        )
        // and back off: the Health Connect row stays Health Connect
        CareLinkSettings(app).sendToNightscout = false
        write(bolus(50, 1.0, 1.0), bolus(70, 0.5, 0.5))
        val again = insulin().associateBy { it.sourceRecordId }
        assertEquals(
            JournalEntrySource.HEALTH_CONNECT.storageValue,
            again["carelink:INSULIN:2027-01-15T06:00:50:0:insulin"]!!.source,
        )
        assertEquals(JournalEntrySource.API.storageValue, again["carelink:INSULIN:2027-01-15T06:00:70:0:insulin"]!!.source)
    }

    @Test
    fun movedMarkerOfARunningBolusKeepsItsRow() {
        write(bolus(10, 3.0, 1.0, completed = false))
        val running = insulin().single()
        // the finished marker came 20 s later; the old one is gone from the answer
        write(bolus(30, 3.0, 3.0))
        val row = insulin().single()
        assertEquals(running.id, row.id)
        assertEquals("carelink:INSULIN:2027-01-15T06:00:30:0:insulin", row.sourceRecordId)
        assertEquals(3.0f, row.amount!!, 1e-6f)
        assertEquals(t0 + 30_000L, row.timestamp)
        assertNull(row.note)
    }

    @Test
    fun aZoneChangeDoesNotMoveRowsAlreadyWritten() {
        // critic of the joints 4e, C2: the same pump history counted with another phone offset (+03:00 -> +06:00) - the
        // keys are the same, every moment 3 h earlier; the rows keep their time and are not written again
        val shift = -3 * 3_600_000L
        fun moved(e: ClEvent) = e.copy(atMs = e.atMs + shift)
        write(meal(8, 30.0), bolus(10, 3.0, 3.0), bolus(50, 2.0, 1.0, completed = false))
        val before = rows().associateBy { it.sourceRecordId }
        Thread.sleep(5)
        write(moved(meal(8, 30.0)), moved(bolus(10, 3.0, 3.0)), moved(bolus(50, 2.0, 1.0, completed = false)))
        val after = rows().associateBy { it.sourceRecordId }
        assertEquals(before.keys, after.keys)
        for ((k, r) in before) {
            assertEquals(k, r.timestamp, after[k]!!.timestamp)
            assertEquals("not written again: $k", r.updatedAt, after[k]!!.updatedAt)
        }
        // the running one finishes after the change: the dose is written, the time stays
        write(moved(meal(8, 30.0)), moved(bolus(10, 3.0, 3.0)), moved(bolus(50, 2.0, 2.0)))
        val done = insulin().single { it.sourceRecordId == "carelink:INSULIN:2027-01-15T06:00:50:0:insulin" }
        assertEquals(2.0f, done.amount!!, 1e-6f)
        assertEquals(t0 + 50_000L, done.timestamp)
    }

    @Test
    fun aRunningBolusWhoseMarkerMovesAfterAZoneChangeKeepsItsRow() {
        // critic 4e, C1: running at +03:00 -> the same history at +06:00 -> its marker moves 30 s, still at +06:00;
        // the move is counted in pump time (the keys), so it is the same bolus: one row, under the new key
        val shift = -3 * 3_600_000L
        fun moved(e: ClEvent) = e.copy(atMs = e.atMs + shift)
        write(bolus(20, 2.0, 1.0, completed = false))
        val running = insulin().single()
        write(moved(bolus(20, 2.0, 1.0, completed = false)))
        write(moved(bolus(50, 2.0, 2.0)))
        val row = insulin().single()
        assertEquals(running.id, row.id)
        assertEquals("carelink:INSULIN:2027-01-15T06:00:50:0:insulin", row.sourceRecordId)
        assertEquals(2.0f, row.amount!!, 1e-6f)
        assertNull(row.note)
        assertEquals("moved 30 s from its own time, not to the new offset", t0 + 50_000L, row.timestamp)
        assertEquals(SeenState.MOVED, state("carelink:INSULIN:2027-01-15T06:00:20:0:insulin"))
    }

    @Test
    fun finishedBolusWithTheSameDoseIsNotRewritten() {
        write(bolus(10, 2.0, 2.0))
        val first = insulin().single()
        Thread.sleep(5)
        write(bolus(10, 2.0, 2.0))
        assertEquals("no needless write (it would send the row to Nightscout again)", first.updatedAt, insulin().single().updatedAt)
    }

    @Test
    fun lostRecordAdoptsTheRowInsteadOfAddingOne() {
        write(bolus(10, 2.0, 2.0))
        CareLinkSeenStore.clear(app)
        write(bolus(10, 2.0, 2.0))
        assertEquals(1, insulin().size)
        assertFalse(rows().isEmpty())
    }

    // ---------------------------------------------------------------- critic 4d and critic of the joints

    private fun state(key: String) = CareLinkSeenStore.load(app, now).records[key]?.state

    private fun editLikeTheirEditor(row: JournalEntryEntity, amount: Float, note: String?) = runBlocking {
        // upstream's JournalCompose.toInput: the preset's name as the title, no source (MANUAL), no key
        JournalRepository().upsertEntry(
            JournalEntryInput(
                id = row.id, timestamp = row.timestamp, type = JournalEntryType.INSULIN, title = "Made-up preset",
                note = note, amount = amount, insulinPresetId = row.insulinPresetId,
            )
        )
    }

    @Test
    fun movedKeyAndAnOlderAnswerMakeNoSecondBolus() {
        // critic 4d, B1: running K1 -> moved to K2 and finished -> an older answer brings K1 "running" again
        write(bolus(10, 3.0, 1.0, completed = false))
        write(bolus(30, 3.0, 3.0))
        write(bolus(10, 3.0, 1.0, completed = false))
        val row = insulin().single()
        assertEquals("carelink:INSULIN:2027-01-15T06:00:30:0:insulin", row.sourceRecordId)
        assertEquals(3.0f, row.amount!!, 1e-6f)
        assertNull(row.note)
        assertEquals(SeenState.MOVED, state("carelink:INSULIN:2027-01-15T06:00:10:0:insulin"))
        // and the fresh answers after it change nothing
        write(bolus(30, 3.0, 3.0))
        assertEquals(1, insulin().size)
    }

    @Test
    fun olderAnswerDoesNotTakeAFinishedBolusBackToRunning() {
        // critic 4d, C1: written finished (1.2 of 3 delivered), an older answer shows it running
        write(bolus(10, 3.0, 1.2))
        val done = insulin().single()
        Thread.sleep(5)
        write(bolus(10, 3.0, 1.0, completed = false))
        val row = insulin().single()
        assertEquals(1.2f, row.amount!!, 1e-6f)
        assertEquals(done.note, row.note)
        assertEquals("not even written", done.updatedAt, row.updatedAt)
    }

    @Test
    fun ourWriteWithALostRecordIsNotTakenForThePersonsEdit() {
        // critic of the joints, C1: our write went through, its record did not (work cancelled, process killed)
        write(bolus(10, 3.0, 1.0, completed = false))
        val running = insulin().single()
        Thread.sleep(5)
        runBlocking {
            JournalRepository().upsertEntry(
                JournalEntryInput(
                    id = running.id, timestamp = running.timestamp, type = JournalEntryType.INSULIN, title = running.title,
                    amount = 3.0f, insulinPresetId = running.insulinPresetId,
                    source = JournalEntrySource.fromStorage(running.source), sourceRecordId = running.sourceRecordId,
                )
            )
        }
        val key = running.sourceRecordId!!
        write(bolus(10, 3.0, 3.0))
        assertEquals("still ours", SeenState.LIVE, state(key))
        // ours: a later cancellation still removes it
        write(bolus(10, 3.0, 0.0, completed = true))
        assertTrue(insulin().isEmpty())
    }

    @Test
    fun personsEditThroughTheirEditorIsKept() {
        // critic 4d, N8: their editor makes a Health Connect row MANUAL without its key
        CareLinkSettings(app).sendToNightscout = true
        write(bolus(10, 3.0, 1.0, completed = false))
        val ours = insulin().single()
        Thread.sleep(5)
        editLikeTheirEditor(ours, 2.5f, "made-up own note")
        write(bolus(10, 3.0, 3.0))
        val row = insulin().single()
        assertEquals(2.5f, row.amount!!, 1e-6f)
        assertEquals("made-up own note", row.note)
        assertNull("their editor took the key", row.sourceRecordId)
        // nor is it removed when the pump cancels it
        write(bolus(10, 3.0, 0.0, completed = true))
        assertEquals(1, insulin().size)
    }

    @Test
    fun theirEditorSavingUnchangedLeavesTheRowOursAndGetsSourceAndKeyBack() {
        CareLinkSettings(app).sendToNightscout = true
        write(bolus(10, 3.0, 1.0, completed = false))
        val ours = insulin().single()
        Thread.sleep(5)
        editLikeTheirEditor(ours, ours.amount!!, ours.note)
        assertEquals(JournalEntrySource.MANUAL.storageValue, insulin().single().source)
        write(bolus(10, 3.0, 3.0))
        val row = insulin().single()
        assertEquals(3.0f, row.amount!!, 1e-6f)
        assertNull(row.note)
        assertEquals(JournalEntrySource.HEALTH_CONNECT.storageValue, row.source)
        assertEquals(ours.sourceRecordId, row.sourceRecordId)
    }

    @Test
    fun cancelledBolusRemovesItsCarbsWhenTheMealMarkerIsGone() {
        write(meal(8, 30.0), bolus(10, 3.0, 0.0, completed = false))
        assertEquals(1, carbs().size)
        write(bolus(10, 3.0, 0.0, completed = true))
        assertTrue(carbs().isEmpty())
        assertTrue(insulin().isEmpty())
    }

    @Test
    fun theCarbsOfThePersonsBolusAreLeftAlone() {
        // critic 4d, N3: the person deleted the bolus; the pump then cancels it - the carbs are not ours to remove
        write(meal(8, 30.0), bolus(10, 3.0, 1.0, completed = false))
        runBlocking { JournalRepository().deleteEntry(insulin().single().id) }
        write(meal(8, 30.0), bolus(10, 3.0, 0.0, completed = true))
        assertEquals(1, carbs().size)
    }

    @Test
    fun secondBolusNearbyDoesNotTakeTheFirstOnesRow() {
        // critic 4d, N4: a 1 U bolus a few seconds before the moved marker of a running 3 U one
        write(bolus(10, 3.0, 1.0, completed = false))
        val running = insulin().single()
        write(bolus(5, 1.0, 1.0), bolus(30, 3.0, 3.0))
        val byKey = insulin().associateBy { it.sourceRecordId }
        assertEquals(2, byKey.size)
        assertEquals(running.id, byKey["carelink:INSULIN:2027-01-15T06:00:30:0:insulin"]!!.id)
        assertEquals(1.0f, byKey["carelink:INSULIN:2027-01-15T06:00:05:0:insulin"]!!.amount!!, 1e-6f)
    }

    // ---------------------------------------------------------------- critic 4d2 (second round)

    private val k10 = "carelink:INSULIN:2027-01-15T06:00:10:0:insulin"
    private val k30 = "carelink:INSULIN:2027-01-15T06:00:30:0:insulin"

    @Test
    fun oneRowThatFailsDoesNotStopTheLaterBoluses() {
        // critic 4d2, C1: upstream refuses one row - the later bolus and its carbs are still written, the failure reported
        CareLinkJournalWriter.journal = { c ->
            object : CareLinkJournal(c) {
                override suspend fun upsert(input: JournalEntryInput): JournalEntryEntity {
                    if (input.sourceRecordId == k10) throw IllegalStateException("made-up refusal")
                    return super.upsert(input)
                }
            }
        }
        try {
            write(bolus(10, 1.0, 1.0), meal(28, 30.0), bolus(30, 2.0, 2.0))
            fail("the failure is reported to the poll")
        } catch (_: IllegalStateException) {
        }
        assertEquals(listOf(k30), insulin().map { it.sourceRecordId })
        assertEquals(1, carbs().size)
        // the journal works again: the next poll writes the first one, nothing twice
        CareLinkJournalWriter.journal = { CareLinkJournal(it) }
        write(bolus(10, 1.0, 1.0), meal(28, 30.0), bolus(30, 2.0, 2.0))
        assertEquals(setOf(k10, k30), insulin().map { it.sourceRecordId }.toSet())
        assertEquals(1, carbs().size)
    }

    @Test
    fun aFailedKeyMoveIsTriedAgainAndMakesNoSecondBolus() {
        // critic 4d2, C2: the database fails during the move - the old key is not marked moved, the next poll moves it
        write(bolus(10, 3.0, 1.0, completed = false))
        var failures = 1
        CareLinkJournalWriter.journal = { c ->
            object : CareLinkJournal(c) {
                override suspend fun retag(id: Long, key: String): Boolean {
                    if (failures-- > 0) throw IllegalStateException("made-up disk error")
                    return super.retag(id, key)
                }
            }
        }
        try {
            write(bolus(30, 3.0, 3.0))
            fail("the failure is reported to the poll")
        } catch (_: IllegalStateException) {
        }
        assertEquals("no trace before the move", SeenState.LIVE, state(k10))
        write(bolus(30, 3.0, 3.0))
        val row = insulin().single()
        assertEquals(k30, row.sourceRecordId)
        assertEquals(3.0f, row.amount!!, 1e-6f)
        assertEquals(SeenState.MOVED, state(k10))
    }

    @Test
    fun lostRecordAndAnOlderAnswerDoNotTakeAFinishedBolusBackToRunning() {
        // critic 4d2, N8: the record file is lost, then an older answer shows the finished bolus running
        write(bolus(10, 3.0, 1.2))
        val done = insulin().single()
        CareLinkSeenStore.clear(app)
        Thread.sleep(5)
        write(bolus(10, 3.0, 1.0, completed = false))
        val row = insulin().single()
        assertEquals(1.2f, row.amount!!, 1e-6f)
        assertEquals("not even written", done.updatedAt, row.updatedAt)
    }
}
