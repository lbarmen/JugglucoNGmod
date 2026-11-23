package tk.glucodata.mod.widget

import android.content.Context
import kotlin.math.roundToInt

/** How the chart draws its readings. */
enum class ModWidgetDots { AVERAGE_5MIN, EACH, LINE }

/** Where the text goes: over the chart (top left, as xDrip) or in its own row above the chart. */
enum class ModWidgetLayout { OVERLAY, TOP }

/** Size of the big number: from the widget height, or fixed. */
enum class ModWidgetNumberSize { AUTO, S, M, L }

/**
 * JugglucoNGmod widget: the settings of one placed widget. The defaults are the xDrip look the user chose
 * (18.11.2026): transparent, 3 h of 5-minute averages, delta and age on, number colored by range, no range lines.
 */
data class ModWidgetSettings(
    /** Percent: 100 = fully transparent background, 0 = black. */
    val backgroundTransparency: Int = 100,
    val periodHours: Int = 3,
    val dots: ModWidgetDots = ModWidgetDots.AVERAGE_5MIN,
    val showDelta: Boolean = true,
    val showAge: Boolean = true,
    val colorByRange: Boolean = true,
    val rangeLines: Boolean = false,
    val layout: ModWidgetLayout = ModWidgetLayout.OVERLAY,
    val numberSize: ModWidgetNumberSize = ModWidgetNumberSize.AUTO,
) {
    val periodMillis: Long get() = periodHours * HOUR_MS

    /** 0..255 alpha of the black background. */
    val backgroundAlpha: Int get() = (255f * (100 - backgroundTransparency) / 100f).roundToInt().coerceIn(0, 255)

    /** Values outside the offered choices (an old or damaged file) fall back to the defaults. */
    fun sanitized(): ModWidgetSettings = copy(
        backgroundTransparency = backgroundTransparency.takeIf { it in TRANSPARENCY_CHOICES } ?: DEFAULT.backgroundTransparency,
        periodHours = periodHours.takeIf { it in PERIOD_CHOICES } ?: DEFAULT.periodHours,
    )

    companion object {
        const val HOUR_MS = 60L * 60L * 1000L
        val DEFAULT = ModWidgetSettings()
        val TRANSPARENCY_CHOICES = listOf(100, 75, 50, 25, 0)
        val PERIOD_CHOICES = listOf(3, 1, 6)
        val MAX_PERIOD_MS: Long = PERIOD_CHOICES.max() * HOUR_MS
    }
}

/**
 * The settings of each placed widget, in our own preference file `mod_widget` (keys `w<id>.<name>`), so the
 * settings of one widget never touch another's or upstream's. Removed in [delete] when the widget goes.
 * Kept out of the Compose files on purpose (upstream's ArchitectureGateTests).
 */
object ModWidgetSettingsStore {
    const val FILE = "mod_widget"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(id: Int, name: String) = "w$id.$name"

    fun read(context: Context, id: Int): ModWidgetSettings {
        val d = ModWidgetSettings.DEFAULT
        return try {
            val p = prefs(context)
            ModWidgetSettings(
                backgroundTransparency = p.getInt(key(id, "transparency"), d.backgroundTransparency),
                periodHours = p.getInt(key(id, "period"), d.periodHours),
                dots = enumOr(p.getString(key(id, "dots"), null), d.dots),
                showDelta = p.getBoolean(key(id, "delta"), d.showDelta),
                showAge = p.getBoolean(key(id, "age"), d.showAge),
                colorByRange = p.getBoolean(key(id, "color"), d.colorByRange),
                rangeLines = p.getBoolean(key(id, "lines"), d.rangeLines),
                layout = enumOr(p.getString(key(id, "layout"), null), d.layout),
                numberSize = enumOr(p.getString(key(id, "size"), null), d.numberSize),
            ).sanitized()
        } catch (_: Throwable) {
            d
        }
    }

    fun write(context: Context, id: Int, s: ModWidgetSettings) {
        prefs(context).edit()
            .putInt(key(id, "transparency"), s.backgroundTransparency)
            .putInt(key(id, "period"), s.periodHours)
            .putString(key(id, "dots"), s.dots.name)
            .putBoolean(key(id, "delta"), s.showDelta)
            .putBoolean(key(id, "age"), s.showAge)
            .putBoolean(key(id, "color"), s.colorByRange)
            .putBoolean(key(id, "lines"), s.rangeLines)
            .putString(key(id, "layout"), s.layout.name)
            .putString(key(id, "size"), s.numberSize.name)
            .apply()
    }

    fun delete(context: Context, id: Int) {
        val p = prefs(context)
        val prefix = "w$id."
        val editor = p.edit()
        p.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback
}
