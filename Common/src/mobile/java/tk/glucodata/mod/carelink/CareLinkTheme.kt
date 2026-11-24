package tk.glucodata.mod.carelink

import android.content.Context
import tk.glucodata.ui.ThemeMode

/**
 * JugglucoNGmod, CareLink: the theme upstream's main window uses (light / dark / system), read where it keeps it
 * (MobileComposeHost: `<package>_preferences`, key `theme_mode`). Kept out of the Compose files on purpose: upstream's
 * ArchitectureGateTests fails a Compose file (one declaring composables) that touches preferences directly.
 */
internal object CareLinkTheme {
    fun mode(context: Context): ThemeMode = try {
        val prefs = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
        ThemeMode.valueOf(prefs.getString("theme_mode", "SYSTEM") ?: "SYSTEM")
    } catch (_: Throwable) {
        ThemeMode.SYSTEM
    }
}
