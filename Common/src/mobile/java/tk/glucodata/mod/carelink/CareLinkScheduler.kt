package tk.glucodata.mod.carelink

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * JugglucoNGmod, CareLink (stage 4c): when to poll. A chain of exact alarms every [CareLinkSettings.intervalMin]
 * (`setExactAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP)`; without the exact-alarm right - `setAndAllowWhileIdle`), as
 * upstream's NightscoutFollowerManager.setPollAlarm. The alarm does not poll itself ([CareLinkAlarmReceiver]: the
 * broadcast has ~10 s, a poll with a key renewal takes longer): it arms the next alarm and puts a one-time
 * [CareLinkWorker] in WorkManager. Safety net - a unique periodic [CareLinkWorker] every 15 min (network required), as
 * upstream's LumiflexCloudSyncWorker; it also restores a broken chain.
 *
 * Nothing is scheduled without a session ([CareLinkAuth.hasSession], by the file), with polling off or while the
 * Bluetooth pump source is on ([PumpSourceSwitch.bluetoothOn]; its own watchdog is not here). Cheap and
 * repeatable: the starter calls [arm] on every process start; an app that never signed in to CareLink touches neither
 * AlarmManager nor WorkManager.
 */
object CareLinkScheduler {

    const val ACTION_POLL = "tk.glucodata.mod.carelink.POLL"

    internal const val WORK_POLL = "mod-carelink-poll"
    internal const val WORK_NOW = "mod-carelink-now"
    internal const val WORK_PERIODIC = "mod-carelink-periodic"
    internal const val KEY_REASON = "reason"
    internal const val KEY_FIRST = "firstPoll"

    private const val PREFS = "mod_carelink_schedule"
    private const val K_NEXT = "nextAlarmElapsedMs"
    private const val K_LAST_ALARM = "lastAlarmAtMs"
    private const val K_ARMED = "armed"
    private const val REQUEST_CODE = 1_947_000

    /** WorkManager; tests put a recording fake here. */
    @Volatile
    internal var work: CareLinkWork = SystemCareLinkWork

    @Volatile
    internal var elapsed: () -> Long = { SystemClock.elapsedRealtime() }

    /**
     * Arms the chain: the next alarm in one interval and the periodic safety work. Without [force] an alarm already
     * set within one interval is kept (a process start does not push the poll back). false - nothing to arm (off or
     * no session); then whatever was armed is removed.
     */
    fun arm(context: Context, force: Boolean = false): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val settings = CareLinkSettings(app)
        // the Bluetooth pump source is on: the cloud is silent, its alarms and work go (series brief, decision 17)
        if (!settings.enabled || !CareLinkAuth.hasSession(app) || PumpSourceSwitch.bluetoothOn(app)) {
            // off with a live session: the 15-min safety work must not wake the app for nothing even when the "armed"
            // mark is gone (upstream's settings import clears it; critic of the joints, B1)
            if (prefs.getBoolean(K_ARMED, false) || CareLinkAuth.hasSession(app)) disarm(app)
            return false
        }
        try {
            work.ensurePeriodic(app)
        } catch (t: Throwable) {
            CareLinkLog.w("safety work not scheduled (${t.javaClass.simpleName})")
        }
        val now = elapsed()
        val interval = settings.intervalMin * 60_000L
        val next = prefs.getLong(K_NEXT, 0L)
        if (!force && next > now && next - now <= interval && pollIntent(app, PendingIntent.FLAG_NO_CREATE) != null) {
            return true
        }
        val at = now + interval
        setAlarm(app, at)
        prefs.edit().putLong(K_NEXT, at).putBoolean(K_ARMED, true).apply()
        return true
    }

    /**
     * The alarm came at [nowMs] (wall clock, as the poll times): stored; the previous alarm's time returned (null - none
     * since the chain was armed after a sign-in). For the alarm's "no data" check (critic 4d2, N3).
     */
    fun markAlarm(context: Context, nowMs: Long): Long? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = if (prefs.contains(K_LAST_ALARM)) prefs.getLong(K_LAST_ALARM, 0L) else null
        prefs.edit().putLong(K_LAST_ALARM, nowMs).apply()
        return prev
    }

    /** Removes the alarm and the periodic safety work. A one-time poll already queued runs and finds nothing to do. */
    fun disarm(context: Context) {
        val app = context.applicationContext
        try {
            val alarms = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            pollIntent(app, PendingIntent.FLAG_NO_CREATE)?.let { p ->
                alarms?.cancel(p)
                p.cancel()
            }
        } catch (t: Throwable) {
            CareLinkLog.w("alarm not removed (${t.javaClass.simpleName})")
        }
        try {
            work.cancelPeriodic(app)
        } catch (t: Throwable) {
            CareLinkLog.w("safety work not removed (${t.javaClass.simpleName})")
        }
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * A one-time poll in WorkManager (network required). Alarm - kept if one is already waiting; sign-in and the
     * button replace a waiting one of their own. null - off, no session or the Bluetooth pump source on: nothing queued.
     */
    fun enqueuePoll(context: Context, reason: PollReason, firstPoll: Boolean = false): Operation? {
        val app = context.applicationContext
        if (!CareLinkSettings(app).enabled || !CareLinkAuth.hasSession(app) || PumpSourceSwitch.bluetoothOn(app)) return null
        return work.enqueue(app, reason, firstPoll)
    }

    private fun setAlarm(app: Context, atElapsedMs: Long) {
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        val pending = pollIntent(app, 0)
        if (alarms == null || pending == null) {
            CareLinkLog.w("no alarm service: the 15-min safety work polls alone")
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
                CareLinkLog.w("alarm refused (${t2.javaClass.simpleName}): the 15-min safety work polls alone")
            }
        }
    }

    private fun pollIntent(app: Context, flags: Int): PendingIntent? {
        val intent = Intent(app, CareLinkAlarmReceiver::class.java).setAction(ACTION_POLL)
        return PendingIntent.getBroadcast(
            app, REQUEST_CODE, intent, flags or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Back to defaults (tests). */
    internal fun resetForTest() {
        work = SystemCareLinkWork
        elapsed = { SystemClock.elapsedRealtime() }
    }
}

/** What [CareLinkScheduler] asks of WorkManager - an interface so tests need no WorkManager. */
internal interface CareLinkWork {
    fun ensurePeriodic(app: Context)
    fun cancelPeriodic(app: Context)
    fun enqueue(app: Context, reason: PollReason, firstPoll: Boolean): Operation?
}

/** The real WorkManager (upstream's Applic gives its configuration). */
internal object SystemCareLinkWork : CareLinkWork {

    private val network: Constraints
        get() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    override fun ensurePeriodic(app: Context) {
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            CareLinkScheduler.WORK_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CareLinkWorker>(15, TimeUnit.MINUTES).setConstraints(network).build(),
        )
    }

    override fun cancelPeriodic(app: Context) {
        WorkManager.getInstance(app).cancelUniqueWork(CareLinkScheduler.WORK_PERIODIC)
    }

    override fun enqueue(app: Context, reason: PollReason, firstPoll: Boolean): Operation {
        // the person's own polls do not wait behind an alarm's; a sign-in and the button replace a waiting one
        val byPerson = reason == PollReason.LOGIN || reason == PollReason.MANUAL || reason == PollReason.FOREGROUND
        val replace = reason == PollReason.LOGIN || reason == PollReason.MANUAL
        val input = Data.Builder()
            .putString(CareLinkScheduler.KEY_REASON, reason.name)
            .putBoolean(CareLinkScheduler.KEY_FIRST, firstPoll)
            .build()
        return WorkManager.getInstance(app).enqueueUniqueWork(
            if (byPerson) CareLinkScheduler.WORK_NOW else CareLinkScheduler.WORK_POLL,
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<CareLinkWorker>().setConstraints(network).setInputData(input).build(),
        )
    }
}
