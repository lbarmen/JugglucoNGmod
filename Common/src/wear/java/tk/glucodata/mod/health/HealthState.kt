package tk.glucodata.mod.health

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * JugglucoNGmod, watch block (part 4): what heart rate and steps collection keeps between runs of the watch process,
 * in our own preferences file [PREFS] (not upstream's settings): the batch number, when a batch went, when data came,
 * the open heart rate windows, what the last status and battery rows said. Times are watch times (ms of the epoch).
 */
class HealthState(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The next batch number, written at once (commit) so a number never repeats after a crash. */
    fun nextSeq(): Long = synchronized(seqLock) {
        val next = prefs.getLong(KEY_SEQ, 0L) + 1
        prefs.edit().putLong(KEY_SEQ, next).commit()
        next
    }

    /** The last batch number given out (0 - none yet). */
    val seq: Long get() = prefs.getLong(KEY_SEQ, 0L)

    /** When a batch last went into the Data Layer / when a send was last tried (also by a tick that is under way). */
    var lastSentAt by long("lastSentAt")
    var lastAttemptAt by long("lastAttemptAt")

    /** When the source last delivered data (the source's "silent for 15 min" check reads it, part 4b). */
    var lastDataAt by long("lastDataAt")

    /** Open 1-minute heart rate windows ([HrAggregator.encode]; Watch 7i). */
    var hrBuckets by string("hrBuckets1")

    /**
     * Open 5-minute windows a watch app before Watch 7i left under the old key: [HealthCore] queues them once as
     * `hr5` rows and empties this, so they are never read as 1-minute windows.
     */
    var hrBucketsOld5 by string("hrBuckets")

    /** End of the latest closed heart rate window, and how many readings came for a closed window since install. */
    var hrClosedUntil by long("hrClosedUntil")
    var hrLate by long("hrLate")

    /** When the last status row went and its permanent part (without ages) - the next goes on a change or after 30 min. */
    var lastStatusAt by long("lastStatusAt")
    var lastStatusStable by string("lastStatusStable")

    /** The last battery row (-1 - none yet): a new row only when it changes. */
    var lastBattery by int("lastBattery", -1)
    var lastCharging by boolean("lastCharging")

    /** Class of the last send failure, empty after a good send. */
    var lastError by string("lastError")

    /**
     * The Health Services subscription (part 4b): when it was made, what it covers ("hr,steps"; empty - none or lost),
     * the permissions it was made with ([HealthPermissions.Perms.short]), the last attempt and its failure class.
     */
    var registeredAt by long("registeredAt")
    var registeredTypes by string("registeredTypes")
    var registeredPerms by string("registeredPerms")
    var registerAttemptAt by long("registerAttemptAt")
    var registerError by string("registerError")

    /** Failed subscription attempts in a row (0 after a good one): the pause before the next grows with it. */
    var registerFailures by int("registerFailures", 0)

    /** The page asked for the background permission once (the next time it opens the app settings instead). */
    var askedBackground by boolean("askedBackground")

    private fun long(key: String) = pref({ prefs.getLong(key, 0L) }) { putLong(key, it) }

    private fun int(key: String, default: Int) = pref({ prefs.getInt(key, default) }) { putInt(key, it) }

    private fun boolean(key: String) = pref({ prefs.getBoolean(key, false) }) { putBoolean(key, it) }

    private fun string(key: String) = pref({ prefs.getString(key, "").orEmpty() }) { putString(key, it) }

    private fun <T> pref(read: () -> T, write: SharedPreferences.Editor.(T) -> Unit) =
        object : ReadWriteProperty<Any?, T> {
            override fun getValue(thisRef: Any?, property: KProperty<*>): T = read()

            override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
                prefs.edit().apply { write(value) }.apply()
            }
        }

    companion object {
        /** Our preferences file for heart rate and steps on the watch (part 4). */
        const val PREFS = "mod_health"

        private const val KEY_SEQ = "seq"
        private val seqLock = Any()
    }
}
