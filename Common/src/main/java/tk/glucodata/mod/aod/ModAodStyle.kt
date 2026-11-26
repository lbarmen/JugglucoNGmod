package tk.glucodata.mod.aod

/**
 * JugglucoNGmod, phone always-on display (part 1): colours and sizes, pure values. The owner asked for the watch face's
 * dim mode on the phone (26.11): no background, no band and no grid - the 5-minute dots, the dashed target lines with
 * their values, thin hour lines, the forecast dashed. The owner on the pictures (26.11): the number and the arrow are
 * never coloured, the dots are white in the target range and in upstream's range colour only outside it.
 *
 * [ON_BLACK] is for the always-on screen (the watch face's dim-mode greys); [ON_LIGHT] for upstream's lock-screen
 * mode over a light wallpaper, where their own overlay turns dark too (`AODOverlayService.resolveUsesLightPalette`).
 */
data class ModAodPalette(
    /** The number and the arrow. */
    val value: Int,
    /** A dot inside the target range (lines included). */
    val inRangeDot: Int,
    /** How much of upstream's range colour a dot outside the range keeps. */
    val zoneDim: Float,
    /** The change and the age under the number, the target lines' values, the "no new value" text. */
    val secondary: Int,
    val rangeLine: Int,
    val hourLine: Int,
    val hourLabel: Int,
    val forecast: Int,
    /** The edge round a target line's value so a dot never hides it; `null` - none (over a wallpaper). */
    val labelEdge: Int?,
    /** Upstream's range colours in their dark-theme variant (light on dark). */
    val darkTheme: Boolean,
)

object ModAodStyle {

    /** How much of each colour channel the dim mode keeps (the watch face's `AMBIENT_DIM`). */
    const val DIM = 0.85f

    /** The number and the arrow: dimmed less than the rest (the watch face's `AMBIENT_VALUE_DIM`). */
    const val VALUE_DIM = 0.95f

    private const val WHITE = 0xFFFFFFFF.toInt()

    val ON_BLACK = ModAodPalette(
        value = dim(WHITE, VALUE_DIM),
        inRangeDot = dim(WHITE, DIM),
        zoneDim = DIM,
        secondary = dim(0xFFB4B4B4.toInt(), DIM),
        rangeLine = dim(0xFF7A7A7A.toInt(), DIM),
        hourLine = dim(0xFF4A4A4A.toInt(), DIM),
        hourLabel = dim(0xFF9A9A9A.toInt(), DIM),
        // upstream's forecast colour on their watch chart (the neutral tone at 55 % alpha), as it looks on black
        forecast = dim(0xFF7D7C7A.toInt(), DIM),
        labelEdge = 0xFF000000.toInt(),
        darkTheme = true,
    )

    val ON_LIGHT = ModAodPalette(
        value = 0xFF1C1C1C.toInt(),
        inRangeDot = 0xFF2E2E2E.toInt(),
        zoneDim = 1f,
        secondary = 0xFF4A4A4A.toInt(),
        rangeLine = 0xFF6E6E6E.toInt(),
        hourLine = 0xFFA6A6A6.toInt(),
        hourLabel = 0xFF5A5A5A.toInt(),
        forecast = 0xFF707070.toInt(),
        labelEdge = null,
        darkTheme = false,
    )

    /** The largest dot radius; closer dots are smaller so they never run into each other. */
    const val DOT_RADIUS_DP = 2.6f
    const val RANGE_LINE_DP = 1.2f
    const val RANGE_DASH_DP = 4f
    const val HOUR_LINE_DP = 0.8f
    const val FORECAST_LINE_DP = 1.6f
    const val FORECAST_DASH_DP = 3f
    const val FORECAST_GAP_DP = 4f

    /** The hour labels under the chart and the target lines' values. */
    const val LABEL_SP = 12f

    /** The edge round a target line's value (the watch face's `LABEL_EDGE`). */
    const val LABEL_EDGE_DP = 3f

    /** Room left and right of the chart: upstream's burn-in steps move the overlay up to 4 dp sideways. */
    const val SIDE_DP = 16f

    /** Air above the dots and between the dots and the hour labels. */
    const val TOP_DP = 8f
    const val HOUR_GAP_DP = 4f

    /** [color] with each of red, green and blue scaled by [factor] (0..1); alpha kept. */
    fun dim(color: Int, factor: Float): Int {
        val f = factor.coerceIn(0f, 1f)
        fun channel(shift: Int) = ((((color ushr shift) and 0xFF) * f) + 0.5f).toInt().coerceIn(0, 255) shl shift
        return (color and 0xFF000000.toInt()) or channel(16) or channel(8) or channel(0)
    }

    /**
     * A dot's colour: [ModAodPalette.inRangeDot] inside the target range (its lines included), else upstream's range
     * colour for the value ([zoneColor]) dimmed by [ModAodPalette.zoneDim].
     */
    fun dotColor(value: Float, low: Float, high: Float, palette: ModAodPalette, zoneColor: (Float) -> Int): Int =
        if (value >= minOf(low, high) && value <= maxOf(low, high)) palette.inRangeDot
        else dim(zoneColor(value), palette.zoneDim)
}
