package tk.glucodata.mod.pumpbt

import android.util.Log

/**
 * JugglucoNGmod, pump over Bluetooth (trial): logcat lines (tag `ModPumpBt`). Only stages and errors - never values,
 * the pump's serial number, addresses or key material; those go to the trial journal on the phone ([PumpBtJournal]).
 * Through `Log.println` like CareLinkLog: upstream's R8 rules strip `Log.i/w` from release builds.
 */
internal object PumpBtLog {

    const val TAG = "ModPumpBt"

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
