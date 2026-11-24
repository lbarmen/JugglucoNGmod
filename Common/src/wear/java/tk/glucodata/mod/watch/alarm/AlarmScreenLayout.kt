package tk.glucodata.mod.watch.alarm

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import tk.glucodata.TrendArrowAngle

/**
 * JugglucoNGmod, watch block (part 8): where everything of our alarm screen goes on a round screen, in shares of its
 * side (450 px on the Galaxy Watch 5 Pro, 225 dp), so the picture is the same at any density. From the bottom up: the
 * two buttons one above the other, each a pill as wide as the circle allows at its height less [RIM] (the upper one
 * capped at [MAX_BUTTON_W]), [BUTTON_H] tall (56 dp on that watch - two 64 dp buttons would leave no room for a
 * readable value; owner's photo of 18.11: upstream's two 52 dp half-width buttons cut their words); above them the
 * small line, and from [LABEL_TOP] the alert's name and the value, which take what is left.
 */
internal object AlarmGeometry {
    const val RIM = 0.035f
    const val BUTTON_H = 0.25f
    const val GAP = 0.03f
    /** The lower button's middle below the screen's centre. */
    const val BOTTOM_CENTER = 0.28f
    const val MAX_BUTTON_W = 0.78f
    const val BUTTON_TEXT = 0.072f
    const val BUTTON_TEXT_MIN = 0.05f
    /** Text keeps this much inside a button's straight part on each side. */
    const val BUTTON_PAD = 0.03f
    const val LABEL_TOP = 0.07f
    const val LABEL_TEXT = 0.062f
    const val LABEL_TEXT_MIN = 0.046f
    const val INFO_TEXT = 0.048f
    const val INFO_GAP = 0.012f
    const val VALUE_TEXT_MAX = 0.2f
    const val VALUE_GAP = 0.008f
    const val LINE = 1.22f
    /** Text keeps this far from the circle on each side. */
    const val TEXT_SIDE = 0.04f

    class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
        val centerY: Float get() = (top + bottom) / 2f
    }

    class Frame(
        val size: Float,
        val centerX: Float,
        val centerY: Float,
        val snooze: Box,
        val dismiss: Box,
        /** The small line's band (its width is [textWidth]). */
        val info: Box,
        /** Where the alert's name starts. */
        val textTop: Float,
    ) {
        val radius: Float get() = size / 2f

        /** The width a text line over [top]..[bottom] may take: the circle's chord at its outer edge, less [TEXT_SIDE]. */
        fun textWidth(top: Float, bottom: Float): Float {
            val dy = max(abs(top - centerY), abs(bottom - centerY))
            val half = sqrt(max(0f, radius * radius - dy * dy)) - TEXT_SIDE * size
            return max(0f, 2f * half)
        }
    }

    fun compute(width: Int, height: Int): Frame {
        val s = min(width, height).toFloat()
        val r = s / 2f
        val cx = width / 2f
        val cy = height / 2f
        val h = BUTTON_H * s
        val rim = RIM * s
        val bottomMid = cy + BOTTOM_CENTER * s
        val dismiss = pill(cx, bottomMid, h, min(MAX_BUTTON_W * s, 2f * pillHalfWidth(r, bottomMid - cy, h, rim)))
        val topMid = bottomMid - h - GAP * s
        val snooze = pill(cx, topMid, h, min(MAX_BUTTON_W * s, 2f * pillHalfWidth(r, topMid - cy, h, rim)))
        val infoBottom = snooze.top - INFO_GAP * s
        val info = Box(cx - r, infoBottom - INFO_TEXT * s * LINE, cx + r, infoBottom)
        return Frame(s, cx, cy, snooze, dismiss, info, cy - r + LABEL_TOP * s)
    }

    /**
     * Half the width of a pill [height] tall whose middle is [centerDy] from the centre of a circle of [radius]: its
     * round ends stay [rim] inside the circle. 0 when no pill fits there.
     */
    fun pillHalfWidth(radius: Float, centerDy: Float, height: Float, rim: Float): Float {
        val end = height / 2f
        val reach = radius - rim - end
        val v = reach * reach - centerDy * centerDy
        return if (reach <= 0f || v <= 0f) 0f else end + sqrt(v)
    }

    private fun pill(cx: Float, cy: Float, height: Float, width: Float) =
        Box(cx - width / 2f, cy - height / 2f, cx + width / 2f, cy + height / 2f)
}

/**
 * JugglucoNGmod, watch block (part 8): our alarm screen as plain views (pictures in Robolectric, no Compose): black,
 * the alert's name on top (one line, smaller for a long name, two lines when even that does not fit), the value large
 * in its colour with the trend arrow in the same colour (as our face; upstream's arrow from `TrendArrowAngle`, a
 * double head past 2 mg/dL/min), the small line, then "Snooze" above "Dismiss" as two big buttons with whole words.
 * The texts are drawn here; the buttons are real ones (touch, ripple, accessibility).
 *
 * Any exception while measuring, placing or drawing goes to [onFailure] once, on the main thread, and the screen
 * hands over to upstream's.
 */
@SuppressLint("ViewConstructor")
internal open class AlarmScreenLayout(
    context: Context,
    val ui: AlarmUi,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : ViewGroup(context) {

    val snoozeButton: AlarmButton = button(ui.snoozeText, SNOOZE_FILL, SNOOZE_TEXT) { onSnooze() }
    val dismissButton: AlarmButton = button(ui.dismissText, DISMISS_FILL, DISMISS_TEXT) { onDismiss() }

    private var frame: AlarmGeometry.Frame? = null
    private var failed = false

    /** The buttons' words, drawn here over each button (the same size for both: the one that fits both). */
    private val buttonPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    /** The buttons' text size after the last measure, px. */
    var buttonTextSize = 0f
        private set

    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LABEL_COLOR
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.LEFT
    }
    private val infoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INFO_COLOR
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowPath = Path()
    private val bounds = Rect()

    init {
        setWillNotDraw(false)
        setBackgroundColor(BACKGROUND)
        addView(snoozeButton)
        addView(dismissButton)
        contentDescription = listOfNotNull(ui.label, ui.value, ui.info).joinToString(". ")
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun button(text: String, fill: Int, textColor: Int, click: () -> Unit): AlarmButton =
        AlarmButton(context, text, textColor).apply {
            contentDescription = text
            isClickable = true
            isFocusable = true
            val shape = GradientDrawable().apply {
                setColor(fill)
                cornerRadius = 10_000f
            }
            val mask = GradientDrawable().apply {
                setColor(0xFFFFFFFF.toInt())
                cornerRadius = 10_000f
            }
            background = RippleDrawable(ColorStateList.valueOf(RIPPLE), shape, mask)
            setOnClickListener { click() }
        }

    // ------------------------------------------------------------------ measure, place, draw (guarded)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        guarded("measure") {
            val f = AlarmGeometry.compute(w, h)
            frame = f
            buttonTextSize = min(fitButton(snoozeButton, f.snooze, f.size), fitButton(dismissButton, f.dismiss, f.size))
            measureButton(snoozeButton, f.snooze)
            measureButton(dismissButton, f.dismiss)
        }
    }

    /** The largest text size at which the button's word fits its [box]. */
    private fun fitButton(button: AlarmButton, box: AlarmGeometry.Box, size: Float): Float =
        fitSize(buttonPaint, button.label, AlarmGeometry.BUTTON_TEXT * size, AlarmGeometry.BUTTON_TEXT_MIN * size,
            buttonTextWidth(box, size))

    private fun measureButton(button: AlarmButton, box: AlarmGeometry.Box) {
        button.measure(
            MeasureSpec.makeMeasureSpec(box.width.toInt(), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(box.height.toInt(), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        guarded("layout") {
            val f = frame ?: AlarmGeometry.compute(r - l, b - t).also { frame = it }
            place(snoozeButton, f.snooze)
            place(dismissButton, f.dismiss)
        }
    }

    private fun place(view: View, box: AlarmGeometry.Box) {
        val left = box.left.toInt()
        val top = box.top.toInt()
        view.layout(left, top, left + view.measuredWidth, top + view.measuredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        guarded("draw") {
            canvas.drawColor(BACKGROUND)
            val f = frame ?: AlarmGeometry.compute(width, height).also { frame = it }
            drawTexts(canvas, f)
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        guarded("draw buttons") {
            super.dispatchDraw(canvas)
            buttonPaint.textSize = buttonTextSize
            for (button in listOf(snoozeButton, dismissButton)) {
                if (button.width <= 0) continue
                buttonPaint.color = button.textColor
                drawLine(canvas, buttonPaint, button.label, (button.left + button.right) / 2f,
                    button.top.toFloat(), button.bottom.toFloat())
            }
        }
    }

    private inline fun guarded(where: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            fail(where, t)
        }
    }

    private fun fail(where: String, t: Throwable) {
        if (failed) return
        failed = true
        tk.glucodata.mod.watch.ModWatchLog.w("alarm screen $where failed: ${t.javaClass.simpleName}")
        try {
            Handler(Looper.getMainLooper()).post { onFailure(t) }
        } catch (_: Throwable) {
            onFailure(t)
        }
    }

    // ------------------------------------------------------------------ texts

    /** The name, the value with its arrow and the small line inside [f]. */
    internal open fun drawTexts(canvas: Canvas, f: AlarmGeometry.Frame) {
        val s = f.size
        val labelBottom = drawLabel(canvas, f)
        drawValue(canvas, f, labelBottom + AlarmGeometry.VALUE_GAP * s, f.info.top - AlarmGeometry.VALUE_GAP * s)
        drawInfo(canvas, f)
    }

    /** Returns the bottom of the name. */
    private fun drawLabel(canvas: Canvas, f: AlarmGeometry.Frame): Float {
        val s = f.size
        val top = f.textTop
        val text = ui.label
        var size = AlarmGeometry.LABEL_TEXT * s
        val minSize = AlarmGeometry.LABEL_TEXT_MIN * s
        while (true) {
            labelPaint.textSize = size
            val bottom = top + size * AlarmGeometry.LINE
            if (labelPaint.measureText(text) <= f.textWidth(top + LETTER_TOP * (bottom - top), bottom)) {
                drawLine(canvas, labelPaint, text, f.centerX, top, bottom)
                return bottom
            }
            if (size <= minSize) break
            size = max(minSize, size * 0.94f)
        }
        // two lines at the smallest size, the rest cut with an ellipsis
        labelPaint.textSize = minSize
        val bottom = top + 2f * minSize * AlarmGeometry.LINE
        val width = f.textWidth(top + LETTER_TOP / 2f * (bottom - top), bottom).toInt().coerceAtLeast(1)
        labelPaint.textAlign = Paint.Align.LEFT
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, labelPaint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        canvas.save()
        canvas.translate(f.centerX - width / 2f, top + (bottom - top - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
        labelPaint.textAlign = Paint.Align.CENTER
        return bottom
    }

    private fun drawValue(canvas: Canvas, f: AlarmGeometry.Frame, top: Float, bottom: Float) {
        val boxHeight = bottom - top
        if (boxHeight <= 0f) return
        val arrow = ui.rate.isFinite()
        // the digits' height fills ~85 % of the band; the row narrows to the circle at its outer edge
        var size = min(AlarmGeometry.VALUE_TEXT_MAX * f.size, sizeForCap(boxHeight * 0.85f))
        val available = f.textWidth(top, bottom)
        var cap = 0f
        var rowWidth = 0f
        var arrowSize = 0f
        for (step in 0 until 40) {
            cap = capHeight(size)
            arrowSize = if (arrow) cap * 1.15f else 0f
            val gap = if (arrow) size * 0.18f else 0f
            valuePaint.textSize = size
            rowWidth = valuePaint.measureText(ui.value) + gap + arrowSize
            if (rowWidth <= available || size < 4f) break
            size *= 0.95f
        }
        val centerY = (top + bottom) / 2f
        val left = f.centerX - rowWidth / 2f
        valuePaint.color = ui.valueColor
        canvas.drawText(ui.value, left, centerY + cap / 2f, valuePaint)
        if (arrow) {
            drawArrow(canvas, left + rowWidth - arrowSize, centerY - arrowSize / 2f, arrowSize, ui.rate, ui.valueColor)
        }
    }

    private fun drawInfo(canvas: Canvas, f: AlarmGeometry.Frame) {
        val info = ui.info ?: return
        infoPaint.textSize = AlarmGeometry.INFO_TEXT * f.size
        val width = f.textWidth(f.info.top, f.info.bottom)
        val text = TextUtils.ellipsize(info, infoPaint, width, TextUtils.TruncateAt.END).toString()
        drawLine(canvas, infoPaint, text, f.centerX, f.info.top, f.info.bottom)
    }

    /** One centred line, its font box centred in [top]..[bottom]. */
    private fun drawLine(canvas: Canvas, paint: Paint, text: String, cx: Float, top: Float, bottom: Float) {
        val fm = paint.fontMetrics
        val baseline = (top + bottom) / 2f - (fm.ascent + fm.descent) / 2f
        canvas.drawText(text, cx, baseline, paint)
    }

    /** The digits' height at [size] px (0.72 of the size when the font gives none). */
    private fun capHeight(size: Float): Float {
        valuePaint.textSize = size
        valuePaint.getTextBounds(DIGITS, 0, DIGITS.length, bounds)
        val h = bounds.height().toFloat()
        return if (h > 0f && h < size * 1.5f) h else size * 0.72f
    }

    private fun sizeForCap(cap: Float): Float {
        val ref = 100f
        val refCap = capHeight(ref)
        return cap * ref / refCap
    }

    /**
     * Upstream's trend arrow in a [size] square: a shaft and a head turned by `TrendArrowAngle.rotationDegrees`
     * (tip up for a rise), a second head past 2 mg/dL/min.
     */
    private fun drawArrow(canvas: Canvas, left: Float, top: Float, size: Float, rate: Float, color: Int) {
        val cx = left + size / 2f
        val cy = top + size / 2f
        val double = abs(rate) > 2f
        val head = size * 0.36f
        val start = cx - size * 0.42f
        val tip = cx + size * (if (double) 0.22f else 0.42f)
        arrowPaint.color = color
        arrowPaint.strokeWidth = size * 0.13f
        arrowPath.reset()
        arrowPath.moveTo(start, cy)
        arrowPath.lineTo(tip, cy)
        arrowPath.moveTo(tip - head, cy - head)
        arrowPath.lineTo(tip, cy)
        arrowPath.lineTo(tip - head, cy + head)
        if (double) {
            val second = tip + size * 0.2f
            arrowPath.moveTo(second - head, cy - head)
            arrowPath.lineTo(second, cy)
            arrowPath.lineTo(second - head, cy + head)
        }
        canvas.save()
        canvas.rotate(TrendArrowAngle.rotationDegrees(rate), cx, cy)
        canvas.drawPath(arrowPath, arrowPaint)
        canvas.restore()
    }

    /**
     * A button: touch, ripple, focus and "button" for accessibility services (its word as content description); the
     * word itself is drawn by the screen over it.
     */
    class AlarmButton(context: Context, val label: String, val textColor: Int) : View(context) {
        override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
    }

    companion object {
        const val BACKGROUND = 0xFF000000.toInt()
        /** Upstream's screen draws the name in the theme's error colour. */
        const val LABEL_COLOR = 0xFFFF8A80.toInt()
        const val INFO_COLOR = 0xFFC4C7C5.toInt()
        const val SNOOZE_FILL = 0xFFA8C7FA.toInt()
        const val SNOOZE_TEXT = 0xFF062E6F.toInt()
        const val DISMISS_FILL = 0xFF33373D.toInt()
        const val DISMISS_TEXT = 0xFFE3E2DE.toInt()
        const val RIPPLE = 0x55FFFFFF
        private const val DIGITS = "0123456789"
        /** Where letters start in a line's box (the line spacing above them): the circle's width is taken there. */
        private const val LETTER_TOP = 0.3f

        /** The width a button's word may take: the straight part plus a little of each round end, less the padding. */
        fun buttonTextWidth(box: AlarmGeometry.Box, size: Float): Float =
            box.width - 0.4f * box.height - 2f * AlarmGeometry.BUTTON_PAD * size

        /** The largest size up to [max] at which [text] is no wider than [width] ([min] at the least). */
        fun fitSize(paint: Paint, text: String, max: Float, min: Float, width: Float): Float {
            var size = max
            val saved = paint.textSize
            while (size > min) {
                paint.textSize = size
                if (paint.measureText(text) <= width) break
                size = kotlin.math.max(min, size * 0.95f)
            }
            paint.textSize = saved
            return size
        }
    }
}
