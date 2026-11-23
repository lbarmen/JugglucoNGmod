package mod.carelink

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import kotlinx.coroutines.runBlocking
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import mod.carelink.CareLinkJournalTestEnv.app
import mod.carelink.CareLinkTestData.CARELINK_BASE
import mod.carelink.CareLinkTestData.TOKEN_URL
import mod.carelink.CareLinkTestData.access
import tk.glucodata.mod.carelink.AesGcmSessionCipher
import tk.glucodata.mod.carelink.CareLinkActivity
import tk.glucodata.mod.carelink.CareLinkError
import tk.glucodata.mod.carelink.CareLinkErrorKind
import tk.glucodata.mod.carelink.CareLinkInsulin
import tk.glucodata.mod.carelink.CareLinkNotifier
import tk.glucodata.mod.carelink.CareLinkScheduler
import tk.glucodata.mod.carelink.CareLinkSession
import tk.glucodata.mod.carelink.CareLinkSettings
import tk.glucodata.mod.carelink.CareLinkState
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.carelink.CareLinkStatus
import tk.glucodata.mod.carelink.CareLinkVault
import tk.glucodata.mod.carelink.CareLinkView
import tk.glucodata.mod.carelink.CareLinkViewModel
import tk.glucodata.mod.carelink.ForegroundPoll
import tk.glucodata.mod.carelink.PumpNow
import javax.crypto.KeyGenerator

/**
 * JugglucoNGmod, CareLink 4e: the CareLink window's model - what it shows for each state (signed out, OK, sign-in
 * needed, error, no data yet, polling off), a new interval re-arms the schedule, switches and the warning threshold are
 * written, the insulin type is checked against upstream's list, sign-out wipes the session and the page cookies; the
 * settings row only where the window is declared; the poll when the app comes to the front. Upstream's real journal
 * (insulin types) under Robolectric. Made-up session, numbers and times only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = CareLinkJournalTestApp::class, shadows = [CareLinkTestNatives::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkViewModelTest {

    private val now = 1_800_000_000_000L
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val work = FakeCareLinkWork()
    private var cookiesCleared = 0
    private lateinit var model: CareLinkViewModel

    private fun session() = CareLinkSession(
        accessToken = access((now + 2 * 3_600_000L) / 1000), refreshToken = "made-up-refresh-1",
        expiresAtMs = now + 2 * 3_600_000L, username = "tester", role = "PATIENT", patientId = null, country = "DE",
        region = "EU", baseUrlCareLink = CARELINK_BASE, baseUrlCumulus = "https://cumulus.example.eu/connect/v13",
        tokenUrl = TOKEN_URL, clientId = "abc123", loggedInAtMs = now - 3_600_000L,
    )

    @Before
    fun setUp() {
        CareLinkJournalTestEnv.setUp { now }
        CareLinkScheduler.resetForTest()
        CareLinkScheduler.work = work
        CareLinkScheduler.elapsed = { 1_000_000L }
        CareLinkVault.cipher = AesGcmSessionCipher(keyProvider = { key })
        CareLinkVault.clear(app)
        CareLinkStateStore(app).clear()
        CareLinkSettings(app).enabled = false
        CareLinkViewModel.clearCookies = { cookiesCleared++ }
        model = CareLinkViewModel(app)
    }

    @After
    fun tearDown() {
        CareLinkVault.clear(app)
        CareLinkVault.cipher = AesGcmSessionCipher.keystore
        CareLinkScheduler.resetForTest()
        CareLinkJournalTestEnv.tearDown()
    }

    private fun signIn() {
        CareLinkVault.save(app, session())
        CareLinkSettings(app).enabled = true
    }

    private fun load() = runBlocking { model.load() }

    @Test
    fun notSignedInShowsTheSignInAndNoPump() {
        CareLinkStateStore(app).write(CareLinkState(CareLinkStatus.OFF, pumpNow = PumpNow(now, reservoirUnits = 80.0)))
        val s = load()
        assertEquals(CareLinkView.SIGNED_OUT, s.view)
        assertFalse(s.signedIn)
        assertNull("no session - no pump numbers", s.pump)
    }

    @Test
    fun goodPollShowsThePumpAndTheDataTime() {
        signIn()
        val pump = PumpNow(now - 120_000L, iobUnits = 1.5, iobAtMs = now - 180_000L, reservoirUnits = 98.0,
            reservoirPercent = 45, batteryPercent = 70)
        CareLinkStateStore(app).write(CareLinkState(CareLinkStatus.OK, now - 60_000L, now - 120_000L, null, pump))
        val s = load()
        assertEquals(CareLinkView.OK, s.view)
        assertTrue(s.signedIn)
        assertEquals(now, s.nowMs)
        assertEquals(now - 120_000L, s.lastDataAtMs)
        assertEquals(1.5, s.pump!!.iobUnits!!, 1e-9)
        assertEquals(45, s.pump!!.reservoirPercent)
        assertEquals(70, s.pump!!.batteryPercent)
        assertNull(s.error)
    }

    @Test
    fun everyStateHasItsView() {
        val store = CareLinkStateStore(app)
        // the session ended: the vault is wiped, the state says so
        store.write(CareLinkState(CareLinkStatus.NEED_LOGIN, now))
        assertEquals(CareLinkView.NEED_LOGIN, load().view)
        signIn()
        store.write(CareLinkState(CareLinkStatus.OK, now, null))
        assertEquals("no data since the sign-in", CareLinkView.WAITING, load().view)
        val e = CareLinkError(CareLinkErrorKind.WRITE, 0, "IllegalStateException")
        store.write(CareLinkState(CareLinkStatus.ERROR, now, now - 60_000L, e))
        val err = load()
        assertEquals(CareLinkView.ERROR, err.view)
        assertEquals(e, err.error)
        // critic 4d2, N2: a session with polling off is said so
        CareLinkSettings(app).enabled = false
        assertEquals(CareLinkView.POLLING_OFF, load().view)
        assertTrue(load().signedIn)
        model.turnPollingOn()
        assertTrue(CareLinkSettings(app).enabled)
        assertTrue(work.log.contains("poll MANUAL false"))
    }

    @Test
    fun oldPumpDataIsOutOfDateNotComingIn() {
        // critic of the texts 4d, B1: the cloud answers, the data is old - not the green "coming in"; from the same
        // moment as the "no data" notification (the threshold)
        signIn()
        CareLinkSettings(app).warnAfterMin = 15
        val store = CareLinkStateStore(app)
        val fresh = now - 14 * 60_000L
        store.write(CareLinkState(CareLinkStatus.OK, now - 60_000L, fresh, null, PumpNow(fresh, iobUnits = 0.5)))
        assertEquals(CareLinkView.OK, load().view)
        val old = now - 15 * 60_000L
        store.write(CareLinkState(CareLinkStatus.OK, now - 60_000L, old, null, PumpNow(old, iobUnits = 0.5)))
        val s = load()
        assertEquals(CareLinkView.STALE, s.view)
        assertTrue(s.signedIn)
        assertEquals("the old numbers stay, with their one data time", old, s.pump!!.dataAtMs)
        // a failed update says why instead
        store.write(CareLinkState(CareLinkStatus.ERROR, now, old, CareLinkError(CareLinkErrorKind.NO_NETWORK, 0, "IOException")))
        assertEquals(CareLinkView.ERROR, load().view)
    }

    @Test
    fun noDataEverSinceTheSignInIsOutOfDateWhenTheNotificationComes() {
        // critic 4e, N2: the window counts as the notification does - polling flag + 10 min + the threshold
        val ok = CareLinkState(CareLinkStatus.OK, now, null)
        val warn = 5 * 60_000L
        val since = now - 16 * 60_000L
        assertEquals(CareLinkView.STALE, CareLinkViewModel.viewOf(true, true, ok, now, warn, since))
        assertEquals(CareLinkView.WAITING, CareLinkViewModel.viewOf(true, true, ok, now - 2 * 60_000L, warn, since))
        assertEquals("flag time unknown - waiting as before", CareLinkView.WAITING,
            CareLinkViewModel.viewOf(true, true, ok, now, warn, null))
        // through the model: the flag file carries the sign-in time
        signIn()
        CareLinkSettings(app).warnAfterMin = 5
        CareLinkStateStore(app).write(ok)
        assertEquals(CareLinkView.WAITING, load().view)
    }

    @Test
    fun aThresholdBelowTheIntervalPlusFiveIsSaidNotForbidden() {
        // critic 4d, C1 and critic of the texts 4d, C4; the person wants 5 min to test it (17.11)
        model.setWarnAfter(5)
        var s = load()
        assertEquals("allowed", 5, s.warnAfterMin)
        assertEquals(10, s.warnMinQuiet)
        assertTrue(s.warnTooSoon)
        model.setWarnAfter(10)
        assertFalse(load().warnTooSoon)
        CareLinkSettings(app).intervalMin = 10
        s = load()
        assertEquals(15, s.warnMinQuiet)
        assertTrue(s.warnTooSoon)
    }

    @Test
    fun aCareLinkChannelTurnedOffIsShown() {
        // critic 4d, N6: the app may notify, our channel is off - the window says so
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(load().channelOff)
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CareLinkNotifier.CHANNEL, "CareLink", NotificationManager.IMPORTANCE_NONE),
        )
        val s = load()
        assertTrue(s.notificationsAllowed)
        assertTrue(s.channelOff)
    }

    @Test
    fun aNewIntervalReArmsTheSchedule() {
        signIn()
        model.setInterval(10)
        assertEquals(10, CareLinkSettings(app).intervalMin)
        assertTrue(work.log.contains("periodic"))
        val next = app.getSharedPreferences("mod_carelink_schedule", 0).getLong("nextAlarmElapsedMs", 0L)
        assertEquals("the next alarm in 10 min", 1_000_000L + 10 * 60_000L, next)
        model.setInterval(7)
        assertEquals("not offered - ignored", 10, CareLinkSettings(app).intervalMin)
    }

    @Test
    fun switchesAndTheWarningThresholdAreWritten() {
        assertTrue(load().writeCarbs)
        assertFalse(load().sendToNightscout)
        assertEquals(30, load().warnAfterMin)
        model.setWriteCarbs(false)
        model.setSendToNightscout(true)
        model.setWarnAfter(5)
        val settings = CareLinkSettings(app)
        assertFalse(settings.writeCarbs)
        assertTrue(settings.sendToNightscout)
        assertEquals(5, settings.warnAfterMin)
        model.setWarnAfter(45)
        assertEquals("not offered - ignored", 5, settings.warnAfterMin)
        val s = load()
        assertFalse(s.writeCarbs)
        assertTrue(s.sendToNightscout)
        assertEquals(5, s.warnAfterMin)
    }

    @Test
    fun insulinTypeIsOneOfTheirsAndALostChoiceShowsTheOneUsed() {
        val default = runBlocking { CareLinkInsulin.presetId(app) }!!
        // critic 4d2, N6: a settings import left an id that is not a pump type - the window shows the type really used
        CareLinkSettings(app).presetId = 999_999L
        var s = load()
        assertTrue(s.insulinChoices.isNotEmpty())
        assertTrue(s.insulinChoices.any { it.id == default })
        assertEquals(default, s.insulinId)
        assertNotNull(s.insulinName)
        // a choice not in the list the window showed is not taken; one in it is written
        model.setInsulin(123_456_789L)
        assertEquals(999_999L, CareLinkSettings(app).presetId)
        model.setInsulin(default)
        assertEquals(default, CareLinkSettings(app).presetId)
        s = load()
        assertEquals(default, s.insulinId)
    }

    @Test
    fun signOutWipesTheSessionAndThePageCookies() {
        signIn()
        model.signOut()
        assertFalse(CareLinkVault.exists(app))
        assertEquals("critic of the joints, C5", 1, cookiesCleared)
        assertEquals(CareLinkView.SIGNED_OUT, load().view)
    }

    @Test
    fun settingsRowAndNotificationTapOnlyWhereTheWindowIsDeclared() {
        // upstream's builds: no window - the row is not drawn, a tap opens the app (taken out of Robolectric's package
        // manager first, in case its test manifest has it)
        val window = ComponentName(app, CareLinkActivity::class.java)
        shadowOf(app.packageManager).removeActivity(window)
        assertNull(CareLinkActivity.intentIfDeclared(app))
        val fallback = CareLinkNotifier.tapIntent(app)
        assertTrue(fallback == null || fallback.component?.className != CareLinkActivity::class.java.name)
        // the `mod` build declares it
        shadowOf(app.packageManager).addActivityIfNotPresent(window)
        assertNotNull(CareLinkActivity.intentIfDeclared(app))
        assertEquals(CareLinkActivity::class.java.name, CareLinkNotifier.tapIntent(app)!!.component!!.className)
    }

    @Test
    fun theAppComingToTheFrontQueuesOnePoll() {
        signIn()
        val cb = ForegroundPoll(app)
        val a = Activity()
        cb.onActivityStarted(a)
        cb.onActivityResumed(a)
        cb.onActivityPaused(a)
        cb.onActivityResumed(a)
        assertEquals("once per coming to the front", listOf("poll FOREGROUND false"), work.log.filter { it.startsWith("poll") })
        cb.onActivityStopped(a)
        cb.onActivityStarted(a)
        cb.onActivityResumed(a)
        assertEquals(2, work.log.count { it == "poll FOREGROUND false" })
        // signed out: nothing queued
        CareLinkVault.clear(app)
        cb.onActivityStopped(a)
        cb.onActivityStarted(a)
        cb.onActivityResumed(a)
        assertEquals(2, work.log.count { it == "poll FOREGROUND false" })
    }
}
