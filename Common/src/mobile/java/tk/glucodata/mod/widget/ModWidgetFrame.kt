package tk.glucodata.mod.widget

import tk.glucodata.GlucoseDelta
import tk.glucodata.GlucoseRangeColors
import kotlin.math.sin

/** The user's range limits, in display units, for the colors and the range lines. */
data class ModWidgetRange(
    val low: Float,
    val high: Float,
    val veryLow: Float,
    val veryHigh: Float,
    val isMmol: Boolean,
) {
    /** Upstream's five-zone color (as their chart; dark variant - the text is light on dark or on wallpaper). */
    fun color(value: Float): Int = GlucoseRangeColors.colorForValue(
        value, low, high, veryLow, veryHigh, GlucoseRangeColors.inRange(true), true, isMmol,
    )

    companion object {
        fun defaults(isMmol: Boolean) = ModWidgetRange(
            GlucoseRangeColors.defaultLow(isMmol),
            GlucoseRangeColors.defaultHigh(isMmol),
            GlucoseRangeColors.defaultVeryLow(isMmol),
            GlucoseRangeColors.defaultVeryHigh(isMmol),
            isMmol,
        )
    }
}

/**
 * What upstream's data gives the widget, read once per redraw: the current number as their app shows it
 * (`primaryStr`, `primaryValue`, rate), its time, and the chart readings resolved for the same view mode and
 * calibration (see [ModWidgetData]).
 */
data class ModWidgetInput(
    val sensorPresent: Boolean,
    val valueText: String?,
    val value: Float,
    /** mg/dL per minute (upstream's unit for the arrow); NaN when unknown. */
    val rate: Float,
    val readingTime: Long,
    val points: List<ModWidgetPoint>,
    val range: ModWidgetRange,
    val isExample: Boolean = false,
    /**
     * The readings for the 5-minute delta: the sensor's own values in the lane of the view mode (raw for raw modes),
     * unsmoothed - the same rule as upstream's notification delta (`Notify.java`, "Signed change").
     */
    val deltaPoints: List<ModWidgetPoint> = points,
) {
    val isMmol: Boolean get() = range.isMmol

    companion object {
        val EMPTY_MMOL = ModWidgetInput(false, null, Float.NaN, Float.NaN, 0L, emptyList(), ModWidgetRange.defaults(true))

        /**
         * Made-up readings for the settings preview when there is no data (an emulator, no sensor yet): a gentle wave
         * around the middle of the range, one reading a minute for 6 h, the last one a minute old and rising slowly.
         */
        fun example(now: Long, range: ModWidgetRange): ModWidgetInput {
            val scale = if (range.isMmol) 1f else 18f
            val points = (0..360).map { i ->
                val t = now - ModWidgetMath.MINUTE_MS - (360 - i) * ModWidgetMath.MINUTE_MS
                val v = (7.0 + 2.4 * sin(i / 38.0) + 0.9 * sin(i / 9.0)).toFloat() * scale
                ModWidgetPoint(t, v)
            }
            val last = points.last()
            val text = if (range.isMmol) String.format(java.util.Locale.getDefault(), "%.1f", last.value)
            else String.format(java.util.Locale.getDefault(), "%.0f", last.value)
            // the arrow follows the made-up curve (upstream's rate is mg/dL per minute)
            val delta = ModWidgetMath.fiveMinuteDelta(points)
            val rate = if (delta.isFinite()) delta / 5f * (if (range.isMmol) 18f else 1f) else Float.NaN
            return ModWidgetInput(true, text, last.value, rate, last.time, points, range, isExample = true)
        }
    }
}

/** Texts from resources; an interface so the frame rules are tested without Android. */
interface ModWidgetTexts {
    fun justNow(): String
    fun minutesAgo(minutes: Int): String
    fun stale(readingTime: Long): String
    fun noValue(): String
    fun unit(isMmol: Boolean): String
    fun trend(trend: ModWidgetMath.Trend): String
    fun example(): String
}

/** Everything one picture needs, decided; the renderer only draws it. */
data class ModWidgetFrame(
    val freshness: ModWidgetFreshness,
    /** The big number, or null (no value: [noValueText] is drawn instead). */
    val valueText: String?,
    val valueColor: Int,
    /** Grey and struck through: the reading is older than upstream's freshness limit. */
    val valueStruck: Boolean,
    /** NaN - no arrow. */
    val rate: Float,
    val deltaText: String?,
    /** "2 min ago" or "No new value since 14:32". */
    val statusText: String?,
    val noValueText: String?,
    val points: List<ModWidgetPoint>,
    val now: Long,
    val range: ModWidgetRange,
    val settings: ModWidgetSettings,
    /** For TalkBack: the number and the trend in words. */
    val description: String,
    /** "Example" across the picture when the readings are made up. */
    val exampleLabel: String?,
) {
    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFF9E9E9E.toInt()

        fun build(input: ModWidgetInput, settings: ModWidgetSettings, now: Long, timeoutMs: Long, texts: ModWidgetTexts): ModWidgetFrame {
            val usable = ModWidgetMath.usable(input.points, now)
            val chart = ModWidgetMath.chartPoints(usable, now, settings)
            val freshness = when {
                !input.sensorPresent || input.valueText.isNullOrBlank() -> ModWidgetFreshness.NONE
                else -> ModWidgetMath.freshness(input.readingTime, now, timeoutMs)
            }
            val example = if (input.isExample) texts.example() else null
            return when (freshness) {
                ModWidgetFreshness.FRESH -> {
                    val color = if (settings.colorByRange && input.value.isFinite()) input.range.color(input.value) else WHITE
                    val delta = if (settings.showDelta) {
                        val d = ModWidgetMath.deltaForReading(ModWidgetMath.usable(input.deltaPoints, now), input.readingTime)
                        GlucoseDelta.format(d, input.isMmol).takeIf { it.isNotEmpty() }?.let { "$it ${texts.unit(input.isMmol)}" }
                    } else null
                    val minutes = ModWidgetMath.ageMinutes(input.readingTime, now)
                    val ageText = if (minutes == 0) texts.justNow() else texts.minutesAgo(minutes)
                    val trend = ModWidgetMath.trend(input.rate)
                    val description = listOfNotNull(
                        "${input.valueText} ${texts.unit(input.isMmol)}",
                        trend?.let { texts.trend(it) },
                        ageText,
                    ).joinToString(", ")
                    ModWidgetFrame(
                        freshness, input.valueText, color, false,
                        if (input.rate.isFinite()) input.rate else Float.NaN,
                        delta, if (settings.showAge) ageText else null, null,
                        chart, now, input.range, settings, description, example,
                    )
                }
                ModWidgetFreshness.STALE -> {
                    val status = texts.stale(input.readingTime)
                    ModWidgetFrame(
                        freshness, input.valueText, GREY, true, Float.NaN, null, status, null,
                        chart, now, input.range, settings, "${input.valueText} ${texts.unit(input.isMmol)}, $status", example,
                    )
                }
                ModWidgetFreshness.NONE -> {
                    val none = texts.noValue()
                    ModWidgetFrame(
                        freshness, null, GREY, false, Float.NaN, null, null, none,
                        chart, now, input.range, settings, none, example,
                    )
                }
            }
        }
    }
}
