package mod.carelink

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import mod.carelink.CareLinkTestData.CARELINK_BASE
import mod.carelink.CareLinkTestData.NO_NETWORK
import mod.carelink.CareLinkTestData.TOKEN_URL
import mod.carelink.CareLinkTestData.access
import mod.carelink.CareLinkTestData.ok
import mod.carelink.CareLinkTestData.status
import tk.glucodata.mod.carelink.AesGcmSessionCipher
import tk.glucodata.mod.carelink.CareLinkAuth
import tk.glucodata.mod.carelink.CareLinkError
import tk.glucodata.mod.carelink.CareLinkErrorKind
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
import tk.glucodata.mod.carelink.PollReason
import tk.glucodata.mod.carelink.PumpNow
import tk.glucodata.mod.carelink.ModCareLinkStarter
import java.time.ZoneOffset
import javax.crypto.KeyGenerator

/**
 * JugglucoNGmod, CareLink 4c: one poll against a fake network - renewal on 401 and one more try, `invalid_grant` ->
 * sign-in needed and a notification, no network -> error with nothing wiped, a failed writer or an empty answer does not
 * move the data time, when a poll is due, the "no data" notification. Made-up answers, user and doses only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkPollerTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val net = FakeCareLinkHttp()
    private val work = FakeCareLinkWork()
    private var now = 1_800_000_000_000L
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cumulus = "https://cumulus.example.eu/connect/carepartner/v13"
    private val dm = "$cumulus/display/message"

    private val journal = mutableListOf<Pair<List<ClEvent>, Boolean>>()
    private val basal = mutableListOf<Pair<PumpNow?, Long>>()
    private val iob = mutableListOf<PumpNow?>()
    private var closed = 0

    private fun session(role: String = "PATIENT", patientId: String? = null) = CareLinkSession(
        accessToken = access((now + 2 * 3_600_000L) / 1000), refreshToken = "made-up-refresh-1",
        expiresAtMs = now + 2 * 3_600_000L, username = "tester", role = role, patientId = patientId, country = "DE",
        region = "EU", baseUrlCareLink = CARELINK_BASE, baseUrlCumulus = cumulus, tokenUrl = TOKEN_URL,
        clientId = "abc123", loggedInAtMs = now - 3_600_000L,
    )

    /** A made-up answer: one finished 2 U bolus, pump data [lagMin] min before now. */
    private fun answer(lagMin: Long = 3, reservoir: String = "150.0", battery: String = "60") = """
        {"metadata": {"kind": "BLENGP", "clientDateTime": "2027-01-15T11:00:00.000+03:00"},
         "patientData": {
           "lastMedicalDeviceDataUpdateServerTime": ${now - lagMin * 60_000L},
           "activeInsulin": {"amount": 1.5}, "reservoirRemainingUnits": $reservoir, "pumpBatteryLevelPercent": $battery,
           "basal": {"basalRate": 0.8},
           "markers": [{"type": "INSULIN", "timestamp": "2027-01-15T10:30:00",
                        "data": {"dataValues": {"programmedFastAmount": 2.0, "deliveredFastAmount": 2.0, "completed": true}}}]
         }}
    """.trimIndent()

    private val store get() = CareLinkStateStore(app)
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private fun shown(id: Int): Notification? = nm.activeNotifications.firstOrNull { it.id == id }?.notification

    private fun poll(reason: PollReason = PollReason.MANUAL) = runBlocking { CareLinkPoller.poll(app, reason) }

    @Before
    fun setUp() {
        CareLinkAuth.resetForTest()
        CareLinkPoller.resetForTest()
        CareLinkScheduler.resetForTest()
        CareLinkAuth.http = net
        CareLinkAuth.clock = { now }
        CareLinkScheduler.work = work
        CareLinkPoller.zone = { ZoneOffset.ofHours(5) }
        CareLinkPoller.writeJournal = { _, e, first -> journal += e to first }
        CareLinkPoller.writeBasal = { _, p, at -> basal += p to at }
        CareLinkPoller.publishIob = { p -> iob += p }
        CareLinkPoller.closeBasal = { closed++ }
        CareLinkVault.cipher = AesGcmSessionCipher(keyProvider = { key })
        CareLinkVault.clear(app)
        CareLinkVault.save(app, session())
        CareLinkSettings(app).enabled = true
    }

    @After
    fun tearDown() {
        CareLinkVault.clear(app)
        CareLinkAuth.resetForTest()
        CareLinkPoller.resetForTest()
        CareLinkScheduler.resetForTest()
        CareLinkVault.cipher = AesGcmSessionCipher.keystore
    }

    @Test
    fun goodPollWritesAndMovesTheDataTime() {
        net.on(dm, ok(answer()))
        val s = poll()
        assertEquals(CareLinkStatus.OK, s.status)
        assertEquals(now, s.lastPollAtMs)
        assertEquals(now - 3 * 60_000L, s.lastDataAtMs)
        assertNull(s.lastError)
        assertEquals(150.0, s.pumpNow!!.reservoirUnits!!, 1e-9)
        assertEquals(listOf("JSON $dm"), net.calls)
        val body = JSONObject(net.jsons.single())
        assertEquals("tester", body.getString("username"))
        assertEquals("patient", body.getString("role"))
        assertFalse(body.has("patientId"))
        assertEquals(1, journal.single().first.size)
        assertTrue("no data written before: the whole window", journal.single().second)
        assertEquals(now, basal.single().second)
        assertEquals(1.5, basal.single().first!!.iobUnits!!, 1e-9)
        assertEquals(1.5, iob.single()!!.iobUnits!!, 1e-9)
        assertEquals("state is stored", s, store.read())
        // critic 4c, N9: the pump numbers are out of the settings backup (shared_prefs), in noBackupFilesDir
        assertFalse(app.getSharedPreferences(CareLinkStateStore.PREFS, 0).all.keys.any { it.contains("pump", ignoreCase = true) })
        assertTrue(java.io.File(app.noBackupFilesDir, "mod-carelink/pump.json").isFile)
        // the next poll is no longer the first
        poll()
        assertFalse(journal.last().second)
    }

    @Test
    fun carePartnerAsksForThePatient() {
        CareLinkVault.save(app, session(role = "CARE_PARTNER", patientId = "made-up-patient"))
        net.on(dm, ok(answer()))
        poll()
        val body = JSONObject(net.jsons.single())
        assertEquals("carepartner", body.getString("role"))
        assertEquals("made-up-patient", body.getString("patientId"))
    }

    @Test
    fun refusedKeyIsRenewedAndTheRequestRepeatedOnce() {
        net.on(dm, status(401), ok(answer()))
        net.on(TOKEN_URL, ok("""{"access_token": "${access(now / 1000 + 10_800)}", "expires_in": 10800, "refresh_token": "made-up-refresh-2"}"""))
        val s = poll()
        assertEquals(listOf("JSON $dm", "FORM $TOKEN_URL refresh_token", "JSON $dm"), net.calls)
        assertEquals(CareLinkStatus.OK, s.status)
        assertEquals(1, journal.size)
    }

    @Test
    fun refusedAgainAfterRenewalIsAnErrorAndTheSessionStays() {
        net.on(dm, status(403))
        net.on(TOKEN_URL, ok("""{"access_token": "${access(now / 1000 + 10_800)}", "expires_in": 10800}"""))
        val s = poll()
        assertEquals(3, net.calls.size)
        assertEquals(CareLinkStatus.ERROR, s.status)
        assertEquals(CareLinkErrorKind.SERVER, s.lastError!!.kind)
        assertEquals(403, s.lastError!!.code)
        assertTrue(CareLinkVault.exists(app))
        assertTrue(journal.isEmpty())
    }

    @Test
    fun invalidGrantNeedsSignInNotifiesOnceAndStopsTheSchedule() {
        net.on(dm, status(401))
        net.on(TOKEN_URL, status(400, """{"error": "invalid_grant", "error_description": "made-up"}"""))
        val s = poll()
        assertEquals(CareLinkStatus.NEED_LOGIN, s.status)
        assertFalse("session wiped", CareLinkVault.exists(app))
        assertNotNull(shown(CareLinkNotifier.ID_NEED_LOGIN))
        assertTrue("alarms and safety work off", work.log.contains("cancel"))
        // critic of the joints, C2: no data until a sign-in - the open basal segment is closed at its last data
        assertEquals(1, closed)
        // no session: the next poll does nothing, the notification is not posted again after a swipe
        nm.cancel(CareLinkNotifier.ID_NEED_LOGIN)
        net.calls.clear()
        val again = poll()
        assertTrue(net.calls.isEmpty())
        assertEquals(CareLinkStatus.NEED_LOGIN, again.status)
        CareLinkNotifier.needLogin(app)
        assertNull("once until a sign-in", shown(CareLinkNotifier.ID_NEED_LOGIN))
        CareLinkNotifier.loggedIn(app)
        CareLinkNotifier.needLogin(app)
        assertNotNull("after a sign-in it may come again", shown(CareLinkNotifier.ID_NEED_LOGIN))
    }

    private fun seed(dataAt: Long, pump: PumpNow? = PumpNow(dataAt, reservoirUnits = 90.0, basalRate = 0.6)) =
        store.write(CareLinkState(CareLinkStatus.OK, now - 5 * 60_000L, dataAt, null, pump))

    @Test
    fun noNetworkIsAnErrorAndNothingIsWiped() {
        val before = now - 10 * 60_000L
        seed(before)
        net.on(dm, NO_NETWORK)
        val s = poll()
        assertEquals(CareLinkStatus.ERROR, s.status)
        assertEquals(CareLinkErrorKind.NO_NETWORK, s.lastError!!.kind)
        assertEquals("IOException", s.lastError!!.errorClass)
        assertEquals(before, s.lastDataAtMs)
        assertEquals(90.0, s.pumpNow!!.reservoirUnits!!, 1e-9)
        assertEquals(now, s.lastPollAtMs)
        assertTrue(CareLinkVault.exists(app))
        assertTrue(journal.isEmpty())
    }

    @Test
    fun failedJournalWriteIsAnErrorButTheBasalTheIobAndTheDataTimeGoOn() {
        // critic of the joints, C3: one journal row that keeps failing must not stop the basal and the IoB plate, nor
        // bring "no data" in 30 min while the data comes
        val before = now - 10 * 60_000L
        seed(before)
        CareLinkPoller.writeJournal = { _, _, _ -> throw IllegalStateException("made-up") }
        net.on(dm, ok(answer()))
        val s = poll()
        assertEquals(CareLinkStatus.ERROR, s.status)
        assertEquals(CareLinkErrorKind.WRITE, s.lastError!!.kind)
        assertEquals("IllegalStateException", s.lastError!!.errorClass)
        assertEquals("the data came", now - 3 * 60_000L, s.lastDataAtMs)
        assertEquals("the new snapshot", 150.0, s.pumpNow!!.reservoirUnits!!, 1e-9)
        assertEquals(1, basal.size)
        assertEquals(1.5, iob.single()!!.iobUnits!!, 1e-9)
        // 21 min later with no new poll: no "no data" - the data time moved (the old one would be 31 min old)
        CareLinkNotifier.checkNoData(app, store.read(), now + 21 * 60_000L)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
    }

    @Test
    fun failedBasalWriteDoesNotStopTheJournalAndTheIob() {
        val before = now - 10 * 60_000L
        seed(before)
        CareLinkPoller.writeBasal = { _, _, _ -> throw java.io.IOException("made-up") }
        net.on(dm, ok(answer()))
        val s = poll()
        assertEquals(CareLinkStatus.ERROR, s.status)
        assertEquals(CareLinkErrorKind.WRITE, s.lastError!!.kind)
        assertEquals("IOException", s.lastError!!.errorClass)
        assertEquals(now - 3 * 60_000L, s.lastDataAtMs)
        assertEquals(1, journal.size)
        assertEquals(1, iob.size)
        // the next good poll clears the error
        CareLinkPoller.writeBasal = { _, p, at -> basal += p to at }
        assertEquals(CareLinkStatus.OK, poll().status)
        assertNull(store.read().lastError)
    }

    @Test
    fun writesAreNotCancelledHalfWay() {
        // critic of the joints, C1: the work is cancelled (network gone) while the journal writes - the write and its
        // record finish, the cancellation comes after
        net.on(dm, ok(answer()))
        val started = kotlinx.coroutines.CompletableDeferred<Unit>()
        val journalDone = java.util.concurrent.atomic.AtomicBoolean(false)
        CareLinkPoller.writeJournal = { _, _, _ ->
            started.complete(Unit)
            kotlinx.coroutines.delay(100)
            journalDone.set(true)
        }
        runBlocking {
            val job = launch(kotlinx.coroutines.Dispatchers.IO) { CareLinkPoller.poll(app, PollReason.MANUAL) }
            started.await()
            job.cancel()
            job.join()
        }
        assertTrue("the journal write ran to its end", journalDone.get())
        assertEquals("the IoB after it too", 1, iob.size)
    }

    @Test
    fun settingsImportKeepsPollingWhileTheSessionLives() {
        // critic of the joints, B1: upstream's import clears every shared_prefs file it does not carry
        net.on(dm, ok(answer()))
        for (name in listOf(CareLinkSettings.PREFS, CareLinkStateStore.PREFS, "mod_carelink_schedule")) {
            app.getSharedPreferences(name, 0).edit().clear().commit()
        }
        assertTrue(CareLinkSettings(app).enabled)
        val s = poll(PollReason.ALARM)
        assertEquals(1, net.calls.size)
        assertEquals(CareLinkStatus.OK, s.status)
        assertEquals(1, journal.size)
    }

    @Test
    fun deadSessionFileNeedsSignInInsteadOfNoDataForever() {
        // critic of the joints, C4: the Keystore key no longer fits the file - wiped, "sign in again"
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        CareLinkVault.cipher = AesGcmSessionCipher(keyProvider = { other })
        net.on(dm, ok(answer()))
        val s = poll()
        assertEquals(CareLinkStatus.NEED_LOGIN, s.status)
        assertFalse("wiped", CareLinkVault.exists(app))
        assertNotNull(shown(CareLinkNotifier.ID_NEED_LOGIN))
        assertTrue(work.log.contains("cancel"))
        assertTrue(net.calls.isEmpty())
    }

    @Test
    fun sessionFileNotOpenedThisTimeIsAStorageErrorAndIsKept() {
        // a Keystore hiccup is not a dead file: kept, reported as storage
        CareLinkVault.cipher = AesGcmSessionCipher(keyProvider = { throw java.security.KeyStoreException("made-up") })
        val s = poll()
        assertEquals(CareLinkStatus.ERROR, s.status)
        assertEquals(CareLinkErrorKind.STORAGE, s.lastError!!.kind)
        assertEquals("KeyStoreException", s.lastError!!.errorClass)
        assertTrue(CareLinkVault.exists(app))
    }

    @Test
    fun signOutClosesTheBasalInTheBackground() {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        CareLinkPoller.closeBasal = { done.complete(Unit) }
        ModCareLinkStarter.afterSignOut(app)
        runBlocking { kotlinx.coroutines.withTimeout(5_000) { done.await() } }
        assertFalse(CareLinkSettings(app).enabled)
    }

    @Test
    fun brokenAnswerIsABadAnswer() {
        net.on(dm, ok("{}"), ok("not json"), status(500))
        assertEquals(CareLinkErrorKind.BAD_ANSWER, poll().lastError!!.kind)
        assertEquals(CareLinkErrorKind.BAD_ANSWER, poll().lastError!!.kind)
        val s = poll()
        assertEquals(CareLinkErrorKind.SERVER, s.lastError!!.kind)
        assertEquals(500, s.lastError!!.code)
    }

    @Test
    fun emptyAnswerKeepsTheSnapshotAndTheDataTime() {
        val before = now - 10 * 60_000L
        seed(before)
        net.on(dm, ok(answer(lagMin = 1, reservoir = "-1", battery = "-1")))
        val s = poll()
        assertEquals(CareLinkStatus.OK, s.status)
        assertEquals(before, s.lastDataAtMs)
        assertEquals(90.0, s.pumpNow!!.reservoirUnits!!, 1e-9)
        assertNull("the basal writer sees a gap", basal.single().first)
        assertNull(iob.single())
        assertEquals("markers are still written", 1, journal.single().first.size)
    }

    @Test
    fun dataTimeNeverMovesBack() {
        val before = now - 2 * 60_000L
        seed(before)
        net.on(dm, ok(answer(lagMin = 20)))
        val s = poll()
        assertEquals(before, s.lastDataAtMs)
        assertEquals("an older snapshot is not taken", 90.0, s.pumpNow!!.reservoirUnits!!, 1e-9)
        // critic 4c, C3: nor does the basal or the IoB plate get it; critic 4d, B1 and C1: nor the journal - its
        // markers would bring a finished bolus back to running, or a moved one under its old key as a second bolus
        assertTrue(basal.isEmpty())
        assertTrue(iob.isEmpty())
        assertTrue(journal.isEmpty())
    }

    @Test
    fun alarmAndSafetyWorkPollOnlyWhenDue() {
        net.on(dm, ok(answer()))
        store.write(CareLinkState(CareLinkStatus.OK, now - 2 * 60_000L, now - 4 * 60_000L, null, null))
        poll(PollReason.ALARM)
        poll(PollReason.WORKER)
        assertTrue("5-min interval, 2 min ago - not due", net.calls.isEmpty())
        store.write(CareLinkState(CareLinkStatus.OK, now - 270_000L, now - 4 * 60_000L, null, null))
        poll(PollReason.ALARM)
        assertEquals("4.5 min ago - due", 1, net.calls.size)
        CareLinkSettings(app).intervalMin = 15
        store.write(CareLinkState(CareLinkStatus.OK, now - 7 * 60_000L, now - 4 * 60_000L, null, null))
        poll(PollReason.WORKER)
        assertEquals("15-min interval, 7 min ago - not due", 1, net.calls.size)
        // critic 4c, N1: the last poll ran late (waited for the network) - the next alarm still polls
        CareLinkSettings(app).intervalMin = 5
        store.write(CareLinkState(CareLinkStatus.OK, now - 3 * 60_000L, now - 4 * 60_000L, null, null))
        poll(PollReason.ALARM)
        assertEquals("3 min ago with a 5-min interval - due", 2, net.calls.size)
    }

    @Test
    fun firstPollStaysPendingUntilAPollIsWritten() {
        // critic 4c, C1: the sign-in's poll failed (or the button replaced it) - the next one still takes 24 h
        seed(now - 10 * 60_000L)
        store.firstPollPending = true
        net.on(dm, NO_NETWORK)
        poll(PollReason.ALARM)
        assertTrue(store.firstPollPending)
        net.on(dm, ok(answer()))
        poll(PollReason.MANUAL)
        assertTrue("written with the whole window", journal.single().second)
        assertFalse(store.firstPollPending)
        now += 6 * 60_000L
        poll(PollReason.ALARM)
        assertFalse(journal.last().second)
    }

    @Test
    fun failedWriteKeepsTheFirstPollPending() {
        seed(now - 10 * 60_000L)
        store.firstPollPending = true
        CareLinkPoller.writeJournal = { _, _, _ -> throw IllegalStateException("made-up") }
        net.on(dm, ok(answer()))
        poll()
        assertTrue(store.firstPollPending)
    }

    @Test
    fun aNewSignInDuringARenewalKeepsTheNewSessionAndItsAlarms() {
        // critic 4c, C2: the renewal of an old poll finds a new sign-in (the vault moved on) - no "sign in again"
        net.on(dm, status(401), ok(answer()))
        net.on(TOKEN_URL, ok("""{"access_token": "${access(now / 1000 + 10_800)}", "expires_in": 10800}"""))
        net.onForm = {
            CareLinkVault.clear(app)
            CareLinkVault.save(app, session())
        }
        val s = poll()
        assertFalse(CareLinkStatus.NEED_LOGIN == s.status)
        assertFalse("alarms not removed", work.log.contains("cancel"))
        assertNull(shown(CareLinkNotifier.ID_NEED_LOGIN))
        assertTrue(CareLinkVault.exists(app))
    }

    @Test
    fun aGoodPollArmsTheChainAgain() {
        // critic 4c, C2: a good poll repairs a chain removed by an older poll's "sign in again"
        net.on(dm, ok(answer()))
        work.log.clear()
        poll()
        assertTrue(work.log.contains("periodic"))
    }

    @Test
    fun signOutDuringThePollWritesNothing() {
        // critic 4c, N2: the sign-out came after the answer - no journal, no state brought back
        seed(now - 10 * 60_000L)
        net.on(dm, ok(answer()))
        CareLinkPoller.zone = {
            CareLinkSettings(app).enabled = false
            store.clear()
            ZoneOffset.ofHours(5)
        }
        val s = poll()
        assertTrue(journal.isEmpty())
        assertTrue(basal.isEmpty())
        assertEquals(CareLinkState(), s)
        assertEquals(CareLinkState(), store.read())
    }

    @Test
    fun answerWithoutDataTimeKeepsTheLastDataTimeOfTheSnapshot() {
        // critic 4c, N5: no lastMedicalDeviceDataUpdateServerTime - the snapshot is not stamped "now"
        val before = now - 10 * 60_000L
        seed(before)
        net.on(dm, ok(answer().replace("\"lastMedicalDeviceDataUpdateServerTime\": ${now - 3 * 60_000L},", "")))
        val s = poll()
        assertEquals(before, s.pumpNow!!.dataAtMs)
        assertEquals("the new numbers are taken", 150.0, s.pumpNow!!.reservoirUnits!!, 1e-9)
        assertEquals(before, s.lastDataAtMs)
        assertEquals(before, basal.single().first!!.dataAtMs)
    }

    @Test
    fun signInDropsTheOldSnapshotAndDataTime() {
        // critic 4c, N4: another account or a long pause - no old numbers, no "no data for 2000 min"
        seed(now - 2_000 * 60_000L)
        ModCareLinkStarter.afterLogin(app)
        val s = store.read()
        assertNull(s.pumpNow)
        assertNull(s.lastDataAtMs)
        assertTrue(store.firstPollPending)
    }

    @Test
    fun appOpeningPollsNotMoreOftenThanEveryTwoMinutes() {
        net.on(dm, ok(answer()))
        store.write(CareLinkState(CareLinkStatus.OK, now - 60_000L, now - 4 * 60_000L, null, null))
        poll(PollReason.FOREGROUND)
        assertTrue(net.calls.isEmpty())
        poll(PollReason.MANUAL)
        assertEquals("the button always polls", 1, net.calls.size)
        now += 3 * 60_000L
        poll(PollReason.FOREGROUND)
        assertEquals(2, net.calls.size)
    }

    @Test
    fun offOrSignedOutDoesNothing() {
        net.on(dm, ok(answer()))
        CareLinkSettings(app).enabled = false
        assertEquals(CareLinkStatus.OFF, poll().status)
        CareLinkSettings(app).enabled = true
        CareLinkVault.clear(app)
        poll(PollReason.LOGIN)
        assertTrue(net.calls.isEmpty())
    }

    @Test
    fun noPumpDataFor30MinNotifiesOnceAndGoesAwayWithData() {
        seed(now - 31 * 60_000L)
        net.on(dm, NO_NETWORK)
        poll()
        val n = shown(CareLinkNotifier.ID_NO_DATA)
        assertNotNull(n)
        // critic of the texts 4d, B2: the threshold, not the age at this moment - the notification is not updated
        val title = n!!.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        assertTrue(title, title.contains("over 30 min"))
        assertFalse(title, title.contains("31"))
        // swiped away: not posted again while the data is still missing
        nm.cancel(CareLinkNotifier.ID_NO_DATA)
        now += 5 * 60_000L
        poll()
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        // the alarm checks without a poll too: still missing, still not posted again
        CareLinkNotifier.checkNoData(app, store.read(), now)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        // data is back: the mark is cleared; a later gap notifies again
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        now += 40 * 60_000L
        CareLinkNotifier.checkNoData(app, store.read(), now)
        assertNotNull(shown(CareLinkNotifier.ID_NO_DATA))
        // and goes away by itself when data returns
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
    }

    @Test
    fun noDataNotificationOnlyWhileSignedInAndOn() {
        seed(now - 60 * 60_000L)
        CareLinkSettings(app).enabled = false
        CareLinkNotifier.checkNoData(app, store.read(), now)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        CareLinkSettings(app).enabled = true
        CareLinkVault.clear(app)
        CareLinkNotifier.checkNoData(app, store.read(), now)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        CareLinkVault.save(app, session())
        store.write(CareLinkState(CareLinkStatus.OK, now, null, null, null))
        CareLinkNotifier.checkNoData(app, store.read(), now)
        assertNull("never any data, polling just on - not yet", shown(CareLinkNotifier.ID_NO_DATA))
    }

    @Test
    fun noDataEverSinceTheSignInCountsFromIt() {
        // critic of the joints 4e, N4: only empty answers since polling was turned on - "no data" from that time,
        // threshold + 10 min (also after upstream's settings import cleared our state: the flag file keeps the time)
        CareLinkSettings(app).warnAfterMin = 5
        store.write(CareLinkState(CareLinkStatus.OK, now, null, null, null))
        CareLinkNotifier.checkNoData(app, store.read(), now + 14 * 60_000L)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        app.getSharedPreferences(CareLinkStateStore.PREFS, 0).edit().clear().commit()
        CareLinkNotifier.checkNoData(app, CareLinkState(CareLinkStatus.OK), now + 15 * 60_000L)
        assertNotNull(shown(CareLinkNotifier.ID_NO_DATA))
    }

    // ---------------------------------------------------------------- critic 4d2 (second round)

    @Test
    fun writeFailureNotifiesAfterTheThresholdAndGoesAwayAfterAGoodWrite() {
        // critic 4d2, C3 and the person's word 17.11: "warn after" 5 min - the 5th minute of failed writes notifies
        CareLinkSettings(app).warnAfterMin = 5
        CareLinkPoller.writeJournal = { _, _, _ -> throw IllegalStateException("made-up") }
        net.on(dm, ok(answer()))
        poll()
        assertNull("the first failed write only starts the clock", shown(CareLinkNotifier.ID_WRITE_FAIL))
        assertEquals(now, store.writeFailSinceMs)
        // fresh data every poll (the threshold is for "no data" too)
        now += 4 * 60_000L
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        assertNull(shown(CareLinkNotifier.ID_WRITE_FAIL))
        now += 60_000L
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        val n = shown(CareLinkNotifier.ID_WRITE_FAIL)
        assertNotNull("5 min of failed writes", n)
        assertTrue(n!!.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("5"))
        assertNull("the data comes: not \"no data\"", shown(CareLinkNotifier.ID_NO_DATA))
        // a network failure in between changes nothing; a good write takes it away and stops the clock
        net.on(dm, NO_NETWORK)
        now += 60_000L
        poll()
        assertNotNull(shown(CareLinkNotifier.ID_WRITE_FAIL))
        CareLinkPoller.writeJournal = { _, e, first -> journal += e to first }
        now += 60_000L
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        assertNull(shown(CareLinkNotifier.ID_WRITE_FAIL))
        assertNull(store.writeFailSinceMs)
    }

    @Test
    fun aNewWarningThresholdCountsWithoutARestart() {
        // "no data" with the default 30 min: 6 min old data - nothing; the person picks 5 min - the next check notifies
        seed(now - 6 * 60_000L)
        CareLinkNotifier.checkNoData(app, store.read(), now)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        CareLinkSettings(app).warnAfterMin = 5
        CareLinkNotifier.checkNoData(app, store.read(), now)
        assertNotNull(shown(CareLinkNotifier.ID_NO_DATA))
        // a value not offered falls back to the default
        CareLinkSettings(app).warnAfterMin = 7
        assertEquals(30, CareLinkSettings(app).warnAfterMin)
    }

    @Test
    fun signOutDuringTheWritesKeepsThePumpIobOffThePlate() {
        // critic 4d2, N3: signed out while the writers ran - no IoB published after it
        CareLinkPoller.writeBasal = { _, p, at ->
            basal += p to at
            CareLinkSettings(app).enabled = false
        }
        net.on(dm, ok(answer()))
        poll()
        assertEquals(1, basal.size)
        assertTrue(iob.isEmpty())
    }

    // ---------------------------------------------------------------- critic 4d (code and texts)

    @Test
    fun theAlarmDoesNotWarnBeforeItsPollAndThePollAfterItDoes() {
        // critic 4d, C1 and the person's test 17.11: "warn after" 5 min with updates every 5 min
        CareLinkSettings(app).warnAfterMin = 5
        net.on(dm, ok(answer(lagMin = 2)))
        poll(PollReason.ALARM)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        // the next alarm: the data is 7 min old, but the alarm has queued a poll - no check before it
        now += 5 * 60_000L
        CareLinkNotifier.checkNoDataOnAlarm(app, pollQueued = true, state = store.read(), nowMs = now)
        assertNull("not before the alarm's poll", shown(CareLinkNotifier.ID_NO_DATA))
        // the poll brings fresh data: nothing
        net.on(dm, ok(answer(lagMin = 1)))
        poll(PollReason.ALARM)
        assertNull("fresh data after the poll", shown(CareLinkNotifier.ID_NO_DATA))
        // the next one: the cloud has nothing newer (MiniMed Mobile did not upload) - the poll warns
        now += 5 * 60_000L
        net.on(dm, ok(answer(lagMin = 6)))
        poll(PollReason.ALARM)
        assertNotNull("no new data after the poll", shown(CareLinkNotifier.ID_NO_DATA))
    }

    @Test
    fun theAlarmChecksByItselfWhenPollsDoNotRun() {
        // no network: WorkManager does not start the poll, the data still gets old - the alarm checks after 1.5 intervals
        CareLinkSettings(app).warnAfterMin = 5
        seed(now - 3 * 60_000L) // the last poll 5 min ago
        now += 2 * 60_000L
        CareLinkNotifier.checkNoDataOnAlarm(app, pollQueued = true, state = store.read(), nowMs = now)
        assertNull("7 min since the last poll: polls run", shown(CareLinkNotifier.ID_NO_DATA))
        now += 60_000L
        CareLinkNotifier.checkNoDataOnAlarm(app, pollQueued = true, state = store.read(), nowMs = now)
        assertNotNull("8 min: no poll for over 1.5 intervals", shown(CareLinkNotifier.ID_NO_DATA))
    }

    @Test
    fun anOlderAnswerKeepsTheWriteFailure() {
        // critic 4d, N1: an older answer writes nothing - the write failure, its clock and its notification stand
        CareLinkSettings(app).warnAfterMin = 5
        CareLinkPoller.writeJournal = { _, _, _ -> throw IllegalStateException("made-up") }
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        val since = store.writeFailSinceMs
        assertEquals(now, since)
        now += 6 * 60_000L
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        assertNotNull(shown(CareLinkNotifier.ID_WRITE_FAIL))
        // the cloud serves an older answer
        now += 60_000L
        net.on(dm, ok(answer(lagMin = 20)))
        val s = poll()
        assertEquals(CareLinkStatus.ERROR, s.status)
        assertEquals(CareLinkErrorKind.WRITE, s.lastError!!.kind)
        assertEquals("the clock goes on", since, store.writeFailSinceMs)
        assertNotNull("not taken away", shown(CareLinkNotifier.ID_WRITE_FAIL))
    }

    @Test
    fun aWriteFailureClockDoesNotOutliveTheSignOut() {
        // critic 4d, N2: a check after the sign-out's clear writes nothing; a sign-in drops a former session's clock
        CareLinkSettings(app).enabled = false
        val failed = CareLinkState(CareLinkStatus.ERROR, now, now, CareLinkError(CareLinkErrorKind.WRITE, 0, "IllegalStateException"))
        CareLinkNotifier.checkWrites(app, failed, now)
        assertNull(store.writeFailSinceMs)
        CareLinkSettings(app).enabled = true
        store.writeFailSinceMs = now - 7 * 24 * 3_600_000L
        store.writeFailShown = true
        ModCareLinkStarter.afterLogin(app)
        assertNull(store.writeFailSinceMs)
        assertFalse(store.writeFailShown)
    }

    @Test
    fun aChannelTurnedOffPostsNothingAndMarksNothing() {
        // critic 4d, N6: Android drops the notifications of a channel turned off - nothing marked shown, the next check
        // posts once it is on again
        nm.createNotificationChannel(NotificationChannel(CareLinkNotifier.CHANNEL, "CareLink", NotificationManager.IMPORTANCE_NONE))
        assertTrue(CareLinkNotifier.channelOff(app))
        seed(now - 31 * 60_000L)
        CareLinkNotifier.checkNoData(app, store.read(), now)
        assertNull(shown(CareLinkNotifier.ID_NO_DATA))
        assertFalse(store.noDataShown)
    }

    // ---------------------------------------------------------------- critic 4d2 (second round, low)

    @Test
    fun aFailingWriteCheckDoesNotSkipTheNoDataCheck() {
        // critic 4d2, N2: while polls run, the poll is the only "no data" check - a throw in the write check must not
        // skip it. A broken stored write clock makes the write check throw.
        seed(now - 31 * 60_000L)
        app.getSharedPreferences(CareLinkStateStore.PREFS, 0).edit().putString("writeFailSinceMs", "broken").commit()
        CareLinkNotifier.afterPoll(app, store.read(), now)
        assertNotNull(shown(CareLinkNotifier.ID_NO_DATA))
    }

    @Test
    fun anOlderAnswerAfterANetworkFailureKeepsTheWriteFailure() {
        // critic 4d2, N1: write failure -> no network -> an older answer: the clock and the notification stand
        CareLinkSettings(app).warnAfterMin = 5
        CareLinkPoller.writeJournal = { _, _, _ -> throw IllegalStateException("made-up") }
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        val since = store.writeFailSinceMs
        assertEquals(now, since)
        now += 6 * 60_000L
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        assertNotNull(shown(CareLinkNotifier.ID_WRITE_FAIL))
        now += 60_000L
        net.on(dm, NO_NETWORK)
        assertEquals(CareLinkErrorKind.NO_NETWORK, poll().lastError!!.kind)
        now += 60_000L
        net.on(dm, ok(answer(lagMin = 20)))
        val s = poll()
        assertEquals(CareLinkStatus.ERROR, s.status)
        assertEquals(CareLinkErrorKind.WRITE, s.lastError!!.kind)
        assertEquals("the clock goes on", since, store.writeFailSinceMs)
        assertNotNull("not taken away", shown(CareLinkNotifier.ID_WRITE_FAIL))
    }

    @Test
    fun anEmptyAnswerKeepsTheWriteFailure() {
        // critic 4e, N5: write failure -> an empty answer (no pump data, no boluses): the journal had nothing to write,
        // so the clock and the notification stand
        CareLinkSettings(app).warnAfterMin = 5
        CareLinkPoller.writeJournal = { _, _, _ -> throw IllegalStateException("made-up") }
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        val since = store.writeFailSinceMs
        assertEquals(now, since)
        now += 6 * 60_000L
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        assertNotNull(shown(CareLinkNotifier.ID_WRITE_FAIL))
        // the real journal writer returns at once with no boluses: no failure from it
        CareLinkPoller.writeJournal = { _, e, first -> journal += e to first }
        now += 60_000L
        val empty = answer(lagMin = 1, reservoir = "-1", battery = "-1")
        net.on(dm, ok(JSONObject(empty).apply { getJSONObject("patientData").put("markers", JSONArray()) }.toString()))
        val s = poll()
        assertEquals(CareLinkStatus.ERROR, s.status)
        assertEquals(CareLinkErrorKind.WRITE, s.lastError!!.kind)
        assertEquals("the clock goes on", since, store.writeFailSinceMs)
        assertNotNull("not taken away", shown(CareLinkNotifier.ID_WRITE_FAIL))
        // a fresh answer written fine takes it away
        now += 60_000L
        net.on(dm, ok(answer(lagMin = 1)))
        poll()
        assertNull(shown(CareLinkNotifier.ID_WRITE_FAIL))
        assertNull(store.writeFailSinceMs)
    }

    @Test
    fun aLateAlarmChecksOnlyWhenNoPollFollowedThePreviousOne() {
        // critic 4d2, N3: interval 5, the alarm comes 9 min after the previous one (Doze), a poll right after that one
        CareLinkSettings(app).warnAfterMin = 5
        store.write(CareLinkState(CareLinkStatus.OK, now - 9 * 60_000L, now - 11 * 60_000L, null,
            PumpNow(now - 11 * 60_000L, reservoirUnits = 90.0)))
        CareLinkNotifier.checkNoDataOnAlarm(app, pollQueued = true, prevAlarmAtMs = now - 9 * 60_000L - 2_000L,
            state = store.read(), nowMs = now)
        assertNull("a poll followed the previous alarm: polls run", shown(CareLinkNotifier.ID_NO_DATA))
        // no poll since the previous alarm (no network): the alarm checks
        store.write(CareLinkState(CareLinkStatus.OK, now - 12 * 60_000L, now - 14 * 60_000L, null,
            PumpNow(now - 14 * 60_000L, reservoirUnits = 90.0)))
        CareLinkNotifier.checkNoDataOnAlarm(app, pollQueued = true, prevAlarmAtMs = now - 9 * 60_000L,
            state = store.read(), nowMs = now)
        assertNotNull(shown(CareLinkNotifier.ID_NO_DATA))
    }

    @Test
    fun notificationsTurnedOffAndOnBringTheWarningsBack() {
        // critic 4d2, N4: Android removes our notifications when they are turned off; the mark must go with them
        CareLinkSettings(app).warnAfterMin = 5
        seed(now - 31 * 60_000L)
        store.writeFailSinceMs = now - 6 * 60_000L
        val failed = CareLinkState(CareLinkStatus.ERROR, now, now - 31 * 60_000L,
            CareLinkError(CareLinkErrorKind.WRITE, 0, "IllegalStateException"))
        CareLinkNotifier.checkNoData(app, store.read(), now)
        CareLinkNotifier.checkWrites(app, failed, now)
        assertNotNull(shown(CareLinkNotifier.ID_NO_DATA))
        assertNotNull(shown(CareLinkNotifier.ID_WRITE_FAIL))
        shadowOf(nm).setNotificationsEnabled(false)
        nm.cancelAll()
        now += 5 * 60_000L
        CareLinkNotifier.checkNoData(app, store.read(), now)
        CareLinkNotifier.checkWrites(app, failed, now)
        assertFalse(store.noDataShown)
        assertFalse(store.writeFailShown)
        shadowOf(nm).setNotificationsEnabled(true)
        now += 5 * 60_000L
        CareLinkNotifier.checkNoData(app, store.read(), now)
        CareLinkNotifier.checkWrites(app, failed, now)
        assertNotNull("back once on again", shown(CareLinkNotifier.ID_NO_DATA))
        assertNotNull(shown(CareLinkNotifier.ID_WRITE_FAIL))
    }
}
