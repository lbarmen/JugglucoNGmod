package tk.glucodata.mod.widget

import android.content.Context
import tk.glucodata.ui.ThemeMode

/**
 * JugglucoNGmod widget: the theme upstream's main window uses (light / dark / system), read where it keeps it
 * (`<package>_preferences`, key `theme_mode`). Outside the Compose files on purpose (upstream's ArchitectureGateTests).
 */
internal object ModWidgetTheme {
    fun mode(context: Context): ThemeMode = try {
        val prefs = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
        ThemeMode.valueOf(prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM")
    } catch (_: Throwable) {
        ThemeMode.SYSTEM
    }
}
