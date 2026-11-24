package tk.glucodata.mod.health

import android.content.Context
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import tk.glucodata.mod.watch.ModWatchLog

/**
 * JugglucoNGmod, watch block (part 4): the one set of heart rate and steps objects of the watch process - the queue
 * ([buffer]), the memory between runs ([state]), the open heart rate windows and the counter of deliveries a
 * hand-over waits on. Built lazily on first use from any entry point ([get]): upstream's Application is theirs, so
 * nothing is set up at process start. Tests build their own with a folder and a clock.
 */
class HealthCore(
    val context: Context,
    dir: File,
    val clock: () -> Long = { System.currentTimeMillis() },
) {
    val state = HealthState(context)
    val buffer = SampleBuffer(dir)

    private val hrLock = Any()
    private val hr: HrAggregator = HrAggregator.decode(state.hrBuckets, state.hrClosedUntil)

    init {
        queueOldFiveMinuteWindows()
    }

    private val eventLock = Object()
    private var events = 0L

    /** Open heart rate windows (for tests and the status). */
    val openHeartRateWindows: Int get() = synchronized(hrLock) { hr.openCount }

    /**
     * Adds heart rate readings and closes the windows ended [graceMs] before [nowMs] or by the newest of these readings
     * ([coveredUntil]: the delivery holds everything up to it); the closed means go to the queue
     * first and only then leave the kept open windows, so the process dying in between repeats a mean instead of
     * losing it. After the restart the window comes back from the last saved [HealthState.hrBuckets] (written with
     * `apply()`, so it may be older than the queued mean) and closes again: with the same readings it is the same row
     * in every field, which the phone drops as a repeat; with fewer it is another row, which the phone merges as late
     * readings ([ModHealthBatch.Sample]), counting those readings twice. Rare (the process dies within those few
     * milliseconds) and accepted (critic 4b; critic 5, N-5-7). Readings for an already closed window are counted in
     * [HealthState.hrLate]. Returns how many means were queued.
     */
    fun addHeartRate(readings: List<HealthIntake.HeartRate>, nowMs: Long, graceMs: Long): Int = synchronized(hrLock) {
        var late = 0
        readings.forEach { if (hr.add(it.timeMs, it.bpm, it.noContact)) late++ }
        val closed = hr.close(nowMs, graceMs, coveredUntil(readings, nowMs))
        buffer.append(closed)
        state.hrBuckets = hr.encode()
        state.hrClosedUntil = hr.closedUntil
        if (late > 0) {
            state.hrLate = state.hrLate + late
            ModWatchLog.w("health late hr=$late")
        }
        closed.size
    }

    /** Closes the heart rate windows ended [graceMs] before [nowMs] into the queue; returns how many. */
    fun closeHeartRate(nowMs: Long, graceMs: Long): Int = addHeartRate(emptyList(), nowMs, graceMs)

    /**
     * Once after an update from a watch app before Watch 7i: its open 5-minute windows ([HealthState.hrBucketsOld5])
     * go to the queue as they are, as `hr5` rows the phone still takes (their readings stay 5-minute means; the rest of
     * such a window comes as 1-minute rows). Queue first, then the old key is emptied (a death in between repeats the
     * rows, which the phone drops as repeats).
     */
    private fun queueOldFiveMinuteWindows() {
        val old = state.hrBucketsOld5
        if (old.isEmpty()) return
        val rows = HrAggregator.decode(old, windowMs = OLD_HR5_WINDOW_MS, kind = ModHealthBatch.KIND_HR5)
            .close(nowMs = Long.MAX_VALUE, graceMs = 0L)
        buffer.append(rows)
        state.hrBucketsOld5 = ""
        if (rows.isNotEmpty()) ModWatchLog.i("health old hr5 windows queued=${rows.size}")
    }

    /** The number of deliveries so far (a hand-over waits for it to change after a flush). */
    fun dataEvents(): Long = synchronized(eventLock) { events }

    fun signalData() {
        synchronized(eventLock) {
            events++
            eventLock.notifyAll()
        }
    }

    /** Waits up to [timeoutMs] for a delivery after the count [before]; `true` when one came. */
    fun awaitDataAfter(before: Long, timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        synchronized(eventLock) {
            while (events == before) {
                val left = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (left <= 0) return false
                try {
                    eventLock.wait(left)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return events != before
                }
            }
            return true
        }
    }

    companion object {
        /** Our queue folder in the app's no-backup storage. */
        const val DIR = "mod_health"

        /** The heart rate window of watch apps before Watch 7i. */
        const val OLD_HR5_WINDOW_MS = 5 * 60_000L

        /**
         * The newest reading time of a delivery ([readings], skipped ones too: the sensor was read then), never after
         * [nowMs] (a reading stamped in the future must not close windows early); [Long.MIN_VALUE] - no readings.
         * The windows ended by then are complete ([HrAggregator.close]).
         */
        fun coveredUntil(readings: List<HealthIntake.HeartRate>, nowMs: Long): Long =
            readings.maxOfOrNull { it.timeMs }?.coerceAtMost(nowMs) ?: Long.MIN_VALUE

        @Volatile
        private var shared: HealthCore? = null

        /**
         * One background thread for hand-overs and sends (disk and Data Layer never on the main thread). A hand-over
         * waits here for the answer to its flush, so a delivery never queues behind it: deliveries are written on
         * their own thread ([HealthIntake.deliver]) or, from the main thread, on [lightExecutor].
         */
        val executor: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "ModHealth").apply { isDaemon = true } }
        }

        /** Short disk work that must never wait behind a hand-over: deliveries from the main thread, page reads. */
        val lightExecutor: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "ModHealthLight").apply { isDaemon = true } }
        }

        fun get(context: Context): HealthCore = shared ?: synchronized(this) {
            shared ?: run {
                val app = context.applicationContext ?: context
                HealthCore(app, File(app.noBackupFilesDir, DIR)).also { shared = it }
            }
        }
    }
}
