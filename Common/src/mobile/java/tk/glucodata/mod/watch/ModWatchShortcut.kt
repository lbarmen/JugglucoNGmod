package tk.glucodata.mod.watch

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import tk.glucodata.R

/**
 * JugglucoNGmod, watch block (part 5): the launcher shortcut "JugglucoNGmod watch" on a long press of the app icon,
 * opening our check screen ([ModWatchActivity]). A dynamic shortcut pushed by our code at start - no hook, not
 * upstream's `shortcuts.xml`. The system starts a shortcut as our own app, so the screen itself stays not exported.
 * The icon is the app's own.
 */
object ModWatchShortcut {

    const val ID = "mod_watch"

    fun publishAsync(context: Context) {
        val app = context.applicationContext ?: context
        try {
            ModWatchStore.executor.execute { publish(app) }
        } catch (t: Throwable) {
            ModWatchLog.w("watch shortcut not queued: ${t.javaClass.simpleName}")
        }
    }

    /** Pushes (or refreshes) the shortcut; the label follows the language of the moment. */
    fun publish(context: Context): Boolean = try {
        val intent = Intent(context, ModWatchActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(context.getString(R.string.mod_watch_screen_short))
            .setLongLabel(context.getString(R.string.mod_watch_screen_title))
            .setIcon(IconCompat.createWithResource(context, context.applicationInfo.icon))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        true
    } catch (t: Throwable) {
        ModWatchLog.w("watch shortcut failed: ${t.javaClass.simpleName}")
        false
    }
}
