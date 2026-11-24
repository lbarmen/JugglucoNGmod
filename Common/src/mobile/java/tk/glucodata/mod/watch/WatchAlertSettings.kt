package tk.glucodata.mod.watch

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import tk.glucodata.MessageSender

/**
 * JugglucoNGmod, watch block (parts 7b, 7d): the phone's alert settings to our watch app ([ModAlertSettings],
 * `/mod/alerts`).
 *
 * With a tick ([WatchTicker.send] hands over the watches the tick reached) only when the settings differ from what
 * that watch last got, when it got nothing yet in this process or was out of reach since (a new or returning watch),
 * and once an [RESEND_MS] for safety ([AlertSendMemory]) - so the alert settings no longer ride along every 5 minutes.
 * And always [CHANGE_DELAY_MS] after the last change of a carried key or of an alert's on/off in upstream's alert
 * preferences ([ModAlertSettings.triggersSend]), so a slider drag goes once and upstream's own toggle push (at once)
 * lands on the watch before ours. Each message carries its send time ([SendStamps]: never back while the process
 * lives, so a phone clock corrected backwards cannot make the watch drop a change it counts as delivered): the watch
 * drops one overtaken by a newer one. Nothing goes while the user has the watch companion switched off (as upstream's
 * own sender). The log has counts only.
 */
object WatchAlertSettings {

    /** After the last change of a sent setting, the settings go to the watch this much later. */
    const val CHANGE_DELAY_MS = 3_000L

    /** Unchanged settings go to a watch again after this long. */
    const val RESEND_MS = 60 * 60_000L

    private const val WAIT_SECONDS = 10L

    private val memory = AlertSendMemory()

    private val stamps = SendStamps()

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ModWatchAlerts").apply { isDaemon = true }
    }

    @Volatile
    private var pending: ScheduledFuture<*>? = null

    /** Kept here: SharedPreferences holds its listeners weakly. */
    @Volatile
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /**
     * Sends the current settings with [client] to those of [nodeIds] that are due ([force]: all of them); [nodeIds]
     * are all the watches reachable now - one missing is forgotten. [label] names the occasion in the log. Blocks up
     * to a few seconds per watch (a worker thread). Returns how many watches got it; never throws.
     */
    fun sendTo(client: MessageClient, nodeIds: Collection<String>, label: String, force: Boolean = false): Int {
        return try {
            memory.keepOnly(nodeIds)
            if (nodeIds.isEmpty()) return 0
            val now = System.currentTimeMillis()
            // read and stamped together: a later read always has a later stamp, whichever send finishes first
            val (settings, stamp) = synchronized(stamps) { ModAlertSettings.collect() to stamps.next(now) }
            if (settings.isEmpty()) {
                ModWatchLog.w("alerts $label: nothing to send")
                return 0
            }
            val due = nodeIds.filter { force || memory.due(it, settings, now) }
            if (due.isEmpty()) return 0
            val data = ModAlertSettings.encode(settings, stamp)
            var sent = 0
            for (id in due) {
                try {
                    Tasks.await(client.sendMessage(id, ModWatchMessages.PATH_ALERTS, data), WAIT_SECONDS, TimeUnit.SECONDS)
                    memory.sent(id, settings, now)
                    sent++
                } catch (t: Throwable) {
                    memory.failed(id)
                    ModWatchLog.w("alerts $label: node failed: ${t.javaClass.simpleName}")
                }
            }
            ModWatchLog.i("alerts $label: ${settings.size} kinds to $sent of ${due.size}")
            sent
        } catch (t: Throwable) {
            ModWatchLog.w("alerts $label failed: ${t.javaClass.simpleName}")
            0
        }
    }

    /** Looks up our reachable watches and sends them all the settings; blocks (our thread). Never throws. */
    fun sendNow(context: Context, label: String) {
        try {
            if (!MessageSender.outgoingAllowed()) {
                memory.forget()
                ModWatchLog.i("alerts $label: watch companion off")
                return
            }
            val nodes = Tasks.await(
                Wearable.getCapabilityClient(context)
                    .getCapability(ModWatchMessages.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE),
                WAIT_SECONDS, TimeUnit.SECONDS,
            ).nodes
            if (nodes.isEmpty()) {
                memory.forget()
                ModWatchLog.i("alerts $label: no watch")
                return
            }
            sendTo(Wearable.getMessageClient(context), nodes.map { it.id }, label, force = true)
        } catch (t: Throwable) {
            ModWatchLog.w("alerts $label failed: ${t.javaClass.simpleName}")
        }
    }

    /** Our watch app appeared again: the next tick sends it the settings whatever was sent before. */
    fun forget() = memory.forget()

    /** A sent setting changed: [sendNow] once, [CHANGE_DELAY_MS] after the last such call. Returns at once. */
    fun onChanged(context: Context) {
        val app = context.applicationContext ?: context
        try {
            synchronized(this) {
                pending?.cancel(false)
                pending = executor.schedule({ sendNow(app, "change") }, CHANGE_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        } catch (t: Throwable) {
            ModWatchLog.w("alerts change not queued: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Watches upstream's alert preferences for the life of the process (from [ModWatchStarter]; the file is opened on
     * our thread, not the main one). Returns at once.
     */
    fun watchChanges(context: Context) {
        val app = context.applicationContext ?: context
        try {
            executor.execute {
                try {
                    if (listener != null) return@execute
                    val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        if (ModAlertSettings.triggersSend(key)) onChanged(app)
                    }
                    listener = l
                    app.getSharedPreferences(ModAlertSettings.ALERT_PREFS, Context.MODE_PRIVATE)
                        .registerOnSharedPreferenceChangeListener(l)
                } catch (t: Throwable) {
                    ModWatchLog.w("alerts listener not registered: ${t.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("alerts listener not queued: ${t.javaClass.simpleName}")
        }
    }
}

/**
 * Send times of `/mod/alerts`: the phone clock, but never at or below the last one given in this process (critic
 * finding M1 of part 7d) - the watch drops a message stamped shortly before the last it accepted as overtaken, and a
 * clock set back a few minutes would otherwise have it drop every change for those minutes.
 */
class SendStamps {

    private var last = Long.MIN_VALUE

    /** A stamp for [nowMs]: [nowMs], or one more than the last stamp when that is not below. */
    @Synchronized
    fun next(nowMs: Long): Long {
        last = if (last == Long.MIN_VALUE || nowMs > last) nowMs else last + 1
        return last
    }
}

/**
 * What [WatchAlertSettings] last sent to each watch in this process and the rule for a tick's send, apart from the
 * Data Layer (tests drive it directly). Safe from several threads; a race costs at most one extra message.
 */
class AlertSendMemory {

    private data class Sent(val settings: List<ModAlertSettings.Settings>, val atMs: Long)

    private val byNode = ConcurrentHashMap<String, Sent>()

    /**
     * [node] is due [settings] at [nowMs]: it got nothing yet (or was forgotten), got other settings, or got these
     * [WatchAlertSettings.RESEND_MS] ago or longer (a clock set back counts as long ago).
     */
    fun due(node: String, settings: List<ModAlertSettings.Settings>, nowMs: Long): Boolean {
        val last = byNode[node] ?: return true
        return last.settings != settings || nowMs - last.atMs !in 0 until WatchAlertSettings.RESEND_MS
    }

    fun sent(node: String, settings: List<ModAlertSettings.Settings>, nowMs: Long) {
        byNode[node] = Sent(settings, nowMs)
    }

    fun failed(node: String) {
        byNode.remove(node)
    }

    /** Forgets every watch not in [reachable]: when it is back, it is due. */
    fun keepOnly(reachable: Collection<String>) {
        byNode.keys.retainAll(reachable.toSet())
    }

    fun forget() {
        byNode.clear()
    }
}
