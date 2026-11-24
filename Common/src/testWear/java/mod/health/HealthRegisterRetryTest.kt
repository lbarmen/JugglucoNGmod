package mod.health

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.mod.health.HealthPermissions.Perms
import tk.glucodata.mod.health.HealthRegisterRetryWorker
import tk.glucodata.mod.health.HealthRegisterRetryWorker.Outcome
import tk.glucodata.mod.health.HealthState
import tk.glucodata.mod.health.HealthSubscription

/**
 * JugglucoNGmod, watch block (Watch 7i, critic 7k C2 and N5): a failed subscription attempt is tried again - from the
 * watch itself after the boot attempt (a retry task, at most three runs), and on the ticks with a pause that grows
 * with the failures written by the one place that keeps an attempt's end. Made-up times.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthRegisterRetryTest {

    private val t0 = 1_800_000_000_000L
    private val minute = 60_000L
    private val all = Perms(hr = true, background = true, activity = true)
    private lateinit var state: HealthState

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(HealthState.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        state = HealthState(context)
    }

    private fun reasonNow(now: Long) = HealthSubscription.registerReason(
        nowMs = now, permsNow = all, permsAtRegistration = all.short(), registered = state.registeredTypes.isNotEmpty(),
        registeredAt = state.registeredAt, lastDataAt = state.lastDataAt, attemptAt = state.registerAttemptAt,
        failures = state.registerFailures,
    )

    @Test
    fun aFailedAttemptIsKeptSoTheNextTickTriesAgainAndASuccessEndsTheStreak() {
        // what ModHealthServicesSource.register writes at its end, and what maintain reads back
        state.registerAttemptAt = t0
        HealthSubscription.noteAttempt(state, "TimeoutException")
        assertEquals("TimeoutException", state.registerError)
        assertEquals(1, state.registerFailures)
        assertEquals("new", reasonNow(t0 + minute)) // not 15 minutes later
        state.registerAttemptAt = t0 + minute
        HealthSubscription.noteAttempt(state, HealthSubscription.errorForNoTypes(permsAny = true))
        assertEquals(2, state.registerFailures)
        assertNull(reasonNow(t0 + 5 * minute)) // the second failure: 5 minutes
        assertEquals("new", reasonNow(t0 + 6 * minute))
        HealthSubscription.noteAttempt(state, "")
        assertEquals("", state.registerError)
        assertEquals(0, state.registerFailures)
    }

    @Test
    fun theBootAttemptThatDidNotSubscribeQueuesTheRetry() {
        var queued = 0
        assertTrue(HealthRegisterRetryWorker.afterBootAttempt(registered = false, permsAny = true) { queued++ })
        assertEquals(1, queued)
        assertFalse(HealthRegisterRetryWorker.afterBootAttempt(registered = true, permsAny = true) { queued++ })
        assertFalse(HealthRegisterRetryWorker.afterBootAttempt(registered = false, permsAny = false) { queued++ })
        assertEquals(1, queued)
        // WorkManager not there: no exception to the receiver
        assertFalse(HealthRegisterRetryWorker.afterBootAttempt(registered = false, permsAny = true) { throw IllegalStateException() })
    }

    @Test
    fun theRetryRunsAtMostThreeTimesAndStopsOnceSubscribed() {
        var calls = 0
        val failing = { calls++; false }
        assertEquals(Outcome.RETRY, HealthRegisterRetryWorker.attempt(false, true, runAttempt = 0, register = failing))
        assertEquals(Outcome.RETRY, HealthRegisterRetryWorker.attempt(false, true, runAttempt = 1, register = failing))
        assertEquals(Outcome.DONE, HealthRegisterRetryWorker.attempt(false, true, runAttempt = 2, register = failing))
        assertEquals(3, calls)
        assertEquals(Outcome.DONE, HealthRegisterRetryWorker.attempt(false, true, runAttempt = 0) { true })
        // a tick subscribed in the meantime, or nothing is allowed: no call at all
        assertEquals(Outcome.DONE, HealthRegisterRetryWorker.attempt(true, true, runAttempt = 0, register = failing))
        assertEquals(Outcome.DONE, HealthRegisterRetryWorker.attempt(false, false, runAttempt = 0, register = failing))
        assertEquals(3, calls)
    }

    @Test
    fun theRetryTaskIsPlainLateAndUnique() {
        assertEquals("mod-health-register-retry", HealthRegisterRetryWorker.WORK)
        assertEquals(ExistingWorkPolicy.KEEP, HealthRegisterRetryWorker.POLICY)
        assertEquals(3, HealthRegisterRetryWorker.MAX_ATTEMPTS)
        val spec = HealthRegisterRetryWorker.request().workSpec
        assertFalse(spec.expedited)
        assertEquals(90_000L, spec.initialDelay)
        assertEquals(BackoffPolicy.LINEAR, spec.backoffPolicy)
        assertEquals(90_000L, spec.backoffDelayDuration)
        assertEquals(HealthRegisterRetryWorker::class.java.name, spec.workerClassName)
    }
}
