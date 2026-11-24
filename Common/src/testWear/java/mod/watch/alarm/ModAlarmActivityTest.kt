package mod.watch.alarm

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import mod.watch.OngoingAlertTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.Notify
import tk.glucodata.R
import tk.glucodata.WearAlertAcknowledgement
import tk.glucodata.WearAlertSync
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.alerts.AlertType
import tk.glucodata.alerts.SnoozeManager
import tk.glucodata.mod.watch.OngoingAlert
import tk.glucodata.mod.watch.alarm.AlarmScreenLayout
import tk.glucodata.mod.watch.alarm.AlarmScreens
import tk.glucodata.mod.watch.alarm.ModAlarmActivity
import tk.glucodata.mod.watch.alarm.ModAlarmScreen
import tk.glucodata.receivers.AlarmActionReceiver
import tk.glucodata.receivers.AlarmLaunchReceiver
import tk.glucodata.ui.AlarmActivity
import tk.glucodata.ui.AlarmActivityAccess
import tk.glucodata.ui.WearAlarmActivityHost

/**
 * JugglucoNGmod, watch block (part 8): our alarm screen against upstream's real registry, acknowledgement path,
 * action receiver, snooze store and tracker - only Notify's sound and notification side and the natives are the
 * recording sinks of [OngoingAlertTest] (src/test). The registry after the process start, the claim taken back, the
 * buttons sending their actions, the phone's acknowledgement and our hardware button closing the screen, and the
 * hand-over to upstream's screen when ours fails. Made-up values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = OngoingAlertTest.TestApplication::class,
    shadows = [OngoingAlertTest.AlarmSink::class, OngoingAlertTest.NativeSink::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class ModAlarmActivityTest {

    private lateinit var app: Context
    private val controllers = mutableListOf<ActivityController<ModAlarmActivity>>()
    private val theirControllers = mutableListOf<ActivityController<AlarmActivity>>()

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowLog.stream = System.out
        // a locked watch, as upstream's test of their screen (Robolectric's keyguard needs it for a null callback)
        shadowOf(app.getSystemService(android.app.KeyguardManager::class.java)).setKeyguardLocked(true)
        OngoingAlertTest.resetAlertState()
        ModAlarmActivity.contentHook = null
        ModAlarmScreen.resetForTest()
        AlarmScreens.resetForTest()
        Notify.unitlabel = "mmol/L"
        alarmUiVisible = false
    }

    @After
    fun tearDown() {
        ModAlarmActivity.contentHook = null
        controllers.forEach { runCatching { it.pause().stop().destroy() } }
        theirControllers.forEach { runCatching { it.pause().stop().destroy() } }
        AlarmScreens.resetForTest()
        OngoingAlertTest.resetAlertState()
    }

    /** Upstream's watch alarm screen, created only (their Compose content runs as in their own test). */
    private fun createTheirs(intent: Intent = alarmIntent()): ActivityController<AlarmActivity> =
        Robolectric.buildActivity(AlarmActivity::class.java, intent).create().also { theirControllers += it }

    // ------------------------------------------------------------------ helpers

    private fun alarmIntent(kind: AlertType = AlertType.HIGH, presentedAt: Long = System.currentTimeMillis() - 1_000L) =
        Intent()
            .putExtra(AlarmActivity.EXTRA_ALERT_TYPE_ID, kind.id)
            .putExtra(AlarmActivity.EXTRA_GLUCOSE_VAL, "14,2")
            .putExtra(AlarmActivity.EXTRA_ALARM_TYPE, "ALARM")
            .putExtra(AlarmActivity.EXTRA_RATE, 2f)
            .putExtra(WearAlertSync.EXTRA_PRESENTED_AT_MS, presentedAt)

    private fun open(intent: Intent = alarmIntent()): ActivityController<ModAlarmActivity> =
        Robolectric.buildActivity(ModAlarmActivity::class.java, intent).create().start().resume().also { controllers += it }

    private fun screen(activity: ModAlarmActivity): AlarmScreenLayout =
        activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as AlarmScreenLayout

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun receive(action: WearAlertAcknowledgement) {
        WearAlertSync.onMessage(action.encode())
        idle()
    }

    /** Upstream's private "alarm window is showing" flag (their missed-window notification reads it). */
    private var alarmUiVisible: Boolean
        get() = visibleField.getBoolean(null)
        set(value) = visibleField.setBoolean(null, value)

    private val visibleField = Notify::class.java.getDeclaredField("alarmUiVisible").apply { isAccessible = true }

    private fun lastActionSent(): Intent? = shadowOf(app as android.app.Application).broadcastIntents
        .lastOrNull { it.component?.className == AlarmActionReceiver::class.java.name }

    private fun assertOpensUpstreamWithTheSameExtras(started: Intent?) {
        assertNotNull("upstream's screen started", started)
        started!!
        assertEquals(AlarmActivity::class.java.name, started.component?.className)
        assertEquals(AlertType.HIGH.id, started.getIntExtra(AlarmActivity.EXTRA_ALERT_TYPE_ID, -1))
        assertEquals("14,2", started.getStringExtra(AlarmActivity.EXTRA_GLUCOSE_VAL))
        assertEquals(2f, started.getFloatExtra(AlarmActivity.EXTRA_RATE, Float.NaN))
        assertTrue(started.getLongExtra(WearAlertSync.EXTRA_PRESENTED_AT_MS, 0L) > 0L)
        val flags = AlarmLaunchReceiver.launchFlags(true)
        assertEquals(flags, started.flags and flags)
    }

    // ------------------------------------------------------------------ which screen opens

    @Test
    fun afterTheProcessStartTheRegistryGivesOurScreen() {
        // the provider runs before Application.onCreate; their Applic.onCreate registers theirs in the same message
        ModAlarmScreen.claimAfterStart(app, startWatch = false)
        AlarmActivityAccess.register(WearAlarmActivityHost)
        assertEquals(AlarmActivity::class.java.name, AlarmActivityAccess.newIntent(app)?.component?.className)
        idle()
        assertEquals(ModAlarmActivity::class.java.name, AlarmActivityAccess.newIntent(app)?.component?.className)
        assertTrue(ModAlarmScreen.isOurs(app))
        // a second claim changes nothing
        assertFalse(ModAlarmScreen.claim(app))
    }

    @Test
    fun aLateRegistrationOfTheirsIsTakenBackOnTheNextRefresh() {
        AlarmActivityAccess.register(WearAlarmActivityHost)
        val signals = MutableStateFlow(0)
        val job = ModAlarmScreen.reclaimOn(app, signals, Dispatchers.Unconfined)
        try {
            assertTrue(ModAlarmScreen.isOurs(app))
            // their initproc ran later (native start failed in Applic.onCreate): registerBridges again
            AlarmActivityAccess.register(WearAlarmActivityHost)
            assertFalse(ModAlarmScreen.isOurs(app))
            signals.value = 1
            assertTrue(ModAlarmScreen.isOurs(app))
        } finally {
            job?.cancel()
        }
    }

    // ------------------------------------------------------------------ shown as theirs

    @Test
    fun theScreenShowsTheAlertAndTellsUpstreamItIsVisible() {
        val activity = open().get()
        assertFalse(activity.isFinishing)
        val screen = screen(activity)
        assertEquals(app.getString(R.string.alert_high), screen.ui.label)
        assertEquals(app.getString(R.string.snooze), screen.snoozeButton.label)
        assertEquals(app.getString(R.string.notification_dismiss_action_dismiss), screen.dismissButton.label)
        assertTrue(alarmUiVisible)
    }

    @Test
    fun aMessageAlertWithoutAKindShowsItsMessage() {
        val intent = alarmIntent().putExtra(AlarmActivity.EXTRA_ALERT_TYPE_ID, -1)
            .putExtra(AlarmActivity.EXTRA_ALARM_MESSAGE, "Dawn phenomenon 9.8")
            .putExtra(Notify.EXTRA_CUSTOM_ALERT_ID, "custom-1")
        val activity = open(intent).get()
        assertEquals("Dawn phenomenon 9.8", screen(activity).ui.label)
        // a custom alert is not closed by a standard kind's acknowledgement (theirs: no observer for it)
        AlarmActivityAccess.acknowledge(-1)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun snoozeSendsTheirSnoozeWithTheAlertsMinutesAndCloses() {
        OngoingAlertTest.alertGoingOn(AlertType.HIGH)
        val activity = open().get()
        screen(activity).snoozeButton.performClick()
        assertTrue(activity.isFinishing)
        val sent = lastActionSent()
        assertNotNull(sent)
        assertEquals(AlarmActionReceiver.ACTION_SNOOZE, sent!!.action)
        assertEquals(AlertType.HIGH.id, sent.getIntExtra(AlarmActionReceiver.EXTRA_ALERT_TYPE_ID, -1))
        assertEquals(AlertRepository.loadConfig(AlertType.HIGH).defaultSnoozeMinutes,
            sent.getIntExtra(AlarmActionReceiver.EXTRA_SNOOZE_MINUTES, -1))
        assertNull(sent.getStringExtra(Notify.EXTRA_CUSTOM_ALERT_ID))
        // their receiver does the snooze (delivered here unless Robolectric already did)
        idle()
        if (!SnoozeManager.isSnoozed(AlertType.HIGH)) AlarmActionReceiver().onReceive(app, sent)
        assertTrue(SnoozeManager.isSnoozed(AlertType.HIGH))
        assertTrue("stop" in OngoingAlertTest.AlarmSink.calls)
        controllers.last().pause().stop()
        assertFalse(alarmUiVisible)
    }

    @Test
    fun dismissSendsTheirDismissAndCloses() {
        OngoingAlertTest.alertGoingOn(AlertType.HIGH)
        val activity = open().get()
        screen(activity).dismissButton.performClick()
        assertTrue(activity.isFinishing)
        val sent = lastActionSent()
        assertNotNull(sent)
        assertEquals(AlarmActionReceiver.ACTION_DISMISS, sent!!.action)
        assertEquals(AlertType.HIGH.id, sent.getIntExtra(AlarmActionReceiver.EXTRA_ALERT_TYPE_ID, -1))
        assertFalse(sent.hasExtra(AlarmActionReceiver.EXTRA_SNOOZE_MINUTES))
        idle()
        if (!AlertStateTracker.isDismissed(AlertType.HIGH)) AlarmActionReceiver().onReceive(app, sent)
        assertTrue(AlertStateTracker.isDismissed(AlertType.HIGH))
        assertFalse(SnoozeManager.isSnoozed(AlertType.HIGH))
        assertTrue("stop" in OngoingAlertTest.AlarmSink.calls)
    }

    // ------------------------------------------------------------------ closed from elsewhere

    @Test
    fun thePhonesAcknowledgementClosesTheScreen() {
        val activity = open().get()
        val now = System.currentTimeMillis()
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, false, now, now + 300_000))
        assertTrue(activity.isFinishing)
    }

    @Test
    fun anAcknowledgementWhileStoppedClosesTheScreenOnReturn() {
        // as upstream's test returningToTheStoppedAlarmActivityClosesAnAcknowledgedScreen
        val controller = open().pause().stop()
        val now = System.currentTimeMillis()
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, false, now, now + 300_000))
        assertFalse(controller.get().isFinishing)
        controller.restart().start()
        assertTrue(controller.get().isFinishing)
    }

    @Test
    fun anAlreadyAcknowledgedAlarmClosesAtOnce() {
        val now = System.currentTimeMillis()
        receive(WearAlertAcknowledgement(AlertType.HIGH.id, false, now, now + 300_000))
        val activity = Robolectric.buildActivity(ModAlarmActivity::class.java, alarmIntent()).create().get()
        assertTrue(activity.isFinishing)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun ourWatchButtonSnoozesAndClosesTheScreen() {
        OngoingAlertTest.alertGoingOn(AlertType.HIGH)
        val activity = open().get()
        assertEquals(AlertType.HIGH.id, OngoingAlert.snoozeOngoing())
        assertTrue(activity.isFinishing)
        assertTrue(SnoozeManager.isSnoozed(AlertType.HIGH))
    }

    // ------------------------------------------------------------------ hand-over to upstream's screen

    @Test
    fun aFailureWhileBuildingOpensUpstreamsScreenWithTheSameExtras() {
        ModAlarmActivity.contentHook = { throw IllegalStateException("test") }
        val activity = Robolectric.buildActivity(ModAlarmActivity::class.java, alarmIntent()).create().get()
        assertTrue(activity.isFinishing)
        assertOpensUpstreamWithTheSameExtras(shadowOf(activity).nextStartedActivity)
        // the failed screen watches nothing
        AlarmActivityAccess.acknowledge(AlertType.HIGH.id)
    }

    @Test
    fun aFailureOnANewIntentOpensUpstreamsScreen() {
        val controller = open()
        ModAlarmActivity.contentHook = { throw IllegalStateException("test") }
        controller.newIntent(alarmIntent())
        assertTrue(controller.get().isFinishing)
        assertOpensUpstreamWithTheSameExtras(shadowOf(controller.get()).nextStartedActivity)
    }

    @Test
    fun aFailureOfTheShownScreenHandsOverAndLeavesTheVisibleFlagToTheirs() {
        val controller = open()
        val activity = controller.get()
        assertTrue(alarmUiVisible)
        activity.fallBack("view", IllegalStateException("test"))
        assertTrue(activity.isFinishing)
        assertOpensUpstreamWithTheSameExtras(shadowOf(activity).nextStartedActivity)
        // their screen sets the flag in its onStart; ours stopping after it must not clear it
        controller.pause().stop()
        assertTrue(alarmUiVisible)
        // a second failure report does nothing more
        activity.fallBack("view", IllegalStateException("again"))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    // ------------------------------------------------------------------ never two screens (critic 8)

    @Test
    fun whileTheirScreenIsOpenNewAlarmsGoToIt() {
        assertTrue(AlarmScreens.register(app))
        AlarmActivityAccess.register(ModAlarmScreen)
        assertEquals(ModAlarmActivity::class.java.name, AlarmActivityAccess.newIntent(app)?.component?.className)
        // their screen is open (our hand-over, a late registration of theirs, or their old full-screen notification)
        val theirs = createTheirs().start().resume()
        assertTrue(AlarmScreens.upstreamOpen())
        assertEquals(AlarmActivity::class.java.name, AlarmActivityAccess.newIntent(app)?.component?.className)
        // our entry is still the one registered: no claim needed
        assertTrue(ModAlarmScreen.isOurs(app))
        assertFalse(ModAlarmScreen.claim(app))
        // stopped (the screen went dark) it is still open: the next alarm still goes to it
        theirs.pause().stop()
        assertEquals(AlarmActivity::class.java.name, AlarmActivityAccess.newIntent(app)?.component?.className)
        theirs.destroy()
        assertFalse(AlarmScreens.upstreamOpen())
        assertEquals(ModAlarmActivity::class.java.name, AlarmActivityAccess.newIntent(app)?.component?.className)
    }

    @Test
    fun ourScreenOverTheirsClosesTheirsAndTheFlagStaysSet() {
        AlarmScreens.register(app)
        val theirs = createTheirs().start().resume()
        assertTrue(alarmUiVisible)
        // an old notification of ours opens ours over theirs: the system pauses theirs, ours comes up
        theirs.pause()
        val ours = open()
        assertTrue(theirs.get().isFinishing)
        assertFalse(ours.get().isFinishing)
        // their onStop clears the flag while ours is on screen: set again at once
        theirs.stop()
        assertTrue(alarmUiVisible)
        theirs.destroy()
        assertFalse(AlarmScreens.upstreamOpen())
        assertTrue(alarmUiVisible)
        // ours closing clears it as usual
        ours.pause().stop()
        assertFalse(alarmUiVisible)
    }

    @Test
    fun theirScreenOpeningClosesOursAndTheFlagStaysSet() {
        AlarmScreens.register(app)
        val ours = open()
        assertTrue(alarmUiVisible)
        // their old full-screen notification opens theirs over ours
        ours.pause()
        val theirs = createTheirs()
        assertTrue(ours.get().isFinishing)
        theirs.start().resume()
        ours.stop()
        assertTrue(alarmUiVisible)
        theirs.pause().stop()
        assertFalse(alarmUiVisible)
    }

    @Test
    fun aHandOverWhoseScreenNeverCameUpClearsTheFlagOnStop() {
        // critic 8, finding 3: the hand-over came after the flag was set, their window never showed - the flag must
        // not stay set, or their missed-window notification is held back
        AlarmScreens.register(app)
        val controller = open()
        assertTrue(alarmUiVisible)
        controller.get().fallBack("start", IllegalStateException("test"))
        assertOpensUpstreamWithTheSameExtras(shadowOf(controller.get()).nextStartedActivity)
        controller.pause().stop()
        assertFalse(alarmUiVisible)
    }

    @Test
    fun aHandOverToTheirScreenThatCameUpKeepsTheFlag() {
        AlarmScreens.register(app)
        val controller = open()
        controller.get().fallBack("view", IllegalStateException("test"))
        val started = shadowOf(controller.get()).nextStartedActivity
        assertOpensUpstreamWithTheSameExtras(started)
        controller.pause()
        createTheirs(started).start().resume()
        controller.stop()
        assertTrue(alarmUiVisible)
    }
}
