package mod.carelink

import android.app.Application
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
import mod.carelink.CareLinkTestData.CARELINK_BASE
import mod.carelink.CareLinkTestData.NO_NETWORK
import mod.carelink.CareLinkTestData.TOKEN_URL
import mod.carelink.CareLinkTestData.access
import mod.carelink.CareLinkTestData.ok
import mod.carelink.CareLinkTestData.status
import tk.glucodata.drivers.api.ApiIobSnapshot
import tk.glucodata.mod.carelink.AesGcmSessionCipher
import tk.glucodata.mod.carelink.CareLinkAuth
import tk.glucodata.mod.carelink.CareLinkClock
import tk.glucodata.mod.carelink.CareLinkErrorKind
import tk.glucodata.mod.carelink.CareLinkIntake
import tk.glucodata.mod.carelink.CareLinkIob
import tk.glucodata.mod.carelink.CareLinkNotifier
import tk.glucodata.mod.carelink.CareLinkPoller
import tk.glucodata.mod.carelink.CareLinkScheduler
import tk.glucodata.mod.carelink.CareLinkSession
import tk.glucodata.mod.carelink.CareLinkSettings
import tk.glucodata.mod.carelink.CareLinkState
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.carelink.CareLinkStatus
import tk.glucodata.mod.carelink.CareLinkVault
import tk.glucodata.mod.carelink.ClEvent
import tk.glucodata.mod.carelink.ClKind
import tk.glucodata.mod.carelink.IntakeOutcome
import tk.glucodata.mod.carelink.IntakeSource
import tk.glucodata.mod.carelink.ModCareLinkStarter
import tk.glucodata.mod.carelink.PollReason
import tk.glucodata.mod.carelink.PumpNow
import tk.glucodata.mod.carelink.PumpSourceSwitch
import java.util.Collections
import javax.crypto.KeyGenerator

/**
 * JugglucoNGmod, pump over Bluetooth part B1: the common intake of pump data and the cloud's silence while the
 * Bluetooth source is on (series brief, decision 17) - one writer at a time, the five cloud entries do nothing, a
 * CareLink sign-out touches nothing, Bluetooth writes without CareLink, and the outcomes WRITTEN / SKIPPED / FAILED.
 * Made-up doses, times and user only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkIntakeTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val net = FakeCareLinkHttp()
    private val work = FakeCareLinkWork()
    private var now = 1_800_000_000_000L
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cumulus = "https://cumulus.example.eu/connect/carepartner/v13"
    private val dm = "$cumulus/display/message"

    private val journal = Collections.synchronizedList(mutableListOf<List<ClEvent>>())
    private val basal = Collections.synchronizedList(mutableListOf<PumpNow?>())
    private val iob = Collections.synchronizedList(mutableListOf<PumpNow?>())
    private var closed = 0

    private val store get() = CareLinkStateStore(app)
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private fun shown(id: Int) = nm.activeNotifications.firstOrNull { it.id == id }?.notification

    private fun session() = CareLinkSession(
        accessToken = access((now + 2 * 3_600_000L) / 1000), refreshToken = "made-up-refresh-1",
        expiresAtMs = now + 2 * 3_600_000L, username = "tester", role = "PATIENT", patientId = null, country = "DE",
        region = "EU", baseUrlCareLink = CARELINK_BASE, baseUrlCumulus = cumulus, tokenUrl = TOKEN_URL,
        clientId = "abc123", loggedInAtMs = now - 3_600_000L,
    )

    private fun bolus(atMs: Long, units: Double = 2.0) = ClEvent(
        ClKind.BOLUS, "INSULIN|2027-01-15T10:30:00|0", atMs, units, units, true, ClEvent.BOLUS_FAST,
    )

    private fun pump(atMs: Long, iobUnits: Double = 1.5) =
        PumpNow(atMs, iobUnits = iobUnits, iobAtMs = atMs, reservoirUnits = 120.0, basalRate = 0.7)

    private fun accept(events: List<ClEvent> = listOf(bolus(now - 30 * 60_000L)), pump: PumpNow? = pump(now - 60_000L)) =
        runBlocking { CareLinkIntake.accept(app, IntakeSource.BLUETOOTH, events, pump, now) }

    private fun bluetooth(on: Boolean, sinceMs: Long = now) = PumpSourceSwitch.set(app, on, sinceMs)

    /** A made-up cloud answer: one finished 2 U bolus, pump data 3 min before now, reservoir 150 U. */
    private fun answer() = """
        {"metadata": {"kind": "BLENGP", "clientDateTime": "2027-01-15T11:00:00.000+03:00"},
         "patientData": {
           "lastMedicalDeviceDataUpdateServerTime": ${now - 3 * 60_000L},
           "activeInsulin": {"amount": 1.5}, "reservoirRemainingUnits": 150.0, "pumpBatteryLevelPercent": 60,
           "basal": {"basalRate": 0.8},
           "markers": [{"type": "INSULIN", "timestamp": "2027-01-15T10:30:00",
                        "data": {"dataValues": {"programmedFastAmount": 2.0, "deliveredFastAmount": 2.0, "completed": true}}}]
         }}
    """.trimIndent()

    /** A live CareLink session with polling on. */
    private fun signedIn() {
        CareLinkVault.save(app, session())
        CareLinkSettings(app).enabled = true
    }

    /** Data a source wrote before: an IoB plate, a data time, a snapshot. */
    private fun seed(dataAt: Long) =
        store.write(CareLinkState(CareLinkStatus.OK, dataAt, dataAt, null, pump(dataAt, iobUnits = 0.9)))

    @Before
    fun setUp() {
        CareLinkAuth.resetForTest()
        CareLinkPoller.resetForTest()
        CareLinkScheduler.resetForTest()
        PumpSourceSwitch.override = null
        ApiIobSnapshot.clear()
        CareLinkAuth.http = net
        CareLinkClock.now = { now }
        CareLinkScheduler.work = work
        CareLinkIntake.writeJournal = { _, e, _ -> journal += e }
        CareLinkIntake.writeBasal = { _, p, _ -> basal += p }
        CareLinkIntake.publishIob = { p -> iob += p }
        CareLinkIntake.closeBasal = { closed++ }
        CareLinkVault.cipher = AesGcmSessionCipher(keyProvider = { key })
        CareLinkVault.clear(app)
        PumpSourceSwitch.set(app, false, now)
    }

    @After
    fun tearDown() {
        CareLinkVault.clear(app)
        CareLinkAuth.resetForTest()
        CareLinkPoller.resetForTest()
        CareLinkScheduler.resetForTest()
        PumpSourceSwitch.override = null
        ApiIobSnapshot.clear()
        CareLinkVault.cipher = AesGcmSessionCipher.keystore
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
    fun theFiveCloudEntriesAreSilentWithBluetoothOn() {
        // decision 17: a live session and polling on, but the Bluetooth source is on - no request, nothing written,
        // no alarm, no work, the state as it was
        CareLinkVault.save(app, session())
        CareLinkSettings(app).enabled = true
        net.on(dm, ok("{}"))
        seed(now - 2 * 60_000L)
        val before = store.read()
        bluetooth(true, now - 60 * 60_000L)
        for (reason in PollReason.values()) {
            val s = runBlocking { CareLinkPoller.poll(app, reason, firstPoll = reason == PollReason.LOGIN) }
            assertEquals(reason.name, before, s)
            assertNull("nothing queued: $reason", CareLinkScheduler.enqueuePoll(app, reason))
        }
        assertEquals(5, PollReason.values().size)
        assertTrue(net.calls.isEmpty())
        assertTrue(journal.isEmpty() && basal.isEmpty() && iob.isEmpty())
        assertEquals(before, store.read())
        // the chain is not armed, and what was armed goes
        assertFalse(CareLinkScheduler.arm(app, force = true))
        assertFalse(work.log.toString(), work.log.any { it == "periodic" || it.startsWith("poll") })
        assertTrue(work.log.contains("cancel"))
        // a sign-in while the source is on: the source's snapshot and data time stay, nothing is queued
        work.log.clear()
        ModCareLinkStarter.afterLogin(app)
        assertEquals(before.lastDataAtMs, store.read().lastDataAtMs)
        assertEquals(before.pumpNow, store.read().pumpNow)
        assertFalse(work.log.toString(), work.log.any { it == "periodic" || it.startsWith("poll") })
        assertTrue("polling marked on for later", CareLinkSettings(app).enabled)
        // "sign in again" is not shown while the source is on
        CareLinkNotifier.needLogin(app)
        assertNull(shown(CareLinkNotifier.ID_NEED_LOGIN))
        // the source off: the cloud polls again
        bluetooth(false)
        runBlocking { CareLinkPoller.poll(app, PollReason.MANUAL) }
        assertEquals(1, net.calls.size)
    }

    @Test
    fun signOutOfCareLinkWithBluetoothOnTouchesNothing() {
        // decision 17 and the person's sign-out 27.11: the basal segment, the IoB plate, the data time and the
        // source's warnings stay
        CareLinkVault.save(app, session())
        CareLinkSettings(app).enabled = true
        bluetooth(true, now - 60 * 60_000L)
        seed(now - 60_000L)
        CareLinkIob.publish(pump(now - 60_000L))
        store.writeFailSinceMs = now - 2 * 60_000L
        ModCareLinkStarter.afterSignOut(app)
        runBlocking { withTimeout(5_000) { CareLinkPoller.closeAfterSignOut(app).join() } }
        assertFalse(CareLinkSettings(app).enabled)
        assertEquals("the basal is not closed", 0, closed)
        assertNotNull("the IoB plate keeps the pump's number", ApiIobSnapshot.fresh(now))
        assertEquals(now - 60_000L, store.read().lastDataAtMs)
        assertEquals(0.9, store.read().pumpNow!!.iobUnits!!, 1e-9)
        assertEquals("the write clock is the source's", now - 2 * 60_000L, store.writeFailSinceMs)
        // the source off: a sign-out closes as before
        bluetooth(false)
        ModCareLinkStarter.afterSignOut(app)
        runBlocking { withTimeout(5_000) { CareLinkPoller.closeAfterSignOut(app).join() } }
        assertTrue(closed >= 1)
        assertNull(ApiIobSnapshot.fresh(now))
        assertNull(store.read().lastDataAtMs)
    }

    @Test
    fun bluetoothWritesWithCareLinkSignedOut() {
        // the person signed out of CareLink (27.11): the Bluetooth source writes regardless
        assertFalse(CareLinkSettings(app).enabled)
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
        // the cloud intake is off at the same time (polling off, and the source on)
        val cloud = runBlocking { CareLinkIntake.accept(app, IntakeSource.CLOUD, listOf(bolus(now)), pump(now), now) }
        assertEquals(IntakeOutcome.SKIPPED, cloud.outcome)
        assertEquals(1, journal.size)
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
        // IoB, no state and no notification check after it (the "sign in again" mark, which afterPoll would clear, stays)
        PumpSourceSwitch.override = null
        store.needLoginShown = true
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
        assertTrue("afterPoll did not run", store.needLoginShown)
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
    fun bluetoothLeavesTheCloudsFirstPollAndAlarmsAlone() {
        // a live session: arm would at least remove the chain ("cancel") - the Bluetooth intake does not call it, nor
        // clear the cloud's "first poll pending"
        signedIn()
        bluetooth(true)
        store.firstPollPending = true
        work.log.clear()
        assertEquals(IntakeOutcome.WRITTEN, accept().outcome)
        assertTrue(store.firstPollPending)
        assertTrue(work.log.toString(), work.log.isEmpty())
    }

    @Test
    fun aCloudPollAndABluetoothCycleTakeTurns() {
        // one lock for both: the cycle waits for the poll's writers (a latch, as above); the Bluetooth source turned
        // on while the poll wrote - the poll publishes no IoB and stores nothing, the cycle writes after it
        signedIn()
        net.on(dm, ok(answer()))
        val order = Collections.synchronizedList(mutableListOf<String>())
        val inPoll = CompletableDeferred<Unit>()
        val cycleCalling = CompletableDeferred<Unit>()
        val cycleIn = CompletableDeferred<Unit>()
        var cameIn = false
        CareLinkIntake.writeBasal = { _, p, _ ->
            order += "basal ${p?.reservoirUnits}"
            if (p?.reservoirUnits == 2.0) cycleIn.complete(Unit)
        }
        CareLinkIntake.writeJournal = { _, e, _ ->
            val polling = !inPoll.isCompleted
            order += if (polling) "journal poll" else "journal cycle"
            if (polling) {
                inPoll.complete(Unit)
                cycleCalling.await()
                cameIn = withTimeoutOrNull(1_000) { cycleIn.await() } != null
            }
        }
        CareLinkIntake.publishIob = { p -> order += "iob ${p?.reservoirUnits}" }
        val (polled, cycle) = runBlocking {
            withTimeout(10_000) {
                val a = async(Dispatchers.IO) { CareLinkPoller.poll(app, PollReason.MANUAL) }
                inPoll.await()
                bluetooth(true)
                val b = async(Dispatchers.IO) {
                    cycleCalling.complete(Unit)
                    CareLinkIntake.accept(app, IntakeSource.BLUETOOTH, listOf(bolus(now - 60_000L)),
                        pump(now - 60_000L).copy(reservoirUnits = 2.0), now)
                }
                a.await() to b.await()
            }
        }
        assertFalse("the cycle came in while the poll wrote", cameIn)
        assertEquals(listOf("basal 150.0", "journal poll", "basal 2.0", "journal cycle", "iob 2.0"), order.toList())
        assertEquals(CareLinkState(), polled)
        assertEquals(IntakeOutcome.WRITTEN, cycle.outcome)
        assertEquals(2.0, store.read().pumpNow!!.reservoirUnits!!, 1e-9)
        assertEquals(now - 60_000L, store.read().lastDataAtMs)
    }

    @Test
    fun aPollRunningWhenBluetoothIsTurnedOnRecordsNoFailure() {
        // the key refused, renewed; meanwhile the source is turned on; then no network: failed() writes nothing
        signedIn()
        seed(now - 10 * 60_000L)
        val before = store.read()
        net.on(dm, status(401), NO_NETWORK)
        net.on(TOKEN_URL, ok("""{"access_token": "${access(now / 1000 + 10_800)}", "expires_in": 10800}"""))
        net.onForm = { bluetooth(true) }
        val s = runBlocking { CareLinkPoller.poll(app, PollReason.ALARM) }
        assertEquals(listOf("JSON $dm", "FORM $TOKEN_URL refresh_token", "JSON $dm"), net.calls)
        assertEquals(before, s)
        assertEquals(before, store.read())
    }

    @Test
    fun aPollRunningWhenBluetoothIsTurnedOnAsksNoSignIn() {
        // the renewal says invalid_grant after the source was turned on: no "sign in again", the basal and the IoB
        // plate stay, the state stays
        signedIn()
        seed(now - 10 * 60_000L)
        CareLinkIob.publish(pump(now - 60_000L))
        val before = store.read()
        net.on(dm, status(401))
        net.on(TOKEN_URL, status(400, """{"error": "invalid_grant", "error_description": "made-up"}"""))
        net.onForm = { bluetooth(true) }
        val s = runBlocking { CareLinkPoller.poll(app, PollReason.MANUAL) }
        assertEquals(before, s)
        assertEquals(before, store.read())
        assertEquals(0, closed)
        assertNotNull(ApiIobSnapshot.fresh(now))
        assertNull(shown(CareLinkNotifier.ID_NEED_LOGIN))
        assertFalse(work.log.contains("cancel"))
    }

    @Test
    fun aPollWhoseAnswerCameAfterBluetoothWasTurnedOnWritesNothing() {
        // the answer parsed after the source was turned on: the cloud intake is off (SKIPPED), nothing is written
        signedIn()
        seed(now - 10 * 60_000L)
        val before = store.read()
        net.on(dm, ok(answer()))
        CareLinkPoller.zone = {
            bluetooth(true)
            java.time.ZoneOffset.ofHours(5)
        }
        val s = runBlocking { CareLinkPoller.poll(app, PollReason.MANUAL) }
        assertEquals(1, net.calls.size)
        assertTrue(journal.isEmpty() && basal.isEmpty() && iob.isEmpty())
        assertEquals(before, s)
        assertEquals(before, store.read())
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
    fun anOlderSnapshotIsSkipped() {
        bluetooth(true)
        seed(now - 60_000L)
        val r = accept(pump = pump(now - 5 * 60_000L))
        assertEquals(IntakeOutcome.SKIPPED, r.outcome)
        assertFalse(r.journalAccepted)
        assertTrue(journal.isEmpty() && basal.isEmpty())
        assertEquals(now - 60_000L, store.read().pumpNow!!.dataAtMs)
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
        // CareLink signed out, the source on 25 min ago with data of the cloud from hours before: not "no data" at once,
        // but threshold + 10 min after turning on (the watchdog calls this without a poll)
        CareLinkSettings(app).warnAfterMin = 15
        bluetooth(true, now)
        store.write(CareLinkState(CareLinkStatus.OFF, null, now - 5 * 3_600_000L, null, null))
        CareLinkNotifier.checkNoData(app, store.read(), now + 24 * 60_000L)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        CareLinkNotifier.checkNoData(app, store.read(), now + 25 * 60_000L)
        assertNotNull(shown(CareLinkNotifier.ID_NO_DATA))
        // data comes: away; it gets old: back
        now += 26 * 60_000L
        accept(pump = pump(now - 60_000L))
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        CareLinkNotifier.checkNoData(app, store.read(), now + 16 * 60_000L)
        assertNotNull(shown(CareLinkNotifier.ID_NO_DATA))
        // the source off and CareLink signed out: nothing
        bluetooth(false)
        CareLinkNotifier.checkNoData(app, store.read(), now + 17 * 60_000L)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        assertNull(PumpSourceSwitch.onSinceMs(app))
    }
}
