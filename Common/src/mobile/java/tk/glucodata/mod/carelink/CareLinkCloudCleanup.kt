package tk.glucodata.mod.carelink

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import androidx.work.WorkManager
import java.io.File
import java.security.KeyStore

/**
 * JugglucoNGmod, pump over Bluetooth (part D1): the CareLink cloud left the app; this removes, once, what it left on a
 * phone that had it. Without it WorkManager would wake the app every 15 min for a worker class that no longer exists,
 * and the encrypted session (Medtronic tokens) would stay on the phone.
 *
 * Steps, each in its own `try` (one failure does not stop the others):
 * (a) "was signed in" = `session.bin`, `pending.json` or `enabled` exists - written to the mark file before anything
 *     is removed, so a retry after a failure still knows it;
 * (b) the cloud's WorkManager works by name (no worker class needed);
 * (c) the cloud's alarm, found by its old receiver name, action and request code - the pump watchdog
 *     (`tk.glucodata.mod.pumpbt.WATCHDOG`, request code 1 947 020) has another action, receiver and code and stays;
 * (d) the "sign in again" notification;
 * (e) `session.bin`, `pending.json` (and their `.tmp`), `enabled`; then the Keystore key of the session;
 * (f) preferences: `mod_carelink_schedule` whole, the cloud keys of `mod_carelink_state` and `mod_carelink_settings`;
 * (g) only for someone who was signed in: the cookies of the sign-in page (upstream uses no WebView).
 *
 * Kept: `seen.json`, `pump.json`, `mod_carelink_state` (its other keys: `lastDataAtMs` is the first catch-up's
 * boundary), `mod_carelink_settings`, the `mod_carelink` channel, the shortcut and all of `mod-pumpbt/`.
 *
 * Done (the mark file says so, later runs do nothing) once (b)-(f) went through without an exception. One log line
 * says what was found and removed - codes only, nothing personal. Not for the main thread.
 */
object CareLinkCloudCleanup {

    const val MARK_FILE = "cloud-cleanup"
    internal const val SESSION_FILE = "session.bin"
    internal const val PENDING_FILE = "pending.json"
    internal val WORKS = listOf("mod-carelink-poll", "mod-carelink-now", "mod-carelink-periodic")
    internal const val ALARM_RECEIVER = "tk.glucodata.mod.carelink.CareLinkAlarmReceiver"
    internal const val ALARM_ACTION = "tk.glucodata.mod.carelink.POLL"
    internal const val ALARM_REQUEST_CODE = 1_947_000
    internal const val NEED_LOGIN_ID = 1_947_001
    internal const val KEYSTORE = "AndroidKeyStore"
    internal const val KEY_ALIAS = "mod_carelink_session"
    internal const val SCHEDULE_PREFS = "mod_carelink_schedule"
    internal val STATE_KEYS = listOf("firstPollPending", "needLoginShown")
    internal const val SETTINGS_INTERVAL = "intervalMin"
    private const val SIGNED_IN = "signed-in"
    private const val DONE = "done"

    /** Test seams: Robolectric has no WorkManager set up, no Android Keystore and may fail on the WebView. */
    @Volatile
    internal var cancelWork: (Context, String) -> Unit = { c, name -> WorkManager.getInstance(c).cancelUniqueWork(name) }

    @Volatile
    internal var deleteKey: () -> Boolean = {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        if (ks.containsAlias(KEY_ALIAS)) {
            ks.deleteEntry(KEY_ALIAS)
            true
        } else {
            false
        }
    }

    @Volatile
    internal var clearCookies: () -> Unit = {
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
    }

    private val lock = Any()

    /** Runs the cleanup unless it is done. Never throws. */
    fun run(context: Context) {
        synchronized(lock) {
            try {
                runLocked(context.applicationContext)
            } catch (t: Throwable) {
                CareLinkLog.w("cloud cleanup failed (${t.javaClass.simpleName})")
            }
        }
    }

    private fun runLocked(app: Context) {
        val dir = File(app.noBackupFilesDir, CareLinkStateStore.DIR)
        val mark = File(dir, MARK_FILE)
        val before = readMark(mark)
        if (DONE in before) return
        val found = mutableListOf<String>()
        val failed = mutableListOf<String>()
        fun step(name: String, block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                failed += "$name ${t.javaClass.simpleName}"
            }
        }

        // (a) before anything is removed; a retry keeps what an earlier run saw
        var signedIn = SIGNED_IN in before
        step("mark") {
            signedIn = signedIn || listOf(SESSION_FILE, PENDING_FILE, CareLinkSettings.ENABLED_FILE)
                .any { File(dir, it).exists() }
            if (signedIn && SIGNED_IN !in before) writeMark(mark, setOf(SIGNED_IN))
        }
        val firstFailure = failed.size

        // (b)
        step("work") { WORKS.forEach { cancelWork(app, it) } }
        // (c)
        step("alarm") {
            val intent = Intent().setClassName(app.packageName, ALARM_RECEIVER).setAction(ALARM_ACTION)
            val p = PendingIntent.getBroadcast(
                app, ALARM_REQUEST_CODE, intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            if (p != null) {
                (app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(p)
                p.cancel()
                found += "alarm"
            }
        }
        // (d)
        step("notification") {
            (app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)?.cancel(NEED_LOGIN_ID)
        }
        // (e) the files first, the key after the session file is gone
        step("files") {
            for (name in listOf(SESSION_FILE, PENDING_FILE, CareLinkSettings.ENABLED_FILE)) {
                for (f in listOf(File(dir, name), File(dir, "$name.tmp"))) {
                    if (f.exists()) {
                        if (!f.delete()) throw java.io.IOException("not deleted")
                        found += f.name
                    }
                }
            }
        }
        step("key") { if (deleteKey()) found += "key" }
        // (f)
        step("prefs") {
            val schedule = app.getSharedPreferences(SCHEDULE_PREFS, Context.MODE_PRIVATE)
            if (schedule.all.isNotEmpty()) found += "schedule"
            if (!schedule.edit().clear().commit()) throw java.io.IOException("schedule not cleared")
            app.deleteSharedPreferences(SCHEDULE_PREFS) // the file too; false only if there was none

            val state = app.getSharedPreferences(CareLinkStateStore.PREFS, Context.MODE_PRIVATE)
            val stateEdit = state.edit()
            var stateChanged = false
            for (k in STATE_KEYS) if (state.contains(k)) {
                stateEdit.remove(k)
                stateChanged = true
            }
            if (state.getString("status", null) == "NEED_LOGIN") {
                stateEdit.remove("status")
                stateChanged = true
            }
            if (stateChanged) {
                found += "state keys"
                if (!stateEdit.commit()) throw java.io.IOException("state not written")
            }

            val settings = app.getSharedPreferences(CareLinkSettings.PREFS, Context.MODE_PRIVATE)
            if (settings.contains(SETTINGS_INTERVAL)) {
                found += "interval"
                if (!settings.edit().remove(SETTINGS_INTERVAL).commit()) throw java.io.IOException("settings not written")
            }
        }
        val stepsOk = failed.size == firstFailure

        // (g) not part of "done": the WebView may be off on this phone
        if (signedIn) {
            step("cookies") {
                clearCookies()
                found += "cookies"
            }
        }

        var done = false
        if (stepsOk) {
            step("done") {
                writeMark(mark, buildSet { if (signedIn) add(SIGNED_IN); add(DONE) })
                done = true
            }
        }
        CareLinkLog.i(
            "cloud cleanup: signed in ${if (signedIn) "yes" else "no"}; removed " +
                found.joinToString().ifEmpty { "nothing" } +
                (if (failed.isEmpty()) "" else "; failed ${failed.joinToString()}") +
                (if (done) "; done" else "; will retry on the next start"),
        )
    }

    private fun readMark(mark: File): Set<String> = try {
        if (mark.isFile) mark.readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() }.toSet() else emptySet()
    } catch (t: Throwable) {
        emptySet()
    }

    private fun writeMark(mark: File, lines: Set<String>) {
        mark.parentFile?.mkdirs()
        mark.writeText(lines.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }
}
