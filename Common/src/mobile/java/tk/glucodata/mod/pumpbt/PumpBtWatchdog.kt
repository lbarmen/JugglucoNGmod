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
import kotlinx.coroutines.Job
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
 * One watchdog round as the rules see it. [connectedSeenAtMs] - the watchdog's own mark, as the link does not tell since
 * when it is connected: the first round that saw the link connected after the last round that saw it not connected. A
 * gap between two pump sessions that no round saw (rounds every 5 min, a gap of about a minute) keeps the older mark;
 * accepted - the last read is usually fresher (critic B2c, N2). [remindedAtMs] - the last time the first catch-up card
 * was told (its notification or a reminder). [noAdvertSeenAtMs] - the previous round saw the link running, neither
 * connected nor advertising (critic B2c, N1: the advertisement is mended only when two rounds in a row see it gone).
 * [bondLostUntold] - the source marks "the phone forgot the pump", but its notification was not delivered.
 */
internal data class WatchdogInput(
    val nowMs: Long,
    val sourceOn: Boolean,
    val health: PumpLinkHealth?,
    val connectedSeenAtMs: Long?,
    val cardWaiting: Boolean,
    val remindedAtMs: Long?,
    val noAdvertSeenAtMs: Long? = null,
    val bondLostUntold: Boolean = false,
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
    /** The mark of a round that saw no advertisement (kept for the next round). */
    val noAdvertSeenAtMs: Long? = null,
    /** How long nothing was read while connected (READ_NOW, RECONNECT: the link journal line). */
    val quietMs: Long? = null,
    /**
     * The bond is checked again ([PumpBtSource.recheckBond]): "the phone forgot the pump" was not delivered and no pump
     * is connected - the check tells it again, and marks it told only when shown (critic of the joints 2, N-2).
     */
    val recheckBond: Boolean = false,
)

/**
 * The watchdog's rules (series brief, decision 2; plan B2c, point 2). Pure: no Android, no clock of its own.
 *
 * The pump connects by itself about every 8 min, stays about 7 min and leaves by its own timeout (journal of the
 * trial, 27.11): the link being gone between sessions is normal and only means "make sure we advertise". A link that
 * does not run is started at once; a running link that neither is connected nor advertises is advertised again only
 * when the previous round saw the same - while it adds its services, waits for the advertisement to start or pauses to
 * recover, the flag is false for a moment (critic B2c, N1). The 7 and 15 min are counted only while the link is
 * connected, from the later of the last read and the round that first saw this connection - a read from the previous
 * session must not make a fresh handshake look stale. Times in the future (the phone's clock set back) count as now.
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
        var noAdvert: Long? = null
        var quietMs: Long? = null
        val action = when {
            h == null || !h.running -> WatchdogAction.ENSURE_ADVERTISING // started at once
            !h.connected && h.advertising -> WatchdogAction.ENSURE_ADVERTISING // advertising: the source leaves it alone
            !h.connected -> if (i.noAdvertSeenAtMs != null) {
                WatchdogAction.ENSURE_ADVERTISING // the second round in a row without an advertisement
            } else {
                WatchdogAction.NONE.also { noAdvert = now }
            }
            else -> {
                val since = i.connectedSeenAtMs?.takeIf { it <= now } ?: now
                val lastRead = h.lastCycleAtMs?.takeIf { it <= now }
                val quiet = now - maxOf(lastRead ?: Long.MIN_VALUE, since)
                when {
                    // the link is dropped: the next connection is new
                    quiet >= RECONNECT_AFTER_MS -> WatchdogAction.RECONNECT.also { quietMs = quiet }
                    quiet >= READ_AFTER_MS -> WatchdogAction.READ_NOW.also { seen = since; quietMs = quiet }
                    else -> WatchdogAction.NONE.also { seen = since }
                }
            }
        }
        var reminded = i.remindedAtMs
        var remind = false
        if (i.cardWaiting) {
            val r = reminded
            when {
                // no mark: the card was never shown (notifications off when it came, the process died before its
                // notification, an import cleared the marks) - told in this round, not a day later (critic of the
                // joints 2, N-1); a reminder not shown keeps no mark and is tried again next round
                r == null -> {
                    remind = true
                    reminded = now
                }
                // told "in the future" (the phone's clock set back): from now
                r > now -> reminded = now
                now - r >= REMIND_MS -> {
                    remind = true
                    reminded = now
                }
            }
        }
        // a connected pump is paired: its handshake clears the mark anyway
        val recheck = i.bondLostUntold && (h == null || !h.running || !h.connected)
        return WatchdogDecision(
            action, seen, remind, reminded, checkNoData = true, keepArmed = true, noAdvertSeenAtMs = noAdvert, quietMs = quietMs,
            recheckBond = recheck,
        )
    }

    /**
     * The journal line of the first arm in a process (a process start or the source turned on): whether the alarm was
     * set, the round's time kept or new, and in how long it comes - so a journal shows the watchdog alive after a restart.
     */
    fun armLine(set: Boolean, kept: Boolean, inMs: Long): String {
        if (!set) return "watchdog: the system refused the alarm - no rounds until the app starts again"
        val s = (inMs.coerceAtLeast(0L) + 999) / 1000
        val time = if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s"
        return "watchdog set: next round in $time (${if (kept) "time kept" else "new time"})"
    }
}

/** What a watchdog round asks of the source and the notifications: an interface so tests need neither. */
internal interface PumpBtWatchdogActions {
    fun health(): PumpLinkHealth?
    fun cardWaiting(): Boolean

    /** The link runs (main thread). */
    fun linkRunning(): Boolean

    /** A stopped link is started; a running one, also one still setting up, is left alone. */
    fun startLink(context: Context)
    fun ensureAdvertising(context: Context)
    fun readNow()
    fun reconnect()
    fun checkNoData(context: Context)

    /** The card's reminder; throws when it was not shown. */
    fun remind(context: Context)

    /** "The phone forgot the pump" is marked, but was not delivered. */
    fun bondLostUntold(): Boolean

    /** The bond checked again (off the main thread, under the source's mutex; the link is not touched). */
    fun recheckBond(context: Context)
}

/** The real source, "no data" of the CareLink notifier and our card notification. */
internal object SourceWatchdogActions : PumpBtWatchdogActions {
    override fun health(): PumpLinkHealth? = PumpBtSource.linkHealth()
    override fun cardWaiting(): Boolean = PumpBtSource.state.value.let { it.backfill == BackfillState.WAITING && it.card != null }
    override fun linkRunning(): Boolean = PumpBtSource.linkRunning

    /**
     * [PumpBtSource.init]: once the memory is loaded, on the main thread, it starts the link only when it does not run
     * then. Not [PumpBtSource.ensureAdvertising]: after an update the starter's init may have started the link a moment
     * before, and a link still adding its services does not advertise yet - ensureAdvertising would drop it (critic
     * B2c, N1).
     */
    override fun startLink(context: Context) {
        PumpBtSource.init(context)
    }

    override fun ensureAdvertising(context: Context) = PumpBtSource.ensureAdvertising(context)
    override fun readNow() = PumpBtSource.readNow()
    override fun reconnect() = PumpBtSource.reconnect()
    override fun checkNoData(context: Context) = CareLinkNotifier.checkNoData(context)
    override fun remind(context: Context) = PumpBtNotifier.remindCard(context)
    override fun bondLostUntold(): Boolean = PumpBtSource.bondLostUntold()
    override fun recheckBond(context: Context) {
        PumpBtSource.recheckBond(context)
    }
}

/**
 * JugglucoNGmod, pump over Bluetooth (series part 2, part B2c): the watchdog of the Bluetooth pump source - its own
 * class, action, PendingIntent and request code, apart from the removed CareLink cloud poll (the one-time cleanup,
 * [tk.glucodata.mod.carelink.CareLinkCloudCleanup], cancels only the cloud's old alarm). An exact alarm every 5 min
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
    private const val K_NO_ADVERT = "noAdvertSeenAtMs"

    @Volatile internal var elapsed: () -> Long = { SystemClock.elapsedRealtime() }
    @Volatile internal var actions: PumpBtWatchdogActions = SourceWatchdogActions

    /**
     * After an app update, the source on: upstream's keep-alive service, only when the person gets the sensor over
     * Bluetooth (upstream has no receiver of the update; their reboot path `Applic.possiblybluetooth` is not reachable
     * from our package). Not otherwise: upstream's `updateservice(c, false)` may stop its service and with it the
     * "sensor signal lost" alarm (critic B2c, N3). Tests put their own.
     */
    @Volatile internal var upstreamService: (Context) -> Unit = ::startUpstreamService

    private fun startUpstreamService(c: Context) {
        if (Applic.stopprogram == 0 && Natives.getusebluetooth()) Applic.updateservice(c, true)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val following = AtomicBoolean(false)
    @Volatile private var followJob: Job? = null

    /** Once per process (the starter): the alarm follows the source's on/off. */
    fun follow(context: Context) {
        val app = context.applicationContext
        if (!following.compareAndSet(false, true)) return
        followJob = scope.launch {
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
     * The next round in [PumpBtWatchdogRules.TICK_MS]. Without [force] a round already due within one tick keeps its
     * time (a process start does not push the round back), but the alarm is set again for that time: a force stop
     * removes the app's alarms while Android 14 keeps its PendingIntent, so "the PendingIntent is there" does not prove
     * the alarm is (pump journal 28.11: after a force stop at 09:59 no round came for hours, and with the pump connected
     * and quiet nothing was read). Setting the same PendingIntent again replaces the alarm - never two. The first such
     * arm of a process with the source on writes a journal line. false - the source is off (nothing armed).
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
        val kept = !force && next > now && next - now <= PumpBtWatchdogRules.TICK_MS
        val at = if (kept) next else now + PumpBtWatchdogRules.TICK_MS
        // the time is kept only for an alarm that was set: a refused one is tried again at the next arm (critic B2c, N7)
        val set = setAlarm(app, at)
        if (set) prefs.edit().putLong(K_NEXT, at).apply() else prefs.edit().remove(K_NEXT).apply()
        if (!force && journaledArm.compareAndSet(false, true)) {
            PumpBtJournal.init(app)
            PumpBtJournal.add(PumpBtWatchdogRules.armLine(set, kept, at - now))
        }
        return true
    }

    /** The first arm without force in this process was written to the journal (one line per process start). */
    private val journaledArm = AtomicBoolean(false)

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
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(K_NEXT).remove(K_SEEN).remove(K_NO_ADVERT).apply()
    }

    /** One round (the alarm, main thread): the rules, then what they say. Returns the decision (tests). */
    fun tick(context: Context, nowMs: Long = CareLinkClock.now()): WatchdogDecision {
        val app = context.applicationContext
        PumpBtJournal.init(app)
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val a = actions
        val remindedBefore = prefs.long(K_REMINDED)
        val d = PumpBtWatchdogRules.decide(
            WatchdogInput(
                nowMs = nowMs,
                sourceOn = PumpSourceSwitch.bluetoothOn(app),
                health = a.health(),
                connectedSeenAtMs = prefs.long(K_SEEN),
                cardWaiting = a.cardWaiting(),
                remindedAtMs = remindedBefore,
                noAdvertSeenAtMs = prefs.long(K_NO_ADVERT),
                bondLostUntold = try {
                    a.bondLostUntold()
                } catch (t: Throwable) {
                    false
                },
            ),
        )
        if (!d.keepArmed) {
            disarm(app)
            return d
        }
        prefs.edit().apply {
            if (d.connectedSeenAtMs == null) remove(K_SEEN) else putLong(K_SEEN, d.connectedSeenAtMs)
            if (d.remindedAtMs == null) remove(K_REMINDED) else putLong(K_REMINDED, d.remindedAtMs)
            if (d.noAdvertSeenAtMs == null) remove(K_NO_ADVERT) else putLong(K_NO_ADVERT, d.noAdvertSeenAtMs)
        }.apply()
        val quietMin = (d.quietMs ?: 0L) / 60_000
        when (d.action) {
            WatchdogAction.ENSURE_ADVERTISING -> a.ensureAdvertising(app)
            WatchdogAction.READ_NOW -> {
                PumpBtJournal.add("watchdog: connected, nothing read for $quietMin min - read now")
                a.readNow()
            }
            WatchdogAction.RECONNECT -> {
                PumpBtJournal.add("watchdog: connected, nothing read for $quietMin min - reconnect")
                a.reconnect()
            }
            WatchdogAction.NONE -> {}
        }
        if (d.recheckBond) {
            try {
                a.recheckBond(app)
            } catch (t: Throwable) {
                PumpBtLog.w("watchdog: bond not checked again (${t.javaClass.simpleName})")
            }
        }
        if (d.checkNoData) {
            try {
                a.checkNoData(app)
            } catch (t: Throwable) {
                PumpBtLog.w("watchdog no-data check failed (${t.javaClass.simpleName})")
            }
        }
        if (d.remind) {
            try {
                a.remind(app)
                PumpBtJournal.add("watchdog: the first catch-up card waits over a day - reminded")
            } catch (t: Throwable) {
                // not shown (notifications off): the old time stays, the next round tries again (critic B2c, C1)
                prefs.edit().apply { if (remindedBefore == null) remove(K_REMINDED) else putLong(K_REMINDED, remindedBefore) }.apply()
                PumpBtLog.w("watchdog: card reminder not shown (${t.javaClass.simpleName})")
            }
        }
        return d
    }

    /** The card was shown now (the notifier): its reminder comes a day later. */
    fun cardTold(context: Context, nowMs: Long = CareLinkClock.now()) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(K_REMINDED, nowMs).apply()
    }

    /**
     * After an app update (the update receiver): the link, only when it does not run (critic B2c, N1: the starter's
     * init may already have started it), upstream's service, the alarm. The journal line names which it was.
     */
    fun afterUpdate(context: Context) {
        val app = context.applicationContext
        if (!PumpSourceSwitch.bluetoothOn(app)) return
        PumpBtJournal.init(app)
        val a = actions
        // the line says what was done (critic of the joints 2, N-6): the link was running already, or started now
        val line = try {
            if (a.linkRunning()) {
                "app updated: the link was running already, watchdog set again"
            } else {
                a.startLink(app)
                "app updated: the link was not running - starting it, watchdog set again"
            }
        } catch (t: Throwable) {
            PumpBtLog.w("link not started after the update (${t.javaClass.simpleName})")
            "app updated: link not started (${t.javaClass.simpleName}), watchdog set again"
        }
        PumpBtJournal.add(line)
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

    /** false - no alarm was set (no alarm service, or both calls refused). */
    private fun setAlarm(app: Context, atElapsedMs: Long): Boolean {
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pending = intent(app, 0)
        if (alarms == null || pending == null) {
            PumpBtLog.w("no alarm service: no pump watchdog")
            return false
        }
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()
        return try {
            if (exact) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, pending)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, pending)
            }
            true
        } catch (t: Throwable) {
            try {
                alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, atElapsedMs, pending)
                true
            } catch (t2: Throwable) {
                PumpBtLog.w("watchdog alarm refused (${t2.javaClass.simpleName})")
                false
            }
        }
    }

    internal fun intent(app: Context, flags: Int): PendingIntent? {
        val i = Intent(app, PumpBtWatchdogReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(app, REQUEST_CODE, i, flags or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Back to defaults (tests): also the following of the source, so that a test can start it again. */
    internal fun resetForTest() {
        elapsed = { SystemClock.elapsedRealtime() }
        actions = SourceWatchdogActions
        upstreamService = ::startUpstreamService
        followJob?.cancel()
        followJob = null
        following.set(false)
        journaledArm.set(false)
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
 * process has just started (our [ModPumpStarter] has called [PumpBtSource.init]); here the link is started when it does
 * not run, upstream's keep-alive service is started when the sensor comes over Bluetooth and the watchdog armed - all
 * only with the source on. Exported because of its intent filter (Android 12+ wants the value stated): the action is a
 * protected broadcast only the system sends; anything else is ignored. After a reboot upstream's own boot receiver
 * starts the process and [ModPumpStarter] arms the watchdog.
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
