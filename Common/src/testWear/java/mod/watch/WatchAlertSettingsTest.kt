package mod.watch

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.alerts.AlertDeliveryMode
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.HapticProfile
import tk.glucodata.mod.watch.ModAlertSettings
import tk.glucodata.mod.watch.ModAlertSettings.Settings
import tk.glucodata.mod.watch.ModWatchMessages
import tk.glucodata.mod.watch.ModWearListener
import tk.glucodata.mod.watch.PhoneBatteryStore
import tk.glucodata.mod.watch.WatchAlertStore

/**
 * JugglucoNGmod, watch block (parts 7b, 7d, 7f, 7g, 7h): `/mod/alerts` arriving on the watch - written into upstream's
 * alert file, or not at all; never an alert's on/off; never a sound, always the vibration; an overtaken message dropped, a change after a phone clock set
 * back not; the kept message written again after upstream's toggle sync; after every message the on/off checked
 * against the phone's, a lost state request asked again up to a limit. Made-up values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WatchAlertSettingsTest {

    /** Native thresholds in mg/L; the watch build's tests have no native side. */
    private class Levels : ModAlertSettings.ThresholdStore {
        val levels = HashMap<AlertType, Int>()
        override fun read(type: AlertType): Int? = levels[type]
        override fun write(type: AlertType, mgPerL: Int) {
            levels[type] = mgPerL
        }
    }

    private lateinit var context: Context

    private val alerts get() = context.getSharedPreferences(ModAlertSettings.ALERT_PREFS, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        alerts.edit().clear().commit()
        context.getSharedPreferences(PhoneBatteryStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        WatchAlertStore.resetForTests()
    }

    private fun settings(kind: Int, seconds: Int) = Settings(
        kind = kind, soundEnabled = true, vibrationEnabled = true, soundDelayEnabled = false, retryEnabled = true,
        alarmDurationSeconds = seconds, hapticProfile = HapticProfile.STRONG, soundVolumePercent = 100,
        soundDelaySeconds = 0, retryIntervalMinutes = 0, retryCount = 0, defaultSnoozeMinutes = 30,
        deliveryMode = AlertDeliveryMode.NOTIFICATION_ONLY, timeRangeEnabled = true, activeStartHour = 22,
        activeStartMinute = 0, activeEndHour = 7, activeEndMinute = ModAlertSettings.TIME_UNSET,
    )

    @Test
    fun thePhoneSettingsLandUnderUpstreamKeys() {
        val data = ModAlertSettings.encode(listOf(settings(AlertType.LOW.id, 40), settings(AlertType.MISSED_READING.id, 15)), 1_000L)
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_ALERTS, data))
        val low = "alert_${AlertType.LOW.id}_"
        assertEquals(40, alerts.getInt(low + "alarmDur", 5))
        assertTrue(alerts.getBoolean(low + "retryOn", false))
        assertEquals(0, alerts.getInt(low + "retryInt", 5))
        assertEquals(0, alerts.getInt(low + "retryCnt", 3))
        assertEquals("STRONG", alerts.getString(low + "haptic", null))
        assertEquals(100, alerts.getInt(low + "soundVolumePercent", 80))
        assertEquals(30, alerts.getInt(low + "snooze", 15))
        assertEquals("NOTIFICATION_ONLY", alerts.getString(low + "delivery", null))
        assertTrue(alerts.getBoolean(low + "timeRange", false))
        assertEquals(22, alerts.getInt(low + "startHour", -1))
        assertEquals(0, alerts.getInt(low + "startMin", -1))
        assertEquals(7, alerts.getInt(low + "endHour", -1))
        assertFalse(alerts.contains(low + "endMin"))
        assertEquals(15, alerts.getInt("alert_${AlertType.MISSED_READING.id}_alarmDur", 5))
        // the watch only vibrates (owner's rule, part 7h): the phone's sound on lands as off
        assertFalse(alerts.getBoolean(low + "sound", true))
        assertTrue(alerts.getBoolean(low + "vibration", false))
        // no alert's on/off is ever written by the watch, native-backed or prefs-only
        assertFalse(alerts.contains(low + "enabled"))
        assertFalse(alerts.contains("alert_${AlertType.MISSED_READING.id}_enabled"))
        // the own melody, flash and DND never come from the phone
        assertFalse(alerts.contains(low + "soundUri"))
        assertFalse(alerts.contains(low + "flash"))
        assertFalse(alerts.contains(low + "dnd"))
    }

    @Test
    fun theWatchOnlyVibratesWhateverThePhoneHas() {
        val high = "alert_${AlertType.HIGH.id}_"
        // the phone sounds without vibration; the watch: no sound, vibration on, the duration as the phone's
        val phone = settings(AlertType.HIGH.id, 30).copy(soundEnabled = true, vibrationEnabled = false)
        assertTrue(WatchAlertStore.onMessage(context, ModAlertSettings.encode(listOf(phone), 1_000L), Levels(), NoToggles))
        assertFalse(alerts.getBoolean(high + "sound", true))
        assertTrue(alerts.getBoolean(high + "vibration", false))
        assertEquals(30, alerts.getInt(high + "alarmDur", 5))
        // someone turns the sound on in the watch's file: written back to off with the kept message
        alerts.edit().putBoolean(high + "sound", true).commit()
        assertEquals(1, WatchAlertStore.reapply(context, Levels()))
        assertFalse(alerts.getBoolean(high + "sound", true))
        assertEquals(
            listOf(phone.copy(soundEnabled = false, vibrationEnabled = true)),
            WatchAlertStore.vibrationOnly(listOf(phone)),
        )
    }

    @Test
    fun aBrokenOrNewerMessageChangesNothing() {
        alerts.edit().putInt("alert_${AlertType.LOW.id}_alarmDur", 9).commit()
        val good = ModAlertSettings.encode(listOf(settings(AlertType.LOW.id, 40)))
        for (bad in listOf(null, ByteArray(0), good.copyOf(good.size - 1), good.copyOf().also { it[0] = 2 })) {
            assertFalse(ModWearListener.handle(context, ModWatchMessages.PATH_ALERTS, bad))
        }
        assertEquals(mapOf("alert_${AlertType.LOW.id}_alarmDur" to 9), alerts.all)
    }

    @Test
    fun theSameSettingsAgainAreStillAValidMessage() {
        val data = ModAlertSettings.encode(listOf(settings(AlertType.HIGH.id, 20)), 1_000L)
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_ALERTS, data))
        val before = alerts.all
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_ALERTS, data))
        assertEquals(before, alerts.all)
    }

    @Test
    fun anOvertakenMessageIsDroppedAClockSetBackIsNot() {
        val t = 1_800_000_000_000L
        val key = "alert_${AlertType.HIGH.id}_alarmDur"
        // all within seconds of each other on the watch
        fun send(seconds: Int, at: Long?) = WatchAlertStore.onMessage(
            context, ModAlertSettings.encode(listOf(settings(AlertType.HIGH.id, seconds)), at), Levels(), NoToggles, 5_000L,
        )
        assertTrue(send(40, t))
        // the tick read the settings before the change went, and arrived after it
        assertTrue(send(20, t - 5_000))
        assertEquals(40, alerts.getInt(key, 0))
        // the same time again, or newer: written
        assertTrue(send(41, t))
        assertEquals(41, alerts.getInt(key, 0))
        // older by more than the window: the phone clock went back - written, and the new base
        assertTrue(send(30, t - WatchAlertStore.STALE_WINDOW_MS))
        assertEquals(30, alerts.getInt(key, 0))
        assertTrue(send(31, t - WatchAlertStore.STALE_WINDOW_MS + 1))
        assertEquals(31, alerts.getInt(key, 0))
        // a phone of part 7b sends no time: always written
        assertTrue(send(25, null))
        assertEquals(25, alerts.getInt(key, 0))
    }

    @Test
    fun staleIsOnlyAShortStepBackArrivingSoon() {
        val soon = 1_000L
        assertFalse(WatchAlertStore.isStale(null, 100L, soon))
        assertFalse(WatchAlertStore.isStale(100L, null, soon))
        assertFalse(WatchAlertStore.isStale(100L, 100L, soon))
        assertFalse(WatchAlertStore.isStale(101L, 100L, soon))
        val last = 10 * WatchAlertStore.STALE_WINDOW_MS
        assertTrue(WatchAlertStore.isStale(last - 1, last, soon))
        assertTrue(WatchAlertStore.isStale(last - WatchAlertStore.STALE_WINDOW_MS + 1, last, soon))
        assertFalse(WatchAlertStore.isStale(last - WatchAlertStore.STALE_WINDOW_MS, last, soon))
        // long after the last accepted one, or no receipt known in this process: not an overtaken message
        assertTrue(WatchAlertStore.isStale(last - 1, last, WatchAlertStore.OVERTAKE_WINDOW_MS - 1))
        assertFalse(WatchAlertStore.isStale(last - 1, last, WatchAlertStore.OVERTAKE_WINDOW_MS))
        assertFalse(WatchAlertStore.isStale(last - 1, last, null))
        assertFalse(WatchAlertStore.isStale(last - 1, last, -1L))
    }

    @Test
    fun aChangeAfterThePhoneClockWentBackIsNotLost() {
        // critic M1 of part 7d: the phone clock was 3 min ahead when a message was accepted, then set right
        val t = 1_800_000_000_000L
        val key = "alert_${AlertType.LOW.id}_alarmDur"
        fun send(seconds: Int, at: Long, uptime: Long) = WatchAlertStore.onMessage(
            context, ModAlertSettings.encode(listOf(settings(AlertType.LOW.id, seconds)), at), Levels(), NoToggles, uptime,
        )
        assertTrue(send(40, t + 3 * 60_000L, 100_000L))
        // the change comes minutes later on the watch: written though its stamp is older
        assertTrue(send(20, t + 60_000L, 100_000L + 2 * 60_000L))
        assertEquals(20, alerts.getInt(key, 0))
        // a watch process started again knows no receipt: written too
        WatchAlertStore.resetForTests()
        assertTrue(send(25, t, 1_000L))
        assertEquals(25, alerts.getInt(key, 0))
    }

    /**
     * Native on/off as the watch holds it and as the phone last sent it; [mends]: the phone's answer to a state request
     * corrects the watch (upstream's apply), else the request is lost.
     */
    private class Toggles(val phone: Map<AlertType, Boolean>, val mends: Boolean = false) : WatchAlertStore.ToggleState {
        val watch = ConcurrentHashMap(phone)
        @Volatile
        var requests = 0
        /** Wall time of the first comparison: phoneEnabled is read first. */
        @Volatile
        var firstReadAtMs = 0L
        override fun watchEnabled(type: AlertType): Boolean? = watch[type]
        override fun phoneEnabled(type: AlertType): Boolean? {
            if (firstReadAtMs == 0L) firstReadAtMs = System.currentTimeMillis()
            return phone[type]
        }
        override fun requestState() {
            requests++
            if (mends) watch.putAll(phone)
        }
    }

    /** Nothing from the phone yet: nothing is compared, so a state request cannot happen. */
    private object NoToggles : WatchAlertStore.ToggleState {
        override fun watchEnabled(type: AlertType): Boolean? = null
        override fun phoneEnabled(type: AlertType): Boolean? = null
        override fun requestState() {}
    }

    private val phoneToggles = mapOf(AlertType.LOW to true, AlertType.HIGH to true, AlertType.LOSS to false)

    private fun awaitRequests(toggles: Toggles, n: Int) {
        val until = System.currentTimeMillis() + WatchAlertStore.REAPPLY_DELAY_MS + 5_000
        while (toggles.requests < n && System.currentTimeMillis() < until) Thread.sleep(50)
    }

    private fun noLevel(kind: Int) = ModAlertSettings.encode(listOf(settings(kind, 40)), 1_000L)

    @Test
    fun aNeighbourSwitchedOffByTheRaceAsksThePhoneForItsState() {
        val toggles = Toggles(phoneToggles)
        // our level write read "high off" just before their toggle sync turned it on, and wrote the group back
        toggles.watch[AlertType.HIGH] = false
        assertEquals(WatchAlertStore.Check(listOf(AlertType.HIGH), asked = true), WatchAlertStore.checkToggles(toggles))
        assertEquals(1, toggles.requests)
    }

    @Test
    fun theSameOnOffAsThePhoneAsksNothing() {
        val toggles = Toggles(phoneToggles)
        assertEquals(WatchAlertStore.Check(emptyList(), asked = false), WatchAlertStore.checkToggles(toggles))
        assertEquals(0, toggles.requests)
        // nothing from the phone yet: nothing compared, nothing asked
        assertEquals(emptyList<AlertType>(), WatchAlertStore.checkToggles(Toggles(emptyMap())).differ)
    }

    @Test
    fun atMostThreeStateRequestsInTenMinutes() {
        val t = 1_000_000L
        assertEquals(0, WatchAlertStore.mayAsk(t))
        assertEquals(1, WatchAlertStore.mayAsk(t + 1))
        assertEquals(2, WatchAlertStore.mayAsk(t + 2))
        assertEquals(-1, WatchAlertStore.mayAsk(t + 3))
        assertEquals(-1, WatchAlertStore.mayAsk(t + WatchAlertStore.ASK_WINDOW_MS - 1))
        // the first one left the window
        assertEquals(2, WatchAlertStore.mayAsk(t + WatchAlertStore.ASK_WINDOW_MS))
        // past the limit a difference is reported but not asked
        val toggles = Toggles(phoneToggles).also { it.watch[AlertType.LOW] = false }
        assertEquals(WatchAlertStore.Check(listOf(AlertType.LOW), asked = false), WatchAlertStore.checkToggles(toggles, t + 4))
        assertEquals(0, toggles.requests)
    }

    @Test
    fun aMessageWithoutALevelStillChecksTheOnOff() {
        // critic M1 of part 7f: a request lost earlier left a difference; the next message (the hourly resend at the
        // latest) writes no level, and still finds it and asks
        val toggles = Toggles(phoneToggles)
        toggles.watch[AlertType.LOW] = false
        assertTrue(WatchAlertStore.onMessage(context, noLevel(AlertType.LOW.id), Levels(), toggles))
        awaitRequests(toggles, 1)
        assertEquals(1, toggles.requests)
    }

    @Test
    fun aDifferenceThatStaysIsAskedAgainUpToTheLimit() {
        WatchAlertStore.askRetryMs = 100
        // every request is lost
        val toggles = Toggles(phoneToggles)
        toggles.watch[AlertType.LOW] = false
        assertTrue(WatchAlertStore.onMessage(context, noLevel(AlertType.LOW.id), Levels(), toggles))
        awaitRequests(toggles, WatchAlertStore.MAX_ASKS)
        Thread.sleep(1_000)
        assertEquals(WatchAlertStore.MAX_ASKS, toggles.requests)
    }

    @Test
    fun aDifferenceMendedByThePhoneIsNotAskedAgain() {
        WatchAlertStore.askRetryMs = 100
        val toggles = Toggles(phoneToggles, mends = true)
        toggles.watch[AlertType.HIGH] = false
        assertTrue(WatchAlertStore.onMessage(context, noLevel(AlertType.LOW.id), Levels(), toggles))
        awaitRequests(toggles, 1)
        Thread.sleep(1_000)
        assertEquals(1, toggles.requests)
        assertEquals(true, toggles.watch[AlertType.HIGH])
    }

    @Test
    fun theirSaveInTheSameMomentKeepsTheCheck() {
        // critic L4 of part 7f: their toggle sync writes an on/off just as our message is written; the listener's
        // task replaces ours and must still check
        val toggles = Toggles(phoneToggles)
        toggles.watch[AlertType.HIGH] = false
        assertTrue(WatchAlertStore.onMessage(context, noLevel(AlertType.LOW.id), Levels(), toggles))
        alerts.edit().putBoolean("alert_${AlertType.MISSED_READING.id}_enabled", true).commit()
        awaitRequests(toggles, 1)
        assertEquals(1, toggles.requests)
    }

    @Test
    fun theRoundsEndAndTheCheckComesAPauseAfterTheLastWrite() {
        // a level that never stays (their save puts the old one back every time)
        val store = object : ModAlertSettings.ThresholdStore {
            @Volatile
            var writes = 0
            @Volatile
            var lastWriteAtMs = 0L
            override fun read(type: AlertType): Int? = 500
            override fun write(type: AlertType, mgPerL: Int) {
                writes++
                lastWriteAtMs = System.currentTimeMillis()
            }
        }
        val toggles = Toggles(phoneToggles)
        val data = ModAlertSettings.encode(listOf(settings(AlertType.LOW.id, 40).copy(thresholdMgPerL = 700)), 1_000L)
        assertTrue(WatchAlertStore.onMessage(context, data, store, toggles))
        val until = System.currentTimeMillis() + (WatchAlertStore.MAX_CHECK_ROUNDS + 1) * WatchAlertStore.REAPPLY_DELAY_MS + 5_000
        while (toggles.firstReadAtMs == 0L && System.currentTimeMillis() < until) Thread.sleep(50)
        // the message's write and one per round, then the check alone - a pause after the last write (critic L1 of 7f)
        assertEquals(1 + WatchAlertStore.MAX_CHECK_ROUNDS, store.writes)
        assertTrue(toggles.firstReadAtMs - store.lastWriteAtMs >= WatchAlertStore.REAPPLY_DELAY_MS - 200)
        Thread.sleep(WatchAlertStore.REAPPLY_DELAY_MS + 500)
        assertEquals(1 + WatchAlertStore.MAX_CHECK_ROUNDS, store.writes)
        assertEquals(0, toggles.requests)
    }

    @Test
    fun aNativeWriteIsFollowedByTheOnOffCheck() {
        val toggles = Toggles(phoneToggles)
        val racing = object : ModAlertSettings.ThresholdStore {
            val levels = HashMap<AlertType, Int>()
            override fun read(type: AlertType): Int? = levels[type]
            override fun write(type: AlertType, mgPerL: Int) {
                levels[type] = mgPerL
                toggles.watch[AlertType.HIGH] = false
            }
        }
        val data = ModAlertSettings.encode(listOf(settings(AlertType.LOW.id, 40).copy(thresholdMgPerL = 700)), 1_000L)
        assertTrue(WatchAlertStore.onMessage(context, data, racing, toggles))
        assertEquals(0, toggles.requests)
        val until = System.currentTimeMillis() + WatchAlertStore.REAPPLY_DELAY_MS + 5_000
        while (toggles.requests == 0 && System.currentTimeMillis() < until) Thread.sleep(100)
        assertEquals(1, toggles.requests)
        assertEquals(700, racing.levels[AlertType.LOW])
    }

    @Test
    fun theToggleListenerIsInPlaceBeforeTheFirstWrite() {
        assertFalse(WatchAlertStore.isWatchingToggles())
        var watchingAtWrite: Boolean? = null
        val store = object : ModAlertSettings.ThresholdStore {
            override fun read(type: AlertType): Int? = null
            override fun write(type: AlertType, mgPerL: Int) {
                if (watchingAtWrite == null) watchingAtWrite = WatchAlertStore.isWatchingToggles()
            }
        }
        val data = ModAlertSettings.encode(listOf(settings(AlertType.LOW.id, 40).copy(thresholdMgPerL = 700)), 1_000L)
        assertTrue(WatchAlertStore.onMessage(context, data, store, Toggles(emptyMap())))
        assertEquals(true, watchingAtWrite)
    }

    @Test
    fun theKeptMessageIsWrittenAgainAfterTheirToggleSync() {
        val missed = "alert_${AlertType.MISSED_READING.id}_"
        val levels = Levels()
        val s = settings(AlertType.MISSED_READING.id, 40)
        assertTrue(WatchAlertStore.onMessage(context, ModAlertSettings.encode(listOf(s), 1_000L), levels))
        // their toggle sync turns the alert on: their save writes the defaults over ours, with "enabled"
        alerts.edit().putBoolean(missed + "enabled", true).putBoolean(missed + "retryOn", false)
            .putInt(missed + "snooze", 15).putInt(missed + "alarmDur", 5).commit()
        assertEquals(3, WatchAlertStore.reapply(context, levels))
        assertTrue(alerts.getBoolean(missed + "retryOn", false))
        assertEquals(30, alerts.getInt(missed + "snooze", 0))
        assertEquals(40, alerts.getInt(missed + "alarmDur", 0))
        // their "enabled" stays as they wrote it
        assertTrue(alerts.getBoolean(missed + "enabled", false))
        assertEquals(0, WatchAlertStore.reapply(context, levels))
    }

    @Test
    fun aToggleOnTheWatchBringsOursBackByItself() {
        val missed = "alert_${AlertType.MISSED_READING.id}_"
        assertTrue(WatchAlertStore.onMessage(context, ModAlertSettings.encode(listOf(settings(AlertType.MISSED_READING.id, 40)), 1_000L)))
        alerts.edit().putBoolean(missed + "enabled", true).putInt(missed + "alarmDur", 5).commit()
        val until = System.currentTimeMillis() + WatchAlertStore.REAPPLY_DELAY_MS + 5_000
        while (alerts.getInt(missed + "alarmDur", 0) != 40 && System.currentTimeMillis() < until) Thread.sleep(100)
        assertEquals(40, alerts.getInt(missed + "alarmDur", 0))
    }

    @Test
    fun nothingKeptNothingWrittenAgain() {
        assertEquals(0, WatchAlertStore.reapply(context, Levels()))
        assertTrue(WatchAlertStore.isToggleKey("alert_9_enabled"))
        assertTrue(WatchAlertStore.isToggleKey("alert_0_enabled"))
        assertFalse(WatchAlertStore.isToggleKey("alert_2_enabled"))
        assertFalse(WatchAlertStore.isToggleKey("alert_9_alarmDur"))
        assertFalse(WatchAlertStore.isToggleKey(null))
    }
}
