package tk.glucodata.mod.watch.face

import java.util.Locale

/**
 * JugglucoNGmod, watch block (part 3b, part 7a): colours and line weights of the face, pure values.
 *
 * The background is always black (the screen stays on, AMOLED); the graph has no frame and no band (part 7a). The dim
 * (ambient) mode keeps the same picture: every colour dimmer by [AMBIENT_DIM], thinner lines, smaller dots; the number
 * in regular weight (part 7c, critic 7, S2: the largest lit area of an always-on AMOLED screen; the picture also moves
 * a little every minute). Part 7l, the owner: the dim mode is what he glances at all day, so it shows all the normal
 * mode shows - the forecast and the graph's hour and range labels too, dimmed. Part 7j, the owner on the watch: "too
 * dim, nothing visible" (it was 0.6 for all, 0.45 for the number) - now near the xDrip face: the number and the arrow at
 * [AMBIENT_VALUE_DIM] / [AMBIENT_ARROW_DIM], the rest at [AMBIENT_DIM]. Part 7l, the owner: the arrow and the change
 * changed colour too often (by the 30-minute projection) - now they take the number's range colour.
 * Widths and radii are shares of the screen's diameter; [arrowStroke] is a share of the arrow's size.
 */
data class FaceStyle(
    val ambient: Boolean,
    val background: Int,
    val time: Int,
    /** Date, change, battery numbers and the graph's range labels. */
    val secondary: Int,
    val age: Int,
    /** The age after [FaceValues.AGE_ALARM_MINUTES]. */
    val ageAlarm: Int,
    /** The "-" without a value, and the tone of a value without a range colour. */
    val neutral: Int,
    val rangeLine: Int,
    /** The "TEST" mark of a local trial build with made-up values. */
    val mark: Int,
    /** The largest radius of a 5-minute dot; a dot is smaller when the dots stand closer (6 h). */
    val dotRadius: Float,
    val rangeLineWidth: Float,
    val arrowStroke: Float,
    /** The graph's thin lines at full hours (part 3c2). */
    val hourLine: Int = HOUR_LINE,
    val hourLineWidth: Float = 0.0025f,
    /** The hour labels under those lines; `null` - neither they nor the range labels are drawn. */
    val hourLabel: Int? = HOUR_LABEL,
    /** Upstream's forecast, dashed (their chart: the neutral tone, faded); `null` - not drawn. */
    val forecast: Int? = FORECAST,
    val forecastWidth: Float = 0.006f,
    /** A battery icon's outline and fill, and its number. */
    val battery: Int = GREY,
    /** The same under [FaceIcons.LOW_PERCENT]. */
    val batteryLow: Int = RED,
    /** The same under [FaceIcons.WARN_PERCENT] (part 7c). */
    val batteryWarn: Int = AMBER,
    /** The number in bold (not in the dim mode, part 7c). */
    val valueBold: Boolean = true,
    /** The pump's active insulin under 15 minutes (20.11: light blue). */
    val iobFresh: Int = IOB_FRESH,
    /** ... 15-30 minutes old and "— ЕД" (20.11: a dim blue, not grey - grey sank into the pale age). */
    val iobStale: Int = IOB_STALE,
    /** The age while not red in the three rows with the active insulin (20.11: paler than the change above it). */
    val agePale: Int = AGE_PALE,
) {
    /** The colour of the active insulin row by its [state]. */
    fun iobColor(state: FaceValues.IobState?): Int =
        if (state == FaceValues.IobState.FRESH) iobFresh else iobStale

    /** The value's own range colour, dimmed in ambient more than the rest (part 7c); [neutral] without one. */
    fun valueColor(rangeColor: Int?): Int =
        rangeColor?.let { if (ambient) dim(it, AMBIENT_VALUE_DIM) else it } ?: neutral

    /**
     * The arrow's and the change's colour: the number's range colour (part 7l), [neutral] without one; in ambient
     * dimmed less than the rest, by [AMBIENT_ARROW_DIM] (part 7e: a dark red arrow sank into the black).
     */
    fun arrowColor(rangeColor: Int?): Int =
        rangeColor?.let { if (ambient) dim(it, AMBIENT_ARROW_DIM) else it } ?: neutral

    /** A battery's colour by its percent: red when low, yellow under half, else grey (part 7c). */
    fun batteryColor(percent: Int): Int = when {
        FaceIcons.isLow(percent) -> batteryLow
        FaceIcons.isWarn(percent) -> batteryWarn
        else -> battery
    }

    /** A graph point's range colour, dimmed in ambient. */
    fun traceColor(rangeColor: Int): Int = tone(rangeColor)

    private fun tone(color: Int): Int = if (ambient) dim(color, AMBIENT_DIM) else color

    companion object {
        const val BLACK = 0xFF000000.toInt()

        /** How much of each colour channel the ambient mode keeps (part 7j: 0.85, was 0.6). */
        const val AMBIENT_DIM = 0.85f

        /** How much of the number's colour the ambient mode keeps (part 7j: 0.95, was 0.45 - too dim on the watch). */
        const val AMBIENT_VALUE_DIM = 0.95f

        /** How much of the arrow's colour the ambient mode keeps (part 7j: as the number; part 7e: lighter than the dots). */
        const val AMBIENT_ARROW_DIM = 0.95f

        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val GREY = 0xFFB4B4B4.toInt()
        /** The age while not red beside the active insulin (20.11, 1a with fixes: paler than the change above it). */
        private const val AGE_PALE = 0xFF8E8E8E.toInt()
        /** The pump's active insulin: fresh light blue, stale a dim blue still read as blue beside the pale age. */
        private const val IOB_FRESH = 0xFF7CC4FF.toInt()
        private const val IOB_STALE = 0xFF4D7AA8.toInt()
        private const val RED = 0xFFFF5252.toInt()
        private const val AMBER = 0xFFFFC940.toInt()
        /** Upstream's neutral value tone on a dark face (as [ModFaceSource]). */
        private const val NEUTRAL = 0xFFE3E2DE.toInt()
        private const val RANGE_LINE = 0xFF7A7A7A.toInt()
        private const val ORANGE = 0xFFFF9800.toInt()
        /** Hour lines: fainter than the target lines, so they mark time without competing with the range. */
        private const val HOUR_LINE = 0xFF4A4A4A.toInt()
        private const val HOUR_LABEL = 0xFF9A9A9A.toInt()
        /** Upstream's forecast colour on their watch chart (the neutral tone at 55 % alpha), as it looks on black. */
        private const val FORECAST = 0xFF7D7C7A.toInt()

        private val NORMAL = FaceStyle(
            ambient = false, background = BLACK, time = WHITE, secondary = GREY, age = GREY, ageAlarm = RED,
            neutral = NEUTRAL, rangeLine = RANGE_LINE, mark = ORANGE,
            dotRadius = 0.0095f, rangeLineWidth = 0.004f, arrowStroke = 0.12f,
        )

        private val AMBIENT = FaceStyle(
            ambient = true, background = BLACK, time = dim(WHITE, AMBIENT_DIM), secondary = dim(GREY, AMBIENT_DIM),
            age = dim(GREY, AMBIENT_DIM), ageAlarm = dim(RED, AMBIENT_DIM), neutral = dim(NEUTRAL, AMBIENT_DIM),
            rangeLine = dim(RANGE_LINE, AMBIENT_DIM), mark = dim(ORANGE, AMBIENT_DIM),
            // part 7l: thinner than in the normal mode, yet the dots, lines and forecast readable at a glance
            dotRadius = 0.0075f, rangeLineWidth = 0.003f, arrowStroke = 0.07f,
            hourLine = dim(HOUR_LINE, AMBIENT_DIM), hourLineWidth = 0.002f, hourLabel = dim(HOUR_LABEL, AMBIENT_DIM),
            forecast = dim(FORECAST, AMBIENT_DIM), forecastWidth = 0.0045f,
            battery = dim(GREY, AMBIENT_DIM), batteryLow = dim(RED, AMBIENT_DIM), batteryWarn = dim(AMBER, AMBIENT_DIM),
            valueBold = false,
            iobFresh = dim(IOB_FRESH, AMBIENT_DIM), iobStale = dim(IOB_STALE, AMBIENT_DIM),
            agePale = dim(AGE_PALE, AMBIENT_DIM),
        )

        fun of(ambient: Boolean): FaceStyle = if (ambient) AMBIENT else NORMAL

        /** [color] with each of red, green and blue scaled by [factor] (0..1); alpha kept. */
        fun dim(color: Int, factor: Float): Int {
            val f = factor.coerceIn(0f, 1f)
            fun channel(shift: Int) = ((((color ushr shift) and 0xFF) * f) + 0.5f).toInt().coerceIn(0, 255) shl shift
            return (color and 0xFF000000.toInt()) or channel(16) or channel(8) or channel(0)
        }

        /** A target line's label as the app writes values: "10.0" / "10,0" in mmol/L, "180" in mg/dL. */
        fun rangeLabel(value: Float, isMmol: Boolean, locale: Locale = Locale.getDefault()): String =
            String.format(locale, if (isMmol) "%.1f" else "%.0f", value)
    }
}
