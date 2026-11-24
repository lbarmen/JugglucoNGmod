package mod.health

import android.app.Application
import androidx.work.ExistingWorkPolicy
import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.mod.health.HealthHandOverWorker

/**
 * JugglucoNGmod, watch block (part 5b, critic of part 5, C-5-1): what a tick queues - one task under one name, kept
 * while one is waiting or running, expedited where that needs no notification of ours, never delayed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthHandOverWorkerTest {

    @Test
    fun aTickQueuesOneExpeditedTaskUnderOneNameAndKeepsTheRunningOne() {
        assertEquals(ExistingWorkPolicy.KEEP, HealthHandOverWorker.POLICY)
        assertEquals("mod-health-hand-over", HealthHandOverWorker.WORK)
        val spec = HealthHandOverWorker.request(34).workSpec
        assertTrue(spec.expedited)
        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, spec.outOfQuotaPolicy)
        assertEquals(0L, spec.initialDelay)
        assertEquals(HealthHandOverWorker::class.java.name, spec.workerClassName)
        assertTrue(HealthHandOverWorker.request(31).workSpec.expedited)
    }

    @Test
    fun belowApi31TheTaskIsPlainSoItNeedsNoForegroundNotification() {
        val spec = HealthHandOverWorker.request(30).workSpec
        assertFalse(spec.expedited)
        assertEquals(0L, spec.initialDelay)
    }

    @Test
    fun theTaskHoldsTheProcessLongerThanOneHandOverCanTake() {
        // flush wait + a subscription check (about 16 s) + the send budget and one put, well inside WorkManager's 10 min
        assertTrue(HealthHandOverWorker.WAIT_MS >= 60_000L)
        assertTrue(HealthHandOverWorker.WAIT_MS < 10 * 60_000L)
    }
}
