package mod.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.aod.ModAodPlot
import tk.glucodata.mod.aod.ModAodPoint
import java.time.ZoneId
import java.time.ZonedDateTime

/** JugglucoNGmod phone AOD (part 1): the chart's rules without drawing. */
class ModAodPlotTest {

    private val min = 60_000L
    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 11, 26, 13, 7, 20, 0, zone).toInstant().toEpochMilli()
    private val bucket = ModAodPlot.BUCKET_MS

    @Test
    fun oneDotPerFiveClockMinutesAtTheBucketsMiddle() {
        val start = Math.floorDiv(now, bucket) * bucket - 2 * bucket // 12:55: two buckets before the one now is in
        val points = listOf(
            ModAodPoint(start + 0 * min, 5f), ModAodPoint(start + 2 * min, 6f), ModAodPoint(start + 4 * min, 7f),
            ModAodPoint(start + 5 * min, 8f), ModAodPoint(start + 9 * min, 10f),
        )
        val dots = ModAodPlot.fiveMinuteMeans(points)
        assertEquals(2, dots.size)
        assertEquals(start + bucket / 2, dots[0].timeMs)
        assertEquals(6f, dots[0].value, 1e-4f)
        // the newest bucket is still filling: its dot at the bucket's middle, not later than its newest reading
        assertEquals(start + bucket + bucket / 2, dots[1].timeMs)
        assertEquals(9f, dots[1].value, 1e-4f)
        val filling = ModAodPlot.fiveMinuteMeans(listOf(ModAodPoint(start + 5 * min, 8f), ModAodPoint(start + 6 * min, 8f)))
        assertEquals(start + 6 * min, filling.single().timeMs)
    }

    @Test
    fun aSensorGapHasNoDotAndBadValuesAreLeftOut() {
        val start = Math.floorDiv(now, bucket) * bucket - 6 * bucket
        val points = listOf(
            ModAodPoint(start, 6f), ModAodPoint(start + 5 * bucket, 7f),
            ModAodPoint(start + 5 * bucket + min, Float.NaN), ModAodPoint(start + 5 * bucket + 2 * min, 0.05f),
        )
        val dots = ModAodPlot.fiveMinuteMeans(points)
        assertEquals(listOf(6f, 7f), dots.map { it.value })
    }

    @Test
    fun theTargetLinesAreAlwaysOnTheScaleAndACalmTraceIsNotMountains() {
        val calm = (0..60).map { ModAodPoint(now - (60 - it) * min, 6.0f + (it % 3) * 0.05f) }
        val p = ModAodPlot.plot(calm, now, 3.9f, 10f, isMmol = true)
        assertTrue(p.bottom < 3.9f && p.top > 10f)
        val far = (0..60).map { ModAodPoint(now - (60 - it) * min, 18f) }
        val q = ModAodPlot.plot(far, now, 3.9f, 10f, isMmol = true)
        assertTrue(q.top > 18f && q.bottom < 3.9f)
        // mg/dL: a narrow range still spans at least upstream's 54 mg/dL
        val r = ModAodPlot.plot(calm.map { it.copy(value = 120f) }, now, 110f, 130f, isMmol = false)
        assertTrue(r.top - r.bottom >= 54f)
        assertTrue(p.bottom >= 0f)
    }

    @Test
    fun theWindowIsThreeHoursAndTheForecastTakesAtMostAFifthOfTheWidth() {
        val points = (0..240).map { ModAodPoint(now - (240 - it) * min, 7f) }
        val longForecast = (1..24).map { ModAodPoint(now + it * 5 * min, 7f + it * 0.1f) } // 2 hours ahead
        val p = ModAodPlot.plot(points, now, 3.9f, 10f, isMmol = true, forecast = longForecast)
        assertEquals(now - ModAodPlot.WINDOW_MS, p.fromMs)
        assertTrue(p.dots.all { it.timeMs >= p.fromMs })
        assertEquals(now + (ModAodPlot.WINDOW_MS * ModAodPlot.FORECAST_SHARE).toLong(), p.toMs)
        assertTrue(p.forecast.all { it.timeMs in (now + 1)..p.toMs })
        assertEquals(0.8f, ModAodPlot.xFraction(p, now), 1e-4f)
        val none = ModAodPlot.plot(points, now, 3.9f, 10f, isMmol = true)
        assertEquals(now, none.toMs)
        assertEquals(1f, ModAodPlot.xFraction(none, now), 1e-4f)
        assertEquals(points.last(), none.lastReading)
    }

    @Test
    fun hourLinesAtFullLocalHours() {
        val marks = ModAodPlot.hourMarks(now - ModAodPlot.WINDOW_MS, now, zone, hour24 = true)
        assertEquals(listOf("11", "12", "13"), marks.map { it.label })
        val pm = ModAodPlot.hourMarks(now - ModAodPlot.WINDOW_MS, now, zone, hour24 = false)
        assertEquals(listOf("11", "12", "1"), pm.map { it.label })
        assertTrue(ModAodPlot.hourMarks(now, now, zone, true).isEmpty())
    }
}
