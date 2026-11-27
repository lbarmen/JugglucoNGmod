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
 * Steps, each in its own `try` (one failure does not stop the others; every file and key on its own too):
 * (a) "was signed in" = `session.bin`, `pending.json` or `enabled` exists - written to the mark file before anything
 *     is removed, so a retry after a failure still knows it;
 * (b) the cloud's WorkManager works by name (no worker class needed);
 * (c) the cloud's alarm, found by its old receiver name, action and request code - the pump watchdog
 *     (`tk.glucodata.mod.pumpbt.WATCHDOG`, request code 1 947 020) has another action, receiver and code and stays;
 * (d) the "sign in again" notification; with the Bluetooth source off also the marks and clock of "no data" and "not
 *     saved" ([CareLinkNotifier.cancelAll]): left by the cloud, they would keep the source's first "no data" back and
 *     show a false "not saved" (critic D1, C-2); with the source on they are the source's and stay;
 * (e) `session.bin`, `pending.json` (and their `.tmp`), `enabled`; then the Keystore key of the session;
 * (f) preferences: `mod_carelink_schedule` whole, the cloud keys of `mod_carelink_state` and `mod_carelink_settings`;
 * (g) only for someone who was signed in, and only once: the cookies of the sign-in page (upstream uses no WebView).
 *
 * Kept: `seen.json`, `pump.json`, `mod_carelink_state` (its other keys: `lastDataAtMs` is the first catch-up's
 * boundary), `mod_carelink_settings`, the `mod_carelink` channel, the shortcut and all of `mod-pumpbt/`.
 *
 * Done once (b)-(f) went through without an exception, or after [MAX_FAILED_RUNS] runs that each failed somewhere
 * ("done" with "partial": never again - a step that always fails must not make every start pay; critic D1, C-1). "Done"
 * and "cookies tried" are written before the cookies are touched: a WebView that hangs or takes the process down is
 * never started again by this. One log line per run says what was found and removed - codes only, nothing personal.
 * Not for the main thread (the WebView start waits for it).
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
    /** Runs that failed somewhere before the cleanup counts as done anyway ("partial"). */
    internal const val MAX_FAILED_RUNS = 3
    private const val SIGNED_IN = "signed-in"
    private const val DONE = "done"
    private const val PARTIAL = "partial"
    private const val COOKIES_TRIED = "cookies-tried"
    private const val ATTEMPT = "attempt="

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
        val failedRuns = before.firstOrNull { it.startsWith(ATTEMPT) }?.removePrefix(ATTEMPT)?.toIntOrNull() ?: 0
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
            if (signedIn && SIGNED_IN !in before) writeMark(mark, before + SIGNED_IN)
        }
        val firstFailure = failed.size

        // (b)
        for (work in WORKS) step("work $work") { cancelWork(app, work) }
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
        step("marks") {
            if (!PumpSourceSwitch.bluetoothOn(app)) {
                CareLinkNotifier.cancelAll(app)
                found += "notification marks"
            }
        }
        // (e) the files first, the key after the session file is gone
        for (name in listOf(SESSION_FILE, PENDING_FILE, CareLinkSettings.ENABLED_FILE)) {
            for (f in listOf(File(dir, name), File(dir, "$name.tmp"))) {
                step("file ${f.name}") {
                    if (f.exists()) {
                        if (!f.delete()) throw java.io.IOException("not deleted")
                        found += f.name
                    }
                }
            }
        }
        step("key") { if (deleteKey()) found += "key" }
        // (f)
        step("schedule") {
            val schedule = app.getSharedPreferences(SCHEDULE_PREFS, Context.MODE_PRIVATE)
            if (schedule.all.isNotEmpty()) found += "schedule"
            if (!schedule.edit().clear().commit()) throw java.io.IOException("schedule not cleared")
            app.deleteSharedPreferences(SCHEDULE_PREFS) // the file too; false only if there was none
        }
        step("state") {
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
        }
        step("settings") {
            val settings = app.getSharedPreferences(CareLinkSettings.PREFS, Context.MODE_PRIVATE)
            if (settings.contains(SETTINGS_INTERVAL)) {
                found += "interval"
                if (!settings.edit().remove(SETTINGS_INTERVAL).commit()) throw java.io.IOException("settings not written")
            }
        }
        val stepsOk = failed.size == firstFailure
        val runs = if (stepsOk) failedRuns else failedRuns + 1
        val finish = stepsOk || runs >= MAX_FAILED_RUNS

        // "done" (and "cookies tried") before the cookies: they never make a start pay again (critic D1, C-1)
        var written = false
        step("done") {
            val lines = buildSet {
                if (signedIn) add(SIGNED_IN)
                if (finish) {
                    add(DONE)
                    if (!stepsOk) add(PARTIAL)
                    if (signedIn) add(COOKIES_TRIED)
                } else {
                    add("$ATTEMPT$runs")
                }
            }
            writeMark(mark, lines)
            written = true
        }
        val tryCookies = finish && written && signedIn && COOKIES_TRIED !in before
        CareLinkLog.i(
            "cloud cleanup: signed in ${if (signedIn) "yes" else "no"}; removed " +
                found.joinToString().ifEmpty { "nothing" } +
                (if (failed.isEmpty()) "" else "; failed ${failed.joinToString()}") +
                when {
                    !written -> "; mark not written, will retry on the next start"
                    finish && stepsOk -> "; done"
                    finish -> "; done partially after $runs failed runs, not tried again"
                    else -> "; failed run $runs of $MAX_FAILED_RUNS, will retry on the next start"
                } +
                (if (tryCookies) "; clearing the sign-in page cookies once" else ""),
        )
        // (g) once; a failure is logged, never retried
        if (tryCookies) {
            try {
                clearCookies()
                CareLinkLog.i("cloud cleanup: sign-in page cookies cleared")
            } catch (t: Throwable) {
                CareLinkLog.w("cloud cleanup: cookies not cleared (${t.javaClass.simpleName}), not tried again")
            }
        }
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
