package tk.glucodata.mod.health

import android.content.Context

/**
 * JugglucoNGmod, watch block (part 4): where heart rate and steps come from - Health Services in the `mod` build
 * (part 4b, `tk.glucodata.mod.health.hs`, the only code that sees that library). This logic never names the library:
 * the source registers itself in [HealthSources.current] and hands its data to [HealthIntake.deliver].
 *
 * Calls come on a background thread, never on the main one.
 */
interface HealthSource {

    /**
     * Asks the platform to deliver what it collected now (Health Services: `PassiveMonitoringClient.flush()`), waiting
     * a few seconds at most. `true` when the request went through: the data then arrives through
     * [HealthIntake.deliver], and the hand-over waits [HealthConfig.FLUSH_WAIT_MS] for it.
     */
    fun flush(context: Context): Boolean

    /**
     * Called on every hand-over after the flush, at watch time [nowMs]: the place to subscribe again when the
     * platform has been silent too long or the subscription is old (part 4b decides; [HealthState.lastDataAt] is the
     * last delivery). Nothing by default.
     */
    fun maintain(context: Context, nowMs: Long) {}

    /**
     * Subscribes now for what the permissions allow (the page after permissions were given, a watch restart; part
     * 4b), for [reason] in the log. `true` when a subscription is in place. Blocking: never on the main thread.
     */
    fun register(context: Context, reason: String): Boolean = false

    /**
     * Fields for the status row, `key=value;...` without `\t` or line breaks: permissions, subscription and the like
     * (never heart rate or steps). Part of the permanent status: a change sends a status row at once.
     */
    fun statusFields(context: Context): String
}

/** JugglucoNGmod, watch block (part 4): the registered [HealthSource]; `null` - nothing collects (no hand-over at all). */
object HealthSources {
    @Volatile
    var current: HealthSource? = null
}
