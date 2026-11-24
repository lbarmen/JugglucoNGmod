package mod.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.health.HealthConfig
import tk.glucodata.mod.health.HrAggregator
import tk.glucodata.mod.health.ModHealthBatch

/**
 * JugglucoNGmod, watch block (part 4; Watch 7i): heart rate means per window - 1-minute `hr1` rows, the 5-minute `hr5`
 * of older watch apps still buildable (ported from Glucove). Made-up values.
 */
class HrAggregatorTest {

    private val min = 60_000L
    private val t0 = 1_800_000_000_000L // on a 5-minute boundary

    @Test
    fun oneMinuteWindowsAlignedToTheClockAsHr1Rows() {
        assertEquals(60_000L, HealthConfig.HR_WINDOW_MS)
        val a = HrAggregator()
        a.add(t0 + 10_000, 60f)
        a.add(t0 + 20_000, 80f)
        a.add(t0 + 30_000, 0f)
        a.add(t0 + 35_000, Float.NaN)
        a.add(t0 + 40_000, 120f, noContact = true)
        a.add(t0 + min + 1_000, 90f) // the next window already
        val closed = a.close(nowMs = t0 + min + 10_000, graceMs = 5_000)
        assertEquals(1, closed.size)
        val s = closed.single()
        assertEquals(ModHealthBatch.KIND_HR1, s.kind)
        assertEquals(t0, s.timeMs)
        assertEquals(70f, s.value)
        assertEquals("n=2;min=60;max=80", s.extra)
        assertEquals(1, a.openCount)
    }

    @Test
    fun fiveMinuteWindowsOfAnOlderAppAreStillBuiltAsHr5() {
        val a = HrAggregator(5 * min, ModHealthBatch.KIND_HR5)
        a.add(t0 + 10_000, 60f)
        a.add(t0 + 4 * min, 80f)
        val s = a.close(nowMs = t0 + 5 * min + 10_000, graceMs = 5_000).single()
        assertEquals(ModHealthBatch.KIND_HR5, s.kind)
        assertEquals(t0, s.timeMs)
        assertEquals("n=2;min=60;max=80", s.extra)
    }

    @Test
    fun openWindowWaitsForGrace() {
        val a = HrAggregator()
        a.add(t0 + 10_000, 70f)
        assertTrue(a.close(nowMs = t0 + min + 2_000, graceMs = 5_000).isEmpty())
        assertEquals(1, a.close(nowMs = t0 + min + 6_000, graceMs = 5_000).size)
    }

    @Test
    fun normalDeliveryWaitsTwoMinutesDeliverNowFiveSeconds() {
        val a = HrAggregator()
        a.add(t0 + 10_000, 72f)
        val now = t0 + min + 30_000
        assertTrue(a.close(now, HealthConfig.HR_GRACE_DATA_MS).isEmpty())
        assertEquals(1, a.close(now, HealthConfig.HR_GRACE_REFRESH_MS).size)
    }

    @Test
    fun aDeliveryClosesTheWindowsEndedByItsNewestReadingAtOnce() {
        // a batch of Health Services covering 3.5 minutes, delivered right after its newest reading
        val a = HrAggregator()
        val readings = (0 until 210).map { t0 + it * 1_000L } // one a second, t0 .. t0+3:29
        readings.forEach { a.add(it, 60f + (it - t0) / min) }
        val newest = readings.last()
        val closed = a.close(nowMs = newest + 2_000, graceMs = HealthConfig.HR_GRACE_DATA_MS, coveredUntilMs = newest)
        assertEquals(listOf(t0, t0 + min, t0 + 2 * min), closed.map { it.timeMs }) // the whole minutes
        assertEquals(listOf("n=60;min=60;max=60", "n=60;min=61;max=61", "n=60;min=62;max=62"), closed.map { it.extra })
        assertEquals(1, a.openCount) // t0+3 min: the next batch may still bring readings for it
        // a window ending exactly at the newest reading is complete too
        a.add(t0 + 4 * min, 70f)
        assertEquals(listOf(t0 + 3 * min), a.close(t0 + 4 * min, HealthConfig.HR_GRACE_DATA_MS, t0 + 4 * min).map { it.timeMs })
    }

    @Test
    fun survivesProcessRestartViaEncode() {
        val a = HrAggregator()
        a.add(t0 + 1_000, 64f)
        a.add(t0 + 2_000, 66f)
        val b = HrAggregator.decode(a.encode())
        b.add(t0 + 3_000, 68f)
        val s = b.close(nowMs = t0 + 2 * min, graceMs = 0).single()
        assertEquals(ModHealthBatch.KIND_HR1, s.kind)
        assertEquals(66f, s.value)
        assertEquals("n=3;min=64;max=68", s.extra)
    }

    @Test
    fun readingForAClosedWindowIsLateAndGoesAsASecondRow() {
        val a = HrAggregator()
        assertFalse(a.add(t0 + 1_000, 60f))
        assertEquals(1, a.close(nowMs = t0 + 3 * min, graceMs = HealthConfig.HR_GRACE_DATA_MS).size)
        assertEquals(t0 + min, a.closedUntil)
        assertTrue(a.add(t0 + 30_000, 80f)) // late: its window went already
        assertFalse(a.add(t0 + min, 70f)) // the next window is open
        assertFalse(a.add(t0 + 30_000, 0f)) // skipped readings are never late
        val again = a.close(nowMs = t0 + 20 * min, graceMs = 0)
        assertEquals(listOf(t0, t0 + min), again.map { it.timeMs }) // the phone merges the two t0 rows by n
        assertEquals(listOf(ModHealthBatch.KIND_HR1, ModHealthBatch.KIND_HR1), again.map { it.kind })
        assertEquals("n=1;min=80;max=80", again[0].extra)
        // closedUntil survives a restart
        assertTrue(HrAggregator.decode(a.encode(), a.closedUntil).add(t0 + 90_000, 75f))
    }

    @Test
    fun brokenStateIsIgnored() {
        assertEquals(0, HrAggregator.decode("").openCount)
        assertEquals(0, HrAggregator.decode("abc|1:2").openCount)
        assertEquals(0, HrAggregator.decode("1:2:0:3:4").openCount) // zero count
    }
}
