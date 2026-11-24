package tk.glucodata.mod.watch

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/**
 * JugglucoNGmod, watch block: this device's battery from the system's sticky ACTION_BATTERY_CHANGED - the phone's for
 * `/mod/tick`, the watch's for our watch face. Our own small reader (upstream's battery readers are private to their
 * classes). Never throws; anything unreadable is `null`.
 */
object ModBattery {

    /** [percent] 0-100; [charging] - charging now or full on the charger. */
    data class Reading(val percent: Int, val charging: Boolean)

    /** The battery now, from the sticky broadcast (no receiver is registered); `null` when unknown. */
    fun read(context: Context): Reading? = try {
        fromIntent(context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
    } catch (_: Throwable) {
        null
    }

    /** The battery in an ACTION_BATTERY_CHANGED [intent]; `null` when its level is missing or broken. */
    fun fromIntent(intent: Intent?): Reading? {
        if (intent == null) return null
        val percent = percent(
            intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        ) ?: return null
        return Reading(percent, isCharging(intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)))
    }

    /** [level] of [scale] as a whole percent 0-100 (rounded down, as the system shows it); `null` when broken. */
    fun percent(level: Int, scale: Int): Int? {
        if (level < 0 || scale <= 0 || level > scale) return null
        return (level.toLong() * 100 / scale).toInt()
    }

    /** [status] (BatteryManager.EXTRA_STATUS) means the device is on its charger. */
    fun isCharging(status: Int): Boolean =
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
}
