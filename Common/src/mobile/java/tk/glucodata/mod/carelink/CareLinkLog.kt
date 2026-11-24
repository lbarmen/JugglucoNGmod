package tk.glucodata.mod.carelink

import android.util.Log

/**
 * JugglucoNGmod, CareLink: our log lines (tag `ModCareLink`). Through `Log.println`, not `Log.i`/`Log.w`: upstream's R8
 * rules (Common/proguard-rules.log) strip `Log.v/d/i/w` from release builds, so none of our lines ("poll ok", errors by
 * code) reached the device (found by the emulator trial, part 4p). Never anything personal: codes, counts and
 * exception classes only - no keys, user name, answer bodies or addresses. Not upstream's `tk.glucodata.Log`: that one
 * is exported with their logs.
 */
internal object CareLinkLog {

    const val TAG = "ModCareLink"

    fun i(message: String) {
        Log.println(Log.INFO, TAG, message)
    }

    fun w(message: String) {
        Log.println(Log.WARN, TAG, message)
    }
}
