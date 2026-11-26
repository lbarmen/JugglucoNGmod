package mod.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        assertEquals("+0,2 · 2 min", ModAodBlock.subLine("+0,2", "2 min"))
        assertEquals("2 min", ModAodBlock.subLine("", "2 min"))
        assertNull(ModAodBlock.subLine("", ""))
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
