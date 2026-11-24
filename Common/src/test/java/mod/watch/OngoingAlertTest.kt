package mod.watch

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import tk.glucodata.Applic
import tk.glucodata.Log
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.WearAlertSync
import tk.glucodata.alerts.AlertActions
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.SnoozeManager
import tk.glucodata.mod.watch.OngoingAlert
import tk.glucodata.ui.AlarmActivityAccess

/**
 * JugglucoNGmod, watch block: the alert going on and its snooze ([OngoingAlert]) against upstream's real action
 * handler, tracker, snooze store and "current alert" check - the way upstream's WearAlertSyncTests runs them. Their
 * presented mark is set through reflection, as their delivery would; only native audio and notification delivery is
 * a recording sink. Runs in both test tasks (phone and watch).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OngoingAlertTest.TestApplication::class,
    shadows = [OngoingAlertTest.AlarmSink::class, OngoingAlertTest.NativeSink::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class OngoingAlertTest {

    class TestApplication : Applic() {
        override fun onCreate() {}
        override fun startActivity(intent: android.content.Intent) {}
    }

    /** Notify's sound and notification side; its alert queries (isCurrentStandardAlert, ...) run for real. */
    @Implements(Notify::class)
    class AlarmSink {
        companion object {
            val calls = mutableListOf<String>()
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun cancelRetrySession(kind: Int, reason: String) { calls += "retry:$kind" }
            @JvmStatic @Implementation fun cancelQueuedAlarmActivityLaunch(kind: Int, custom: String?, reason: String) {}
            @JvmStatic @Implementation fun stopalarm() { calls += "stop" }
            @JvmStatic @Implementation fun stopalarmnotsend(send: Boolean) { calls += "remote-stop:$send" }
            /** As the real one: the notification goes and so does the presented mark. */
            @JvmStatic @Implementation fun cancelAlertNotification() {
                calls += "notification"
                OngoingAlertTest.presented = -1
            }
            /** Upstream's private kind of the sound on (their playringhier sets it with the native flag); -1 = none. */
            var lastAlarm = -1
            /** As the real one without a repeat session: the kind of the sound on, else the presented one. */
            @JvmStatic @Implementation fun resolveAlertKind(fallback: Int) = when {
                lastAlarm >= 0 -> lastAlarm
                OngoingAlertTest.presented >= 0 -> OngoingAlertTest.presented
                else -> fallback
            }
        }
    }

    @Implements(value = Natives::class, callThroughByDefault = false)
    class NativeSink {
        companion object {
            /** Upstream's native "an alarm sound is on". */
            var soundOn = false
            @JvmStatic @Implementation fun getisalarm() = soundOn
        }
    }

    private val acknowledged = mutableListOf<Int>()
    private val observer = Any()

    companion object {
        private val presentedField = Notify::class.java.getDeclaredField("presentedStandardAlertKind")
            .apply { isAccessible = true }

        /** Upstream's presented standard alert (their private mark; -1 = none). */
        var presented: Int
            get() = presentedField.getInt(null)
            set(value) = presentedField.setInt(null, value)

        /** An alert of [type] fired and is shown: upstream's delivery opens the episode and sets the mark. */
        fun alertGoingOn(type: AlertType) {
            AlertStateTracker.onAlertTriggered(type)
            presented = type.id
        }

        /** An alarm sound of [kind] is on: upstream sets the native flag and its kind together. */
        fun soundOf(kind: Int) {
            NativeSink.soundOn = true
            AlarmSink.lastAlarm = kind
        }

        /**
         * Upstream's alert state as a fresh install, before and after each test. SnoozeManager keeps the preferences
         * of the first test application it saw (`by lazy`), so clearing the current application's file is not
         * enough: a snooze left there would leak into the next test class (upstream's WearAlertSyncTests included).
         */
        fun resetAlertState() {
            Applic.app = ApplicationProvider.getApplicationContext<TestApplication>()
            Applic.app.getSharedPreferences("wear_alert_ack", 0).edit().clear().commit()
            Applic.app.getSharedPreferences("tk.glucodata.snooze", 0).edit().clear().commit()
            AlertType.entries.forEach {
                SnoozeManager.clearSnooze(it, recordCancellation = false)
                AlertStateTracker.resetState(it)
                AlertStateTracker.consumeManualTestAction(it)
            }
            AlarmSink.calls.clear()
            AlarmSink.lastAlarm = -1
            NativeSink.soundOn = false
            presented = -1
            Log.doLog = false
            AlarmActivityAccess.register { android.content.Intent("test.alarm") }
        }
    }

    @Before
    fun setUp() {
        resetAlertState()
        acknowledged.clear()
    }

    @After
    fun tearDown() {
        AlarmActivityAccess.removeObserver(observer)
        resetAlertState()
    }

    /** The full-screen alarm for [kind] is open: it records when it is told to close. */
    private fun alarmScreenOpenFor(kind: Int) {
        AlarmActivityAccess.observeAcknowledgement(observer, kind) { acknowledged += kind }
    }

    private fun assertNothingSnoozedOrCalled() {
        assertTrue(AlarmSink.calls.isEmpty())
        assertTrue(acknowledged.isEmpty())
        for (type in AlertType.settingsEntries) {
            assertFalse(type.name, SnoozeManager.isSnoozed(type))
            assertFalse(type.name, WearAlertSync.isSuppressed(type.id))
        }
    }

    @Test
    fun noAlertGoingOnFindsNothingAndCallsNothing() {
        alarmScreenOpenFor(AlertType.HIGH.id)
        assertNull(OngoingAlert.find())
        assertNull(OngoingAlert.snoozeOngoing())
        assertNothingSnoozedOrCalled()
    }

    @Test
    fun findReturnsTheAlertGoingOn() {
        for (type in AlertType.settingsEntries.filter { it != AlertType.LOSS }) {
            resetAlertState()
            alertGoingOn(type)
            assertEquals(type.name, type.id, OngoingAlert.find())
        }
    }

    @Test
    fun presentedButTheEpisodeIsOverFindsNothing() {
        // a night alert nobody touched: the condition cleared, the notification and the mark stayed
        alarmScreenOpenFor(AlertType.LOW.id)
        alertGoingOn(AlertType.LOW)
        AlertStateTracker.resetState(AlertType.LOW)
        assertEquals(AlertType.LOW.id, presented)
        assertNull(OngoingAlert.find())
        assertNull(OngoingAlert.snoozeOngoing())
        assertNothingSnoozedOrCalled()
    }

    @Test
    fun presentedWithALiveEpisodeTheUserDismissedFindsNothing() {
        alertGoingOn(AlertType.HIGH)
        AlertStateTracker.onAlertDismissed(AlertType.HIGH)
        assertTrue(AlertStateTracker.isEpisodeActive(AlertType.HIGH))
        assertNull(OngoingAlert.find())
        assertNull(OngoingAlert.snoozeOngoing())
        assertNothingSnoozedOrCalled()
    }

    @Test
    fun theConditionClearedWhileTheSoundStillPlaysIsSnoozed() {
        // the next reading cleared the condition: their runtime resets the episode, the sound plays to its end
        alarmScreenOpenFor(AlertType.VERY_LOW.id)
        alertGoingOn(AlertType.VERY_LOW)
        AlertStateTracker.resetState(AlertType.VERY_LOW)
        soundOf(AlertType.VERY_LOW.id)
        assertFalse(AlertStateTracker.isEpisodeActive(AlertType.VERY_LOW))
        assertEquals(AlertType.VERY_LOW.id, OngoingAlert.find())
        assertEquals(AlertType.VERY_LOW.id, OngoingAlert.snoozeOngoing())
        assertTrue(SnoozeManager.isSnoozed(AlertType.VERY_LOW))
        assertTrue(AlarmSink.calls.contains("stop"))
        assertEquals(listOf(AlertType.VERY_LOW.id), acknowledged)
    }

    @Test
    fun presentedWithoutAnyEpisodeFindsNothingEvenWithASoundOn() {
        // a night LOW nobody touched left its mark; in the morning the "amount not entered" reminder rings
        alarmScreenOpenFor(AlertType.LOW.id)
        alertGoingOn(AlertType.LOW)
        AlertStateTracker.resetState(AlertType.LOW)
        soundOf(AlertType.AMOUNT.id)
        assertNull(OngoingAlert.find())
        assertNull(OngoingAlert.snoozeOngoing())
        assertNothingSnoozedOrCalled()
    }

    @Test
    fun anotherStandardKindsSoundDoesNotSnoozeAStaleMark() {
        // e.g. the HIGH melody tried in the settings while a stale LOW mark is up
        alertGoingOn(AlertType.LOW)
        AlertStateTracker.resetState(AlertType.LOW)
        soundOf(AlertType.HIGH.id)
        assertNull(OngoingAlert.find())
        assertNull(OngoingAlert.snoozeOngoing())
        assertNothingSnoozedOrCalled()
    }

    @Test
    fun aLiveEpisodeIsGoingOnWhateverSoundPlays() {
        alertGoingOn(AlertType.HIGH)
        soundOf(AlertType.AMOUNT.id)
        assertEquals(AlertType.HIGH.id, OngoingAlert.find())
    }

    @Test
    fun aStaleMarkWithoutAnEpisodeOrASoundFindsNothing() {
        presented = AlertType.VERY_LOW.id
        NativeSink.soundOn = false
        assertNull(OngoingAlert.find())
        assertNull(OngoingAlert.snoozeOngoing())
        assertNothingSnoozedOrCalled()
    }

    @Test
    fun lossIsGoingOnOnlyWhileItsSoundIsOn() {
        // upstream's loss of signal comes from the native side and never opens an episode
        presented = AlertType.LOSS.id
        assertFalse(AlertStateTracker.isEpisodeActive(AlertType.LOSS))
        assertNull(OngoingAlert.find())
        soundOf(AlertType.LOSS.id)
        assertEquals(AlertType.LOSS.id, OngoingAlert.find())
        assertEquals(AlertType.LOSS.id, OngoingAlert.snoozeOngoing())
        assertTrue(SnoozeManager.isSnoozed(AlertType.LOSS))
    }

    @Test
    fun aManualTestGoingOnFindsNothing() {
        AlertStateTracker.allowNextTriggerForTest(AlertType.LOW)
        assertTrue(AlertStateTracker.shouldTrigger(AlertType.LOW, AlertRepository.loadConfig(AlertType.LOW)))
        assertTrue(AlertStateTracker.isManualTestActive(AlertType.LOW))
        presented = AlertType.LOW.id
        soundOf(AlertType.LOW.id)
        assertNull(OngoingAlert.find())
    }

    @Test
    fun legacyOnlyKindsAreNeverPickedUp() {
        alertGoingOn(AlertType.AVAILABLE)
        assertNull(OngoingAlert.find())
        alertGoingOn(AlertType.AMOUNT)
        assertNull(OngoingAlert.find())
    }

    @Test
    fun snoozeIsUpstreamsSnoozeAndClosesTheAlarmScreen() {
        alertGoingOn(AlertType.LOW)
        alarmScreenOpenFor(AlertType.LOW.id)
        val before = System.currentTimeMillis()
        assertEquals(AlertType.LOW.id, OngoingAlert.snoozeOngoing())
        val after = System.currentTimeMillis()

        assertTrue(SnoozeManager.isSnoozed(AlertType.LOW))
        assertTrue(WearAlertSync.isSuppressed(AlertType.LOW.id)) // their /alertack record for the other device
        assertTrue(AlarmSink.calls.indexOf("retry:0") in 0 until AlarmSink.calls.indexOf("stop"))
        assertTrue(AlarmSink.calls.contains("notification"))
        assertEquals(listOf(AlertType.LOW.id), acknowledged)
        // the alert's own default snooze, as a plain "Snooze" on the phone
        val minutes = AlertActions.defaultSnoozeMinutes(AlertType.LOW.id, null)
        val until = SnoozeManager.getSnoozeState(AlertType.LOW)!!.snoozeUntilMillis
        assertTrue(until >= before + minutes * 60_000L - 1_000L)
        assertTrue(until <= after + minutes * 60_000L + 1_000L)
        assertFalse(SnoozeManager.isSnoozed(AlertType.HIGH))
    }

    @Test
    fun theAlarmScreenOfAnotherKindStaysOpen() {
        alertGoingOn(AlertType.VERY_LOW)
        alarmScreenOpenFor(AlertType.HIGH.id)
        assertEquals(AlertType.VERY_LOW.id, OngoingAlert.snoozeOngoing())
        assertTrue(acknowledged.isEmpty())
    }

    @Test
    fun aRepeatedPressKeepsOneSnooze() {
        alertGoingOn(AlertType.HIGH)
        assertEquals(AlertType.HIGH.id, OngoingAlert.snoozeOngoing())
        val until = SnoozeManager.getSnoozeState(AlertType.HIGH)!!.snoozeUntilMillis
        assertEquals(-1, presented) // their handle took the notification and the mark
        assertNull(OngoingAlert.snoozeOngoing())
        assertEquals(until, SnoozeManager.getSnoozeState(AlertType.HIGH)!!.snoozeUntilMillis)
    }

    @Test
    fun offTheMainThreadSnoozeRefusesAndCallsNothing() {
        alertGoingOn(AlertType.HIGH)
        alarmScreenOpenFor(AlertType.HIGH.id)
        var result: Boolean? = null
        val thread = Thread { result = OngoingAlert.snooze(AlertType.HIGH.id) }
        thread.start()
        thread.join(5_000)
        assertEquals(false, result)
        assertNothingSnoozedOrCalled()
    }

    /** [OngoingAlert.snoozeOngoingOnMain] on a listener-like thread while the test thread runs the main looper. */
    private fun onMainFromAnotherThread(waitMs: Long): Int? {
        var result: Int? = -100
        val thread = Thread { result = OngoingAlert.snoozeOngoingOnMain(waitMs) }
        thread.start()
        val deadline = System.currentTimeMillis() + 10_000
        while (thread.isAlive && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        thread.join(1_000)
        return result
    }

    @Test
    fun snoozeOngoingOnMainRunsOnTheMainThreadAndReturnsTheKind() {
        alertGoingOn(AlertType.PRE_LOW)
        alarmScreenOpenFor(AlertType.PRE_LOW.id)
        assertEquals(AlertType.PRE_LOW.id, onMainFromAnotherThread(10_000))
        assertTrue(SnoozeManager.isSnoozed(AlertType.PRE_LOW))
        assertEquals(listOf(AlertType.PRE_LOW.id), acknowledged)
    }

    @Test
    fun snoozeOngoingOnMainWithoutAnAlertIsNull() {
        assertNull(onMainFromAnotherThread(10_000))
        assertTrue(AlarmSink.calls.isEmpty())
    }

    @Test
    fun snoozeOngoingOnMainTooLateAnswersLateAndTheQueuedTaskDoesNothing() {
        alertGoingOn(AlertType.HIGH)
        alarmScreenOpenFor(AlertType.HIGH.id)
        var result: Int? = -100
        // the main looper is not run while the listener thread waits: the main thread is "busy"
        val thread = Thread { result = OngoingAlert.snoozeOngoingOnMain(50) }
        thread.start()
        thread.join(5_000)
        assertEquals(OngoingAlert.LATE, result)
        // the main thread gets to the queued task afterwards: it must not snooze
        shadowOf(Looper.getMainLooper()).idle()
        assertNothingSnoozedOrCalled()
        assertEquals(AlertType.HIGH.id, OngoingAlert.find())
    }

    @Test
    fun snoozeOngoingOnMainFromTheMainThreadRunsDirectly() {
        alertGoingOn(AlertType.HIGH)
        assertEquals(AlertType.HIGH.id, OngoingAlert.snoozeOngoingOnMain(10))
    }
}
