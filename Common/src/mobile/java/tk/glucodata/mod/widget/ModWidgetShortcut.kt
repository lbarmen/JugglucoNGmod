package tk.glucodata.mod.widget

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import tk.glucodata.R

/**
 * JugglucoNGmod widget: the "Widget settings" shortcut on a long press of the app icon - the way back into the
 * settings window if a launcher shows no "Settings" on the widget itself. A dynamic shortcut, there only while at least
 * one widget of ours is placed (pushed on enable and update, removed on disable); other shortcuts stay.
 */
object ModWidgetShortcut {

    const val ID = "mod_widget"

    fun publish(context: Context): Boolean = try {
        val intent = Intent(context, ModWidgetConfigActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val shortcut = ShortcutInfoCompat.Builder(context, ID)
            .setShortLabel(context.getString(R.string.mod_widget_shortcut_short))
            .setLongLabel(context.getString(R.string.mod_widget_settings_title))
            .setIcon(IconCompat.createWithResource(context, context.applicationInfo.icon))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)
        true
    } catch (t: Throwable) {
        ModWidgetLog.w("shortcut failed: ${t.javaClass.simpleName}")
        false
    }

    fun remove(context: Context) {
        try {
            ShortcutManagerCompat.removeDynamicShortcuts(context, listOf(ID))
        } catch (t: Throwable) {
            ModWidgetLog.w("shortcut not removed: ${t.javaClass.simpleName}")
        }
    }
}
