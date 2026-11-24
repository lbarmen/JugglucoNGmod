package mod.watch

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.mod.health.ModHealthBatch
import tk.glucodata.mod.health.ModHealthBatch.Sample
import tk.glucodata.mod.watch.db.ModWatchDao
import tk.glucodata.mod.watch.db.ModWatchDatabase

/**
 * JugglucoNGmod, watch block (part 5): our database on Robolectric, in memory - a batch stored whole, the same batch
 * twice (listener and drain) changes nothing, the same number with another send time (the watch app reinstalled) is
 * taken, late heart rate merged once, "today" read back. Made-up values. On Windows upstream's Room tests fail with
 * "Illegal connection pointer"; if these do the same, GitHub (Linux) checks them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModWatchDaoTest {

    private lateinit var db: ModWatchDatabase
    private lateinit var dao: ModWatchDao
    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ModWatchDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.dao()
    }

    @After
    fun tearDown() = db.close()

    private fun hr(mean: Float, n: Int, min: Int, max: Int, time: Long = t0) =
        Sample(ModHealthBatch.KIND_HR5, time, mean, ModHealthBatch.hr5Extra(n, min, max))

    private fun batch(seq: Long, sentAt: Long, vararg samples: Sample) = ModHealthBatch.forSending(seq, sentAt, samples.toList())

    @Test
    fun aBatchIsStoredWholeAndTheSameBatchAgainChangesNothing() {
        val b = batch(1, t0 + minute, hr(70f, 3, 60, 80), Sample(ModHealthBatch.KIND_STEPS_DAY, t0, 1200f, "0"),
            Sample(ModHealthBatch.KIND_BATTERY, t0, 77f, ""))
        val first = dao.store(b, t0 + 2 * minute)
        assertFalse(first.repeat)
        assertEquals(3, first.inserted)
        val again = dao.store(b, t0 + 3 * minute)
        assertTrue(again.repeat)
        assertEquals(3, dao.count())
        assertEquals("n=3;min=60;max=80", dao.latest(ModHealthBatch.KIND_HR5)!!.extra)
        assertEquals(t0 + 2 * minute, dao.lastReceivedAt())
    }

    @Test
    fun theSameNumberWithAnotherSendTimeIsTaken() {
        dao.store(batch(1, t0, hr(70f, 3, 60, 80)), t0)
        val reinstalled = dao.store(batch(1, t0 + 60 * minute, hr(65f, 10, 55, 75, time = t0 + 60 * minute)), t0 + 61 * minute)
        assertFalse(reinstalled.repeat)
        assertEquals(1, reinstalled.inserted)
        assertEquals(2, dao.count())
    }

    @Test
    fun lateHeartRateMergesOnceAndAResentFileIsNotCountedAgain() {
        val first = hr(70f, 3, 60, 80)
        val late = hr(90f, 1, 88, 92)
        dao.store(batch(1, t0, first), t0)
        assertEquals(1, dao.store(batch(2, t0 + minute, late), t0 + minute).merged)
        // the outbox file sent again under new numbers
        val resent = dao.store(batch(3, t0 + 2 * minute, first, late), t0 + 2 * minute)
        assertFalse(resent.repeat)
        assertEquals(0, resent.inserted + resent.merged)
        assertEquals(2, resent.dropped)
        val row = dao.find(ModHealthBatch.KIND_HR5, t0)!!
        assertEquals(75.0, row.value, 0.0001)
        assertEquals("n=4;min=60;max=92", row.extra)
        assertEquals(1, dao.count())
    }

    @Test
    fun oneMinuteRowsAreStoredBesideFiveMinuteOnesAndLateOnesMerge() {
        val hr1 = hr(64f, 50, 58, 72).copy(kind = ModHealthBatch.KIND_HR1)
        val late = hr(70f, 10, 66, 75).copy(kind = ModHealthBatch.KIND_HR1)
        val stored = dao.store(batch(1, t0 + minute, hr(70f, 3, 60, 80), hr1), t0 + minute)
        assertEquals(2, stored.inserted) // hr5 and hr1 of the same start: two rows
        assertEquals(1, dao.store(batch(2, t0 + 2 * minute, late), t0 + 2 * minute).merged)
        assertEquals(0, dao.store(batch(3, t0 + 3 * minute, hr1, late), t0 + 3 * minute).let { it.inserted + it.merged })
        val row = dao.find(ModHealthBatch.KIND_HR1, t0)!!
        assertEquals(65.0, row.value, 0.0001)
        assertEquals("n=60;min=58;max=75", row.extra)
        assertEquals("n=3;min=60;max=80", dao.find(ModHealthBatch.KIND_HR5, t0)!!.extra)
        dao.store(batch(4, t0 + 4 * minute, hr(66f, 60, 60, 72, time = t0 + 3 * minute).copy(kind = ModHealthBatch.KIND_HR1)), t0 + 4 * minute)
        assertEquals(t0 + 3 * minute, dao.latest(ModHealthBatch.KIND_HR1)!!.at)
        assertEquals(3, dao.count())
    }

    @Test
    fun theNewestOfAKindAndOfAWindow() {
        dao.store(batch(1, t0, Sample(ModHealthBatch.KIND_STEPS_DAY, t0, 1000f, "0"),
            Sample(ModHealthBatch.KIND_STEPS_DAY, t0 + 10 * minute, 1500f, "0"),
            Sample(ModHealthBatch.KIND_STEPS_DAY, t0 + 20 * minute, 2000f, "0")), t0)
        assertEquals(2000.0, dao.latest(ModHealthBatch.KIND_STEPS_DAY)!!.value, 0.0)
        assertEquals(1500.0, dao.latestBetween(ModHealthBatch.KIND_STEPS_DAY, t0, t0 + 20 * minute)!!.value, 0.0)
        assertNull(dao.latestBetween(ModHealthBatch.KIND_STEPS_DAY, t0 + 30 * minute, t0 + 40 * minute))
        assertNull(dao.latest(ModHealthBatch.KIND_HR5))
    }

    @Test
    fun anEmptyDatabaseHasNoLastBatch() {
        assertNull(dao.lastReceivedAt())
        assertEquals(0, dao.count())
    }
}
