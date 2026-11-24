package mod.watch.alarm

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Locale
import kotlin.math.hypot
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tk.glucodata.R
import tk.glucodata.mod.watch.alarm.AlarmContent
import tk.glucodata.mod.watch.alarm.AlarmGeometry
import tk.glucodata.mod.watch.alarm.AlarmScreenLayout
import tk.glucodata.mod.watch.alarm.AlarmUi
import tk.glucodata.mod.watch.face.ModFaceSource

/**
 * JugglucoNGmod, watch block (part 8): our alarm screen drawn into a 450 px bitmap (Galaxy Watch 5 Pro) with real
 * pixels (Robolectric native graphics), Russian texts as on the watch, upstream's default limits (3.9 / 10.0, very low
 * 3.0, very high 13.9 mmol/L) for the colours. Nothing outside the circle, both buttons inside it with room to the
 * edge and at least 56 dp tall, their words whole, the value in its colour, a long name on two lines.
 *
 * With MOD_ALARM_PREVIEW_DIR set, every scenario is also saved there as PNG with the round mask (outside black) -
 * to look at before installing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "ru")
class AlarmScreenPreviewTest {

    private val size = 450
    private val failures = mutableListOf<Throwable>()
    private lateinit var savedLocale: Locale

    @Before
    fun setUp() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.forLanguageTag("ru-RU"))
    }

    @After
    fun tearDown() = Locale.setDefault(savedLocale)

    private fun context(): Context = ApplicationProvider.getApplicationContext()

    private fun ui(label: String, value: String, rate: Float, color: Int, info: String?) = AlarmUi(
        label = label,
        value = value,
        rate = rate,
        valueColor = color,
        info = info,
        snoozeText = context().getString(R.string.snooze),
        dismissText = context().getString(R.string.notification_dismiss_action_dismiss),
    )

    private fun threshold(value: Float, isMmol: Boolean) =
        context().getString(R.string.mod_watch_alarm_threshold, AlarmContent.format(value, isMmol))

    private fun high() = ui(context().getString(R.string.alert_high), "14,2", 2f,
        ModFaceSource.trafficColor(14.2f, true), threshold(10f, true))

    private fun low() = ui(context().getString(R.string.alert_low), "3,1", -2f,
        ModFaceSource.trafficColor(3.1f, true), threshold(3.9f, true))

    private fun longName() = ui("Феномен рассвета после позднего ужина", "11,4", 1.2f,
        ModFaceSource.trafficColor(11.4f, true), null)

    private fun forecast() = ui(context().getString(R.string.alert_forecast_high), "9,6", 1.6f,
        ModFaceSource.trafficColor(9.6f, true), threshold(10f, true))

    private fun mgdl() = ui(context().getString(R.string.alert_very_high), "252", 3f,
        ModFaceSource.trafficColor(252f, false), threshold(250f, false))

    private fun noValue() = ui(context().getString(R.string.alert_loss), AlarmContent.NO_VALUE, Float.NaN,
        AlarmContent.NEUTRAL, null)

    private fun render(ui: AlarmUi, name: String?, view: AlarmScreenLayout = layout(ui)): Pair<Bitmap, AlarmScreenLayout> {
        view.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        shadowOf(Looper.getMainLooper()).idle()
        save(bitmap, name)
        return bitmap to view
    }

    private fun layout(ui: AlarmUi) = AlarmScreenLayout(context(), ui, {}, {}, { failures += it })

    private fun save(bitmap: Bitmap, name: String?) {
        val dir = System.getenv("MOD_ALARM_PREVIEW_DIR")?.takeIf { it.isNotBlank() } ?: return
        if (name == null) return
        File(dir).mkdirs()
        val out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val outside = Path().apply {
            addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW)
            fillType = Path.FillType.INVERSE_WINDING
        }
        Canvas(out).drawPath(outside, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
        File(dir, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun litOutside(bitmap: Bitmap, inset: Float): Int {
        var n = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (hypot(x + 0.5f - size / 2f, y + 0.5f - size / 2f) > size / 2f - inset &&
                bitmap.getPixel(x, y) and 0xFFFFFF != 0) n++
        }
        return n
    }

    private fun count(bitmap: Bitmap, color: Int, top: Int, bottom: Int): Int {
        var n = 0
        for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(bitmap.height)) for (x in 0 until bitmap.width) {
            if (bitmap.getPixel(x, y) == color) n++
        }
        return n
    }

    /** Both words whole at one readable size inside their buttons, and drawn there in the button's text colour. */
    private fun assertWholeButtons(view: AlarmScreenLayout, bitmap: Bitmap) {
        val f = AlarmGeometry.compute(size, size)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            textSize = view.buttonTextSize
        }
        assertTrue("button text ${view.buttonTextSize}", view.buttonTextSize >= AlarmGeometry.BUTTON_TEXT_MIN * size)
        for ((button, box) in listOf(view.snoozeButton to f.snooze, view.dismissButton to f.dismiss)) {
            assertTrue("${button.label} fits",
                paint.measureText(button.label) <= AlarmScreenLayout.buttonTextWidth(box, size.toFloat()))
            assertEquals(box.width.toInt(), button.width)
            assertTrue("${button.label} drawn", count(bitmap, button.textColor, box.top.toInt(), box.bottom.toInt()) > 50)
        }
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun theButtonsAreBigInsideTheCircleOneAboveTheOther() {
        val f = AlarmGeometry.compute(size, size)
        val r = size / 2f
        for (box in listOf(f.snooze, f.dismiss)) {
            // at least 56 dp on the 225 dp watch
            assertTrue("height ${box.height}", box.height >= 0.249f * size)
            val end = box.height / 2f
            val far = hypot(box.right - end - f.centerX, box.centerY - f.centerY) + end
            assertTrue("button reaches ${far} of $r", far <= r - AlarmGeometry.RIM * size + 0.5f)
            // wide: at least 60 % of the screen
            assertTrue("width ${box.width}", box.width >= 0.6f * size)
        }
        assertTrue(f.snooze.bottom < f.dismiss.top)
        // the text above the buttons, the name below the top of the circle
        assertTrue(f.info.bottom <= f.snooze.top)
        assertTrue(f.textTop > 0f)
    }

    @Test
    fun highDrawsInsideTheCircleWithTheValueInItsColourAndWholeWords() {
        val (bitmap, view) = render(high(), "8_high_mmol")
        assertTrue(failures.isEmpty())
        assertEquals(0, litOutside(bitmap, 0.01f * size))
        assertEquals(AlarmScreenLayout.BACKGROUND, bitmap.getPixel(2, 2))
        val f = AlarmGeometry.compute(size, size)
        // 14.2 is past very high (13.9): red, in the band between the name and the small line
        assertTrue(count(bitmap, ModFaceSource.trafficColor(14.2f, true), f.textTop.toInt(), f.info.top.toInt()) > 300)
        assertTrue(count(bitmap, AlarmScreenLayout.SNOOZE_FILL, f.snooze.top.toInt(), f.snooze.bottom.toInt()) > 5000)
        assertTrue(count(bitmap, AlarmScreenLayout.DISMISS_FILL, f.dismiss.top.toInt(), f.dismiss.bottom.toInt()) > 5000)
        assertWholeButtons(view, bitmap)
    }

    @Test
    fun lowAndTheOtherScenariosDrawToo() {
        for ((ui, name) in listOf(low() to "8_low_mmol", forecast() to "8_forecast_high", mgdl() to "8_very_high_mgdl",
            noValue() to "8_no_value")) {
            val (bitmap, view) = render(ui, name)
            assertTrue(name, failures.isEmpty())
            assertEquals(name, 0, litOutside(bitmap, 0.01f * size))
            assertWholeButtons(view, bitmap)
        }
        val f = AlarmGeometry.compute(size, size)
        val (low, _) = render(low(), null)
        // 3.1 is between very low (3.0) and low (3.9): yellow
        assertTrue(count(low, ModFaceSource.trafficColor(3.1f, true), f.textTop.toInt(), f.info.top.toInt()) > 300)
    }

    @Test
    fun aLongNameTakesTwoLinesAndStaysInside() {
        val (bitmap, view) = render(longName(), "8_long_name")
        assertTrue(failures.isEmpty())
        assertEquals(0, litOutside(bitmap, 0.01f * size))
        assertWholeButtons(view, bitmap)
        // the name's two lines come lower than one line would
        val f = AlarmGeometry.compute(size, size)
        val oneLine = f.textTop + AlarmGeometry.LABEL_TEXT * size * AlarmGeometry.LINE
        val twoLines = f.textTop + 2f * AlarmGeometry.LABEL_TEXT_MIN * size * AlarmGeometry.LINE
        assertTrue(twoLines > oneLine)
        assertTrue(count(bitmap, AlarmScreenLayout.LABEL_COLOR, oneLine.toInt() + 2, twoLines.toInt()) > 20)
    }

    @Test
    fun aDrawingFailureIsReportedOnceOnTheMainThread() {
        val broken = object : AlarmScreenLayout(context(), high(), {}, {}, { failures += it }) {
            override fun drawTexts(canvas: Canvas, f: AlarmGeometry.Frame) {
                throw IllegalStateException("test")
            }
        }
        render(high(), null, broken)
        render(high(), null, broken)
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IllegalStateException)
    }
}
