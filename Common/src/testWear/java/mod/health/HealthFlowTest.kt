package mod.health

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.mod.health.HealthConfig
import tk.glucodata.mod.health.HealthCore
import tk.glucodata.mod.health.HealthHandOver
import tk.glucodata.mod.health.HealthIntake
import tk.glucodata.mod.health.HealthIntake.HeartRate
import tk.glucodata.mod.health.HealthIntake.StepsDay
import tk.glucodata.mod.health.HealthSender
import tk.glucodata.mod.health.HealthSource
import tk.glucodata.mod.health.HealthSources
import tk.glucodata.mod.health.HealthState
import tk.glucodata.mod.health.ModHealthBatch
import tk.glucodata.mod.health.ModHealthBatch.Sample

/**
 * JugglucoNGmod, watch block (part 4): deliveries into the queue, batches out of it (numbers, paths, failures) and
 * the hand-over on a tick, with a fake source and a fake Data Layer. Made-up values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthFlowTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = 1_800_000_000_000L // on a 5-minute boundary
    private val minute = 60_000L
    private var now = t0
    private lateinit var context: Context
    private lateinit var dir: File

    /** Keeps what was put; fails the puts whose number is in [failOn]. */
    private class FakeSink(val failOn: Set<Int> = emptySet()) : HealthSender.Sink {
        val batches = mutableListOf<ModHealthBatch>()
        var calls = 0
        override fun put(batch: ModHealthBatch) {
            calls++
            if (calls in failOn) throw IOException("not accepted")
            batches += batch
        }
        fun rows(kind: String) = batches.flatMap { it.samples }.filter { it.kind == kind }
    }

    private open inner class FakeSource : HealthSource {
        var flushes = 0
        var maintainedAt = 0L
        override fun flush(context: Context): Boolean {
            flushes++
            return true
        }
        override fun maintain(context: Context, nowMs: Long) {
            maintainedAt = nowMs
        }
        override fun statusFields(context: Context) = "perm=hr,act;reg=1"
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(HealthState.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        dir = tmp.newFolder("q")
        HealthSources.current = null
    }

    private fun core() = HealthCore(context, dir) { now }

    @Test
    fun deliveryQueuesStepsAsTheyAreAndKeepsTheOpenWindow() {
        val core = core()
        now = t0 + 3 * minute
        val dayStart = t0 - 9 * 60 * minute
        val queued = HealthIntake.deliverNow(
            core,
            listOf(HeartRate(t0 + 2 * minute + 10_000, 64f), HeartRate(t0 + 2 * minute + 40_000, 68f)),
            listOf(StepsDay(t0 + 3 * minute, 4321, dayStart), StepsDay(t0, -1, dayStart), StepsDay(dayStart - 1, 10, dayStart)),
            now,
        )
        assertEquals(1, queued) // the steps row; the window is still open, the broken counts skipped
        assertEquals(1, core.openHeartRateWindows)
        assertEquals(now, core.state.lastDataAt)
        assertEquals(1L, core.dataEvents())
        val rows = core.buffer.takeOutbox(now).flatMap { core.buffer.read(it) }
        assertEquals(listOf(Sample(ModHealthBatch.KIND_STEPS_DAY, t0 + 3 * minute, 4321f, dayStart.toString())), rows)
    }

    @Test
    fun aDeliveryClosesTheMinutesItCoversAtOnce() {
        // Health Services hands over 2.5 minutes of readings, one a second, right after the newest one
        val core = core()
        val readings = (0 until 150).map { HeartRate(t0 + it * 1_000L, if (it < 60) 60f else 70f) }
        val queued = HealthIntake.deliverNow(core, readings, emptyList(), t0 + 150_000)
        assertEquals(2, queued) // t0 and t0+1 min; t0+2 min waits for the next delivery
        assertEquals(1, core.openHeartRateWindows)
        val rows = core.buffer.takeOutbox(now).flatMap { core.buffer.read(it) }
        assertEquals(
            listOf(
                Sample(ModHealthBatch.KIND_HR1, t0, 60f, "n=60;min=60;max=60"),
                Sample(ModHealthBatch.KIND_HR1, t0 + minute, 70f, "n=60;min=70;max=70"),
            ),
            rows,
        )
    }

    @Test
    fun aReadingStampedInTheFutureClosesNothingEarly() {
        val core = core()
        val queued = HealthIntake.deliverNow(
            core, listOf(HeartRate(t0 + 10_000, 60f), HeartRate(t0 + 10 * minute, 70f)), emptyList(), t0 + 20_000,
        )
        assertEquals(0, queued)
        assertEquals(2, core.openHeartRateWindows)
    }

    @Test
    fun aWindowNoDeliveryCoveredClosesTwoMinutesAfterItsEnd() {
        val core = core()
        // the readings stop (watch off the wrist): the last window is not covered by a later reading
        HealthIntake.deliverNow(core, listOf(HeartRate(t0 + minute, 70f)), emptyList(), t0 + 3 * minute)
        assertEquals(1, core.openHeartRateWindows)
        val dayStart = t0 - 9 * 60 * minute
        HealthIntake.deliverNow(core, emptyList(), listOf(StepsDay(t0 + 4 * minute, 4321, dayStart)), t0 + 4 * minute)
        val rows = core.buffer.takeOutbox(now).flatMap { core.buffer.read(it) }
        assertEquals(
            listOf(
                Sample(ModHealthBatch.KIND_HR1, t0 + minute, 70f, "n=1;min=70;max=70"),
                Sample(ModHealthBatch.KIND_STEPS_DAY, t0 + 4 * minute, 4321f, dayStart.toString()),
            ),
            rows,
        )
        assertEquals(0, core.openHeartRateWindows)
    }

    @Test
    fun openWindowsSurviveAProcessRestart() {
        HealthIntake.deliverNow(core(), listOf(HeartRate(t0 + minute, 60f)), emptyList(), t0 + minute)
        val restarted = core()
        HealthIntake.deliverNow(restarted, listOf(HeartRate(t0 + minute + 30_000, 80f)), emptyList(), t0 + minute + 30_000)
        restarted.closeHeartRate(t0 + 6 * minute, HealthConfig.HR_GRACE_REFRESH_MS)
        val rows = restarted.buffer.takeOutbox(now).flatMap { restarted.buffer.read(it) }
        assertEquals(listOf(Sample(ModHealthBatch.KIND_HR1, t0 + minute, 70f, "n=2;min=60;max=80")), rows)
    }

    @Test
    fun openFiveMinuteWindowsOfAnOlderAppGoOnceAsHr5() {
        // what a watch app before Watch 7i kept under the old key: one open 5-minute window, 2 readings
        HealthState(context).hrBucketsOld5 = "$t0:130.0:2:60.0:70.0"
        val core = core()
        assertEquals("", core.state.hrBucketsOld5)
        assertEquals(0, core.openHeartRateWindows) // never read as a 1-minute window
        val queued = core.buffer.count()
        assertEquals(1, queued)
        core() // a second start queues nothing more
        assertEquals(queued, core.buffer.count())
        val rows = core.buffer.takeOutbox(now).flatMap { core.buffer.read(it) }
        assertEquals(listOf(Sample(ModHealthBatch.KIND_HR5, t0, 65f, "n=2;min=60;max=70")), rows)
    }

    @Test
    fun batchNumbersGrowAndSurviveAProcessRestart() {
        val sink = FakeSink()
        val first = core()
        first.buffer.append(listOf(Sample(ModHealthBatch.KIND_HR5, t0, 66f, "n=4;min=64;max=68")))
        HealthSender.send(first, null, sink, t0)
        assertEquals(1, sink.batches.size)
        assertEquals(1L, sink.batches[0].seq)
        assertEquals("/mod/health/batch/1", sink.batches[0].path)
        assertEquals(1L, HealthState(context).seq)

        val restarted = core()
        restarted.buffer.append(listOf(Sample(ModHealthBatch.KIND_HR5, t0 + 5 * minute, 67f, "n=4;min=65;max=69")))
        HealthSender.send(restarted, null, sink, t0 + 10 * minute)
        assertEquals(2L, sink.batches[1].seq)
        assertEquals("/mod/health/batch/2", sink.batches[1].path)
    }

    @Test
    fun failedPutKeepsTheFileAndTheNextSendDeliversIt() {
        val core = core()
        val hr = Sample(ModHealthBatch.KIND_HR5, t0, 66f, "n=4;min=64;max=68")
        core.buffer.append(listOf(hr))
        val failing = FakeSink(failOn = setOf(1))
        val r1 = HealthSender.send(core, null, failing, t0)
        assertTrue(r1.failed)
        assertTrue(core.buffer.hasOutbox())
        assertEquals("IOException", core.state.lastError)
        assertEquals(0L, core.state.lastSentAt)

        val good = FakeSink()
        val r2 = HealthSender.send(core, null, good, t0 + minute)
        assertFalse(r2.failed)
        assertEquals(listOf(hr), good.rows(ModHealthBatch.KIND_HR5))
        assertFalse(core.buffer.hasOutbox())
        assertEquals(0, core.buffer.count())
        assertEquals("", core.state.lastError)
        assertEquals(t0 + minute, core.state.lastSentAt)
    }

    @Test
    fun bigQueueIsCutIntoBatchesInOrderAndForeignKindsNeverGo() {
        val core = core()
        val means = (0 until 200).map { Sample(ModHealthBatch.KIND_HR5, t0 + it * 5 * minute, 60f + it % 30, "n=30;min=55;max=99") }
        core.buffer.append(means + Sample("floors_day", t0, 3f, ""))
        val sink = FakeSink()
        val r = HealthSender.send(core, null, sink, t0, maxBytes = 2_000)
        assertTrue(r.items > 1)
        assertEquals(means, sink.rows(ModHealthBatch.KIND_HR5))
        assertTrue(sink.batches.flatMap { it.samples }.all { it.kind in ModHealthBatch.KINDS })
        assertEquals(sink.batches.indices.map { it + 1L }, sink.batches.map { it.seq })
    }

    @Test
    fun statusRowFirstTimeThenEveryThirtyMinutes() {
        val core = core()
        val source = FakeSource()
        val sink = FakeSink()
        HealthSender.send(core, source, sink, t0)
        val status = sink.rows(ModHealthBatch.KIND_STATUS)
        assertEquals(1, status.size)
        assertTrue(status[0].extra.contains("sdk=34;perm=hr,act;reg=1;data=-"))
        HealthSender.send(core, source, sink, t0 + 10 * minute)
        assertEquals(1, sink.rows(ModHealthBatch.KIND_STATUS).size)
        HealthSender.send(core, source, sink, t0 + 30 * minute)
        assertEquals(2, sink.rows(ModHealthBatch.KIND_STATUS).size)
        HealthSender.send(core, source, sink, t0 + 31 * minute, forceStatus = true)
        assertEquals(3, sink.rows(ModHealthBatch.KIND_STATUS).size)
    }

    @Test
    fun deliveryDoesNotSendWithinAMinuteOfTheLastAttempt() {
        assertEquals(60_000L, HealthConfig.SEND_AFTER_DATA_GAP_MS)
        val core = core()
        val sink = FakeSink()
        core.buffer.append(listOf(Sample(ModHealthBatch.KIND_HR1, t0, 66f, "n=1;min=66;max=66")))
        HealthSender.send(core, null, sink, t0)
        val items = sink.batches.size
        core.buffer.append(listOf(Sample(ModHealthBatch.KIND_HR1, t0 + minute, 67f, "n=1;min=67;max=67")))
        assertNull(HealthSender.sendIfDue(core, null, sink, t0 + minute - 1))
        assertEquals(items, sink.batches.size)
        assertTrue(HealthSender.sendIfDue(core, null, sink, t0 + minute)!!.items > 0)
    }

    @Test
    fun theMinuteRuleOfASendAfterADelivery() {
        assertTrue(HealthSender.dueAfterData(lastMs = 0L, nowMs = t0)) // nothing went yet
        assertFalse(HealthSender.dueAfterData(lastMs = t0, nowMs = t0))
        assertFalse(HealthSender.dueAfterData(lastMs = t0, nowMs = t0 + 59_999))
        assertTrue(HealthSender.dueAfterData(lastMs = t0, nowMs = t0 + minute))
        assertTrue(HealthSender.dueAfterData(lastMs = t0, nowMs = t0 - minute)) // the clock went back
    }

    @Test
    fun eachDeliverySendsTheClosedMinutesAtMostOnceAMinute() {
        val core = core()
        val sink = FakeSink()
        fun deliverAt(atMs: Long, vararg readings: Long) {
            now = atMs
            HealthIntake.deliver(core, readings.map { HeartRate(it, 70f) }, emptyList()) { sink }
            HealthCore.executor.submit {}.get(10, TimeUnit.SECONDS) // the send queued by the delivery is done
        }
        // a batch every 1.5-3.5 minutes (watch log 19.11.2026)
        deliverAt(t0 + 90_000, t0 + 10_000, t0 + minute)
        assertEquals(listOf(t0), sink.rows(ModHealthBatch.KIND_HR1).map { it.timeMs })
        assertEquals(t0 + 90_000, core.state.lastSentAt)
        val afterFirst = sink.batches.size
        // 40 s later: the minute it closes is queued, not sent
        deliverAt(t0 + 2 * minute + 10_000, t0 + minute + 10_000, t0 + 2 * minute)
        assertEquals(afterFirst, sink.batches.size)
        assertTrue(core.buffer.count() > 0)
        // 2 minutes after the first send: the waiting minute goes with this one's
        deliverAt(t0 + 3 * minute + 30_000, t0 + 2 * minute + 10_000, t0 + 3 * minute)
        assertEquals(listOf(t0, t0 + minute, t0 + 2 * minute), sink.rows(ModHealthBatch.KIND_HR1).map { it.timeMs })
        assertEquals(t0 + 3 * minute + 30_000, core.state.lastSentAt)
        assertEquals(0, core.buffer.count())
    }

    @Test
    fun tickFlushesWaitsForTheDataClosesTheWindowWithFiveSecondsGraceAndSends() {
        val core = core()
        HealthIntake.deliverNow(core, listOf(HeartRate(t0 + 4 * minute + 10_000, 60f)), emptyList(), t0 + 4 * minute + 10_000)
        now = t0 + 5 * minute + HealthConfig.HR_GRACE_REFRESH_MS
        val source = object : FakeSource() {
            override fun flush(context: Context): Boolean {
                super.flush(context)
                // Health Services answers the flush a little later, from its own thread.
                Thread {
                    Thread.sleep(100)
                    HealthIntake.deliverNow(core, listOf(HeartRate(t0 + 4 * minute + 50_000, 70f)), emptyList(), now)
                }.start()
                return true
            }
        }
        val sink = FakeSink()
        val r = HealthHandOver.run(core, source, sink, flushWaitMs = 5_000)
        assertFalse(r.failed)
        assertEquals(1, source.flushes)
        assertEquals(now, source.maintainedAt)
        assertEquals(listOf(Sample(ModHealthBatch.KIND_HR1, t0 + 4 * minute, 65f, "n=2;min=60;max=70")), sink.rows(ModHealthBatch.KIND_HR1))
        assertTrue(sink.rows(ModHealthBatch.KIND_HR5).isEmpty()) // the watch makes no 5-minute rows any more
        assertEquals(0, core.openHeartRateWindows)
    }

    @Test
    fun aTickRightAfterADeliverySentEverythingOnlyChecksTheSubscription() {
        // critic 7k, N3: no flush and no batch of one or two rows within a minute of a good send with an empty queue
        val core = core()
        val sink = FakeSink()
        core.buffer.append(listOf(Sample(ModHealthBatch.KIND_HR1, t0, 66f, "n=1;min=66;max=66")))
        HealthSender.send(core, null, sink, t0)
        val items = sink.batches.size
        val source = FakeSource()
        now = t0 + 30_000
        HealthHandOver.run(core, source, sink, flushWaitMs = 200)
        assertEquals(0, source.flushes)
        assertEquals(now, source.maintainedAt)
        assertEquals(items, sink.batches.size)
        // "send now" always runs
        HealthHandOver.run(core, source, sink, flushWaitMs = 200, forceStatus = true)
        assertEquals(1, source.flushes)
        // something waits in the queue: the tick runs
        val again = FakeSource()
        now = t0 + 40_000
        core.state.lastSentAt = t0
        core.buffer.append(listOf(Sample(ModHealthBatch.KIND_HR1, t0 + minute, 67f, "n=1;min=67;max=67")))
        HealthHandOver.run(core, again, sink, flushWaitMs = 200)
        assertEquals(1, again.flushes)
        // a minute after the send: the tick runs as before
        val later = FakeSource()
        core.state.lastSentAt = t0
        now = t0 + minute
        HealthHandOver.run(core, later, sink, flushWaitMs = 200)
        assertEquals(1, later.flushes)
    }

    @Test
    fun tickWithoutDataWaitsAtMostTheFlushWaitAndStillSends() {
        val core = core()
        core.buffer.append(listOf(Sample(ModHealthBatch.KIND_STEPS_DAY, t0, 1234f, (t0 - 60 * minute).toString())))
        val sink = FakeSink()
        val started = System.nanoTime()
        HealthHandOver.run(core, FakeSource(), sink, flushWaitMs = 200)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $tookMs ms", tookMs in 150..3_000)
        assertEquals(1, sink.rows(ModHealthBatch.KIND_STEPS_DAY).size)
    }

    @Test
    fun failedFlushDoesNotWait() {
        val core = core()
        val source = object : FakeSource() {
            override fun flush(context: Context): Boolean = throw IllegalStateException("no service")
        }
        val started = System.nanoTime()
        HealthHandOver.run(core, source, FakeSink(), flushWaitMs = 5_000)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
    }

    @Test
    fun noSourceNoHandOver() {
        HealthSources.current = null
        HealthHandOver.onTick(context) // nothing registered: returns at once, no Data Layer
        assertFalse(HealthHandOver.sendNow(context))
        assertEquals(0L, HealthState(context).seq)
    }

    @Test
    fun sendNowOnOurThreadGetsTheFlushAnswerThroughDeliverAtOnce() {
        // Critic 4a, finding 1: the hand-over waits on HealthCore.executor; the answer to its flush comes through
        // HealthIntake.deliver from Health Services' own thread and must not queue behind that wait.
        val core = core()
        val sink = FakeSink()
        HealthIntake.deliver(core, listOf(HeartRate(t0 + 4 * minute + 10_000, 60f)), emptyList()) { sink }
        HealthCore.executor.submit {}.get(10, TimeUnit.SECONDS) // its send went at t0, 5 minutes before the tick
        now = t0 + 5 * minute + HealthConfig.HR_GRACE_REFRESH_MS
        val source = object : FakeSource() {
            override fun flush(context: Context): Boolean {
                super.flush(context)
                Thread {
                    Thread.sleep(100)
                    HealthIntake.deliver(core, listOf(HeartRate(t0 + 4 * minute + 50_000, 70f)), emptyList()) { sink }
                }.start()
                return true
            }
        }
        val started = System.nanoTime()
        val r = HealthHandOver.submit(core, source, sink, flushWaitMs = 5_000).get(10, TimeUnit.SECONDS)
        val tookMs = (System.nanoTime() - started) / 1_000_000
        HealthCore.executor.submit {}.get(10, TimeUnit.SECONDS) // the send queued by the delivery is done too
        assertFalse(r.failed)
        assertTrue("took $tookMs ms", tookMs < 3_000)
        assertEquals(listOf(Sample(ModHealthBatch.KIND_HR1, t0 + 4 * minute, 65f, "n=2;min=60;max=70")), sink.rows(ModHealthBatch.KIND_HR1))
        assertEquals(0L, core.state.hrLate)
    }

    @Test
    fun deliveryIsOnDiskBeforeItReturnsEvenWhileOurThreadIsBusy() {
        // Critic 4a, finding 2: the service may be stopped right after its callback - the data must be written by then.
        val core = core()
        val gate = CountDownLatch(1)
        HealthCore.executor.execute { gate.await(5, TimeUnit.SECONDS) }
        try {
            val worker = Thread {
                HealthIntake.deliver(core, emptyList(), listOf(StepsDay(t0, 1234, t0 - 60 * minute))) { FakeSink() }
            }
            worker.start()
            worker.join(5_000)
            assertEquals(1, core.buffer.count())
            assertEquals(1L, core.dataEvents())
        } finally {
            gate.countDown()
            HealthCore.executor.submit {}.get(10, TimeUnit.SECONDS)
        }
    }

    @Test
    fun lateReadingsAreCountedAndShownInTheStatus() {
        val core = core()
        HealthIntake.deliverNow(core, listOf(HeartRate(t0 + minute, 60f)), emptyList(), t0 + 8 * minute)
        // two more readings of the minute already closed and queued: a second hr1 row of that start (the phone merges it)
        HealthIntake.deliverNow(core, listOf(HeartRate(t0 + minute + 10_000, 62f), HeartRate(t0 + minute + 20_000, 64f)), emptyList(), t0 + 9 * minute)
        assertEquals(2L, core.state.hrLate)
        val rows = core.buffer.takeOutbox(now).flatMap { core.buffer.read(it) }
        assertEquals(
            listOf(
                Sample(ModHealthBatch.KIND_HR1, t0 + minute, 60f, "n=1;min=60;max=60"),
                Sample(ModHealthBatch.KIND_HR1, t0 + minute, 63f, "n=2;min=62;max=64"),
            ),
            rows,
        )
        assertEquals(2L, core().state.hrLate) // kept across a restart
        val sink = FakeSink()
        HealthSender.send(core, FakeSource(), sink, t0 + 9 * minute)
        assertTrue(sink.rows(ModHealthBatch.KIND_STATUS).single().extra.contains(";late=2;"))
    }

    @Test
    fun onlyTheSendNowButtonAsksTheDataLayerForUrgent() {
        // owner's decision 20.11: routine batches wait for the system's batching; "Send now" alone is urgent
        fun source(path: String) =
            listOf(File("src/$path"), File("Common/src/$path")).first { it.isFile }.readText()
        val sender = source("wear/java/tk/glucodata/mod/health/HealthSender.kt")
        assertTrue(sender.contains("fun dataClient(context: Context, urgent: Boolean = false)"))
        assertEquals(1, Regex("""setUrgent\(\)""").findAll(sender).count())
        assertTrue(sender.contains("if (urgent) request.setUrgent()"))
        val handOver = source("wear/java/tk/glucodata/mod/health/HealthHandOver.kt")
        assertTrue(handOver.contains("HealthSender.dataClient(core.context, urgent = forceStatus)"))
        // forceStatus = true comes from the button only; a tick and its fallback pass false
        assertEquals(1, Regex("""safeRun\(app, source, forceStatus = true\)""").findAll(handOver).count())
        val intake = source("wear/java/tk/glucodata/mod/health/HealthIntake.kt")
        assertFalse(intake.contains("urgent"))
    }
}
