package tk.glucodata.mod.watch

import android.content.Context
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import tk.glucodata.NotificationHistorySource

/**
 * JugglucoNGmod, watch block: fills a hole in the watch's glucose history after the watch was off or away from the
 * phone. The phone pushes only an 8-minute tail per reading (upstream's WearSync2), so after a reboot the newest
 * reading jumps over the hole, and upstream's routine request ("from the newest reading minus 15 minutes") never
 * reaches back to it. Only a deep request (opening the app on the watch) did.
 *
 * On each `/mod/tick` (every 5 minutes, [ModWearListener]) we read the watch's history in the background, find the
 * earliest hole longer than [GAP_MS] between two readings, and ask the phone for everything since [LEAD_MS] before
 * it, in upstream's own request format ([encodeRequest], `/sync2/req`) through [WatchPhoneLink]. Upstream's phone
 * answers any request shallower than its 14-day horizon from the asked start up to now (WearSync2.onRequest), and the
 * watch's ingest writes into empty slots - no hook in their files.
 *
 * What is read: from the anchor of the last scan (the start of the earliest hole still open, else the newest
 * reading) minus a minute, never deeper than [MAX_DEPTH_MS], never less than the last [MIN_READ_MS]. A normal tick
 * reads half an hour, not days; after the watch was off up to [MAX_DEPTH_MS], the reading before the hole is still
 * in the read, so the hole is seen.
 *
 * Repeats: one request covers every hole from its start to the moment it was sent, so they are counted together -
 * a chain. A hole whose start lies in the interval of the chain's last request belongs to the chain, also when a
 * partial fill moved its start later. A chain is asked for at most every [RETRY_MS] and [MAX_TRIES] times, counting
 * only delivered requests; its holes still open after that are ones the phone does not have either (the sensor was
 * not reading) and are dropped. The state is kept in our preferences file, so a restart of the watch process does not
 * start the count again. Edges are not holes: before the first reading read (a new sensor, a fresh watch) and after
 * the newest one (upstream's routine request covers that).
 *
 * Log (tag `ModWatch`): counts, lengths and ages in minutes and the request start in epoch seconds (it matches the
 * phone's "served N triples ... since S" line of WearSync2); never a glucose value.
 */
object HistoryGapFill {

    /** Never read or ask deeper than this (cost of one serve: ~1 reading a minute, 360 per message). */
    const val MAX_DEPTH_MS = 72 * 3_600_000L

    /** The shortest read of a scan, so a clock set back or a lost anchor still looks at the latest readings. */
    const val MIN_READ_MS = 30 * 60_000L

    /** A step between two readings longer than this is a hole (Sibionics and Libre: 1 a minute, Dexcom: 5). */
    const val GAP_MS = 12 * 60_000L

    /** The request starts this much before the hole, so the readings either side overlap. */
    const val LEAD_MS = 5 * 60_000L

    /** A chain is not asked for again sooner than this, delivered or not (a tick may come every 5 minutes). */
    const val RETRY_MS = 10 * 60_000L

    /** Delivered requests for one chain before its holes are dropped. */
    const val MAX_TRIES = 3

    /** Ticks closer than this to the last scan are ignored (cycle, battery and start ticks may come together). */
    const val MIN_SCAN_MS = 60_000L

    /** Upstream's WearSync2.VERSION - the request is `[u8 version][i64 fromSec][u8 force]`, big-endian. */
    const val WIRE_VERSION = 1

    private const val KEY_COVER = "gap_fill_cover_from"
    private const val KEY_TRIES = "gap_fill_tries"
    private const val KEY_LAST = "gap_fill_last"
    private const val KEY_DEAD = "gap_fill_dead"
    private const val KEY_ANCHOR = "gap_fill_anchor"

    /** A hole between the reading at [startMs] and the next one at [endMs]. */
    data class Gap(val startMs: Long, val endMs: Long)

    /**
     * The chain: [coverFromMs] - the start of its first request (0 - no chain), [tries] - delivered requests,
     * [lastAttemptMs] - the last request, delivered or not; [dead] - starts of dropped holes; [anchorMs] - where the
     * next scan reads from (0 - none yet).
     */
    data class State(
        val coverFromMs: Long = 0L,
        val tries: Int = 0,
        val lastAttemptMs: Long = 0L,
        val dead: Set<Long> = emptySet(),
        val anchorMs: Long = 0L,
    )

    /**
     * What a scan decided: [fromSec] > 0 - send a request since then (the [tries]-th if delivered); [wait] - the chain
     * was asked for less than [RETRY_MS] ago; [gap] - the hole it is for, if any; [state] - before the send.
     */
    data class Decision(val gap: Gap?, val fromSec: Long, val tries: Int, val wait: Boolean, val state: State)

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ModGapFill").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    private val lastScanMs = AtomicLong(0L)

    /** Timestamps (ms) of the watch's readings since the given start; replaced in tests. */
    @Volatile
    internal var history: (Long) -> List<Long> = { startMs ->
        NotificationHistorySource.getRawHistory(startMs).map { it.timestamp }
    }

    /** Sends a request to the phone and waits for it; true when a phone took it. Replaced in tests. */
    @Volatile
    internal var sender: (Context, ByteArray) -> Boolean = { context, data -> WatchPhoneLink.sendSyncRequest(context, data) }

    /** Runs a scan off the caller's thread; replaced in tests. */
    @Volatile
    internal var runner: (Runnable) -> Unit = { worker.execute(it) }

    /** A `/mod/tick` at [nowMs]: queues one scan unless one runs or ran within [MIN_SCAN_MS]. Never throws. */
    fun onTick(context: Context, nowMs: Long) {
        try {
            val last = lastScanMs.get()
            if (last != 0L && nowMs - last in 0 until MIN_SCAN_MS) return
            if (!running.compareAndSet(false, true)) return
            lastScanMs.set(nowMs)
            val app = context.applicationContext ?: context
            runner {
                try {
                    scan(app, nowMs)
                } catch (t: Throwable) {
                    ModWatchLog.w("gap: scan failed: ${t.javaClass.simpleName}")
                } finally {
                    running.set(false)
                }
            }
        } catch (t: Throwable) {
            running.set(false)
            ModWatchLog.w("gap: tick failed: ${t.javaClass.simpleName}")
        }
    }

    /** One scan at [nowMs]: read, decide, send, keep the state. */
    internal fun scan(context: Context, nowMs: Long) {
        val saved = load(context)
        val readStart = readStartMs(saved.anchorMs, nowMs)
        val stamps = history(readStart)
        val gaps = findGaps(stamps, readStart, nowMs)
        val decision = decide(gaps, saved, nowMs)
        var state = decision.state
        val gap = decision.gap
        when {
            gap == null -> ModWatchLog.i("gap: none, holes=${gaps.size} points=${stamps.size}")
            decision.wait -> ModWatchLog.i(
                "gap: waiting ${describe(gap, nowMs)}, holes=${gaps.size} try=${decision.state.tries}/$MAX_TRIES",
            )
            else -> {
                ModWatchLog.i(
                    "gap: found ${describe(gap, nowMs)}, holes=${gaps.size} points=${stamps.size}, " +
                        "request since ${decision.fromSec} try=${decision.tries}/$MAX_TRIES",
                )
                val delivered = sender(context, encodeRequest(decision.fromSec))
                if (!delivered) ModWatchLog.i("gap: request not delivered, try not counted")
                state = afterSend(state, delivered, nowMs)
            }
        }
        save(context, state.copy(anchorMs = anchorOf(gaps, state.dead, stamps, nowMs) ?: saved.anchorMs))
    }

    private fun describe(gap: Gap, nowMs: Long): String =
        "len=${(gap.endMs - gap.startMs) / 60_000L}min from -${(nowMs - gap.startMs) / 60_000L}min " +
            "to -${(nowMs - gap.endMs) / 60_000L}min"

    /**
     * Where a scan at [nowMs] reads from: a minute before [anchorMs] (so the reading at the anchor is in the read),
     * at most [MAX_DEPTH_MS] and at least [MIN_READ_MS] back; no anchor yet - [MAX_DEPTH_MS] back.
     */
    fun readStartMs(anchorMs: Long, nowMs: Long): Long {
        val deepest = nowMs - MAX_DEPTH_MS
        if (anchorMs <= 0L) return deepest
        return (anchorMs - 60_000L).coerceIn(deepest, nowMs - MIN_READ_MS)
    }

    /** The next scan's anchor: the start of the earliest hole not dropped, else the newest reading up to [nowMs]. */
    fun anchorOf(gaps: List<Gap>, dead: Set<Long>, stampsMs: List<Long>, nowMs: Long): Long? =
        gaps.firstOrNull { it.startMs !in dead }?.startMs ?: stampsMs.filter { it <= nowMs }.maxOrNull()

    /**
     * Every hole longer than [GAP_MS] between two readings in [startMs]..[nowMs], oldest first. Unsorted input and
     * repeated timestamps are fine; readings outside that span are ignored.
     */
    fun findGaps(timestampsMs: List<Long>, startMs: Long, nowMs: Long): List<Gap> {
        val sorted = timestampsMs.filter { it in startMs..nowMs }.distinct().sorted()
        val gaps = ArrayList<Gap>()
        for (i in 1 until sorted.size) {
            if (sorted[i] - sorted[i - 1] > GAP_MS) gaps.add(Gap(sorted[i - 1], sorted[i]))
        }
        return gaps
    }

    /** The request start (epoch seconds) for [gap]: [LEAD_MS] before it, never deeper than [MAX_DEPTH_MS]. */
    fun requestFromSec(gap: Gap, nowMs: Long): Long = maxOf(gap.startMs - LEAD_MS, nowMs - MAX_DEPTH_MS) / 1000L

    /** Upstream's `/sync2/req` body: version, start in epoch seconds, force (0: the phone keeps its own spacing). */
    fun encodeRequest(fromSec: Long, force: Boolean = false): ByteArray =
        ByteBuffer.allocate(10)
            .put(WIRE_VERSION.toByte())
            .putLong(fromSec)
            .put((if (force) 1 else 0).toByte())
            .array()

    /**
     * Which hole of [gaps] to ask for at [nowMs], given the [state] after the last scan. The earliest hole not dropped
     * is the target. It belongs to the chain when its start lies in the chain's interval (from [State.coverFromMs] to
     * the last request); otherwise a new chain starts from it. A chain asked for [MAX_TRIES] times and still holding
     * the target [RETRY_MS] after the last request drops it, and the next hole is considered at once (one of the same
     * chain is dropped too). Dropped holes deeper than [MAX_DEPTH_MS] are forgotten. A last request in the future (the
     * clock set back) counts as long ago.
     */
    fun decide(gaps: List<Gap>, state: State, nowMs: Long): Decision {
        val dead = state.dead.filterTo(HashSet()) { it >= nowMs - MAX_DEPTH_MS }
        var chain = state.copy(dead = dead)
        while (true) {
            val target = gaps.firstOrNull { it.startMs !in dead }
                ?: return Decision(null, 0L, 0, false, State(dead = dead, anchorMs = state.anchorMs))
            val inChain = chain.coverFromMs > 0L && target.startMs >= chain.coverFromMs &&
                target.startMs < chain.lastAttemptMs
            if (!inChain) chain = State(requestFromSec(target, nowMs) * 1000L, 0, 0L, dead, state.anchorMs)
            val recent = chain.lastAttemptMs > 0L && nowMs - chain.lastAttemptMs in 0 until RETRY_MS
            if (recent) return Decision(target, 0L, chain.tries, true, chain)
            if (chain.tries >= MAX_TRIES) {
                dead.add(target.startMs)
                ModWatchLog.i("gap: dropped after $MAX_TRIES tries, len=${(target.endMs - target.startMs) / 60_000L}min")
                continue
            }
            return Decision(target, requestFromSec(target, nowMs), chain.tries + 1, false, chain)
        }
    }

    /** The chain after a request at [nowMs]: the pause starts either way, the try counts only when [delivered]. */
    fun afterSend(state: State, delivered: Boolean, nowMs: Long): State =
        state.copy(tries = if (delivered) state.tries + 1 else state.tries, lastAttemptMs = nowMs)

    internal fun load(context: Context): State {
        val prefs = PhoneBatteryStore.prefs(context)
        val dead = prefs.getString(KEY_DEAD, "").orEmpty().split(',').mapNotNullTo(HashSet()) { it.toLongOrNull() }
        return State(
            prefs.getLong(KEY_COVER, 0L), prefs.getInt(KEY_TRIES, 0), prefs.getLong(KEY_LAST, 0L), dead,
            prefs.getLong(KEY_ANCHOR, 0L),
        )
    }

    internal fun save(context: Context, state: State) {
        PhoneBatteryStore.prefs(context).edit()
            .putLong(KEY_COVER, state.coverFromMs)
            .putInt(KEY_TRIES, state.tries)
            .putLong(KEY_LAST, state.lastAttemptMs)
            .putString(KEY_DEAD, state.dead.sorted().joinToString(","))
            .putLong(KEY_ANCHOR, state.anchorMs)
            .apply()
    }

    /** Tests: forget the in-memory pacing. */
    internal fun resetForTest() {
        running.set(false)
        lastScanMs.set(0L)
    }
}
