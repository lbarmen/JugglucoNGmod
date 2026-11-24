package tk.glucodata.mod.health

import android.content.Context
import android.os.Build
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import tk.glucodata.mod.watch.ModWatchLog

/**
 * JugglucoNGmod, watch block (part 5b, critic of part 5, C-5-1): the hand-over of a `/mod/tick` as a one-time
 * WorkManager task. The Data Layer listener returns at once (the phone's "snoozed" answer comes through the same
 * listener thread, joints critic C4); without our face on screen nothing else holds the watch process then, and a
 * cached process is frozen within seconds - the flush, the subscription check and the sends would stall until the
 * next wake-up. A running task holds the process until it is done.
 *
 * Expedited on API 31+ (starts at once, also in Doze; past the quota it runs as a normal task). Below 31 WorkManager
 * would run an expedited task as a foreground service with a notification of ours - a plain task there. Unique name
 * with KEEP: a tick while one is queued or running adds nothing (that one sends everything collected).
 *
 * The task itself only waits: the hand-over runs on [HealthCore.executor] as the "send now" button's does (one at a
 * time), and the deliveries it waits for are still written on Health Services' own thread ([HealthIntake]) - the
 * task never waits on its own thread for them (part 4a2).
 */
class HealthHandOverWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        try {
            HealthHandOver.submitTick(applicationContext)?.get(WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            // the hand-over goes on on our thread; the next tick queues again
            ModWatchLog.w("health hand-over task: still running after the wait")
        } catch (t: Throwable) {
            ModWatchLog.w("health hand-over task failed: ${t.javaClass.simpleName}")
        }
        return Result.success()
    }

    companion object {
        /** Unique WorkManager name of the hand-over task. */
        const val WORK = "mod-health-hand-over"

        /** A tick while a hand-over task is queued or running adds nothing. */
        val POLICY = ExistingWorkPolicy.KEEP

        /**
         * How long the task holds the process for one hand-over: the flush wait, a subscription check (up to about
         * 16 s), the sends ([HealthConfig.SEND_BUDGET_MS] plus one put) with room to spare; WorkManager allows 10 min.
         */
        const val WAIT_MS = 90_000L

        /** Expedited tasks without a foreground notification: API 31+. */
        const val EXPEDITED_FROM_SDK = Build.VERSION_CODES.S

        /** Queues the hand-over (never throws to the caller: [HealthHandOver.onTick] falls back to our thread). */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, POLICY, request())
        }

        /** The task for [sdk]: expedited from [EXPEDITED_FROM_SDK], past the quota as a normal task. */
        internal fun request(sdk: Int = Build.VERSION.SDK_INT): OneTimeWorkRequest {
            val builder = OneTimeWorkRequestBuilder<HealthHandOverWorker>()
            if (sdk >= EXPEDITED_FROM_SDK) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            return builder.build()
        }
    }
}
