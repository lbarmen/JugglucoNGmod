package mod.watch

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.ByteBuffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.WearMessagePath
import tk.glucodata.mod.watch.HistoryGapFill
import tk.glucodata.mod.watch.HistoryGapFill.Gap
import tk.glucodata.mod.watch.HistoryGapFill.State
import tk.glucodata.mod.watch.PhoneBatteryStore

/** JugglucoNGmod, watch block: the history hole check of the watch - holes, the read, the request bytes, repeats. Made-up times. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HistoryGapFillTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute
    private lateinit var context: Context
    private val sent = ArrayList<ByteArray>()
    private val reads = ArrayList<Long>()
    private var stamps: List<Long> = emptyList()
    private var deliver = true

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PhoneBatteryStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        HistoryGapFill.resetForTest()
        HistoryGapFill.history = { start -> reads.add(start); stamps.filter { it >= start } }
        HistoryGapFill.sender = { _, data -> sent.add(data); deliver }
        HistoryGapFill.runner = { it.run() }
    }

    @After
    fun tearDown() = HistoryGapFill.resetForTest()

    /** One reading a minute from [fromMs] to [toMs], both included. */
    private fun everyMinute(fromMs: Long, toMs: Long) = (fromMs..toMs step minute).toList()

    private fun fromSecOf(data: ByteArray) = ByteBuffer.wrap(data).apply { get() }.long

    private val deep get() = now - HistoryGapFill.MAX_DEPTH_MS

    /** Runs decide on [gaps] at every 5-minute tick in [ticks], each request delivered; returns the minutes it sent at. */
    private fun run(gaps: (Int) -> List<Gap>, ticks: IntRange, start: State = State()): Pair<List<Int>, State> {
        var state = start
        val sentAt = ArrayList<Int>()
        for (tick in ticks) {
            val at = now + tick * 5 * minute
            val decision = HistoryGapFill.decide(gaps(tick), state, at)
            state = decision.state
            if (decision.fromSec > 0) {
                sentAt.add(tick * 5)
                state = HistoryGapFill.afterSend(state, true, at)
            }
        }
        return sentAt to state
    }

    @Test
    fun noHoleAndEmptyHistoryFindNothing() {
        assertTrue(HistoryGapFill.findGaps(emptyList(), deep, now).isEmpty())
        assertTrue(HistoryGapFill.findGaps(listOf(now - minute), deep, now).isEmpty())
        assertTrue(HistoryGapFill.findGaps(everyMinute(now - 10 * hour, now), deep, now).isEmpty())
        // 12 minutes exactly is not a hole (a Dexcom skipping two readings), 13 is
        assertTrue(HistoryGapFill.findGaps(listOf(now - 12 * minute, now), deep, now).isEmpty())
        assertEquals(1, HistoryGapFill.findGaps(listOf(now - 13 * minute, now), deep, now).size)
    }

    @Test
    fun severalHolesComeOldestFirstUnsortedInputIsFine() {
        val readings = everyMinute(now - 10 * hour, now - 8 * hour) +
            everyMinute(now - 6 * hour, now - 3 * hour) +
            everyMinute(now - 2 * hour, now)
        val gaps = HistoryGapFill.findGaps(readings.shuffled() + readings.take(5), deep, now)
        assertEquals(listOf(Gap(now - 8 * hour, now - 6 * hour), Gap(now - 3 * hour, now - 2 * hour)), gaps)
    }

    @Test
    fun edgesOfTheReadAreNotHoles() {
        assertTrue(HistoryGapFill.findGaps(everyMinute(now - hour, now - 30 * minute), deep, now).isEmpty())
        // a reading older than the read does not open a hole into it
        val old = listOf(deep - hour) + everyMinute(now - hour, now)
        assertTrue(HistoryGapFill.findGaps(old, deep, now).isEmpty())
    }

    @Test
    fun theReadStartsAtTheAnchorBetweenHalfAnHourAnd72HoursBack() {
        assertEquals(deep, HistoryGapFill.readStartMs(0L, now))
        assertEquals(now - 5 * hour - minute, HistoryGapFill.readStartMs(now - 5 * hour, now))
        assertEquals(deep, HistoryGapFill.readStartMs(now - 100 * hour, now))
        assertEquals(now - HistoryGapFill.MIN_READ_MS, HistoryGapFill.readStartMs(now - minute, now))
        // a clock set back: the anchor is "in the future"
        assertEquals(now - HistoryGapFill.MIN_READ_MS, HistoryGapFill.readStartMs(now + hour, now))
        // the anchor: the earliest hole not dropped, else the newest reading
        val gaps = listOf(Gap(now - 5 * hour, now - 4 * hour), Gap(now - 2 * hour, now - hour))
        assertEquals(now - 2 * hour, HistoryGapFill.anchorOf(gaps, setOf(now - 5 * hour), emptyList(), now))
        assertEquals(now - minute, HistoryGapFill.anchorOf(emptyList(), emptySet(), listOf(now - 3 * minute, now - minute), now))
        assertNull(HistoryGapFill.anchorOf(emptyList(), emptySet(), emptyList(), now))
    }

    @Test
    fun theRequestStartsFiveMinutesBeforeTheHoleAndNeverDeeperThan72Hours() {
        val gap = Gap(now - 5 * hour, now - 4 * hour)
        assertEquals((now - 5 * hour - 5 * minute) / 1000L, HistoryGapFill.requestFromSec(gap, now))
        assertEquals(deep / 1000L, HistoryGapFill.requestFromSec(Gap(deep + minute, deep + hour), now))
    }

    @Test
    fun theRequestHasUpstreamsBytes() {
        val fromSec = 1_799_990_000L
        val expected = ByteArray(10)
        expected[0] = 1
        for (i in 0 until 8) expected[1 + i] = (fromSec shr (56 - 8 * i)).toByte()
        expected[9] = 0
        assertArrayEquals(expected, HistoryGapFill.encodeRequest(fromSec))
        assertEquals(1.toByte(), HistoryGapFill.encodeRequest(fromSec, force = true)[9])
    }

    /**
     * Breaks when upstream changes what this class copies from WearSync2 (mod/SYNC.md): the request format and
     * version, the path, the 14-day line between a plain and a deep request (ours stay above it).
     */
    @Test
    fun upstreamsRequestFormatIsStillTheOneWeSend() {
        val source = listOf(File("src/main/java/tk/glucodata/WearSync2.kt"), File("Common/src/main/java/tk/glucodata/WearSync2.kt"))
            .first { it.isFile }.readText()
        val version = Regex("private const val VERSION = (\\d+)").find(source)?.groupValues?.get(1)
        assertEquals(HistoryGapFill.WIRE_VERSION.toString(), version)
        assertTrue(source.contains("request  (receiver→owner, SYNC2_REQ_PATH):  [u8 ver][i64 fromSec]"))
        assertTrue(source.contains("val buf = ByteBuffer.allocate(10)"))
        assertTrue(source.contains("buf.put((if (force) 1 else 0).toByte())"))
        assertTrue(source.contains("private const val BACKFILL_HORIZON_SEC = 14L * 24L * 3600L"))
        assertTrue(source.contains("fromSec <= System.currentTimeMillis() / 1000L - BACKFILL_HORIZON_SEC + 60L"))
        assertTrue(HistoryGapFill.MAX_DEPTH_MS / 1000L < 14L * 24L * 3600L - 60L)
        assertEquals("/sync2/req", WearMessagePath.SYNC2_REQ.wire)
    }

    @Test
    fun oneRequestCoversTheLaterHolesTooThreeTriesTenMinutesApartThenAllDropped() {
        val gaps = listOf(Gap(now - 5 * hour, now - 4 * hour), Gap(now - 2 * hour, now - hour))
        val (sentAt, state) = run({ gaps }, 0..8)
        assertEquals(listOf(0, 10, 20), sentAt)
        assertEquals(gaps.map { it.startMs }.toSet(), state.dead)
        assertNull(HistoryGapFill.decide(gaps, state, now + 70 * minute).gap)
    }

    @Test
    fun aPartialFillKeepsTheCountAHoleAfterTheLastRequestStartsANewChain() {
        val first = Gap(now - 5 * hour, now - 4 * hour)
        val moved = Gap(now - 4 * hour - 30 * minute, now - 4 * hour)
        // the phone filled half of the hole after the first request: still the same chain, three tries in all
        val (sentAt, state) = run({ if (it == 0) listOf(first) else listOf(moved) }, 0..6)
        assertEquals(listOf(0, 10, 20), sentAt)
        assertEquals(setOf(moved.startMs), state.dead)
        // the watch was off again later: the new hole starts after the last request - asked for at once
        val fresh = Gap(now + 40 * minute, now + 60 * minute)
        val next = HistoryGapFill.decide(listOf(moved, fresh), state, now + 65 * minute)
        assertEquals(fresh, next.gap)
        assertEquals(1, next.tries)
        assertFalse(next.wait)
    }

    @Test
    fun anUndeliveredRequestKeepsThePauseButIsNotCounted() {
        val gap = Gap(now - 5 * hour, now - 4 * hour)
        val one = HistoryGapFill.decide(listOf(gap), State(), now)
        val failed = HistoryGapFill.afterSend(one.state, false, now)
        assertEquals(0, failed.tries)
        assertTrue(HistoryGapFill.decide(listOf(gap), failed, now + 5 * minute).wait)
        val again = HistoryGapFill.decide(listOf(gap), failed, now + 10 * minute)
        assertEquals(1, again.tries)
        assertTrue(again.fromSec > 0)
        // old dropped holes are forgotten; a clock set back does not block the next request
        val old = State(dead = setOf(deep - hour, gap.startMs))
        assertEquals(setOf(gap.startMs), HistoryGapFill.decide(emptyList(), old, now).state.dead)
        val chain = State(gap.startMs - 5 * minute, 1, now + hour)
        val back = HistoryGapFill.decide(listOf(gap), chain, now)
        assertFalse(back.wait)
        assertEquals(2, back.tries)
    }

    @Test
    fun aTickScansSendsCountsOnlyDeliveredAndKeepsTheCountAcrossARestart() {
        stamps = everyMinute(now - 20 * hour, now - 3 * hour) + everyMinute(now - 10 * minute, now)
        deliver = false
        HistoryGapFill.onTick(context, now)
        assertEquals(1, sent.size)
        assertEquals((now - 3 * hour - 5 * minute) / 1000L, fromSecOf(sent[0]))
        assertEquals(0, HistoryGapFill.load(context).tries)
        // a second tick within a minute is ignored
        HistoryGapFill.onTick(context, now + 30_000L)
        assertEquals(1, sent.size)
        // the process restarts: the pause is in the preferences, 5 minutes later is too soon to ask again
        HistoryGapFill.resetForTest()
        deliver = true
        HistoryGapFill.onTick(context, now + 5 * minute)
        assertEquals(1, sent.size)
        HistoryGapFill.onTick(context, now + 10 * minute)
        assertEquals(2, sent.size)
        assertEquals(1, HistoryGapFill.load(context).tries)
        // the reads start at the hole, not 72 hours back
        assertEquals(listOf(deep, now - 3 * hour - minute, now - 3 * hour - minute), reads)
        // the phone filled the hole: nothing more is sent, the chain is cleared, the anchor is the newest reading
        stamps = everyMinute(now - 20 * hour, now + 15 * minute)
        HistoryGapFill.onTick(context, now + 15 * minute)
        assertEquals(2, sent.size)
        assertEquals(State(anchorMs = now + 15 * minute), HistoryGapFill.load(context))
        // and the next tick reads only the last half hour
        HistoryGapFill.onTick(context, now + 20 * minute)
        assertEquals(now + 20 * minute - HistoryGapFill.MIN_READ_MS, reads.last())
    }

    @Test
    fun aHoleAfterTwoAndAHalfDaysAwayIsSeen() {
        // the last scan before the watch went away anchored at its newest reading, 60 hours ago
        HistoryGapFill.save(context, State(anchorMs = now - 60 * hour))
        stamps = everyMinute(now - 70 * hour, now - 60 * hour) + everyMinute(now - 10 * minute, now)
        HistoryGapFill.onTick(context, now)
        assertEquals(1, sent.size)
        assertEquals((now - 60 * hour - 5 * minute) / 1000L, fromSecOf(sent[0]))
        assertEquals(now - 60 * hour - minute, reads.single())
    }
}
