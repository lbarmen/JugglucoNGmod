package mod.pumpbt

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowAlarmManager
import tk.glucodata.mod.carelink.CareLinkClock
import tk.glucodata.mod.carelink.CareLinkNotifier
import tk.glucodata.mod.carelink.PumpSourceSwitch
import tk.glucodata.mod.pumpbt.ModPumpStarter
import tk.glucodata.mod.pumpbt.PumpBtAlert
import tk.glucodata.mod.pumpbt.PumpBtAlertKind
import tk.glucodata.mod.pumpbt.PumpBtBridge
import tk.glucodata.mod.pumpbt.PumpBtJournal
import tk.glucodata.mod.pumpbt.PumpBtKey
import tk.glucodata.mod.pumpbt.PumpBtNotifier
import tk.glucodata.mod.pumpbt.PumpBtSource
import tk.glucodata.mod.pumpbt.PumpBtUpdateReceiver
import tk.glucodata.mod.pumpbt.PumpBtWatchdog
import tk.glucodata.mod.pumpbt.PumpBtWatchdogActions
import tk.glucodata.mod.pumpbt.PumpBtWatchdogReceiver
import tk.glucodata.mod.pumpbt.PumpBtWatchdogRules
import tk.glucodata.mod.pumpbt.RealStarterSteps
import tk.glucodata.mod.pumpbt.SourceWatchdogActions
import tk.glucodata.mod.pumpbt.StarterSteps
import tk.glucodata.mod.pumpbt.WatchdogAction
import tk.glucodata.mod.pumpbt.WatchdogInput
import tk.glucodata.mod.pumpbt.ble.PumpLinkHealth
import tk.glucodata.mod.pumpbt.core.toHex
import java.io.File

private const val MIN = 60_000L
private const val T0 = 1_800_000_000_000L

/**
 * The watchdog's rules (series part 2, part B2c): every branch by time - the source off, no link, a running link without
 * an advertisement mended only on the second round in a row, the link connected with the 7 and 15 min counted from the
 * later of the last read and the round that first saw the connection, clock set back, the card reminder a day later;
 * and the pump's own rhythm (a session of ~7 min every ~8 min, a gap of 55 min) never makes the watchdog drop a link or
 * ask for a read. Pure: no Android.
 */
class PumpBtWatchdogRulesTest {

    private fun health(running: Boolean = true, advertising: Boolean = false, connected: Boolean = true, lastCycle: Long? = null) =
        PumpLinkHealth(running, advertising, connected, lastCycle, lastCycle)

    private fun input(
        now: Long = T0, on: Boolean = true, health: PumpLinkHealth? = health(), seen: Long? = null,
        card: Boolean = false, reminded: Long? = null, noAdvert: Long? = null, bondUntold: Boolean = false,
    ) = WatchdogInput(now, on, health, seen, card, reminded, noAdvert, bondUntold)

    @Test
    fun theArmLineSaysWhenTheNextRoundComes() {
        assertEquals("watchdog set: next round in 5 min 0 s (new time)", PumpBtWatchdogRules.armLine(true, false, 5 * MIN))
        assertEquals("watchdog set: next round in 3 min 12 s (time kept)", PumpBtWatchdogRules.armLine(true, true, 191_001))
        assertEquals("watchdog set: next round in 59 s (time kept)", PumpBtWatchdogRules.armLine(true, true, 59_000))
        assertEquals("watchdog set: next round in 1 min 0 s (time kept)", PumpBtWatchdogRules.armLine(true, true, 59_001))
        assertEquals("watchdog set: next round in 0 s (new time)", PumpBtWatchdogRules.armLine(true, false, -5))
        assertEquals(
            "watchdog: the system refused the alarm - no rounds until the app starts again",
            PumpBtWatchdogRules.armLine(false, true, 5 * MIN),
        )
    }

    @Test
    fun anUndeliveredLostBondIsCheckedAgainInARoundWithoutThePump() {
        // critic of the joints 2, N-2: the check tells it again; a connected pump is paired (its handshake clears the mark)
        for (h in listOf(null, health(running = false, connected = false), health(connected = false, advertising = true), health(connected = false))) {
            assertTrue(h.toString(), PumpBtWatchdogRules.decide(input(health = h, bondUntold = true)).recheckBond)
        }
        assertFalse(PumpBtWatchdogRules.decide(input(health = health(), bondUntold = true)).recheckBond)
        assertFalse(PumpBtWatchdogRules.decide(input(health = null, bondUntold = false)).recheckBond)
        assertFalse(PumpBtWatchdogRules.decide(input(on = false, health = null, bondUntold = true)).recheckBond)
    }

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
    fun noLinkObjectNotRunningOrAdvertisingMakesSureOfTheAdvertisement() {
        for (h in listOf(null, health(running = false, connected = false), health(connected = false, advertising = true))) {
            val d = PumpBtWatchdogRules.decide(input(health = h, seen = T0 - 3 * MIN))
            assertEquals(WatchdogAction.ENSURE_ADVERTISING, d.action)
            assertNull(d.connectedSeenAtMs) // the connection is over: the next one is counted anew
            assertNull(d.noAdvertSeenAtMs)
            assertTrue(d.checkNoData)
            assertTrue(d.keepArmed)
        }
        // a link that is connected but not running (stopped meanwhile) is not a live link either
        assertEquals(WatchdogAction.ENSURE_ADVERTISING, PumpBtWatchdogRules.decide(input(health = health(running = false))).action)
    }

    @Test
    fun aRunningLinkWithoutAnAdvertisementIsMendedOnlyOnTheSecondRoundInARow() {
        // critic B2c, N1: while the link adds its services or pauses to recover, it does not advertise for a moment
        val first = PumpBtWatchdogRules.decide(input(health = health(connected = false), seen = T0 - 3 * MIN))
        assertEquals(WatchdogAction.NONE, first.action)
        assertEquals(T0, first.noAdvertSeenAtMs)
        assertNull(first.connectedSeenAtMs)
        assertTrue(first.checkNoData)
        val second = PumpBtWatchdogRules.decide(input(now = T0 + 5 * MIN, health = health(connected = false), noAdvert = first.noAdvertSeenAtMs))
        assertEquals(WatchdogAction.ENSURE_ADVERTISING, second.action)
        assertNull(second.noAdvertSeenAtMs) // counted anew: at most every second round
        // the advertisement came back (or the pump connected) in between: the mark goes
        assertNull(PumpBtWatchdogRules.decide(input(health = health(connected = false, advertising = true), noAdvert = T0 - 5 * MIN)).noAdvertSeenAtMs)
        val connected = PumpBtWatchdogRules.decide(input(health = health(), noAdvert = T0 - 5 * MIN))
        assertEquals(WatchdogAction.NONE, connected.action)
        assertNull(connected.noAdvertSeenAtMs)
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
        assertEquals(7 * MIN, at(7 * MIN).quietMs)
        assertEquals(WatchdogAction.READ_NOW, at(15 * MIN - 1).action)
        val reconnect = at(15 * MIN)
        assertEquals(WatchdogAction.RECONNECT, reconnect.action)
        assertEquals(15 * MIN, reconnect.quietMs)
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
        // no mark - the card was never shown (notifications off when it came): told in this round (critic of the joints 2, N-1)
        card(null).let { assertTrue(it.remind); assertEquals(T0, it.remindedAtMs) }
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
    fun thePumpsOwnRhythmNeverDropsTheLinkNorAsksForARead() {
        // journal of 27.11: the pump connects about every 8 min for about 7 min and reads at once; then a gap of 55
        // min without the pump; rounds every 5 min (Doze: sometimes 9); between sessions the link advertises
        var seen: Long? = null
        var noAdvert: Long? = null
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
            val h = health(connected = connected, advertising = !connected, lastCycle = lastRead)
            val d = PumpBtWatchdogRules.decide(input(now = t, health = h, seen = seen, noAdvert = noAdvert))
            seen = d.connectedSeenAtMs
            noAdvert = d.noAdvertSeenAtMs
            actions += d.action
            t += if (m % 3 == 0L) 9 * MIN else 5 * MIN
        }
        assertFalse(actions.toString(), WatchdogAction.RECONNECT in actions)
        // the link reads by itself at every connection: the watchdog never has to ask (critic B2c, N9)
        assertEquals(actions.toString(), 0, actions.count { it == WatchdogAction.READ_NOW })
        assertTrue(WatchdogAction.ENSURE_ADVERTISING in actions)
    }

    @Test
    fun twoSessionsWithoutAReadAskOnceAndReconnectOnceAfterFifteenMinutes() {
        // critic B2c, N2 (accepted): sessions of 7 min every 8 min, nothing read in them; rounds every 5 min catch only
        // one gap - the first sight then stays from the session before, so the reconnect may come early in a session,
        // but only after 15 min of connected rounds without a read, once, and a read is asked once before it
        var seen: Long? = null
        var noAdvert: Long? = null
        val actions = mutableListOf<WatchdogAction>()
        var t = T0 + 2 * MIN
        while (t <= T0 + 32 * MIN) {
            val m = (t - T0) / MIN
            val connected = (m % 8) < 7
            val d = PumpBtWatchdogRules.decide(
                input(now = t, health = health(connected = connected, advertising = !connected, lastCycle = T0 - 30 * MIN), seen = seen, noAdvert = noAdvert),
            )
            if (d.action == WatchdogAction.RECONNECT) assertTrue(d.quietMs!! >= PumpBtWatchdogRules.RECONNECT_AFTER_MS)
            seen = d.connectedSeenAtMs
            noAdvert = d.noAdvertSeenAtMs
            actions += d.action
            t += 5 * MIN
        }
        assertEquals(
            listOf(
                WatchdogAction.NONE, WatchdogAction.ENSURE_ADVERTISING, WatchdogAction.NONE, WatchdogAction.NONE,
                WatchdogAction.READ_NOW, WatchdogAction.RECONNECT, WatchdogAction.NONE,
            ),
            actions,
        )
    }
}

/**
 * The watchdog on Android (part B2c): the alarm armed once, kept at a process start and set again by the alarm itself
 * (exact, or inexact without the right); the source off removes it, and it follows the source's on and off; one round
 * calls what the rules say; the update receiver (a running link left alone, upstream's service); the notifier - one
 * notification per kind, a repeat replaces it without a second sound, unknown types gathered, nothing shown is nothing
 * told; the starter's order (critic of B2a, C-3: the notifier before the source's start) and its source step.
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
    private fun text(n: Notification?) = n?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private val prefs get() = app.getSharedPreferences("mod_pumpbt_watchdog", Context.MODE_PRIVATE)
    private val pumpDir get() = File(app.noBackupFilesDir, "mod-pumpbt")

    private var elapsed = 10_000_000L
    private var now = T0
    private var on = true

    private val calls = mutableListOf<String>()
    private var health: PumpLinkHealth? = null
    private var card = false
    private var running = false
    private var remindFails = false
    private var bondUntold = false

    private val fake = object : PumpBtWatchdogActions {
        override fun bondLostUntold() = bondUntold
        override fun recheckBond(context: Context) { calls += "bond" }
        override fun health() = health
        override fun cardWaiting() = card
        override fun linkRunning() = running
        override fun startLink(context: Context) { calls += "start" }
        override fun ensureAdvertising(context: Context) { calls += "advertise" }
        override fun readNow() { calls += "read" }
        override fun reconnect() { calls += "reconnect" }
        override fun checkNoData(context: Context) { calls += "noData" }
        override fun remind(context: Context) {
            calls += "remind"
            if (remindFails) throw IllegalStateException("not shown")
        }
    }

    @Before
    fun setUp() {
        pumpDir.deleteRecursively()
        PumpBtSource.resetForTest()
        PumpBtSource.sensorBluetooth = { true } // the native library is not there
        // no journal database: the source's real start with an empty memory asks it for the boundary, and Room's
        // instance would outlive this test (a later test of the journal then met a closed connection)
        PumpBtBridge.resetForTest()
        PumpBtBridge.loadRows = { _, _, _ -> emptyList() }
        PumpBtBridge.loadByKeys = { _, _ -> emptyList() }
        PumpBtBridge.loadCloudRowTimes = { _, _, _ -> emptyList() }
        PumpBtWatchdog.resetForTest()
        PumpBtWatchdog.elapsed = { elapsed }
        PumpBtWatchdog.actions = fake
        PumpBtWatchdog.upstreamService = { calls += "upstream" }
        PumpSourceSwitch.override = { on }
        CareLinkClock.now = { now }
        prefs.edit().clear().commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true) // the right upstream's USE_EXACT_ALARM gives on a phone
        shadowOf(nm).setNotificationsEnabled(true)
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
        PumpBtBridge.resetForTest()
        idleMain()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowOf(nm).setNotificationsEnabled(true)
        nm.cancelAll()
        pumpDir.deleteRecursively()
    }

    private fun fire() = PumpBtWatchdogReceiver().onReceive(app, Intent(PumpBtWatchdog.ACTION))

    private fun idleMain() {
        repeat(3) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(50)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!check()) {
            if (System.currentTimeMillis() > until) fail("waited in vain: $what")
            Thread.sleep(20)
        }
    }

    @Test
    fun theAlarmIsArmedOnceKeptAtAProcessStartAndSetAgainByItself() {
        assertTrue(PumpBtWatchdog.arm(app))
        assertEquals(1, alarms.size)
        assertEquals(elapsed + PumpBtWatchdogRules.TICK_MS, alarms[0].triggerAtMs)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarms[0].type)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarms[0].windowLengthMs)
        assertTrue(alarms[0].isAllowWhileIdle)
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
    fun aProcessStartAfterAForceStopSetsTheRemovedAlarmAgainAtTheKeptTime() {
        // pump journal 28.11: a force stop at 09:59 removed the alarm, Android 14 kept the PendingIntent, the process
        // start trusted it and no round came for hours - with the pump connected and quiet nothing was read
        assertTrue(PumpBtWatchdog.arm(app))
        val first = alarms[0].triggerAtMs
        assertTrue(PumpBtJournal.tail.value.last().endsWith("watchdog set: next round in 5 min 0 s (new time)"))
        // the force stop: the alarm goes, the PendingIntent stays
        val pending = PumpBtWatchdog.intent(app, android.app.PendingIntent.FLAG_NO_CREATE)
        assertNotNull(pending)
        am.cancel(pending!!)
        assertTrue(alarms.isEmpty())
        assertNotNull(PumpBtWatchdog.intent(app, android.app.PendingIntent.FLAG_NO_CREATE))
        // a new process a minute later: the alarm is back at the same time, one only, and the journal says so
        PumpBtWatchdog.resetForTest()
        PumpBtWatchdog.elapsed = { elapsed }
        PumpBtWatchdog.actions = fake
        PumpBtWatchdog.upstreamService = { calls += "upstream" }
        elapsed += MIN
        assertTrue(PumpBtWatchdog.arm(app))
        assertEquals(1, alarms.size)
        assertEquals(first, alarms[0].triggerAtMs)
        assertTrue(PumpBtJournal.tail.value.last().endsWith("watchdog set: next round in 4 min 0 s (time kept)"))
        // a second arm in the same process sets it again without a second alarm and without a second line
        fun armLines() = PumpBtJournal.tail.value.count { it.contains("watchdog set:") }
        val lines = armLines()
        assertTrue(PumpBtWatchdog.arm(app))
        assertEquals(1, alarms.size)
        assertEquals(first, alarms[0].triggerAtMs)
        assertEquals(lines, armLines())
        // and the alarm, when it comes, carries on every 5 min
        elapsed += 4 * MIN
        fire()
        assertEquals(1, alarms.size)
        assertEquals(elapsed + PumpBtWatchdogRules.TICK_MS, alarms[0].triggerAtMs)
    }

    @Test
    fun withoutTheExactAlarmRightAnInexactOneIsSet() {
        // critic B2c, N9: no exact alarms allowed (Android 12+ without the right) - setAndAllowWhileIdle, still waking
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertTrue(PumpBtWatchdog.arm(app))
        assertEquals(1, alarms.size)
        assertEquals(ShadowAlarmManager.WINDOW_HEURISTIC, alarms[0].windowLengthMs)
        assertTrue(alarms[0].isAllowWhileIdle)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, alarms[0].type)
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
    fun theAlarmFollowsTheSourceTurnedOnAndOff() {
        // critic B2c, N9: the starter's follow - the source publishes "enabled" (init, enable, disable)
        on = false
        PumpBtWatchdog.follow(app)
        on = true
        PumpBtSource.init(app)
        waitFor("the alarm armed") { alarms.size == 1 }
        on = false
        PumpBtSource.init(app)
        waitFor("the alarm removed") { alarms.isEmpty() }
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
        // the journal names the minutes of silence (critic B2c, N8)
        assertTrue(PumpBtJournal.tail.value.last().endsWith("nothing read for 10 min - read now"))
        calls.clear()
        PumpBtWatchdog.tick(app, now + 15 * MIN)
        assertEquals(listOf("reconnect", "noData"), calls)
        assertTrue(PumpBtJournal.tail.value.last().endsWith("nothing read for 15 min - reconnect"))
        calls.clear()
        // the next connection is counted anew
        PumpBtWatchdog.tick(app, now + 20 * MIN)
        assertEquals(listOf("noData"), calls)
        calls.clear()
        // running, neither connected nor advertising: mended on the second round in a row
        health = PumpLinkHealth(running = true, advertising = false, connected = false, lastCycleAtMs = null, lastHistoryAtMs = null)
        PumpBtWatchdog.tick(app, now + 25 * MIN)
        assertEquals(listOf("noData"), calls)
        PumpBtWatchdog.tick(app, now + 30 * MIN)
        assertEquals(listOf("noData", "advertise", "noData"), calls)
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
    fun aCardNeverShownIsToldInTheNextRoundAndAgainWhileNotShown() {
        // critic of the joints 2, N-1: notifications were off when the card came - no mark; told at once, not a day later
        card = true
        remindFails = true
        PumpBtWatchdog.tick(app, now)
        assertEquals(1, calls.count { it == "remind" })
        assertFalse(prefs.contains("cardRemindedAtMs")) // not shown: still no mark
        remindFails = false
        PumpBtWatchdog.tick(app, now + 5 * MIN)
        assertEquals(2, calls.count { it == "remind" })
        assertEquals(now + 5 * MIN, prefs.getLong("cardRemindedAtMs", 0L))
        PumpBtWatchdog.tick(app, now + 10 * MIN)
        assertEquals(2, calls.count { it == "remind" }) // shown: the next one a day later
    }

    @Test
    fun anUndeliveredLostBondIsCheckedAgainEachRoundWithoutThePump() {
        // critic of the joints 2, N-2
        bondUntold = true
        health = PumpLinkHealth(running = true, advertising = true, connected = false, lastCycleAtMs = null, lastHistoryAtMs = null)
        PumpBtWatchdog.tick(app, now)
        assertEquals(listOf("advertise", "bond", "noData"), calls)
        calls.clear()
        health = PumpLinkHealth(running = true, advertising = false, connected = true, lastCycleAtMs = null, lastHistoryAtMs = null)
        PumpBtWatchdog.tick(app, now + 5 * MIN)
        assertEquals(listOf("noData"), calls)
        calls.clear()
        bondUntold = false
        health = null
        PumpBtWatchdog.tick(app, now + 10 * MIN)
        assertEquals(listOf("advertise", "noData"), calls)
    }

    @Test
    fun theWatchdogsCheckTellsAnUndeliveredLostBondThroughTheRealSource() {
        // the real source and notifier: notifications off - marked lost, not told; on again - the next round tells it
        PumpBtWatchdog.actions = object : PumpBtWatchdogActions by fake {
            override fun bondLostUntold() = SourceWatchdogActions.bondLostUntold()
            override fun recheckBond(context: Context) {
                calls += "bond"
                runBlocking { PumpBtSource.recheckBond(context).join() }
            }
        }
        RealStarterSteps.notifier()
        PumpBtSource.bondedDevices = { emptyList() }
        PumpBtSource.store(app).apply {
            paired = true
            pumpAddress = "00:11:22:33:44:55"
        }
        shadowOf(nm).setNotificationsEnabled(false)
        runBlocking { PumpBtSource.recheckBond(app).join() }
        assertTrue(PumpBtSource.state.value.bondLost)
        assertTrue(PumpBtSource.bondLostUntold())
        val lostLines = PumpBtJournal.tail.value.count { "no longer has the paired pump" in it }
        PumpBtWatchdog.tick(app, now)
        assertTrue("bond" in calls)
        assertTrue(PumpBtSource.bondLostUntold())
        // checked again while not shown: no second journal line for the same loss
        assertEquals(lostLines, PumpBtJournal.tail.value.count { "no longer has the paired pump" in it })
        shadowOf(nm).setNotificationsEnabled(true)
        PumpBtWatchdog.tick(app, now + 5 * MIN)
        assertFalse(PumpBtSource.bondLostUntold())
        assertTrue(PumpBtSource.store(app).alerts.bondLostTold)
        assertNotNull(shown(PumpBtNotifier.id(PumpBtAlertKind.BOND_LOST)))
        assertTrue(PumpBtJournal.tail.value.last { "phone forgot" in it }.endsWith("told now"))
        // told: the watchdog no longer checks
        calls.clear()
        PumpBtWatchdog.tick(app, now + 10 * MIN)
        assertFalse("bond" in calls)
    }

    @Test
    fun aReminderNotShownIsTriedAgainNextRound() {
        card = true
        PumpBtWatchdog.cardTold(app, now)
        remindFails = true
        PumpBtWatchdog.tick(app, now + PumpBtWatchdogRules.REMIND_MS)
        assertEquals(1, calls.count { it == "remind" })
        assertEquals(now, prefs.getLong("cardRemindedAtMs", 0L)) // the old time stays
        remindFails = false
        PumpBtWatchdog.tick(app, now + PumpBtWatchdogRules.REMIND_MS + 5 * MIN)
        assertEquals(2, calls.count { it == "remind" })
        assertEquals(now + PumpBtWatchdogRules.REMIND_MS + 5 * MIN, prefs.getLong("cardRemindedAtMs", 0L))
    }

    @Test
    fun theUpdateReceiverStartsTheLinkTheServiceAndTheAlarmOnlyWithTheSourceOn() {
        on = false
        PumpBtUpdateReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(calls.isEmpty())
        assertTrue(alarms.isEmpty())
        on = true
        PumpBtUpdateReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED)) // not its action
        assertTrue(calls.isEmpty())
        PumpBtUpdateReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(listOf("start", "upstream"), calls)
        assertEquals(1, alarms.size)
        // the journal says what was done (critic of the joints 2, N-6)
        assertTrue(PumpBtJournal.tail.value.last { "app updated" in it }.endsWith("the link was not running - starting it, watchdog set again"))
        // the starter has the link running already: left alone (critic B2c, N1)
        calls.clear()
        running = true
        PumpBtUpdateReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(listOf("upstream"), calls)
        assertTrue(PumpBtJournal.tail.value.last { "app updated" in it }.endsWith("the link was running already, watchdog set again"))
    }

    @Test
    fun afterAnUpdateTheRealSourceStartsTheLinkAndLeavesARunningOneAlone() {
        // critic B2c, N9: the real path (no radio: Robolectric's Bluetooth is off, the link still runs and waits)
        PumpBtKey.file(app).apply { parentFile!!.mkdirs(); writeText(fakeKeyDatabase(local = 4, remotes = listOf(1)).toHex()) }
        shadowOf(app).grantPermissions(*PumpBtSource.permissions())
        PumpBtWatchdog.actions = SourceWatchdogActions
        PumpBtWatchdog.afterUpdate(app)
        waitFor("the link running") {
            idleMain()
            PumpBtSource.linkRunning
        }
        assertTrue(PumpBtSource.state.value.link.running)
        fun lines(what: String) = PumpBtJournal.tail.value.count { what in it }
        val waiting = lines("mode: waiting for the pump")
        val reconnects = lines("reconnect")
        // the update receiver again (or right after the starter): nothing restarted, nothing dropped
        PumpBtWatchdog.afterUpdate(app)
        idleMain()
        assertTrue(PumpBtSource.linkRunning)
        assertEquals(waiting, lines("mode: waiting for the pump"))
        assertEquals(reconnects, lines("reconnect"))
        assertTrue(PumpBtJournal.tail.value.last { "app updated" in it }.endsWith("the link was running already, watchdog set again"))
        PumpBtSource.stopLink()
        idleMain()
    }

    @Test
    fun oneNotificationPerKindARepeatWithoutASecondSound() {
        PumpBtNotifier.show(app, PumpBtAlert(PumpBtAlertKind.UNKNOWN_RECORD, 0x1234))
        PumpBtNotifier.show(app, PumpBtAlert(PumpBtAlertKind.UNKNOWN_RECORD, 0x4321))
        assertEquals(1, nm.activeNotifications.size)
        val n = shown(PumpBtNotifier.id(PumpBtAlertKind.UNKNOWN_RECORD))!!
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(CareLinkNotifier.CHANNEL, n.channelId)
        assertTrue(text(n)!!.contains("0x4321"))
        // every kind its own id and text; none of the cloud's ids
        for (k in PumpBtAlertKind.values()) PumpBtNotifier.show(app, PumpBtAlert(k, 0x005A))
        assertEquals(PumpBtAlertKind.values().size, nm.activeNotifications.size)
        val ids = PumpBtAlertKind.values().map { PumpBtNotifier.id(it) }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it >= 1_947_010 })
        val bond = shown(PumpBtNotifier.id(PumpBtAlertKind.BOND_LOST))!!
        assertTrue(text(bond)!!.contains("pair again"))
    }

    @Test
    fun unknownTypesGatherWhileTheNotificationIsShown() {
        // critic B2c, N4: a second unknown type does not hide the first
        val id = PumpBtNotifier.id(PumpBtAlertKind.UNKNOWN_RECORD)
        PumpBtNotifier.show(app, PumpBtAlert(PumpBtAlertKind.UNKNOWN_RECORD, 0x1234))
        PumpBtNotifier.show(app, PumpBtAlert(PumpBtAlertKind.UNKNOWN_RECORD, 0x4321))
        PumpBtNotifier.show(app, PumpBtAlert(PumpBtAlertKind.UNKNOWN_RECORD, 0x1234))
        assertTrue(text(shown(id)), text(shown(id))!!.contains("0x1234, 0x4321"))
        // swiped away: the next one names only itself
        nm.cancel(id)
        PumpBtNotifier.show(app, PumpBtAlert(PumpBtAlertKind.UNKNOWN_RECORD, 0x0042))
        assertTrue(text(shown(id)), text(shown(id))!!.contains("0x0042"))
        assertFalse(text(shown(id)), text(shown(id))!!.contains("0x1234"))
    }

    @Test
    fun theCardReminderIsPostedAgainAndMarksTheTime() {
        PumpBtNotifier.show(app, PumpBtAlert(PumpBtAlertKind.CONFIRM_BACKFILL))
        assertEquals(now, prefs.getLong("cardRemindedAtMs", 0L))
        now += PumpBtWatchdogRules.REMIND_MS
        PumpBtNotifier.remindCard(app)
        assertNotNull(shown(PumpBtNotifier.id(PumpBtAlertKind.CONFIRM_BACKFILL)))
        assertEquals(1, nm.activeNotifications.size)
        assertEquals(now, prefs.getLong("cardRemindedAtMs", 0L))
    }

    @Test
    fun withNotificationsOffNothingIsToldAndTheCardClockDoesNotStart() {
        // critic B2c, C1: not shown - the notifier throws, the source keeps the kind "not told"
        shadowOf(nm).setNotificationsEnabled(false)
        for (k in PumpBtAlertKind.values()) {
            try {
                PumpBtNotifier.show(app, PumpBtAlert(k, 0x005A))
                fail("$k shown with notifications off")
            } catch (_: IllegalStateException) {
            }
        }
        assertTrue(nm.activeNotifications.isEmpty())
        assertFalse(prefs.contains("cardRemindedAtMs"))
        // through the source's own path: the lost bond is not marked told while nothing is shown
        RealStarterSteps.notifier()
        runBlocking { PumpBtSource.applyBondForTest(app, paired = true, bonded = false) }
        assertTrue(PumpBtSource.state.value.bondLost)
        assertFalse(PumpBtSource.store(app).alerts.bondLostTold)
        // notifications on again: the next check tells it, once
        shadowOf(nm).setNotificationsEnabled(true)
        runBlocking { PumpBtSource.applyBondForTest(app, paired = true, bonded = false) }
        assertTrue(PumpBtSource.store(app).alerts.bondLostTold)
        assertNotNull(shown(PumpBtNotifier.id(PumpBtAlertKind.BOND_LOST)))
        nm.cancelAll()
        runBlocking { PumpBtSource.applyBondForTest(app, paired = true, bonded = false) }
        assertNull(shown(PumpBtNotifier.id(PumpBtAlertKind.BOND_LOST)))
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
    fun theStarterStartsTheSourceOnlyForSomeoneWhoUsesIt() {
        // critic B2c, N6: off and never paired - no init (no memory read, no bond receiver)
        assertFalse(ModPumpStarter.sourceInUse(false) { false })
        assertTrue(ModPumpStarter.sourceInUse(true) { false })
        assertTrue(ModPumpStarter.sourceInUse(false) { true })
        // the real step: init publishes the detailed-journal switch, so its mark shows whether init ran
        on = false
        PumpBtSource.store(app).verbose = true
        RealStarterSteps.init(app)
        assertFalse(PumpBtSource.state.value.verbose)
        PumpBtSource.store(app).pumpAddress = "00:11:22:33:44:55"
        RealStarterSteps.init(app)
        assertTrue(PumpBtSource.state.value.verbose)
    }

    @Test
    fun theStarterMakesNoLinkStateFileForSomeoneWhoNeverUsedThePump() {
        // critic of the joints 2, N-5: the address is only read - no folder, no file, no store
        on = false
        val file = File(pumpDir, "link.json")
        RealStarterSteps.init(app)
        assertFalse(file.exists())
        assertFalse(pumpDir.exists())
        assertFalse(PumpBtSource.pumpAddressKnown(app))
        // a file with an address (a new process: no store yet) - read, and the source starts
        pumpDir.mkdirs()
        file.writeText("""{"pump_address":"00:11:22:33:44:55","verbose":true}""")
        assertTrue(PumpBtSource.pumpAddressKnown(app))
        RealStarterSteps.init(app)
        assertTrue(PumpBtSource.state.value.verbose)
    }

    @Test
    fun theAddressIsReadFromTheFileOrTheOldPreferencesWithoutWriting() {
        val file = File(pumpDir, "link.json")
        val legacy = app.getSharedPreferences("mod_pumpbt_test_legacy", Context.MODE_PRIVATE)
        legacy.edit().clear().commit()
        assertFalse(tk.glucodata.mod.pumpbt.PumpBtStore.addressKnown(file, legacy))
        // the old preferences of an older build, not moved yet
        legacy.edit().putString("pump_address", "00:11:22:33:44:55").commit()
        assertTrue(tk.glucodata.mod.pumpbt.PumpBtStore.addressKnown(file, legacy))
        // moved before: the file decides
        legacy.edit().putBoolean("moved_to_file", true).commit()
        assertFalse(tk.glucodata.mod.pumpbt.PumpBtStore.addressKnown(file, legacy))
        assertFalse(file.exists())
        pumpDir.mkdirs()
        file.writeText("""{"pump_address":""}""")
        assertFalse(tk.glucodata.mod.pumpbt.PumpBtStore.addressKnown(file, legacy))
        // unreadable: someone used the pump - the store sorts it out
        file.writeText("{broken")
        assertTrue(tk.glucodata.mod.pumpbt.PumpBtStore.addressKnown(file, legacy))
        assertEquals("{broken", file.readText())
        legacy.edit().clear().commit()
    }

    @Test
    fun theRealNotifierStepPutsTheNotifierInTheSource() {
        RealStarterSteps.notifier()
        assertNotNull(PumpBtSource.alert)
        PumpBtSource.alert?.invoke(app, PumpBtAlert(PumpBtAlertKind.BOND_LOST))
        assertNotNull(shown(PumpBtNotifier.id(PumpBtAlertKind.BOND_LOST)))
    }

    @Test
    fun theUnitTestsDoNotCreateTheStarterProvider() {
        // critic B2c, N9: the unit tests run on the phone variant without the mod manifest - no ModPumpStarter whose
        // post would put the real notifier into the source behind a test's back
        assertNull(app.packageManager.resolveContentProvider("${app.packageName}.mod-pumpbt", 0))
    }
}
