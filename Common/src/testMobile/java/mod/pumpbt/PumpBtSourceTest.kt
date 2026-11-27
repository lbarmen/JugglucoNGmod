package mod.pumpbt

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import mod.pumpbt.B2Fixtures.ZONE
import mod.pumpbt.B2Fixtures.bolus
import mod.pumpbt.B2Fixtures.cycle
import mod.pumpbt.B2Fixtures.ms
import mod.pumpbt.B2Fixtures.ref
import mod.pumpbt.B2Fixtures.sensor
import tk.glucodata.drivers.api.ApiIobSnapshot
import tk.glucodata.mod.carelink.CareLinkClock
import tk.glucodata.mod.carelink.CareLinkIntake
import tk.glucodata.mod.carelink.CareLinkIob
import tk.glucodata.mod.carelink.CareLinkPoller
import tk.glucodata.mod.carelink.CareLinkScheduler
import tk.glucodata.mod.carelink.ClEvent
import tk.glucodata.mod.carelink.ClKind
import tk.glucodata.mod.carelink.IntakeOutcome
import tk.glucodata.mod.carelink.PumpNow
import tk.glucodata.mod.carelink.PumpSourceSwitch
import tk.glucodata.mod.pumpbt.BackfillState
import tk.glucodata.mod.pumpbt.BondCheck
import tk.glucodata.mod.pumpbt.PumpBtAlertMemory
import tk.glucodata.mod.pumpbt.PumpBtAlertRules
import tk.glucodata.mod.pumpbt.JournalRow
import tk.glucodata.mod.pumpbt.PumpBtAlert
import tk.glucodata.mod.pumpbt.PumpBtAlertKind
import tk.glucodata.mod.pumpbt.PumpBtBridge
import tk.glucodata.mod.pumpbt.PumpBtHistoryStore
import tk.glucodata.mod.pumpbt.PumpBtJournal
import tk.glucodata.mod.pumpbt.PumpBtNameVariant
import tk.glucodata.mod.pumpbt.PumpBtSource
import tk.glucodata.mod.pumpbt.PumpBtStore
import tk.glucodata.mod.pumpbt.PumpHistoryMemory
import tk.glucodata.mod.pumpbt.core.PumpCycle
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * PumpBtSource (series part 2, part B2a): one cycle from the link to the journal intake - nothing without the serial
 * number, the memory on disk before anything is written, only WRITTEN lets records go, cycles one at a time, the enable
 * moment set once and again after "off", "off" closes the basal and takes the active insulin off the plate, the memory
 * across a process death, the trial's number while the source is off, notifications once per type; and the link state
 * file of PumpBtStore. The journal itself (Room) is not used: rows and writers are made-up stand-ins. Made-up records
 * and doses only; pump clock and phone zone UTC+3, 2026-10-15.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PumpBtSourceTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var now = ms(12, 0)

    /** Journal rows of the stand-in journal (what Room would give). */
    private val rows = Collections.synchronizedList(mutableListOf<JournalRow>())
    private var cloudRowTimes = listOf<Long>()

    private val fed = Collections.synchronizedList(mutableListOf<List<ClEvent>>())
    private val snapshots = Collections.synchronizedList(mutableListOf<PumpNow?>())
    private var journalFails = false
    private var onWrite: suspend () -> Unit = {}
    private var closed = 0
    private val alerts = Collections.synchronizedList(mutableListOf<PumpBtAlert>())

    private val dir get() = File(app.noBackupFilesDir, "mod-pumpbt")
    private val historyFile get() = File(dir, "history.json")
    private fun onDisk(): PumpHistoryMemory = PumpBtHistoryStore(historyFile).load()

    @Before
    fun setUp() {
        dir.deleteRecursively()
        PumpBtSource.resetForTest()
        PumpBtBridge.resetForTest()
        CareLinkPoller.resetForTest()
        CareLinkScheduler.resetForTest()
        PumpSourceSwitch.override = null
        ApiIobSnapshot.clear()
        CareLinkClock.now = { now }
        hooks()
    }

    /** The stand-ins (again after a "process death", which resets the source). */
    private fun hooks() {
        PumpBtSource.zone = { ZONE }
        PumpBtSource.sensorBluetooth = { true }
        PumpBtSource.alert = { _, a -> alerts += a }
        PumpBtBridge.loadRows = { _, from, to -> synchronized(rows) { rows.filter { it.atMs in from..to } } }
        PumpBtBridge.loadByKeys = { _, keys -> synchronized(rows) { rows.filter { it.sourceRecordId in keys } } }
        PumpBtBridge.loadCloudRowTimes = { _, from, to -> cloudRowTimes.filter { it in from..to } }
        CareLinkIntake.writeJournal = { _, e, _ ->
            onWrite()
            if (journalFails) throw IllegalStateException("made-up journal failure")
            fed += e
        }
        CareLinkIntake.writeBasal = { _, p, _ -> snapshots += p }
        CareLinkIntake.publishIob = { }
        CareLinkIntake.closeBasal = { closed++ }
    }

    @After
    fun tearDown() {
        PumpBtSource.resetForTest()
        PumpBtBridge.resetForTest()
        CareLinkPoller.resetForTest()
        CareLinkScheduler.resetForTest()
        PumpSourceSwitch.set(app, false, now)
        CareLinkClock.now = { System.currentTimeMillis() }
        ApiIobSnapshot.clear()
        dir.deleteRecursively()
    }

    /** The process dies: the source forgets everything that is not on disk. */
    private fun restart() {
        PumpBtSource.resetForTest()
        hooks()
    }

    private fun enable(at: Long = now) = runBlocking {
        now = at
        PumpBtSource.enable(app).join()
    }

    private fun accept(c: PumpCycle) = runBlocking { PumpBtSource.acceptCycle(app, c) }

    private fun bolusKeys() = fed.flatten().filter { it.kind == ClKind.BOLUS }.map { it.key }

    /** ref 100 at 11:00, a finished 2.5 U bolus programmed at 11:20 (101-104), read through. */
    private fun oldBolusCycle(readAt: Long) = cycle(listOf(ref(100, 3600, 11)) + bolus(101, 1200, id = 1, tenths = 25), readAtMs = readAt, oldest = 100)

    /** The cycle with the active basal answer: only then the intake calls the basal writer for Bluetooth (joints fix 4f1d4f8f6). */
    private fun PumpCycle.withBasal() = copy(basalRead = true, basalRate = 0.8)

    // --- one cycle ----------------------------------------------------------------------------------------------------

    @Test
    fun aCycleWithoutTheSerialNumberKeepsAndWritesNothing() {
        enable()
        val before = onDisk()
        accept(cycle(listOf(ref(100, 3600, 11), sensor(101, 60)), readAtMs = ms(12, 5), hash = null))
        assertTrue(fed.isEmpty())
        assertTrue(snapshots.isEmpty())
        assertEquals(before, onDisk())
        assertTrue(PumpBtSource.state.value.lastCycleNoSerial)
        assertNull(PumpBtSource.historyFromForTest(app)) // the number did not move
    }

    @Test
    fun theMemoryIsOnDiskBeforeTheWriterAndADeathMakesNoSecondRow() {
        // the cloud wrote this bolus at 11:20:30 (its seconds differ); the source is turned on at 12:00
        val cloudKey = "INSULIN|2026-10-15T11:20:30|0"
        val pumpKey = "INSULIN|2026-10-15T11:20:00|0"
        rows += JournalRow(1, ClKind.BOLUS, ms(11, 20, 30), 2.5, "carelink:INSULIN:2026-10-15T11:20:30:0:insulin")
        cloudRowTimes = listOf(ms(11, 20, 30))
        enable(ms(12, 0))
        assertEquals(ms(11, 10, 30), onDisk().boundaryMs) // the cloud's row minus 10 min

        // the first catch-up matches the bolus to the cloud row by time and dose; the writer sees it saved already
        var seenByWriter: PumpHistoryMemory? = null
        onWrite = { seenByWriter = onDisk() }
        accept(oldBolusCycle(ms(12, 5)))
        val seen = seenByWriter!!
        assertEquals(cloudKey, seen.aliases[pumpKey])
        assertEquals(BackfillState.WAITING, seen.backfill)
        val card = PumpBtSource.state.value.card!!
        assertEquals(1, card.alreadyInJournal)
        assertTrue(card.toWrite.isEmpty())
        assertTrue(bolusKeys().isEmpty()) // nothing old before "Write"
        assertEquals(listOf(PumpBtAlert(PumpBtAlertKind.CONFIRM_BACKFILL)), alerts)

        runBlocking { PumpBtSource.confirmBackfill(app, card.toWrite.map { it.key }).join() }
        assertEquals(BackfillState.CONFIRMED, onDisk().backfill)
        assertNull(PumpBtSource.state.value.card)

        // after a death the old bolus goes under the cloud row's key; the journal fails - nothing accepted
        restart()
        journalFails = true
        accept(cycle(emptyList(), readAtMs = ms(12, 10), from = 105, to = 104, newest = 104, contiguousTo = null))
        assertEquals(IntakeOutcome.FAILED, PumpBtSource.state.value.lastIntake)
        assertFalse(cloudKey in onDisk().acceptedAt)

        // another death, the journal works: the same key again (the writer updates that row), now accepted
        restart()
        journalFails = false
        accept(cycle(emptyList(), readAtMs = ms(12, 15), from = 105, to = 104, newest = 104, contiguousTo = null))
        assertEquals(IntakeOutcome.WRITTEN, PumpBtSource.state.value.lastIntake)
        assertEquals(listOf(cloudKey), bolusKeys())
        assertTrue(cloudKey in onDisk().acceptedAt)
        assertFalse(pumpKey in onDisk().acceptedAt)
    }

    @Test
    fun onlyWrittenLetsTheRecordsGo() {
        enable(ms(11, 0))
        journalFails = true
        accept(oldBolusCycle(ms(12, 5)).withBasal())
        val key = "INSULIN|2026-10-15T11:20:00|0"
        assertTrue(onDisk().acceptedAt.isEmpty())
        assertEquals(104L, onDisk().contiguousTo) // the records are kept, the number moved
        journalFails = false
        accept(cycle(emptyList(), readAtMs = ms(12, 10), from = 105, to = 104, newest = 104, contiguousTo = null).withBasal())
        assertEquals(listOf(key), bolusKeys())
        assertEquals(ms(12, 10), onDisk().acceptedAt[key])
        // the snapshot goes every cycle, also with the failed journal
        assertEquals(2, snapshots.size)
        assertNotNull(snapshots.first())
    }

    @Test
    fun twoCyclesAtOnceRunOneAfterTheOther() {
        enable(ms(11, 0))
        val inside = AtomicInteger()
        val most = AtomicInteger()
        onWrite = {
            most.accumulateAndGet(inside.incrementAndGet(), ::maxOf)
            delay(100)
            inside.decrementAndGet()
        }
        val first = cycle(listOf(ref(100, 3600, 11), sensor(101, 60)), readAtMs = ms(12, 0), oldest = 100).withBasal()
        // asked from 100 too: whichever comes first, the number ends at 103 with every record
        val second = cycle(listOf(sensor(102, 120), sensor(103, 180)), readAtMs = ms(12, 1), from = 100, newest = 103).withBasal()
        runBlocking {
            listOf(first, second).map { c -> async(Dispatchers.Default) { PumpBtSource.acceptCycle(app, c) } }.awaitAll()
        }
        assertEquals(1, most.get())
        assertEquals(2, snapshots.size)
        val m = onDisk()
        assertEquals(103L, m.contiguousTo)
        assertEquals((100L..103L).toList(), m.records.map { it.sequence })
        assertEquals(104L, PumpBtSource.historyFromForTest(app))
    }

    // --- on and off ---------------------------------------------------------------------------------------------------

    @Test
    fun enableSetsTheMomentOnceAndAgainAfterOff() {
        cloudRowTimes = listOf(ms(8, 0))
        enable(ms(10, 0))
        assertTrue(PumpSourceSwitch.bluetoothOn(app))
        assertEquals(ms(10, 0), onDisk().enabledAtMs)
        assertEquals(ms(7, 50), onDisk().boundaryMs)
        // pressed again while on: nothing moves
        enable(ms(11, 0))
        assertEquals(ms(10, 0), onDisk().enabledAtMs)
        assertEquals(ms(7, 50), onDisk().boundaryMs)

        runBlocking { PumpBtSource.disable(app).join() }
        assertFalse(PumpSourceSwitch.bluetoothOn(app))
        assertEquals(ms(10, 0), onDisk().enabledAtMs) // the memory stays
        cloudRowTimes = emptyList()
        enable(ms(13, 0))
        assertEquals(ms(13, 0), onDisk().enabledAtMs)
        assertEquals(ms(13, 0) - 24 * 3_600_000L, onDisk().boundaryMs) // no cloud row: enable minus 24 h
        assertEquals(BackfillState.NOT_READY, onDisk().backfill)
    }

    @Test
    fun offClosesTheBasalAndTakesTheActiveInsulinOffThePlate() {
        enable(ms(12, 0))
        CareLinkIob.publish(PumpNow(now, iobUnits = 1.5, iobAtMs = now))
        assertNotNull(ApiIobSnapshot.fresh(now))
        runBlocking { PumpBtSource.disable(app).join() }
        assertEquals(1, closed)
        assertNull(ApiIobSnapshot.fresh(now))
        assertFalse(PumpSourceSwitch.bluetoothOn(app))
        assertFalse(PumpBtSource.state.value.enabled)
        // a cycle after "off" writes nothing
        accept(oldBolusCycle(ms(12, 5)))
        assertTrue(snapshots.isEmpty())
        assertTrue(fed.isEmpty())
    }

    @Test
    fun theMemorySurvivesTheDeathOfTheProcess() {
        enable(ms(11, 0))
        accept(oldBolusCycle(ms(12, 5)))
        val before = onDisk()
        assertTrue(before.acceptedAt.isNotEmpty())
        restart()
        assertNull(PumpBtSource.state.value.contiguousTo)
        runBlocking { PumpBtSource.init(app).join() }
        // the memory comes back from its file: the link asks after the last record, nothing is fed twice as new
        assertEquals(105L, PumpBtSource.historyFromForTest(app))
        assertEquals(before, onDisk())
        accept(cycle(listOf(sensor(105, 1500)), readAtMs = ms(12, 10)))
        assertEquals(105L, onDisk().contiguousTo)
        assertEquals(before.acceptedAt, onDisk().acceptedAt)
    }

    @Test
    fun aBrokenMemoryFileWithTheSourceOnGetsANewMomentAndBoundary() {
        PumpSourceSwitch.set(app, true, ms(9, 0))
        historyFile.parentFile!!.mkdirs()
        historyFile.writeText("{\"v\":1,\"records\":[\"zz\"]")
        cloudRowTimes = listOf(ms(11, 30))
        now = ms(12, 0)
        accept(oldBolusCycle(ms(12, 5)))
        val m = onDisk()
        assertEquals(ms(12, 0), m.enabledAtMs)
        assertEquals(ms(11, 20), m.boundaryMs)
        assertTrue(PumpBtSource.state.value.memoryLoadFailed)
        assertTrue(File(dir, "history.json.broken").isFile)
        // the bolus at 11:20 is old now: it waits for the card, nothing old is written
        assertEquals(BackfillState.WAITING, m.backfill)
        assertTrue(bolusKeys().isEmpty())
    }

    @Test
    fun whileTheSourceIsOffOnlyTheTrialNumberMoves() {
        accept(oldBolusCycle(ms(12, 5)))
        assertFalse(historyFile.exists())
        assertTrue(fed.isEmpty())
        assertTrue(snapshots.isEmpty())
        assertEquals(105L, PumpBtSource.historyFromForTest(app))
        // another pump (its newest record below the number): from its oldest again
        accept(cycle(listOf(sensor(10, 1)), readAtMs = ms(12, 10), contiguousTo = null, newest = 10))
        assertNull(PumpBtSource.historyFromForTest(app))
    }

    @Test
    fun offWhileACycleWaitsInTheIntakeFinishes() {
        // critic of the joints 1, C-2: the source's mutex first, then the intake's lock - for the cycle and for "off"
        enable(ms(11, 0))
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        onWrite = {
            entered.complete(Unit)
            gate.await()
        }
        runBlocking {
            val running = async(Dispatchers.Default) { PumpBtSource.acceptCycle(app, oldBolusCycle(ms(12, 5))) }
            withTimeout(10_000) { entered.await() }
            val off = PumpBtSource.disable(app)
            delay(200)
            assertFalse(off.isCompleted) // waits for the cycle, does not cut into it
            gate.complete(Unit)
            withTimeout(10_000) {
                running.await()
                off.join()
            }
        }
        assertFalse(PumpSourceSwitch.bluetoothOn(app))
        assertEquals(1, closed)
        assertEquals(IntakeOutcome.WRITTEN, PumpBtSource.state.value.lastIntake)
    }

    @Test
    fun enableKeepsTheSwitchMomentAndAPlainRestartDoesNotMoveIt() {
        enable(ms(10, 0))
        assertEquals(PumpSourceSwitch.onSinceMs(app), onDisk().enabledAtMs)
        restart()
        now = ms(14, 0)
        runBlocking { PumpBtSource.init(app).join() }
        assertEquals(ms(10, 0), onDisk().enabledAtMs)
    }

    @Test
    fun aDeathBetweenTheSwitchAndTheMemoryIsMendedAtTheStart() {
        // critic of the joints 1, C-3: an old memory (on long ago, confirmed) and the switch on since 13:00 without the
        // memory's new moment (a death in between, as an older order could leave it)
        PumpBtHistoryStore(historyFile).save(
            PumpHistoryMemory(
                pumpHash = B2Fixtures.HASH, enabledAtMs = ms(8, 0, day = 10), boundaryMs = ms(7, 0, day = 10),
                backfill = BackfillState.CONFIRMED,
            ),
        )
        PumpSourceSwitch.set(app, true, ms(13, 0))
        cloudRowTimes = listOf(ms(12, 30)) // the cloud wrote while the source was off
        now = ms(13, 5)
        runBlocking { PumpBtSource.init(app).join() }
        val m = onDisk()
        assertEquals(ms(13, 0), m.enabledAtMs)
        assertEquals(ms(12, 20), m.boundaryMs)
        assertEquals(BackfillState.NOT_READY, m.backfill) // a new card for what the pump did while off
    }

    @Test
    fun anEmptyMemoryDoesNotTakeAReadAskedFromAnOlderNumber() {
        // critic of the joints 1, N-10: the link was given an old number (here 500), the memory is empty now
        enable(ms(11, 0))
        accept(cycle(listOf(sensor(500, 60), sensor(501, 61)), readAtMs = ms(12, 5), from = 500, oldest = 10, newest = 501).withBasal())
        assertNull(onDisk().contiguousTo)
        assertTrue(onDisk().records.isEmpty())
        assertEquals(1, snapshots.size) // the snapshot still goes
        // read from the oldest record: taken
        accept(cycle(listOf(ref(10, 3600, 11), sensor(11, 60)), readAtMs = ms(12, 10), from = 10, oldest = 10, newest = 11))
        assertEquals(11L, onDisk().contiguousTo)
    }

    @Test
    fun anotherPumpSetsTheLinksNumberBackAtOnce() {
        enable(ms(11, 0))
        accept(oldBolusCycle(ms(12, 5)))
        assertEquals(105L, PumpBtSource.historyFromForTest(app))
        accept(cycle(listOf(sensor(105, 1500)), readAtMs = ms(12, 10), hash = "pump-b"))
        assertNull(PumpBtSource.historyFromForTest(app))
    }

    @Test
    fun notificationRulesTellOnce() {
        val empty = PumpBtAlertMemory()
        val (a1, m1) = PumpBtAlertRules.decide(empty, setOf(0x0123), setOf(0x005A), setOf("b1"), setOf("b1", "b2"), drift = true, nowMs = 0)
        assertEquals(
            listOf(
                PumpBtAlert(PumpBtAlertKind.UNKNOWN_RECORD, 0x0123), PumpBtAlert(PumpBtAlertKind.UNPARSED_BOLUS, 0x005A),
                PumpBtAlert(PumpBtAlertKind.NO_RESULT_BOLUS), PumpBtAlert(PumpBtAlertKind.CLOCK_DRIFT),
            ),
            a1,
        )
        // the same next cycle, and a day later while the drift never went: nothing
        val (a2, m2) = PumpBtAlertRules.decide(m1, setOf(0x0123), setOf(0x005A), setOf("b1"), setOf("b1", "b2"), drift = true, nowMs = 60_000)
        assertTrue(a2.isEmpty())
        val (a3, m3) = PumpBtAlertRules.decide(m2, emptySet(), emptySet(), emptySet(), setOf("b1", "b2"), drift = true, nowMs = 25 * 3_600_000L)
        assertTrue(a3.isEmpty())
        // another bolus without its result: told; "not known" is not "gone"
        val (a4, m4) = PumpBtAlertRules.decide(m3, emptySet(), emptySet(), setOf("b2"), setOf("b1", "b2"), drift = null, nowMs = 26 * 3_600_000L)
        assertEquals(listOf(PumpBtAlert(PumpBtAlertKind.NO_RESULT_BOLUS)), a4)
        assertFalse(m4.driftArmed)
        // the drift gone, back an hour later: not before a day after the last one
        val (_, m5) = PumpBtAlertRules.decide(m4, emptySet(), emptySet(), emptySet(), emptySet(), drift = false, nowMs = 2 * 3_600_000L)
        assertTrue(m5.driftArmed)
        assertTrue(m5.noResultKeys.isEmpty()) // boluses gone from the memory are forgotten
        val (a6, m6) = PumpBtAlertRules.decide(m5, emptySet(), emptySet(), emptySet(), emptySet(), drift = true, nowMs = 3 * 3_600_000L)
        assertTrue(a6.isEmpty())
        val (a7, _) = PumpBtAlertRules.decide(m6, emptySet(), emptySet(), emptySet(), emptySet(), drift = true, nowMs = 24 * 3_600_000L)
        assertEquals(listOf(PumpBtAlert(PumpBtAlertKind.CLOCK_DRIFT)), a7)
        // kept in the link state file
        val f = File(app.cacheDir, "alerts-test.json").apply { delete() }
        PumpBtStore(f, null).alerts = m4.copy(bondLostTold = true)
        assertEquals(m4.copy(bondLostTold = true), PumpBtStore(f, null).alerts)
    }

    @Test
    fun aForgottenPumpIsToldOncePerLossAndTheFlagStays() {
        // paired, the phone lost the bond: the mark and one notification; again (next start) - the mark, no notification
        val lost = PumpBtAlertRules.bond(paired = true, bonded = false, told = false)
        assertEquals(BondCheck(lost = true, alert = true, told = true), lost)
        assertEquals(BondCheck(lost = true, alert = false, told = true), PumpBtAlertRules.bond(true, false, lost.told))
        // not known (Bluetooth off, no permission): the mark stays as it is, nothing told
        assertEquals(BondCheck(lost = null, alert = false, told = true), PumpBtAlertRules.bond(true, null, true))
        // bonded again: the mark goes; a later loss is told again
        val back = PumpBtAlertRules.bond(true, true, true)
        assertEquals(BondCheck(lost = false, alert = false, told = false), back)
        assertTrue(PumpBtAlertRules.bond(true, false, back.told).alert)
        // never paired: nothing lost
        assertEquals(BondCheck(lost = false, alert = false, told = false), PumpBtAlertRules.bond(false, false, false))
    }

    // --- notifications and the journal --------------------------------------------------------------------------------

    @Test
    fun unknownAndUnparsedDoseRecordsAreNotifiedOncePerType() {
        enable(ms(11, 0))
        val recs = listOf(
            ref(100, 3600, 11),
            B2Fixtures.raw(0x0123, 101, 60, "0102"), // a type nobody knows
            B2Fixtures.raw(0x005A, 102, 70, "0100"), // a bolus record too short
            B2Fixtures.raw(0xF010, 103, 80, "00" + "0500" + "02f0" + "33" + "00000000"), // annunciation
        )
        accept(cycle(recs, readAtMs = ms(12, 5), oldest = 100))
        accept(cycle(listOf(B2Fixtures.raw(0x0123, 104, 90, "0102"), B2Fixtures.raw(0x005A, 105, 95, "01")), readAtMs = ms(12, 10), newest = 105))
        assertEquals(
            listOf(PumpBtAlert(PumpBtAlertKind.UNKNOWN_RECORD, 0x0123), PumpBtAlert(PumpBtAlertKind.UNPARSED_BOLUS, 0x005A)),
            alerts.toList(),
        )
        // what was told is in the link state file, not in the history memory
        assertEquals(setOf(0x0123), PumpBtSource.store(app).alerts.unknownTypes)
        assertEquals(setOf(0x005A), PumpBtSource.store(app).alerts.unparsedTypes)
        assertTrue(onDisk().seenUnknownTypes.isEmpty())
        // after a death: still once
        restart()
        accept(cycle(listOf(B2Fixtures.raw(0x0123, 106, 99, "0102")), readAtMs = ms(12, 15), newest = 106))
        assertEquals(2, alerts.size)
        val lines = PumpBtJournal.tail.value.joinToString("\n")
        assertTrue(lines, lines.contains("record #102 type 0x005A not parsed"))
        assertTrue(lines, lines.contains("unknown record types 0x0123"))
        assertTrue(lines, lines.contains("annunciation records type"))
    }

    @Test
    fun theLinkJournalOfACycleHoldsNoDoses() {
        enable(ms(11, 0))
        accept(cycle(listOf(ref(100, 3600, 11)) + bolus(101, 1200, id = 1, tenths = 37), readAtMs = ms(12, 5), oldest = 100))
        val lines = PumpBtJournal.tail.value.filter { "source:" in it }.joinToString("\n")
        assertTrue(lines, lines.contains("fed 1 boluses, 0 meals"))
        assertFalse(lines, lines.contains("3.7"))
        assertFalse(lines, lines.contains("3,7"))
    }

    // --- the link state file ------------------------------------------------------------------------------------------

    @Test
    fun theLinkStateMovesFromTheOldPreferencesOnceAndOutlivesTheirImport() {
        val prefs = app.getSharedPreferences("mod_pumpbt", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("name_digits", "042117").putString("name_variant", "B").putBoolean("paired", true)
            .putString("pump_address", "00:11:22:33:44:55").commit()
        val tmp = File(app.cacheDir, "link-state-test").apply { deleteRecursively(); mkdirs() }
        val file = File(tmp, "link.json")
        val first = PumpBtStore(file, prefs)
        assertEquals("042117", first.nameDigits)
        assertEquals(PumpBtNameVariant.B, first.variant)
        assertTrue(first.paired)
        assertEquals("00:11:22:33:44:55", first.pumpAddress)
        assertFalse(first.verbose)
        assertTrue(file.isFile)
        first.verbose = true
        first.variant = PumpBtNameVariant.A

        // upstream's settings import clears the preferences (or writes old values back): the file stays the truth
        prefs.edit().clear().putString("name_digits", "999999").commit()
        val second = PumpBtStore(file, prefs)
        assertEquals("042117", second.nameDigits)
        assertEquals(PumpBtNameVariant.A, second.variant)
        assertTrue(second.verbose)
        assertTrue(second.paired)

        // nothing anywhere: new digits, made once
        val fresh = PumpBtStore(File(tmp, "other.json"), null)
        val digits = fresh.nameDigits
        assertTrue(digits.length == 6 && digits.all(Char::isDigit))
        assertEquals(digits, PumpBtStore(File(tmp, "other.json"), null).nameDigits)
        assertFalse(fresh.paired)
    }
}
