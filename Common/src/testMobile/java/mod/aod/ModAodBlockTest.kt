package mod.aod

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.NotificationPredictionSeries
import tk.glucodata.mod.aod.ModAodBlock
import tk.glucodata.mod.aod.ModAodPoint
import tk.glucodata.mod.aod.ModAodStyle

/** JugglucoNGmod phone AOD (part 1): the block's small rules without Android. */
class ModAodBlockTest {

    private val auto = listOf(ModAodPoint(1L, 1f))
    private val raw = listOf(ModAodPoint(1L, 2f))
    private val calibrated = listOf(ModAodPoint(1L, 3f))

    @Test
    fun theForecastOfTheMainReadingAsUpstreamsChartPicksIt() {
        val all = listOf(NotificationPredictionSeries.KIND_RAW to raw, NotificationPredictionSeries.KIND_AUTO to auto)
        assertEquals(auto, ModAodBlock.primaryForecast(all, 0))
        assertEquals(auto, ModAodBlock.primaryForecast(all, 2))
        assertEquals(raw, ModAodBlock.primaryForecast(all, 1))
        assertEquals(raw, ModAodBlock.primaryForecast(all, 3))
        val withCalibration = all + (NotificationPredictionSeries.KIND_CALIBRATED to calibrated)
        assertEquals(calibrated, ModAodBlock.primaryForecast(withCalibration, 0))
        assertEquals(emptyList<ModAodPoint>(), ModAodBlock.primaryForecast(emptyList(), 0))
    }

    @Test
    fun theNumberWithoutUpstreamsSecondaryValuesAndTheSmallLine() {
        assertEquals("8,1", ModAodBlock.numberText("8,1 · 7,9 · 8,3"))
        assertEquals("8,1", ModAodBlock.numberText("8,1"))
        assertNull(ModAodBlock.numberText(" "))
        assertEquals("+0,2 · 17:01", ModAodBlock.subLine("+0,2", "17:01"))
        assertEquals("17:01", ModAodBlock.subLine("", "17:01"))
        assertNull(ModAodBlock.subLine("", ""))
    }

    @Test
    fun theReadingsClockTimeInThePhonesFormatAndThePlainFormWhenAPatternLetterIsUnknown() {
        val zone = ZoneId.of("Europe/Moscow")
        val at = ZonedDateTime.of(2026, 11, 26, 17, 1, 30, 0, zone).toInstant().toEpochMilli()
        val morning = ZonedDateTime.of(2026, 11, 26, 7, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("17:01", ModAodBlock.readingTime(at, zone, "HH:mm", Locale.US))
        assertEquals("07:05", ModAodBlock.readingTime(morning, zone, "HH:mm", Locale.US))
        assertEquals("5:01 PM", ModAodBlock.readingTime(at, zone, "h:mm a", Locale.US))
        // patterns Android gives for other locales (Canadian French, Finnish-style dots)
        assertEquals("17 h 01", ModAodBlock.readingTime(at, zone, "HH 'h' mm", Locale.CANADA_FRENCH))
        assertEquals("17.01", ModAodBlock.readingTime(at, zone, "H.mm", Locale.US))
        // 'b' is not a java.time pattern letter: the plain 24- or 12-hour form instead
        assertEquals("17:01", ModAodBlock.readingTime(at, zone, "HH:mm b", Locale.US))
        assertEquals("5:01 PM", ModAodBlock.readingTime(at, zone, "h:mm b", Locale.US))
    }

    private fun key(minute: Long = 29_000_000L, shownTime: Long = 1_740_000_000_000L) = ModAodBlock.DrawKey(
        light = true, widthPx = 1080, density = 2.625f, scaledDensity = 2.625f, textScale = 1.5f, chartScale = 1.5f,
        showChart = true, showArrow = true, isMmol = true, range = ModAodBlock.Range(3.9f, 10f, 3.0f, 13.9f),
        deltaInterval = 5, viewMode = 0, hasCalibration = false, sensorId = "S", valueText = "6,0", rate = Float.NaN,
        stale = null, shownTime = shownTime, historySize = 36, historyLastTime = shownTime, historyLastValue = 6f,
        deltaLastTime = shownTime, dataRevision = 7L, minute = minute, hour24 = true, locale = Locale.US,
        zone = ZoneId.of("UTC"),
    )

    @Test
    fun everyInputOfTheKeyForcesARedraw() {
        val base = key()
        val changed = listOf(
            base.copy(light = false), base.copy(widthPx = 1440), base.copy(density = 3.75f),
            base.copy(scaledDensity = 3f), base.copy(textScale = 2f), base.copy(chartScale = 1f),
            base.copy(showChart = false), base.copy(showArrow = false), base.copy(isMmol = false),
            base.copy(range = ModAodBlock.Range(4f, 10f, 3.0f, 13.9f)), base.copy(deltaInterval = 15),
            base.copy(viewMode = 1), base.copy(hasCalibration = true), base.copy(sensorId = "T"),
            base.copy(valueText = "6,1"), base.copy(rate = 0.1f), base.copy(stale = "No new value since 17:01"),
            base.copy(shownTime = base.shownTime + 60_000L), base.copy(historySize = 37),
            base.copy(historyLastTime = base.historyLastTime + 60_000L), base.copy(historyLastValue = 6.1f),
            base.copy(deltaLastTime = base.deltaLastTime + 60_000L), base.copy(dataRevision = 8L),
            base.copy(minute = base.minute + 1), base.copy(hour24 = false), base.copy(locale = Locale.GERMANY),
            base.copy(zone = ZoneId.of("Europe/Moscow")),
        )
        for (next in changed) assertFalse(next.toString(), ModAodBlock.sameDrawing(base, next, true))
    }

    @Test
    fun aRefreshIsSkippedOnlyWhenItWouldDrawWhatTheViewStillShows() {
        // the same key twice, NaN rates included, and the view still holds our block
        assertTrue(ModAodBlock.sameDrawing(key(), key(), true))
        assertFalse(ModAodBlock.sameDrawing(key(), key(minute = 29_000_001L), true))
        assertFalse(ModAodBlock.sameDrawing(key(), key(shownTime = 1_740_000_060_000L), true))
        assertFalse(ModAodBlock.sameDrawing(key(), key(), false))
        assertFalse(ModAodBlock.sameDrawing(null, key(), true))
    }

    @Test
    fun theShownNumberIsTheNewestDotOnlyWhenTheHistoryLags() {
        val history = listOf(ModAodPoint(0L, 6f), ModAodPoint(60_000L, 6.1f))
        assertEquals(history, ModAodBlock.withShownReading(history, 90_000L, 6.2f))
        assertEquals(history + ModAodPoint(240_000L, 6.4f), ModAodBlock.withShownReading(history, 240_000L, 6.4f))
        assertEquals(history, ModAodBlock.withShownReading(history, 240_000L, Float.NaN))
        assertEquals(history, ModAodBlock.withShownReading(history, 0L, 6.4f))
    }

    @Test
    fun dotsAreWhiteInsideTheRangeAndInItsColourOutside() {
        val p = ModAodStyle.ON_BLACK
        val zone = { v: Float -> if (v > 10f) 0xFFD0A23A.toInt() else 0xFFC97970.toInt() }
        assertEquals(p.inRangeDot, ModAodStyle.dotColor(7f, 3.9f, 10f, p, zone))
        assertEquals(p.inRangeDot, ModAodStyle.dotColor(10f, 3.9f, 10f, p, zone))
        assertEquals(p.inRangeDot, ModAodStyle.dotColor(3.9f, 3.9f, 10f, p, zone))
        assertEquals(ModAodStyle.dim(0xFFD0A23A.toInt(), p.zoneDim), ModAodStyle.dotColor(10.1f, 3.9f, 10f, p, zone))
        assertEquals(ModAodStyle.dim(0xFFC97970.toInt(), p.zoneDim), ModAodStyle.dotColor(3.8f, 3.9f, 10f, p, zone))
        // the number and the in-range dots carry no colour of their own
        for (c in listOf(p.value, p.inRangeDot)) {
            assertEquals(c and 0xFF, (c ushr 8) and 0xFF)
            assertEquals(c and 0xFF, (c ushr 16) and 0xFF)
        }
    }
}
