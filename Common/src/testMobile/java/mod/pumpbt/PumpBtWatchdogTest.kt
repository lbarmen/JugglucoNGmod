package mod.pumpbt

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
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
import tk.glucodata.mod.carelink.CareLinkClock
import tk.glucodata.mod.carelink.CareLinkNotifier
import tk.glucodata.mod.carelink.PumpSourceSwitch
import tk.glucodata.mod.pumpbt.ModPumpStarter
import tk.glucodata.mod.pumpbt.PumpBtAlert
import tk.glucodata.mod.pumpbt.PumpBtAlertKind
import tk.glucodata.mod.pumpbt.PumpBtNotifier
import tk.glucodata.mod.pumpbt.PumpBtSource
import tk.glucodata.mod.pumpbt.PumpBtUpdateReceiver
import tk.glucodata.mod.pumpbt.PumpBtWatchdog
import tk.glucodata.mod.pumpbt.PumpBtWatchdogActions
import tk.glucodata.mod.pumpbt.PumpBtWatchdogReceiver
import tk.glucodata.mod.pumpbt.PumpBtWatchdogRules
import tk.glucodata.mod.pumpbt.RealStarterSteps
import tk.glucodata.mod.pumpbt.StarterSteps
import tk.glucodata.mod.pumpbt.WatchdogAction
import tk.glucodata.mod.pumpbt.WatchdogInput
import tk.glucodata.mod.pumpbt.ble.PumpLinkHealth

private const val MIN = 60_000L
private const val T0 = 1_800_000_000_000L

/**
 * The watchdog's rules (series part 2, part B2c): every branch by time - the source off, no link, the link connected
 * with the 7 and 15 min counted from the later of the last read and the round that first saw the connection, clock
 * set back, the card reminder a day later; and the pump's own rhythm (a session of ~7 min every ~8 min, a gap of 55
 * min) never makes the watchdog drop a link. Pure: no Android.
 */
class PumpBtWatchdogRulesTest {

    private fun health(running: Boolean = true, advertising: Boolean = false, connected: Boolean = true, lastCycle: Long? = null) =
        PumpLinkHealth(running, advertising, connected, lastCycle, lastCycle)

    private fun input(
        now: Long = T0, on: Boolean = true, health: PumpLinkHealth? = health(), seen: Long? = null,
        card: Boolean = false, reminded: Long? = null,
    ) = WatchdogInput(now, on, health, seen, card, reminded)

    @Test
    fun sourceOffDoesNothingAndTheAlarmGoes() {
        val d = PumpBtWatchdogRules.decide(input(on = false, seen = T0 - MIN, card = true, reminded = T0 - 48 * 60 * MIN))
        assertEquals(WatchdogAction.NONE, d.action)
        assertFalse(d.keepArmed)
        assertFalse(d.checkNoData)
        assertFalse(d.remind)
        assertNull(d.connectedSeenAtMs)
    }

    @Test
    fun noLinkObjectNotRunningOrNotConnectedMakesSureOfTheAdvertisement() {
        for (h in listOf(null, health(running = false, connected = false), health(connected = false), health(connected = false, advertising = true))) {
            val d = PumpBtWatchdogRules.decide(input(health = h, seen = T0 - 3 * MIN))
            assertEquals(WatchdogAction.ENSURE_ADVERTISING, d.action)
            assertNull(d.connectedSeenAtMs) // the connection is over: the next one is counted anew
            assertTrue(d.checkNoData)
            assertTrue(d.keepArmed)
        }
        // a link that is connected but not running (stopped meanwhile) is not a live link either
        assertEquals(WatchdogAction.ENSURE_ADVERTISING, PumpBtWatchdogRules.decide(input(health = health(running = false))).action)
    }

    @Test
    fun aFreshConnectionWithAnOldReadFromThePreviousSessionIsNotStale() {
        val d = PumpBtWatchdogRules.decide(input(health = health(lastCycle = T0 - 40 * MIN), seen = null))
        assertEquals(WatchdogAction.NONE, d.action)
        assertEquals(T0, d.connectedSeenAtMs)
    }

    @Test
    fun connectedSevenMinutesWithoutAReadReadsAndFifteenReconnects() {
        val seen = T0
        fun at(after: Long) = PumpBtWatchdogRules.decide(input(now = T0 + after, health = health(lastCycle = null), seen = seen))
        assertEquals(WatchdogAction.NONE, at(7 * MIN - 1).action)
        assertEquals(seen, at(7 * MIN - 1).connectedSeenAtMs)
        assertEquals(WatchdogAction.READ_NOW, at(7 * MIN).action)
        assertEquals(seen, at(7 * MIN).connectedSeenAtMs)
        assertEquals(WatchdogAction.READ_NOW, at(15 * MIN - 1).action)
        val reconnect = at(15 * MIN)
        assertEquals(WatchdogAction.RECONNECT, reconnect.action)
        assertNull(reconnect.connectedSeenAtMs)
        assertTrue(reconnect.checkNoData)
    }

    @Test
    fun theLaterOfTheLastReadAndTheFirstSightCounts() {
        // seen 20 min ago, read 3 min ago - fresh
        assertEquals(WatchdogAction.NONE, PumpBtWatchdogRules.decide(input(health = health(lastCycle = T0 - 3 * MIN), seen = T0 - 20 * MIN)).action)
        // seen 10 min ago, read 8 min ago - read now
        assertEquals(WatchdogAction.READ_NOW, PumpBtWatchdogRules.decide(input(health = health(lastCycle = T0 - 8 * MIN), seen = T0 - 10 * MIN)).action)
        // seen 30 min ago, read 16 min ago - reconnect
        assertEquals(WatchdogAction.RECONNECT, PumpBtWatchdogRules.decide(input(health = health(lastCycle = T0 - 16 * MIN), seen = T0 - 30 * MIN)).action)
    }

    @Test
    fun timesInTheFutureCountAsNow() {
        // the phone's clock was set back: the first sight and the read lie ahead
        val d = PumpBtWatchdogRules.decide(input(health = health(lastCycle = T0 + 60 * MIN), seen = T0 + 30 * MIN))
        assertEquals(WatchdogAction.NONE, d.action)
        assertEquals(T0, d.connectedSeenAtMs)
        // a read in the future is ignored, the first sight 8 min ago counts
        assertEquals(WatchdogAction.READ_NOW, PumpBtWatchdogRules.decide(input(health = health(lastCycle = T0 + MIN), seen = T0 - 8 * MIN)).action)
    }

    @Test
    fun theCardIsRemindedADayAfterItWasTold() {
        fun card(reminded: Long?, now: Long = T0) = PumpBtWatchdogRules.decide(input(now = now, health = null, card = true, reminded = reminded))
        // first seen here (a new process): from now, no reminder yet
        card(null).let { assertFalse(it.remind); assertEquals(T0, it.remindedAtMs) }
        card(T0 - PumpBtWatchdogRules.REMIND_MS + 1).let { assertFalse(it.remind); assertEquals(T0 - PumpBtWatchdogRules.REMIND_MS + 1, it.remindedAtMs) }
        card(T0 - PumpBtWatchdogRules.REMIND_MS).let { assertTrue(it.remind); assertEquals(T0, it.remindedAtMs) }
        // told "in the future" (clock set back): from now
        card(T0 + 60 * MIN).let { assertFalse(it.remind); assertEquals(T0, it.remindedAtMs) }
        // no card waiting: nothing, the mark stays
        PumpBtWatchdogRules.decide(input(health = null, card = false, reminded = T0 - 48 * 60 * MIN)).let {
            assertFalse(it.remind)
            assertEquals(T0 - 48 * 60 * MIN, it.remindedAtMs)
        }
    }

    @Test
    fun thePumpsOwnRhythmNeverDropsTheLink() {
        // journal of 27.11: the pump connects about every 8 min for about 7 min and reads at once; then a gap of 55
        // min without the pump; rounds every 5 min (Doze: sometimes 9)
        var seen: Long? = null
        val actions = mutableListOf<WatchdogAction>()
        var lastRead: Long? = null
        fun connectedAt(t: Long): Boolean {
            val m = (t - T0) / MIN
            if (m in 40 until 95) return false // the 55-min gap
            return (m % 8) < 7
        }
        var t = T0
        while (t < T0 + 150 * MIN) {
            val m = (t - T0) / MIN
            val connected = connectedAt(t)
            if (connected && m !in 40 until 95) lastRead = T0 + (m - m % 8) * MIN // the read at the session's start
            val d = PumpBtWatchdogRules.decide(input(now = t, health = health(connected = connected, lastCycle = lastRead), seen = seen))
            seen = d.connectedSeenAtMs
            actions += d.action
            t += if (m % 3 == 0L) 9 * MIN else 5 * MIN
        }
        assertFalse(actions.toString(), WatchdogAction.RECONNECT in actions)
        assertTrue(WatchdogAction.ENSURE_ADVERTISING in actions)
    }
}

/**
 * The watchdog on Android (part B2c): the alarm armed once, kept at a process start and set again by the alarm itself;
 * the source off removes it; one round calls what the rules say; the update receiver; the notifier - one notification
 * per kind, a repeat replaces it without a second sound; the starter's order (critic of B2a, C-3: the notifier before
 * the source's start).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PumpBtWatchdogTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val am get() = app.getSystemService(AlarmManager::class.java)
    private val alarms get() = shadowOf(am).scheduledAlarms
    private val nm get() = app.getSystemService(NotificationManager::class.java)
    private fun shown(id: Int): Notification? = nm.activeNotifications.firstOrNull { it.id == id }?.notification

    private var elapsed = 10_000_000L
    private var now = T0
    private var on = true

    private val calls = mutableListOf<String>()
    private var health: PumpLinkHealth? = null
    private var card = false

    private val fake = object : PumpBtWatchdogActions {
        override fun health() = health
        override fun cardWaiting() = card
        override fun ensureAdvertising(context: Context) { calls += "advertise" }
        override fun readNow() { calls += "read" }
        override fun reconnect() { calls += "reconnect" }
        override fun checkNoData(context: Context) { calls += "noData" }
        override fun remind(context: Context) { calls += "remind" }
    }

    @Before
    fun setUp() {
        PumpBtWatchdog.resetForTest()
        PumpBtWatchdog.elapsed = { elapsed }
        PumpBtWatchdog.actions = fake
        PumpBtWatchdog.upstreamService = { calls += "upstream" }
        PumpSourceSwitch.override = { on }
        CareLinkClock.now = { now }
        app.getSharedPreferences("mod_pumpbt_watchdog", Context.MODE_PRIVATE).edit().clear().commit()
        nm.cancelAll()
    }

    @After
    fun tearDown() {
        PumpBtWatchdog.disarm(app)
        PumpBtWatchdog.resetForTest()
        PumpSourceSwitch.override = null
        CareLinkClock.now = { System.currentTimeMillis() }
        ModPumpStarter.steps = RealStarterSteps
        PumpBtSource.resetForTest()
        nm.cancelAll()
    }

    private fun fire() = PumpBtWatchdogReceiver().onReceive(app, Intent(PumpBtWatchdog.ACTION))

    @Test
    fun theAlarmIsArmedOnceKeptAtAProcessStartAndSetAgainByItself() {
        assertTrue(PumpBtWatchdog.arm(app))
        assertEquals(1, alarms.size)
        assertEquals(elapsed + PumpBtWatchdogRules.TICK_MS, alarms[0].triggerAtMs)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarms[0].type)
        val first = alarms[0].triggerAtMs
        // a process start a minute later keeps it
        elapsed += MIN
        assertTrue(PumpBtWatchdog.arm(app))
        assertEquals(1, alarms.size)
        assertEquals(first, alarms[0].triggerAtMs)
        // the alarm comes: the next one in 5 min, then the round
        elapsed += 4 * MIN
        fire()
        assertEquals(1, alarms.size)
        assertEquals(elapsed + PumpBtWatchdogRules.TICK_MS, alarms[0].triggerAtMs)
        assertEquals(listOf("advertise", "noData"), calls)
        // and again
        elapsed += 5 * MIN
        fire()
        assertEquals(1, alarms.size)
        assertEquals(elapsed + PumpBtWatchdogRules.TICK_MS, alarms[0].triggerAtMs)
    }

    @Test
    fun theSourceOffRemovesTheAlarm() {
        PumpBtWatchdog.arm(app)
        assertEquals(1, alarms.size)
        on = false
        fire()
        assertTrue(alarms.isEmpty())
        assertTrue(calls.isEmpty())
        assertFalse(PumpBtWatchdog.arm(app))
        assertTrue(alarms.isEmpty())
        // sync follows the switch
        on = true
        PumpBtWatchdog.sync(app)
        assertEquals(1, alarms.size)
        on = false
        PumpBtWatchdog.sync(app)
        assertTrue(alarms.isEmpty())
    }

    @Test
    fun aRoundDoesWhatTheRulesSay() {
        health = PumpLinkHealth(running = true, advertising = false, connected = true, lastCycleAtMs = null, lastHistoryAtMs = null)
        PumpBtWatchdog.tick(app, now)              // first sight of the connection
        PumpBtWatchdog.tick(app, now + 5 * MIN)
        assertEquals(listOf("noData", "noData"), calls)
        calls.clear()
        PumpBtWatchdog.tick(app, now + 10 * MIN)   // 10 min connected without a read
        assertEquals(listOf("read", "noData"), calls)
        calls.clear()
        PumpBtWatchdog.tick(app, now + 15 * MIN)
        assertEquals(listOf("reconnect", "noData"), calls)
        calls.clear()
        // the next connection is counted anew
        PumpBtWatchdog.tick(app, now + 20 * MIN)
        assertEquals(listOf("noData"), calls)
    }

    @Test
    fun theCardIsRemindedADayAfterTheNotifierToldIt() {
        card = true
        PumpBtNotifier.show(app, PumpBtAlert(PumpBtAlertKind.CONFIRM_BACKFILL)) // the source told it at now
        PumpBtWatchdog.tick(app, now + PumpBtWatchdogRules.REMIND_MS - MIN)
        assertFalse("remind" in calls)
        PumpBtWatchdog.tick(app, now + PumpBtWatchdogRules.REMIND_MS)
        assertTrue("remind" in calls)
        calls.clear()
        PumpBtWatchdog.tick(app, now + PumpBtWatchdogRules.REMIND_MS + 5 * MIN)
        assertFalse("remind" in calls)
    }

    @Test
    fun theUpdateReceiverStartsTheServiceAndTheAlarmOnlyWithTheSourceOn() {
        on = false
        PumpBtUpdateReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(calls.isEmpty())
        assertTrue(alarms.isEmpty())
        on = true
        PumpBtUpdateReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED)) // not its action
        assertTrue(calls.isEmpty())
        PumpBtUpdateReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(listOf("advertise", "upstream"), calls)
        assertEquals(1, alarms.size)
    }

    @Test
    fun oneNotificationPerKindARepeatWithoutASecondSound() {
        PumpBtNotifier.show(app, PumpBtAlert(PumpBtAlertKind.UNKNOWN_RECORD, 0x1234))
        PumpBtNotifier.show(app, PumpBtAlert(PumpBtAlertKind.UNKNOWN_RECORD, 0x4321))
        assertEquals(1, nm.activeNotifications.size)
        val n = shown(PumpBtNotifier.id(PumpBtAlertKind.UNKNOWN_RECORD))!!
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(CareLinkNotifier.CHANNEL, n.channelId)
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("0x4321"))
        // every kind its own id and text; none of the cloud's ids
        for (k in PumpBtAlertKind.values()) PumpBtNotifier.show(app, PumpBtAlert(k, 0x005A))
        assertEquals(PumpBtAlertKind.values().size, nm.activeNotifications.size)
        val ids = PumpBtAlertKind.values().map { PumpBtNotifier.id(it) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it >= 1_947_010 })
        val bond = shown(PumpBtNotifier.id(PumpBtAlertKind.BOND_LOST))!!
        assertTrue(bond.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("pair again"))
    }

    @Test
    fun theStarterSetsTheNotifierBeforeTheSourceStarts() {
        val order = mutableListOf<String>()
        ModPumpStarter.steps = object : StarterSteps {
            override fun notifier() { order += "notifier" }
            override fun trial(app: Context) { order += "trial" }
            override fun init(app: Context) { order += "init" }
            override fun watchdog(app: Context) { order += "watchdog" }
        }
        ModPumpStarter.start(app)
        assertEquals(listOf("notifier", "trial", "init", "watchdog"), order)
        // a failing step does not stop the rest
        order.clear()
        ModPumpStarter.steps = object : StarterSteps {
            override fun notifier() { throw IllegalStateException("made-up") }
            override fun trial(app: Context) { order += "trial" }
            override fun init(app: Context) { order += "init" }
            override fun watchdog(app: Context) { order += "watchdog" }
        }
        ModPumpStarter.start(app)
        assertEquals(listOf("trial", "init", "watchdog"), order)
    }

    @Test
    fun theRealNotifierStepPutsTheNotifierInTheSource() {
        RealStarterSteps.notifier()
        assertNotNull(PumpBtSource.alert)
        PumpBtSource.alert?.invoke(app, PumpBtAlert(PumpBtAlertKind.BOND_LOST))
        assertNotNull(shown(PumpBtNotifier.id(PumpBtAlertKind.BOND_LOST)))
    }
}
