package mod.health

import com.google.android.gms.wearable.DataMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.health.ModHealthBatch
import tk.glucodata.mod.health.ModHealthBatch.Decoded
import tk.glucodata.mod.health.ModHealthBatch.Sample
import tk.glucodata.mod.health.ModHealthBatch.StepsTotal
import tk.glucodata.mod.health.ModHealthDataMap

/** JugglucoNGmod, watch block (part 4): the heart rate and steps batch format (runs in both test tasks). Made-up values. */
class ModHealthBatchTest {

    private val at = 1_800_000_000_000L
    private val minute = 60_000L

    private val rows = listOf(
        Sample(ModHealthBatch.KIND_HR5, at, 71.5f, "n=12;min=64;max=80"),
        Sample(ModHealthBatch.KIND_STEPS_DAY, at + 2 * minute, 4321f, (at - 10 * 60 * minute).toString()),
        Sample(ModHealthBatch.KIND_BATTERY, at + 3 * minute, 55f, ModHealthBatch.CHARGING),
        Sample(ModHealthBatch.KIND_STATUS, at + 3 * minute, 0f, "app=x;sdk=34;perm=1"),
    )

    private fun decode(b: ModHealthBatch) =
        ModHealthBatch.decode(b.version, b.seq, b.sentAtMs, b.kinds(), b.times(), b.values(), b.extras())

    @Test
    fun arraysRoundTrip() {
        val batch = ModHealthBatch(7, at + 4 * minute, rows)
        assertEquals(Decoded.Ok(batch), decode(batch))
        assertEquals("/mod/health/batch/7", batch.path)
    }

    @Test
    fun dataMapRoundTrip() {
        val batch = ModHealthBatch(42, at, rows)
        val map = DataMap()
        ModHealthDataMap.write(batch, map)
        assertEquals(Decoded.Ok(batch), ModHealthDataMap.read(map))
    }

    @Test
    fun emptyDataMapIsBrokenNotAnException() {
        assertEquals(Decoded.Broken("version"), ModHealthDataMap.read(DataMap()))
    }

    @Test
    fun arraysOfDifferentLengthsAreRefused() {
        val b = ModHealthBatch(1, at, rows)
        val shortTimes = b.times().copyOf(rows.size - 1)
        assertEquals(Decoded.Broken("lengths"), ModHealthBatch.decode(1, 1, at, b.kinds(), shortTimes, b.values(), b.extras()))
        val shortExtras = b.extras().copyOf(rows.size - 1)
        assertEquals(Decoded.Broken("lengths"), ModHealthBatch.decode(1, 1, at, b.kinds(), b.times(), b.values(), shortExtras))
        assertEquals(Decoded.Broken("missing"), ModHealthBatch.decode(1, 1, at, null, b.times(), b.values(), b.extras()))
    }

    @Test
    fun newerVersionIsLeftAloneVersionZeroIsBroken() {
        val b = ModHealthBatch(1, at, rows)
        assertEquals(Decoded.Newer(3), ModHealthBatch.decode(3, 1, at, b.kinds(), b.times(), b.values(), b.extras()))
        // the watch apps before 7i (1) and from 7i (2) are both read
        assertEquals(Decoded.Ok(ModHealthBatch(1, at, rows, 1)), ModHealthBatch.decode(1, 1, at, b.kinds(), b.times(), b.values(), b.extras()))
        assertEquals(Decoded.Ok(ModHealthBatch(1, at, rows, 2)), ModHealthBatch.decode(2, 1, at, b.kinds(), b.times(), b.values(), b.extras()))
        assertEquals(Decoded.Broken("version"), ModHealthBatch.decode(0, 1, at, b.kinds(), b.times(), b.values(), b.extras()))
    }

    @Test
    fun brokenHeaderAndNullKindAreRefused() {
        val b = ModHealthBatch(1, at, rows)
        assertEquals(Decoded.Broken("header"), ModHealthBatch.decode(1, 0, at, b.kinds(), b.times(), b.values(), b.extras()))
        assertEquals(Decoded.Broken("header"), ModHealthBatch.decode(1, 1, -1, b.kinds(), b.times(), b.values(), b.extras()))
        val kinds: Array<String?> = arrayOf(null, "hr5", "hr5", "hr5")
        assertEquals(Decoded.Broken("kind"), ModHealthBatch.decode(1, 1, at, kinds, b.times(), b.values(), b.extras()))
    }

    @Test
    fun nullExtraBecomesEmpty() {
        val extras: Array<String?> = arrayOf(null)
        val decoded = ModHealthBatch.decode(1, 3, at, arrayOf("hr5"), longArrayOf(at), floatArrayOf(66f), extras)
        assertEquals(Decoded.Ok(ModHealthBatch(3, at, listOf(Sample("hr5", at, 66f, "")), version = 1)), decoded)
    }

    @Test
    fun onlyKnownKindsAreSentAndReadAsKnown() {
        val withForeign = rows + Sample("floors_day", at, 3f, "")
        val batch = ModHealthBatch.forSending(5, at, withForeign)
        assertEquals(rows, batch.samples)
        val read = (ModHealthBatch.decode(1, 5, at, arrayOf("hr5", "sleep"), longArrayOf(at, at), floatArrayOf(66f, 1f), arrayOf("", "")) as Decoded.Ok).batch
        assertEquals(2, read.samples.size)
        assertEquals(listOf(Sample("hr5", at, 66f, "")), read.knownSamples())
    }

    @Test
    fun batchesWithOneMinuteRowsGoAsVersion2SoAnOlderPhoneKeepsThem() {
        // Critic 7k, C1: a phone app before 7i (its VERSION 1) drops hr1 rows in knownSamples and deletes the batch.
        // Its decode returns Newer for any version above 1 (git show 0dd952f2c: ModHealthBatch.decode), and its
        // ModWatchStore.receive leaves a Newer item in the Data Layer (ModWatchIntake.outcome -> KEEP, noteNewer) -
        // so the batch waits there until the phone app is updated.
        val versionBefore7i = 1
        val hr1 = Sample(ModHealthBatch.KIND_HR1, at, 64f, "n=58;min=60;max=70")
        val batch = ModHealthBatch.forSending(9, at, listOf(hr1) + rows)
        assertEquals(hr1, batch.samples.first()) // the new watch sends it
        assertEquals(2, batch.version)
        val map = DataMap()
        ModHealthDataMap.write(batch, map)
        val written = map.getInt(ModHealthBatch.KEY_VERSION, 0)
        assertTrue(written > versionBefore7i) // what the older phone compares with its own version
        // this phone reads it whole
        val read = ModHealthDataMap.read(map)
        assertEquals(Decoded.Ok(batch), read)
        assertEquals(listOf(hr1) + rows, (read as Decoded.Ok).batch.knownSamples())
    }

    @Test
    fun seqFromPath() {
        assertEquals(12L, ModHealthBatch.seqFromPath("/mod/health/batch/12"))
        assertEquals("/mod/health/batch/12", ModHealthBatch.path(12))
        assertNull(ModHealthBatch.seqFromPath("/mod/health/batch/"))
        assertNull(ModHealthBatch.seqFromPath("/mod/health/batch/0"))
        assertNull(ModHealthBatch.seqFromPath("/mod/health/batch/x"))
        assertNull(ModHealthBatch.seqFromPath("/mod/tick"))
        assertNull(ModHealthBatch.seqFromPath(null))
    }

    @Test
    fun chunksStayUnderLimitAndKeepOrder() {
        val samples = (1..1000).map { Sample(ModHealthBatch.KIND_HR5, at + it * 5 * minute, 60f + it % 40, "n=30;min=60;max=99") }
        val chunks = ModHealthBatch.chunks(samples, maxBytes = 5_000)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { c -> c.sumOf { it.approxBytes() } <= 5_000 })
        assertEquals(samples, chunks.flatten())
        assertEquals(2, ModHealthBatch.chunks(samples).size) // 1000 means of 55 bytes: over one 50 KB batch
        assertEquals(1, ModHealthBatch.chunks(samples.take(800)).size) // ~2.8 days of means fit one batch
    }

    @Test
    fun oversizedRowGoesAloneInsteadOfLooping() {
        val big = Sample(ModHealthBatch.KIND_STATUS, at, 0f, "x".repeat(10_000))
        val small = Sample(ModHealthBatch.KIND_HR5, at, 70f)
        assertEquals(listOf(listOf(big), listOf(small)), ModHealthBatch.chunks(listOf(big, small), maxBytes = 1_000))
        assertEquals(emptyList<List<Sample>>(), ModHealthBatch.chunks(emptyList()))
    }

    // Step totals (rule ported from Glucove's phone, WatchBuckets.delta, with its tests). Made-up totals.
    private val hour = 60 * minute
    private val day1 = at - 10 * hour // the watch's day start
    private val day2 = day1 + 24 * hour

    private fun total(t: Long, steps: Double, start: Long?) = StepsTotal(t, steps, start)

    @Test
    fun sameDayIsTheDifferenceAndDayStartJitterIsNoNewDay() {
        val a = total(at, 1000.0, day1)
        val b = total(at + 2 * minute, 1100.0, day1 + 1)
        val c = total(at + 4 * minute, 1150.0, day1 - 2)
        assertFalse(ModHealthBatch.isNewDay(a, b))
        assertEquals(100.0, ModHealthBatch.stepsDelta(a, b), 1e-9)
        assertEquals(50.0, ModHealthBatch.stepsDelta(b, c), 1e-9)
        assertFalse(ModHealthBatch.isNewDay(a, total(at + minute, 1001.0, day1 + hour))) // exactly an hour: jitter
    }

    @Test
    fun realMidnightIsTheWholeTotal() {
        val late = total(day2 - 2 * minute, 9930.0, day1)
        val first = total(day2 + 3 * minute, 40.0, day2)
        assertTrue(ModHealthBatch.isNewDay(late, first))
        assertEquals(40.0, ModHealthBatch.stepsDelta(late, first), 1e-9)
        // another day with a bigger total is not added as a difference
        assertEquals(5000.0, ModHealthBatch.stepsDelta(total(day2 - minute, 3000.0, day1), total(day2 + 5 * minute, 5000.0, day2)), 1e-9)
        // a late reading with the old start after midnight, then a smaller total: a new day (the total went down)
        val lateAfterMidnight = total(day2 + 2 * minute, 9935.0, day1)
        assertEquals(50.0, ModHealthBatch.stepsDelta(lateAfterMidnight, total(day2 + 7 * minute, 50.0, day2)), 1e-9)
    }

    @Test
    fun dayStartShiftWithoutResetIsTheDifferenceNotTheWholeTotalAgain() {
        // The watch worked its day start out again more than an hour later, but before the previous reading, and the
        // total grows: the same day.
        val prev = total(at, 5000.0, day1)
        val cur = total(at + 3 * minute, 5200.0, day1 + 2 * hour)
        assertFalse(ModHealthBatch.isNewDay(prev, cur))
        assertEquals(200.0, ModHealthBatch.stepsDelta(prev, cur), 1e-9)
    }

    @Test
    fun timeZoneChangeKeepsTheDay() {
        // Flying west by 3 hours at 14:00: local midnight moves 3 hours later, still before the previous reading.
        val beforeWest = total(day1 + 14 * hour, 6000.0, day1)
        val afterWest = total(day1 + 14 * hour + 5 * minute, 6040.0, day1 + 3 * hour)
        assertEquals(40.0, ModHealthBatch.stepsDelta(beforeWest, afterWest), 1e-9)
        // Flying east by 5 hours: local midnight moves 5 hours earlier.
        val afterEast = total(day1 + 14 * hour + 5 * minute, 6060.0, day1 - 5 * hour)
        assertFalse(ModHealthBatch.isNewDay(beforeWest, afterEast))
        assertEquals(60.0, ModHealthBatch.stepsDelta(beforeWest, afterEast), 1e-9)
        // East late in the evening: the new local midnight is already past - the watch starts a new day from zero.
        val evening = total(day1 + 22 * hour, 9000.0, day1)
        val newDay = total(day1 + 22 * hour + 10 * minute, 120.0, day1 + 22 * hour + 5 * minute)
        assertTrue(ModHealthBatch.isNewDay(evening, newDay))
        assertEquals(120.0, ModHealthBatch.stepsDelta(evening, newDay), 1e-9)
    }

    @Test
    fun aTotalCountsThePhonesDayUnlessItsWatchDayStartedADayBefore() {
        // part 5b, critic 5 N-5-4: the phone's day starts at day2
        val phoneDay = day2
        assertTrue(ModHealthBatch.countsDayOf(total(day2 + 5 * minute, 40.0, day2), phoneDay))
        // sent after the phone's midnight, but the watch has not passed its own: yesterday's total
        assertFalse(ModHealthBatch.countsDayOf(total(day2 + 5 * minute, 9100.0, day1), phoneDay))
        // the phone flew 3 hours west and the watch is still in the old zone: its day started 3 hours before the phone's
        assertTrue(ModHealthBatch.countsDayOf(total(day2 + 4 * hour, 800.0, day2 - 3 * hour), phoneDay))
        // ...or east: its day starts 5 hours after the phone's
        assertTrue(ModHealthBatch.countsDayOf(total(day2 + 6 * hour, 900.0, day2 + 5 * hour), phoneDay))
        // yesterday's total of a watch 3 hours west is still yesterday's
        assertFalse(ModHealthBatch.countsDayOf(total(day2 + 2 * hour, 9000.0, day1 + 3 * hour), phoneDay))
        // an unknown start counts
        assertTrue(ModHealthBatch.countsDayOf(total(day2 + 5 * minute, 40.0, null), phoneDay))
    }

    @Test
    fun correctionDownIsZeroAndUnknownStartFallsBackToSmallerTotal() {
        val a = total(at, 7400.0, day1)
        val b = total(at + 5 * minute, 7350.0, day1 + 1)
        assertEquals(0.0, ModHealthBatch.stepsDelta(a, b), 1e-9)
        assertEquals(150.0, ModHealthBatch.stepsDelta(b, total(at + 10 * minute, 7500.0, day1)), 1e-9)
        assertEquals(7350.0, ModHealthBatch.stepsDelta(a.copy(dayStartMs = null), b.copy(dayStartMs = null)), 1e-9)
        assertEquals(0.0, ModHealthBatch.stepsDelta(a.copy(dayStartMs = null), total(at + minute, -5.0, null)), 1e-9)
    }

    @Test
    fun stepsTotalFromARow() {
        assertEquals(total(at + 2 * minute, 4321.0, at - 10 * hour), StepsTotal.of(rows[1]))
        assertNull(StepsTotal.of(rows[0]))
        assertEquals(total(at, 12.0, null), StepsTotal.of(Sample(ModHealthBatch.KIND_STEPS_DAY, at, 12f, "")))
    }
}
