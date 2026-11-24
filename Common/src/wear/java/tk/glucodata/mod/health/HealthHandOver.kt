package tk.glucodata.mod.health

import android.content.Context
import java.util.concurrent.Callable
import java.util.concurrent.Future
import tk.glucodata.mod.watch.ModWatchLog

/**
 * JugglucoNGmod, watch block (part 4): "hand over what was collected" - on a `/mod/tick` from the phone (through
 * HealthTick) and on the "send now" button (part 4b): ask the source to deliver what it holds, wait for the data
 * (usually a fraction of a second, at most [HealthConfig.FLUSH_WAIT_MS]), let the source check its subscription,
 * close the heart rate windows ended by now and send (Glucove's Refresher). The watch stays awake only as long as
 * that takes: a tick's hand-over is held by a WorkManager task ([HealthHandOverWorker]).
 *
 * Without a registered source ([HealthSources.current]) nothing collects, and nothing is done.
 */
object HealthHandOver {

    /**
     * A tick that passed the filter: always on our own thread ([HealthCore.executor]), never on the Data Layer
     * listener's - the flush, the subscription check and the sends take up to half a minute, and the phone's
     * "snoozed" answer comes through the same listener thread (joints critic, C4). The deliveries the hand-over waits
     * for are still written on Health Services' own callback thread, never queued here ([HealthIntake]).
     *
     * Queued as a WorkManager task ([HealthHandOverWorker], part 5b): the listener returns at once, and the running
     * task keeps the process from being frozen while the hand-over works (critic of part 5, C-5-1). When the task
     * cannot be queued, straight on our thread as before.
     */
    fun onTick(context: Context) {
        val source = HealthSources.current ?: return
        try {
            HealthHandOverWorker.enqueue(context.applicationContext ?: context)
        } catch (t: Throwable) {
            ModWatchLog.w("health hand-over task not queued: ${t.javaClass.simpleName}")
            start(context, source, forceStatus = false)
        }
    }

    /**
     * The tick's hand-over on [HealthCore.executor] for [HealthHandOverWorker] to wait on; `null` when no source is
     * registered (nothing collects).
     */
    internal fun submitTick(context: Context): Future<*>? {
        val source = HealthSources.current ?: return null
        val app = context.applicationContext ?: context
        return HealthCore.executor.submit { safeRun(app, source, forceStatus = false) }
    }

    /**
     * The "send now" button (part 4b): the same hand-over with a fresh status row, always on our thread (the core is
     * built there too - its first build reads the disk). `false` when nothing was queued (no source registered).
     */
    fun sendNow(context: Context): Boolean {
        val source = HealthSources.current ?: return false
        return try {
            val app = context.applicationContext ?: context
            HealthCore.executor.execute { safeRun(app, source, forceStatus = true) }
            true
        } catch (t: Throwable) {
            ModWatchLog.w("health send-now not queued: ${t.javaClass.simpleName}")
            false
        }
    }

    /** [run] on [HealthCore.executor], as the button and a tick do (tests wait on the result). */
    fun submit(
        core: HealthCore,
        source: HealthSource,
        sink: HealthSender.Sink,
        flushWaitMs: Long = HealthConfig.FLUSH_WAIT_MS,
        forceStatus: Boolean = false,
    ): Future<HealthSender.Result> = HealthCore.executor.submit(Callable { run(core, source, sink, flushWaitMs, forceStatus) })

    private fun start(context: Context, source: HealthSource, forceStatus: Boolean) {
        try {
            val app = context.applicationContext ?: context
            HealthCore.executor.execute { safeRun(app, source, forceStatus) }
        } catch (t: Throwable) {
            ModWatchLog.w("health hand-over failed: ${t.javaClass.simpleName}")
        }
    }

    private fun safeRun(context: Context, source: HealthSource, forceStatus: Boolean) {
        try {
            val core = HealthCore.get(context)
            // urgent only for "Send now" (forceStatus is set by the button alone): a tick's batches wait for the
            // system's own batching (owner's decision 20.11, HealthSender)
            run(core, source, HealthSender.dataClient(core.context, urgent = forceStatus), forceStatus = forceStatus)
        } catch (t: Throwable) {
            ModWatchLog.w("health hand-over failed: ${t.javaClass.simpleName}")
        }
    }

    /** The hand-over itself (blocking; never on the main thread). */
    fun run(
        core: HealthCore,
        source: HealthSource,
        sink: HealthSender.Sink,
        flushWaitMs: Long = HealthConfig.FLUSH_WAIT_MS,
        forceStatus: Boolean = false,
    ): HealthSender.Result {
        // A delivery sent everything less than a minute ago (Watch 7i, critic 7k N3): a tick only checks the
        // subscription - no flush, no batch of one or two rows; the next delivery sends what comes. "Send now" always runs.
        if (!forceStatus && HealthSender.sentJustNow(core, core.clock())) {
            try {
                source.maintain(core.context, core.clock())
            } catch (t: Throwable) {
                ModWatchLog.w("health maintain failed: ${t.javaClass.simpleName}")
            }
            return HealthSender.Result(0, 0, 0, false)
        }
        // A delivery coming in answer to the flush does not send on its own: everything goes in one batch below.
        core.state.lastAttemptAt = core.clock()
        val before = core.dataEvents()
        val flushed = try {
            source.flush(core.context)
        } catch (t: Throwable) {
            ModWatchLog.w("health flush failed: ${t.javaClass.simpleName}")
            false
        }
        if (flushed) core.awaitDataAfter(before, flushWaitMs)
        try {
            source.maintain(core.context, core.clock())
        } catch (t: Throwable) {
            ModWatchLog.w("health maintain failed: ${t.javaClass.simpleName}")
        }
        // The source handed over everything up to now: close the windows ended by this moment.
        val now = core.clock()
        core.closeHeartRate(now, HealthConfig.HR_GRACE_REFRESH_MS)
        return HealthSender.send(core, source, sink, now, forceStatus)
    }
}
