package tk.glucodata.mod.watch

import android.app.Activity
import android.content.Context
import android.os.Bundle

/**
 * JugglucoNGmod, watch block: the "Snooze alarm" launcher entry without a screen - the user puts it on a hardware
 * button of the watch. A plain [Activity] (Theme.NoDisplay in our manifest does not go with an AppCompat theme) that
 * finishes inside onCreate; the work is [SnoozeButton.press]. Declared only in Common/src/wearMod/AndroidManifest.xml.
 */
class SnoozeButtonActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            SnoozeButton.press(applicationContext, send = send)
        } catch (t: Throwable) {
            ModWatchLog.w("button activity failed: ${t.javaClass.simpleName}")
        } finally {
            finish()
        }
    }

    companion object {
        /** How the press reaches the phone; tests put a recorder here instead of the real Data Layer. */
        @Volatile
        internal var send: (Context, Long, () -> Unit) -> Unit = WatchPhoneLink::sendSnooze
    }
}
