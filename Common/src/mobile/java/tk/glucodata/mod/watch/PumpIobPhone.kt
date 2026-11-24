package tk.glucodata.mod.watch

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import tk.glucodata.UiRefreshBus
import tk.glucodata.drivers.api.ApiIobSnapshot
import tk.glucodata.drivers.api.ApiGlucoseSourceRegistry

/**
 * JugglucoNGmod, watch block (watch face, 20.11 decision "1a with fixes"): the pump's active insulin for the watch face,
 * carried by `/mod/tick` ([ModWatchMessages.PumpIob]).
 *
 * **Only the pump's number.** Our CareLink code puts the pump's active insulin into upstream's remote snapshot
 * [ApiIobSnapshot] (its time is the pump's, fresh for 30 min); that slot is read here and nothing else - never the
 * journal's computation (`JournalIobAccess`, `RemoteIobSnapshot`, Clone or Nightscout): a journal "0" while CareLink is
 * silent would read as a known zero. The same slot is also upstream's HTTP API follower source (another phone's
 * journal numbers), so while that follower is switched on nothing is read at all ([pumpOnly]).
 *
 * **Past 30 minutes.** The slot forgets its number after [ApiIobSnapshot.FRESHNESS_WINDOW_MS]; the watch must then show
 * "- U", not nothing, so the last number is remembered ([PumpIobMemory], kept in our preferences across a process
 * restart) and sent with its growing age for up to [PumpIobMemory.KEEP_MS]. A number that leaves the slot before its 30
 * minutes (CareLink signed out, the follower switched off) is forgotten at once: the next tick carries none and the
 * watch drops its copy.
 *
 * **Extra ticks.** Upstream has no listener on the slot and CareLink's code is not ours to touch here, so the slot is
 * looked at from this side: once a minute on our own thread and on every new reading on the phone
 * (`UiRefreshBus.revision`, which also runs while the device dozes between sensor packets). A changed number (other
 * tenths, or it came or went; a new time alone waits for the cycle tick) sends a tick with
 * [ModWatchMessages.REASON_IOB] - not within
 * [WatchTicker.MIN_EXTRA_GAP_MS] of any tick ([PumpIobTicks.due]). The log has no doses.
 */
object PumpIobPhone {

    private const val PREFS = "mod_watch_pump_iob"
    private const val KEY_TENTHS = "tenths"
    private const val KEY_AT = "at_ms"

    /** How often the slot is looked at on our own thread. */
    const val CHECK_PERIOD_MS = 60_000L

    private val lock = Any()
    private var memory: PumpIobMemory? = null
    private val ticks = PumpIobTicks()

    @Volatile private var started = false
    private var executor: ScheduledExecutorService? = null

    /**
     * The pump's number in the slot at [nowMs] as a [PumpIobMemory.Reading]; `null` - none, not fresh, not the pump's
     * (the API follower on) or broken. Never throws.
     */
    fun slot(context: Context, nowMs: Long): PumpIobMemory.Reading? = try {
        val followerOn = try {
            ApiGlucoseSourceRegistry.loadConfig(context).enabled
        } catch (_: Throwable) {
            true // unknown: the number may not be the pump's
        }
        val fresh = ApiIobSnapshot.fresh(nowMs)
        pumpOnly(followerOn, fresh?.iobUnits, fresh?.timestampMillis)
    } catch (_: Throwable) {
        null
    }

    /**
     * The slot's number as the pump's: none while upstream's API follower is on ([followerOn]: then the slot may hold
     * another phone's journal number), a finite, not negative [units] (rounded to tenths) and a time.
     */
    fun pumpOnly(followerOn: Boolean, units: Float?, atMs: Long?): PumpIobMemory.Reading? {
        if (followerOn || units == null || atMs == null || atMs <= 0L) return null
        if (!units.isFinite() || units < 0f) return null
        val tenths = (units * 10f).roundToInt().coerceAtMost(ModWatchMessages.IOB_MAX_TENTHS)
        return PumpIobMemory.Reading(tenths, atMs)
    }

    /** What a tick at [nowMs] carries; notes it as sent (the extra-tick rule compares with it). Never throws. */
    fun forTick(context: Context, nowMs: Long): ModWatchMessages.PumpIob? = try {
        synchronized(lock) {
            val reading = observe(context, nowMs)
            ticks.sent(reading)
            reading?.let { PumpIobMemory.toMessage(it, nowMs) }
        }
    } catch (t: Throwable) {
        ModWatchLog.w("pump iob not read: ${t.javaClass.simpleName}")
        null
    }

    /** Starts the checks for the life of the process (from [ModWatchStarter]); a second call does nothing. */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext ?: context
        try {
            val ex = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "ModPumpIob").apply { isDaemon = true }
            }
            executor = ex
            ex.scheduleWithFixedDelay({ check(app) }, CHECK_PERIOD_MS, CHECK_PERIOD_MS, TimeUnit.MILLISECONDS)
            CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
                ModWatchLog.w("pump iob watch failed: ${t.javaClass.simpleName}")
            }).launch {
                UiRefreshBus.revision.drop(1).collect {
                    try {
                        ex.execute { check(app) }
                    } catch (_: Throwable) {
                    }
                }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("pump iob watch not started: ${t.javaClass.simpleName}")
        }
    }

    /** One look at the slot (our thread): an extra tick when the number changed and no tick went within a minute. */
    private fun check(context: Context) {
        try {
            val now = System.currentTimeMillis()
            val due = synchronized(lock) {
                val reading = observe(context, now)
                // claimed as sent now: without a watch the send carries nothing, and this number must not ask
                // for a tick every minute (the next cycle tick carries it)
                ticks.due(reading, now, WatchTicker.lastTickAtMs()).also { if (it) ticks.sent(reading) }
            }
            if (due) {
                ModWatchLog.i("pump iob changed: extra tick")
                WatchTicker.sendAsync(context, ModWatchMessages.REASON_IOB)
            }
        } catch (t: Throwable) {
            ModWatchLog.w("pump iob check failed: ${t.javaClass.simpleName}")
        }
    }

    /** The slot through the memory, the memory saved when it changed. Under [lock]. */
    private fun observe(context: Context, nowMs: Long): PumpIobMemory.Reading? {
        val mem = memory ?: PumpIobMemory(load(context)).also { memory = it }
        val before = mem.last
        val reading = mem.observe(slot(context, nowMs), nowMs)
        if (mem.last != before) save(context, mem.last)
        return reading
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): PumpIobMemory.Reading? = try {
        val p = prefs(context)
        if (!p.contains(KEY_AT)) null
        else PumpIobMemory.Reading(p.getInt(KEY_TENTHS, 0), p.getLong(KEY_AT, 0L)).takeIf { it.atMs > 0L }
    } catch (_: Throwable) {
        null
    }

    private fun save(context: Context, reading: PumpIobMemory.Reading?) {
        try {
            val edit = prefs(context).edit()
            if (reading == null) edit.clear() else edit.putInt(KEY_TENTHS, reading.tenths).putLong(KEY_AT, reading.atMs)
            edit.apply()
        } catch (_: Throwable) {
        }
    }
}

/**
 * The phone's memory of the pump's number (pure; [PumpIobPhone] keeps one under its lock): the slot's number while it
 * is there; after the slot let it go at the end of its 30 minutes, the same number with its growing age for up to
 * [KEEP_MS] (the watch shows "- U"); forgotten at once when it left the slot earlier - but only when this process saw
 * it there ([seenLive]): after a restart the slot is empty until the next CareLink poll, and the remembered number
 * stays.
 */
class PumpIobMemory(initial: Reading? = null) {

    /** [tenths] of a unit, [atMs] - the number's own time (the pump's, phone clock). */
    data class Reading(val tenths: Int, val atMs: Long)

    var last: Reading? = initial
        private set

    private var seenLive = false

    /** The number to send at [nowMs], given the slot's [slot] (`null` - empty). */
    fun observe(slot: Reading?, nowMs: Long): Reading? {
        if (slot != null) {
            last = slot
            seenLive = true
            return slot
        }
        val kept = last ?: return null
        val age = nowMs - kept.atMs
        val leftEarly = seenLive && age <= ApiIobSnapshot.FRESHNESS_WINDOW_MS
        if (leftEarly || age > KEEP_MS) {
            last = null
            seenLive = false
            return null
        }
        return kept
    }

    companion object {
        /** How long a number gone stale is still sent (the watch shows "- U" all that time). */
        const val KEEP_MS = 6 * 60 * 60_000L

        /** [reading] as a tick field at [nowMs]: its age in whole seconds (a time ahead of the clock counts as 0). */
        fun toMessage(reading: Reading, nowMs: Long): ModWatchMessages.PumpIob {
            val ageSec = ((nowMs - reading.atMs).coerceAtLeast(0L) / 1000L)
                .coerceAtMost(ModWatchMessages.IOB_MAX_AGE_SEC.toLong()).toInt()
            return ModWatchMessages.PumpIob(reading.tenths, ageSec)
        }
    }
}

/**
 * When the number's change sends an extra tick (pure): its tenths differ from what the last tick carried or the last
 * extra tick claimed ([sent]), or it came or went, and no tick of any reason went within [WatchTicker.MIN_EXTRA_GAP_MS] -
 * a change within that minute waits for the next check. Only a new time of the same number sends none (critic 9, M1:
 * CareLink gives each 5-minute poll a new time; the 5-minute cycle tick brings the fresher time anyway).
 */
class PumpIobTicks {

    private var lastSent: PumpIobMemory.Reading? = null
    private var anySent = false

    /** A tick carried [reading] (`null` - none), or an extra tick for it was claimed. */
    fun sent(reading: PumpIobMemory.Reading?) {
        lastSent = reading
        anySent = true
    }

    /**
     * An extra tick is due at [nowMs] for [current]; [lastTickAtMs] - the last tick attempt of any reason (0 - none).
     * Before the first tick of the process (no watch found yet) nothing is due: the tick that finds one carries it.
     */
    fun due(current: PumpIobMemory.Reading?, nowMs: Long, lastTickAtMs: Long): Boolean {
        if (!anySent || !differs(current, lastSent)) return false
        return WatchTicker.gapPassed(nowMs, lastTickAtMs)
    }

    companion object {
        /** [a] and [b] differ for the watch's eye: one is there and the other not, or other tenths. */
        fun differs(a: PumpIobMemory.Reading?, b: PumpIobMemory.Reading?): Boolean =
            if (a == null || b == null) (a == null) != (b == null) else a.tenths != b.tenths
    }
}
