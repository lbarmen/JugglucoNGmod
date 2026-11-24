package tk.glucodata.mod.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import tk.glucodata.MessageSender

/**
 * JugglucoNGmod, watch block (part 2): `/mod/tick` from the phone to our watch app - the phone battery for the watch
 * face and the wake-up after which the watch hands over its heart rate and steps (one alarm for both, watch plan,
 * decision 5). The watch has no alarm of its own: Android keeps an app that is never opened in rare standby buckets,
 * while a Data Layer message wakes it at once.
 *
 * Sent by the 5-minute cycle ([WatchTickWorker]), at the app's start ([ModWatchStarter]), when our watch app appears
 * ([ModPhoneListener]) and when the battery moved [BATTERY_STEP] points from the last tick sent (at most once a
 * [MIN_EXTRA_GAP_MS], while the process lives, and not while no watch answered - [WatchTickMemory]) or the pump's active
 * insulin changed ([PumpIobPhone], which also fills the tick's active insulin field). The watch is every
 * reachable node with the watch app's capability: the Data Layer links only apps of the same package and signature, so
 * a node found is our watch app. Nothing goes while the user has the watch companion switched off on the phone (as
 * upstream's own sender). The log has counts and reason codes only, never the battery.
 */
object WatchTicker {

    /** A battery move this big (percentage points) from the last tick sent sends an extra tick. */
    const val BATTERY_STEP = 5

    /** Extra ticks (battery, watch appeared) - not within this of the last tick. */
    const val MIN_EXTRA_GAP_MS = 60_000L

    private const val WAIT_SECONDS = 10L

    private val memory = WatchTickMemory()

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ModWatchTick").apply { isDaemon = true } }

    /**
     * Sends a tick with [reason] to our watch app; blocks up to a few seconds (a worker thread, never the main thread
     * or the listener's). Returns true when a watch was found, false when none is reachable, the companion is off or
     * the lookup failed. Never throws.
     */
    fun send(
        context: Context,
        reason: Int,
        nowMs: Long,
        battery: ModBattery.Reading? = ModBattery.read(context),
    ): Boolean {
        memory.attempted(nowMs)
        try {
            if (!MessageSender.outgoingAllowed()) {
                memory.noWatch()
                ModWatchLog.i("tick $reason: watch companion off")
                return false
            }
            val nodes = Tasks.await(
                Wearable.getCapabilityClient(context)
                    .getCapability(ModWatchMessages.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE),
                WAIT_SECONDS, TimeUnit.SECONDS,
            ).nodes
            if (nodes.isEmpty()) {
                memory.noWatch()
                ModWatchLog.i("tick $reason: no watch")
                return false
            }
            memory.watchFound()
            // the pump's active insulin for the watch face (only the pump's number; none - the watch drops its copy)
            val iob = PumpIobPhone.forTick(context, nowMs)
            val data = ModWatchMessages.encodeTick(
                ModWatchMessages.Tick(battery?.percent, battery?.charging == true, nowMs, reason, iob)
            )
            val client = Wearable.getMessageClient(context)
            val reached = ArrayList<String>(nodes.size)
            for (node in nodes) {
                try {
                    Tasks.await(client.sendMessage(node.id, ModWatchMessages.PATH_TICK, data), WAIT_SECONDS, TimeUnit.SECONDS)
                    reached += node.id
                } catch (t: Throwable) {
                    ModWatchLog.w("tick $reason: node failed: ${t.javaClass.simpleName}")
                }
            }
            val sent = reached.size
            if (sent > 0) memory.delivered(nowMs, battery?.percent)
            ModWatchLog.i("tick $reason: $sent of ${nodes.size}")
            // parts 7b, 7d: the alert settings to the watches the tick reached, when due (never throws)
            WatchAlertSettings.sendTo(client, reached, "tick $reason")
            return true
        } catch (t: Throwable) {
            ModWatchLog.w("tick $reason failed: ${t.javaClass.simpleName}")
            return false
        }
    }

    /** [send] on our own background thread; returns at once. */
    fun sendAsync(context: Context, reason: Int, battery: ModBattery.Reading? = null) {
        val app = context.applicationContext ?: context
        try {
            worker.execute {
                val now = System.currentTimeMillis()
                if (battery == null) send(app, reason, now) else send(app, reason, now, battery)
            }
        } catch (t: Throwable) {
            ModWatchLog.w("tick $reason not queued: ${t.javaClass.simpleName}")
        }
    }

    /**
     * The app's process started (posted to the main thread by [ModWatchStarter], so after Application.onCreate): a
     * start tick on our thread, then the cycle - started afresh only when a watch answered and the queued step waits
     * the long hour ([WatchTickWorker.replaceOnStart]), kept as it is otherwise: a step that woke the process is
     * running now and queues the next itself.
     */
    fun onAppStart(context: Context) {
        val app = context.applicationContext ?: context
        try {
            worker.execute {
                try {
                    val found = send(app, ModWatchMessages.REASON_START, System.currentTimeMillis())
                    val waitsLong = if (found) WatchTickWorker.queuedWaitsLong(app) else null
                    if (WatchTickWorker.replaceOnStart(found, waitsLong)) WatchTickWorker.restart(app)
                    else WatchTickWorker.ensureScheduled(app)
                } catch (t: Throwable) {
                    ModWatchLog.w("start tick failed: ${t.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("start tick not queued: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Our watch app became reachable (ModPhoneListener's CAPABILITY_CHANGED): on our own thread, so the listener's
     * single thread stays free for `/mod/snooze` - an extra tick unless one reached a watch within [MIN_EXTRA_GAP_MS],
     * and the cycle back to every 5 minutes unless a 5-minute step is already waiting
     * ([WatchTickWorker.replaceOnWatchAppeared]). Returns at once.
     */
    fun onWatchAppeared(context: Context) {
        val app = context.applicationContext ?: context
        memory.watchAppeared()
        WatchAlertSettings.forget() // part 7d: the next tick brings the alert settings
        try {
            worker.execute {
                try {
                    val now = System.currentTimeMillis()
                    if (memory.watchTickDue(now)) send(app, ModWatchMessages.REASON_WATCH, now)
                    if (WatchTickWorker.replaceOnWatchAppeared(WatchTickWorker.queuedWaitsLong(app))) {
                        WatchTickWorker.restart(app)
                    }
                } catch (t: Throwable) {
                    ModWatchLog.w("watch tick failed: ${t.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("watch tick not queued: ${t.javaClass.simpleName}")
        }
    }

    /** A battery broadcast (main thread): an extra tick when [WatchTickMemory.batteryTickDue] says so. */
    fun onBatteryChanged(context: Context, battery: ModBattery.Reading?, nowMs: Long) {
        if (battery == null) return
        if (!memory.batteryTickDue(battery.percent, nowMs)) return
        memory.attempted(nowMs) // claimed now: broadcasts that come before the send runs queue no second tick
        sendAsync(context, ModWatchMessages.REASON_BATTERY, battery)
    }

    /**
     * An extra tick for the battery: it moved at least [BATTERY_STEP] points from [lastSentPercent] (the last tick
     * that reached a watch; none yet - no extra tick, the next cycle carries it) and no tick went within
     * [MIN_EXTRA_GAP_MS] of [nowMs].
     */
    fun shouldSendForBattery(percent: Int, lastSentPercent: Int?, nowMs: Long, lastTickAtMs: Long): Boolean {
        if (lastSentPercent == null) return false
        if (Math.abs(percent - lastSentPercent) < BATTERY_STEP) return false
        return gapPassed(nowMs, lastTickAtMs)
    }

    /** The last tick attempt of any reason in this process (0 - none): the pump's extra ticks keep a minute from it. */
    fun lastTickAtMs(): Long = memory.lastAttemptAtMs

    /** [MIN_EXTRA_GAP_MS] has passed since [lastMs] (a clock set back counts as passed). */
    fun gapPassed(nowMs: Long, lastMs: Long): Boolean = nowMs - lastMs !in 0 until MIN_EXTRA_GAP_MS

    /** Listens to the battery for the life of the process (ACTION_BATTERY_CHANGED is only sent to registered receivers). */
    fun registerBatteryReceiver(context: Context) {
        val app = context.applicationContext ?: context
        ContextCompat.registerReceiver(
            app, BatteryReceiver(), IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private class BatteryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            try {
                onBatteryChanged(context, ModBattery.fromIntent(intent), System.currentTimeMillis())
            } catch (t: Throwable) {
                ModWatchLog.w("battery receiver failed: ${t.javaClass.simpleName}")
            }
        }
    }
}

/**
 * What [WatchTicker] remembers in this process and its rules for the extra ticks, apart from the Data Layer (tests
 * drive it directly). Fields are read and written from the main thread (battery) and our worker thread; a race costs
 * at most one extra tick.
 */
class WatchTickMemory {

    /** Battery percent of the last tick that reached a watch; `null` before the first one in this process. */
    @Volatile
    var lastSentPercent: Int? = null
        private set

    /** Time of the last tick attempt of any reason, or of a battery tick claimed before it runs. */
    @Volatile
    var lastAttemptAtMs = 0L
        private set

    /** Time of the last tick that reached a watch. */
    @Volatile
    var lastDeliveredAtMs = 0L
        private set

    /** The last attempt found no watch (or the companion off): battery ticks wait for a watch. */
    @Volatile
    var watchMissing = false
        private set

    fun attempted(nowMs: Long) {
        lastAttemptAtMs = nowMs
    }

    fun noWatch() {
        watchMissing = true
    }

    /** A watch answered the lookup, whether or not the message then reached it. */
    fun watchFound() {
        watchMissing = false
    }

    /** Our watch app's capability appeared (CAPABILITY_CHANGED). */
    fun watchAppeared() {
        watchMissing = false
    }

    fun delivered(nowMs: Long, percent: Int?) {
        lastDeliveredAtMs = nowMs
        lastSentPercent = percent
        watchMissing = false
    }

    /**
     * A battery extra tick is due: a watch was there at the last attempt and [WatchTicker.shouldSendForBattery] (the
     * gap counts from any attempt, so a tick claimed and not yet run holds the next broadcasts back).
     */
    fun batteryTickDue(percent: Int, nowMs: Long): Boolean =
        !watchMissing && WatchTicker.shouldSendForBattery(percent, lastSentPercent, nowMs, lastAttemptAtMs)

    /** The "watch appeared" tick is due: none reached a watch within [WatchTicker.MIN_EXTRA_GAP_MS] - failed attempts do not count. */
    fun watchTickDue(nowMs: Long): Boolean = WatchTicker.gapPassed(nowMs, lastDeliveredAtMs)
}
