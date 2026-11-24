package mod.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.health.ModHealthBatch
import tk.glucodata.mod.health.ModHealthBatch.Sample

/**
 * JugglucoNGmod, watch block (part 5, joints critic C1; Watch 7i): the extra of a heart rate row - `hr1` of 1 minute,
 * `hr5` of 5 minutes from an older watch app - one format for both sides: built on the watch, read and merged on the
 * phone. Made-up values.
 */
class ModHealthHr5Test {

    private val start = 1_800_000_000_000L

    private fun hr(mean: Float, n: Int, min: Int, max: Int, kind: String = ModHealthBatch.KIND_HR5) =
        Sample(kind, start, mean, ModHealthBatch.hr5Extra(n, min, max))

    @Test
    fun theExtraIsBuiltAndReadBack() {
        assertEquals("n=12;min=58;max=91", ModHealthBatch.hr5Extra(12, 58, 91))
        assertEquals(ModHealthBatch.Hr5(12, 58, 91), ModHealthBatch.parseHr5Extra("n=12;min=58;max=91"))
        // order and unknown fields of a newer watch do not matter
        assertEquals(ModHealthBatch.Hr5(3, 60, 70), ModHealthBatch.parseHr5Extra("max=70;x=1;n=3;min=60"))
    }

    @Test
    fun aBrokenExtraIsNotRead() {
        for (bad in listOf(null, "", "n=0;min=60;max=70", "n=-1;min=60;max=70", "n=x;min=60;max=70", "min=60;max=70",
            "n=3;max=70", "n=3;min=60", "garbage")) {
            assertNull(bad, ModHealthBatch.parseHr5Extra(bad))
        }
    }

    @Test
    fun lateReadingsMergeByTheirCounts() {
        val merged = ModHealthBatch.mergeHeartRate(hr(70f, 3, 60, 80), hr(90f, 1, 88, 92))!!
        assertEquals(75f, merged.value, 0.0001f) // (70*3 + 90*1) / 4
        assertEquals("n=4;min=60;max=92", merged.extra)
        assertEquals(start, merged.timeMs)
        assertEquals(ModHealthBatch.KIND_HR5, merged.kind)
    }

    @Test
    fun oneMinuteRowsMergeTheSameWayAndStayOneMinuteRows() {
        val merged = ModHealthBatch.mergeHeartRate(
            hr(64f, 50, 58, 72, ModHealthBatch.KIND_HR1),
            hr(70f, 10, 66, 75, ModHealthBatch.KIND_HR1),
        )!!
        assertEquals(65f, merged.value, 0.0001f) // (64*50 + 70*10) / 60
        assertEquals("n=60;min=58;max=75", merged.extra)
        assertEquals(ModHealthBatch.KIND_HR1, merged.kind)
    }

    @Test
    fun onlyTwoHeartRateRowsOfOneKindAndOneStartMerge() {
        assertNull(ModHealthBatch.mergeHeartRate(hr(70f, 3, 60, 80), hr(90f, 1, 88, 92).copy(timeMs = start + 300_000L)))
        assertNull(ModHealthBatch.mergeHeartRate(hr(70f, 3, 60, 80), Sample(ModHealthBatch.KIND_BATTERY, start, 80f, "")))
        assertNull(ModHealthBatch.mergeHeartRate(hr(70f, 3, 60, 80), hr(90f, 1, 88, 92).copy(extra = "broken")))
        assertNull(ModHealthBatch.mergeHeartRate(hr(Float.NaN, 3, 60, 80), hr(90f, 1, 88, 92)))
        // a 1-minute and a 5-minute row of the same start are two windows, never one
        assertNull(ModHealthBatch.mergeHeartRate(hr(70f, 3, 60, 80), hr(90f, 1, 88, 92, ModHealthBatch.KIND_HR1)))
        assertNull(ModHealthBatch.mergeHeartRate(hr(70f, 3, 60, 80, ModHealthBatch.KIND_HR1), hr(90f, 1, 88, 92)))
    }

    @Test
    fun bothHeartRateKindsAreKnownAndGoInABatch() {
        assertTrue(ModHealthBatch.isHeartRate(ModHealthBatch.KIND_HR1))
        assertTrue(ModHealthBatch.isHeartRate(ModHealthBatch.KIND_HR5))
        assertFalse(ModHealthBatch.isHeartRate(ModHealthBatch.KIND_STEPS_DAY))
        assertEquals("hr1", ModHealthBatch.KIND_HR1)
        assertTrue(ModHealthBatch.KIND_HR1 in ModHealthBatch.KINDS)
        assertTrue(ModHealthBatch.KIND_HR5 in ModHealthBatch.KINDS)
    }
}
