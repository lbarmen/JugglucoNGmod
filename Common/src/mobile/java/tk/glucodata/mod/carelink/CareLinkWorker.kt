package tk.glucodata.mod.carelink

import android.content.Context
import androidx.annotation.Keep
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/**
 * JugglucoNGmod, CareLink (stage 4c): the poll itself, in WorkManager - one-time from the alarm, a sign-in or the
 * app, and the periodic 15-min safety net (no input: reason WORKER; it also re-arms a broken alarm chain).
 * `@Keep`: WorkManager creates it by its stored class name, which R8 must not change between versions.
 * Always "success": a failed poll is in the state, and the next alarm tries again - no WorkManager retries on top.
 */
@Keep
class CareLinkWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            val reason = inputData.getString(CareLinkScheduler.KEY_REASON)
                ?.let { r -> PollReason.values().firstOrNull { it.name == r } } ?: PollReason.WORKER
            val first = inputData.getBoolean(CareLinkScheduler.KEY_FIRST, false)
            if (reason == PollReason.WORKER) CareLinkScheduler.arm(applicationContext)
            CareLinkPoller.poll(applicationContext, reason, first)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            CareLinkLog.w("poll work failed (${t.javaClass.simpleName})")
        }
        return Result.success()
    }
}
