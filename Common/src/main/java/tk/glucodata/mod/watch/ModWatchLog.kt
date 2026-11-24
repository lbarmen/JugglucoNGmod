package tk.glucodata.mod.watch

import android.util.Log

/**
 * JugglucoNGmod, watch block: our log lines (tag `ModWatch`), on the phone and on the watch. Through `Log.println`, not
 * `Log.i`/`Log.w`: upstream's R8 rules strip `Log.v/d/i/w` from release builds. Never anything personal: codes,
 * counts and exception classes only - no glucose, heart rate, steps, battery or event times. Not upstream's
 * `tk.glucodata.Log`: that one is exported with their logs.
 */
internal object ModWatchLog {

    const val TAG = "ModWatch"

    fun i(message: String) {
        try {
            Log.println(Log.INFO, TAG, message)
        } catch (_: Throwable) {
        }
    }

    fun w(message: String) {
        try {
            Log.println(Log.WARN, TAG, message)
        } catch (_: Throwable) {
        }
    }
}
