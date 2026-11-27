package tk.glucodata.mod.carelink

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.FileOutputStream

/**
 * Pump data: nothing yet, the last cycle wrote fine, the last cycle failed. Values of older builds (the cloud's
 * `NEED_LOGIN`, part D1) read as [OFF].
 */
enum class CareLinkStatus { OFF, OK, ERROR }

/**
 * Why the last cycle failed: the journal or basal write. The cloud's kinds of older builds (`NO_NETWORK`, `SERVER`,
 * `BAD_ANSWER`, `STORAGE`, `PARSE`; part D1) read as no error.
 */
enum class CareLinkErrorKind { WRITE }

/** A cycle failure: kind, code (0 - none) and the exception class or a short reason - never a body or a key. */
data class CareLinkError(val kind: CareLinkErrorKind, val code: Int = 0, val errorClass: String? = null)

/**
 * JugglucoNGmod, pump data (stage 4c; the cloud left in part D1): the state for the window and the notifications; no
 * keys, no user name. [lastPollAtMs] - the last pump cycle (any result), [lastDataAtMs] - the newest pump data time
 * seen (moves only forward; the first catch-up's boundary - PumpBtBridge), [lastError] - why the last cycle failed
 * (null after a good one), [pumpNow] - what the pump showed in the last snapshot.
 */
data class CareLinkState(
    val status: CareLinkStatus = CareLinkStatus.OFF,
    val lastPollAtMs: Long? = null,
    val lastDataAtMs: Long? = null,
    val lastError: CareLinkError? = null,
    val pumpNow: PumpNow? = null,
)

/**
 * Where [CareLinkState] lives: SharedPreferences `mod_carelink_state` (upstream's settings backup takes every
 * shared_prefs file - nothing secret here) and the "shown once" marks of [CareLinkNotifier]. The pump snapshot (active insulin, reservoir - medical numbers) is a file in `noBackupFilesDir`
 * (`mod-carelink/pump.json`), out of the settings backup (critic 4c, N9).
 */
class CareLinkStateStore(context: Context) {

    private val app = context.applicationContext

    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val pumpFile: File get() = File(File(app.noBackupFilesDir, DIR), PUMP_FILE)

    fun read(): CareLinkState = CareLinkState(
        status = prefs.getString(K_STATUS, null)?.let { s -> CareLinkStatus.values().firstOrNull { it.name == s } }
            ?: CareLinkStatus.OFF,
        lastPollAtMs = long(K_POLL),
        lastDataAtMs = long(K_DATA),
        lastError = prefs.getString(K_ERROR_KIND, null)
            ?.let { s -> CareLinkErrorKind.values().firstOrNull { it.name == s } }
            ?.let { CareLinkError(it, prefs.getInt(K_ERROR_CODE, 0), prefs.getString(K_ERROR_CLASS, null)) },
        pumpNow = readPump(),
    )

    fun write(s: CareLinkState) {
        prefs.edit().apply {
            putString(K_STATUS, s.status.name)
            putOrRemove(K_POLL, s.lastPollAtMs, this)
            putOrRemove(K_DATA, s.lastDataAtMs, this)
            if (s.lastError == null) {
                remove(K_ERROR_KIND).remove(K_ERROR_CODE).remove(K_ERROR_CLASS)
            } else {
                putString(K_ERROR_KIND, s.lastError.kind.name)
                putInt(K_ERROR_CODE, s.lastError.code)
                putString(K_ERROR_CLASS, s.lastError.errorClass)
            }
            remove(K_PUMP_OLD)
        }.commit()
        writePump(s.pumpNow)
    }

    fun update(change: (CareLinkState) -> CareLinkState): CareLinkState = synchronized(LOCK) {
        change(read()).also(::write)
    }

    /**
     * [update] only while [allowed] holds, checked under the store's lock (critic 4c, N2: a source turned off during a
     * cycle must not be overwritten by its result). null - not allowed, nothing written.
     */
    fun updateIf(allowed: () -> Boolean, change: (CareLinkState) -> CareLinkState): CareLinkState? = synchronized(LOCK) {
        if (!allowed()) null else change(read()).also(::write)
    }

    /** "No pump data for N min" was shown and the data has not come back yet. */
    var noDataShown: Boolean
        get() = prefs.getBoolean(K_NO_DATA_SHOWN, false)
        set(v) {
            prefs.edit().putBoolean(K_NO_DATA_SHOWN, v).apply()
        }

    /**
     * When the journal or basal write started failing poll after poll (null - the last poll wrote fine); cleared by a
     * good poll. For the "pump records are not saved" notification (critic 4d2, C3).
     */
    var writeFailSinceMs: Long?
        get() = long(K_WRITE_FAIL_SINCE)
        set(v) {
            prefs.edit().apply { if (v == null) remove(K_WRITE_FAIL_SINCE) else putLong(K_WRITE_FAIL_SINCE, v) }.apply()
        }

    /** "Pump records are not saved" was shown and no write has worked since. */
    var writeFailShown: Boolean
        get() = prefs.getBoolean(K_WRITE_FAIL_SHOWN, false)
        set(v) {
            prefs.edit().putBoolean(K_WRITE_FAIL_SHOWN, v).apply()
        }

    private fun readPump(): PumpNow? {
        val f = pumpFile
        if (f.isFile) {
            return try {
                PumpNow.fromJson(CareLinkConfig.parse(f.readText(Charsets.UTF_8)))
            } catch (t: Throwable) {
                CareLinkLog.w("pump snapshot not read (${t.javaClass.simpleName})")
                null
            }
        }
        // 4c kept it in the preferences: taken once, moved to the file by the next write
        return prefs.getString(K_PUMP_OLD, null)?.let { PumpNow.fromJson(CareLinkConfig.parse(it)) }
    }

    private fun writePump(p: PumpNow?) {
        val f = pumpFile
        if (p == null) {
            f.delete()
            return
        }
        try {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, "$PUMP_FILE.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(p.toJson().toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) CareLinkLog.w("pump snapshot not moved into place")
            }
        } catch (t: Throwable) {
            CareLinkLog.w("pump snapshot not written (${t.javaClass.simpleName})")
        }
    }

    private fun long(k: String): Long? = if (prefs.contains(k)) prefs.getLong(k, 0L) else null

    private fun putOrRemove(k: String, v: Long?, e: SharedPreferences.Editor) {
        if (v == null) e.remove(k) else e.putLong(k, v)
    }

    companion object {
        const val PREFS = "mod_carelink_state"
        const val DIR = "mod-carelink"
        const val PUMP_FILE = "pump.json"
        private val LOCK = Any()
        private const val K_STATUS = "status"
        private const val K_POLL = "lastPollAtMs"
        private const val K_DATA = "lastDataAtMs"
        private const val K_ERROR_KIND = "lastErrorKind"
        private const val K_ERROR_CODE = "lastErrorCode"
        private const val K_ERROR_CLASS = "lastErrorClass"
        private const val K_PUMP_OLD = "pumpNow"
        private const val K_NO_DATA_SHOWN = "noDataShown"
        private const val K_WRITE_FAIL_SINCE = "writeFailSinceMs"
        private const val K_WRITE_FAIL_SHOWN = "writeFailShown"
    }
}

/**
 * JugglucoNGmod, pump data (stage 4c; the cloud's polling flag and interval left in part D1): settings of the pump
 * window (SharedPreferences `mod_carelink_settings`, no secrets). [presetId] - insulin type of pump boluses, null -
 * upstream's default for calculation (4d); [writeCarbs] - record calculator carbs (on); [sendToNightscout] - pump
 * records go to Nightscout (off; plan, decision 8); [warnAfterMin] - "no pump data" and "pump records are not saved"
 * come after this long: 5/10/15/20/30/60 min stored, default 30 (one threshold for both - decision of 4e).
 */
class CareLinkSettings(context: Context) {

    private val app = context.applicationContext

    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var presetId: Long?
        get() = if (prefs.contains(K_PRESET)) prefs.getLong(K_PRESET, 0L) else null
        set(v) {
            prefs.edit().apply { if (v == null) remove(K_PRESET) else putLong(K_PRESET, v) }.apply()
        }

    var writeCarbs: Boolean
        get() = prefs.getBoolean(K_CARBS, true)
        set(v) {
            prefs.edit().putBoolean(K_CARBS, v).apply()
        }

    var sendToNightscout: Boolean
        get() = prefs.getBoolean(K_NIGHTSCOUT, false)
        set(v) {
            prefs.edit().putBoolean(K_NIGHTSCOUT, v).apply()
        }

    var warnAfterMin: Int
        get() = prefs.getInt(K_WARN, DEFAULT_WARN_MIN).takeIf { it in WARN_AFTER_MIN } ?: DEFAULT_WARN_MIN
        set(v) {
            prefs.edit().putInt(K_WARN, v.takeIf { it in WARN_AFTER_MIN } ?: DEFAULT_WARN_MIN).apply()
        }

    /** [warnAfterMin] in milliseconds. */
    val warnAfterMs: Long get() = warnAfterMin * 60_000L

    /**
     * The CareLink window has asked Android for the notification permission once (critic 4d, N5): after two refusals
     * Android 13+ refuses the request silently, so the button then opens the app's notification screen at once.
     */
    var notificationsAsked: Boolean
        get() = prefs.getBoolean(K_NOTIFICATIONS_ASKED, false)
        set(v) {
            prefs.edit().putBoolean(K_NOTIFICATIONS_ASKED, v).apply()
        }

    companion object {
        const val PREFS = "mod_carelink_settings"
        const val DEFAULT_WARN_MIN = 30
        /**
         * What [warnAfterMin] stores: the pump window's [WARN_CHOICES] and the older 5/10/15, kept as they are (over
         * Bluetooth at least 20 act - [CareLinkNotifier]); old values are not rewritten.
         */
        val WARN_AFTER_MIN = listOf(5, 10, 15, 20, 30, 60)
        /** The thresholds the pump window offers (decisions for the window, 5). */
        val WARN_CHOICES = listOf(20, 30, 60)

        /** The window's choice for a stored threshold: 5/10/15 act as 20 over Bluetooth - 20; a choice as it is; else 30. */
        fun shownWarnMin(stored: Int): Int = when (stored) {
            in WARN_CHOICES -> stored
            5, 10, 15 -> 20
            else -> DEFAULT_WARN_MIN
        }
        /** The cloud's polling flag of older builds in noBackupFilesDir/mod-carelink/ ([CareLinkCloudCleanup] removes it). */
        const val ENABLED_FILE = "enabled"
        private const val K_PRESET = "presetId"
        private const val K_CARBS = "writeCarbs"
        private const val K_NIGHTSCOUT = "sendToNightscout"
        private const val K_WARN = "warnAfterMin"
        private const val K_NOTIFICATIONS_ASKED = "notificationsAsked"
    }
}
