package tk.glucodata.mod.watch

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import tk.glucodata.UiRefreshBus
import tk.glucodata.alerts.AlertRuntimeManager

/**
 * JugglucoNGmod, watch block (part 7o): the watch's alerts and notification look at a new reading as soon as it is
 * stored, not at upstream's next tick of 15 s (`AlertRuntimeManager.CHECK_INTERVAL_MS`). Every rise of upstream's
 * public `UiRefreshBus.revision` (the phone's calibration and values, `/sync2/cal` and `/sync2/chunk`, both bump it)
 * asks for upstream's public `AlertRuntimeManager.ensureMonitoring()` - under their lock, the same evaluation their
 * timer runs (`evaluateLocked`), which hands a new synced reading on to their alert path once (guarded by the reading's
 * time, `lastDeliveredReadingTimeMs`). No hook: nothing of theirs changes.
 *
 * The calibration signal brings no new reading: its check is the same as an early timer tick (`onDisplayCalibrationChanged`
 * and its suppression run only on the phone, `CalibrationManager`), so it is not filtered - filtering would cost a
 * resolution of the current reading as heavy as the check itself.
 *
 * Our own echo is not checked again (critic 7o, S1): a check that hands a new reading on goes through upstream's
 * `SuperGattCallback.dowithglucose`, which bumps the revision itself (`requestDataRefresh`). The revision right after
 * our check is kept ([RevisionGate]); a queued run that finds the revision no higher does nothing (upstream's 15 s
 * tick still covers data that came in during the check without a later signal).
 *
 * Paced by [SignalBudget] (3 checks in 20 s) and coalesced on one thread ([CoalescedRunner]): one check at a time, a
 * signal during it makes one more after it. Started lazily from [tk.glucodata.mod.health.hs.ModHealthStarter],
 * behind `Application.onCreate`; the collection runs in the background. Our failure never takes the process down.
 *
 * One check per new reading (watch measurement 20.11, 10:39-12:36, 239 `alerts checked` lines in ~2 h): the first
 * version checked twice a minute, at :24 and :38 (median 124 ms, max 1273 ms, ~15 s of CPU an hour), and upstream
 * re-armed `LossOfSensorAlarm` - handed the new reading on - only at :38. By the code: every minute the phone serves
 * `/sync2/cal` and ~0.1 s later `/sync2/chunk` (WearSync2.serveSensorSince), each bumps the revision. The calibration
 * started the check at once (the values not stored yet: it saw the old reading); the values came during that check,
 * and the gate, set after the check, took their bump for our own echo - so no check saw the new reading. Upstream's
 * own 15 s tick handed it on up to 15 s later (at :38), and the bump of that hand-over (their `dowithglucose`, not
 * ours) started our second, useless check. So the check now waits for the signals to settle: [QUIET_MS] after the
 * last one, never more than [MAX_DELAY_MS] after the first ([CoalescedRunner]'s quiet window). The calibration and the
 * values make one check, it sees the new reading and hands it on itself (its echo is skipped by the gate), and
 * upstream's next tick finds nothing new - no hand-over, no bump, no second check. Expected: one check a minute, the
 * alert ~0.5 s after the values instead of up to 15 s (to be confirmed by the same count on the watch). Values that
 * still arrive during a check (later than [QUIET_MS] after the calibration) are covered by upstream's tick as before;
 * the bump of that tick's hand-over then costs one more check - the old cost, now only in that case.
 */
internal object FreshReadingAlerts {

    /** Signals closer than this are one check: the calibration and the values come ~0.1 s apart (measured 19.11). */
    const val QUIET_MS = 500L

    /** A stream of signals (history catching up, a chunk every ~0.4 s) delays the check by at most this. */
    const val MAX_DELAY_MS = 2_000L

    @Volatile private var started = false

    private val gate = RevisionGate()

    private val runner by lazy {
        CoalescedRunner(
            "ModFreshAlerts",
            SignalBudget(),
            due = { gate.due(UiRefreshBus.revision.value) },
            quietMs = QUIET_MS,
            maxDelayMs = MAX_DELAY_MS,
        ) { check() }
    }

    /** At process start (a provider's `onCreate`, before `Application.onCreate`): [start] runs after it. */
    fun startAfterApplication() {
        try {
            Handler(Looper.getMainLooper()).post { start() }
        } catch (t: Throwable) {
            ModWatchLog.w("fresh reading alerts not posted: ${t.javaClass.simpleName}")
        }
    }

    private fun start() {
        if (started) return
        started = true
        try {
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
                    ModWatchLog.w("fresh reading alerts failed: ${t.javaClass.simpleName}")
                },
            )
            scope.launch { UiRefreshBus.revision.drop(1).collect { runner.signal() } }
        } catch (t: Throwable) {
            ModWatchLog.w("fresh reading alerts not started: ${t.javaClass.simpleName}")
        }
    }

    private fun check() {
        val begin = SystemClock.elapsedRealtime()
        try {
            AlertRuntimeManager.ensureMonitoring()
            ModWatchLog.i("alerts checked took=${SystemClock.elapsedRealtime() - begin} ms")
        } catch (t: Throwable) {
            ModWatchLog.w("alerts check failed: ${t.javaClass.simpleName}")
        } finally {
            // the bump of our own hand-over (made during the check, on this thread) is not new data
            try {
                gate.checked(UiRefreshBus.revision.value)
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * The revision seen right after our last check (critic 7o, S1): a revision no higher holds nothing the check did not
 * see - or only the check's own echo. Thread-safe.
 */
internal class RevisionGate {
    @Volatile private var afterCheck = Long.MIN_VALUE

    /** A run at [revision] has something new to look at. */
    fun due(revision: Long): Boolean = revision > afterCheck

    /** A check ended at [revision]. */
    @Synchronized
    fun checked(revision: Long) {
        if (revision > afterCheck) afterCheck = revision
    }
}

/**
 * Runs [action] on its own thread for [signal]s: one run at a time; signals while a run waits to start are one run; a
 * signal during a run makes one run after it; runs keep to the [budget] (a run over it waits, still counted as queued);
 * a run that is not [due] when it starts does nothing and costs no budget. The [clock] is the budget's
 * ([SystemClock.elapsedRealtime] - it goes on in sleep).
 *
 * Quiet window (watch measurement 20.11): with [quietMs] > 0 a run starts only when no signal came for [quietMs], or
 * [maxDelayMs] after the first signal it answers, whichever is sooner - signals a moment apart (the phone's calibration
 * and values) are one run that sees them all. 0 - at once, as before.
 */
internal class CoalescedRunner(
    name: String,
    private val budget: SignalBudget,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val due: () -> Boolean = { true },
    private val quietMs: Long = 0L,
    private val maxDelayMs: Long = quietMs,
    private val action: () -> Unit,
) {
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }

    /** A run is scheduled and has not started yet (guarded by this). */
    private var queued = false

    /** The first and the last signal the queued run answers, clock of [clock] (guarded by this). */
    private var firstSignalAt = 0L
    private var lastSignalAt = 0L

    fun signal() {
        val now = clock()
        synchronized(this) {
            lastSignalAt = now
            if (queued) return
            queued = true
            firstSignalAt = now
        }
        schedule(maxOf(quietMs, budget.waitMs(now)))
    }

    /** How long the queued run still waits for the signals to settle at [nowMs] (0 or less - not at all). */
    private fun settleLeftLocked(nowMs: Long): Long {
        if (quietMs <= 0L) return 0L
        return minOf(lastSignalAt + quietMs, firstSignalAt + maxDelayMs) - nowMs
    }

    private fun schedule(delayMs: Long) {
        try {
            executor.schedule({ run() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            synchronized(this) { queued = false }
            ModWatchLog.w("fresh reading run not scheduled: ${t.javaClass.simpleName}")
        }
    }

    private fun run() {
        val settle = try {
            val now = clock()
            synchronized(this) {
                val left = settleLeftLocked(now)
                // cleared first: a signal from now on means data this run may not see - one more run after it
                if (left <= 0L) queued = false
                left
            }
        } catch (_: Throwable) {
            synchronized(this) { queued = false }
            0L
        }
        // a signal came within the quiet window: the run moves on, still queued (later signals join it)
        if (settle > 0L) {
            schedule(settle)
            return
        }
        val isDue = try {
            due()
        } catch (_: Throwable) {
            true
        }
        if (!isDue) return
        val wait = try {
            budget.waitMs(clock())
        } catch (_: Throwable) {
            0L
        }
        if (wait > 0L) {
            synchronized(this) {
                if (queued) return // a signal since has scheduled its own run
                queued = true
            }
            schedule(wait)
            return
        }
        try {
            budget.record(clock())
            action()
        } catch (_: Throwable) {
        }
    }
}
