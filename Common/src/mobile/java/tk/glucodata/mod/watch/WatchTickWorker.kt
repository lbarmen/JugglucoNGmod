package tk.glucodata.mod.watch

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * JugglucoNGmod, watch block (part 2): the 5-minute cycle of `/mod/tick` ([WatchTicker]) - a chain of one-time
 * WorkManager tasks under one unique name, because a periodic task runs at most every 15 minutes. Each step sends a
 * tick and queues the next: in [PERIOD_MS] when a watch was found, in [NO_WATCH_PERIOD_MS] when none was (without a
 * watch the phone is not woken for nothing; our watch appearing brings the cycle back at once, [WatchTicker.onWatchAppeared]).
 *
 * From outside the chain is queued with KEEP (a running chain stays), from inside with APPEND_OR_REPLACE: REPLACE
 * would cancel the running step itself, KEEP would end the chain. [restart] (REPLACE) starts it afresh at 5 minutes -
 * only when the queued step waits the long hour ([TAG_LONG], read back from WorkManager, so it holds after the process
 * was killed) or nothing is queued. A step cancelled by REPLACE while it runs queues no next step ([nextStepDelayMs]):
 * the cancel does not interrupt doWork, and its tail would hang a second step behind the fresh one.
 */
class WatchTickWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        var found = false
        try {
            found = WatchTicker.send(applicationContext, ModWatchMessages.REASON_CYCLE, System.currentTimeMillis())
        } catch (t: Throwable) {
            ModWatchLog.w("tick step failed: ${t.javaClass.simpleName}")
        }
        try {
            val delay = nextStepDelayMs(found, isStopped)
            if (delay == null) {
                ModWatchLog.i("tick step cancelled: no next step")
                return Result.success()
            }
            WorkManager.getInstance(applicationContext)
                .enqueueUniqueWork(WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request(delay))
        } catch (t: Throwable) {
            ModWatchLog.w("next tick not queued: ${t.javaClass.simpleName}")
        }
        return Result.success()
    }

    companion object {
        /** Unique WorkManager name of the chain. */
        const val WORK = "mod-watch-tick"

        /** Tag of a step that waits [NO_WATCH_PERIOD_MS]. */
        const val TAG_LONG = "mod-watch-tick-long"

        /** With our watch reachable: a tick every 5 minutes (watch plan, decision 5). */
        const val PERIOD_MS = 5 * 60_000L

        /** Without a watch: look again in an hour (dispatcher's decision). */
        const val NO_WATCH_PERIOD_MS = 60 * 60_000L

        private const val INFO_WAIT_SECONDS = 5L

        /** The delay before the next step after one that found a watch ([watchFound]) or not. */
        fun nextDelayMs(watchFound: Boolean): Long = if (watchFound) PERIOD_MS else NO_WATCH_PERIOD_MS

        /** After a step: the delay of the next one, `null` - none, the step was cancelled ([stopped]) while it ran. */
        fun nextStepDelayMs(watchFound: Boolean, stopped: Boolean): Long? = if (stopped) null else nextDelayMs(watchFound)

        /**
         * Of the chain's steps (state and tags, as WorkManager reports them): whether a step still to run waits the long
         * hour; `null` when none is waiting (only a running one, or the chain is gone).
         */
        fun waitsLong(steps: List<Pair<WorkInfo.State, Set<String>>>): Boolean? {
            val waiting = steps.filter { it.first == WorkInfo.State.ENQUEUED || it.first == WorkInfo.State.BLOCKED }
            if (waiting.isEmpty()) return null
            return waiting.any { TAG_LONG in it.second }
        }

        /** The app's start: start the cycle afresh (REPLACE) only when a watch answered and the queued step waits long. */
        fun replaceOnStart(watchFound: Boolean, waitsLong: Boolean?): Boolean = watchFound && waitsLong == true

        /** Our watch appeared: start afresh unless a 5-minute step is already waiting. */
        fun replaceOnWatchAppeared(waitsLong: Boolean?): Boolean = waitsLong != false

        /** [waitsLong] of the chain as WorkManager has it now; `null` also when it cannot tell. Not for the main thread. */
        fun queuedWaitsLong(context: Context): Boolean? = try {
            val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK)
                .get(INFO_WAIT_SECONDS, TimeUnit.SECONDS)
            waitsLong(infos.map { it.state to it.tags })
        } catch (t: Throwable) {
            ModWatchLog.w("tick chain state unknown: ${t.javaClass.simpleName}")
            null
        }

        /** Queues the chain in [PERIOD_MS] unless it is already queued or running. Not for the main thread. */
        fun ensureScheduled(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request(PERIOD_MS))
        }

        /** Starts the chain afresh: the next step in [PERIOD_MS]. Not for the main thread. */
        fun restart(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, request(PERIOD_MS))
        }

        /** One step of the chain; the long wait carries [TAG_LONG] (a test holds it, critic of part 2, N2-3). */
        internal fun request(delayMs: Long): OneTimeWorkRequest {
            val builder = OneTimeWorkRequestBuilder<WatchTickWorker>().setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            if (delayMs == NO_WATCH_PERIOD_MS) builder.addTag(TAG_LONG)
            return builder.build()
        }
    }
}
