package tk.glucodata.mod.watch

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import tk.glucodata.WearToggleSync
import tk.glucodata.alerts.AlertRepository
import tk.glucodata.alerts.AlertType

/**
 * JugglucoNGmod, watch block (parts 7d, 7f, 7g): `/mod/alerts` on the watch ([ModAlertSettings]) - order, memory, the
 * write again after upstream's toggle sync and the on/off check.
 *
 * Order: the phone stamps each message with a send time that never goes back while its process lives
 * (WatchAlertSettings). One older than the last accepted by less than [STALE_WINDOW_MS] and arriving within
 * [OVERTAKE_WINDOW_MS] of it was overtaken on the way (a tick that waited in its send while a change went out) and is
 * dropped. Anything else is accepted: a phone clock set back, a message long after the last one (a phone clock
 * corrected in between must not hide a change for an hour - the phone counts it as delivered), the first message of
 * the watch process, a 7b message without a time.
 *
 * Memory: the last accepted message is kept in our own preferences file ([PhoneBatteryStore.PREFS]). When upstream's
 * toggle sync turns an alert on or off on the watch, their AlertRepository.saveConfig writes every field of the
 * config it loaded - for a prefs-only alert without its "enabled" key the defaults, and at any time it may land just
 * after our write with older values. So a change of any `alert_<kind>_enabled` (only their code writes it, never ours)
 * writes the kept message again [REAPPLY_DELAY_MS] later, after their native part of the save; only what differs.
 * Registered for the life of the process before the first message is written.
 *
 * On/off check (critic finding H1 of part 7d): upstream's Natives.setAlertConfig reads the on/off of the whole native
 * group (low, high, loss; or very low/high and forecast) and writes it back with one level. Their toggle sync runs the
 * same call on their receiver thread, with no lock we could share, so our level write landing between their read and
 * write can switch a neighbouring alert back. [REAPPLY_DELAY_MS] after every accepted message we write the kept message
 * again (their save may have put an old level back; a round that writes a level waits another round, at most
 * [MAX_CHECK_ROUNDS], and the check always comes a pause after the last write) and compare every native alert's on/off
 * with what the phone last sent; on a difference we ask the phone for its state ([ToggleState.requestState]) - its
 * answer goes through upstream's own apply, which corrects the watch. The watch still never writes an on/off itself.
 * A request can be lost without a word (upstream's sender skips it when it knows no phone node), so a difference still
 * there [askRetryMs] after a request is checked and asked again, at most [MAX_ASKS] requests per [ASK_WINDOW_MS]; and
 * since every accepted message runs the check, the phone's hourly resend brings a lost one back within the hour
 * (critic M1 of part 7f).
 *
 * Vibration only (owner's rule, part 7h): an alert on the watch never sounds - whatever the phone has, the watch
 * writes the sound off and the vibration on ([vibrationOnly]); duration, pattern, repeat and the rest come from the
 * phone. Upstream's Notify.mksound reads exactly these two keys, and their alert channels carry no sound of their own.
 */
object WatchAlertStore {

    /** A message this much or less older than the last accepted one may be an overtaken one. */
    const val STALE_WINDOW_MS = 10 * 60_000L

    /**
     * ... and only when it arrives this soon after the last accepted one: a tick waits at most 10 s per watch in its
     * send, a change goes 3 s after the last edit.
     */
    const val OVERTAKE_WINDOW_MS = 60_000L

    /** After a message, upstream's toggle sync or our native write, ours is written again this much later. */
    const val REAPPLY_DELAY_MS = 2_000L

    /** Rounds of "write again, wait" before the on/off check runs regardless. */
    const val MAX_CHECK_ROUNDS = 3

    /** A difference still there after a state request is checked again this much later. */
    const val ASK_RETRY_MS = 45_000L

    /** State requests at most this many per [ASK_WINDOW_MS]: a difference upstream's apply cannot mend asks no more. */
    const val MAX_ASKS = 3

    const val ASK_WINDOW_MS = 10 * 60_000L

    private const val KEY_SENT_AT = "alerts_sent_at"
    private const val KEY_MESSAGE = "alerts_message"

    private val lock = Any()

    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ModWatchAlerts").apply { isDaemon = true }
    }

    @Volatile
    private var pending: ScheduledFuture<*>? = null

    /** The pending task also checks the on/off. */
    @Volatile
    private var pendingCheck = false

    /** Kept here: SharedPreferences holds its listeners weakly. */
    @Volatile
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    /** Watch uptime (elapsedRealtime) when the last message was accepted in this process; `null` before the first. */
    @Volatile
    private var acceptedAtUptimeMs: Long? = null

    /** What the last message was written with; the toggle listener uses the same (upstream's own outside tests). */
    @Volatile
    private var lastThresholds: ModAlertSettings.ThresholdStore = ModAlertSettings.UpstreamThresholds

    @Volatile
    private var lastToggles: ToggleState = UpstreamToggles

    /** [ASK_RETRY_MS]; shorter in tests. */
    @Volatile
    internal var askRetryMs = ASK_RETRY_MS

    /** Watch uptimes of the state requests in the last [ASK_WINDOW_MS]. */
    private val askedAt = ArrayDeque<Long>()

    /** Upstream's on/off on the watch and from the phone; an interface so tests can stand in for both. */
    interface ToggleState {
        /** [type]'s on/off the watch acts on; `null` when it cannot be read. */
        fun watchEnabled(type: AlertType): Boolean?

        /** [type]'s on/off the phone last sent through upstream's toggle sync; `null` before it sent any. */
        fun phoneEnabled(type: AlertType): Boolean?

        /** Asks the phone for its on/off state; upstream's apply of the answer corrects the watch. */
        fun requestState()
    }

    /** Upstream's own: AlertRepository.loadConfig (native on/off), WearToggleSync.knownEnabled and requestState. */
    object UpstreamToggles : ToggleState {
        override fun watchEnabled(type: AlertType): Boolean? = AlertRepository.loadConfig(type).enabled

        override fun phoneEnabled(type: AlertType): Boolean? =
            WearToggleSync.knownEnabled(WearToggleSync.SCOPE_ALERT, type.id.toString())

        override fun requestState() = WearToggleSync.requestState()
    }

    /** What [checkToggles] found: the kinds whose on/off differs from the phone's, and whether the phone was asked. */
    data class Check(val differ: List<AlertType>, val asked: Boolean)

    /**
     * [sentAtMs] is older than [lastAcceptedMs] by less than [STALE_WINDOW_MS] and the message came [sinceAcceptedMs]
     * after the last accepted one, less than [OVERTAKE_WINDOW_MS]: it was overtaken. An unknown value is never stale.
     */
    fun isStale(sentAtMs: Long?, lastAcceptedMs: Long?, sinceAcceptedMs: Long?): Boolean =
        sentAtMs != null && lastAcceptedMs != null && sinceAcceptedMs != null && sentAtMs < lastAcceptedMs &&
            lastAcceptedMs - sentAtMs < STALE_WINDOW_MS && sinceAcceptedMs in 0 until OVERTAKE_WINDOW_MS

    /**
     * One `/mod/alerts` message: written into upstream's alert preferences and native thresholds unless it is broken
     * or overtaken; an accepted one is followed by the write again and the on/off check. [nowUptimeMs] is the watch's
     * elapsedRealtime. Returns false only for a broken message. Never throws.
     */
    fun onMessage(
        context: Context,
        data: ByteArray?,
        thresholds: ModAlertSettings.ThresholdStore = ModAlertSettings.UpstreamThresholds,
        toggles: ToggleState = UpstreamToggles,
        nowUptimeMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        val message = ModAlertSettings.decodeMessage(data)
        if (message == null || data == null) {
            ModWatchLog.w("alerts: bad message")
            return false
        }
        try {
            lastThresholds = thresholds
            lastToggles = toggles
            // before the first write: upstream's save racing it must already find us listening (critic H1)
            watchToggles(context)
            synchronized(lock) {
                val ours = PhoneBatteryStore.prefs(context)
                val last = if (ours.contains(KEY_SENT_AT)) ours.getLong(KEY_SENT_AT, 0L) else null
                val since = acceptedAtUptimeMs?.let { nowUptimeMs - it }
                if (isStale(message.sentAtMs, last, since)) {
                    ModWatchLog.i("alerts: an overtaken message dropped")
                    return true
                }
                val changed = ModAlertSettings.apply(alertPrefs(context), vibrationOnly(message.settings), thresholds)
                val editor = ours.edit().putString(KEY_MESSAGE, Base64.encodeToString(data, Base64.NO_WRAP))
                message.sentAtMs?.let { editor.putLong(KEY_SENT_AT, it) }
                editor.apply()
                acceptedAtUptimeMs = nowUptimeMs
                if (changed > 0) ModWatchLog.i("alerts: $changed values from the phone (${message.settings.size} kinds)")
                if (message.outOfRangeKinds.isNotEmpty()) {
                    ModWatchLog.w("alerts: threshold of kinds ${message.outOfRangeKinds} out of range, the watch keeps its own")
                }
            }
            // after every accepted message, not only after a native write of ours: a check whose state request was
            // lost runs again with the next one - the phone's hourly resend at the latest (critic M1 of part 7f)
            schedule(context, thresholds, toggles, check = true, round = 1)
        } catch (t: Throwable) {
            ModWatchLog.w("alerts: not applied: ${t.javaClass.simpleName}")
        }
        return true
    }

    /** Writes the kept message again (only what differs); returns how many values changed. Never throws. */
    fun reapply(
        context: Context,
        thresholds: ModAlertSettings.ThresholdStore = ModAlertSettings.UpstreamThresholds,
    ): Int = try {
        synchronized(lock) {
            val kept = PhoneBatteryStore.prefs(context).getString(KEY_MESSAGE, null)
            val settings = kept?.let { ModAlertSettings.decode(Base64.decode(it, Base64.NO_WRAP)) }
            if (settings == null) 0 else ModAlertSettings.apply(alertPrefs(context), vibrationOnly(settings), thresholds)
        }
    } catch (t: Throwable) {
        ModWatchLog.w("alerts: not written again: ${t.javaClass.simpleName}")
        0
    }

    /** The phone's settings as the watch keeps them: the sound off and the vibration on, all else as sent. */
    fun vibrationOnly(settings: List<ModAlertSettings.Settings>): List<ModAlertSettings.Settings> =
        settings.map { it.copy(soundEnabled = false, vibrationEnabled = true) }

    /**
     * Compares every native alert's on/off on the watch with what the phone last sent; on a difference asks the phone
     * for its state, unless [MAX_ASKS] requests went in the last [ASK_WINDOW_MS] ([nowUptimeMs] is the watch's
     * elapsedRealtime). Never throws.
     */
    fun checkToggles(
        toggles: ToggleState = UpstreamToggles,
        nowUptimeMs: Long = SystemClock.elapsedRealtime(),
    ): Check {
        val differ = ModAlertSettings.NATIVE_BACKED.filter { type ->
            try {
                // the phone's first: before it sent any there is nothing to compare, and no native read
                val phone = toggles.phoneEnabled(type) ?: return@filter false
                val watch = toggles.watchEnabled(type) ?: return@filter false
                phone != watch
            } catch (t: Throwable) {
                ModWatchLog.w("alerts: on/off of kind ${type.id} not read: ${t.javaClass.simpleName}")
                false
            }
        }
        if (differ.isEmpty()) return Check(differ, asked = false)
        val kinds = differ.map { it.id }
        val ask = mayAsk(nowUptimeMs)
        if (ask < 0) {
            ModWatchLog.w("alerts: on/off of kinds $kinds differs from the phone, not asked again ($MAX_ASKS asks in ${ASK_WINDOW_MS / 60_000} min)")
            return Check(differ, asked = false)
        }
        ModWatchLog.w("alerts: on/off of kinds $kinds differs from the phone, its state asked (ask ${ask + 1} of $MAX_ASKS)")
        try {
            toggles.requestState()
        } catch (t: Throwable) {
            ModWatchLog.w("alerts: state not asked: ${t.javaClass.simpleName}")
        }
        return Check(differ, asked = true)
    }

    /**
     * Counts a state request at [nowUptimeMs] when fewer than [MAX_ASKS] went in the [ASK_WINDOW_MS] before it; returns
     * how many went before this one, or -1 when it may not go.
     */
    internal fun mayAsk(nowUptimeMs: Long): Int = synchronized(askedAt) {
        while (askedAt.isNotEmpty() && nowUptimeMs - askedAt.first() !in 0 until ASK_WINDOW_MS) askedAt.removeFirst()
        if (askedAt.size >= MAX_ASKS) {
            -1
        } else {
            askedAt.addLast(nowUptimeMs)
            askedAt.size - 1
        }
    }

    /** [key] is an alert's on/off in upstream's alert preferences. */
    fun isToggleKey(key: String?): Boolean =
        key != null && AlertType.settingsEntries.any { key == "alert_${it.id}_enabled" }

    /** The write again after upstream's toggle sync is registered in this process. */
    fun isWatchingToggles(): Boolean = listener != null

    /** Registers, once per process, the write again after upstream's toggle sync. */
    private fun watchToggles(context: Context) {
        if (listener != null) return
        val app = context.applicationContext ?: context
        synchronized(lock) {
            if (listener != null) return
            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (isToggleKey(key)) schedule(app, lastThresholds, lastToggles, check = false, round = 1)
            }
            listener = l
            alertPrefs(app).registerOnSharedPreferenceChangeListener(l)
        }
    }

    /** Tests: forgets the listener, the pending write, the last receipt and the requests of an earlier test. */
    internal fun resetForTests() {
        synchronized(this) {
            pending?.cancel(false)
            pendingCheck = false
        }
        listener = null
        acceptedAtUptimeMs = null
        lastThresholds = ModAlertSettings.UpstreamThresholds
        lastToggles = UpstreamToggles
        askRetryMs = ASK_RETRY_MS
        synchronized(askedAt) { askedAt.clear() }
    }

    /**
     * In [delayMs]: writes the kept message again ([writeAgain]); when that set a native level, waits another round
     * (it may have raced too), and after the last round's write once more without writing; else - [check] or a native
     * write in an earlier round - compares the on/off ([checkToggles]), and when the phone was asked, checks again in
     * [askRetryMs]. A newer call replaces a pending one and keeps its [check].
     */
    private fun schedule(
        context: Context,
        thresholds: ModAlertSettings.ThresholdStore,
        toggles: ToggleState,
        check: Boolean,
        round: Int,
        writeAgain: Boolean = true,
        delayMs: Long = REAPPLY_DELAY_MS,
    ) {
        try {
            synchronized(this) {
                val withCheck = check || pendingCheck
                pending?.cancel(false)
                pendingCheck = withCheck
                pending = executor.schedule({
                    synchronized(this) { pendingCheck = false }
                    val counted = CountedThresholds(thresholds)
                    if (writeAgain) {
                        val changed = reapply(context, counted)
                        if (changed > 0) ModWatchLog.i("alerts: $changed values written again")
                    }
                    when {
                        counted.writes > 0 && round < MAX_CHECK_ROUNDS ->
                            schedule(context, thresholds, toggles, check = true, round = round + 1)
                        // the last round wrote a level: its own race with upstream's save needs the pause too (critic L1 of 7f)
                        counted.writes > 0 ->
                            schedule(context, thresholds, toggles, check = true, round = round, writeAgain = false)
                        withCheck -> if (checkToggles(toggles).asked) {
                            schedule(context, thresholds, toggles, check = true, round = 1, writeAgain = false, delayMs = askRetryMs)
                        }
                    }
                }, delayMs, TimeUnit.MILLISECONDS)
            }
        } catch (t: Throwable) {
            ModWatchLog.w("alerts: write again not queued: ${t.javaClass.simpleName}")
        }
    }

    /** Counts the native level writes [inner] was asked for (one that throws counts too). */
    private class CountedThresholds(private val inner: ModAlertSettings.ThresholdStore) : ModAlertSettings.ThresholdStore {
        @Volatile
        var writes = 0

        override fun read(type: AlertType): Int? = inner.read(type)

        override fun write(type: AlertType, mgPerL: Int) {
            writes++
            inner.write(type, mgPerL)
        }
    }

    private fun alertPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(ModAlertSettings.ALERT_PREFS, Context.MODE_PRIVATE)
}
