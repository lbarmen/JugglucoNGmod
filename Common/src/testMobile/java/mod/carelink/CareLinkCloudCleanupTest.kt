package mod.carelink

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
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
import tk.glucodata.mod.carelink.CareLinkCloudCleanup
import tk.glucodata.mod.carelink.CareLinkSettings
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.pumpbt.PumpBtWatchdog
import java.io.File

/**
 * JugglucoNGmod, pump over Bluetooth (part D1): the one-time removal of what the CareLink cloud left on a phone. All
 * that is named goes; `seen.json`, `pump.json`, `lastDataAtMs`, `mod_carelink_settings`, `mod-pumpbt/` and the pump
 * watchdog's alarm stay; a second run does nothing; a failing step does not stop the others and leaves the cleanup to
 * the next start; without a sign-in the cookies are not touched. WorkManager, the Keystore and the cookies are seams.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkCloudCleanupTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val dir: File get() = File(app.noBackupFilesDir, CareLinkStateStore.DIR)
    private val am get() = app.getSystemService(AlarmManager::class.java)
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    private val worksCancelled = mutableListOf<String>()
    private var keyDeletes = 0
    private var cookieClears = 0
    private var workFails = false

    private val cancelWork0 = CareLinkCloudCleanup.cancelWork
    private val deleteKey0 = CareLinkCloudCleanup.deleteKey
    private val clearCookies0 = CareLinkCloudCleanup.clearCookies

    @Before
    fun setUp() {
        CareLinkCloudCleanup.cancelWork = { _, name ->
            if (workFails) throw IllegalStateException("made-up")
            worksCancelled += name
        }
        CareLinkCloudCleanup.deleteKey = { keyDeletes++; true }
        CareLinkCloudCleanup.clearCookies = { cookieClears++ }
    }

    @After
    fun tearDown() {
        CareLinkCloudCleanup.cancelWork = cancelWork0
        CareLinkCloudCleanup.deleteKey = deleteKey0
        CareLinkCloudCleanup.clearCookies = clearCookies0
        dir.deleteRecursively()
    }

    private fun file(name: String) = File(dir, name)

    private fun mark(): Set<String> = file(CareLinkCloudCleanup.MARK_FILE).readLines().filter { it.isNotBlank() }.toSet()

    private fun put(name: String, text: String = "x") {
        dir.mkdirs()
        file(name).writeText(text)
    }

    private fun cloudAlarm(flags: Int): PendingIntent? = PendingIntent.getBroadcast(
        app, 1_947_000,
        Intent().setClassName(app.packageName, "tk.glucodata.mod.carelink.CareLinkAlarmReceiver")
            .setAction("tk.glucodata.mod.carelink.POLL"),
        flags or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** What a signed-in phone of build 14 or older keeps, with the pump source's own files next to it. */
    private fun signedInPhone() {
        listOf("session.bin", "session.bin.tmp", "pending.json", "pending.json.tmp", "enabled").forEach { put(it) }
        put("seen.json", "{}")
        put("pump.json", "{}")
        File(app.noBackupFilesDir, "mod-pumpbt").apply { mkdirs() }.resolve("source_on").writeText("")
        app.getSharedPreferences("mod_carelink_schedule", Context.MODE_PRIVATE).edit()
            .putLong("nextAlarmElapsedMs", 1L).putBoolean("armed", true).commit()
        app.getSharedPreferences(CareLinkStateStore.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("firstPollPending", true).putBoolean("needLoginShown", true).putString("status", "NEED_LOGIN")
            .putLong("lastDataAtMs", 1_800_000_000_000L).putBoolean("noDataShown", true).commit()
        app.getSharedPreferences(CareLinkSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putInt("intervalMin", 10).putBoolean("sendToNightscout", true).putInt("warnAfterMin", 30).commit()
        am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 60_000L, cloudAlarm(0)!!)
        am.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 300_000L,
            PumpBtWatchdog.intent(app, 0)!!,
        )
        nm.createNotificationChannel(NotificationChannel("mod_carelink", "Pump", NotificationManager.IMPORTANCE_DEFAULT))
        listOf(1_947_001, 1_947_002).forEach { id ->
            nm.notify(id, Notification.Builder(app, "mod_carelink").setSmallIcon(android.R.drawable.ic_dialog_info).build())
        }
    }

    @Test
    fun everythingNamedGoesAndThePumpSourcesDataAndWatchdogStay() {
        signedInPhone()

        CareLinkCloudCleanup.run(app)

        listOf("session.bin", "session.bin.tmp", "pending.json", "pending.json.tmp", "enabled").forEach {
            assertFalse(it, file(it).exists())
        }
        assertEquals(listOf("mod-carelink-poll", "mod-carelink-now", "mod-carelink-periodic"), worksCancelled)
        assertEquals(1, keyDeletes)
        assertEquals(1, cookieClears)
        assertNull("cloud alarm", cloudAlarm(PendingIntent.FLAG_NO_CREATE))
        assertNotNull("watchdog alarm", PumpBtWatchdog.intent(app, PendingIntent.FLAG_NO_CREATE))
        val left = shadowOf(am).scheduledAlarms
        assertEquals(1, left.size)
        assertEquals(PumpBtWatchdog.intent(app, PendingIntent.FLAG_NO_CREATE), left[0].operation)
        assertEquals(listOf(1_947_002), nm.activeNotifications.map { it.id })
        assertTrue(app.getSharedPreferences("mod_carelink_schedule", Context.MODE_PRIVATE).all.isEmpty())

        val state = app.getSharedPreferences(CareLinkStateStore.PREFS, Context.MODE_PRIVATE)
        assertFalse(state.contains("firstPollPending"))
        assertFalse(state.contains("needLoginShown"))
        assertFalse(state.contains("status"))
        assertTrue(state.getBoolean("noDataShown", false))
        assertEquals(1_800_000_000_000L, CareLinkStateStore(app).read().lastDataAtMs)
        val settings = CareLinkSettings(app)
        assertFalse(app.getSharedPreferences(CareLinkSettings.PREFS, Context.MODE_PRIVATE).contains("intervalMin"))
        assertTrue(settings.sendToNightscout)
        assertEquals(30, settings.warnAfterMin)
        assertEquals("{}", file("seen.json").readText())
        assertEquals("{}", file("pump.json").readText())
        assertTrue(File(app.noBackupFilesDir, "mod-pumpbt/source_on").exists())
        assertEquals(setOf("signed-in", "done", "cookies-tried"), mark())
    }

    @Test
    fun theCloudsValuesStoredByOlderBuildsReadSafely() {
        app.getSharedPreferences(CareLinkStateStore.PREFS, Context.MODE_PRIVATE).edit()
            .putString("status", "NEED_LOGIN").putString("lastErrorKind", "SERVER").putInt("lastErrorCode", 503)
            .putLong("lastDataAtMs", 1_800_000_000_000L).commit()

        val s = CareLinkStateStore(app).read()

        assertEquals(tk.glucodata.mod.carelink.CareLinkStatus.OFF, s.status)
        assertNull(s.lastError)
        assertEquals(1_800_000_000_000L, s.lastDataAtMs)
    }

    @Test
    fun aStatusOtherThanNeedLoginStays() {
        app.getSharedPreferences(CareLinkStateStore.PREFS, Context.MODE_PRIVATE).edit().putString("status", "OK").commit()
        CareLinkCloudCleanup.run(app)
        assertEquals("OK", app.getSharedPreferences(CareLinkStateStore.PREFS, Context.MODE_PRIVATE).getString("status", null))
    }

    @Test
    fun theSecondRunDoesNothing() {
        signedInPhone()
        CareLinkCloudCleanup.run(app)
        put("session.bin")
        worksCancelled.clear()

        CareLinkCloudCleanup.run(app)

        assertTrue(file("session.bin").exists())
        assertTrue(worksCancelled.isEmpty())
        assertEquals(1, keyDeletes)
        assertEquals(1, cookieClears)
    }

    @Test
    fun aFailingStepDoesNotStopTheOthersAndTheNextStartFinishesWithTheSignInRemembered() {
        signedInPhone()
        workFails = true

        CareLinkCloudCleanup.run(app)

        assertFalse(file("session.bin").exists())
        assertFalse(file("enabled").exists())
        assertNull(cloudAlarm(PendingIntent.FLAG_NO_CREATE))
        assertEquals(1, keyDeletes)
        assertTrue(app.getSharedPreferences("mod_carelink_schedule", Context.MODE_PRIVATE).all.isEmpty())
        assertEquals("the cookies wait for the cleanup to be done", 0, cookieClears)
        assertEquals(setOf("signed-in", "attempt=1"), mark())

        // the next start: the files that told of a sign-in are gone, the mark file remembers it
        workFails = false
        CareLinkCloudCleanup.run(app)

        assertEquals(listOf("mod-carelink-poll", "mod-carelink-now", "mod-carelink-periodic"), worksCancelled)
        assertEquals(1, cookieClears)
        assertEquals(setOf("signed-in", "done", "cookies-tried"), mark())
    }

    @Test
    fun theCookiesComeAfterDoneIsWrittenAndAreTriedOnceEvenWhenTheyFail() {
        // critic D1, C-1: a WebView that hangs or takes the process down must not be started at every start
        put("session.bin")
        var markWhenCalled: Set<String>? = null
        CareLinkCloudCleanup.clearCookies = {
            cookieClears++
            markWhenCalled = mark()
            throw IllegalStateException("no WebView")
        }

        CareLinkCloudCleanup.run(app)
        CareLinkCloudCleanup.run(app)

        assertEquals(setOf("signed-in", "done", "cookies-tried"), markWhenCalled)
        assertEquals(setOf("signed-in", "done", "cookies-tried"), mark())
        assertEquals(1, cookieClears)
    }

    @Test
    fun aStepThatAlwaysFailsEndsAsPartialAfterThreeRunsWithTheCookiesTriedOnce() {
        signedInPhone()
        var keyTries = 0
        CareLinkCloudCleanup.deleteKey = { keyTries++; throw java.security.KeyStoreException("made-up") }

        CareLinkCloudCleanup.run(app)
        assertEquals(setOf("signed-in", "attempt=1"), mark())
        CareLinkCloudCleanup.run(app)
        assertEquals(setOf("signed-in", "attempt=2"), mark())
        assertEquals(0, cookieClears)
        CareLinkCloudCleanup.run(app)
        assertEquals(setOf("signed-in", "done", "partial", "cookies-tried"), mark())
        assertEquals(1, cookieClears)
        CareLinkCloudCleanup.run(app)

        assertEquals("not tried after partial", 3, keyTries)
        assertEquals(1, cookieClears)
    }

    @Test
    fun oneFileThatCannotBeDeletedDoesNotStopTheOthers() {
        signedInPhone()
        file("session.bin").delete()
        File(file("session.bin"), "inside").apply { parentFile!!.mkdirs() }.writeText("x") // a folder: delete() fails

        CareLinkCloudCleanup.run(app)

        assertTrue(file("session.bin").exists())
        listOf("session.bin.tmp", "pending.json", "pending.json.tmp", "enabled").forEach { assertFalse(it, file(it).exists()) }
        assertEquals(1, keyDeletes)
        assertEquals(setOf("signed-in", "attempt=1"), mark())
    }

    @Test
    fun withTheSourceOffTheCloudsNotificationMarksGo() {
        // critic D1, C-2: left by the cloud, "no data" shown would keep the source's first one back, and the write clock
        // would make its first empty cycle a false "not saved"
        put("session.bin")
        val store = CareLinkStateStore(app)
        store.noDataShown = true
        store.writeFailShown = true
        store.writeFailSinceMs = 1_790_000_000_000L
        nm.createNotificationChannel(NotificationChannel("mod_carelink", "Pump", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(1_947_002, Notification.Builder(app, "mod_carelink").setSmallIcon(android.R.drawable.ic_dialog_info).build())

        CareLinkCloudCleanup.run(app)

        assertFalse(store.noDataShown)
        assertFalse(store.writeFailShown)
        assertNull(store.writeFailSinceMs)
        assertTrue(nm.activeNotifications.isEmpty())
        assertTrue("done" in mark())
    }

    @Test
    fun withoutASignInTheCookiesAreNotTouched() {
        put("seen.json", "{}")

        CareLinkCloudCleanup.run(app)

        assertEquals(0, cookieClears)
        assertEquals(3, worksCancelled.size)
        assertEquals(setOf("done"), mark())
        assertEquals("{}", file("seen.json").readText())
    }
}
