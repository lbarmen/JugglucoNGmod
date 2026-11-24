package tk.glucodata.mod.watch

import android.content.Context

/**
 * JugglucoNGmod, watch block (part 2): the phone battery from the last `/mod/tick`, kept in our own preferences file
 * ([PREFS], not upstream's settings) with the watch's time of receipt. The watch face shows it while it is at most
 * [MAX_AGE_MS] old and "-" after that (watch plan, decision 5): the phone ticks every 5 minutes while the watch is
 * reachable, so an older value means the phone is away or silent.
 */
object PhoneBatteryStore {

    /** Our preferences file on the watch (part 2 onwards). */
    const val PREFS = "mod_watch"

    /** Older than this, the phone battery is not shown. */
    const val MAX_AGE_MS = 30 * 60_000L

    private const val KEY_PERCENT = "phone_battery_percent"
    private const val KEY_CHARGING = "phone_battery_charging"
    private const val KEY_AT = "phone_battery_received_at"

    /** [percent] 0-100, [charging] - the phone is on its charger, [receivedAtMs] - watch time of the tick. */
    data class PhoneBattery(val percent: Int, val charging: Boolean, val receivedAtMs: Long)

    /** Keeps the battery of [tick], received at [receivedAtMs] (an unknown battery is kept as unknown). */
    fun save(context: Context, tick: ModWatchMessages.Tick, receivedAtMs: Long) {
        prefs(context).edit()
            .putInt(KEY_PERCENT, tick.batteryPercent ?: ModWatchMessages.BATTERY_UNKNOWN)
            .putBoolean(KEY_CHARGING, tick.charging)
            .putLong(KEY_AT, receivedAtMs)
            .apply()
    }

    /** The phone battery percent to show at [nowMs]; `null` - none, unknown or too old ("-" on the face). */
    fun read(context: Context, nowMs: Long): Int? = readFull(context, nowMs)?.percent

    /** As [read], with the charging state and the time of receipt. */
    fun readFull(context: Context, nowMs: Long): PhoneBattery? {
        val prefs = prefs(context)
        if (!prefs.contains(KEY_AT)) return null
        val saved = PhoneBattery(
            prefs.getInt(KEY_PERCENT, ModWatchMessages.BATTERY_UNKNOWN),
            prefs.getBoolean(KEY_CHARGING, false),
            prefs.getLong(KEY_AT, 0L),
        )
        return fresh(saved, nowMs)
    }

    /**
     * [saved] when its percent is known and it is at most [MAX_AGE_MS] old at [nowMs]; a time of receipt after
     * [nowMs] (the watch clock set back) counts as too old - the next tick, within 5 minutes, puts a fresh one.
     */
    fun fresh(saved: PhoneBattery?, nowMs: Long): PhoneBattery? {
        if (saved == null || saved.percent !in 0..100) return null
        return if (nowMs - saved.receivedAtMs in 0..MAX_AGE_MS) saved else null
    }

    internal fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
