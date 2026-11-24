package tk.glucodata.mod.watch.face

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import java.util.Locale
import kotlin.math.abs
import tk.glucodata.TrendArrowAngle

/**
 * JugglucoNGmod, watch block (part 3b, the whole circle since part 7a): paints a [FaceModel] on a canvas by
 * [FaceLayout] and [FaceStyle]. No data reading here: the renderer hands over the model; [traceColor] gives a dot
 * its colour as the app would paint that value.
 *
 * Every text is fitted into its row by its measured bounds and set on its baseline (critic 3a, N8: a text's real
 * height is not its size), so nothing reaches the round edge. One painter per renderer, used on its thread; it keeps
 * its dash patterns for the screen's size instead of making them every frame (critic 7, N9).
 */
internal class FacePainter {

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bounds = Rect()
    private val path = Path()
    private val icons = FaceIcons()

    /** The dash patterns of the target lines and of the forecast, made for [dashDiameter] (critic 7, N9). */
    private var dashDiameter = -1f
    private var rangeDash: DashPathEffect? = null
    private var forecastDash: DashPathEffect? = null

    private fun dashesFor(diameter: Float) {
        if (diameter == dashDiameter && rangeDash != null && forecastDash != null) return
        dashDiameter = diameter
        val dash = diameter * 0.012f
        rangeDash = DashPathEffect(floatArrayOf(dash, dash), 0f)
        forecastDash = DashPathEffect(floatArrayOf(diameter * 0.01f, diameter * 0.012f), 0f)
    }

    /**
     * Paints the face on [canvas] ([widthPx] x [heightPx]) and returns the layout used (the tap area). With
     * [showData] false (no snapshot yet) only the time and the date are drawn; [trial] adds the "TEST" mark.
     * [offsetX] / [offsetY] move the whole picture (the dim mode's burn-in steps, [FaceLayout.burnInOffset]); the
     * returned layout is the unmoved one.
     */
    fun draw(
        canvas: Canvas,
        widthPx: Int,
        heightPx: Int,
        model: FaceModel,
        isMmol: Boolean,
        ambient: Boolean,
        showData: Boolean,
        trial: Boolean,
        traceColor: (Float) -> Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): FaceLayout.Layout {
        val style = FaceStyle.of(ambient)
        val layout = FaceLayout.compute(widthPx, heightPx)
        val d = layout.radius * 2f
        dashesFor(d)
        canvas.drawColor(style.background)
        canvas.save()
        canvas.translate(offsetX, offsetY)
        try {
            drawClock(canvas, layout.time, model.clock, style)
            drawStatus(canvas, layout, model, style, showData, d)
            if (showData) {
                drawValueRow(canvas, layout, model, style)
                drawGraph(canvas, layout, d, model, isMmol, style, traceColor)
            }
            if (trial) drawCentred(canvas, TRIAL_MARK, layout.mark, layout.mark.textSize, style.mark, bold = true)
        } finally {
            canvas.restore()
        }
        return layout
    }

    // ------------------------------------------------------------------ texts

    private fun drawClock(canvas: Canvas, row: FaceLayout.Row, clock: FaceText.Clock, style: FaceStyle) {
        val part = clock.dayPart
        if (part == null) {
            drawCentred(canvas, clock.text, row, row.textSize, style.time, bold = false)
            return
        }
        // 12-hour mode: "2:35" with a small "pm" after it, both centred together
        val size = fitSize(clock.text + " " + part, row, row.textSize, bold = false)
        setText(size, style.time, bold = false)
        val timeWidth = text.measureText(clock.text)
        text.getTextBounds(clock.text, 0, clock.text.length, bounds)
        val baseline = row.top + row.height / 2f - bounds.exactCenterY()
        val partSize = size * 0.4f
        text.textSize = partSize
        val partWidth = text.measureText(part)
        val gap = size * 0.12f
        val left = row.centerX - (timeWidth + gap + partWidth) / 2f
        text.textSize = size
        canvas.drawText(clock.text, left, baseline, text)
        text.textSize = partSize
        canvas.drawText(part, left + timeWidth + gap, baseline - size * 0.35f, text)
    }

    /** [s] centred in [row], at most [maxSize], shrunk to the row's width and height. */
    private fun drawCentred(canvas: Canvas, s: String, row: FaceLayout.Row, maxSize: Float, color: Int, bold: Boolean) {
        if (s.isEmpty() || row.width <= 0f || row.height <= 0f) return
        setText(fitSize(s, row, maxSize, bold), color, bold)
        text.getTextBounds(s, 0, s.length, bounds)
        val width = text.measureText(s)
        canvas.drawText(s, row.centerX - width / 2f, row.top + row.height / 2f - bounds.exactCenterY(), text)
    }

    /** The largest size up to [maxSize] at which [s] fits [row] by its measured width and glyph height. */
    private fun fitSize(s: String, row: FaceLayout.Row, maxSize: Float, bold: Boolean): Float {
        setText(maxSize, 0, bold)
        var size = maxSize
        val width = text.measureText(s)
        if (width > row.width && width > 0f) size *= row.width / width
        text.textSize = size
        text.getTextBounds(s, 0, s.length, bounds)
        if (bounds.height() > row.height && bounds.height() > 0) size *= row.height / bounds.height()
        return size
    }

    private fun setText(size: Float, color: Int, bold: Boolean) {
        text.textSize = size.coerceAtLeast(1f)
        text.color = color
        text.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    // ------------------------------------------------------------------ status row (part 7a, at the bottom since 7c)

    /**
     * The day and month in the middle (grey, the system's dot between the two words, part 7e), the phone's battery
     * left of it and the watch's right of it, each an icon and its number in the colour of its percent
     * ([FaceStyle.batteryColor]); a battery without a known percent has neither; the dim mode shows both, dimmed
     * (part 7j).
     */
    private fun drawStatus(
        canvas: Canvas,
        layout: FaceLayout.Layout,
        model: FaceModel,
        style: FaceStyle,
        showData: Boolean,
        diameter: Float,
    ) {
        val row = layout.status
        if (row.width <= 0f || row.height <= 0f) return
        val size = fitSize("00", row, row.textSize, bold = false)
        setText(size, style.secondary, bold = false)
        // "18 ноя": the day left of the centre, the month right of it; a date without a space is simply centred
        val date = model.dateText
        val cut = date.indexOf(' ')
        val day = if (cut > 0) date.substring(0, cut) else date
        val month = if (cut > 0) date.substring(cut + 1).trim() else ""
        val dateGap = if (month.isEmpty()) 0f else diameter * FaceLayout.DATE_GAP
        val dayWidth = if (month.isEmpty()) text.measureText(day) / 2f else text.measureText(day)
        val monthWidth = if (month.isEmpty()) dayWidth else text.measureText(month)
        val iconHeight = minOf(layout.statusIcon, row.height)
        val iconGap = diameter * FaceLayout.STATUS_ICON_GAP
        fun groupWidth(kind: FaceIcons.Kind, percent: Int?): Float =
            if (!showData || !FaceIcons.shown(percent, style.ambient)) 0f
            else FaceIcons.width(kind, iconHeight) + iconGap + text.measureText(percent.toString())
        val phoneWidth = groupWidth(FaceIcons.Kind.PHONE, model.phoneBattery)
        val watchWidth = groupWidth(FaceIcons.Kind.WATCH, model.watchBattery)
        val place = FaceLayout.placeStatus(row, dayWidth, monthWidth, phoneWidth, watchWidth, dateGap,
            diameter * FaceLayout.STATUS_GAP, diameter * FaceLayout.STATUS_MIN_GAP)
        text.getTextBounds("0", 0, 1, bounds)
        val baseline = row.centerY - bounds.exactCenterY()
        canvas.drawText(day, place.dayX, baseline, text)
        if (month.isNotEmpty()) canvas.drawText(month, place.monthX, baseline, text)
        fun group(kind: FaceIcons.Kind, percent: Int?, width: Float, left: Float) {
            if (width <= 0f || percent == null) return
            val color = style.batteryColor(percent)
            icons.draw(canvas, kind, percent, left, row.centerY, iconHeight, color)
            text.color = color
            canvas.drawText(percent.toString(), left + FaceIcons.width(kind, iconHeight) + iconGap, baseline, text)
        }
        group(FaceIcons.Kind.PHONE, model.phoneBattery, phoneWidth, place.phoneX)
        group(FaceIcons.Kind.WATCH, model.watchBattery, watchWidth, place.watchX)
    }

    // ------------------------------------------------------------------ value row

    private fun drawValueRow(canvas: Canvas, layout: FaceLayout.Layout, model: FaceModel, style: FaceStyle) {
        val row = layout.value
        val valueColor = if (model.hasValue) style.valueColor(model.valueColor) else style.neutral
        // the arrow and the change in the number's range colour, so they change colour only with it (part 7l)
        val arrowColor = if (model.hasValue) style.arrowColor(model.valueColor) else style.neutral
        // bold, but in the dim mode regular (part 7c, critic 7, S2)
        val bold = style.valueBold
        setText(row.textSize, valueColor, bold)
        val valueWidth = text.measureText(model.valueText)
        val hasArrow = model.rate.isFinite()
        val delta = model.deltaText
        val age = model.ageText
        val iob = model.iobText
        // 20.11, 1a with fixes: with the active insulin three rows of a smaller text; the age paler and smaller unless
        // red (then full size and bold), the active insulin never larger than the age. Without it the two rows as
        // before (the owner: "no IOB - two rows, as now"): the age grey, full size, regular
        val three = iob != null
        val side = if (three) layout.sideTextIob else layout.sideTextSize
        val ageSize = if (three && !model.ageAlarm) side * FaceLayout.AGE_SMALL else side
        val ageBold = three && model.ageAlarm
        val iobSize = side * FaceLayout.AGE_SMALL
        val sideWidth = maxOf(measure(delta, side, false), measure(age, ageSize, ageBold), measure(iob, iobSize, false))
        val gap = layout.radius * 0.04f
        var arrowSlot = if (hasArrow) layout.arrowSize else 0f
        var place = FaceLayout.placeValueRow(row, valueWidth, arrowSlot, sideWidth, gap)
        if (iob != null && hasArrow && place.scale < 1f) {
            // tight: the arrow's slot narrower before the number shrinks (its drawing stays as large)
            arrowSlot = layout.arrowTight
            place = FaceLayout.placeValueRow(row, valueWidth, arrowSlot, sideWidth, gap)
        }
        val centerY = row.top + row.height / 2f

        // the number, its digits centred on the row
        setText(row.textSize * place.scale, valueColor, bold)
        text.getTextBounds(model.valueText, 0, model.valueText.length, bounds)
        canvas.drawText(model.valueText, place.valueX, centerY - bounds.exactCenterY(), text)

        if (arrowSlot > 0f) {
            val size = layout.arrowSize * place.scale
            val left = place.arrowX + (arrowSlot * place.scale - size) / 2f
            drawArrow(canvas, left, centerY - size / 2f, size, model.rate, arrowColor, style.arrowStroke)
        }

        val ageColor = when {
            model.ageAlarm -> style.ageAlarm
            three -> style.agePale
            else -> style.age
        }
        val s = place.scale
        if (iob != null) {
            // three rows on fixed places: change / age / active insulin; a missing change leaves its place empty
            val (deltaY, ageY, iobY) = layout.sideRows
            delta?.let { sideText(canvas, it, place.sideX, deltaY, side * s, arrowColor, false) }
            age?.let { sideText(canvas, it, place.sideX, ageY, ageSize * s, ageColor, ageBold) }
            sideText(canvas, iob, place.sideX, iobY, iobSize * s, style.iobColor(model.iobState), false)
            return
        }

        // change over age, one above the other beside the number (as before 20.11)
        setText(side * s, arrowColor, bold = false)
        text.getTextBounds("0", 0, 1, bounds)
        val capHeight = bounds.height().toFloat()
        val lineGap = capHeight * 0.6f
        if (delta != null && age != null) {
            val upper = centerY - lineGap / 2f
            canvas.drawText(delta, place.sideX, upper, text)
            setText(ageSize * s, ageColor, ageBold)
            text.getTextBounds("0", 0, 1, bounds)
            canvas.drawText(age, place.sideX, upper + lineGap + bounds.height(), text)
        } else if (delta != null) {
            canvas.drawText(delta, place.sideX, centerY + capHeight / 2f, text)
        } else if (age != null) {
            sideText(canvas, age, place.sideX, centerY, ageSize * s, ageColor, ageBold)
        }
    }

    /** The measured width of [s] at [size] (0 - none). */
    private fun measure(s: String?, size: Float, bold: Boolean): Float {
        if (s == null) return 0f
        setText(size, 0, bold)
        return text.measureText(s)
    }

    /** [s] from [left] with its digits' middle on [centerY]. */
    private fun sideText(canvas: Canvas, s: String, left: Float, centerY: Float, size: Float, color: Int, bold: Boolean) {
        setText(size, color, bold)
        text.getTextBounds("0", 0, 1, bounds)
        canvas.drawText(s, left, centerY + bounds.height() / 2f, text)
    }

    /**
     * Upstream's trend arrow (`ComplicationRenderer.drawArrow`: angle from `TrendArrowAngle`, a double head past
     * 2 mg/dL/min, the same proportions) with our stroke weight, thinner in ambient.
     */
    private fun drawArrow(canvas: Canvas, left: Float, top: Float, size: Float, rate: Float, color: Int, weight: Float) {
        val cx = left + size / 2f
        val cy = top + size / 2f
        val speed = abs(rate)
        val double = speed > 2f
        val scale = 1f + (speed * 0.12f).coerceAtMost(0.5f)
        val headSpan = size * 0.55f
        val headDepth = headSpan / 2f
        val gap = headDepth * 0.5f
        val length = (size * (if (double) 0.35f else 0.6f) * scale).coerceAtMost(size * (if (double) 0.42f else 0.72f))
        val total = if (double) length + gap + headDepth else length
        stroke.pathEffect = null
        stroke.strokeWidth = size * weight
        stroke.color = color
        canvas.save()
        canvas.rotate(TrendArrowAngle.rotationDegrees(rate), cx, cy)
        val start = cx - total / 2f
        val tip = start + length
        path.reset()
        path.moveTo(start, cy)
        path.lineTo(tip, cy)
        path.moveTo(tip - headDepth, cy - headSpan / 2f)
        path.lineTo(tip, cy)
        path.lineTo(tip - headDepth, cy + headSpan / 2f)
        if (double) {
            val second = tip + gap + headDepth
            path.moveTo(second - headDepth, cy - headSpan / 2f)
            path.lineTo(second, cy)
            path.lineTo(second - headDepth, cy + headSpan / 2f)
        }
        canvas.drawPath(path, stroke)
        canvas.restore()
    }

    // ------------------------------------------------------------------ graph

    private fun drawGraph(
        canvas: Canvas,
        layout: FaceLayout.Layout,
        diameter: Float,
        model: FaceModel,
        isMmol: Boolean,
        style: FaceStyle,
        traceColor: (Float) -> Int,
    ) {
        val row = layout.graph
        val plot = model.graph
        if (row.width <= 0f || row.height <= 0f) return
        val plotLeft = row.left
        val plotRight = row.right
        val plotWidth = plotRight - plotLeft
        fun x(timeMs: Long) = plotLeft + FaceGraph.xFraction(plot, timeMs) * plotWidth
        fun y(value: Float) = row.top + FaceGraph.yFraction(plot, value) * row.height
        val yHigh = y(plot.high)
        val yLow = y(plot.low)

        // thin lines at full hours of local time, their hours under the graph (in the dim mode too since part 7l)
        val hourLines = FaceLayout.hourLines(plot, model.hourMarks, plotLeft, plotRight)
        stroke.pathEffect = null
        stroke.strokeWidth = (diameter * style.hourLineWidth).coerceAtLeast(1f)
        stroke.color = style.hourLine
        for (line in hourLines) canvas.drawLine(line.x, row.top, line.x, row.bottom, stroke)
        style.hourLabel?.let { color -> drawHourLabels(canvas, layout.hours, hourLines, color, diameter) }

        // target lines, dashed, from round edge to round edge at their height (part 7a)
        stroke.strokeWidth = (diameter * style.rangeLineWidth).coerceAtLeast(1f)
        stroke.color = style.rangeLine
        stroke.pathEffect = rangeDash
        val edgeInset = diameter * 0.03f
        for (yLine in listOf(yHigh, yLow)) {
            val half = FaceLayout.halfWidthAt(layout, yLine, edgeInset)
            canvas.drawLine(layout.centerX - half, yLine, layout.centerX + half, yLine, stroke)
        }
        stroke.pathEffect = null

        // upstream's forecast, dashed, from the newest reading on (in the dim mode too, dimmed and thinner: part 7l)
        style.forecast?.let { color ->
            if (plot.forecast.isEmpty()) return@let
            path.reset()
            val start = plot.points.lastOrNull()
            if (start != null) path.moveTo(x(start.timeMs), y(start.value))
            plot.forecast.forEachIndexed { i, p ->
                if (i == 0 && start == null) path.moveTo(x(p.timeMs), y(p.value)) else path.lineTo(x(p.timeMs), y(p.value))
            }
            stroke.strokeWidth = (diameter * style.forecastWidth).coerceAtLeast(1f)
            stroke.color = color
            stroke.pathEffect = forecastDash
            canvas.drawPath(path, stroke)
            stroke.pathEffect = null
        }

        // the 5-minute dots, each in the colour the number would have at its value (part 7a, as xDrip's face);
        // closer dots (6 h) are smaller so they never run into each other
        val step = plotWidth * FaceGraph.BUCKET_MS / (plot.toMs - plot.fromMs).coerceAtLeast(1L)
        val radius = minOf(diameter * style.dotRadius, step * 0.42f).coerceAtLeast(1.2f)
        for (dot in plot.dots) {
            fill.color = style.traceColor(traceColor(dot.value))
            canvas.drawCircle(x(dot.timeMs), y(dot.value), radius, fill)
        }

        // the target lines' values small at the left end, just above each line, over the dots with a black edge
        // round each glyph so a dot never hides them (part 7c; in the dim mode too since part 7l)
        if (style.hourLabel != null) drawRangeLabels(canvas, layout, diameter, plot, isMmol, style, yHigh, yLow, edgeInset)
    }

    private fun drawRangeLabels(
        canvas: Canvas,
        layout: FaceLayout.Layout,
        diameter: Float,
        plot: FaceGraph.Plot,
        isMmol: Boolean,
        style: FaceStyle,
        yHigh: Float,
        yLow: Float,
        edgeInset: Float,
    ) {
        val row = layout.graph
        val locale = Locale.getDefault()
        setText(layout.graphLabelSize, style.secondary, bold = false)
        text.getTextBounds("0", 0, 1, bounds)
        val capHeight = bounds.height().toFloat()
        val lift = diameter * 0.008f
        for ((value, yLine) in listOf(plot.high to yHigh, plot.low to yLow)) {
            val label = FaceStyle.rangeLabel(value, isMmol, locale)
            val baseline = (yLine - lift).coerceIn(row.top + capHeight, row.bottom)
            val left = maxOf(row.left, layout.centerX - FaceLayout.halfWidthAt(layout, baseline - capHeight, edgeInset))
            text.style = Paint.Style.STROKE
            text.strokeWidth = diameter * LABEL_EDGE
            text.strokeJoin = Paint.Join.ROUND
            text.color = style.background
            canvas.drawText(label, left, baseline, text)
            text.style = Paint.Style.FILL
            text.color = style.secondary
            canvas.drawText(label, left, baseline, text)
        }
    }

    /** The hours under their lines, small, centred; a label without room is left out ([FaceLayout.placeHourLabels]). */
    private fun drawHourLabels(
        canvas: Canvas,
        row: FaceLayout.Row,
        lines: List<FaceLayout.HourLine>,
        color: Int,
        diameter: Float,
    ) {
        if (lines.isEmpty() || row.width <= 0f || row.height <= 0f) return
        setText(fitSize("00", row, row.textSize, bold = false), color, bold = false)
        val widths = lines.map { text.measureText(it.label) }
        text.getTextBounds("0", 0, 1, bounds)
        val baseline = row.top + row.height / 2f - bounds.exactCenterY()
        FaceLayout.placeHourLabels(row, lines, widths, diameter * 0.01f).forEachIndexed { i, left ->
            if (left != null) canvas.drawText(lines[i].label, left, baseline, text)
        }
    }

    companion object {
        /** Marks a local trial build with made-up values; never shown with the real source. */
        const val TRIAL_MARK = "TEST"

        /** The black edge round a range label's glyphs, share of the diameter (part 7c). */
        const val LABEL_EDGE = 0.014f
    }
}
