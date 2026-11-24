package tk.glucodata.mod.watch.face

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * JugglucoNGmod, watch block (part 3a, the whole circle since part 7a, the status row at the bottom since part 7c):
 * where the face's rows sit on a round screen, without a Canvas:
 * ```
 *                 19:57              time alone at the top (~50 px on 450)
 *          10,8 ↗  +0.5              value (~90 px on 450), arrow, change over age
 *                  2 min
 *   - - - - - - - - - - - - - - - -  target lines to the round edge
 *   10.0  ·  ·· ·  ·   ··· ·  ·::  ╌ 5-minute dots, forecast dashed on the right
 *   3.9 - - - - - - - - - - - - - -
 *          18      19      20        hour labels under the hour lines
 *        [▯] 49   18 ноя   [⌚] 81    batteries as icons, the day and month, above the round edge
 * ```
 * Every share is of the screen's diameter (the smaller side, centred); a row is as wide as the circle allows at its
 * edge farther from the centre, less a margin, so nothing reaches the round edge on any size. Part 7c (the owner's
 * word) gave up the free band at the bottom that was kept for the system's ongoing-activity icon and the status row
 * takes the room; since part 7j our watch build has no ongoing activity at all (`NoOngoingActivity`), so no icon.
 * Batteries show in the dim mode too (7j): at the strongest burn-in shift their group may touch the round edge by a
 * few pixels (critic 7l-7m, L2; accepted).
 */
object FaceLayout {

    /** Top and bottom of each row, shares of the diameter from the top. */
    private const val TIME_TOP = 0.065f
    private const val TIME_BOTTOM = 0.19f
    private const val VALUE_TOP = 0.20f
    private const val VALUE_BOTTOM = 0.40f
    private const val GRAPH_TOP = 0.415f
    private const val GRAPH_BOTTOM = 0.80f
    /** The hour labels under the graph's hour lines (part 3c2). */
    private const val HOURS_TOP = 0.806f
    private const val HOURS_BOTTOM = 0.849f
    /** The batteries and the date (at the bottom since part 7c; taller for larger icons and numbers, 7e). */
    const val STATUS_TOP = 0.856f
    const val STATUS_BOTTOM = 0.912f
    /** The "TEST" mark of a local trial build (part 3b), under the status row. */
    private const val MARK_TOP = 0.918f
    private const val MARK_BOTTOM = 0.954f

    /** Text sizes, shares of the diameter. */
    const val TIME_TEXT = 0.11f
    /** The status row's numbers and date (part 7e: ~25 px on 450, was 20). */
    private const val STATUS_TEXT = 0.055f
    private const val VALUE_TEXT = 0.20f
    private const val ARROW = 0.10f
    private const val SIDE_TEXT = 0.058f

    /**
     * The pump's active insulin beside the number (20.11, "1a with fixes", critics' shares of the diameter): three rows
     * on fixed places - change / age / active insulin - their text [SIDE_TEXT_IOB] (24.3 px on 450, so the number is
     * not shrunk even at "11,7 · 12 мин · 12,5 ЕД"), their centres [SIDE_STEP] apart around [SIDE_CENTER] from the top,
     * the active insulin row [IOB_EXTRA] lower still (~2 px). Without the active insulin the two rows stay as before.
     */
    const val SIDE_TEXT_IOB = 0.054f
    const val SIDE_STEP = 0.060f
    const val SIDE_CENTER = 0.295f
    const val IOB_EXTRA = 0.0045f

    /**
     * The age while not red beside the active insulin, share of the side text (20.11: "a couple of points smaller");
     * red - full size, bold. Without the active insulin the age is as before 20.11.
     */
    const val AGE_SMALL = 0.88f

    /** The arrow's slot when the three rows would make the value row too wide (20.11: its drawing is 30-34 px). */
    const val ARROW_TIGHT = 0.085f
    private const val GRAPH_LABEL_TEXT = 0.038f
    private const val HOURS_TEXT = 0.035f
    private const val MARK_TEXT = 0.035f

    /** The tap area of the value reaches this share of the value row's height above and below it. */
    private const val TAP_SLACK = 0.05f

    /** Space kept from the round edge on each side, share of the diameter. */
    const val MARGIN = 0.04f

    /**
     * The status row keeps less (part 7e): its icons and texts are centred in it, so their corners stay well inside
     * the circle even so, and the larger numbers need the width.
     */
    const val STATUS_MARGIN = 0.01f

    /** A battery icon's height (part 7e: ~24 px on 450), the space between an icon and its number. */
    const val STATUS_ICON = 0.054f
    const val STATUS_ICON_GAP = 0.01f

    /**
     * Between the batteries and the date (part 7e: ~22 px on 450, twice as wide as before); down to [STATUS_MIN_GAP]
     * when a "100" needs the room.
     */
    const val STATUS_GAP = 0.048f
    const val STATUS_MIN_GAP = 0.024f

    /**
     * Between the day and the month (~12 px on 450). This gap sits at the screen's centre, where Wear OS draws the
     * faint dot of upstream's ongoing activity (~0.86 of the diameter, part 7c), so the dot never lands in a word.
     */
    const val DATE_GAP = 0.026f

    /**
     * A generous average glyph width, share of the text size, for checking that a text fits without measuring it
     * (digits and letters of the face's fonts are narrower); the renderer measures for real.
     */
    const val CHAR_WIDTH = 0.6f

    /** One row in pixels: its box and its text size. */
    data class Row(val left: Float, val top: Float, val right: Float, val bottom: Float, val textSize: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    /**
     * The face on a [widthPx] x [heightPx] screen: the rows top to bottom; in the value row the number takes
     * [Row.textSize], the arrow [arrowSize] and the change and age, one over the other, [sideTextSize].
     */
    data class Layout(
        val centerX: Float,
        val centerY: Float,
        val radius: Float,
        val time: Row,
        val value: Row,
        val arrowSize: Float,
        val sideTextSize: Float,
        val graph: Row,
        val graphLabelSize: Float,
        /** The labels of the graph's hour lines (part 3c2). */
        val hours: Row,
        /** The battery icons with their numbers and, between them, the day and month (7a; the bottom 7c; 7e). */
        val status: Row,
        val mark: Row,
    ) {
        /** A battery icon's height in pixels (part 7e). */
        val statusIcon: Float get() = STATUS_ICON * radius * 2f

        /** The side text with the active insulin row, pixels (20.11). */
        val sideTextIob: Float get() = SIDE_TEXT_IOB * radius * 2f

        /** The arrow's narrower slot, pixels ([ARROW_TIGHT]). */
        val arrowTight: Float get() = ARROW_TIGHT * radius * 2f

        /**
         * The centres (y, pixels) of the three side rows with the active insulin: change, age, active insulin
         * ([SIDE_CENTER], [SIDE_STEP], [IOB_EXTRA]); fixed, so a missing change leaves its place empty.
         */
        val sideRows: List<Float>
            get() {
                val d = radius * 2f
                val c = centerY - radius + SIDE_CENTER * d
                return listOf(c - SIDE_STEP * d, c, c + SIDE_STEP * d + IOB_EXTRA * d)
            }

        /** The drawn rows, top to bottom. */
        val rows: List<Row> get() = listOf(time, value, graph, hours, status, mark)
    }

    /** A thin hour line of the graph at [x] (pixels) and its [label]. */
    data class HourLine(val x: Float, val label: String)

    /**
     * Where the value row's parts start (left edges, pixels) once the whole is centred in the row, and the [scale]
     * every part's size is multiplied by when the whole would be wider than the row (1 when it fits).
     */
    data class ValuePlacement(val scale: Float, val valueX: Float, val arrowX: Float, val sideX: Float)

    /**
     * Left edges in the status row: the day at [dayX] and the month at [monthX] with the date's gap between them on
     * the centre, the phone's battery (icon and number) ending a gap left of the day at [phoneX], the watch's
     * starting a gap right of the month at [watchX].
     */
    data class StatusPlacement(val phoneX: Float, val dayX: Float, val monthX: Float, val watchX: Float)

    fun compute(widthPx: Int, heightPx: Int): Layout {
        val size = min(widthPx, heightPx).toFloat().coerceAtLeast(1f)
        val left = (widthPx - size) / 2f
        val top = (heightPx - size) / 2f
        fun row(rowTop: Float, rowBottom: Float, text: Float, margin: Float = MARGIN): Row {
            // The edge farther from the centre is the narrower one.
            val far = maxOf(abs(rowTop - 0.5f), abs(rowBottom - 0.5f))
            val half = (sqrt((0.25f - far * far).coerceAtLeast(0f)) - margin).coerceAtLeast(0f)
            return Row(left + (0.5f - half) * size, top + rowTop * size, left + (0.5f + half) * size,
                top + rowBottom * size, text * size)
        }
        return Layout(
            centerX = left + size / 2f,
            centerY = top + size / 2f,
            radius = size / 2f,
            time = row(TIME_TOP, TIME_BOTTOM, TIME_TEXT),
            value = row(VALUE_TOP, VALUE_BOTTOM, VALUE_TEXT),
            arrowSize = ARROW * size,
            sideTextSize = SIDE_TEXT * size,
            graph = row(GRAPH_TOP, GRAPH_BOTTOM, GRAPH_LABEL_TEXT),
            graphLabelSize = GRAPH_LABEL_TEXT * size,
            hours = row(HOURS_TOP, HOURS_BOTTOM, HOURS_TEXT),
            status = row(STATUS_TOP, STATUS_BOTTOM, STATUS_TEXT, STATUS_MARGIN),
            mark = row(MARK_TOP, MARK_BOTTOM, MARK_TEXT),
        )
    }

    /** The steps of [burnInOffset]: the picture where it is, then right, down, left and up (part 7c). */
    private val BURN_IN_STEPS = listOf(0 to 0, 1 to 0, 0 to 1, -1 to 0, 0 to -1)

    /** How far [burnInOffset] moves the picture, share of the diameter (~3.6 px on 450). */
    const val BURN_IN_SHIFT = 0.008f

    /**
     * Where the dim mode moves the whole picture in [minute] (minutes since the epoch), pixels on a [diameter] screen
     * (part 7c, critic 7, S2): one of five places, the next every minute, round and round, so no pixel of the number
     * or of a line stays lit in one place all day. [strong] - the watch says its screen needs burn-in protection
     * (`WatchState.hasBurnInProtection`): twice as far. Whole pixels. Every row keeps [MARGIN] from the round edge and the target
     * lines 0.03 of the diameter, so the moved picture stays inside the circle.
     */
    fun burnInOffset(minute: Long, diameter: Float, strong: Boolean): Pair<Float, Float> {
        val (dx, dy) = BURN_IN_STEPS[Math.floorMod(minute, BURN_IN_STEPS.size.toLong()).toInt()]
        // whole pixels, so the moved texts stay as sharp as the unmoved ones
        val step = Math.round(diameter * BURN_IN_SHIFT).coerceAtLeast(1) * (if (strong) 2f else 1f)
        return dx * step to dy * step
    }

    /**
     * Half the width of the circle at height [y] (pixels) less [inset] at each end: a target line drawn from
     * `centerX - halfWidthAt` to `centerX + halfWidthAt` reaches the round edge without touching it.
     */
    fun halfWidthAt(layout: Layout, y: Float, inset: Float): Float {
        val dy = y - layout.centerY
        return (sqrt((layout.radius * layout.radius - dy * dy).coerceAtLeast(0f)) - inset).coerceAtLeast(0f)
    }

    /**
     * The value row of measured widths: the number, the arrow (0 - none) and the wider of change and age (0 - none),
     * [gap] between present parts; centred, scaled down together when wider than the row.
     */
    fun placeValueRow(row: Row, valueWidth: Float, arrowWidth: Float, sideWidth: Float, gap: Float): ValuePlacement {
        val gaps = (if (arrowWidth > 0f) gap else 0f) + (if (sideWidth > 0f) gap else 0f)
        val total = valueWidth + arrowWidth + sideWidth + gaps
        val scale = if (total > row.width && total > 0f) (row.width / total).coerceAtLeast(0f) else 1f
        val left = row.centerX - total * scale / 2f
        val arrowX = left + (valueWidth + (if (arrowWidth > 0f) gap else 0f)) * scale
        val sideX = arrowX + (arrowWidth + (if (sideWidth > 0f) gap else 0f)) * scale
        return ValuePlacement(scale, left, arrowX, sideX)
    }

    /**
     * The status row of measured widths ([phoneWidth] / [watchWidth]: icon, gap and number; 0 - not shown): the day
     * ending and the month starting [dateGap] / 2 from the row's centre (the system's dot falls between them), the
     * batteries [gap] beside them - narrower, down to [minGap], when a battery would reach past the row; one that
     * still would is pushed back inside.
     */
    fun placeStatus(
        row: Row,
        dayWidth: Float,
        monthWidth: Float,
        phoneWidth: Float,
        watchWidth: Float,
        dateGap: Float,
        gap: Float,
        minGap: Float,
    ): StatusPlacement {
        val dayX = row.centerX - dateGap / 2f - dayWidth
        val monthX = row.centerX + dateGap / 2f
        val leftGap = (dayX - phoneWidth - row.left).coerceIn(minGap, gap)
        val phoneX = (dayX - leftGap - phoneWidth).coerceAtLeast(row.left)
        val monthEnd = monthX + monthWidth
        val rightGap = (row.right - watchWidth - monthEnd).coerceIn(minGap, gap)
        val watchX = (monthEnd + rightGap).coerceAtMost(row.right - watchWidth)
        return StatusPlacement(phoneX, dayX, monthX, watchX)
    }

    /**
     * The graph's hour lines: each of [marks] at its time in the [plot]'s window, between [plotLeft] and [plotRight]
     * (pixels).
     */
    fun hourLines(plot: FaceGraph.Plot, marks: List<FaceGraph.HourMark>, plotLeft: Float, plotRight: Float):
        List<HourLine> = marks.map { HourLine(plotLeft + FaceGraph.xFraction(plot, it.timeMs) * (plotRight - plotLeft), it.label) }

    /**
     * Left edges of the hour labels in the [hours] row, each centred under its line ([widths] - measured, same
     * order as [lines]); `null` - the label is left out (its line stays): it would reach past the row (the round
     * edge) or come closer than [gap] to the label before it. The row lies between the graph and the status row,
     * so a label never touches either.
     */
    fun placeHourLabels(hours: Row, lines: List<HourLine>, widths: List<Float>, gap: Float): List<Float?> {
        var lastRight = Float.NEGATIVE_INFINITY
        return lines.mapIndexed { i, line ->
            val width = widths.getOrElse(i) { 0f }
            val left = line.x - width / 2f
            val right = left + width
            if (width <= 0f || left < hours.left || right > hours.right || left < lastRight + gap) null
            else {
                lastRight = right
                left
            }
        }
    }

    /** A tap at [x], [y] (pixels) is on the value: the value row, a little taller, across its width. */
    fun isOnValue(layout: Layout, x: Float, y: Float): Boolean {
        val row = layout.value
        val slack = row.height * TAP_SLACK
        return x >= row.left && x <= row.right && y >= row.top - slack && y <= row.bottom + slack
    }

    /** A width estimate for [text] at [textSize] (see [CHAR_WIDTH]). */
    fun estimatedWidth(text: String, textSize: Float): Float = text.length * CHAR_WIDTH * textSize

    /**
     * The value row's content width estimate: the number, the arrow and the wider of change and age, with the
     * gaps the renderer leaves between them.
     */
    fun valueRowWidth(layout: Layout, valueText: String, deltaText: String, ageText: String): Float {
        val gap = layout.radius * 0.04f
        return estimatedWidth(valueText, layout.value.textSize) + gap + layout.arrowSize + gap +
            maxOf(estimatedWidth(deltaText, layout.sideTextSize), estimatedWidth(ageText, layout.sideTextSize))
    }
}
