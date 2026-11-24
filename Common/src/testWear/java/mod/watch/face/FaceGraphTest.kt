package mod.watch.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.face.FaceGraph
import tk.glucodata.mod.watch.face.FacePoint

/** JugglucoNGmod, watch block (part 3a): the face graph's window and vertical scale. Made-up values. */
class FaceGraphTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    private fun assertInView(plot: FaceGraph.Plot, vararg values: Float) {
        for (v in values) assertTrue("$v in ${plot.bottom}..${plot.top}", v > plot.bottom && v < plot.top)
    }

    @Test
    fun anEmptyGraphStillShowsTheRangeLines() {
        val plot = FaceGraph.plot(emptyList(), now, 3, 3.9f, 10.0f, isMmol = true)
        assertTrue(plot.points.isEmpty())
        assertInView(plot, 3.9f, 10.0f)
        assertEquals(now - 3 * 60 * minute, plot.fromMs)
        assertEquals(now, plot.toMs)
    }

    @Test
    fun oneValueAndTheRangeAreInView() {
        val plot = FaceGraph.plot(listOf(FacePoint(now - 10 * minute, 6.2f)), now, 3, 3.9f, 10.0f, isMmol = true)
        assertEquals(1, plot.points.size)
        assertInView(plot, 3.9f, 6.2f, 10.0f)
    }

    @Test
    fun everythingAboveTheRangeKeepsTheRangeAndTheTraceInView() {
        val points = (0..30).map { FacePoint(now - it * 5 * minute, 15f + it % 4) }
        val plot = FaceGraph.plot(points, now, 3, 3.9f, 10.0f, isMmol = true)
        assertInView(plot, 3.9f, 10.0f, 15f, 18f)
        // higher on the screen is a smaller y
        assertTrue(FaceGraph.yFraction(plot, 18f) < FaceGraph.yFraction(plot, 10f))
        assertTrue(FaceGraph.yFraction(plot, 10f) < FaceGraph.yFraction(plot, 3.9f))
        for (p in plot.points) {
            assertTrue(FaceGraph.yFraction(plot, p.value) in 0f..1f)
            assertTrue(FaceGraph.xFraction(plot, p.timeMs) in 0f..1f)
        }
    }

    @Test
    fun aCalmTraceInMgdlGetsUpstreamsLeastSpan() {
        val points = (0..10).map { FacePoint(now - it * minute, 100f + it % 2) }
        val plot = FaceGraph.plot(points, now, 1, 95f, 105f, isMmol = false)
        assertTrue(plot.top - plot.bottom >= 54f)
        assertInView(plot, 95f, 105f)
        assertTrue(plot.bottom >= 0f)
    }

    @Test
    fun theWindowIsOneThreeOrSixHours() {
        val points = listOf(5, 2 * 60, 4 * 60, 7 * 60).map { FacePoint(now - it * minute, 6f) }
        assertEquals(1, FaceGraph.plot(points, now, 1, 3.9f, 10f, true).points.size)
        assertEquals(2, FaceGraph.plot(points, now, 3, 3.9f, 10f, true).points.size)
        assertEquals(3, FaceGraph.plot(points, now, 6, 3.9f, 10f, true).points.size)
        assertEquals(3, FaceGraph.sanitizeHours(2))
        assertEquals(3, FaceGraph.sanitizeHours(0))
        assertEquals(6, FaceGraph.sanitizeHours(6))
        assertEquals(2, FaceGraph.plot(points, now, 4, 3.9f, 10f, true).points.size)
    }

    @Test
    fun brokenValuesAreLeftOutAndPointsComeOldestFirst() {
        val points = listOf(FacePoint(now - minute, 6f), FacePoint(now - 3 * minute, Float.NaN),
            FacePoint(now - 2 * minute, 0f), FacePoint(now - 5 * minute, 5f))
        val plot = FaceGraph.plot(points, now, 1, 3.9f, 10f, true)
        assertEquals(listOf(now - 5 * minute, now - minute), plot.points.map { it.timeMs })
    }

    @Test
    fun aPointAheadOfTheWatchClockSitsAtTheRightEdge() {
        val plot = FaceGraph.plot(listOf(FacePoint(now + 2 * minute, 6f)), now, 3, 3.9f, 10f, true)
        assertEquals(1, plot.points.size)
        assertEquals(1f, FaceGraph.xFraction(plot, now + 2 * minute))
        assertEquals(0f, FaceGraph.xFraction(plot, now - 3 * 60 * minute))
    }

    // ------------------------------------------------------------------ 5-minute dots (part 7a)

    /** 1_800_000_000_000 ms is 08:00:00 UTC, a multiple of 5 minutes. */
    private val bucket = FaceGraph.BUCKET_MS

    @Test
    fun oneDotPerFiveMinutesOfTheClockTheMeanAtTheMiddle() {
        // readings every minute from 08:00 to 08:14 (three full buckets): 5..9, 10..14, 15..19
        val points = (0 until 15).map { FacePoint(now + it * minute, 5f + it) }
        val dots = FaceGraph.fiveMinuteMeans(points)
        assertEquals(listOf(now + bucket / 2, now + bucket + bucket / 2, now + 2 * bucket + bucket / 2),
            dots.map { it.timeMs })
        assertEquals(listOf(7f, 12f, 17f), dots.map { it.value })
    }

    @Test
    fun bucketsFollowTheClockNotTheFirstReading() {
        // readings at 08:03, 08:04 | 08:05, 08:06: two buckets, not one starting at 08:03
        val points = listOf(3, 4, 5, 6).map { FacePoint(now + it * minute, it.toFloat()) }
        val dots = FaceGraph.fiveMinuteMeans(points)
        assertEquals(2, dots.size)
        assertEquals(3.5f, dots[0].value, 0.0001f)
        assertEquals(5.5f, dots[1].value, 0.0001f)
    }

    @Test
    fun theBucketStillFillingSitsAtItsNewestReadingNotAheadOfIt() {
        // 08:05 and 08:06 only: the middle 08:07:30 is still to come
        val dots = FaceGraph.fiveMinuteMeans(listOf(FacePoint(now + 5 * minute, 6f), FacePoint(now + 6 * minute, 7f)))
        assertEquals(1, dots.size)
        assertEquals(now + 6 * minute, dots[0].timeMs)
        assertEquals(6.5f, dots[0].value, 0.0001f)
    }

    @Test
    fun aGapHasNoDotsBrokenValuesDoNotCountAndOrderDoesNotMatter() {
        val points = listOf(FacePoint(now + 31 * minute, 8f), FacePoint(now + minute, 6f),
            FacePoint(now + 2 * minute, Float.NaN), FacePoint(now + 3 * minute, 0f), FacePoint(now + 4 * minute, 7f),
            FacePoint(now + 37 * minute, 9f))
        val dots = FaceGraph.fiveMinuteMeans(points)
        // a past bucket with one early reading still sits at its middle; only the newest one is held back
        assertEquals(listOf(now + bucket / 2, now + 6 * bucket + bucket / 2, now + 37 * minute), dots.map { it.timeMs })
        assertEquals(listOf(6.5f, 8f, 9f), dots.map { it.value })
        assertTrue(FaceGraph.fiveMinuteMeans(emptyList()).isEmpty())
    }

    @Test
    fun thePlotsDotsAreTheMeansOfItsWindow() {
        val points = (0..200).map { FacePoint(now - it * minute, 6f + (it % 7) * 0.1f) }
        val plot = FaceGraph.plot(points, now, 1, 3.9f, 10f, true)
        assertEquals(FaceGraph.fiveMinuteMeans(plot.points), plot.dots)
        // an hour plus the reading at now itself: 12 full buckets and the one at 08:00 still filling
        assertEquals(13, plot.dots.size)
        assertTrue(plot.dots.all { it.timeMs in plot.fromMs..plot.toMs })
    }

    // ------------------------------------------------------------------ forecast (part 7a)

    private fun forecast(minutes: Int, value: (Int) -> Float = { 8f - it * 0.05f }) =
        (5..minutes step 5).map { FacePoint(now + it * minute, value(it)) }

    @Test
    fun withoutAForecastTheGraphEndsAtNow() {
        val plot = FaceGraph.plot(listOf(FacePoint(now - minute, 6f)), now, 3, 3.9f, 10f, true)
        assertEquals(now, plot.toMs)
        assertEquals(now, plot.nowMs)
        assertTrue(plot.forecast.isEmpty())
    }

    @Test
    fun aForecastTakesAtMostAFifthOfTheWidthAndIsCutThere() {
        // 3 h of readings: room for 45 minutes; upstream's default horizon of 120 minutes is cut there
        val plot = FaceGraph.plot(listOf(FacePoint(now - minute, 8f)), now, 3, 3.9f, 10f, true, forecast(120))
        assertEquals(now + 45 * minute, plot.toMs)
        assertEquals(now, plot.nowMs)
        assertEquals(9, plot.forecast.size)
        assertTrue(plot.forecast.all { it.timeMs in (now + 1)..plot.toMs })
        val share = (plot.toMs - now).toFloat() / (plot.toMs - plot.fromMs)
        assertTrue(share <= 0.2f + 1e-4f)
        // the readings keep their place left of now
        assertEquals(0.8f, FaceGraph.xFraction(plot, now), 0.001f)
    }

    @Test
    fun aShortForecastNeedsOnlyItsOwnRoomAndPointsBeforeNowAreLeftOut() {
        val points = listOf(FacePoint(now - 5 * minute, 7f), FacePoint(now, 6f)) + forecast(20)
        val plot = FaceGraph.plot(listOf(FacePoint(now - minute, 6f)), now, 6, 3.9f, 10f, true, points)
        assertEquals(now + 20 * minute, plot.toMs)
        assertEquals(4, plot.forecast.size)
    }

    @Test
    fun theScaleHoldsTheForecast() {
        val low = forecast(30) { 6f - it * 0.15f }   // down to 1.5; 6 h leave room for all of it
        val plot = FaceGraph.plot(listOf(FacePoint(now - minute, 6f)), now, 6, 3.9f, 10f, true, low)
        assertTrue(plot.bottom < 1.5f)
        assertTrue(plot.forecast.all { FaceGraph.yFraction(plot, it.value) < 1f })
    }

    @Test
    fun aSensorGapSplitsTheTrace() {
        val points = listOf(0, 5, 10, 40, 45, 70).map { FacePoint(now - (70 - it) * minute, 6f) }
        val runs = FaceGraph.segments(points)
        assertEquals(listOf(3, 2, 1), runs.map { it.size })
        // exactly the limit still joins
        assertEquals(1, FaceGraph.segments(points.take(2), 5 * minute).size)
        assertTrue(FaceGraph.segments(emptyList()).isEmpty())
    }
}
