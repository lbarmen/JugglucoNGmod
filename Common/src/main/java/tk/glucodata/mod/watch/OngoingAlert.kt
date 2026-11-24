package tk.glucodata.mod.watch

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import tk.glucodata.Natives
import tk.glucodata.Notify
import tk.glucodata.alerts.AlertActions
import tk.glucodata.alerts.AlertStateTracker
import tk.glucodata.alerts.AlertType
import tk.glucodata.ui.AlarmActivityAccess

/**
 * JugglucoNGmod, watch block: the standard alert that is going on now and its snooze, the same on the phone and on
 * the watch (watch plan, decision 3; user's decision: no alert going on - nothing happens).
 *
 * "Going on" is a kind from [AlertType.settingsEntries] for which [Notify.isCurrentStandardAlert] holds (presented,
 * not a manual test) and whose episode is still live: [AlertStateTracker.isEpisodeActive] and not
 * [AlertStateTracker.isDismissed] - the check upstream's Notify.isActivePresentedRecoveryBlocker adds, because "a
 * notification can outlive its condition". The presented mark alone stays after the sound and the condition are over,
 * and snoozing that stale kind would silence its next real alert. Without a live episode the presented kind is still
 * going on while its own alarm sound is on: LOSS never opens an episode (the native side calls Notify.lossalarm
 * directly), and any kind's sound may outlive its episode by up to a minute. "Its own" matters: other sounds set the
 * same native flag ([Natives.getisalarm]) and leave the mark alone - the "amount not entered" reminder (kind AMOUNT),
 * a melody tried in the settings. Which kind sounds is [Notify.resolveAlertKind]: the kind of a running repeat
 * session, otherwise the kind upstream's playringhier set together with the flag (cleared with it), otherwise the
 * presented one. Left open: trying the melody of the very kind whose stale mark is up, while pressing the button.
 * Custom (user) alerts are not covered: upstream has no public "which custom alert is ringing".
 *
 * The snooze is what upstream's "Snooze" button does: [AlertActions.handle] with the alert's default minutes (it
 * also sends their `/alertack` to the other device), then [AlarmActivityAccess.acknowledge] - `handle` stops the sound
 * but leaves a full-screen alarm open. Their alert code expects the main thread.
 */
object OngoingAlert {

    /** How long a listener thread waits for the main thread in [snoozeOngoingOnMain]. */
    const val MAIN_WAIT_MS = 5_000L

    /** [snoozeOngoingOnMain]: the main thread did not run the snooze in time, and it never will. */
    const val LATE = -2

    private const val PENDING = 0
    private const val STARTED = 1
    private const val CANCELLED = 2

    /** The kind (AlertType id) of the standard alert going on now, `null` when there is none. */
    fun find(): Int? = AlertType.settingsEntries.firstOrNull { isGoingOn(it) }?.id

    /**
     * [type] is presented now and its episode is live or its own alarm sound is still on (see the class comment).
     * The sound counts for every kind: when the next reading clears the condition while the sound plays, upstream's
     * runtime resets the episode but lets the sound finish (up to a minute), and their own "Snooze" still works then.
     * A stale mark without a sound, or with another kind's sound, snoozes nothing.
     */
    fun isGoingOn(type: AlertType): Boolean {
        if (!Notify.isCurrentStandardAlert(type.id)) return false
        if (AlertStateTracker.isEpisodeActive(type) && !AlertStateTracker.isDismissed(type)) return true
        return soundOn() && soundingKind() == type.id
    }

    private fun soundOn(): Boolean = try {
        Natives.getisalarm()
    } catch (_: Throwable) {
        false
    }

    private fun soundingKind(): Int = try {
        Notify.resolveAlertKind(-1)
    } catch (_: Throwable) {
        -1
    }

    /** Snoozes [kind] as upstream's "Snooze" does. Main thread only: elsewhere refuses and returns false. */
    fun snooze(kind: Int): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ModWatchLog.w("snooze refused: not on the main thread")
            return false
        }
        AlertActions.handle(kind, null, AlertActions.defaultSnoozeMinutes(kind, null))
        AlarmActivityAccess.acknowledge(kind)
        return true
    }

    /** On the main thread: snoozes the alert going on and returns its kind; `null` when there was none. */
    fun snoozeOngoing(): Int? {
        val kind = find() ?: return null
        return if (snooze(kind)) kind else null
    }

    /**
     * [snoozeOngoing] from any thread: runs it on the main thread and waits up to [waitMs]. The kind snoozed; `null`
     * when there was no alert or on a failure; [LATE] when the main thread did not start it in time - the queued task
     * is then cancelled and does nothing when it runs, so a late answer is never a snooze "from the past".
     */
    fun snoozeOngoingOnMain(waitMs: Long = MAIN_WAIT_MS): Int? {
        if (Looper.myLooper() == Looper.getMainLooper()) return snoozeOngoing()
        val result = AtomicReference<Int?>(null)
        val done = CountDownLatch(1)
        val state = AtomicInteger(PENDING)
        val posted = Handler(Looper.getMainLooper()).post {
            if (!state.compareAndSet(PENDING, STARTED)) {
                ModWatchLog.i("snooze: late task dropped")
                return@post
            }
            try {
                result.set(snoozeOngoing())
            } catch (t: Throwable) {
                ModWatchLog.w("snooze failed: ${t.javaClass.simpleName}")
            } finally {
                done.countDown()
            }
        }
        if (!posted) {
            ModWatchLog.w("snooze: main thread gone")
            return LATE
        }
        if (!done.await(waitMs, TimeUnit.MILLISECONDS)) {
            if (state.compareAndSet(PENDING, CANCELLED)) {
                ModWatchLog.w("snooze: main thread did not answer in time")
                return LATE
            }
            // started right at the deadline: it is running now, so its result is the answer
            if (!done.await(waitMs, TimeUnit.MILLISECONDS)) {
                ModWatchLog.w("snooze: main thread did not finish in time")
                return LATE
            }
        }
        return result.get()
    }
}
