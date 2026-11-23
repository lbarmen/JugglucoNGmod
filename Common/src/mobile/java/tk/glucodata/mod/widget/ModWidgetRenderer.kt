package tk.glucodata.mod.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import tk.glucodata.TrendArrowAngle
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * JugglucoNGmod widget: draws one picture of the widget (as xDrip does - one bitmap in an ordinary RemoteViews), the
 * same function for the home screen and for the live preview in the settings window. Pure drawing of a decided
 * [ModWidgetFrame]: no upstream state, no resources - runs under Robolectric for the look pictures.
 */
object ModWidgetRenderer {

    /** Where each part goes, in pixels; null - not drawn. Exposed for the layout tests. */
    class Layout(
        val width: Int,
        val height: Int,
        val valueSizePx: Float,
        val smallSizePx: Float,
        val value: RectF?,
        val arrow: RectF?,
        val delta: RectF?,
        val status: RectF?,
        val noValue: RectF?,
        val chart: RectF?,
        val deltaText: String?,
        val statusText: String?,
        val noValueText: String?,
        val noValueSizePx: Float,
    ) {
        fun parts(): List<RectF> = listOfNotNull(value, arrow, delta, status, noValue)
    }

    private const val SHADOW = 0xD9000000.toInt()

    private fun valuePaint(sizePx: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = sizePx
    }

    private fun smallPaint(sizePx: Float) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        textSize = sizePx
    }

    private fun lineHeight(p: Paint): Float = p.fontMetrics.let { it.descent - it.ascent }

    /** The big number's size in dp before it is fitted to the height. */
    internal fun valueSizeDp(heightDp: Float, settings: ModWidgetSettings): Float = when (settings.numberSize) {
        ModWidgetNumberSize.S -> 20f
        ModWidgetNumberSize.M -> 28f
        ModWidgetNumberSize.L -> 38f
        ModWidgetNumberSize.AUTO -> when (settings.layout) {
            ModWidgetLayout.OVERLAY -> (heightDp * 0.6f).coerceIn(18f, 64f)
            ModWidgetLayout.TOP -> (heightDp * 0.45f).coerceIn(16f, 48f)
        }
    }

    fun layout(frame: ModWidgetFrame, width: Int, height: Int, density: Float): Layout {
        val d = density.takeIf { it > 0f } ?: 1f
        val w = width.toFloat()
        val h = height.toFloat()
        val settings = frame.settings
        val hasBackground = settings.backgroundAlpha > 0
        val padX = (if (hasBackground) 7f else 4f) * d
        val padY = (if (hasBackground) 4f else 3f) * d
        val innerH = max(1f, h - 2 * padY)

        // The big number: its line must fit the height, whatever size was asked for.
        var valueSize = valueSizeDp(h / d, settings) * d
        val vp = valuePaint(valueSize)
        val lh = lineHeight(vp)
        if (lh > innerH) {
            valueSize *= innerH / lh
            vp.textSize = valueSize
        }
        // ... and its width: the number is made smaller, never cut (a cut "13..." would be a wrong number)
        frame.valueText?.let { text ->
            val availW = max(1f, w - 2 * padX)
            val vw = vp.measureText(text)
            if (vw > availW) {
                valueSize *= availW / vw * 0.98f
                vp.textSize = valueSize
            }
        }
        val valueLine = lineHeight(vp)

        // The small lines (delta, status): two of them stay inside the number's line or the height.
        var smallSize = (valueSize * 0.38f).coerceIn(9f * d, 15f * d)
        val sp = smallPaint(smallSize)
        val textLines = listOfNotNull(frame.deltaText, frame.statusText).size
        val room = max(valueLine, innerH)
        if (textLines > 0 && lineHeight(sp) * textLines > room) {
            smallSize *= room / (lineHeight(sp) * textLines)
            sp.textSize = smallSize
        }
        val smallLine = lineHeight(sp)

        var valueRect: RectF? = null
        var arrowRect: RectF? = null
        var deltaRect: RectF? = null
        var statusRect: RectF? = null
        var noValueRect: RectF? = null
        var deltaText: String? = null
        var statusText: String? = null
        var noValueText: String? = null
        var noValueSize = 0f
        var textBottom = padY

        val left = padX
        val top = padY
        if (frame.valueText != null) {
            val vw = vp.measureText(frame.valueText)
            valueRect = RectF(left, top, left + vw, top + valueLine)
            var x = valueRect.right
            val centerY = top + valueLine * 0.55f
            if (frame.rate.isFinite()) {
                val a = valueSize * 0.62f
                val ax = x + valueSize * 0.12f
                if (ax + a <= w - padX) {
                    arrowRect = RectF(ax, centerY - a / 2, ax + a, centerY + a / 2)
                    x = arrowRect.right
                }
            }
            val colX = x + valueSize * 0.22f
            val avail = w - padX - colX
            val lines = listOfNotNull(frame.deltaText, frame.statusText)
            if (lines.isNotEmpty() && avail >= 3f * smallSize) {
                val colH = smallLine * lines.size
                var y = (centerY - colH / 2).coerceIn(top, max(top, h - padY - colH))
                if (frame.deltaText != null) {
                    // a narrow widget drops the unit before it cuts the number
                    val short = if (sp.measureText(frame.deltaText) > avail) frame.deltaText.substringBefore(' ') else frame.deltaText
                    deltaText = ellipsize(short, sp, avail)
                    deltaRect = RectF(colX, y, colX + sp.measureText(deltaText), y + smallLine)
                    y += smallLine
                }
                if (frame.statusText != null) {
                    statusText = ellipsize(frame.statusText, sp, avail)
                    statusRect = RectF(colX, y, colX + sp.measureText(statusText), y + smallLine)
                }
            }
            textBottom = listOfNotNull(valueRect, arrowRect, deltaRect, statusRect).maxOf { it.bottom }
        } else if (frame.noValueText != null) {
            noValueSize = min(valueSize * 0.6f, max(smallSize * 1.3f, 11f * d))
            val np = smallPaint(noValueSize)
            noValueText = ellipsize(frame.noValueText, np, w - 2 * padX)
            noValueRect = RectF(left, top, left + np.measureText(noValueText), top + lineHeight(np))
            textBottom = noValueRect.bottom
        }

        val dotR = dotRadius(settings, d)
        val chart = when (settings.layout) {
            ModWidgetLayout.OVERLAY -> RectF(padX + dotR, padY + dotR, w - padX - dotR, h - padY - dotR)
            ModWidgetLayout.TOP -> {
                val chartTop = textBottom + 2f * d + dotR
                val chartBottom = h - padY - dotR
                if (chartBottom - chartTop >= 8f * d) RectF(padX + dotR, chartTop, w - padX - dotR, chartBottom) else null
            }
        }?.takeIf { it.width() > 4f * d && it.height() > 2f * d }

        return Layout(
            width, height, valueSize, smallSize, valueRect, arrowRect, deltaRect, statusRect, noValueRect, chart,
            deltaText, statusText, noValueText, noValueSize,
        )
    }

    /** The longest start of [text] that fits [avail] with "…" after it (own loop: no layout classes needed). */
    internal fun ellipsize(text: String, paint: Paint, avail: Float): String {
        if (paint.measureText(text) <= avail) return text
        val dots = "…"
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paint.measureText(text, 0, mid) + paint.measureText(dots) <= avail) lo = mid else hi = mid - 1
        }
        return if (lo == 0) "" else text.substring(0, lo).trimEnd() + dots
    }

    private fun dotRadius(settings: ModWidgetSettings, d: Float): Float = when (settings.dots) {
        ModWidgetDots.AVERAGE_5MIN -> 2.3f * d
        ModWidgetDots.EACH -> 1.5f * d
        ModWidgetDots.LINE -> 1.0f * d
    }

    fun render(frame: ModWidgetFrame, width: Int, height: Int, density: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
        draw(Canvas(bitmap), frame, layout(frame, max(1, width), max(1, height), density), density)
        return bitmap
    }

    fun draw(canvas: Canvas, frame: ModWidgetFrame, layout: Layout, density: Float) {
        val d = density.takeIf { it > 0f } ?: 1f
        val w = layout.width.toFloat()
        val h = layout.height.toFloat()
        val settings = frame.settings
        val shadowR = 2.2f * d
        val shadowDy = 0.7f * d

        if (settings.backgroundAlpha > 0) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = settings.backgroundAlpha shl 24 }
            val r = min(14f * d, h / 2f)
            canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, bg)
        }

        layout.chart?.let { drawChart(canvas, frame, it, d, shadowR, shadowDy) }

        if (frame.valueText != null && layout.value != null) {
            val vp = valuePaint(layout.valueSizePx).apply {
                color = frame.valueColor
                isStrikeThruText = frame.valueStruck
                setShadowLayer(shadowR, 0f, shadowDy, SHADOW)
            }
            canvas.drawText(frame.valueText, layout.value.left, layout.value.top - vp.fontMetrics.ascent, vp)
        }
        layout.arrow?.let { drawArrow(canvas, it, frame.rate, frame.valueColor, shadowR, shadowDy) }
        val sp = smallPaint(layout.smallSizePx).apply {
            color = 0xF2FFFFFF.toInt()
            setShadowLayer(shadowR, 0f, shadowDy, SHADOW)
        }
        if (layout.deltaText != null && layout.delta != null) {
            canvas.drawText(layout.deltaText, layout.delta.left, layout.delta.top - sp.fontMetrics.ascent, sp)
        }
        if (layout.statusText != null && layout.status != null) {
            canvas.drawText(layout.statusText, layout.status.left, layout.status.top - sp.fontMetrics.ascent, sp)
        }
        if (layout.noValueText != null && layout.noValue != null) {
            val np = smallPaint(layout.noValueSizePx).apply {
                color = ModWidgetFrame.GREY
                setShadowLayer(shadowR, 0f, shadowDy, SHADOW)
            }
            canvas.drawText(layout.noValueText, layout.noValue.left, layout.noValue.top - np.fontMetrics.ascent, np)
        }
        frame.exampleLabel?.let { label ->
            // bottom right, small: never over the number, the arrow or the text column
            val ep = valuePaint(min(h * 0.24f, 13f * d)).apply {
                color = 0xE6FFD54F.toInt()
                textAlign = Paint.Align.RIGHT
                letterSpacing = 0.08f
                setShadowLayer(shadowR, 0f, shadowDy, SHADOW)
            }
            canvas.drawText(label.uppercase(), w - 6f * d, h - 4f * d - ep.fontMetrics.descent, ep)
        }
    }

    private fun drawChart(canvas: Canvas, frame: ModWidgetFrame, area: RectF, d: Float, shadowR: Float, shadowDy: Float) {
        val settings = frame.settings
        val range = frame.range
        val points = frame.points
        val scale = ModWidgetMath.verticalScale(points.map { it.value }, range.low, range.high)
        val period = settings.periodMillis

        if (settings.rangeLines) {
            val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = max(1f, 0.9f * d)
                color = 0x8CFFFFFF.toInt()
                pathEffect = DashPathEffect(floatArrayOf(4f * d, 3f * d), 0f)
            }
            for (limit in floatArrayOf(range.low, range.high)) {
                val y = scale.y(limit, area.top, area.bottom)
                val path = Path().apply { moveTo(area.left, y); lineTo(area.right, y) }
                canvas.drawPath(path, lp)
            }
        }
        if (points.isEmpty()) return

        fun x(t: Long) = ModWidgetMath.x(t, frame.now, period, area.left, area.right)
        fun y(v: Float) = scale.y(v, area.top, area.bottom)

        when (settings.dots) {
            ModWidgetDots.LINE -> {
                val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.7f * d
                    strokeCap = Paint.Cap.ROUND
                    setShadowLayer(shadowR, 0f, shadowDy, SHADOW)
                }
                for (run in ModWidgetMath.lineRuns(points)) {
                    if (run.size == 1) {
                        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = range.color(run[0].value) }
                        canvas.drawCircle(x(run[0].time), y(run[0].value), 1.3f * d, fp)
                        continue
                    }
                    for (i in 1 until run.size) {
                        val a = run[i - 1]
                        val b = run[i]
                        lp.color = range.color(b.value)
                        canvas.drawLine(x(a.time), y(a.value), x(b.time), y(b.value), lp)
                    }
                }
            }
            else -> {
                val r = dotRadius(settings, d)
                val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { setShadowLayer(shadowR, 0f, shadowDy, SHADOW) }
                for (p in points) {
                    fp.color = range.color(p.value)
                    canvas.drawCircle(x(p.time), y(p.value), r, fp)
                }
            }
        }
    }

    private fun drawArrow(canvas: Canvas, box: RectF, rate: Float, color: Int, shadowR: Float, shadowDy: Float) {
        val a = min(box.width(), box.height())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = a * 0.13f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.color = color
            setShadowLayer(shadowR, 0f, shadowDy, SHADOW)
        }
        val half = a * 0.42f
        val head = a * 0.3f
        val path = Path().apply {
            moveTo(-half, 0f); lineTo(half, 0f)
            moveTo(half - head, -head); lineTo(half, 0f); lineTo(half - head, head)
            if (abs(rate) > 2f) {
                val back = a * 0.3f
                moveTo(half - head - back, -head); lineTo(half - back, 0f); lineTo(half - head - back, head)
            }
        }
        canvas.save()
        canvas.translate(box.centerX(), box.centerY())
        canvas.rotate(TrendArrowAngle.rotationDegrees(rate))
        canvas.drawPath(path, paint)
        canvas.restore()
    }

    /** Pixels for a size in dp, at most [maxPx] on either side (the RemoteViews bitmap limit is about the screen). */
    fun pixels(dp: Float, density: Float, maxPx: Int): Int = (dp * density).roundToInt().coerceIn(1, max(1, maxPx))
}
