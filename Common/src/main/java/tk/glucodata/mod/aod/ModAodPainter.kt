package tk.glucodata.mod.aod

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import tk.glucodata.TrendArrowAngle

/**
 * JugglucoNGmod, phone always-on display (part 1): paints the block as the watch face's dim mode on a transparent
 * bitmap (the overlay lies over the black always-on screen): the number centred with the arrow right of it, the change
 * and the age small under it, the chart below - no background, band or grid. No data reading here: the caller hands
 * over the plot and the texts; [zoneColor] gives upstream's range colour of a value.
 *
 * [density] and [scaledDensity] are the screen's (px per dp and per sp). One painter per caller thread.
 */
class ModAodPainter(private val density: Float, private val scaledDensity: Float = density) {

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bounds = Rect()
    private val path = Path()
    private val rangeDash = DashPathEffect(floatArrayOf(dp(ModAodStyle.RANGE_DASH_DP), dp(ModAodStyle.RANGE_DASH_DP)), 0f)
    private val forecastDash = DashPathEffect(floatArrayOf(dp(ModAodStyle.FORECAST_DASH_DP), dp(ModAodStyle.FORECAST_GAP_DP)), 0f)

    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = v * scaledDensity

    /** The height of the hour labels' row under the chart. */
    private fun hourRowPx(): Float = sp(ModAodStyle.LABEL_SP) * 1.5f

    /**
     * What the block shows above the chart: the number [text] (null - none), the trend [rate] (upstream's mg/dL a
     * minute; NaN - no arrow) and a small [sub] line under it ("+0,2 · 2 мин"); [stale] in place of all three when
     * there is no fresh value ("No new value since 14:32").
     */
    data class Value(val text: String?, val rate: Float, val sub: String?, val stale: String? = null)

    /**
     * The block on one transparent bitmap [width] wide: the digits [numberSizePx] large, centred on the screen, the
     * arrow right of them; the chart [chartHeight] high below ([chartHeight] 0 - no chart).
     */
    fun blockBitmap(
        width: Int,
        numberSizePx: Float,
        chartHeight: Int,
        value: Value,
        plot: ModAodPlot.Plot,
        isMmol: Boolean,
        palette: ModAodPalette,
        zoneColor: (Float) -> Int,
        zone: ZoneId,
        hour24: Boolean,
        locale: Locale = Locale.getDefault(),
    ): Bitmap {
        val subSize = numberSizePx * SUB_SHARE
        val topPad = dp(BLOCK_TOP_DP)
        setText(numberSizePx, palette.value)
        text.getTextBounds("0", 0, 1, bounds)
        val digitsHeight = bounds.height().toFloat()
        setText(subSize, palette.secondary)
        text.getTextBounds("0", 0, 1, bounds)
        val subHeight = bounds.height().toFloat()
        val subGap = numberSizePx * SUB_GAP_SHARE
        val valueBottom = topPad + digitsHeight + subGap + subHeight
        val chartTop = valueBottom + dp(CHART_GAP_DP)
        val height = if (chartHeight > 0) chartTop + chartHeight else valueBottom + dp(BLOCK_TOP_DP)
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), ceil(height).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawValue(canvas, width, topPad, digitsHeight, subGap, subHeight, numberSizePx, subSize, value, palette)
        if (chartHeight > 0) {
            drawChart(canvas, RectF(0f, chartTop, width.toFloat(), chartTop + chartHeight), plot, isMmol, palette, zoneColor, zone, hour24, locale)
        }
        return bmp
    }

    private fun drawValue(
        canvas: Canvas,
        width: Int,
        topPad: Float,
        digitsHeight: Float,
        subGap: Float,
        subHeight: Float,
        numberSizePx: Float,
        subSize: Float,
        value: Value,
        palette: ModAodPalette,
    ) {
        val cx = width / 2f
        val numberText = value.text
        if (value.stale != null || numberText.isNullOrEmpty()) {
            // no fresh value: the text in the number's place, smaller, grey; no arrow, change or age
            val s = value.stale ?: return
            setFitted(s, subSize * STALE_SHARE, palette.secondary, width)
            text.getTextBounds(s, 0, s.length, bounds)
            canvas.drawText(s, cx - text.measureText(s) / 2f, topPad + digitsHeight / 2f - bounds.exactCenterY(), text)
            return
        }
        setText(numberSizePx, palette.value)
        val numberWidth = text.measureText(numberText)
        val arrowSize = numberSizePx * ARROW_SHARE
        val arrowGap = numberSizePx * ARROW_GAP_SHARE
        val hasArrow = value.rate.isFinite()
        // the digits centred; the arrow hangs right of them; all pushed left only when the arrow would reach the margin
        var numberLeft = cx - numberWidth / 2f
        val groupRight = numberLeft + numberWidth + if (hasArrow) arrowGap + arrowSize else 0f
        val maxRight = width - dp(ModAodStyle.SIDE_DP)
        if (groupRight > maxRight) numberLeft -= groupRight - maxRight
        val baseline = topPad + digitsHeight
        canvas.drawText(numberText, numberLeft, baseline, text)
        if (hasArrow) {
            val arrowLeft = numberLeft + numberWidth + arrowGap
            drawArrow(canvas, arrowLeft, baseline - digitsHeight / 2f - arrowSize / 2f, arrowSize, value.rate, palette.value)
        }
        value.sub?.takeIf { it.isNotEmpty() }?.let { s ->
            // a large AOD text size makes "+0,9 · 17:01 · IOB 2,4 (16:40)" wider than the screen: shrunk, not cut
            setFitted(s, subSize, palette.secondary, width)
            canvas.drawText(s, cx - text.measureText(s) / 2f, baseline + subGap + subHeight, text)
        }
    }

    /** The text paint at [size], shrunk so [s] fits the block's [width] inside the side margins. */
    private fun setFitted(s: String, size: Float, color: Int, width: Int) {
        setText(size, color)
        val maxWidth = width - 2 * dp(ModAodStyle.SIDE_DP)
        val w = text.measureText(s)
        if (w > maxWidth && w > 0f && maxWidth > 0f) setText(size * maxWidth / w, color)
    }

    /**
     * The chart inside [box]: thin hour lines with their hours under the chart, the dashed target lines with their
     * values at the left end, the forecast dashed from the newest reading on, the 5-minute dots (white in the range,
     * upstream's range colour outside it).
     */
    fun drawChart(
        canvas: Canvas,
        box: RectF,
        plot: ModAodPlot.Plot,
        isMmol: Boolean,
        palette: ModAodPalette,
        zoneColor: (Float) -> Int,
        zone: ZoneId,
        hour24: Boolean,
        locale: Locale = Locale.getDefault(),
    ) {
        val left = box.left + dp(ModAodStyle.SIDE_DP)
        val right = box.right - dp(ModAodStyle.SIDE_DP)
        val top = box.top + dp(ModAodStyle.TOP_DP)
        val labelsTop = box.bottom - hourRowPx()
        val bottom = labelsTop - dp(ModAodStyle.HOUR_GAP_DP)
        if (right <= left || bottom <= top) return
        val width = right - left
        fun x(timeMs: Long) = left + ModAodPlot.xFraction(plot, timeMs) * width
        fun y(value: Float) = top + ModAodPlot.yFraction(plot, value) * (bottom - top)

        // thin lines at full hours, their hours under the chart
        val marks = ModAodPlot.hourMarks(plot.fromMs, plot.toMs, zone, hour24)
        stroke.pathEffect = null
        stroke.strokeWidth = dp(ModAodStyle.HOUR_LINE_DP).coerceAtLeast(1f)
        stroke.color = palette.hourLine
        for (m in marks) canvas.drawLine(x(m.timeMs), top, x(m.timeMs), bottom, stroke)
        drawHourLabels(canvas, marks.map { x(it.timeMs) to it.label }, labelsTop, box.bottom, box.left, box.right, palette)

        // the target lines, dashed, the chart's full width
        val yHigh = y(plot.high)
        val yLow = y(plot.low)
        stroke.strokeWidth = dp(ModAodStyle.RANGE_LINE_DP).coerceAtLeast(1f)
        stroke.color = palette.rangeLine
        stroke.pathEffect = rangeDash
        for (yLine in listOf(yHigh, yLow)) canvas.drawLine(left, yLine, right, yLine, stroke)
        stroke.pathEffect = null

        // upstream's forecast, dashed and thin, from the newest reading on
        if (plot.forecast.isNotEmpty()) {
            path.reset()
            val start = plot.lastReading
            if (start != null) path.moveTo(x(start.timeMs), y(start.value))
            plot.forecast.forEachIndexed { i, p ->
                if (i == 0 && start == null) path.moveTo(x(p.timeMs), y(p.value)) else path.lineTo(x(p.timeMs), y(p.value))
            }
            stroke.strokeWidth = dp(ModAodStyle.FORECAST_LINE_DP).coerceAtLeast(1f)
            stroke.color = palette.forecast
            stroke.pathEffect = forecastDash
            canvas.drawPath(path, stroke)
            stroke.pathEffect = null
        }

        // the 5-minute dots; closer dots (a long forecast) are smaller so they never run into each other
        val step = width * ModAodPlot.BUCKET_MS / (plot.toMs - plot.fromMs).coerceAtLeast(1L)
        val radius = minOf(dp(ModAodStyle.DOT_RADIUS_DP), step * 0.42f).coerceAtLeast(1.2f)
        for (dot in plot.dots) {
            fill.color = ModAodStyle.dotColor(dot.value, plot.low, plot.high, palette, zoneColor)
            canvas.drawCircle(x(dot.timeMs), y(dot.value), radius, fill)
        }

        // the target lines' values small at the left end, just above each line, over the dots
        drawRangeLabels(canvas, plot, isMmol, locale, left, top, bottom, yHigh, yLow, palette)
    }

    private fun drawRangeLabels(
        canvas: Canvas,
        plot: ModAodPlot.Plot,
        isMmol: Boolean,
        locale: Locale,
        left: Float,
        top: Float,
        bottom: Float,
        yHigh: Float,
        yLow: Float,
        palette: ModAodPalette,
    ) {
        setText(sp(ModAodStyle.LABEL_SP), palette.secondary)
        text.getTextBounds("0", 0, 1, bounds)
        val capHeight = bounds.height().toFloat()
        val lift = dp(3f)
        for ((value, yLine) in listOf(plot.high to yHigh, plot.low to yLow)) {
            val label = rangeLabel(value, isMmol, locale)
            val baseline = (yLine - lift).coerceIn(top + capHeight, bottom)
            palette.labelEdge?.let { edge ->
                text.style = Paint.Style.STROKE
                text.strokeWidth = dp(ModAodStyle.LABEL_EDGE_DP)
                text.strokeJoin = Paint.Join.ROUND
                text.color = edge
                canvas.drawText(label, left, baseline, text)
            }
            text.style = Paint.Style.FILL
            text.color = palette.secondary
            canvas.drawText(label, left, baseline, text)
        }
    }

    /** The hours centred under their lines; a label that would touch the one before it or the edge is left out. */
    private fun drawHourLabels(
        canvas: Canvas,
        lines: List<Pair<Float, String>>,
        top: Float,
        bottom: Float,
        minX: Float,
        maxX: Float,
        palette: ModAodPalette,
    ) {
        if (lines.isEmpty()) return
        setText(sp(ModAodStyle.LABEL_SP), palette.hourLabel)
        text.getTextBounds("0", 0, 1, bounds)
        val baseline = (top + bottom) / 2f - bounds.exactCenterY()
        val gap = dp(4f)
        var lastRight = Float.NEGATIVE_INFINITY
        for ((x, label) in lines) {
            val w = text.measureText(label)
            val l = x - w / 2f
            if (l < minX || l + w > maxX || l < lastRight + gap) continue
            canvas.drawText(label, l, baseline, text)
            lastRight = l + w
        }
    }

    /**
     * The watch face's trend arrow (upstream's `ComplicationRenderer.drawArrow` proportions: angle from
     * `TrendArrowAngle`, a double head past 2 mg/dL/min) in the dim mode's thin stroke.
     */
    private fun drawArrow(canvas: Canvas, left: Float, top: Float, size: Float, rate: Float, color: Int) {
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
        stroke.strokeWidth = size * ARROW_STROKE
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

    private fun setText(size: Float, color: Int) {
        text.textSize = size.coerceAtLeast(1f)
        text.color = color
        text.typeface = Typeface.DEFAULT
        text.style = Paint.Style.FILL
    }

    companion object {
        /** Shares of the number's size: the arrow, the space before it, the small line and its gap. */
        const val ARROW_SHARE = 0.55f
        const val ARROW_GAP_SHARE = 0.18f
        const val SUB_SHARE = 0.3f
        const val SUB_GAP_SHARE = 0.28f
        /** The "no new value" text, a share of the small line's size before it is fitted to the width. */
        const val STALE_SHARE = 1.4f
        /** The arrow's stroke, a share of its size (the watch face's dim mode: 0.07). */
        const val ARROW_STROKE = 0.08f
        const val BLOCK_TOP_DP = 16f
        const val CHART_GAP_DP = 20f

        /** A target line's value as the app writes values: "10.0" / "10,0" in mmol/L, "180" in mg/dL. */
        fun rangeLabel(value: Float, isMmol: Boolean, locale: Locale = Locale.getDefault()): String =
            String.format(locale, if (isMmol) "%.1f" else "%.0f", value)
    }
}
