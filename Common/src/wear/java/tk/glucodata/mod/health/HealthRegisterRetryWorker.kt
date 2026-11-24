package tk.glucodata.mod.health

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import tk.glucodata.mod.watch.ModWatchLog

/**
 * JugglucoNGmod, watch block (Watch 7i, critic 7k, C2): the subscription attempt at boot or after an update failed
 * (watch log 19.11.2026: Health Services may not answer yet right after boot) - try again from the watch itself, a
 * plain one-time WorkManager task [DELAY_MS] later, at most [MAX_ATTEMPTS] runs [DELAY_MS] apart and more
 * (WorkManager's linear back-off). Without it only a phone tick ([HealthSource.maintain]) tried again, and with the
 * phone away nothing came for hours: without a subscription Health Services delivers nothing that could wake us.
 *
 * Not expedited: a minute more or less does not matter here. Unique name with KEEP: a second boot broadcast while one
 * is waiting adds nothing. Each run first looks whether a subscription is in place by now (a tick may have made it).
 */
class HealthRegisterRetryWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val outcome = try {
            val app = applicationContext
            val source = HealthSources.current
            if (source == null) {
                Outcome.DONE
            } else {
                attempt(
                    registered = HealthCore.get(app).state.registeredTypes.isNotEmpty(),
                    permsAny = HealthPermissions.read(app).any,
                    runAttempt = runAttemptCount,
                ) { source.register(app, REASON) }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("health register retry failed: ${t.javaClass.simpleName}")
            if (runAttemptCount + 1 < MAX_ATTEMPTS) Outcome.RETRY else Outcome.DONE
        }
        return if (outcome == Outcome.RETRY) Result.retry() else Result.success()
    }

    /** What one run ends with: done (subscribed, nothing to do, or the last attempt), or another run later. */
    enum class Outcome { DONE, RETRY }

    companion object {
        /** Unique WorkManager name of the retry task. */
        const val WORK = "mod-health-register-retry"

        /** A second boot broadcast while the retry waits or runs adds nothing. */
        val POLICY = ExistingWorkPolicy.KEEP

        /** The first run this long after the failed attempt; each further one this much later again (linear). */
        const val DELAY_MS = 90_000L

        /** At most this many runs, so a failure that stays costs three attempts (the ticks go on with their pause). */
        const val MAX_ATTEMPTS = 3

        /** The reason in the watch log: `health registered reason=retry`. */
        const val REASON = "retry"

        /**
         * One run, [runAttempt] counted from 0: nothing when a subscription is in place ([registered]) or nothing is
         * allowed ([permsAny] false); otherwise [register], and when it fails another run unless this was the last.
         */
        fun attempt(registered: Boolean, permsAny: Boolean, runAttempt: Int, register: () -> Boolean): Outcome {
            if (registered || !permsAny) return Outcome.DONE
            if (register()) return Outcome.DONE
            return if (runAttempt + 1 < MAX_ATTEMPTS) Outcome.RETRY else Outcome.DONE
        }

        /**
         * After the attempt of the boot receiver: queues the retry when it did not subscribe ([registered] false) and
         * something is allowed ([permsAny]) - [enqueue] is the real queueing ([enqueue] below) or a test's. Returns
         * whether it queued. Never throws.
         */
        fun afterBootAttempt(registered: Boolean, permsAny: Boolean, enqueue: () -> Unit): Boolean {
            if (registered || !permsAny) return false
            return try {
                enqueue()
                true
            } catch (t: Throwable) {
                ModWatchLog.w("health register retry not queued: ${t.javaClass.simpleName}")
                false
            }
        }

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, POLICY, request())
        }

        /** The task: plain, [DELAY_MS] late, retried linearly [DELAY_MS] apart and more. */
        internal fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<HealthRegisterRetryWorker>()
            .setInitialDelay(DELAY_MS, TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.LINEAR, DELAY_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
