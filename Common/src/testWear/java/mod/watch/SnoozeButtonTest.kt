package mod.watch

import android.content.Context
import android.os.Looper
import android.os.VibrationAttributes
import android.os.Vibrator
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowToast
import org.robolectric.shadows.ShadowVibrator
import tk.glucodata.R
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.SnoozeManager
import tk.glucodata.mod.watch.ModWatchMessages
import tk.glucodata.mod.watch.ModWatchMessages.SnoozeDone
import tk.glucodata.mod.watch.ModWearListener
import tk.glucodata.mod.watch.SnoozeButton
import tk.glucodata.mod.watch.SnoozeButton.Event
import tk.glucodata.mod.watch.SnoozeButton.Status
import tk.glucodata.mod.watch.SnoozeButtonActivity
import tk.glucodata.ui.AlarmActivityAccess

/**
 * JugglucoNGmod, watch block: the "Snooze alarm" button on the watch - local snooze, buzz, the message to the phone
 * and the phone's answer. Upstream's alert code runs for real; the Notify/Natives sinks and the test application are
 * the shared ones of [OngoingAlertTest] (src/test, compiled into this task too). Nothing goes to the real Data Layer.
 * Made-up times only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OngoingAlertTest.TestApplication::class,
    shadows = [OngoingAlertTest.AlarmSink::class, OngoingAlertTest.NativeSink::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SnoozeButtonTest {

    private val pressedAt = 1_800_000_000_000L
    private val sent = mutableListOf<Long>()
    private val fakeSend: (Context, Long, () -> Unit) -> Unit = { _, at, _ -> sent += at }
    private val owner = Any()
    private val closed = mutableListOf<Int>()
    private lateinit var context: Context
    private lateinit var realSend: (Context, Long, () -> Unit) -> Unit

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        OngoingAlertTest.resetAlertState()
        SnoozeButton.resetForTest()
        ShadowVibrator.reset()
        vibrator().setHasVibrator(true)
        realSend = SnoozeButtonActivity.send
        SnoozeButtonActivity.send = fakeSend
    }

    @After
    fun tearDown() {
        SnoozeButtonActivity.send = realSend
        AlarmActivityAccess.removeObserver(owner)
        OngoingAlertTest.resetAlertState()
    }

    private fun vibrator(): ShadowVibrator = shadowOf(context.getSystemService(Vibrator::class.java))

    private fun buzzed() = vibrator().milliseconds == SnoozeButton.BUZZ_MS

    private fun lastUsage(): Int = (vibrator().vibrationAttributesFromLastVibration as VibrationAttributes).usage

    @Test
    fun noAlertNoBuzzButThePhoneIsAskedAnyway() {
        assertNull(SnoozeButton.press(context, pressedAt, 10_000L, fakeSend))
        assertFalse(buzzed())
        assertEquals(listOf(pressedAt), sent)
        assertTrue(OngoingAlertTest.AlarmSink.calls.isEmpty())
    }

    @Test
    fun aStaleAlertOnTheWatchIsNotSnoozedNorBuzzed() {
        // shown at night, the condition is over: the mark stayed, the episode did not
        OngoingAlertTest.alertGoingOn(AlertType.LOW)
        AlertStateTracker.resetState(AlertType.LOW)
        assertNull(SnoozeButton.press(context, pressedAt, 10_000L, fakeSend))
        assertFalse(buzzed())
        assertFalse(SnoozeManager.isSnoozed(AlertType.LOW))
        assertEquals(listOf(pressedAt), sent)
    }

    @Test
    fun anAlertGoingOnIsSnoozedClosedBuzzedOnceAndThePhoneIsAsked() {
        OngoingAlertTest.alertGoingOn(AlertType.HIGH)
        AlarmActivityAccess.observeAcknowledgement(owner, AlertType.HIGH.id) { closed += AlertType.HIGH.id }
        assertEquals(AlertType.HIGH.id, SnoozeButton.press(context, pressedAt, 10_000L, fakeSend))
        assertTrue(SnoozeManager.isSnoozed(AlertType.HIGH))
        assertEquals(listOf(AlertType.HIGH.id), closed)
        assertTrue(buzzed())
        assertEquals(listOf(pressedAt), sent)
    }

    @Test
    fun thePressBuzzIsHardwareFeedbackAndThePhoneAnswerBuzzIsANotification() {
        OngoingAlertTest.alertGoingOn(AlertType.HIGH)
        SnoozeButton.press(context, pressedAt, 10_000L, fakeSend)
        assertEquals(VibrationAttributes.USAGE_HARDWARE_FEEDBACK, lastUsage())
        ShadowVibrator.reset()
        vibrator().setHasVibrator(true)
        assertTrue(SnoozeButton.onPhoneAnswer(context, SnoozeDone(AlertType.HIGH.id, pressedAt), 60_000L))
        assertEquals(VibrationAttributes.USAGE_NOTIFICATION, lastUsage())
    }

    @Test
    fun aFailingSendDoesNotBreakThePress() {
        OngoingAlertTest.alertGoingOn(AlertType.LOW)
        val kind = SnoozeButton.press(context, pressedAt, 10_000L) { _, _, _ -> throw IllegalStateException("made-up") }
        assertEquals(AlertType.LOW.id, kind)
        assertTrue(SnoozeManager.isSnoozed(AlertType.LOW))
    }

    @Test
    fun thePhonesSnoozedBuzzesOnlyWhenTheWatchDidNotJustBuzz() {
        OngoingAlertTest.alertGoingOn(AlertType.HIGH)
        SnoozeButton.press(context, pressedAt, 10_000L, fakeSend)
        ShadowVibrator.reset()
        vibrator().setHasVibrator(true)
        assertFalse(SnoozeButton.onPhoneAnswer(context, SnoozeDone(AlertType.HIGH.id, pressedAt), 12_000L))
        assertFalse(buzzed())
        assertTrue(SnoozeButton.onPhoneAnswer(context, SnoozeDone(AlertType.HIGH.id, pressedAt), 16_000L))
        assertTrue(buzzed())
    }

    @Test
    fun thePhonesSnoozedBuzzesWhenTheWatchHadNoAlert() {
        SnoozeButton.press(context, pressedAt, 10_000L, fakeSend)
        assertTrue(SnoozeButton.onPhoneAnswer(context, SnoozeDone(AlertType.LOW.id, pressedAt), 10_500L))
        assertTrue(buzzed())
    }

    @Test
    fun thePhonesNoAlertNeverBuzzes() {
        assertFalse(SnoozeButton.onPhoneAnswer(context, SnoozeDone(null, pressedAt), 60_000L))
        assertFalse(buzzed())
    }

    @Test
    fun thePhonesLateNeverBuzzes() {
        assertFalse(SnoozeButton.onPhoneAnswer(context, SnoozeDone(null, pressedAt, late = true), 60_000L))
        assertFalse(buzzed())
        val bytes = ModWatchMessages.encodeSnoozeDone(SnoozeDone(null, pressedAt, late = true))
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_SNOOZE_DONE, bytes))
        assertFalse(buzzed())
    }

    @Test
    fun buzzDedupWindow() {
        assertTrue(SnoozeButton.shouldBuzzForPhone(10_000L, 0L))
        assertFalse(SnoozeButton.shouldBuzzForPhone(10_000L, 10_000L))
        assertFalse(SnoozeButton.shouldBuzzForPhone(14_999L, 10_000L))
        assertTrue(SnoozeButton.shouldBuzzForPhone(15_000L, 10_000L))
        assertTrue(SnoozeButton.shouldBuzzForPhone(5_000L, 10_000L)) // elapsed clock restarted (reboot)
    }

    @Test
    fun theListenerTakesOnlyAValidSnoozeDone() {
        val valid = ModWatchMessages.encodeSnoozeDone(SnoozeDone(AlertType.HIGH.id, pressedAt))
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_SNOOZE_DONE, valid))
        assertFalse(ModWearListener.handle(context, ModWatchMessages.PATH_SNOOZE_DONE, byteArrayOf(1, 2)))
        assertFalse(ModWearListener.handle(context, ModWatchMessages.PATH_SNOOZE_DONE, null))
        val legacyKind = ModWatchMessages.encodeSnoozeDone(SnoozeDone(AlertType.AVAILABLE.id, pressedAt))
        assertFalse(ModWearListener.handle(context, ModWatchMessages.PATH_SNOOZE_DONE, legacyKind))
        assertFalse(ModWearListener.handle(context, ModWatchMessages.PATH_TICK, byteArrayOf(1, 2))) // ticks: PhoneBatteryStoreTest
        assertFalse(ModWearListener.handle(context, "/mod/unknown", valid))
        assertFalse(ModWearListener.handle(context, null, valid))
    }

    @Test
    fun theButtonActivityFinishesInOnCreateAndDoesNothingWithoutAnAlert() {
        val activity = Robolectric.buildActivity(SnoozeButtonActivity::class.java).create().get()
        assertTrue(activity.isFinishing)
        assertFalse(buzzed())
        assertTrue(OngoingAlertTest.AlarmSink.calls.isEmpty())
        assertEquals(1, sent.size)
    }

    @Test
    fun theButtonActivitySnoozesAnAlertGoingOnAndAsksThePhone() {
        OngoingAlertTest.alertGoingOn(AlertType.VERY_HIGH)
        val activity = Robolectric.buildActivity(SnoozeButtonActivity::class.java).create().get()
        assertTrue(activity.isFinishing)
        assertTrue(SnoozeManager.isSnoozed(AlertType.VERY_HIGH))
        assertTrue(buzzed())
        assertEquals(1, sent.size)
        assertEquals(listOf(R.string.mod_watch_snooze_done), toasts())
    }

    // --- the status text: the pure rule ---

    private fun status(event: Event, at: Long, lastShown: Long = 0L, lastWatchSnooze: Long = 0L) =
        SnoozeButton.statusFor(event, at, lastShown, lastWatchSnooze) // lastWatchSnooze: the last "Alarm snoozed" shown

    @Test
    fun statusTheWatchsOwnSnoozeAlwaysShowsAndNoAlertOnTheWatchShowsNothing() {
        assertEquals(Status.SNOOZED, status(Event.WATCH_SNOOZED, 10_000L))
        assertEquals(Status.SNOOZED, status(Event.WATCH_SNOOZED, 12_000L, lastShown = 10_000L, lastWatchSnooze = 10_000L))
        assertNull(status(Event.WATCH_NO_ALERT, 10_000L))
    }

    @Test
    fun statusThePhonesSnoozedShowsOnlyOutsideTheDedupWindow() {
        assertEquals(Status.SNOOZED, status(Event.PHONE_SNOOZED, 10_500L)) // the watch had no alert, nothing shown
        assertNull(status(Event.PHONE_SNOOZED, 10_000L, lastShown = 10_000L))
        assertNull(status(Event.PHONE_SNOOZED, 14_999L, lastShown = 10_000L))
        assertEquals(Status.SNOOZED, status(Event.PHONE_SNOOZED, 15_000L, lastShown = 10_000L))
        assertEquals(Status.SNOOZED, status(Event.PHONE_SNOOZED, 5_000L, lastShown = 10_000L)) // clock restarted
    }

    @Test
    fun statusThePhonesNoAlertShowsUnlessTheWatchJustSnoozedItsOwn() {
        assertEquals(Status.NO_ALARM, status(Event.PHONE_NO_ALERT, 10_500L))
        assertNull(status(Event.PHONE_NO_ALERT, 11_000L, lastShown = 10_000L, lastWatchSnooze = 10_000L))
        // a "no phone" text of the same press does not hide the phone's answer of a later press
        assertEquals(Status.NO_ALARM, status(Event.PHONE_NO_ALERT, 11_000L, lastShown = 10_000L))
        assertNull(status(Event.PHONE_NO_ALERT, 34_999L, lastShown = 10_000L, lastWatchSnooze = 10_000L))
        assertEquals(Status.NO_ALARM, status(Event.PHONE_NO_ALERT, 35_000L, lastShown = 10_000L, lastWatchSnooze = 10_000L))
    }

    @Test
    fun statusNoContradictionAfterAnAlarmSnoozedEvenWhenThePhoneIsSlowOrThePressIsDouble() {
        // critic of part 6, C6-1: the phone's answer may come 10-20 s after the press that snoozed the watch
        assertNull(status(Event.PHONE_NO_ALERT, 22_000L, lastShown = 10_000L, lastWatchSnooze = 10_000L))
        assertNull(status(Event.PHONE_UNREACHABLE, 10_000L, lastShown = 10_000L, lastWatchSnooze = 10_000L))
        // a double press: the phone's "snoozed" answer of the first one was shown, the second one gets "no alert"
        assertNull(status(Event.PHONE_NO_ALERT, 11_500L, lastShown = 10_500L, lastWatchSnooze = 10_500L))
        assertEquals(SnoozeButton.TEXT_QUIET_MS, 25_000L)
    }

    @Test
    fun statusThePhonesLateNeverShows() {
        assertNull(status(Event.PHONE_LATE, 10_500L))
        assertNull(status(Event.PHONE_LATE, 60_000L, lastShown = 10_000L, lastWatchSnooze = 10_000L))
    }

    @Test
    fun statusNoPhoneShowsUnlessTheWatchSnoozedItsOwnOnThatPress() {
        assertEquals(Status.PHONE_NOT_CONNECTED, status(Event.PHONE_UNREACHABLE, 10_000L))
        assertNull(status(Event.PHONE_UNREACHABLE, 10_000L, lastShown = 10_000L, lastWatchSnooze = 10_000L))
        assertEquals(Status.PHONE_NOT_CONNECTED,
            status(Event.PHONE_UNREACHABLE, 35_000L, lastShown = 10_000L, lastWatchSnooze = 10_000L))
    }

    // --- the status text: what a press really shows ---

    private var toastsSeen = 0

    /**
     * The texts shown since the last call, as string ids: their number and the latest one (each step here shows at
     * most one, so a second text shows up as a longer list).
     */
    private fun toasts(): List<Int> {
        shadowOf(Looper.getMainLooper()).idle()
        val count = ShadowToast.shownToastCount()
        val fresh = count - toastsSeen
        toastsSeen = count
        if (fresh == 0) return emptyList()
        val latest = ShadowToast.getTextOfLatestToast()?.toString()
        val id = listOf(R.string.mod_watch_snooze_done, R.string.mod_watch_snooze_no_alarm, R.string.mod_watch_snooze_no_phone)
            .firstOrNull { context.getString(it) == latest } ?: -1
        return List(fresh) { id }
    }

    @Test
    fun aPressThatSnoozesTheWatchShowsOneTextEvenWhenThePhoneAlsoAnswers() {
        OngoingAlertTest.alertGoingOn(AlertType.HIGH)
        SnoozeButton.press(context, pressedAt, 10_000L, fakeSend)
        SnoozeButton.onPhoneAnswer(context, SnoozeDone(AlertType.HIGH.id, pressedAt), 11_000L)
        SnoozeButton.onPhoneAnswer(context, SnoozeDone(null, pressedAt), 11_500L)
        assertEquals(listOf(R.string.mod_watch_snooze_done), toasts())
    }

    @Test
    fun aPressWithoutAnAlertShowsThePhonesAnswer() {
        SnoozeButton.press(context, pressedAt, 10_000L, fakeSend)
        assertEquals(emptyList<Int>(), toasts())
        SnoozeButton.onPhoneAnswer(context, SnoozeDone(AlertType.LOW.id, pressedAt), 10_500L)
        assertEquals(listOf(R.string.mod_watch_snooze_done), toasts())
        SnoozeButton.onPhoneAnswer(context, SnoozeDone(AlertType.LOW.id, pressedAt), 11_000L) // a repeat in the window
        assertEquals(emptyList<Int>(), toasts())
    }

    @Test
    fun noAlertAnywhereShowsNoAlarmAndLateShowsNothing() {
        SnoozeButton.press(context, pressedAt, 10_000L, fakeSend)
        SnoozeButton.onPhoneAnswer(context, SnoozeDone(null, pressedAt, late = true), 10_500L)
        assertEquals(emptyList<Int>(), toasts())
        SnoozeButton.onPhoneAnswer(context, SnoozeDone(null, pressedAt), 11_000L)
        assertEquals(listOf(R.string.mod_watch_snooze_no_alarm), toasts())
    }

    @Test
    fun noPhoneShowsPhoneNotConnectedOnlyWhenTheWatchHadNothingToSnooze() {
        val noPhone: (Context, Long, () -> Unit) -> Unit = { _, at, onNoPhone -> sent += at; onNoPhone() }
        SnoozeButton.press(context, pressedAt, 10_000L, noPhone)
        assertEquals(listOf(R.string.mod_watch_snooze_no_phone), toasts())
        OngoingAlertTest.alertGoingOn(AlertType.HIGH)
        SnoozeButton.press(context, pressedAt + 60_000L, 70_000L, noPhone)
        assertEquals(listOf(R.string.mod_watch_snooze_done), toasts())
    }

    @Test
    fun aSendThatThrowsCountsAsNoPhone() {
        SnoozeButton.press(context, pressedAt, 10_000L) { _, _, _ -> throw IllegalStateException("made-up") }
        assertEquals(listOf(R.string.mod_watch_snooze_no_phone), toasts())
    }
}
