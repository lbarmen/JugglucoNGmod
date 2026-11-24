package mod.carelink

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Operation
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
import mod.carelink.CareLinkTestData.TOKEN_URL
import mod.carelink.CareLinkTestData.access
import tk.glucodata.mod.carelink.AesGcmSessionCipher
import tk.glucodata.mod.carelink.CareLinkAlarmReceiver
import tk.glucodata.mod.carelink.CareLinkAuth
import tk.glucodata.mod.carelink.CareLinkBootReceiver
import tk.glucodata.mod.carelink.CareLinkNotifier
import tk.glucodata.mod.carelink.CareLinkScheduler
import tk.glucodata.mod.carelink.CareLinkSession
import tk.glucodata.mod.carelink.CareLinkSettings
import tk.glucodata.mod.carelink.CareLinkState
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.carelink.CareLinkStatus
import tk.glucodata.mod.carelink.CareLinkVault
import tk.glucodata.mod.carelink.CareLinkWork
import tk.glucodata.mod.carelink.PollReason
import tk.glucodata.mod.carelink.PumpNow
import tk.glucodata.mod.carelink.ModCareLinkStarter
import javax.crypto.KeyGenerator

/** Records what the scheduler asks of WorkManager (tests need no WorkManager). */
internal class FakeCareLinkWork : CareLinkWork {
    val log = mutableListOf<String>()
    override fun ensurePeriodic(app: Context) {
        log += "periodic"
    }

    override fun cancelPeriodic(app: Context) {
        log += "cancel"
    }

    override fun enqueue(app: Context, reason: PollReason, firstPoll: Boolean): Operation? {
        log += "poll $reason $firstPoll"
        return null
    }
}

/**
 * JugglucoNGmod, CareLink 4c: the poll schedule - nothing without a session or with polling off; the alarm chain and
 * the safety work with one; sign-in and sign-out listeners of the starter; the boot receiver. Robolectric's alarm
 * manager; WorkManager replaced by a recording fake.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkSchedulerTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val work = FakeCareLinkWork()
    private var elapsed = 10_000_000L
    private val now = 1_800_000_000_000L
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private val alarms get() = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms

    private fun pollIntentExists(): Boolean = PendingIntent.getBroadcast(
        app, 1_947_000, Intent(app, CareLinkAlarmReceiver::class.java).setAction(CareLinkScheduler.ACTION_POLL),
        PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
    ) != null

    private fun saveSession() = CareLinkVault.save(
        app,
        CareLinkSession(
            accessToken = access((now + 7_200_000L) / 1000), refreshToken = "made-up-refresh-1",
            expiresAtMs = now + 7_200_000L, username = "tester", role = "PATIENT", patientId = null, country = "DE",
            region = "EU", baseUrlCareLink = CARELINK_BASE, baseUrlCumulus = "https://cumulus.example.eu/c",
            tokenUrl = TOKEN_URL, clientId = "abc123", loggedInAtMs = now,
        ),
    )

    @Before
    fun setUp() {
        CareLinkAuth.resetForTest()
        CareLinkScheduler.resetForTest()
        CareLinkScheduler.work = work
        CareLinkScheduler.elapsed = { elapsed }
        CareLinkVault.cipher = AesGcmSessionCipher(keyProvider = { key })
        CareLinkVault.clear(app)
        CareLinkSettings(app).enabled = false
    }

    @After
    fun tearDown() {
        CareLinkVault.clear(app)
        CareLinkAuth.resetForTest()
        CareLinkScheduler.resetForTest()
        CareLinkVault.cipher = AesGcmSessionCipher.keystore
    }

    @Test
    fun withoutASessionNothingIsScheduled() {
        CareLinkSettings(app).enabled = true
        assertFalse(CareLinkScheduler.arm(app))
        assertFalse(CareLinkScheduler.arm(app, force = true))
        assertTrue(alarms.isEmpty())
        assertFalse(pollIntentExists())
        assertTrue("WorkManager not even touched", work.log.isEmpty())
        assertNull(CareLinkScheduler.enqueuePoll(app, PollReason.ALARM))
        assertTrue(work.log.isEmpty())
    }

    @Test
    fun withPollingOffNothingIsScheduledAndTheSafetyWorkGoes() {
        // critic of the joints, B1: off with a live session - the safety work is removed even without the "armed" mark
        saveSession()
        CareLinkSettings(app).enabled = false
        assertFalse(CareLinkScheduler.arm(app))
        assertTrue(alarms.isEmpty())
        assertEquals(listOf("cancel"), work.log)
        assertNull(CareLinkScheduler.enqueuePoll(app, PollReason.MANUAL))
    }

    @Test
    fun settingsImportDoesNotStopPolling() {
        // critic of the joints, B1: upstream's import clears our shared_prefs files; the flag is a file beside the session
        saveSession()
        ModCareLinkStarter.afterLogin(app)
        for (name in listOf(CareLinkSettings.PREFS, CareLinkStateStore.PREFS, "mod_carelink_schedule")) {
            app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        assertTrue(CareLinkSettings(app).enabled)
        assertTrue(CareLinkScheduler.arm(app))
        assertEquals(1, alarms.size)
        assertFalse(work.log.contains("cancel"))
    }

    @Test
    fun signInListenerWithoutASessionTurnsNothingOn() {
        // critic of the joints, N3: a sign-out came between the session write and the listener
        ModCareLinkStarter.afterLogin(app)
        assertFalse(CareLinkSettings(app).enabled)
        assertEquals(CareLinkStatus.OFF, CareLinkStateStore(app).read().status)
        assertTrue(work.log.isEmpty())
    }

    @Test
    fun signedInAndOnArmsOneAlarmAndTheSafetyWork() {
        saveSession()
        CareLinkSettings(app).enabled = true
        assertTrue(CareLinkScheduler.arm(app))
        assertEquals(1, alarms.size)
        assertTrue(pollIntentExists())
        assertEquals(listOf("periodic"), work.log)
        // a process start a minute later keeps the alarm; the alarm itself (force) sets the next one
        elapsed += 60_000L
        assertTrue(CareLinkScheduler.arm(app))
        assertEquals(1, alarms.size)
        assertTrue(CareLinkScheduler.arm(app, force = true))
        assertEquals(1, alarms.size)
        CareLinkScheduler.enqueuePoll(app, PollReason.ALARM)
        assertEquals("poll ALARM false", work.log.last())
    }

    @Test
    fun disarmRemovesTheAlarmAndTheSafetyWork() {
        saveSession()
        CareLinkSettings(app).enabled = true
        CareLinkScheduler.arm(app)
        CareLinkScheduler.disarm(app)
        assertTrue(alarms.isEmpty())
        assertFalse(pollIntentExists())
        assertEquals("cancel", work.log.last())
        // turned off later: the next arm removes what was armed
        CareLinkScheduler.arm(app)
        CareLinkSettings(app).enabled = false
        work.log.clear()
        assertFalse(CareLinkScheduler.arm(app))
        assertTrue(alarms.isEmpty())
        assertEquals(listOf("cancel"), work.log)
    }

    @Test
    fun signInTurnsPollingOnArmsAndAsksTheFirstPoll() {
        val store = CareLinkStateStore(app)
        store.write(CareLinkState(CareLinkStatus.NEED_LOGIN))
        store.needLoginShown = true
        saveSession()
        ModCareLinkStarter.afterLogin(app)
        assertTrue(CareLinkSettings(app).enabled)
        assertEquals(CareLinkStatus.OK, store.read().status)
        assertFalse(store.needLoginShown)
        assertEquals(1, alarms.size)
        assertEquals(listOf("periodic", "poll LOGIN true"), work.log)
    }

    @Test
    fun signOutTurnsPollingOffAndClearsTheState() {
        saveSession()
        CareLinkSettings(app).enabled = true
        CareLinkScheduler.arm(app)
        val store = CareLinkStateStore(app)
        store.write(CareLinkState(CareLinkStatus.OK, now, now, null, PumpNow(now, reservoirUnits = 100.0)))
        CareLinkNotifier.checkNoData(app, store.read(), now + 3_600_000L)
        CareLinkVault.clear(app)
        ModCareLinkStarter.afterSignOut(app)
        assertFalse(CareLinkSettings(app).enabled)
        assertTrue(alarms.isEmpty())
        assertEquals("cancel", work.log.last())
        assertEquals(CareLinkState(), store.read())
        assertFalse(store.noDataShown)
    }

    @Test
    fun starterSetsTheListenersAndArmsAfterAPost() {
        saveSession()
        CareLinkSettings(app).enabled = true
        val starter = org.robolectric.Robolectric.setupContentProvider(ModCareLinkStarter::class.java, "mod.carelink.test")
        assertTrue(starter.onCreate())
        assertNotNull(CareLinkAuth.onLoginSuccess)
        assertNotNull(CareLinkAuth.onSignOut)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, alarms.size)
    }

    @Test
    fun bootAndUpdateArmAgain() {
        saveSession()
        CareLinkSettings(app).enabled = true
        CareLinkBootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(1, alarms.size)
        CareLinkScheduler.disarm(app)
        CareLinkBootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(1, alarms.size)
        CareLinkScheduler.disarm(app)
        CareLinkBootReceiver().onReceive(app, Intent("made.up.ACTION"))
        assertTrue(alarms.isEmpty())
    }

    @Test
    fun theAlarmRemembersThePreviousOne() {
        // critic 4d2, N3: the alarm's "no data" check asks whether a poll finished since the previous alarm
        assertNull(CareLinkScheduler.markAlarm(app, now))
        assertEquals(now, CareLinkScheduler.markAlarm(app, now + 300_000L))
        CareLinkScheduler.disarm(app)
        assertNull("a sign-out or \"sign in again\" starts over", CareLinkScheduler.markAlarm(app, now + 600_000L))
    }

    @Test
    fun intervalOnlyFiveTenOrFifteen() {
        val s = CareLinkSettings(app)
        assertEquals(5, s.intervalMin)
        s.intervalMin = 10
        assertEquals(10, s.intervalMin)
        s.intervalMin = 7
        assertEquals(5, s.intervalMin)
        assertNull(s.presetId)
        s.presetId = 3L
        assertEquals(3L, s.presetId)
        s.presetId = null
        assertNull(s.presetId)
        assertTrue(s.writeCarbs)
        assertFalse(s.sendToNightscout)
        assertFalse(s.enabled)
    }
}
