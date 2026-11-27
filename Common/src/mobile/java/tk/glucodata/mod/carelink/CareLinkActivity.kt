package tk.glucodata.mod.carelink

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import tk.glucodata.mod.pumpbt.PumpBtActivity

/**
 * JugglucoNGmod: the old CareLink window, now only a forwarder to the pump window ([PumpBtActivity]) with no screen of
 * its own - opens it and finishes. Kept, with its name, for what may still point here: a launcher shortcut the person
 * pinned (id `mod-carelink`) and notifications or pending intents made by an older build. Our settings row, the
 * dynamic shortcut and the notifications open the pump window directly.
 *
 * Declared only in Common/src/mobileMod/AndroidManifest.xml (not exported, a translucent theme: nothing is drawn);
 * [intentIfDeclared] gives null in upstream's builds. The pump window itself closes while upstream's restricted mode
 * is locked.
 */
class CareLinkActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        forward(this)?.let { target ->
            try {
                startActivity(target)
            } catch (t: Throwable) {
                CareLinkLog.w("pump window not opened (${t.javaClass.simpleName})")
            }
        }
        finish()
    }

    companion object {
        /** Where this window sends the person: the pump window when the build declares it, else nowhere (null). */
        internal fun forward(context: Context): Intent? = PumpBtActivity.intentIfDeclared(context)

        /**
         * The intent of this window when the build declares it (the phone `mod` build), else null: upstream's builds
         * compile the class but have no such window (no BuildConfig field tells it in debug).
         */
        fun intentIfDeclared(context: Context): Intent? {
            val component = android.content.ComponentName(context, CareLinkActivity::class.java)
            return try {
                // the manifest entry itself (not declared - NameNotFoundException); resolveActivity is no help in tests:
                // Robolectric resolves an explicit intent of any class it knows
                context.packageManager.getActivityInfo(component, 0)
                Intent().setComponent(component)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
