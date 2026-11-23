package tk.glucodata.mod.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import tk.glucodata.GlucoseUpdateBroadcaster

/**
 * JugglucoNGmod widget "JugglucoNGmod" (xDrip look: the number in range color, arrow, 5-minute delta, "N min ago",
 * 3 h of 5-minute average dots; ours, next to upstream's two widgets, no change in their files).
 *
 * Redrawn on upstream's "new reading" broadcast (sent to every receiver of the package), on the system's update and
 * resize, and by our own "N min ago" alarm. Declared only in the manifest of the `mod` build
 * (Common/src/mobileMod/AndroidManifest.xml).
 */
class ModWidgetProvider : AppWidgetProvider() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                GlucoseUpdateBroadcaster.ACTION_GLUCOSE_UPDATE, ModWidgetUpdater.ACTION_TICK -> {
                    if (ModWidgetUpdater.widgetIds(context).isEmpty()) {
                        ModWidgetUpdater.cancelTick(context)
                        return
                    }
                    ModWidgetUpdater.requestAll(context, goAsync())
                }
                else -> super.onReceive(context, intent)
            }
        } catch (t: Throwable) {
            ModWidgetLog.w("receive failed: ${t.javaClass.simpleName}")
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        try {
            ModWidgetShortcut.publish(context)
            ModWidgetUpdater.requestAll(context, goAsync())
        } catch (t: Throwable) {
            ModWidgetLog.w("update failed: ${t.javaClass.simpleName}")
        }
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        try {
            ModWidgetUpdater.requestAll(context, goAsync())
        } catch (t: Throwable) {
            ModWidgetLog.w("resize failed: ${t.javaClass.simpleName}")
        }
    }

    override fun onEnabled(context: Context) {
        try {
            ModWidgetShortcut.publish(context)
        } catch (t: Throwable) {
            ModWidgetLog.w("enable failed: ${t.javaClass.simpleName}")
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        try {
            appWidgetIds.forEach { ModWidgetSettingsStore.delete(context, it) }
        } catch (t: Throwable) {
            ModWidgetLog.w("delete failed: ${t.javaClass.simpleName}")
        }
    }

    override fun onDisabled(context: Context) {
        try {
            ModWidgetUpdater.cancelTick(context)
            ModWidgetShortcut.remove(context)
        } catch (t: Throwable) {
            ModWidgetLog.w("disable failed: ${t.javaClass.simpleName}")
        }
    }
}
