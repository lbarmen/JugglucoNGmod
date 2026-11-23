package mod.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.GlucosePoint
import tk.glucodata.chart.HistoryChartModelBuilder
import tk.glucodata.chart.MainSensorOwnership
import tk.glucodata.mod.widget.ModWidgetData
import tk.glucodata.mod.widget.ModWidgetFrame
import tk.glucodata.mod.widget.ModWidgetFreshness
import tk.glucodata.mod.widget.ModWidgetInput
import tk.glucodata.mod.widget.ModWidgetMath
import tk.glucodata.mod.widget.ModWidgetPoint
import tk.glucodata.mod.widget.ModWidgetRange
import tk.glucodata.mod.widget.ModWidgetSettings
import tk.glucodata.mod.widget.ModWidgetTexts

/** JugglucoNGmod widget: what one picture shows, decided from made-up readings. */
class ModWidgetFrameTest {

    private val min = ModWidgetMath.MINUTE_MS
    private val t0 = 1_000_000L * ModWidgetMath.FIVE_MIN_MS
    private val timeout = 330_000L
    private val range = ModWidgetRange(3.9f, 10f, 3.0f, 13.9f, true)

    private val texts = object : ModWidgetTexts {
        override fun justNow() = "just now"
        override fun minutesAgo(minutes: Int) = "$minutes min ago"
        override fun stale(readingTime: Long) = "No new value since T"
        override fun noValue() = "No value"
        override fun unit(isMmol: Boolean) = if (isMmol) "mmol/L" else "mg/dL"
        override fun trend(trend: ModWidgetMath.Trend) = trend.name.lowercase()
        override fun example() = "Example"
    }

    /** Ten minutes of made-up readings, +0.1 a minute, the last one at [last]. */
    private fun input(last: Float, readingTime: Long, rate: Float = 0.7f): ModWidgetInput {
        val points = (0..10).map { ModWidgetPoint(readingTime - (10 - it) * min, last - (10 - it) * 0.1f) }
        return ModWidgetInput(true, String.format(java.util.Locale.US, "%.1f", last), last, rate, readingTime, points, range)
    }

    @Test
    fun freshReadingShowsColorArrowDeltaAndAge() {
        val f = ModWidgetFrame.build(input(6.2f, t0), ModWidgetSettings(), t0 + 2 * min + 5_000L, timeout, texts)
        assertEquals(ModWidgetFreshness.FRESH, f.freshness)
        assertEquals("6.2", f.valueText)
        assertEquals(range.color(6.2f), f.valueColor)
        assertFalse(f.valueStruck)
        assertTrue(f.rate.isFinite())
        assertTrue(f.deltaText!!.endsWith(" mmol/L"))
        assertTrue(f.deltaText!!.startsWith("+0"))
        assertEquals("2 min ago", f.statusText)
        assertTrue(f.description.contains("6.2 mmol/L"))
        assertTrue(f.description.contains("rising_slowly"))
        assertNull(f.exampleLabel)
    }

    @Test
    fun lowInRangeAndHighHaveDifferentColorsAndColorOffIsWhite() {
        val now = t0 + 10_000L
        val low = ModWidgetFrame.build(input(3.2f, t0), ModWidgetSettings(), now, timeout, texts).valueColor
        val mid = ModWidgetFrame.build(input(6.0f, t0), ModWidgetSettings(), now, timeout, texts).valueColor
        val high = ModWidgetFrame.build(input(12.5f, t0), ModWidgetSettings(), now, timeout, texts).valueColor
        assertNotEquals(low, mid)
        assertNotEquals(high, mid)
        assertNotEquals(low, high)
        val off = ModWidgetFrame.build(input(12.5f, t0), ModWidgetSettings(colorByRange = false), now, timeout, texts)
        assertEquals(ModWidgetFrame.WHITE, off.valueColor)
    }

    @Test
    fun justNowAndOptionsOff() {
        val f = ModWidgetFrame.build(input(6.2f, t0), ModWidgetSettings(showDelta = false, showAge = false), t0 + 20_000L, timeout, texts)
        assertNull(f.deltaText)
        assertNull(f.statusText)
        assertTrue(f.description.contains("just now"))
    }

    @Test
    fun staleIsGreyStruckWithoutArrowOrDelta() {
        val f = ModWidgetFrame.build(input(6.2f, t0), ModWidgetSettings(), t0 + timeout + 1, timeout, texts)
        assertEquals(ModWidgetFreshness.STALE, f.freshness)
        assertEquals(ModWidgetFrame.GREY, f.valueColor)
        assertTrue(f.valueStruck)
        assertTrue(f.rate.isNaN())
        assertNull(f.deltaText)
        assertEquals("No new value since T", f.statusText)
        // the dots stay: the history is still true
        assertTrue(f.points.isNotEmpty())
    }

    @Test
    fun noSensorOrNoValueShowsNoValue() {
        val none = ModWidgetFrame.build(ModWidgetInput.EMPTY_MMOL, ModWidgetSettings(), t0, timeout, texts)
        assertEquals(ModWidgetFreshness.NONE, none.freshness)
        assertNull(none.valueText)
        assertEquals("No value", none.noValueText)
        val noText = ModWidgetFrame.build(input(6.2f, t0).copy(valueText = null), ModWidgetSettings(), t0, timeout, texts)
        assertEquals(ModWidgetFreshness.NONE, noText.freshness)
    }

    @Test
    fun aReadingFromTheFutureIsNotJustNow() {
        val f = ModWidgetFrame.build(input(6.2f, t0 + 5 * min), ModWidgetSettings(), t0, timeout, texts)
        assertEquals(ModWidgetFreshness.NONE, f.freshness)
    }

    @Test
    fun exampleIsLabelled() {
        val ex = ModWidgetInput.example(t0, range)
        val f = ModWidgetFrame.build(ex, ModWidgetSettings(), t0, timeout, texts)
        assertEquals("Example", f.exampleLabel)
        assertEquals(ModWidgetFreshness.FRESH, f.freshness)
        // 3 h of one reading a minute: 36 five-minute dots, 37 when the period starts inside a five-minute slot
        assertTrue(f.points.size in 36..37)
    }

    // ---------- one value for everything, per view mode ----------

    /**
     * Upstream's chart builder with a made-up calibration (+1.0 on auto, +2.0 on raw) over made-up readings whose auto
     * and raw values differ: the widget's dots and delta must take the lane and calibration of the view mode - the
     * same rule as the number - and never mix the two.
     */
    private fun resolved(viewMode: Int, calibrated: Boolean): List<ModWidgetPoint> {
        val points = (0..10).map { i ->
            GlucosePoint(t0 + i * min, 6f + i * 0.1f, 8f + i * 0.2f).also { it.sensorSerial = "SIBI:TEST" }
        }
        val input = HistoryChartModelBuilder.SeriesInput("SIBI:TEST", true, viewMode, 0, points)
        val calibration = HistoryChartModelBuilder.Calibration { base, _, isRaw, _ ->
            if (!calibrated) null else if (isRaw) base + 2f else base + 1f
        }
        val model = HistoryChartModelBuilder.build(listOf(input), MainSensorOwnership.NONE, calibration, hasCalibration = calibrated)
        return ModWidgetData.primaryPoints(model)
    }

    @Test
    fun dotsAndDeltaFollowTheViewModeAndCalibration() {
        // 0 auto, 2 auto + raw beside: the auto lane
        for (mode in listOf(0, 2)) {
            val pts = resolved(mode, calibrated = false)
            assertEquals(11, pts.size)
            assertEquals(6f, pts.first().value, 1e-4f)
            assertEquals(0.5f, ModWidgetMath.fiveMinuteDelta(pts), 1e-4f)
        }
        // 1 raw, 3 raw + auto beside: the raw lane
        for (mode in listOf(1, 3)) {
            val pts = resolved(mode, calibrated = false)
            assertEquals(8f, pts.first().value, 1e-4f)
            assertEquals(1.0f, ModWidgetMath.fiveMinuteDelta(pts), 1e-4f)
        }
        // a calibration applies to the lane of the mode
        assertEquals(7f, resolved(0, calibrated = true).first().value, 1e-4f)
        assertEquals(10f, resolved(1, calibrated = true).first().value, 1e-4f)
    }

    @Test
    fun aRecordedValueWinsAsInUpstreamsChart() {
        val p = GlucosePoint(t0, 6f, 8f).also { it.sealedDisplayValue = 7.5f; it.sealedDisplayViewMode = 0 }
        val input = HistoryChartModelBuilder.SeriesInput("SIBI:TEST", true, 1, 0, listOf(p))
        val model = HistoryChartModelBuilder.build(listOf(input), MainSensorOwnership.NONE, { _, _, _, _ -> null })
        assertEquals(7.5f, ModWidgetData.primaryPoints(model).single().value, 1e-4f)
    }

    @Test
    fun theShownNumberIsTheNewestDotWhenTheChartEndsEarlier() {
        val chart = listOf(ModWidgetPoint(t0, 6f), ModWidgetPoint(t0 + min, 6.1f))
        val withNumber = ModWidgetData.withShownReading(chart, t0 + 4 * min, 6.4f)
        assertEquals(ModWidgetPoint(t0 + 4 * min, 6.4f), withNumber.last())
        assertEquals(chart, ModWidgetData.withShownReading(chart, t0 + min + 30_000L, 6.4f))
        assertEquals(chart, ModWidgetData.withShownReading(chart, t0 + 4 * min, Float.NaN))
    }

    @Test
    fun theDeltaComesFromTheDeltaReadingsAndOnlyForTheShownReading() {
        val base = input(6.2f, t0)
        val lagging = base.copy(deltaPoints = base.points.dropLast(3))   // history 3 min behind the number
        val f = ModWidgetFrame.build(lagging, ModWidgetSettings(), t0 + 10_000L, timeout, texts)
        assertNull(f.deltaText)
        val raw = base.copy(deltaPoints = base.points.map { ModWidgetPoint(it.time, it.value * 2f) })
        val g = ModWidgetFrame.build(raw, ModWidgetSettings(), t0 + 10_000L, timeout, texts)
        assertTrue(g.deltaText!!, Regex("^[+]1[.,]0 ").containsMatchIn(g.deltaText!!))
    }

    @Test
    fun noModelNoPoints() {
        assertTrue(ModWidgetData.primaryPoints(null).isEmpty())
    }
}
