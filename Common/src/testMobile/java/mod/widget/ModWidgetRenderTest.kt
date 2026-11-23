package mod.widget

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.mod.widget.ModWidgetDots
import tk.glucodata.mod.widget.ModWidgetFrame
import tk.glucodata.mod.widget.ModWidgetInput
import tk.glucodata.mod.widget.ModWidgetLayout
import tk.glucodata.mod.widget.ModWidgetMath
import tk.glucodata.mod.widget.ModWidgetNumberSize
import tk.glucodata.mod.widget.ModWidgetPoint
import tk.glucodata.mod.widget.ModWidgetRange
import tk.glucodata.mod.widget.ModWidgetRenderer
import tk.glucodata.mod.widget.ModWidgetSettings
import tk.glucodata.mod.widget.ModWidgetTexts
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * JugglucoNGmod widget: the picture - nothing leaves the widget at one row of icons, and the look pictures for the user
 * (made-up readings; written as PNG only when MOD_WIDGET_PREVIEW_DIR is set).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModWidgetRenderTest {

    private val density = 2.75f
    private val min = ModWidgetMath.MINUTE_MS
    private val now = 1_000_000L * ModWidgetMath.FIVE_MIN_MS + 2 * min + 10_000L
    private val timeout = 330_000L
    private val mmol = ModWidgetRange(3.9f, 10f, 3.0f, 13.9f, true)

    private val texts = object : ModWidgetTexts {
        override fun justNow() = "just now"
        override fun minutesAgo(minutes: Int) = "$minutes min ago"
        override fun stale(readingTime: Long) = "No new value since 14:32"
        override fun noValue() = "No value"
        override fun unit(isMmol: Boolean) = if (isMmol) "mmol/L" else "mg/dL"
        override fun trend(trend: ModWidgetMath.Trend) = trend.name
        override fun example() = "Example"
    }

    /** 3 h of one made-up reading a minute ending at [end], the shape from [shape] (0..1 of the time). */
    private fun curve(end: Long, shape: (Double) -> Double, rate: Float): ModWidgetInput {
        val n = 180
        val points = (0..n).map { i -> ModWidgetPoint(end - (n - i) * min, shape(i / n.toDouble()).toFloat()) }
        val last = points.last()
        return ModWidgetInput(true, String.format(java.util.Locale.US, "%.1f", last.value), last.value, rate, last.time, points, mmol)
    }

    private val readingTime = now - 2 * min
    private val inRange = curve(readingTime, { 6.4 + 1.2 * sin(it * 6.0) + 0.3 * sin(it * 31.0) + it * 0.6 }, 0.7f)
    private val low = curve(readingTime, { 7.5 - 4.2 * it + 0.2 * sin(it * 25.0) }, -1.4f)
    private val high = curve(readingTime, { 7.0 + 5.9 * it * it + 0.2 * sin(it * 20.0) }, 2.4f)

    private fun frame(input: ModWidgetInput, s: ModWidgetSettings, at: Long = now) = ModWidgetFrame.build(input, s, at, timeout, texts)

    private fun px(dp: Float) = (dp * density).roundToInt()

    // ---------- nothing leaves the widget ----------

    private fun assertInside(l: ModWidgetRenderer.Layout, what: String) {
        val bounds = RectF(0f, 0f, l.width.toFloat(), l.height.toFloat())
        (l.parts() + listOfNotNull(l.chart)).forEach { r ->
            assertTrue("$what: $r outside $bounds", r.left >= -0.5f && r.top >= -0.5f && r.right <= bounds.right + 0.5f && r.bottom <= bounds.bottom + 0.5f)
        }
        val v = l.value
        val a = l.arrow
        if (v != null && a != null) assertTrue("$what: arrow over the number", a.left >= v.right - 0.5f)
        val col = listOfNotNull(l.delta, l.status)
        val rightOfNumber = a?.right ?: v?.right
        if (rightOfNumber != null) col.forEach { assertTrue("$what: text over the number or arrow", it.left >= rightOfNumber - 0.5f) }
        if (l.delta != null && l.status != null) assertTrue("$what: delta over status", l.status.top >= l.delta.bottom - 0.5f)
    }

    @Test
    fun oneRowOfIconsAndEverySizeKeepEverythingInside() {
        val sizes = listOf(110f to 40f, 180f to 40f, 250f to 40f, 340f to 48f, 340f to 60f, 340f to 130f, 110f to 110f)
        val settings = buildList {
            for (layout in ModWidgetLayout.entries) for (size in ModWidgetNumberSize.entries) for (t in listOf(100, 50))
                add(ModWidgetSettings(backgroundTransparency = t, layout = layout, numberSize = size))
        }
        val stale = frame(inRange, ModWidgetSettings(), readingTime + timeout + 1)
        for ((w, h) in sizes) for (s in settings) {
            for ((name, f) in listOf("fresh" to frame(high, s), "stale" to stale.copy(settings = s),
                    "none" to frame(ModWidgetInput.EMPTY_MMOL, s))) {
                val l = ModWidgetRenderer.layout(f, px(w), px(h), density)
                assertInside(l, "$name ${w}x$h $s")
                if (name != "none") assertNotNull("$name ${w}x$h: number shown", l.value)
            }
        }
    }

    @Test
    fun theNumberIsNeverCutAndFitsTheWidth() {
        val mgdl = ModWidgetRange(70f, 180f, 54f, 250f, false)
        val inputs = listOf(
            ModWidgetInput(true, "13.9", 13.9f, 2.5f, readingTime, emptyList(), mmol),
            ModWidgetInput(true, "250", 250f, 2.5f, readingTime, emptyList(), mgdl),
            ModWidgetInput(true, "22.2", 22.2f, Float.NaN, readingTime, emptyList(), mmol),
        )
        for ((w, h) in listOf(110f to 110f, 110f to 40f, 130f to 200f))
            for (size in ModWidgetNumberSize.entries) for (input in inputs) {
                val l = ModWidgetRenderer.layout(frame(input, ModWidgetSettings(numberSize = size)), px(w), px(h), density)
                val v = l.value!!
                val textW = android.graphics.Paint().apply {
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    textSize = l.valueSizePx
                }.measureText(input.valueText)
                assertEquals("${input.valueText} ${w}x$h $size", textW, v.width(), 0.5f)
                assertTrue("${input.valueText} ${w}x$h $size: wider than the widget", v.right <= px(w) + 0.5f)
            }
    }

    @Test
    fun atOneRowTheOverlayShowsNumberArrowDeltaAgeAndChart() {
        val l = ModWidgetRenderer.layout(frame(inRange, ModWidgetSettings()), px(340f), px(48f), density)
        assertNotNull(l.value); assertNotNull(l.arrow); assertNotNull(l.delta); assertNotNull(l.status); assertNotNull(l.chart)
        assertEquals("2 min ago", l.statusText)
        // the number is readable: at least 20 dp at one row
        assertTrue(l.valueSizePx >= 20f * density)
    }

    @Test
    fun theTopLayoutDropsTheChartWhenThereIsNoRoom() {
        val top = ModWidgetSettings(layout = ModWidgetLayout.TOP)
        assertNull(ModWidgetRenderer.layout(frame(inRange, top), px(340f), px(40f), density).chart)
        val tall = ModWidgetRenderer.layout(frame(inRange, top), px(340f), px(130f), density)
        assertNotNull(tall.chart)
        assertTrue(tall.chart!!.top >= tall.value!!.bottom)
    }

    @Test
    fun transparentByDefaultAndBlackAtZeroTransparency() {
        val clear = ModWidgetRenderer.render(frame(ModWidgetInput.EMPTY_MMOL, ModWidgetSettings()), px(340f), px(60f), density)
        assertEquals(0, Color.alpha(clear.getPixel(clear.width - 3, clear.height / 2)))
        val black = ModWidgetRenderer.render(frame(ModWidgetInput.EMPTY_MMOL, ModWidgetSettings(backgroundTransparency = 0)), px(340f), px(60f), density)
        assertEquals(255, Color.alpha(black.getPixel(black.width / 2, black.height - px(6f))))
        val half = ModWidgetRenderer.render(frame(ModWidgetInput.EMPTY_MMOL, ModWidgetSettings(backgroundTransparency = 50)), px(340f), px(60f), density)
        assertEquals(128f, Color.alpha(half.getPixel(half.width / 2, half.height - px(6f))).toFloat(), 2f)
    }

    @Test
    fun theNewestDotIsAtTheRightEdgeInItsRangeColor() {
        val f = frame(high, ModWidgetSettings(dots = ModWidgetDots.EACH))
        val bmp = ModWidgetRenderer.render(f, px(340f), px(60f), density)
        val l = ModWidgetRenderer.layout(f, px(340f), px(60f), density)
        val chart = l.chart!!
        val last = f.points.last()
        val scale = ModWidgetMath.verticalScale(f.points.map { it.value }, mmol.low, mmol.high)
        val x = ModWidgetMath.x(last.time, now, f.settings.periodMillis, chart.left, chart.right).roundToInt()
        val y = scale.y(last.value, chart.top, chart.bottom).roundToInt()
        val c = bmp.getPixel(x, y)
        assertTrue(Color.alpha(c) > 200)
        val expected = mmol.color(last.value)
        assertTrue(kotlin.math.abs(Color.red(c) - Color.red(expected)) < 40 && kotlin.math.abs(Color.green(c) - Color.green(expected)) < 40)
    }

    // ---------- the look pictures ----------

    private class Variant(val name: String, val wDp: Float, val hDp: Float, val frame: ModWidgetFrame)

    @Test
    fun lookPictures() {
        val base = ModWidgetSettings()
        val variants = listOf(
            Variant("01 4x1 transparent overlay in-range", 340f, 56f, frame(inRange, base)),
            Variant("02 4x1 transparent overlay low", 340f, 56f, frame(low, base)),
            Variant("03 4x1 transparent overlay high", 340f, 56f, frame(high, base)),
            Variant("04 4x1 50pct overlay in-range", 340f, 56f, frame(inRange, base.copy(backgroundTransparency = 50))),
            Variant("05 4x1 transparent top in-range", 340f, 56f, frame(inRange, base.copy(layout = ModWidgetLayout.TOP))),
            Variant("06 4x1 transparent overlay stale", 340f, 56f, frame(inRange, base, readingTime + timeout + 60_000L)),
            Variant("07 4x2 transparent overlay in-range", 340f, 130f, frame(inRange, base)),
            Variant("08 4x2 50pct top in-range", 340f, 130f, frame(inRange, base.copy(backgroundTransparency = 50, layout = ModWidgetLayout.TOP))),
            Variant("09 4x2 50pct overlay high range-lines", 340f, 130f, frame(high, base.copy(backgroundTransparency = 50, rangeLines = true))),
            Variant("10 4x1 every reading", 340f, 56f, frame(inRange, base.copy(dots = ModWidgetDots.EACH))),
            Variant("11 4x1 line", 340f, 56f, frame(inRange, base.copy(dots = ModWidgetDots.LINE))),
            Variant("12 narrowest 110x40", 110f, 40f, frame(inRange, base)),
            Variant("13 4x1 no data", 340f, 56f, frame(ModWidgetInput.EMPTY_MMOL, base)),
            Variant("14 settings preview example", 340f, 104f, frame(ModWidgetInput.example(now, mmol), base)),
        )
        val dir = System.getenv("MOD_WIDGET_PREVIEW_DIR")?.takeIf { it.isNotBlank() }?.let { File(it) }
        dir?.mkdirs()
        val sheetW = px(2 * 340f + 3 * 12f)
        val rowH = variants.map { px(it.hDp + 24f + 18f) }
        val sheet = Bitmap.createBitmap(sheetW, rowH.sum() + px(12f), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFF202124.toInt())
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 12f * density }
        var y = px(12f).toFloat()
        variants.forEachIndexed { i, v ->
            val w = px(v.wDp)
            val h = px(v.hDp)
            val bmp = ModWidgetRenderer.render(v.frame, w, h, density)
            assertEquals(w, bmp.width)
            canvas.drawText(v.name, px(12f).toFloat(), y + 14f * density, label)
            val top = y + px(20f)
            // the same widget over a dark and over a light wallpaper
            listOf(
                intArrayOf(0xFF1B3A4B.toInt(), 0xFF3E6B5A.toInt()),
                intArrayOf(0xFFE8E2D0.toInt(), 0xFFB9CFD6.toInt()),
            ).forEachIndexed { k, colors ->
                val left = px(12f + k * (340f + 12f)).toFloat()
                val wall = RectF(left, top, left + px(340f), top + h + px(8f))
                canvas.drawRect(wall, Paint().apply { shader = LinearGradient(wall.left, wall.top, wall.right, wall.bottom, colors[0], colors[1], Shader.TileMode.CLAMP) })
                canvas.drawBitmap(bmp, left, top + px(4f), null)
            }
            dir?.let { d -> File(d, v.name.replace(' ', '_') + ".png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            y += rowH[i]
        }
        dir?.let { d -> File(d, "00_all_on_wallpapers.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
