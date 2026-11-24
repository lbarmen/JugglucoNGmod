package mod.watch

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.watch.CoalescedRunner
import tk.glucodata.mod.watch.FreshReadingAlerts
import tk.glucodata.mod.watch.RevisionGate
import tk.glucodata.mod.watch.SignalBudget

/** Part 7o: the read budget and the early alert check of the watch. */
class FreshReadingAlertsTest {

    private fun now() = System.nanoTime() / 1_000_000L

    @Test
    fun theBudgetLetsCountRunsThroughAWindow() {
        val budget = SignalBudget(3, 20_000L)
        val t = 5_000_000L
        assertEquals(0L, budget.waitMs(t))
        budget.record(t)
        budget.record(t + 100L)
        assertEquals(0L, budget.waitMs(t + 200L))
        budget.record(t + 200L)
        assertEquals(20_000L - 300L, budget.waitMs(t + 300L))
        assertEquals(0L, budget.waitMs(t + 20_000L))
        // the oldest run leaves: the next waits for the second oldest
        budget.record(t + 20_000L)
        assertEquals(100L, budget.waitMs(t + 20_000L))
    }

    @Test
    fun signalsWhileARunWaitsAreOneRun() {
        val runs = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = CoalescedRunner("test", SignalBudget(3, 20_000L), ::now) {
            runs.incrementAndGet()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        runner.signal()
        assertTrue(started.await(5, TimeUnit.SECONDS))
        // during the run: one more after it, however many signals
        repeat(10) { runner.signal() }
        release.countDown()
        Thread.sleep(300L)
        assertEquals(2, runs.get())
    }

    @Test
    fun aCatchUpKeepsToTheBudgetAndRunsAgainOnceTheWindowLetsIt() {
        val runs = AtomicInteger()
        val times = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val fourth = CountDownLatch(4)
        val runner = CoalescedRunner("test", SignalBudget(3, 1_000L), ::now) {
            times += now()
            runs.incrementAndGet()
            fourth.countDown()
            Thread.sleep(30L)
        }
        // 60 signals in about 300 ms (a busy wait: Thread.sleep is coarse on Windows)
        repeat(60) {
            runner.signal()
            val until = System.nanoTime() + 5_000_000L
            while (System.nanoTime() < until) Unit
        }
        // the window of 1 s is not over: three at most
        assertTrue("runs ${runs.get()}", runs.get() in 1..3)
        assertTrue(fourth.await(5, TimeUnit.SECONDS))
        assertTrue("times $times", times[3] - times[0] >= 990L)
        Thread.sleep(1_200L)
        // the signals were coalesced: no run for each
        assertTrue("runs ${runs.get()}", runs.get() <= 5)
    }

    @Test
    fun aFailingActionDoesNotStopTheRunner() {
        val runs = AtomicInteger()
        val second = CountDownLatch(2)
        val runner = CoalescedRunner("test", SignalBudget(3, 20_000L), ::now) {
            runs.incrementAndGet()
            second.countDown()
            throw IllegalStateException("upstream failed")
        }
        runner.signal()
        Thread.sleep(200L)
        runner.signal()
        assertTrue(second.await(5, TimeUnit.SECONDS))
        assertEquals(2, runs.get())
    }

    @Test
    fun ourOwnEchoMakesNoSecondCheck() {
        // critic 7o, S1: a check that hands a reading on bumps the revision itself (upstream's dowithglucose)
        val revision = AtomicLong()
        val gate = RevisionGate()
        val checks = AtomicInteger()
        lateinit var runner: CoalescedRunner
        runner = CoalescedRunner("test", SignalBudget(3, 20_000L), ::now, due = { gate.due(revision.get()) }) {
            checks.incrementAndGet()
            revision.incrementAndGet() // the echo, during the check, on its thread
            runner.signal()
            gate.checked(revision.get())
        }
        fun dataArrives() {
            revision.incrementAndGet()
            runner.signal()
        }
        dataArrives()
        Thread.sleep(300L)
        assertEquals(1, checks.get())
        // more data - each new revision checked, each echo not; the skipped echoes cost no budget, or the third check
        // (the budget is 3 in 20 s) would wait 20 s
        dataArrives()
        Thread.sleep(300L)
        dataArrives()
        Thread.sleep(300L)
        assertEquals(3, checks.get())
    }

    /**
     * The phone's calibration, then ~0.1 s later its values (each bumps the revision); the check hands a new reading on
     * (the echo bump, on its thread) when the values are stored. [quietMs] - the runner's quiet window; [checkMs] - how
     * long a check takes. Returns the number of checks and whether one of them saw the values.
     */
    private fun calibrationThenValues(quietMs: Long, checkMs: Long): Pair<Int, Boolean> {
        val revision = AtomicLong()
        val stored = java.util.concurrent.atomic.AtomicBoolean(false)
        val sawValues = java.util.concurrent.atomic.AtomicBoolean(false)
        val gate = RevisionGate()
        val checks = AtomicInteger()
        lateinit var runner: CoalescedRunner
        runner = CoalescedRunner(
            "test", SignalBudget(3, 20_000L), ::now, due = { gate.due(revision.get()) },
            quietMs = quietMs, maxDelayMs = 2_000L,
        ) {
            checks.incrementAndGet()
            val seen = stored.get()
            Thread.sleep(checkMs)
            if (seen) {
                sawValues.set(true)
                revision.incrementAndGet() // the hand-over's echo
                runner.signal()
            }
            gate.checked(revision.get())
        }
        revision.incrementAndGet() // /sync2/cal
        runner.signal()
        Thread.sleep(100L)
        stored.set(true) // /sync2/chunk: stored, then the bump
        revision.incrementAndGet()
        runner.signal()
        Thread.sleep(1_500L)
        return checks.get() to sawValues.get()
    }

    @Test
    fun withoutAQuietWindowTheValuesDuringTheCalibrationsCheckWereMissed() {
        // watch measurement 20.11: the check started by the calibration took the values' bump for its echo
        val (checks, saw) = calibrationThenValues(quietMs = 0L, checkMs = 300L)
        assertEquals(1, checks)
        assertFalse(saw)
    }

    @Test
    fun calibrationAndValuesAMomentApartAreOneCheckThatSeesTheValues() {
        val (checks, saw) = calibrationThenValues(quietMs = FreshReadingAlerts.QUIET_MS, checkMs = 300L)
        assertEquals(1, checks)
        assertTrue(saw)
    }

    @Test
    fun theQuietWindowRunsOnceAfterTheLastSignal() {
        val times = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val runner = CoalescedRunner("test", SignalBudget(3, 20_000L), ::now, quietMs = 300L, maxDelayMs = 5_000L) {
            times += now()
        }
        runner.signal()
        Thread.sleep(150L)
        val last = now()
        runner.signal()
        Thread.sleep(1_000L)
        assertEquals("times $times", 1, times.size)
        assertTrue("waited ${times[0] - last} ms", times[0] - last >= 280L)
    }

    @Test
    fun aStreamOfSignalsWaitsNoLongerThanTheMaxDelay() {
        val times = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val runner = CoalescedRunner("test", SignalBudget(3, 20_000L), ::now, quietMs = 300L, maxDelayMs = 600L) {
            times += now()
        }
        val first = now()
        // a signal every ~50 ms for 1.5 s (a busy wait: Thread.sleep is coarse on Windows) - never quiet for 300 ms
        while (now() - first < 1_500L) {
            runner.signal()
            val until = System.nanoTime() + 50_000_000L
            while (System.nanoTime() < until) Unit
        }
        Thread.sleep(800L)
        assertTrue("times $times", times.isNotEmpty())
        assertTrue("first run after ${times[0] - first} ms", times[0] - first in 580L..1_000L)
        // one run per max delay at most, plus the one after the stream
        assertTrue("runs ${times.size}", times.size in 2..4)
    }

    @Test
    fun theWatchWaitsForTheSignalsToSettle() {
        val alerts = file("wear/java/tk/glucodata/mod/watch/FreshReadingAlerts.kt")
        assertTrue(alerts.contains("quietMs = QUIET_MS"))
        assertTrue(alerts.contains("maxDelayMs = MAX_DELAY_MS"))
        assertTrue(FreshReadingAlerts.QUIET_MS in 200L..1_000L)
        assertTrue(FreshReadingAlerts.MAX_DELAY_MS >= FreshReadingAlerts.QUIET_MS)
    }

    @Test
    fun theGateKeepsTheHighestRevisionSeenAfterACheck() {
        val gate = RevisionGate()
        assertTrue(gate.due(0L))
        gate.checked(5L)
        assertFalse(gate.due(5L))
        assertTrue(gate.due(6L))
        gate.checked(3L) // never back
        assertFalse(gate.due(5L))
    }

    @Test
    fun theWatchStartsTheCheckFromOurProviderAndCallsOnlyUpstreamsPublicEntry() {
        val starter = file("wearMod/java/tk/glucodata/mod/health/hs/ModHealthStarter.kt")
        assertTrue(starter.contains("FreshReadingAlerts.startAfterApplication()"))
        val alerts = file("wear/java/tk/glucodata/mod/watch/FreshReadingAlerts.kt")
        assertTrue(alerts.contains("AlertRuntimeManager.ensureMonitoring()"))
        assertTrue(alerts.contains("UiRefreshBus.revision"))
    }

    @Test
    fun ourJournalLinesCarryNoValueAndNoTimeOfAnEvent() {
        // critic 7o, N7: every journal line of part 7o's files - only ages, durations, flags and failure classes
        val sources = listOf(
            "wear/java/tk/glucodata/mod/watch/FreshReadingAlerts.kt",
            "wear/java/tk/glucodata/mod/watch/SignalBudget.kt",
            "wear/java/tk/glucodata/mod/watch/face/ModFaceRenderer.kt",
            "wearMod/java/tk/glucodata/mod/health/hs/ModHealthStarter.kt",
        )
        val allowed = Regex(
            """ageText\([^()]*\)|SystemClock\.elapsedRealtime\(\) - begin|watchState\.isAmbient\.value == true|""" +
                """t\.javaClass\.simpleName|changed|ambient""",
        )
        val interpolation = Regex("""\$\{([^}]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)""")
        val forbidden = listOf("value", "glucose", "sugar", "mmol", "mg/dl", "mgdl", "time=", "timems", "reading=")
        var calls = 0
        for (path in sources) {
            // a call's argument runs to the ")" that ends its line
            for (call in Regex("""ModWatchLog\.[iw]\(([\s\S]*?)\)\s*\n""").findAll(file(path))) {
                calls++
                val argument = call.groupValues[1]
                interpolation.findAll(argument).forEach {
                    val expr = it.groupValues[1].ifEmpty { it.groupValues[2] }
                    assertTrue("$path: \${$expr}", allowed.matches(expr))
                }
                val text = interpolation.replace(argument, "").lowercase()
                forbidden.forEach { assertFalse("$path: $argument", text.contains(it)) }
            }
        }
        // the lines of part 7o are all found: face read (+ failed), face frame, alerts checked (+ failed)
        assertTrue("calls $calls", calls >= 6)
    }

    private fun file(path: String) =
        listOf(File("src/$path"), File("Common/src/$path")).first { it.isFile }.readText()
}
