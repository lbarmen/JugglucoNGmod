package mod.watch

import androidx.work.WorkInfo.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.WatchTickMemory
import tk.glucodata.mod.watch.WatchTickWorker
import tk.glucodata.mod.watch.WatchTicker

/**
 * JugglucoNGmod, watch block: when the phone ticks the watch - cycle step, what a start or an appearing watch does to
 * the chain, the battery and "watch appeared" extra ticks. Pure rules, no WorkManager or Data Layer. Made-up values.
 */
class WatchTickerTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun theNextCycleStepIsFiveMinutesWithAWatchAndAnHourWithout() {
        assertEquals(5 * minute, WatchTickWorker.nextDelayMs(watchFound = true))
        assertEquals(60 * minute, WatchTickWorker.nextDelayMs(watchFound = false))
        assertEquals("mod-watch-tick", WatchTickWorker.WORK)
    }

    @Test
    fun onlyTheLongWaitCarriesTheTagTheStartReadsBack() {
        assertTrue(WatchTickWorker.TAG_LONG in WatchTickWorker.request(WatchTickWorker.NO_WATCH_PERIOD_MS).tags)
        assertFalse(WatchTickWorker.TAG_LONG in WatchTickWorker.request(WatchTickWorker.PERIOD_MS).tags)
        // and what WorkManager reports back with that tag reads as "waits long"
        assertEquals(true, WatchTickWorker.waitsLong(listOf(State.ENQUEUED to
            WatchTickWorker.request(WatchTickWorker.NO_WATCH_PERIOD_MS).tags)))
    }

    @Test
    fun aBatteryMoveOfFivePointsSendsAnExtraTick() {
        val longAgo = now - 10 * minute
        assertTrue(WatchTicker.shouldSendForBattery(55, 60, now, longAgo))
        assertTrue(WatchTicker.shouldSendForBattery(65, 60, now, longAgo))
        assertTrue(WatchTicker.shouldSendForBattery(30, 60, now, longAgo))
        assertTrue(WatchTicker.shouldSendForBattery(0, 5, now, longAgo))
    }

    @Test
    fun aSmallerMoveSendsNothing() {
        val longAgo = now - 10 * minute
        for (percent in 56..64) assertFalse("percent $percent", WatchTicker.shouldSendForBattery(percent, 60, now, longAgo))
    }

    @Test
    fun noTickReachedAWatchYetSendsNothing() {
        assertFalse(WatchTicker.shouldSendForBattery(10, null, now, 0L))
        assertFalse(WatchTicker.shouldSendForBattery(90, null, now, now - 10 * minute))
    }

    @Test
    fun notMoreThanOnceAMinute() {
        assertFalse(WatchTicker.shouldSendForBattery(40, 60, now, now))
        assertFalse(WatchTicker.shouldSendForBattery(40, 60, now, now - minute + 1))
        assertTrue(WatchTicker.shouldSendForBattery(40, 60, now, now - minute))
        assertTrue(WatchTicker.shouldSendForBattery(40, 60, now, now - 3 * minute))
        // the phone clock set back: not held for the gap
        assertTrue(WatchTicker.shouldSendForBattery(40, 60, now, now + 5 * minute))
    }

    @Test
    fun aStepCancelledWhileItRanQueuesNoNextStep() {
        // REPLACE from outside cancels a running step; doWork still ends and must not hang a tail behind the new chain
        assertNull(WatchTickWorker.nextStepDelayMs(watchFound = true, stopped = true))
        assertNull(WatchTickWorker.nextStepDelayMs(watchFound = false, stopped = true))
        assertEquals(5 * minute, WatchTickWorker.nextStepDelayMs(watchFound = true, stopped = false))
        assertEquals(60 * minute, WatchTickWorker.nextStepDelayMs(watchFound = false, stopped = false))
    }

    private val long = setOf(WatchTickWorker.TAG_LONG, "some.Worker")
    private val short = setOf("some.Worker")

    @Test
    fun whetherTheChainWaitsLongIsReadFromItsWaitingSteps() {
        assertNull(WatchTickWorker.waitsLong(emptyList()))
        // a step that runs now (it woke the process) queues the next itself: nothing is waiting yet
        assertNull(WatchTickWorker.waitsLong(listOf(State.RUNNING to short)))
        assertNull(WatchTickWorker.waitsLong(listOf(State.SUCCEEDED to long, State.CANCELLED to long)))
        assertEquals(true, WatchTickWorker.waitsLong(listOf(State.ENQUEUED to long)))
        assertEquals(false, WatchTickWorker.waitsLong(listOf(State.ENQUEUED to short)))
        assertEquals(false, WatchTickWorker.waitsLong(listOf(State.SUCCEEDED to long, State.ENQUEUED to short)))
        // a chain with a second step behind the first (what a tail after REPLACE made): any long one counts
        assertEquals(true, WatchTickWorker.waitsLong(listOf(State.ENQUEUED to short, State.BLOCKED to long)))
    }

    @Test
    fun theAppsStartReplacesTheChainOnlyWhenAWatchAnsweredAndTheChainWaitsLong() {
        assertTrue(WatchTickWorker.replaceOnStart(watchFound = true, waitsLong = true))
        // a step is running (the start tick and the cycle tick at once): KEEP, the step queues the next itself
        assertFalse(WatchTickWorker.replaceOnStart(watchFound = true, waitsLong = null))
        assertFalse(WatchTickWorker.replaceOnStart(watchFound = true, waitsLong = false))
        assertFalse(WatchTickWorker.replaceOnStart(watchFound = false, waitsLong = true))
        assertFalse(WatchTickWorker.replaceOnStart(watchFound = false, waitsLong = null))
    }

    @Test
    fun aWatchAppearingRestartsTheChainUnlessAShortStepWaits() {
        assertTrue(WatchTickWorker.replaceOnWatchAppeared(waitsLong = true))
        assertTrue(WatchTickWorker.replaceOnWatchAppeared(waitsLong = null))
        assertFalse(WatchTickWorker.replaceOnWatchAppeared(waitsLong = false))
    }

    @Test
    fun theWatchAppearedTickCountsFromTheLastDeliveredTickNotFromAFailedAttempt() {
        val memory = WatchTickMemory()
        assertTrue(memory.watchTickDue(now))
        memory.delivered(now - 10 * minute, 70)
        memory.attempted(now - 30_000L) // a cycle step that found no watch half a minute ago
        memory.noWatch()
        assertTrue(memory.watchTickDue(now))
        memory.delivered(now - 30_000L, 70)
        assertFalse(memory.watchTickDue(now))
        assertTrue(memory.watchTickDue(now + 30_000L))
    }

    @Test
    fun batteryTicksFollowTheLastDeliveredPercentAndAClaimHoldsTheNextBroadcasts() {
        val memory = WatchTickMemory()
        assertFalse(memory.batteryTickDue(20, now)) // nothing reached a watch yet
        memory.attempted(now - 10 * minute)
        memory.delivered(now - 10 * minute, 60)
        assertEquals(60, memory.lastSentPercent)
        assertTrue(memory.batteryTickDue(55, now))
        assertFalse(memory.batteryTickDue(57, now))
        memory.attempted(now) // claimed by a broadcast, the send not run yet
        assertFalse(memory.batteryTickDue(50, now + 1_000L))
        assertTrue(memory.batteryTickDue(50, now + minute))
    }

    @Test
    fun withoutAWatchBatteryTicksWaitUntilOneIsThereAgain() {
        val memory = WatchTickMemory()
        memory.delivered(now - 10 * minute, 60)
        memory.attempted(now - 5 * minute)
        memory.noWatch() // the watch stayed at home
        assertFalse(memory.batteryTickDue(40, now))
        assertFalse(memory.batteryTickDue(30, now + 10 * minute))
        memory.watchAppeared()
        assertTrue(memory.batteryTickDue(30, now + 10 * minute))
        memory.noWatch()
        memory.attempted(now + 20 * minute)
        memory.watchFound() // the next cycle step found it
        assertTrue(memory.batteryTickDue(30, now + 22 * minute))
        memory.noWatch()
        memory.delivered(now + 30 * minute, 30)
        assertFalse(memory.watchMissing)
    }

    @Test
    fun theGapRule() {
        assertFalse(WatchTicker.gapPassed(now, now))
        assertFalse(WatchTicker.gapPassed(now, now - WatchTicker.MIN_EXTRA_GAP_MS + 1))
        assertTrue(WatchTicker.gapPassed(now, now - WatchTicker.MIN_EXTRA_GAP_MS))
        assertTrue(WatchTicker.gapPassed(now, 0L))
        assertEquals(minute, WatchTicker.MIN_EXTRA_GAP_MS)
        assertEquals(5, WatchTicker.BATTERY_STEP)
    }
}
