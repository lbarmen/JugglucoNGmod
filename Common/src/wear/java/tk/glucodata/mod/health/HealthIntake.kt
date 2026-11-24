package tk.glucodata.mod.health

import android.content.Context
import android.os.Looper
import tk.glucodata.mod.health.ModHealthBatch.Sample
import tk.glucodata.mod.watch.ModWatchLog

/**
 * JugglucoNGmod, watch block (part 4): data from the source ([HealthSource], Health Services in part 4b) into the
 * queue. Heart rate goes into 1-minute means ([HrAggregator]); the day's step count goes as it is - the phone counts
 * the differences of totals within one day (part 5, [ModHealthBatch.stepsDelta]). After a delivery a batch goes by
 * itself when none went (or was tried) for [HealthConfig.SEND_AFTER_DATA_GAP_MS] - so the closed minutes leave with
 * the delivery that closed them; the phone's 5-minute `/mod/tick` is the fallback.
 */
object HealthIntake {

    /** One heart rate reading at watch time [timeMs]; [noContact] - the sensor said it had no skin contact. */
    data class HeartRate(val timeMs: Long, val bpm: Float, val noContact: Boolean = false)

    /** The step count [steps] since [dayStartMs] up to [endMs] (watch times). */
    data class StepsDay(val endMs: Long, val steps: Long, val dayStartMs: Long)

    /**
     * Entry point of the source: writes [heartRate] and [steps] to the queue on disk before it returns (the service
     * may be stopped and the process killed right after its callback), then sends on our background thread if due.
     * On the main thread the writing goes to [HealthCore.lightExecutor] instead. Never throws.
     *
     * Never on [HealthCore.executor]: a hand-over waits there for exactly this delivery after its flush.
     */
    fun deliver(context: Context, heartRate: List<HeartRate>, steps: List<StepsDay>) {
        if (heartRate.isEmpty() && steps.isEmpty()) return
        try {
            val app = context.applicationContext ?: context
            if (Looper.myLooper() == Looper.getMainLooper()) {
                HealthCore.lightExecutor.execute { deliver(HealthCore.get(app), heartRate, steps, HealthSender::dataClient) }
            } else {
                deliver(HealthCore.get(app), heartRate, steps, HealthSender::dataClient)
            }
        } catch (t: Throwable) {
            ModWatchLog.w("health delivery failed: ${t.javaClass.simpleName}")
        }
    }

    /** [deliver] with the core and the sink given (tests): the queue at once, the send if due on [HealthCore.executor]. */
    fun deliver(core: HealthCore, heartRate: List<HeartRate>, steps: List<StepsDay>, sink: (Context) -> HealthSender.Sink) {
        try {
            if (heartRate.isEmpty() && steps.isEmpty()) return
            deliverNow(core, heartRate, steps, core.clock())
            HealthCore.executor.execute {
                try {
                    HealthSender.sendIfDue(core, HealthSources.current, sink(core.context), core.clock())
                } catch (t: Throwable) {
                    ModWatchLog.w("health send after delivery failed: ${t.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("health delivery failed: ${t.javaClass.simpleName}")
        }
    }

    /**
     * Queues one delivery received at [nowMs]: heart rate into the means (windows ended by the delivery's newest reading
     * or 2 minutes ago close), valid step counts as they are. Returns how many rows went to the queue now.
     */
    fun deliverNow(core: HealthCore, heartRate: List<HeartRate>, steps: List<StepsDay>, nowMs: Long): Int {
        if (heartRate.isEmpty() && steps.isEmpty()) return 0
        core.state.lastDataAt = nowMs
        val means = core.addHeartRate(heartRate, nowMs, HealthConfig.HR_GRACE_DATA_MS)
        val stepRows = steps.mapNotNull { stepsRow(it) }
        core.buffer.append(stepRows)
        core.signalData()
        ModWatchLog.i("health data hr=${heartRate.size} hr1=$means steps=${stepRows.size}")
        return means + stepRows.size
    }

    /** The queue row of a step count; `null` when it is broken (negative, or ending before its day starts). */
    fun stepsRow(s: StepsDay): Sample? {
        if (s.steps < 0 || s.dayStartMs < 0 || s.endMs < s.dayStartMs) return null
        return Sample(ModHealthBatch.KIND_STEPS_DAY, s.endMs, s.steps.toFloat(), s.dayStartMs.toString())
    }
}
