package mod.watch.face

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.GlucoseRangeColors
import tk.glucodata.mod.watch.face.FaceLabels
import tk.glucodata.mod.watch.face.FaceLayout
import tk.glucodata.mod.watch.face.FaceModel
import tk.glucodata.mod.watch.face.FacePainter
import tk.glucodata.mod.watch.face.FacePoint
import tk.glucodata.mod.watch.face.FacePumpIob
import tk.glucodata.mod.watch.face.FaceReading
import tk.glucodata.mod.watch.face.FaceSnapshot
import tk.glucodata.mod.watch.face.FaceStyle
import tk.glucodata.mod.watch.face.ModFaceSource

/**
 * JugglucoNGmod, watch block (part 3b, part 7a, part 7c): the face painted into a 450 px bitmap (Galaxy Watch 5 Pro)
 * with real pixels (Robolectric native graphics) and the real colours (upstream's traffic light through
 * [ModFaceSource], their default limits 3.9 / 10.0, very low 3.0, very high 13.9 mmol/L). Black around, something in
 * every row, the whole picture inside the circle, the date and batteries at the bottom, the forecast only when there
 * is one (in the dim mode too, dimmed, part 7l), the arrow and the change in the number's colour (part 7l), batteries
 * yellow and red, the dim number moved a step a minute. Made-up values, Russian labels as on the watch.
 *
 * With MOD_FACE_PREVIEW_DIR set, every scenario is also saved there as PNG: round mask and, where Wear OS draws the
 * icon of upstream's ongoing activity, the faint dot our watch build makes of it (part 7c) - to look at before
 * installing; plus the face list's 384 px preview (`mod_watch_face_preview`, unmasked).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "ru")
class FacePainterTest {

    private val zone = ZoneId.of("UTC")
    private val now = LocalDateTime.of(2026, 11, 18, 19, 57).atZone(zone).toInstant().toEpochMilli()
    private val minute = 60_000L
    private val size = 450
    private val green = GlucoseRangeColors.VALUE_IN_RANGE_DARK
    private val yellow = GlucoseRangeColors.VALUE_BORDERLINE_DARK
    private val red = GlucoseRangeColors.VALUE_OUT_DARK
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
    }

    @After
    fun tearDown() = Locale.setDefault(savedLocale)

    private fun labels() = FaceLabels.from(ApplicationProvider.getApplicationContext())

    // ------------------------------------------------------------------ scenarios

    /**
     * Six hours of one-minute readings by [curve] (minutes back -> mmol/L), the last [ageMin] minutes old, the
     * value moving at [rate] mg/dL/min; [forecast] - minutes ahead -> mmol/L every 5 minutes up to 2 h, or none.
     */
    private fun scenario(
        curve: (Int) -> Float,
        rate: Float,
        forecast: ((Int) -> Float)?,
        hours: Int = 3,
        phone: Int? = 64,
        watch: Int? = 81,
        ageMin: Int = 1,
    ): FaceSnapshot {
        val history = (360 downTo ageMin).map { back -> FacePoint(now - back * minute, curve(back)) }
        val last = history.last()
        val color = ModFaceSource.trafficColor(last.value, true)
        val current = FaceReading(last.timeMs, last.value, String.format(Locale.forLanguageTag("ru-RU"), "%.1f", last.value),
            rate, color)
        val ahead = forecast?.let { f -> (5..120 step 5).map { FacePoint(now + it * minute, f(it)) } }.orEmpty()
        return FaceSnapshot(now, true, true, hours, current, history, history, 3.9f, 10f, phone, watch, ahead)
    }

    private fun calm(back: Int) = 6.2f - 0.9f * sin(back / 23.0).toFloat()

    private fun inRange() = scenario(::calm, 0.3f, forecast = { 6.4f + it * 0.004f })

    /** A rise above 10 over the last hour (the photo of 18.11), the forecast turning down after a bolus. */
    private fun highForecastDown() = scenario(
        curve = { back -> if (back > 60) calm(back) else calm(60) + (11.8f - calm(60)) * (1f - back / 60f) },
        rate = 1.6f,
        forecast = { m -> if (m <= 10) 11.8f + m * 0.03f else 12.1f - (m - 10) * 0.075f },
    )

    private fun low() = scenario(
        curve = { back -> if (back > 50) calm(back) else calm(50) + (3.4f - calm(50)) * (1f - back / 50f) },
        rate = -1.5f,
        forecast = { m -> maxOf(2.6f, 3.4f - m * 0.03f) },
    )

    private fun paint(
        snapshot: FaceSnapshot,
        ambient: Boolean,
        trial: Boolean = false,
        name: String? = null,
        px: Int = size,
        offset: Pair<Float, Float> = 0f to 0f,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val model = FaceModel.build(snapshot, zone, labels(), ambient)
        val limits = ModFaceSource.limits(true)
        FacePainter().draw(Canvas(bitmap), px, px, model, true, ambient, showData = true, trial = trial,
            traceColor = { ModFaceSource.trafficColor(it, true, limits) }, offsetX = offset.first, offsetY = offset.second)
        save(bitmap, name)
        return bitmap
    }

    // ------------------------------------------------------------------ preview files

    private fun save(bitmap: Bitmap, name: String?) {
        val dir = System.getenv("MOD_FACE_PREVIEW_DIR")?.takeIf { it.isNotBlank() } ?: return
        if (name == null) return
        File(dir).mkdirs()
        val out = if (name == FACE_LIST_PREVIEW) bitmap else masked(bitmap)
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * The picture as the round screen shows it, with the system's ongoing-activity icon as our watch build draws it:
     * seen on the watch at ~0.82..0.90 of the diameter, ~0.07 of it tall; our `novalue` is a dot of 1.5 / 24 of the
     * icon's box at a quarter alpha.
     */
    private fun masked(source: Bitmap): Bitmap {
        val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val layout = FaceLayout.compute(bitmap.width, bitmap.height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val outside = Path().apply {
            addCircle(layout.centerX, layout.centerY, layout.radius, Path.Direction.CW)
            fillType = Path.FillType.INVERSE_WINDING
        }
        paint.color = 0xFF202020.toInt()
        canvas.drawPath(outside, paint)
        val d = layout.radius * 2f
        paint.color = 0x40FFFFFF
        canvas.drawCircle(layout.centerX, layout.centerY - layout.radius + 0.86f * d, 0.07f * d * 1.5f / 24f, paint)
        return bitmap
    }

    // ------------------------------------------------------------------ pixel helpers

    private fun lit(bitmap: Bitmap, row: FaceLayout.Row? = null): Int {
        var n = 0
        val top = row?.top?.toInt()?.coerceAtLeast(0) ?: 0
        val bottom = row?.bottom?.toInt()?.coerceAtMost(bitmap.height) ?: bitmap.height
        for (y in top until bottom) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) and 0xFFFFFF != 0) n++
        }
        return n
    }

    private fun count(bitmap: Bitmap, row: FaceLayout.Row, color: Int, fromX: Int = 0, toX: Int = bitmap.width): Int {
        var n = 0
        for (y in row.top.toInt() until row.bottom.toInt()) for (x in fromX until toX) {
            if (bitmap.getPixel(x, y) == color) n++
        }
        return n
    }

    /** Lit pixels outside the circle less [inset] px. */
    private fun litOutside(bitmap: Bitmap, inset: Float): Int {
        val layout = FaceLayout.compute(bitmap.width, bitmap.height)
        var n = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (hypot(x + 0.5f - layout.centerX, y + 0.5f - layout.centerY) > layout.radius - inset &&
                bitmap.getPixel(x, y) and 0xFFFFFF != 0) n++
        }
        return n
    }

    private val forecastColor = FaceStyle.of(false).forecast!!

    /**
     * The left edge of the arrow in the value row (the change and the age follow it), placed as [FacePainter] places
     * the row: right of it only the arrow, the change and the age, none of the number.
     */
    private fun arrowX(snapshot: FaceSnapshot, layout: FaceLayout.Layout, bold: Boolean): Int {
        val model = FaceModel.build(snapshot, zone, labels(), ambient = !bold)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textSize = layout.value.textSize
        val valueWidth = paint.measureText(model.valueText)
        // the side texts as the painter sizes them (20.11): with the active insulin smaller; the age smaller and
        // regular unless red (full and bold), the active insulin as the small age
        val side = if (model.iobText != null) layout.sideTextIob else layout.sideTextSize
        fun width(s: String?, size: Float, b: Boolean): Float {
            if (s == null) return 0f
            paint.typeface = if (b) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            paint.textSize = size
            return paint.measureText(s)
        }
        val three = model.iobText != null
        val ageSize = if (three && !model.ageAlarm) side * FaceLayout.AGE_SMALL else side
        val sideWidth = maxOf(width(model.deltaText, side, false), width(model.ageText, ageSize, three && model.ageAlarm),
            width(model.iobText, side * FaceLayout.AGE_SMALL, false))
        return FaceLayout.placeValueRow(layout.value, valueWidth, layout.arrowSize, sideWidth, layout.radius * 0.04f)
            .arrowX.toInt()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun blackAroundSomethingInEveryRowAndAllInsideTheCircle() {
        val bitmap = paint(inRange(), ambient = false, name = "7e_in_range")
        val layout = FaceLayout.compute(size, size)
        for ((x, y) in listOf(1 to 1, size - 2 to 1, 1 to size - 2, size - 2 to size - 2, size / 2 to 2, size / 2 to size - 2)) {
            assertEquals("pixel $x,$y", FaceStyle.BLACK, bitmap.getPixel(x, y))
        }
        listOf(layout.time, layout.value, layout.graph, layout.hours, layout.status).forEach {
            assertTrue("row ${it.top}..${it.bottom} is empty", lit(bitmap, it) > 50)
        }
        assertEquals(0, lit(bitmap, layout.mark))
        assertEquals(0, litOutside(bitmap, 0.01f * size))
        // in range: the number, the arrow and the dots green
        assertTrue(count(bitmap, layout.value, green) > 200)
        assertTrue(count(bitmap, layout.graph, green) > 50)
        // the face list's preview, as before part 7a
        paint(inRange(), ambient = false, name = FACE_LIST_PREVIEW, px = 384)
    }

    @Test
    fun highWithTheForecastDownTheValueTheArrowAndTheChangeYellow() {
        val layout = FaceLayout.compute(size, size)
        val bitmap = paint(highForecastDown(), ambient = false, name = "7e_high_forecast_down")
        assertTrue(count(bitmap, layout.value, yellow) > 200)
        // 11.8 rising 1.6 mg/dL/min would pass very high (13.9) in 30 minutes, yet the arrow and the change keep the
        // number's yellow: they change colour only with the number (part 7l, the owner)
        assertEquals(0, count(bitmap, layout.value, red))
        assertTrue(count(bitmap, layout.value, yellow, arrowX(highForecastDown(), layout, bold = true)) > 20)
        // the forecast dashed in the right fifth of the graph, only there
        val rightFifth = (layout.graph.left + 0.79f * layout.graph.width).toInt()
        assertTrue(count(bitmap, layout.graph, forecastColor, rightFifth) > 10)
        assertEquals(count(bitmap, layout.graph, forecastColor, rightFifth), count(bitmap, layout.graph, forecastColor))
    }

    @Test
    fun lowTheValueYellowAndRedDotsBelowTheRange() {
        val layout = FaceLayout.compute(size, size)
        val bitmap = paint(low(), ambient = false, name = "7e_low")
        // 3.4 is between very low (3.0) and low (3.9): yellow, as the app paints it
        assertTrue(count(bitmap, layout.value, yellow) > 200)
        assertTrue(count(bitmap, layout.graph, yellow) > 10)
    }

    @Test
    fun theRangeLabelsStayReadableOverDots() {
        // part 7c: dots right on a label at the left end (the oldest half hour just above 10.0, then just above
        // 3.9) - the labels are painted after the dots with a black edge, so they keep about as many pixels as over
        // an empty graph
        val layout = FaceLayout.compute(size, size)
        val labelColor = FaceStyle.of(false).secondary
        val box = FaceLayout.Row(layout.graph.left, layout.graph.top, layout.graph.left + 0.12f * size,
            layout.graph.bottom, 0f)
        fun labelPixels(b: Bitmap) = count(b, box, labelColor, box.left.toInt(), box.right.toInt())
        val inEmpty = labelPixels(paint(scenario({ 6.5f }, 0f, forecast = null), ambient = false))
        assertTrue("labels $inEmpty", inEmpty > 10)
        for ((value, name) in listOf(10.25f to "7e_labels_over_dots", 4.15f to null)) {
            val covered = paint(scenario({ back -> if (back >= 150) value else 6.5f }, 0f, forecast = null),
                ambient = false, name = name)
            val inCovered = labelPixels(covered)
            assertTrue("labels over dots at $value: $inCovered of $inEmpty", inCovered >= inEmpty * 0.8f)
        }
    }

    @Test
    fun withoutAForecastNoDashedLineAndTheGraphEndsAtNow() {
        val layout = FaceLayout.compute(size, size)
        val off = paint(scenario(::calm, 0.3f, forecast = null), ambient = false, name = "7e_no_forecast")
        assertEquals(0, count(off, layout.graph, forecastColor))
        val on = paint(inRange(), ambient = false)
        assertTrue(count(on, layout.graph, forecastColor) > 10)
        paint(scenario(::calm, 0.3f, forecast = { 6.4f }, hours = 6), ambient = false, name = "7e_six_hours")
    }

    @Test
    fun theDimModeShowsEverythingDimmedWithARegularNumberAndLightsFewerPixels() {
        val layout = FaceLayout.compute(size, size)
        val normal = paint(highForecastDown(), ambient = false)
        val ambient = paint(highForecastDown(), ambient = true, name = "7e_ambient")
        assertTrue(lit(ambient) < lit(normal))
        // the number regular, not bold: fewer of its pixels lit (part 7c, critic 7, S2)
        assertTrue(lit(ambient, layout.value) < lit(normal, layout.value) * 0.9f)
        assertEquals(FaceStyle.BLACK, ambient.getPixel(1, 1))
        // part 7l, the owner: the forecast in the dim mode too, dimmed as the rest, in the right fifth only
        val dimForecast = FaceStyle.of(true).forecast!!
        val rightFifth = (layout.graph.left + 0.79f * layout.graph.width).toInt()
        assertEquals(0, count(ambient, layout.graph, forecastColor))
        assertTrue(count(ambient, layout.graph, dimForecast, rightFifth) > 10)
        assertEquals(count(ambient, layout.graph, dimForecast, rightFifth), count(ambient, layout.graph, dimForecast))
        // the arrow and the change in the number's yellow, dimmed by AMBIENT_ARROW_DIM; nothing red (part 7l)
        val dimYellow = FaceStyle.dim(yellow, FaceStyle.AMBIENT_ARROW_DIM)
        assertTrue(count(ambient, layout.value, dimYellow, arrowX(highForecastDown(), layout, bold = false)) > 20)
        assertEquals(0, count(ambient, layout.value, FaceStyle.dim(red, FaceStyle.AMBIENT_ARROW_DIM)))
        // part 7l: the hour labels under the graph and the range labels at its left end, dimmed
        val dim = FaceStyle.of(true)
        assertEquals(0, count(ambient, layout.hours, FaceStyle.of(false).hourLabel!!))
        assertTrue(count(ambient, layout.hours, dim.hourLabel!!) > 10)
        val box = FaceLayout.Row(layout.graph.left, layout.graph.top, layout.graph.left + 0.12f * size,
            layout.graph.bottom, 0f)
        assertTrue(count(ambient, box, dim.secondary, box.left.toInt(), box.right.toInt()) > 10)
        // the date stays at the bottom
        assertTrue(lit(ambient, layout.status) > 20)
    }

    @Test
    fun theDimModeStepMovesTheWholePictureAndKeepsItInside() {
        val step = FaceLayout.burnInOffset(1L, size.toFloat(), strong = true)
        assertTrue(step.first > 0f && step.second == 0f)
        val still = paint(highForecastDown(), ambient = true)
        val moved = paint(highForecastDown(), ambient = true, offset = step, name = "7e_ambient_moved")
        val dx = step.first.toInt()
        // a column of the number: lit pixels move right by the step
        val y0 = FaceLayout.compute(size, size).value.top.toInt()
        val y1 = FaceLayout.compute(size, size).value.bottom.toInt()
        var same = 0
        var total = 0
        for (y in y0 until y1) for (x in 0 until size - dx) {
            if (still.getPixel(x, y) and 0xFFFFFF != 0) {
                total++
                if (moved.getPixel(x + dx, y) == still.getPixel(x, y)) same++
            }
        }
        assertTrue("$same of $total", total > 100 && same >= total * 0.9f)
        assertEquals(0, litOutside(moved, 0f))
    }

    @Test
    fun batteriesYellowUnderHalfRedUnder30AndAlsoInTheDimMode() {
        val layout = FaceLayout.compute(size, size)
        val style = FaceStyle.of(false)
        val half = size / 2
        // the owner's word of part 7c: the phone at 45 % yellow, the watch at 18 % red
        val lowBatteries = inRange().copy(phoneBatteryPercent = 45, watchBatteryPercent = 18)
        val bitmap = paint(lowBatteries, ambient = false, name = "7e_low_batteries")
        assertTrue("phone yellow", count(bitmap, layout.status, style.batteryWarn, 0, half) > 10)
        assertEquals(0, count(bitmap, layout.status, style.batteryLow, 0, half))
        assertTrue("watch red", count(bitmap, layout.status, style.batteryLow, half) > 10)
        assertEquals(0, count(bitmap, layout.status, style.batteryWarn, half))
        // 64 and 81: neither
        val fine = paint(inRange(), ambient = false)
        assertEquals(0, count(fine, layout.status, style.batteryLow) + count(fine, layout.status, style.batteryWarn))
        // the dim mode (part 7j, owner's request: batteries always): both, dimmed, in their colours
        val dim = FaceStyle.of(true)
        val ambient = paint(lowBatteries, ambient = true, name = "7j_low_batteries_ambient")
        assertTrue("dim watch red", count(ambient, layout.status, dim.batteryLow, half) > 10)
        assertTrue("dim phone yellow", count(ambient, layout.status, dim.batteryWarn, 0, half) > 10)
        // fine batteries are drawn in the dim mode too: more lit than with none known
        val dimNormal = paint(inRange(), ambient = true, name = "7j_batteries_ambient")
        val noBatteries = paint(inRange().copy(phoneBatteryPercent = null, watchBatteryPercent = null), ambient = true)
        assertTrue(lit(noBatteries, layout.status) < lit(dimNormal, layout.status))
        // unknown: no icon in the normal mode either
        val unknown = paint(inRange().copy(phoneBatteryPercent = null, watchBatteryPercent = null), ambient = false)
        assertTrue(lit(unknown, layout.status) < lit(fine, layout.status))
    }

    @Test
    fun theSystemDotFallsBetweenTheDayAndTheMonthAndFullBatteriesStayInside() {
        // part 7e: Wear OS draws the faint dot of upstream's ongoing activity at the centre, ~0.86 of the diameter;
        // the date's gap is there, so no glyph is under it - also with both batteries at 100 (the widest row)
        for (snapshot in listOf(inRange(), inRange().copy(phoneBatteryPercent = 100, watchBatteryPercent = 100))) {
            val bitmap = paint(snapshot, ambient = false)
            val cx = size / 2
            val cy = (0.86f * size).toInt()
            for (y in cy - 3..cy + 3) for (x in cx - 2..cx + 2) {
                assertEquals("pixel $x,$y under the dot", FaceStyle.BLACK, bitmap.getPixel(x, y))
            }
            // the date's words on both sides of it, on the row's middle line
            val mid = FaceLayout.compute(size, size).status.centerY.toInt()
            val litLeft = (cx - 40 until cx - 3).count { bitmap.getPixel(it, mid) and 0xFFFFFF != 0 }
            val litRight = (cx + 3 until cx + 40).count { bitmap.getPixel(it, mid) and 0xFFFFFF != 0 }
            assertTrue("day $litLeft, month $litRight", litLeft > 0 && litRight > 0)
            assertEquals(0, litOutside(bitmap, 0.01f * size))
        }
        paint(inRange().copy(phoneBatteryPercent = 100, watchBatteryPercent = 100), ambient = false,
            name = "7e_full_batteries")
    }

    @Test
    fun theTrialMarkOnlyForATrialUnderTheStatusRow() {
        val layout = FaceLayout.compute(size, size)
        val trial = paint(inRange(), ambient = false, trial = true, name = "7e_trial")
        assertTrue(lit(trial, layout.mark) > 20)
        assertEquals(0, litOutside(trial, 0.01f * size))
    }

    @Test
    fun oldGoneAndNoDataStillPaint() {
        val layout = FaceLayout.compute(size, size)
        val old = paint(scenario(::calm, 0.3f, forecast = { 6.4f }, ageMin = 15), ambient = false, name = "7e_old")
        assertEquals(0, count(old, layout.graph, forecastColor))
        val gone = paint(scenario(::calm, 0.3f, forecast = null, ageMin = 25), ambient = false)
        assertTrue(lit(gone, layout.value) > 50)
        val none = paint(FaceSnapshot(now, true, true, 3, null, emptyList(), emptyList(), 3.9f, 10f, null, null),
            ambient = true, name = "7e_no_data")
        assertTrue(lit(none, layout.value) > 10)
    }

    // ------------------------------------------------------------------ 20.11: seconds and the pump's active insulin

    /** [snapshot] with its reading [ageSec] old (history stays a minute apart before it). */
    private fun aged(snapshot: FaceSnapshot, ageSec: Int): FaceSnapshot =
        snapshot.copy(current = snapshot.current!!.copy(timeMs = now - ageSec * 1000L))

    private fun iob(tenths: Int, ageMin: Int) = FacePumpIob(tenths, now - ageMin * minute)

    /** 6,2 · 45 с · 2,3 ЕД. */
    private fun usual(iob: FacePumpIob? = iob(23, 3)) = aged(inRange(), 45).copy(pumpIob = iob)

    /** 11,7 · 12 мин (red) · 12,5 ЕД: the widest value row of the critics. */
    private fun hard() = scenario(
        curve = { back -> if (back > 60) calm(back) else calm(60) + (11.7f - calm(60)) * (1f - (back - 12) / 48f) },
        rate = 1.2f,
        forecast = null,
        ageMin = 12,
    ).copy(pumpIob = iob(125, 4))

    private val iobFresh = FaceStyle.of(false).iobFresh
    private val iobStale = FaceStyle.of(false).iobStale

    @Test
    fun theActiveInsulinFreshInLightBlueAsTheThirdRow() {
        val layout = FaceLayout.compute(size, size)
        val bitmap = paint(usual(), ambient = false, name = "9_usual")
        assertTrue(count(bitmap, layout.value, iobFresh) > 10)
        assertEquals(0, count(bitmap, layout.value, iobStale))
        // only beside the number, nowhere else
        assertEquals(0, count(bitmap, layout.graph, iobFresh) + count(bitmap, layout.time, iobFresh))
        assertEquals(0, litOutside(bitmap, 0.01f * size))
        // the number keeps its pixels: as many green ones as without the active insulin (not shrunk)
        val without = paint(usual(iob = null), ambient = false, name = "9_no_iob")
        assertEquals(0, count(without, layout.value, iobFresh) + count(without, layout.value, iobStale))
        // critic 9, L4: without the active insulin the age as before 20.11 (grey); pale only in the three rows
        val style = FaceStyle.of(false)
        assertTrue(count(without, layout.value, style.age) > 10)
        assertEquals(0, count(without, layout.value, style.agePale))
        assertTrue(count(bitmap, layout.value, style.agePale) > 10)
        assertEquals(0, count(bitmap, layout.value, style.age))
        assertTrue(count(bitmap, layout.value, green) >= count(without, layout.value, green) * 0.95f)
    }

    @Test
    fun theHardCaseKeepsTheNumberAndTheRedAgeInside() {
        val layout = FaceLayout.compute(size, size)
        val bitmap = paint(hard(), ambient = false, name = "9_hard")
        assertTrue("red age", count(bitmap, layout.value, FaceStyle.of(false).ageAlarm) > 10)
        assertTrue("active insulin", count(bitmap, layout.value, iobFresh) > 10)
        assertTrue("number", count(bitmap, layout.value, yellow) > 200)
        assertEquals(0, litOutside(bitmap, 0.01f * size))
        // the number is not shrunk by the third row (critics, 20.11)
        val model = FaceModel.build(hard(), zone, labels())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Typeface.DEFAULT_BOLD
        p.textSize = layout.value.textSize
        val valueWidth = p.measureText(model.valueText)
        p.textSize = layout.sideTextIob
        val delta = p.measureText(model.deltaText ?: "")
        val age = p.measureText(model.ageText!!)
        p.typeface = Typeface.DEFAULT
        p.textSize = layout.sideTextIob * FaceLayout.AGE_SMALL
        val iobWidth = p.measureText(model.iobText!!)
        val place = FaceLayout.placeValueRow(layout.value, valueWidth, layout.arrowSize, maxOf(delta, age, iobWidth),
            layout.radius * 0.04f)
        assertEquals(1f, place.scale)
    }

    @Test
    fun aStaleActiveInsulinInDimBlueAndAnUnknownOneAsADash() {
        val layout = FaceLayout.compute(size, size)
        val stale = paint(usual(iob(23, 20)), ambient = false, name = "9_iob_stale")
        assertTrue(count(stale, layout.value, iobStale) > 10)
        assertEquals(0, count(stale, layout.value, iobFresh))
        val unknown = paint(usual(iob(23, 40)), ambient = false, name = "9_iob_unknown")
        assertTrue(count(unknown, layout.value, iobStale) > 5)
        // "— ЕД" is narrower than "2,3 ЕД": fewer blue pixels
        assertTrue(count(unknown, layout.value, iobStale) < count(stale, layout.value, iobStale))
        val over = paint(usual(iob(1234, 2)), ambient = false, name = "9_iob_99plus")
        assertTrue(count(over, layout.value, iobFresh) > 10)
        assertEquals(0, litOutside(over, 0.01f * size))
    }

    @Test
    fun theDimModeShowsMinutesAndTheActiveInsulinDimmed() {
        val layout = FaceLayout.compute(size, size)
        val ambient = paint(usual(), ambient = true, name = "9_ambient")
        assertEquals("0 мин", FaceModel.build(usual(), zone, labels(), ambient = true).ageText)
        assertEquals("45 с", FaceModel.build(usual(), zone, labels()).ageText)
        assertEquals(0, count(ambient, layout.value, iobFresh))
        assertTrue(count(ambient, layout.value, FaceStyle.of(true).iobFresh) > 10)
    }

    @Test
    fun anAgeOver99MinutesIsWrittenInHours() {
        val layout = FaceLayout.compute(size, size)
        val old = scenario(::calm, 0.3f, forecast = null, ageMin = 100).copy(pumpIob = iob(23, 100))
        assertEquals("1 ч", FaceModel.build(old, zone, labels()).ageText)
        val bitmap = paint(old, ambient = false, name = "9_age_hours")
        assertTrue(count(bitmap, layout.value, FaceStyle.of(false).ageAlarm) > 10)
        assertEquals(0, litOutside(bitmap, 0.01f * size))
    }

    companion object {
        /** The face list's picture (`drawable-nodpi/mod_watch_face_preview.png`), made by hand from this test. */
        const val FACE_LIST_PREVIEW = "mod_watch_face_preview"
    }
}
