package tk.glucodata.mod.watch

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import tk.glucodata.R

/**
 * JugglucoNGmod, watch block: what the "Snooze alarm" button does (watch plan, decision 3; user's decision
 * 17.11: an alert going on is snoozed as if "Snooze" was pressed on the phone, no alert - nothing).
 *
 * On a press: the watch's own alert going on is snoozed ([OngoingAlert]; upstream's `/alertack` then silences the
 * phone too) with one short buzz, and `/mod/snooze` always goes to the phone, which snoozes its own alert going on and
 * answers `/mod/snooze/done`. A "snoozed" answer buzzes once, unless the watch buzzed itself within [BUZZ_DEDUP_MS] -
 * so one press is never two buzzes; "no alert" and "late" (the phone did not get to it) are silent. A repeated press
 * is harmless: upstream's action times drop a second acknowledgement. The log never names the alert kind.
 *
 * A short text says what happened ([statusFor]; user's request 18.11: a press showed nothing): "Alarm snoozed" when
 * the watch or the phone snoozed, "No alarm" when neither had one, "Phone not connected" when [WatchPhoneLink] reached
 * no phone and the watch had nothing to snooze. One press is never two texts: the phone's "snoozed" by the buzz
 * window, "No alarm" and "Phone not connected" not within [TEXT_QUIET_MS] of an "Alarm snoozed" (critic of part 6:
 * the phone lookup and the send take up to 10-20 s, and a double press may get "snoozed" then "no alert").
 */
object SnoozeButton {

    /** The confirmation buzz, ms. */
    const val BUZZ_MS = 80L
    /** No buzz for the phone's answer when the watch buzzed itself this recently; the same window for the texts. */
    const val BUZZ_DEDUP_MS = 5_000L
    /** No "No alarm" / "Phone not connected" this long after an "Alarm snoozed": longer than the phone lookup. */
    const val TEXT_QUIET_MS = 25_000L

    /** [SystemClock.elapsedRealtime] of the last buzz: a wall clock set back must not change the dedup. */
    @Volatile
    private var lastBuzzElapsedMs = 0L

    /** Both elapsedRealtime, 0 - never; read and written under [statusLock] (main thread, listener, link worker). */
    private var lastShownElapsedMs = 0L
    private var lastSnoozedShownElapsedMs = 0L
    private val statusLock = Any()

    /** What happened, as far as the text is concerned. */
    enum class Event { WATCH_SNOOZED, WATCH_NO_ALERT, PHONE_SNOOZED, PHONE_NO_ALERT, PHONE_LATE, PHONE_UNREACHABLE }

    /** The text shown. */
    enum class Status(val text: Int) {
        SNOOZED(R.string.mod_watch_snooze_done),
        NO_ALARM(R.string.mod_watch_snooze_no_alarm),
        PHONE_NOT_CONNECTED(R.string.mod_watch_snooze_no_phone),
    }

    /**
     * The press, on the main thread (the button activity's onCreate). [send] gets the application context, the
     * press time ([pressedAtMs], wall clock) and what to call when no phone was reached; it must not block. Returns
     * the kind snoozed on the watch, `null` when no alert was going on here.
     */
    fun press(
        context: Context,
        pressedAtMs: Long = System.currentTimeMillis(),
        elapsedMs: Long = SystemClock.elapsedRealtime(),
        send: (Context, Long, () -> Unit) -> Unit = WatchPhoneLink::sendSnooze,
    ): Int? {
        val app = context.applicationContext ?: context
        var kind: Int? = null
        try {
            kind = OngoingAlert.snoozeOngoing()
            if (kind != null) buzz(app, elapsedMs, Buzz.PRESS)
            ModWatchLog.i("button: " + if (kind != null) "snoozed on the watch" else "no alert on the watch")
        } catch (t: Throwable) {
            ModWatchLog.w("button: local snooze failed: ${t.javaClass.simpleName}")
        }
        try {
            show(app, if (kind != null) Event.WATCH_SNOOZED else Event.WATCH_NO_ALERT, elapsedMs, elapsedMs)
        } catch (t: Throwable) {
            ModWatchLog.w("button: status failed: ${t.javaClass.simpleName}")
        }
        try {
            send(app, pressedAtMs) { onPhoneUnreachable(app, elapsedMs) }
        } catch (t: Throwable) {
            ModWatchLog.w("button: send failed: ${t.javaClass.simpleName}")
            onPhoneUnreachable(app, elapsedMs)
        }
        return kind
    }

    /**
     * [WatchPhoneLink] reached no phone for the press made at [pressElapsedMs] (no reachable node, or every send
     * failed); any thread. The rule counts from the press: the link may take its whole timeout to find out.
     * Returns true when a text was shown.
     */
    fun onPhoneUnreachable(
        context: Context,
        pressElapsedMs: Long,
        elapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean = show(context, Event.PHONE_UNREACHABLE, pressElapsedMs, elapsedMs)

    /** The phone's `/mod/snooze/done`. Returns true when it buzzed. */
    fun onPhoneAnswer(
        context: Context,
        done: ModWatchMessages.SnoozeDone,
        elapsedMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean {
        if (done.late) {
            ModWatchLog.i("phone: late")
            show(context, Event.PHONE_LATE, elapsedMs, elapsedMs)
            return false
        }
        if (done.snoozedKind == null) {
            ModWatchLog.i("phone: no alert")
            show(context, Event.PHONE_NO_ALERT, elapsedMs, elapsedMs)
            return false
        }
        ModWatchLog.i("phone: snoozed")
        show(context, Event.PHONE_SNOOZED, elapsedMs, elapsedMs)
        if (!shouldBuzzForPhone(elapsedMs, lastBuzzElapsedMs)) return false
        buzz(context, elapsedMs, Buzz.PHONE_ANSWER)
        return true
    }

    /** The phone's "snoozed" buzzes unless the watch buzzed within [BUZZ_DEDUP_MS] (both times - elapsedRealtime). */
    fun shouldBuzzForPhone(elapsedMs: Long, lastBuzzElapsedMs: Long): Boolean =
        lastBuzzElapsedMs <= 0L || elapsedMs < lastBuzzElapsedMs || elapsedMs - lastBuzzElapsedMs >= BUZZ_DEDUP_MS

    /**
     * The text for [event] at [elapsedMs], `null` - none. [lastShownElapsedMs] - the last text shown,
     * [lastSnoozedShownElapsedMs] - the last "Alarm snoozed" shown, by the watch or the phone (all elapsedRealtime,
     * 0 - never). The watch's own snooze always shows; the phone's "snoozed" only when no text was shown within
     * [BUZZ_DEDUP_MS] (the buzz rule); the phone's "no alert" and "no phone" not within [TEXT_QUIET_MS] of an "Alarm
     * snoozed" - that press is done, and one press is one text; "late" never (the phone did not get to it: the log
     * says so).
     */
    fun statusFor(event: Event, elapsedMs: Long, lastShownElapsedMs: Long, lastSnoozedShownElapsedMs: Long): Status? =
        when (event) {
            Event.WATCH_SNOOZED -> Status.SNOOZED
            Event.WATCH_NO_ALERT, Event.PHONE_LATE -> null
            Event.PHONE_SNOOZED -> if (within(elapsedMs, lastShownElapsedMs, BUZZ_DEDUP_MS)) null else Status.SNOOZED
            Event.PHONE_NO_ALERT ->
                if (within(elapsedMs, lastSnoozedShownElapsedMs, TEXT_QUIET_MS)) null else Status.NO_ALARM
            Event.PHONE_UNREACHABLE ->
                if (within(elapsedMs, lastSnoozedShownElapsedMs, TEXT_QUIET_MS)) null else Status.PHONE_NOT_CONNECTED
        }

    /** [sinceMs] is less than [windowMs] before [elapsedMs] (never, or a restarted clock - not within). */
    private fun within(elapsedMs: Long, sinceMs: Long, windowMs: Long): Boolean =
        sinceMs > 0L && elapsedMs >= sinceMs && elapsedMs - sinceMs < windowMs

    /**
     * Decides and records under one lock, then posts the text to the main thread. [eventElapsedMs] - the time the
     * rule counts from, [nowElapsedMs] - the time recorded as shown. Returns true when a text was posted.
     */
    private fun show(context: Context, event: Event, eventElapsedMs: Long, nowElapsedMs: Long): Boolean {
        val status = synchronized(statusLock) {
            val decided = statusFor(event, eventElapsedMs, lastShownElapsedMs, lastSnoozedShownElapsedMs)
            if (decided != null) lastShownElapsedMs = nowElapsedMs
            if (decided == Status.SNOOZED) lastSnoozedShownElapsedMs = nowElapsedMs
            decided
        } ?: return false
        try {
            Handler(Looper.getMainLooper()).post {
                try {
                    Toast.makeText(context, status.text, Toast.LENGTH_SHORT).show()
                } catch (t: Throwable) {
                    ModWatchLog.w("status text failed: ${t.javaClass.simpleName}")
                }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("status text failed: ${t.javaClass.simpleName}")
            return false
        }
        return true
    }

    internal fun resetForTest() {
        lastBuzzElapsedMs = 0L
        synchronized(statusLock) {
            lastShownElapsedMs = 0L
            lastSnoozedShownElapsedMs = 0L
        }
    }

    /**
     * Why the watch buzzes. A press comes with our activity in front, and the button is a physical one: hardware
     * feedback (not "touch", which the "touch vibration" setting and the background filter drop). The phone's answer
     * comes later in the background listener: notification usage, which Android lets through for a background app.
     */
    internal enum class Buzz { PRESS, PHONE_ANSWER }

    private fun buzz(context: Context, elapsedMs: Long, why: Buzz) {
        lastBuzzElapsedMs = elapsedMs
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            val effect = VibrationEffect.createOneShot(BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE)
            if (Build.VERSION.SDK_INT >= 33) {
                val usage = if (why == Buzz.PRESS) VibrationAttributes.USAGE_HARDWARE_FEEDBACK
                    else VibrationAttributes.USAGE_NOTIFICATION
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(usage))
            } else {
                val attributes = if (why == Buzz.PRESS) {
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                } else {
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                }
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, attributes)
            }
        } catch (t: Throwable) {
            ModWatchLog.w("buzz failed: ${t.javaClass.simpleName}")
        }
    }
}
