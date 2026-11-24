package tk.glucodata.mod.watch

import android.content.Context
import tk.glucodata.mod.health.HealthHandOver

/**
 * JugglucoNGmod, watch block (part 2): what a `/mod/tick` from the phone wakes for heart rate and steps. Part 4
 * fills [onTickForHealth] (hand over what was collected); here it is the call point and the filter of too frequent
 * ticks: within [MIN_GAP_MS] of the last one that went through, a tick is dropped silently (cycle, battery and start
 * ticks may come close together).
 */
object HealthTick {

    /** Ticks closer than this to the last one that went through are dropped (Glucove's watch rule). */
    const val MIN_GAP_MS = 60_000L

    private const val KEY_LAST = "health_tick_at"

    /** A tick received at [nowMs]: calls [onTickForHealth] unless one went through within [MIN_GAP_MS]. */
    fun onTick(context: Context, nowMs: Long): Boolean {
        val prefs = PhoneBatteryStore.prefs(context)
        val last = if (prefs.contains(KEY_LAST)) prefs.getLong(KEY_LAST, 0L) else null
        if (!shouldRun(nowMs, last)) return false
        prefs.edit().putLong(KEY_LAST, nowMs).apply()
        onTickForHealth(context)
        return true
    }

    /** No tick went through yet, or the last one ([lastMs]) is [MIN_GAP_MS] or more ago (a clock set back too). */
    fun shouldRun(nowMs: Long, lastMs: Long?): Boolean = lastMs == null || nowMs - lastMs !in 0 until MIN_GAP_MS

    /**
     * Part 4: hand the collected heart rate and steps to the phone ([HealthHandOver]: flush the source, wait for its
     * data, close the heart rate windows, send). Nothing when no source is registered. Never throws.
     */
    fun onTickForHealth(context: Context) {
        try {
            HealthHandOver.onTick(context)
        } catch (t: Throwable) {
            ModWatchLog.w("health tick failed: ${t.javaClass.simpleName}")
        }
    }
}
