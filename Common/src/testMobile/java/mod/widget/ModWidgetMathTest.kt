package mod.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.GlucoseChartGap
import tk.glucodata.mod.widget.ModWidgetDots
import tk.glucodata.mod.widget.ModWidgetFreshness
import tk.glucodata.mod.widget.ModWidgetMath
import tk.glucodata.mod.widget.ModWidgetPoint
import tk.glucodata.mod.widget.ModWidgetSettings

/** JugglucoNGmod widget: the pure rules. Every value and time here is made up. */
class ModWidgetMathTest {

    private val min = ModWidgetMath.MINUTE_MS
    /** A made-up clock time on a five-minute boundary (1 000 000 five-minute slots after the epoch). */
    private val t0 = 1_000_000L * ModWidgetMath.FIVE_MIN_MS
    private val timeout = 330_000L

    private fun p(time: Long, value: Float) = ModWidgetPoint(time, value)

    // ---------- which readings count ----------

    @Test
    fun usableDropsNotNumbersTinyValuesAndTheFuture() {
        val now = t0 + 10 * min
        val input = listOf(
            p(t0, 5.0f), p(t0 + min, Float.NaN), p(t0 + 2 * min, 0.1f), p(t0 + 3 * min, -2f),
            p(t0 + 4 * min, Float.POSITIVE_INFINITY), p(t0 + 5 * min, 0.2f),
            p(now + 30_000L, 6.0f),          // half a minute ahead: kept
            p(now + 61_000L, 7.0f),          // more than a minute ahead: dropped
        )
        val out = ModWidgetMath.usable(input, now)
        assertEquals(listOf(t0, t0 + 5 * min, now + 30_000L), out.map { it.time })
    }

    @Test
    fun nearDuplicatesCountOnce() {
        // the stored and the live time of one reading, seconds apart: one reading, the later one
        val out = ModWidgetMath.usable(listOf(p(t0, 5f), p(t0 + 4_000L, 5.1f), p(t0 + min, 5.3f)), t0 + 10 * min)
        assertEquals(listOf(p(t0 + 4_000L, 5.1f), p(t0 + min, 5.3f)), out)
        // a chain closer than 30 s step by step is not merged into one
        val chain = ModWidgetMath.usable(listOf(p(t0, 5f), p(t0 + 25_000L, 5f), p(t0 + 50_000L, 5f), p(t0 + 75_000L, 5f)), t0 + 10 * min)
        assertEquals(listOf(t0 + 25_000L, t0 + 75_000L), chain.map { it.time })
    }

    @Test
    fun theFirstFiveMinutesOfThePeriodIsAveragedFromAllItsReadings() {
        val now = t0 + 60 * min + 2 * min   // the period starts 2 min into a five-minute slot
        val all = (0..62).map { p(t0 + it * min, if (it < 2) 10f else 5f) }
        val avg = ModWidgetMath.chartPoints(all, now, ModWidgetSettings(periodHours = 1))
        assertEquals((10f + 10f + 5f + 5f + 5f) / 5f, avg.first().value, 1e-4f)
    }

    @Test
    fun deltaOnlyForTheShownReading() {
        val points = (0..10).map { p(t0 + it * min, 5f + it * 0.1f) }
        assertEquals(0.5f, ModWidgetMath.deltaForReading(points, t0 + 10 * min), 1e-4f)
        assertEquals(0.5f, ModWidgetMath.deltaForReading(points, t0 + 10 * min + 50_000L), 1e-4f)
        // the history lags the number by more than a minute: no delta
        assertTrue(ModWidgetMath.deltaForReading(points, t0 + 12 * min).isNaN())
        assertTrue(ModWidgetMath.deltaForReading(emptyList(), t0).isNaN())
    }

    @Test
    fun usableSortsAndKeepsOnePerTimestamp() {
        val out = ModWidgetMath.usable(listOf(p(t0 + min, 6f), p(t0, 5f), p(t0 + min, 6.5f)), t0 + 10 * min)
        assertEquals(listOf(p(t0, 5f), p(t0 + min, 6.5f)), out)
    }

    // ---------- five-minute averages ----------

    @Test
    fun fiveMinuteBucketsFollowTheClock() {
        // :00..:04 in one bucket, :05 starts the next, even one second before/after the boundary
        val points = listOf(
            p(t0 + 10_000L, 5.0f), p(t0 + 70_000L, 5.2f), p(t0 + 299_999L, 5.4f),
            p(t0 + 300_000L, 8.0f), p(t0 + 360_000L, 8.4f),
        )
        val out = ModWidgetMath.fiveMinuteAverages(points)
        assertEquals(2, out.size)
        assertEquals(5.2f, out[0].value, 1e-4f)
        assertEquals((10_000L + 70_000L + 299_999L) / 3 + t0, out[0].time)
        assertEquals(8.2f, out[1].value, 1e-4f)
        assertEquals(t0 + 330_000L, out[1].time)
    }

    @Test
    fun theLastUnfinishedFiveMinutesIsTheMeanSoFarAndGapsHaveNoDot() {
        val full = (0 until 5).map { p(t0 + it * min, 6f + it * 0.1f) }
        val partial = listOf(p(t0 + 15 * min, 9f), p(t0 + 16 * min, 10f))   // 10..14 min: nothing
        val out = ModWidgetMath.fiveMinuteAverages(full + partial)
        assertEquals(2, out.size)
        assertEquals(6.2f, out[0].value, 1e-4f)
        assertEquals(9.5f, out[1].value, 1e-4f)
        assertEquals(t0 + 15 * min + 30_000L, out[1].time)
    }

    @Test
    fun chartPointsFollowTheDotStyleAndThePeriod() {
        val now = t0 + 4 * 60 * min
        val all = (0..240).map { p(t0 + it * min, 6f) }
        val avg = ModWidgetMath.chartPoints(all, now, ModWidgetSettings(periodHours = 1))
        assertTrue(avg.all { it.time >= now - 60 * min })
        assertEquals(13, avg.size) // 60 min of readings from the period start, plus the reading at "now"
        val each = ModWidgetMath.chartPoints(all, now, ModWidgetSettings(periodHours = 1, dots = ModWidgetDots.EACH))
        assertEquals(61, each.size)
        val line = ModWidgetMath.chartPoints(all, now, ModWidgetSettings(periodHours = 3, dots = ModWidgetDots.LINE))
        assertEquals(181, line.size)
    }

    @Test
    fun lineBreaksOnlyAtUpstreamsGap() {
        val gap = GlucoseChartGap.THRESHOLD_MS
        val points = listOf(p(t0, 5f), p(t0 + gap, 5f), p(t0 + 2 * gap + 1, 5f))
        val runs = ModWidgetMath.lineRuns(points)
        assertEquals(listOf(2, 1), runs.map { it.size })
    }

    // ---------- 5-minute delta ----------

    @Test
    fun deltaIsNewestAgainstFiveMinutesAgo() {
        val points = (0..10).map { p(t0 + it * min, 5f + it * 0.1f) }   // +0.1 a minute
        assertEquals(0.5f, ModWidgetMath.fiveMinuteDelta(points), 1e-4f)
    }

    @Test
    fun deltaWalksBackPastNearDuplicatesAndScalesTheGap() {
        // newest at 6 min; 4.5 min back is the first old enough; scaled to 5 min
        val points = listOf(p(t0, 4f), p(t0 + 90_000L, 5f), p(t0 + 6 * min - 1_000L, 6f), p(t0 + 6 * min, 6f))
        val d = ModWidgetMath.fiveMinuteDelta(points)
        assertEquals((6f - 5f) * (300_000f / (6 * min - 90_000L)), d, 1e-4f)
    }

    @Test
    fun deltaIsNotANumberWithoutAPairInTheWindow() {
        assertTrue(ModWidgetMath.fiveMinuteDelta(emptyList()).isNaN())
        assertTrue(ModWidgetMath.fiveMinuteDelta(listOf(p(t0, 5f))).isNaN())
        assertTrue(ModWidgetMath.fiveMinuteDelta(listOf(p(t0, 5f), p(t0 + 2 * min, 6f))).isNaN())     // too close
        assertTrue(ModWidgetMath.fiveMinuteDelta(listOf(p(t0, 5f), p(t0 + 25 * min, 6f))).isNaN())    // too far
    }

    // ---------- age and freshness ----------

    @Test
    fun ageTextBoundaries() {
        assertEquals(0, ModWidgetMath.ageMinutes(t0, t0))
        assertEquals(0, ModWidgetMath.ageMinutes(t0, t0 + 59_999L))
        assertEquals(1, ModWidgetMath.ageMinutes(t0, t0 + 60_000L))
        assertEquals(5, ModWidgetMath.ageMinutes(t0, t0 + 330_000L))
        assertEquals(0, ModWidgetMath.ageMinutes(t0 + 30_000L, t0))   // slightly ahead: just now
    }

    @Test
    fun freshUpToTheExactLimitThenStale() {
        assertEquals(ModWidgetFreshness.FRESH, ModWidgetMath.freshness(t0, t0 + timeout, timeout))
        assertEquals(ModWidgetFreshness.STALE, ModWidgetMath.freshness(t0, t0 + timeout + 1, timeout))
        assertEquals(ModWidgetFreshness.NONE, ModWidgetMath.freshness(0L, t0, timeout))
        assertEquals(ModWidgetFreshness.FRESH, ModWidgetMath.freshness(t0 + 50_000L, t0, timeout))
        assertEquals(ModWidgetFreshness.NONE, ModWidgetMath.freshness(t0 + 61_000L, t0, timeout))
    }

    // ---------- the "N min ago" alarm ----------

    @Test
    fun alarmOnEachWholeMinuteThenExactlyAtTheFreshnessLimit() {
        assertEquals(t0 + min, ModWidgetMath.nextRedraw(t0, t0, timeout))
        assertEquals(t0 + min, ModWidgetMath.nextRedraw(t0, t0 + 10_000L, timeout))
        assertEquals(t0 + 2 * min, ModWidgetMath.nextRedraw(t0, t0 + min, timeout))
        assertEquals(t0 + 5 * min, ModWidgetMath.nextRedraw(t0, t0 + 4 * min + 30_000L, timeout))
        // 330 s is not a whole number of minutes: the last alarm is the limit itself, +1 ms
        assertEquals(t0 + timeout + 1, ModWidgetMath.nextRedraw(t0, t0 + 5 * min, timeout))
        assertEquals(t0 + timeout + 1, ModWidgetMath.nextRedraw(t0, t0 + timeout, timeout))
        assertNull(ModWidgetMath.nextRedraw(t0, t0 + timeout + 1, timeout))
        assertNull(ModWidgetMath.nextRedraw(0L, t0, timeout))
        assertEquals(t0 + 30_000L + min, ModWidgetMath.nextRedraw(t0 + 30_000L, t0, timeout))   // slightly ahead
    }

    @Test
    fun followingTheAlarmsEndsStaleAndStops() {
        var now = t0 + 5_000L
        val fired = ArrayList<Long>()
        while (true) {
            val next = ModWidgetMath.nextRedraw(t0, now, timeout) ?: break
            assertTrue(next > now)
            fired.add(next - t0)
            now = next
        }
        assertEquals(listOf(60_000L, 120_000L, 180_000L, 240_000L, 300_000L, 330_001L), fired)
        assertEquals(ModWidgetFreshness.STALE, ModWidgetMath.freshness(t0, now, timeout))
    }

    // ---------- chart scale ----------

    @Test
    fun scaleAlwaysHoldsTheRangeAndTheData() {
        val s = ModWidgetMath.verticalScale(listOf(5f, 6f), 3.9f, 10f)
        assertTrue(s.min < 3.9f && s.max > 10f)
        val wide = ModWidgetMath.verticalScale(listOf(2.2f, 22f), 3.9f, 10f)
        assertTrue(wide.min < 2.2f && wide.max > 22f)
        // a value outside the scale sits at the edge, not off the picture
        assertEquals(100f, s.y(-50f, 0f, 100f), 1e-3f)
        assertEquals(0f, s.y(99f, 0f, 100f), 1e-3f)
        assertEquals(0f, ModWidgetMath.x(-1L, 1000L, 100L, 0f, 50f), 1e-3f)
        assertEquals(50f, ModWidgetMath.x(5000L, 1000L, 100L, 0f, 50f), 1e-3f)
    }

    @Test
    fun trendWords() {
        assertNull(ModWidgetMath.trend(Float.NaN))
        assertEquals(ModWidgetMath.Trend.STABLE, ModWidgetMath.trend(0.3f))
        assertEquals(ModWidgetMath.Trend.RISING_SLOWLY, ModWidgetMath.trend(0.7f))
        assertEquals(ModWidgetMath.Trend.RISING_FAST, ModWidgetMath.trend(2.5f))
        assertEquals(ModWidgetMath.Trend.FALLING, ModWidgetMath.trend(-1.2f))
    }
}
