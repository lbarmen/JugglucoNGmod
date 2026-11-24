package mod.watch

import android.net.Uri
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataItemAsset
import com.google.android.gms.wearable.DataMap
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.health.ModHealthBatch
import tk.glucodata.mod.health.ModHealthBatch.Sample
import tk.glucodata.mod.health.ModHealthDataMap
import tk.glucodata.mod.watch.ModWatchIntake
import tk.glucodata.mod.watch.ModWatchIntake.Action
import tk.glucodata.mod.watch.ModWatchStore
import tk.glucodata.mod.watch.db.ModWatchSample

/**
 * JugglucoNGmod, watch block (part 5): the phone's rules of taking batches without a database - rows inserted,
 * repeats dropped (an outbox file sent again under new numbers), late heart rate merged by the counts once, items
 * kept or deleted, deleted events skipped, and the check screen's "today". Made-up values.
 */
class ModWatchIntakeTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private fun at(h: Int, m: Int, day: Int = 18) = LocalDateTime.of(2026, 11, day, h, m).atZone(zone).toInstant().toEpochMilli()
    private val received = at(12, 0)

    private fun hr(mean: Float, n: Int, min: Int, max: Int, time: Long = at(10, 0)) =
        Sample(ModHealthBatch.KIND_HR5, time, mean, ModHealthBatch.hr5Extra(n, min, max))

    /** Applies [samples] of batch [seq] to an in-memory table keyed by kind and time, as the DAO does. */
    private class Table {
        val rows = linkedMapOf<Pair<String, Long>, ModWatchSample>()
        fun apply(seq: Long, vararg samples: Sample) {
            for (s in samples) {
                when (val a = ModWatchIntake.decide(rows[s.kind to s.timeMs], s, seq, 0L)) {
                    is Action.Insert -> rows[s.kind to s.timeMs] = a.row
                    is Action.Replace -> rows[s.kind to s.timeMs] = a.row
                    Action.Drop -> Unit
                }
            }
        }
        fun hr(time: Long) = rows[ModHealthBatch.KIND_HR5 to time]!!
    }

    @Test
    fun aNewRowIsInsertedAsItCame() {
        val s = hr(72f, 50, 60, 85)
        val action = ModWatchIntake.decide(null, s, 7, received) as Action.Insert
        assertEquals(s, action.row.sample())
        assertEquals(7L, action.row.batchSeq)
        assertEquals(received, action.row.receivedAt)
    }

    @Test
    fun aRowEqualInEveryFieldIsARepeat() {
        val s = hr(72f, 50, 60, 85)
        val stored = (ModWatchIntake.decide(null, s, 7, received) as Action.Insert).row
        assertEquals(Action.Drop, ModWatchIntake.decide(stored, s, 9, received))
        val steps = Sample(ModHealthBatch.KIND_STEPS_DAY, at(10, 5), 4321f, at(0, 0).toString())
        val storedSteps = (ModWatchIntake.decide(null, steps, 7, received) as Action.Insert).row
        assertEquals(Action.Drop, ModWatchIntake.decide(storedSteps, steps, 9, received))
    }

    @Test
    fun lateHeartRateIsMergedByTheCounts() {
        val t = Table()
        t.apply(1, hr(70f, 3, 60, 80))
        t.apply(2, hr(90f, 1, 88, 92))
        assertEquals(75.0, t.hr(at(10, 0)).value, 0.0001)
        assertEquals("n=4;min=60;max=92", t.hr(at(10, 0)).extra)
        assertEquals(2L, t.hr(at(10, 0)).batchSeq)
    }

    @Test
    fun anOutboxSentAgainDoesNotDoubleTheCountEvenAfterAMerge() {
        val first = hr(70f, 3, 60, 80)
        val late = hr(90f, 1, 88, 92)
        val t = Table()
        t.apply(1, first)
        t.apply(1, first) // the same batch twice in a row (listener and drain): nothing new
        assertEquals("n=3;min=60;max=80", t.hr(at(10, 0)).extra)
        t.apply(2, late)
        // the watch sends the same file again under new numbers: both rows are known
        t.apply(3, first)
        t.apply(4, late)
        assertEquals("n=4;min=60;max=92", t.hr(at(10, 0)).extra)
        assertEquals(75.0, t.hr(at(10, 0)).value, 0.0001)
    }

    @Test
    fun aLateOneMinuteRowIsMergedLikeAFiveMinuteOne() {
        val t = Table()
        val first = hr(64f, 50, 58, 72).copy(kind = ModHealthBatch.KIND_HR1)
        val late = hr(70f, 10, 66, 75).copy(kind = ModHealthBatch.KIND_HR1)
        t.apply(1, first)
        t.apply(2, late)
        t.apply(3, first, late) // the outbox file sent again
        val row = t.rows[ModHealthBatch.KIND_HR1 to at(10, 0)]!!
        assertEquals(65.0, row.value, 0.0001)
        assertEquals("n=60;min=58;max=75", row.extra)
        assertEquals(1, t.rows.size)
    }

    @Test
    fun aOneMinuteAndAFiveMinuteRowOfOneStartAreTwoRows() {
        // an older watch app's 5-minute window and the updated app's first minute may start at the same moment
        val t = Table()
        t.apply(1, hr(70f, 300, 60, 80))
        t.apply(2, hr(90f, 60, 85, 95).copy(kind = ModHealthBatch.KIND_HR1))
        assertEquals("n=300;min=60;max=80", t.hr(at(10, 0)).extra)
        assertEquals("n=60;min=85;max=95", t.rows[ModHealthBatch.KIND_HR1 to at(10, 0)]!!.extra)
    }

    @Test
    fun otherKindsKeepTheFirstRowOfATime() {
        val t = Table()
        val b1 = Sample(ModHealthBatch.KIND_BATTERY, at(10, 0), 81f, "")
        t.apply(1, b1, b1.copy(value = 79f))
        assertEquals(81.0, t.rows[ModHealthBatch.KIND_BATTERY to at(10, 0)]!!.value, 0.0)
    }

    @Test
    fun deletedEventsAndOtherPathsAreSkipped() {
        assertTrue(ModWatchIntake.wanted(DataEvent.TYPE_CHANGED, "/mod/health/batch/12"))
        assertFalse(ModWatchIntake.wanted(DataEvent.TYPE_DELETED, "/mod/health/batch/12"))
        assertFalse(ModWatchIntake.wanted(DataEvent.TYPE_CHANGED, "/mod/health/batch/x"))
        assertFalse(ModWatchIntake.wanted(DataEvent.TYPE_CHANGED, "/mod/tick"))
        assertFalse(ModWatchIntake.wanted(DataEvent.TYPE_CHANGED, null))
    }

    @Test
    fun anItemIsDeletedOnlyWhenStoredOrJunk() {
        val batch = ModHealthBatch.forSending(5, received, listOf(hr(72f, 50, 60, 85)))
        val map = DataMap()
        ModHealthDataMap.write(batch, map)
        val decoded = ModHealthDataMap.read(map)
        var stored: ModHealthBatch? = null
        assertEquals(ModWatchIntake.Outcome.DELETE, ModWatchIntake.outcome(decoded) { stored = it; true })
        assertEquals(batch, stored)
        // the database did not open: the item stays for the next start
        assertEquals(ModWatchIntake.Outcome.KEEP, ModWatchIntake.outcome(decoded) { false })
        // a newer watch app: left for a newer phone app, never stored
        var called = false
        assertEquals(ModWatchIntake.Outcome.KEEP,
            ModWatchIntake.outcome(ModHealthBatch.Decoded.Newer(2)) { called = true; true })
        assertFalse(called)
        // broken (arrays of different lengths): never readable, deleted
        val broken = ModHealthBatch.decode(1, 5, received, arrayOf("hr5"), longArrayOf(1L, 2L), floatArrayOf(1f), arrayOf(""))
        assertEquals(ModWatchIntake.Outcome.DELETE, ModWatchIntake.outcome(broken) { true })
    }

    @Test
    fun aVersion2BatchWithOneMinuteRowsAndAVersion1BatchAreBothStored() {
        // critic 7k, C1: the watch from 7i writes version 2; watch apps before it wrote version 1
        val hr1 = hr(64f, 58, 60, 70).copy(kind = ModHealthBatch.KIND_HR1)
        for ((version, row) in listOf(2 to hr1, 1 to hr(72f, 50, 60, 85))) {
            val batch = ModHealthBatch(5, received, listOf(row), version)
            val map = DataMap()
            ModHealthDataMap.write(batch, map)
            var stored: ModHealthBatch? = null
            assertEquals(ModWatchIntake.Outcome.DELETE, ModWatchIntake.outcome(ModHealthDataMap.read(map)) { stored = it; true })
            assertEquals(listOf(row), stored!!.knownSamples())
            assertEquals(version, stored!!.version)
        }
        // a version above this phone's is kept in the Data Layer, never stored
        assertEquals(ModWatchIntake.Outcome.KEEP,
            ModWatchIntake.outcome(ModHealthBatch.Decoded.Newer(ModHealthBatch.VERSION + 1)) { true })
    }

    @Test
    fun theListenerHandsTheItemsOverAndReturnsWithoutWriting() {
        val queued = mutableListOf<Runnable>()
        val later = Executor { queued += it }
        val context = android.content.ContextWrapper(null)
        // no item, nothing queued
        ModWatchStore.receiveAsync(context, emptyList(), later)
        assertTrue(queued.isEmpty())
        // items go to our thread as one task; nothing of it ran on the caller's thread (the item is never even read)
        val item = object : DataItem {
            override fun getAssets(): Map<String, DataItemAsset> = emptyMap()
            override fun getData(): ByteArray? = throw AssertionError("read on the listener thread")
            override fun getUri(): Uri = throw AssertionError("read on the listener thread")
            override fun setData(data: ByteArray?): DataItem = this
            override fun freeze(): DataItem = this
            override fun isDataValid(): Boolean = true
        }
        ModWatchStore.receiveAsync(context, listOf(item, item), later)
        assertEquals(1, queued.size)
    }

    // ------------------------------------------------------------------ today

    private fun row(kind: String, time: Long, value: Double, extra: String = "") =
        ModWatchSample(kind = kind, at = time, value = value, extra = extra, batchSeq = 1, receivedAt = received)

    @Test
    fun todayIsTheLastTotalOfTodayTheLastHeartRateAndBattery() {
        val now = at(12, 0)
        val s = ModWatchIntake.summary(
            latestHeartRate = row(ModHealthBatch.KIND_HR5, at(11, 50), 71.6, "n=40;min=60;max=80"),
            latestSteps = row(ModHealthBatch.KIND_STEPS_DAY, at(11, 55), 5432.0, at(0, 0).toString()),
            latestBattery = row(ModHealthBatch.KIND_BATTERY, at(11, 55), 81.0, ModHealthBatch.CHARGING),
            lastBatchAt = at(11, 56), nowMs = now, zone = zone,
        )
        assertEquals(72, s.heartRate)
        assertEquals(at(11, 50), s.heartRateAt)
        assertEquals(5432, s.stepsToday)
        assertEquals(81, s.watchBattery)
        assertTrue(s.watchCharging)
        assertEquals(at(11, 56), s.lastBatchAt)
        assertFalse(s.empty)
    }

    @Test
    fun theLastHeartRateIsTheNewestOneMinuteOrFiveMinuteRow() {
        val hr1 = row(ModHealthBatch.KIND_HR1, at(11, 58), 64.4, "n=60;min=60;max=70")
        val hr5 = row(ModHealthBatch.KIND_HR5, at(11, 50), 71.6, "n=40;min=60;max=80")
        assertEquals(hr1, ModWatchIntake.newestHeartRate(hr1, hr5))
        assertEquals(hr5, ModWatchIntake.newestHeartRate(hr1.copy(at = at(11, 45)), hr5)) // an older watch app again
        val sameStart = hr1.copy(at = at(11, 50))
        assertEquals(sameStart, ModWatchIntake.newestHeartRate(sameStart, hr5)) // same start: the new kind
        assertEquals(hr5, ModWatchIntake.newestHeartRate(null, hr5))
        assertEquals(hr1, ModWatchIntake.newestHeartRate(hr1, null))
        assertNull(ModWatchIntake.newestHeartRate(null, null))
        val s = ModWatchIntake.summary(hr1, null, null, null, at(12, 0), zone)
        assertEquals(64, s.heartRate)
        assertEquals(at(11, 58), s.heartRateAt)
        // a row of another kind is never shown as heart rate
        assertNull(ModWatchIntake.summary(row(ModHealthBatch.KIND_BATTERY, at(11, 58), 64.0), null, null, null, at(12, 0), zone).heartRate)
    }

    @Test
    fun theDayEndsAtMidnightOfTheZoneGiven() {
        val now = at(0, 30, day = 19)
        val yesterdays = row(ModHealthBatch.KIND_STEPS_DAY, at(23, 50), 9000.0, at(0, 0).toString())
        assertNull(ModWatchIntake.summary(null, yesterdays, null, null, now, zone).stepsToday)
        // the same moment is still the 18th an hour west
        val west = ZoneId.of("Europe/Kaliningrad")
        assertEquals(9000, ModWatchIntake.summary(null, yesterdays, null, null, now, west).stepsToday)
        val (from, to) = ModWatchIntake.dayBounds(now, zone)
        assertEquals(at(0, 0, day = 19), from)
        assertEquals(at(0, 0, day = 20), to)
    }

    @Test
    fun aTotalOfAWatchDayStartedYesterdayIsNotToday() {
        val now = at(0, 10, day = 19)
        // sent at 00:05, but the watch has not passed its midnight: its day started on the 18th
        val late = row(ModHealthBatch.KIND_STEPS_DAY, at(0, 5, day = 19), 9100.0, at(0, 0, day = 18).toString())
        assertNull(ModWatchIntake.summary(null, late, null, null, now, zone).stepsToday)
        val fresh = row(ModHealthBatch.KIND_STEPS_DAY, at(0, 5, day = 19), 40.0, at(0, 0, day = 19).toString())
        assertEquals(40, ModWatchIntake.summary(null, fresh, null, null, now, zone).stepsToday)
        // without a day start the total counts
        assertEquals(40, ModWatchIntake.summary(null, fresh.copy(extra = ""), null, null, now, zone).stepsToday)
    }

    @Test
    fun aWatchStillInTheOldZoneAfterAFlightWestStillGivesTodaysSteps() {
        // part 5b, critic 5 N-5-4: the phone is in London already, the watch counts from Moscow's midnight, 3 hours
        // before London's (the rule of part 5 hid such a total until the watch took the new zone)
        val west = ZoneId.of("Europe/London")
        val now = LocalDateTime.of(2026, 11, 19, 10, 0).atZone(west).toInstant().toEpochMilli()
        val steps = row(ModHealthBatch.KIND_STEPS_DAY, now - 5 * 60_000L, 2500.0, at(0, 0, day = 19).toString())
        assertEquals(2500, ModWatchIntake.summary(null, steps, null, null, now, west).stepsToday)
    }

    @Test
    fun theBatteryCarriesItsTime() {
        val battery = row(ModHealthBatch.KIND_BATTERY, at(9, 40), 64.0)
        assertEquals(at(9, 40), ModWatchIntake.summary(null, null, battery, null, at(12, 0), zone).watchBatteryAt)
        assertNull(ModWatchIntake.summary(null, null, null, null, at(12, 0), zone).watchBatteryAt)
    }

    @Test
    fun anAgeIsMinutesThenHoursThenDays() {
        // part 5b, critic 5 N-5-5: never "4320 min"
        val minute = 60_000L
        assertEquals(ModWatchIntake.AgoUnit.MINUTES to 0L, ModWatchIntake.ago(-5 * minute))
        assertEquals(ModWatchIntake.AgoUnit.MINUTES to 59L, ModWatchIntake.ago(59 * minute + 59_000L))
        assertEquals(ModWatchIntake.AgoUnit.HOURS to 1L, ModWatchIntake.ago(60 * minute))
        assertEquals(ModWatchIntake.AgoUnit.HOURS to 47L, ModWatchIntake.ago(47 * 60 * minute + 59 * minute))
        assertEquals(ModWatchIntake.AgoUnit.DAYS to 2L, ModWatchIntake.ago(48 * 60 * minute))
        assertEquals(ModWatchIntake.AgoUnit.DAYS to 3L, ModWatchIntake.ago(4320 * minute))
    }

    @Test
    fun aNewerItemSeenByEveryDrainCountsOnceAndLeavesWhenStored() {
        // part 5b, critic 5 N-5-2
        var paths = emptySet<String>()
        paths = ModWatchIntake.newerPaths(paths, "/mod/health/batch/3", newer = true)
        paths = ModWatchIntake.newerPaths(paths, "/mod/health/batch/3", newer = true) // the next start's drain
        paths = ModWatchIntake.newerPaths(paths, "/mod/health/batch/4", newer = true)
        assertEquals(2, paths.size)
        // the phone app updated: the item is stored and deleted
        paths = ModWatchIntake.newerPaths(paths, "/mod/health/batch/3", newer = false)
        assertEquals(setOf("/mod/health/batch/4"), paths)
        assertEquals(paths, ModWatchIntake.newerPaths(paths, "/mod/health/batch/9", newer = false))
    }

    @Test
    fun aDrainWithoutTheWearableApiIsNoErrorAndOtherFailuresNameTheirCause() {
        // part 5b: the phone emulator has no Wearable API - Tasks.await wraps the ApiException
        val missing = ExecutionException(ApiException(Status(17)))
        assertNull(ModWatchStore.drainFailure(missing))
        assertNull(ModWatchStore.drainFailure(ApiException(Status(17))))
        assertEquals("watch drain failed: ExecutionException cause=ApiException status=8",
            ModWatchStore.drainFailure(ExecutionException(ApiException(Status(8)))))
        assertEquals("watch drain failed: ExecutionException cause=IllegalStateException",
            ModWatchStore.drainFailure(ExecutionException(IllegalStateException("x"))))
        assertEquals("watch drain failed: TimeoutException", ModWatchStore.drainFailure(TimeoutException()))
    }

    @Test
    fun nothingStoredIsEmpty() {
        val s = ModWatchIntake.summary(null, null, null, null, at(12, 0), zone)
        assertTrue(s.empty)
        assertNull(s.heartRate)
        assertFalse(s.watchCharging)
    }
}
