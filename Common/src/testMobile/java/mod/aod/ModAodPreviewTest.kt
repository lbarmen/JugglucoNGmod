package mod.aod

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.R
import tk.glucodata.mod.aod.ModAodBlock
import tk.glucodata.mod.aod.ModAodPainter
import tk.glucodata.mod.aod.ModAodPalette
import tk.glucodata.mod.aod.ModAodPlot
import tk.glucodata.mod.aod.ModAodPoint
import tk.glucodata.mod.aod.ModAodStyle
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * JugglucoNGmod phone AOD (part 1, the owner's variant B): the block on a phone screen of 411 x 914 dp (an estimate -
 * the phone's model is not recorded) at upstream's "Bottom" position (`AODOverlayService`: gravity BOTTOM, y = 100 px)
 * with the sizes their AOD screen saves (text 1.5, chart 1.5). Made-up readings; PNGs only when MOD_AOD_PREVIEW_DIR
 * is set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "ru-w411dp-h914dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModAodPreviewTest {

    private val ctx: Application get() = RuntimeEnvironment.getApplication()
    private val dm get() = ctx.resources.displayMetrics
    private val zone = ZoneId.of("UTC")
    private val ru = Locale("ru")
    private val min = 60_000L
    private val now = ZonedDateTime.of(2026, 11, 26, 13, 7, 20, 0, zone).toInstant().toEpochMilli()

    private val textScale = 1.5f
    private val chartScale = 1.5f

    private class Range(val low: Float, val high: Float, val veryLow: Float, val veryHigh: Float, val isMmol: Boolean)
    private val mmol = Range(3.9f, 10f, 3.0f, 13.9f, true)
    private val mgdl = Range(70f, 180f, 54f, 250f, false)

    /** One case: readings, the arrow's rate (mg/dL a minute), the forecast, or the "no new value" text. */
    private class Case(
        val name: String,
        val readings: List<ModAodPoint>,
        val rate: Float,
        val forecast: List<ModAodPoint>,
        val range: Range,
        val stale: String? = null,
        val palette: ModAodPalette = ModAodStyle.ON_BLACK,
    )

    /** 3 h of one made-up reading a minute ending a minute before now, shaped by [shape] over 0..1 of the time. */
    private fun curve(shape: (Double) -> Double, skip: (Double) -> Boolean = { false }, end: Long = now - min) =
        (0..180).mapNotNull { i ->
            val t = i / 180.0
            if (skip(t)) null else ModAodPoint(end - (180 - i) * min, shape(t).toFloat())
        }

    private fun forecastFrom(last: ModAodPoint, step: Float, bend: Float) =
        (1..6).map { k -> ModAodPoint(last.timeMs + k * 5 * min, last.value + step * k - bend * k * k) }

    private fun ramp(t: Double, from: Double) = ((t - from) / (1 - from)).coerceIn(0.0, 1.0)

    /** A rise above the range an hour and a half ago, back, rising again for the last 36 minutes. */
    private val mainShape: (Double) -> Double = { t ->
        6.8 + 4.6 * exp(-((t - 0.42) / 0.13).let { it * it }) + 1.3 * ramp(t, 0.8) + 0.15 * sin(t * 40) * (1 - ramp(t, 0.8))
    }

    private val cases: List<Case> by lazy {
        val main = curve(mainShape)
        val high = curve({ t -> 8.2 + 6.4 * t * t + 0.12 * sin(t * 30) })
        val low = curve({ t -> 7.4 - 4.2 * t + 0.15 * sin(t * 25) })
        val gap = curve(mainShape, skip = { it in 0.45..0.68 })
        val staleEnd = now - 26 * min
        val stale = curve(mainShape, end = staleEnd)
        val staleText = ctx.getString(R.string.nonewvalue) +
            DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(staleEnd).atZone(zone))
        val mg = main.map { it.copy(value = it.value * 18f) }
        listOf(
            Case("1 in range, rising", main, 0.65f, forecastFrom(main.last(), 0.22f, 0.015f), mmol),
            Case("2 high, fast rise, forecast", high, 2.6f, forecastFrom(high.last(), 0.6f, 0.02f), mmol),
            Case("3 low, falling", low, -1.6f, forecastFrom(low.last(), -0.2f, -0.012f), mmol),
            Case("4 sensor gap of 40 min", gap, 0.65f, forecastFrom(gap.last(), 0.22f, 0.015f), mmol),
            Case("5 no new value for 26 min", stale, Float.NaN, emptyList(), mmol, stale = staleText),
            Case("6 mg/dL", mg, 0.65f, forecastFrom(mg.last(), 4f, 0.27f), mgdl),
            Case("7 lock screen over a light wallpaper", main, 0.65f, forecastFrom(main.last(), 0.22f, 0.015f), mmol,
                palette = ModAodStyle.ON_LIGHT),
        )
    }

    private fun zoneColor(range: Range, palette: ModAodPalette): (Float) -> Int = { v ->
        GlucoseRangeColors.colorForValue(
            v, range.low, range.high, range.veryLow, range.veryHigh,
            GlucoseRangeColors.inRange(palette.darkTheme), palette.darkTheme, range.isMmol,
        )
    }

    private fun numberSize() = 22f * 2f * dm.density * textScale

    private fun valueText(case: Case) = String.format(ru, if (case.range.isMmol) "%.1f" else "%.0f", case.readings.last().value)

    private fun block(case: Case): Bitmap {
        val last = case.readings.last()
        val sub = if (case.stale == null) {
            val before = case.readings.last { it.timeMs <= last.timeMs - 5 * min }
            // upstream's text for a change, as their DisplayDeltaSource gives it to the block
            val delta = tk.glucodata.GlucoseDelta.format(last.value - before.value, case.range.isMmol)
            delta + " · " + ModAodBlock.readingTime(last.timeMs, zone, "HH:mm", ru)
        } else null
        val value = ModAodPainter.Value(valueText(case).takeIf { case.stale == null }, case.rate, sub, case.stale)
        val plot = ModAodPlot.plot(case.readings, now, case.range.low, case.range.high, case.range.isMmol, case.forecast)
        return ModAodPainter(dm.density, dm.scaledDensity).blockBitmap(
            dm.widthPixels, numberSize(), (200 * dm.density * chartScale).toInt(), value, plot, case.range.isMmol,
            case.palette, zoneColor(case.range, case.palette), zone, true, ru,
        )
    }

    /** The overlay's top on the screen: upstream's "Bottom" - gravity BOTTOM, 100 px above the bottom edge. */
    private fun overlayTop(overlay: Bitmap): Int = dm.heightPixels - 100 - overlay.height

    /** Samsung's own always-on clock, roughly where the owner's photo has it (26.11): the upper third. */
    private fun drawSamsungClock(canvas: Canvas, w: Int, h: Int, density: Float, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
        p.textSize = 84f * density
        val clock = "13:07"
        canvas.drawText(clock, (w - p.measureText(clock)) / 2f, h * 0.20f, p)
        p.textSize = 16f * density
        val date = DateTimeFormatter.ofPattern("EEE, d MMMM", ru).format(Instant.ofEpochMilli(now).atZone(zone))
        canvas.drawText(date, (w - p.measureText(date)) / 2f, h * 0.20f + 32f * density, p)
        p.textSize = 11f * density
        val note = "Samsung AOD clock (approx.)"
        canvas.drawText(note, (w - p.measureText(note)) / 2f, h * 0.20f + 56f * density, p)
        val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; this.color = color; strokeWidth = density
            pathEffect = DashPathEffect(floatArrayOf(6f * density, 6f * density), 0f)
        }
        canvas.drawRoundRect(RectF(24f * density, h * 0.20f - 90f * density, w - 24f * density, h * 0.20f + 66f * density), 16f * density, 16f * density, frame)
    }

    private fun screen(overlay: Bitmap, lightWallpaper: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(dm.widthPixels, dm.heightPixels, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (lightWallpaper) {
            canvas.drawRect(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), 0xFFE8E2D0.toInt(), 0xFFB9CFD6.toInt(), Shader.TileMode.CLAMP)
            })
        } else {
            canvas.drawColor(Color.BLACK)
        }
        drawSamsungClock(canvas, dm.widthPixels, dm.heightPixels, dm.density, if (lightWallpaper) 0xFF505050.toInt() else 0xFF707070.toInt())
        canvas.drawBitmap(overlay, 0f, overlayTop(overlay).toFloat(), null)
        return bmp
    }

    /** Mean brightness of the overlay alone on black over the whole screen, 0..1 (Google's watch-face rule WO-P7: at most 15 %). */
    private fun meanBrightness(overlay: Bitmap): Double {
        var sum = 0.0
        val row = IntArray(overlay.width)
        for (y in 0 until overlay.height) {
            overlay.getPixels(row, 0, overlay.width, 0, y, overlay.width, 1)
            for (c in row) {
                sum += Color.alpha(c) / 255.0 * (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
            }
        }
        return sum / (dm.widthPixels.toDouble() * dm.heightPixels)
    }

    @Test
    fun everyCaseStaysBelowSamsungsClockOnTheScreenAndDim() {
        for (case in cases) {
            val overlay = block(case)
            assertEquals(case.name, dm.widthPixels, overlay.width)
            assertTrue("${case.name}: reaches Samsung's clock (top at ${overlayTop(overlay)} px)", overlayTop(overlay) > dm.heightPixels / 3)
            if (case.palette == ModAodStyle.ON_BLACK) assertTrue("${case.name}: more than 15 % lit", meanBrightness(overlay) < 0.15)
        }
    }

    @Test
    fun theNumberAndTheArrowAreNeverColouredAndTheDigitsAreCentred() {
        for (case in cases.filter { it.stale == null && it.palette == ModAodStyle.ON_BLACK }) {
            val bmp = block(case)
            // the number's row: from the top to just under the digits
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = numberSize(); typeface = Typeface.DEFAULT }
            val digits = android.graphics.Rect().also { paint.getTextBounds("0", 0, 1, it) }
            val rowBottom = (ModAodPainter.BLOCK_TOP_DP * dm.density + digits.height()).toInt()
            var leftmost = Int.MAX_VALUE
            val row = IntArray(bmp.width)
            for (y in 0 until rowBottom) {
                bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
                for (x in row.indices) {
                    val c = row[x]
                    if (Color.alpha(c) < 200) continue
                    assertTrue("${case.name}: coloured pixel ${Integer.toHexString(c)} at $x,$y",
                        abs(Color.red(c) - Color.green(c)) <= 3 && abs(Color.green(c) - Color.blue(c)) <= 3)
                    if (x < leftmost) leftmost = x
                }
            }
            val text = valueText(case)
            val expectedLeft = dm.widthPixels / 2f - paint.measureText(text) / 2f
            assertTrue("${case.name}: digits not centred (left $leftmost, expected about $expectedLeft)",
                abs(leftmost - expectedLeft) < 8 * dm.density)
        }
    }

    @Test
    fun pictures() {
        val dir = System.getenv("MOD_AOD_PREVIEW_DIR")?.takeIf { it.isNotBlank() }?.let { File(it) } ?: return
        dir.mkdirs()
        val report = StringBuilder()
        val blocks = cases.map { it to block(it) }
        for ((case, bmp) in blocks) {
            report.append(String.format(Locale.US, "%s: block %dx%d px, top %d px of %d, lit %.2f %%%n",
                case.name, bmp.width, bmp.height, overlayTop(bmp), dm.heightPixels, meanBrightness(bmp) * 100))
        }
        // the main case on the whole screen, as the owner will see it
        val (mainCase, mainBlock) = blocks.first()
        File(dir, "aod-B.png").outputStream().use { screen(mainBlock, mainCase.palette == ModAodStyle.ON_LIGHT).compress(Bitmap.CompressFormat.PNG, 100, it) }
        // every case on its screen, half size, two columns, a title over each
        val scale = 0.5f
        val gap = (20 * dm.density).toInt()
        val title = (30 * dm.density).toInt()
        val sw = (dm.widthPixels * scale).toInt()
        val shown = (dm.heightPixels * 0.62f * scale).toInt() // the lower part of the screen, where the block is
        val cols = 2
        val rows = (blocks.size + cols - 1) / cols
        val sheet = Bitmap.createBitmap(gap + cols * (sw + gap), rows * (title + shown + gap) + gap, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(0xFF202124.toInt())
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14f * dm.density }
        blocks.forEachIndexed { i, (case, bmp) ->
            val full = screen(bmp, case.palette == ModAodStyle.ON_LIGHT)
            val left = gap + (i % cols) * (sw + gap)
            val top = gap + (i / cols) * (title + shown + gap)
            canvas.drawText(case.name, left.toFloat(), top + title - 10f * dm.density, label)
            val src = android.graphics.Rect(0, (dm.heightPixels * 0.38f).toInt(), dm.widthPixels, dm.heightPixels)
            canvas.drawBitmap(full, src, RectF(left.toFloat(), (top + title).toFloat(), (left + sw).toFloat(), (top + title + shown).toFloat()), null)
        }
        File(dir, "aod-states.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "aod-report.txt").writeText(report.toString())
    }
}
