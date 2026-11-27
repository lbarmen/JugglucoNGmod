package tk.glucodata.mod.pumpbt

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import tk.glucodata.Applic
import tk.glucodata.Natives
import tk.glucodata.mod.carelink.CareLinkClock
import tk.glucodata.mod.carelink.CareLinkNotifier
import tk.glucodata.mod.carelink.PumpSourceSwitch
import tk.glucodata.mod.pumpbt.ble.PumpLinkHealth
import java.util.concurrent.atomic.AtomicBoolean

/** What one watchdog round does with the link (series brief, decision 2). */
internal enum class WatchdogAction { NONE, ENSURE_ADVERTISING, READ_NOW, RECONNECT }

/**
 * One watchdog round as the rules see it. [connectedSeenAtMs] - the round that first saw the link connected (the
 * watchdog's own mark: the link does not tell since when it is connected); [remindedAtMs] - the last time the first
 * catch-up card was told (its notification or a reminder).
 */
internal data class WatchdogInput(
    val nowMs: Long,
    val sourceOn: Boolean,
    val health: PumpLinkHealth?,
    val connectedSeenAtMs: Long?,
    val cardWaiting: Boolean,
    val remindedAtMs: Long?,
)

/** The round's outcome: what to do, the marks to keep, whether to remind of the card and to check "no data". */
internal data class WatchdogDecision(
    val action: WatchdogAction,
    val connectedSeenAtMs: Long?,
    val remind: Boolean,
    val remindedAtMs: Long?,
    val checkNoData: Boolean,
    /** false - the source is off: the alarm goes. */
    val keepArmed: Boolean,
)

/**
 * The watchdog's rules (series brief, decision 2; plan B2c, point 2). Pure: no Android, no clock of its own.
 *
 * The pump connects by itself about every 8 min, stays about 7 min and leaves by its own timeout (journal of the
 * trial, 27.11): the link being gone between sessions is normal and only means "make sure we advertise". The 7 and
 * 15 min are counted only while the link is connected, from the later of the last read and the round that first saw
 * this connection - a read from the previous session must not make a fresh handshake look stale. Times in the future
 * (the phone's clock set back) count as now.
 */
internal object PumpBtWatchdogRules {
    const val TICK_MS = 5 * 60_000L
    const val READ_AFTER_MS = 7 * 60_000L
    const val RECONNECT_AFTER_MS = 15 * 60_000L
    const val REMIND_MS = 24 * 3_600_000L

    fun decide(i: WatchdogInput): WatchdogDecision {
        if (!i.sourceOn) {
            return WatchdogDecision(WatchdogAction.NONE, null, remind = false, remindedAtMs = i.remindedAtMs, checkNoData = false, keepArmed = false)
        }
        val now = i.nowMs
        val h = i.health
        var seen: Long? = null
        val action = if (h == null || !h.running || !h.connected) {
            WatchdogAction.ENSURE_ADVERTISING
        } else {
            val since = i.connectedSeenAtMs?.takeIf { it <= now } ?: now
            val lastRead = h.lastCycleAtMs?.takeIf { it <= now }
            val quiet = now - maxOf(lastRead ?: Long.MIN_VALUE, since)
            when {
                quiet >= RECONNECT_AFTER_MS -> WatchdogAction.RECONNECT // the link is dropped: the next connection is new
                quiet >= READ_AFTER_MS -> WatchdogAction.READ_NOW.also { seen = since }
                else -> WatchdogAction.NONE.also { seen = since }
            }
        }
        var reminded = i.remindedAtMs
        var remind = false
        if (i.cardWaiting) {
            val r = reminded
            when {
                // the source told it when the card was made; a card seen first here (a new process) - from now
                r == null || r > now -> reminded = now
                now - r >= REMIND_MS -> {
                    remind = true
                    reminded = now
                }
            }
        }
        return WatchdogDecision(action, seen, remind, reminded, checkNoData = true, keepArmed = true)
    }
}

/** What a watchdog round asks of the source and the notifications: an interface so tests need neither. */
internal interface PumpBtWatchdogActions {
    fun health(): PumpLinkHealth?
    fun cardWaiting(): Boolean
    fun ensureAdvertising(context: Context)
    fun readNow()
    fun reconnect()
    fun checkNoData(context: Context)
    fun remind(context: Context)
}

/** The real source, "no data" of the CareLink notifier and our card notification. */
internal object SourceWatchdogActions : PumpBtWatchdogActions {
    override fun health(): PumpLinkHealth? = PumpBtSource.linkHealth()
    override fun cardWaiting(): Boolean = PumpBtSource.state.value.let { it.backfill == BackfillState.WAITING && it.card != null }
    override fun ensureAdvertising(context: Context) = PumpBtSource.ensureAdvertising(context)
    override fun readNow() = PumpBtSource.readNow()
    override fun reconnect() = PumpBtSource.reconnect()
    override fun checkNoData(context: Context) = CareLinkNotifier.checkNoData(context)
    override fun remind(context: Context) = PumpBtNotifier.show(context, PumpBtAlert(PumpBtAlertKind.CONFIRM_BACKFILL))
}

/**
 * JugglucoNGmod, pump over Bluetooth (series part 2, part B2c): the watchdog of the Bluetooth pump source - its own
 * class, action, PendingIntent and request code (not the cloud's [tk.glucodata.mod.carelink.CareLinkScheduler]: the
 * cloud's alarm leaves with part D and must not take the watchdog with it). An exact alarm every 5 min
 * (`setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)`; without the exact-alarm right - `setAndAllowWhileIdle`; Doze
 * lets it through less often). Armed while the source is on: the starter follows [PumpBtSource.state] (enable, init,
 * disable all publish `enabled`) and an alarm with the source off removes itself. The round itself -
 * [PumpBtWatchdogRules], then [CareLinkNotifier.checkNoData] and the card reminder.
 */
internal object PumpBtWatchdog {

    const val ACTION = "tk.glucodata.mod.pumpbt.WATCHDOG"
    internal const val REQUEST_CODE = 1_947_020

    private const val PREFS = "mod_pumpbt_watchdog"
    private const val K_NEXT = "nextAlarmElapsedMs"
    private const val K_SEEN = "connectedSeenAtMs"
    private const val K_REMINDED = "cardRemindedAtMs"

    @Volatile internal var elapsed: () -> Long = { SystemClock.elapsedRealtime() }
    @Volatile internal var actions: PumpBtWatchdogActions = SourceWatchdogActions

    /**
     * After an app update, the source on: upstream's keep-alive service by the person's own setting (upstream has no
     * receiver of the update; their reboot path `Applic.possiblybluetooth` is not reachable from our package). Tests
     * put their own.
     */
    @Volatile internal var upstreamService: (Context) -> Unit = { c ->
        if (Applic.stopprogram == 0) Applic.updateservice(c, Natives.getusebluetooth())
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val following = AtomicBoolean(false)

    /** Once per process (the starter): the alarm follows the source's on/off. */
    fun follow(context: Context) {
        val app = context.applicationContext
        if (!following.compareAndSet(false, true)) return
        scope.launch {
            PumpBtSource.state.map { it.enabled }.distinctUntilChanged().collect { on ->
                try {
                    // the state starts "off" until init has read the switch: the switch decides
                    if (on || !PumpSourceSwitch.bluetoothOn(app)) sync(app)
                } catch (t: Throwable) {
                    PumpBtLog.w("watchdog not set (${t.javaClass.simpleName})")
                }
            }
        }
    }

    /** Armed when the source is on, removed when off. */
    fun sync(context: Context) {
        if (PumpSourceSwitch.bluetoothOn(context)) arm(context) else disarm(context)
    }

    /**
     * The next round in [PumpBtWatchdogRules.TICK_MS]. Without [force] an alarm already set within one tick is kept (a
     * process start does not push the round back). false - the source is off (nothing armed).
     */
    fun arm(context: Context, force: Boolean = false): Boolean {
        val app = context.applicationContext
        if (!PumpSourceSwitch.bluetoothOn(app)) {
            disarm(app)
            return false
        }
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = elapsed()
        val next = prefs.getLong(K_NEXT, 0L)
        if (!force && next > now && next - now <= PumpBtWatchdogRules.TICK_MS && intent(app, PendingIntent.FLAG_NO_CREATE) != null) {
            return true
        }
        val at = now + PumpBtWatchdogRules.TICK_MS
        setAlarm(app, at)
        prefs.edit().putLong(K_NEXT, at).apply()
        return true
    }

    /** The alarm and the marks go (the card's reminder time stays: the same card must not be told again at once). */
    fun disarm(context: Context) {
        val app = context.applicationContext
        try {
            val alarms = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            intent(app, PendingIntent.FLAG_NO_CREATE)?.let { p ->
                alarms?.cancel(p)
                p.cancel()
            }
        } catch (t: Throwable) {
            PumpBtLog.w("watchdog alarm not removed (${t.javaClass.simpleName})")
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(K_NEXT).remove(K_SEEN).apply()
    }

    /** One round (the alarm, main thread): the rules, then what they say. Returns the decision (tests). */
    fun tick(context: Context, nowMs: Long = CareLinkClock.now()): WatchdogDecision {
        val app = context.applicationContext
        PumpBtJournal.init(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = actions
        val d = PumpBtWatchdogRules.decide(
            WatchdogInput(
                nowMs = nowMs,
                sourceOn = PumpSourceSwitch.bluetoothOn(app),
                health = a.health(),
                connectedSeenAtMs = prefs.long(K_SEEN),
                cardWaiting = a.cardWaiting(),
                remindedAtMs = prefs.long(K_REMINDED),
            ),
        )
        if (!d.keepArmed) {
            disarm(app)
            return d
        }
        prefs.edit().apply {
            if (d.connectedSeenAtMs == null) remove(K_SEEN) else putLong(K_SEEN, d.connectedSeenAtMs)
            if (d.remindedAtMs == null) remove(K_REMINDED) else putLong(K_REMINDED, d.remindedAtMs)
        }.apply()
        when (d.action) {
            WatchdogAction.ENSURE_ADVERTISING -> a.ensureAdvertising(app)
            WatchdogAction.READ_NOW -> {
                PumpBtJournal.add("watchdog: connected, nothing read for ${PumpBtWatchdogRules.READ_AFTER_MS / 60_000} min - read now")
                a.readNow()
            }
            WatchdogAction.RECONNECT -> {
                PumpBtJournal.add("watchdog: connected, nothing read for ${PumpBtWatchdogRules.RECONNECT_AFTER_MS / 60_000} min - reconnect")
                a.reconnect()
            }
            WatchdogAction.NONE -> {}
        }
        if (d.checkNoData) {
            try {
                a.checkNoData(app)
            } catch (t: Throwable) {
                PumpBtLog.w("watchdog no-data check failed (${t.javaClass.simpleName})")
            }
        }
        if (d.remind) {
            PumpBtJournal.add("watchdog: the first catch-up card waits over a day - reminded")
            a.remind(app)
        }
        return d
    }

    /** The card was told now (the notifier): its reminder comes a day later. */
    fun cardTold(context: Context, nowMs: Long = CareLinkClock.now()) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(K_REMINDED, nowMs).apply()
    }

    /** After an app update (the update receiver): the link at once, upstream's service, the alarm. */
    fun afterUpdate(context: Context) {
        val app = context.applicationContext
        if (!PumpSourceSwitch.bluetoothOn(app)) return
        PumpBtJournal.init(app)
        PumpBtJournal.add("app updated: link and watchdog started again")
        try {
            actions.ensureAdvertising(app)
        } catch (t: Throwable) {
            PumpBtLog.w("link not started after the update (${t.javaClass.simpleName})")
        }
        try {
            upstreamService(app)
        } catch (t: Throwable) {
            PumpBtLog.w("upstream's service not started after the update (${t.javaClass.simpleName})")
        }
        try {
            arm(app, force = true)
        } catch (t: Throwable) {
            PumpBtLog.w("watchdog not set after the update (${t.javaClass.simpleName})")
        }
    }

    private fun android.content.SharedPreferences.long(key: String): Long? = if (contains(key)) getLong(key, 0L) else null

    private fun setAlarm(app: Context, atElapsedMs: Long) {
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pending = intent(app, 0)
        if (alarms == null || pending == null) {
            PumpBtLog.w("no alarm service: no pump watchdog")
            return
        }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        try {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, pending)
            }
        } catch (t: Throwable) {
            try {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, pending)
            } catch (t2: Throwable) {
                PumpBtLog.w("watchdog alarm refused (${t2.javaClass.simpleName})")
            }
        }
    }

    internal fun intent(app: Context, flags: Int): PendingIntent? {
        val i = Intent(app, PumpBtWatchdogReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(app, REQUEST_CODE, i, flags or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Back to defaults (tests). */
    internal fun resetForTest() {
        elapsed = { SystemClock.elapsedRealtime() }
        actions = SourceWatchdogActions
        upstreamService = { c -> if (Applic.stopprogram == 0) Applic.updateservice(c, Natives.getusebluetooth()) }
    }
}

/**
 * The watchdog's alarm: the next alarm first (the chain never breaks), then one round. The source off - the alarm goes.
 * Not exported (manifest): only our own PendingIntent sends it.
 */
class PumpBtWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PumpBtWatchdog.ACTION) return
        try {
            if (!PumpBtWatchdog.arm(context, force = true)) return
            PumpBtWatchdog.tick(context)
        } catch (t: Throwable) {
            PumpBtLog.w("pump watchdog failed (${t.javaClass.simpleName})")
        }
    }
}

/**
 * After an app update (`MY_PACKAGE_REPLACED`, journal of 27.11: the advertisement did not come back for 19 min): the
 * process has just started (our [ModPumpStarter] has called [PumpBtSource.init]); here the link is made sure of, upstream's
 * keep-alive service is started by the person's setting and the watchdog armed - all only with the source on.
 * Exported with its intent filter, as [tk.glucodata.mod.carelink.CareLinkBootReceiver] (Android 12+ wants the value
 * stated): the action is a protected broadcast only the system sends; anything else is ignored.
 */
class PumpBtUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            PumpBtWatchdog.afterUpdate(context)
        } catch (t: Throwable) {
            PumpBtLog.w("pump start after the update failed (${t.javaClass.simpleName})")
        }
    }
}
