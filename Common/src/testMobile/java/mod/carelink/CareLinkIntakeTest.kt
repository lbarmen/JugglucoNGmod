package mod.carelink

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
import tk.glucodata.drivers.api.ApiIobSnapshot
import tk.glucodata.mod.carelink.BasalOpen
import tk.glucodata.mod.carelink.CareLinkBasalWriter
import tk.glucodata.mod.carelink.CareLinkSeenStore
import tk.glucodata.mod.carelink.SeenData
import tk.glucodata.mod.carelink.CareLinkClock
import tk.glucodata.mod.carelink.CareLinkErrorKind
import tk.glucodata.mod.carelink.CareLinkIntake
import tk.glucodata.mod.carelink.CareLinkNotifier
import tk.glucodata.mod.carelink.CareLinkSettings
import tk.glucodata.mod.carelink.CareLinkState
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.carelink.CareLinkStatus
import tk.glucodata.mod.carelink.ClEvent
import tk.glucodata.mod.carelink.ClKind
import tk.glucodata.mod.carelink.IntakeOutcome
import tk.glucodata.mod.carelink.IntakeSource
import tk.glucodata.mod.carelink.PumpNow
import tk.glucodata.mod.carelink.PumpSourceSwitch
import tk.glucodata.mod.pumpbt.PumpBtBridge
import tk.glucodata.mod.pumpbt.core.PumpCycle
import java.util.Collections

/**
 * JugglucoNGmod, pump over Bluetooth part B1 (the cloud's tests left with the cloud in part D1): the common intake of
 * pump data - one writer at a time, the source turned off during a cycle, older and partial snapshots, and the outcomes
 * WRITTEN / SKIPPED / FAILED. Made-up doses and times only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkIntakeTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var now = 1_800_000_000_000L

    private val journal = Collections.synchronizedList(mutableListOf<List<ClEvent>>())
    private val basal = Collections.synchronizedList(mutableListOf<PumpNow?>())
    private val iob = Collections.synchronizedList(mutableListOf<PumpNow?>())
    private var closed = 0
    private val stops = Collections.synchronizedList(mutableListOf<PumpNow>())

    private val store get() = CareLinkStateStore(app)
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private fun shown(id: Int) = nm.activeNotifications.firstOrNull { it.id == id }?.notification

    private fun bolus(atMs: Long, units: Double = 2.0) = ClEvent(
        ClKind.BOLUS, "INSULIN|2027-01-15T10:30:00|0", atMs, units, units, true, ClEvent.BOLUS_FAST,
    )

    private fun pump(atMs: Long, iobUnits: Double = 1.5) =
        PumpNow(atMs, iobUnits = iobUnits, iobAtMs = atMs, reservoirUnits = 120.0, basalRate = 0.7)

    private fun accept(events: List<ClEvent> = listOf(bolus(now - 30 * 60_000L)), pump: PumpNow? = pump(now - 60_000L)) =
        runBlocking { CareLinkIntake.accept(app, IntakeSource.BLUETOOTH, events, pump, now) }

    private fun bluetooth(on: Boolean, sinceMs: Long = now) = PumpSourceSwitch.set(app, on, sinceMs)

    /** Data a source wrote before: an IoB plate, a data time, a snapshot. */
    private fun seed(dataAt: Long) =
        store.write(CareLinkState(CareLinkStatus.OK, dataAt, dataAt, null, pump(dataAt, iobUnits = 0.9)))

    @Before
    fun setUp() {
        CareLinkIntake.resetForTest()
        PumpSourceSwitch.override = null
        ApiIobSnapshot.clear()
        CareLinkClock.now = { now }
        CareLinkIntake.writeJournal = { _, e, _ -> journal += e }
        CareLinkIntake.writeBasal = { _, p, _ -> basal += p }
        CareLinkIntake.publishIob = { p -> iob += p }
        CareLinkIntake.closeBasal = { closed++ }
        CareLinkIntake.endStop = { _, p, _ -> stops += p }
        PumpSourceSwitch.set(app, false, now)
    }

    @After
    fun tearDown() {
        CareLinkIntake.resetForTest()
        CareLinkClock.now = { System.currentTimeMillis() }
        PumpSourceSwitch.override = null
        ApiIobSnapshot.clear()
    }

    @Test
    fun twoCyclesAtOnceTakeTurns() {
        // the writers' memory is made for one writer: the second cycle waits until the first has finished all three
        // (a latch, not a pause: the first, inside its journal write, waits for the second to come in - which it must not
        // before the first lets the lock go)
        bluetooth(true)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val inFirst = CompletableDeferred<Unit>()
        val secondCalling = CompletableDeferred<Unit>()
        val secondIn = CompletableDeferred<Unit>()
        var cameIn = false
        CareLinkIntake.writeBasal = { _, p, _ ->
            order += "basal ${p!!.reservoirUnits}"
            if (p.reservoirUnits == 2.0) secondIn.complete(Unit)
        }
        CareLinkIntake.writeJournal = { _, e, _ ->
            order += "journal in ${e.size}"
            if (e.size == 1) {
                inFirst.complete(Unit)
                secondCalling.await()
                cameIn = withTimeoutOrNull(1_000) { secondIn.await() } != null
            }
            order += "journal out ${e.size}"
        }
        CareLinkIntake.publishIob = { p -> order += "iob ${p!!.reservoirUnits}" }
        val first = pump(now - 60_000L).copy(reservoirUnits = 1.0)
        val second = pump(now).copy(reservoirUnits = 2.0)
        val results = runBlocking {
            withTimeout(10_000) {
                val a = async(Dispatchers.IO) {
                    CareLinkIntake.accept(app, IntakeSource.BLUETOOTH, listOf(bolus(now - 60_000L)), first, now)
                }
                inFirst.await()
                val b = async(Dispatchers.IO) {
                    secondCalling.complete(Unit)
                    CareLinkIntake.accept(app, IntakeSource.BLUETOOTH,
                        listOf(bolus(now - 60_000L), bolus(now - 30_000L, 1.0)), second, now)
                }
                listOf(a.await(), b.await())
            }
        }
        assertFalse("the second came in while the first wrote", cameIn)
        assertEquals(
            listOf("basal 1.0", "journal in 1", "journal out 1", "iob 1.0", "basal 2.0", "journal in 2", "journal out 2", "iob 2.0"),
            order.toList(),
        )
        assertTrue(results.all { it.outcome == IntakeOutcome.WRITTEN && it.journalAccepted })
        assertEquals(2.0, store.read().pumpNow!!.reservoirUnits!!, 1e-9)
    }

    @Test
    fun bluetoothWritesWithCareLinkSignedOut() {
        // the person signed out of CareLink (27.11), and the cloud left the app: the Bluetooth source writes regardless
        bluetooth(true, now - 10 * 60_000L)
        val r = accept()
        assertEquals(IntakeOutcome.WRITTEN, r.outcome)
        assertTrue(r.journalAccepted)
        assertNull(r.error)
        assertEquals(1, journal.size)
        assertEquals(1, basal.size)
        assertEquals(1.5, iob.single()!!.iobUnits!!, 1e-9)
        assertEquals(CareLinkStatus.OK, r.state.status)
        assertEquals(now - 60_000L, r.state.lastDataAtMs)
        assertEquals(now, r.state.lastPollAtMs)
        assertEquals(r.state, store.read())
    }

    @Test
    fun theSourceTurnedOffDuringTheCycleWritesNothing() {
        bluetooth(true)
        seed(now - 10 * 60_000L)
        val before = store.read()
        PumpSourceSwitch.override = { false }
        val r = accept()
        assertEquals(IntakeOutcome.SKIPPED, r.outcome)
        assertFalse(r.journalAccepted)
        assertTrue(journal.isEmpty() && basal.isEmpty() && iob.isEmpty())
        assertEquals(before, store.read())
        // turned off while the writers ran: the journal row stands (accepted - the memory lets its records go), but no
        // IoB, no state and no notification check after it (the "no data" mark, which afterPoll would clear with the
        // source off, stays)
        PumpSourceSwitch.override = null
        store.noDataShown = true
        CareLinkIntake.writeBasal = { _, p, _ ->
            basal += p
            bluetooth(false)
        }
        val during = accept()
        assertEquals(IntakeOutcome.WRITTEN, during.outcome)
        assertTrue(during.journalAccepted)
        assertNull(during.error)
        assertEquals(1, journal.size)
        assertTrue(iob.isEmpty())
        assertEquals(before, during.state)
        assertEquals(before, store.read())
        assertTrue("afterPoll did not run", store.noDataShown)
    }

    @Test
    fun theSwitchIsAFileThatASettingsImportDoesNotTouch() {
        // critic B1, C1: upstream's settings import clears every shared_prefs file its export does not carry
        bluetooth(true, now - 3_600_000L)
        app.getSharedPreferences("mod_pumpbt", 0).edit().putBoolean("source_on", false).commit()
        for (name in listOf("mod_pumpbt", CareLinkSettings.PREFS, CareLinkStateStore.PREFS)) {
            app.getSharedPreferences(name, 0).edit().clear().commit()
        }
        assertTrue(PumpSourceSwitch.bluetoothOn(app))
        assertEquals(now - 3_600_000L, PumpSourceSwitch.onSinceMs(app))
        // turning on again keeps the moment; off - no moment; on again - a new one
        bluetooth(true, now)
        assertEquals(now - 3_600_000L, PumpSourceSwitch.onSinceMs(app))
        bluetooth(false)
        assertFalse(PumpSourceSwitch.bluetoothOn(app))
        assertNull(PumpSourceSwitch.onSinceMs(app))
        bluetooth(true, now)
        assertEquals(now, PumpSourceSwitch.onSinceMs(app))
        assertTrue(java.io.File(app.noBackupFilesDir, "mod-pumpbt/source_on").isFile)
    }

    @Test
    fun aCallerCancelledDuringTheJournalWriteGetsAnExceptionAfterTheWrites() {
        // critic B1, N2: the writers are not cancelled and finish with the state; the caller gets an exception instead
        // of the result - "outcome unknown, do not let the records go" (the KDoc of accept)
        bluetooth(true)
        val inJournal = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        CareLinkIntake.writeJournal = { _, e, _ ->
            inJournal.complete(Unit)
            release.await()
            journal += e
        }
        var got: Result<tk.glucodata.mod.carelink.IntakeResult>? = null
        runBlocking {
            withTimeout(10_000) {
                val job = launch(Dispatchers.IO) {
                    got = runCatching {
                        CareLinkIntake.accept(app, IntakeSource.BLUETOOTH, listOf(bolus(now - 60_000L)), pump(now - 60_000L), now)
                    }
                }
                inJournal.await()
                job.cancel()
                release.complete(Unit)
                job.join()
            }
        }
        val error = got!!.exceptionOrNull()
        assertTrue("an exception, not a result: $got", error is kotlinx.coroutines.CancellationException)
        assertEquals("the journal write ran to its end", 1, journal.size)
        assertEquals("the IoB after it too", 1, iob.size)
        assertEquals("the state was stored", now - 60_000L, store.read().lastDataAtMs)
        assertFalse(CareLinkIntake.lock.isLocked)
    }

    @Test
    fun anOlderBluetoothSnapshotDropsOnlyItself() {
        // critic of the joints 1, N-3: the stored snapshot may be the cloud's (server time) or one from before the phone's
        // clock went back - the doses of Bluetooth are written all the same, without the old snapshot
        bluetooth(true)
        seed(now - 60_000L)
        val r = accept(pump = pump(now - 5 * 60_000L, iobUnits = 3.0))
        assertEquals(IntakeOutcome.WRITTEN, r.outcome)
        assertTrue(r.journalAccepted)
        assertEquals(1, journal.size)
        assertTrue("no basal writer", basal.isEmpty())
        assertTrue("no IoB of the old snapshot", iob.all { it == null })
        assertEquals(now - 60_000L, store.read().pumpNow!!.dataAtMs)
        assertEquals(0.9, store.read().pumpNow!!.iobUnits!!, 1e-9)
        assertEquals("the data time stays", now - 60_000L, store.read().lastDataAtMs)
    }

    @Test
    fun noBluetoothSnapshotWritesTheEventsWithoutTheBasalWriter() {
        // critic of the joints 1, C-1: a cycle that read nothing of the pump's state
        bluetooth(true)
        seed(now - 10 * 60_000L)
        val r = accept(pump = null)
        assertEquals(IntakeOutcome.WRITTEN, r.outcome)
        assertTrue(r.journalAccepted)
        assertEquals(1, journal.size)
        assertTrue("the basal writer is not called: one empty cycle is not a basal gap", basal.isEmpty())
        assertEquals("the data time does not move", now - 10 * 60_000L, store.read().lastDataAtMs)
        assertEquals(now - 10 * 60_000L, store.read().pumpNow!!.dataAtMs)
    }

    @Test
    fun aBluetoothSnapshotWithoutTheBasalAnswerIsAppliedWithoutTheBasalWriter() {
        bluetooth(true)
        seed(now - 10 * 60_000L)
        val partial = PumpNow(now - 60_000L, iobUnits = 2.2, iobAtMs = now - 60_000L, reservoirUnits = 99.0, suspended = false,
            basalKnown = false)
        val r = accept(pump = partial)
        assertEquals(IntakeOutcome.WRITTEN, r.outcome)
        assertTrue(basal.isEmpty())
        assertEquals(now - 60_000L, store.read().lastDataAtMs)
        assertEquals(2.2, store.read().pumpNow!!.iobUnits!!, 1e-9)
        assertEquals(99.0, store.read().pumpNow!!.reservoirUnits!!, 1e-9)
        assertEquals(2.2, iob.last()!!.iobUnits!!, 1e-9)
        // unknown delivery state: the same
        now += 60_000L
        accept(pump = partial.copy(dataAtMs = now - 30_000L, suspended = null))
        assertTrue(basal.isEmpty())
        // stopped: the writer gets it (a stop is known without the basal answer)
        now += 60_000L
        accept(pump = partial.copy(dataAtMs = now - 30_000L, suspended = true))
        assertEquals(true, basal.single()!!.suspended)
        // with the basal answer: as before
        now += 60_000L
        accept(pump = pump(now))
        assertEquals(2, basal.size)
    }

    @Test
    fun cyclesOfTheLinkThroughTheBridgeCallTheBasalWriterOnlyWithTheBasal() {
        // critic of the joints 1, C-1: good, cut before the basal answer, empty, good - the writer sees two snapshots
        bluetooth(true, now - 60 * 60_000L)
        fun cycle(atMs: Long) = PumpCycle(readAtMs = atMs, serialHash = "pump-a", therapyState = 0x55, iobUnits = 1.0,
            basalRate = 0.8, basalRead = true)
        fun feed(c: PumpCycle) = runBlocking {
            CareLinkIntake.accept(app, IntakeSource.BLUETOOTH, emptyList(), PumpBtBridge.toPumpNow(c), c.readAtMs)
        }
        feed(cycle(now - 15 * 60_000L))
        feed(cycle(now - 10 * 60_000L).copy(basalRead = false, basalRate = null))
        assertEquals(now - 10 * 60_000L, store.read().lastDataAtMs)
        feed(PumpCycle(readAtMs = now - 5 * 60_000L, serialHash = "pump-a"))
        assertEquals("an empty cycle is no fresh data", now - 10 * 60_000L, store.read().lastDataAtMs)
        feed(cycle(now))
        assertEquals(listOf(now - 15 * 60_000L, now), basal.map { it!!.dataAtMs })
        assertEquals(now, store.read().lastDataAtMs)
    }

    @Test
    fun aBluetoothCycleOfBrokenValuesOnlyIsNoFreshData() {
        // critic of the joints 1 fixes, P-N2: negative active insulin, a battery past 100, an unknown delivery state
        bluetooth(true, now - 60 * 60_000L)
        seed(now - 10 * 60_000L)
        val broken = PumpCycle(readAtMs = now, serialHash = "pump-a", therapyState = 0x0F, iobUnits = -1.0,
            reservoirUnits = -5.0, batteryPercent = 255)
        assertNull(PumpBtBridge.toPumpNow(broken))
        runBlocking { CareLinkIntake.accept(app, IntakeSource.BLUETOOTH, emptyList(), PumpBtBridge.toPumpNow(broken), now) }
        assertEquals("the data time does not move", now - 10 * 60_000L, store.read().lastDataAtMs)
        assertEquals(0.9, store.read().pumpNow!!.iobUnits!!, 1e-9)
        assertTrue(basal.isEmpty())
        assertTrue(stops.isEmpty())
    }

    @Test
    fun runningAgainWithoutTheBasalAnswerOnlyEndsAStop() {
        // critic of the joints 1 fixes, P-N1: the pump runs again, the cycle broke off before the basal answer
        bluetooth(true)
        seed(now - 10 * 60_000L)
        val running = PumpNow(now - 60_000L, iobUnits = 1.0, iobAtMs = now - 60_000L, suspended = false, basalKnown = false)
        accept(pump = running)
        assertTrue("not the basal writer", basal.isEmpty())
        assertEquals(listOf(now - 60_000L), stops.map { it.dataAtMs })
        // delivery state not known, stopped, with the basal answer, no snapshot: not this way
        now += 60_000L
        accept(pump = running.copy(dataAtMs = now - 30_000L, suspended = null))
        now += 60_000L
        accept(pump = running.copy(dataAtMs = now - 30_000L, suspended = true))
        now += 60_000L
        accept(pump = pump(now - 30_000L).copy(suspended = false))
        now += 60_000L
        accept(pump = null)
        assertEquals(1, stops.size)
        assertEquals(2, basal.size)
    }

    @Test
    fun runningAgainWithoutTheBasalAnswerAndNoStopLeavesTheSegment() {
        // the real writer: no stop note open - the open segment and the writer's memory stay as they are (no journal)
        bluetooth(true)
        seed(now - 10 * 60_000L)
        val open = BasalOpen(CareLinkBasalWriter.basalKey(now - 20 * 60_000L), now - 20 * 60_000L, 0.7, now + 20 * 60_000L,
            now - 10 * 60_000L)
        CareLinkSeenStore.save(app, SeenData(basalOpen = open), now)
        CareLinkIntake.resetForTest()
        CareLinkIntake.writeJournal = { _, e, _ -> journal += e }
        CareLinkIntake.publishIob = { p -> iob += p }
        val r = accept(pump = PumpNow(now - 60_000L, iobUnits = 1.0, iobAtMs = now - 60_000L, suspended = false, basalKnown = false))
        assertNull(r.error)
        assertEquals(open, CareLinkSeenStore.load(app, now).basalOpen)
        assertNull(CareLinkSeenStore.load(app, now).stopOpen)
    }

    @Test
    fun anOlderBluetoothSnapshotLeavesTheOpenBasalSegmentAlone() {
        // critic of the joints 1 fixes, P-N7 (c): the real basal writer; an older snapshot (with the basal answer, or
        // running without it) must not touch the open segment
        bluetooth(true)
        seed(now - 60_000L)
        val open = BasalOpen(CareLinkBasalWriter.basalKey(now - 20 * 60_000L), now - 20 * 60_000L, 0.7, now + 29 * 60_000L,
            now - 60_000L)
        CareLinkSeenStore.save(app, SeenData(basalOpen = open), now)
        CareLinkIntake.resetForTest()
        CareLinkIntake.writeJournal = { _, e, _ -> journal += e }
        CareLinkIntake.publishIob = { p -> iob += p }
        val r = accept(pump = pump(now - 5 * 60_000L, iobUnits = 3.0).copy(basalRate = 1.4))
        assertNull(r.error)
        assertEquals(1, journal.size)
        assertEquals(open, CareLinkSeenStore.load(app, now).basalOpen)
        val r2 = accept(pump = PumpNow(now - 4 * 60_000L, iobUnits = 3.0, iobAtMs = now - 4 * 60_000L, suspended = false,
            basalKnown = false))
        assertNull(r2.error)
        assertEquals(open, CareLinkSeenStore.load(app, now).basalOpen)
        assertEquals("the data time stays", now - 60_000L, store.read().lastDataAtMs)
    }

    @Test
    fun aFailedJournalWriteIsFailedAndWarnsWhileTheSourceIsOn() {
        // CareLink signed out: "pump records are not saved" lives for the Bluetooth source
        CareLinkSettings(app).warnAfterMin = 5
        bluetooth(true, now - 60 * 60_000L)
        CareLinkIntake.writeJournal = { _, _, _ -> throw IllegalStateException("made-up") }
        val r = accept()
        assertEquals(IntakeOutcome.FAILED, r.outcome)
        assertFalse(r.journalAccepted)
        assertEquals(CareLinkErrorKind.WRITE, r.error!!.kind)
        assertEquals("IllegalStateException", r.error.errorClass)
        assertEquals(CareLinkStatus.ERROR, r.state.status)
        assertEquals("the data came", now - 60_000L, r.state.lastDataAtMs)
        assertEquals("the basal and the IoB go on", 1, basal.size)
        assertEquals(1, iob.size)
        assertEquals(now, store.writeFailSinceMs)
        assertNull(shown(CareLinkNotifier.ID_WRITE_FAIL))
        now += 5 * 60_000L
        accept(pump = pump(now - 60_000L))
        assertNotNull(shown(CareLinkNotifier.ID_WRITE_FAIL))
        // a good write takes it away
        CareLinkIntake.writeJournal = { _, e, _ -> journal += e }
        now += 60_000L
        assertEquals(IntakeOutcome.WRITTEN, accept(pump = pump(now - 60_000L)).outcome)
        assertNull(shown(CareLinkNotifier.ID_WRITE_FAIL))
        assertNull(store.writeFailSinceMs)
    }

    @Test
    fun aFailedBasalWriteIsWrittenWithAnError() {
        bluetooth(true)
        CareLinkIntake.writeBasal = { _, _, _ -> throw java.io.IOException("made-up") }
        val r = accept()
        assertEquals(IntakeOutcome.WRITTEN, r.outcome)
        assertTrue(r.journalAccepted)
        assertEquals(CareLinkErrorKind.WRITE, r.error!!.kind)
        assertEquals("IOException", r.error.errorClass)
        assertEquals(1, journal.size)
        assertEquals(1, iob.size)
        assertEquals(CareLinkStatus.ERROR, r.state.status)
    }

    @Test
    fun noPumpDataCountsFromTheSourceTurnedOn() {
        // CareLink signed out, the source on 40 min ago with data of the cloud from hours before: not "no data" at once,
        // but threshold + 10 min after turning on (the watchdog calls this without a poll). The threshold 30 min: above
        // the Bluetooth floor of 20 min (critic B2c, C2), so the person's own counts
        CareLinkSettings(app).warnAfterMin = 30
        bluetooth(true, now)
        store.write(CareLinkState(CareLinkStatus.OFF, null, now - 5 * 3_600_000L, null, null))
        CareLinkNotifier.checkNoData(app, store.read(), now + 39 * 60_000L)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        CareLinkNotifier.checkNoData(app, store.read(), now + 40 * 60_000L)
        assertNotNull(shown(CareLinkNotifier.ID_NO_DATA))
        // data comes: away; it gets old: back
        now += 41 * 60_000L
        accept(pump = pump(now - 60_000L))
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        CareLinkNotifier.checkNoData(app, store.read(), now + 31 * 60_000L)
        assertNotNull(shown(CareLinkNotifier.ID_NO_DATA))
        // the source off and CareLink signed out: nothing
        bluetooth(false)
        CareLinkNotifier.checkNoData(app, store.read(), now + 32 * 60_000L)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        assertNull(PumpSourceSwitch.onSinceMs(app))
    }

    private fun noDataTitle() = shown(CareLinkNotifier.ID_NO_DATA)?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    @Test
    fun overBluetoothNoDataWaitsAtLeastTwentyMinutes() {
        // critic B2c, C2: the pump comes about every 8 min - a threshold of 5 or 10 min chosen for the cloud would warn
        // between two reads; at least 20 min (two reads missed), the title names the threshold in force
        bluetooth(true, now - 3_600_000L)
        for (min in listOf(5, 10)) {
            CareLinkSettings(app).warnAfterMin = min
            nm.cancelAll()
            store.write(CareLinkState(CareLinkStatus.OFF, lastDataAtMs = now))
            store.noDataShown = false
            CareLinkNotifier.checkNoData(app, store.read(), now + 12 * 60_000L)
            assertNull("threshold $min", shown(CareLinkNotifier.ID_NO_DATA))
            CareLinkNotifier.checkNoData(app, store.read(), now + 19 * 60_000L)
            assertNull("threshold $min", shown(CareLinkNotifier.ID_NO_DATA))
            CareLinkNotifier.checkNoData(app, store.read(), now + 21 * 60_000L)
            assertNotNull("threshold $min", shown(CareLinkNotifier.ID_NO_DATA))
            assertTrue(noDataTitle(), noDataTitle()!!.contains(" 20 "))
        }
        // a higher threshold of the person counts as it is
        CareLinkSettings(app).warnAfterMin = 30
        nm.cancelAll()
        store.write(CareLinkState(CareLinkStatus.OFF, lastDataAtMs = now))
        store.noDataShown = false
        CareLinkNotifier.checkNoData(app, store.read(), now + 29 * 60_000L)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        CareLinkNotifier.checkNoData(app, store.read(), now + 30 * 60_000L)
        assertNotNull(shown(CareLinkNotifier.ID_NO_DATA))
        assertTrue(noDataTitle(), noDataTitle()!!.contains(" 30 "))
    }
}
