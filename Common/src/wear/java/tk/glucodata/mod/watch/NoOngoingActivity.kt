package tk.glucodata.mod.watch

import android.app.Notification
import android.content.Context
import android.os.Handler
import android.os.Looper
import tk.glucodata.OngoingNotification
import tk.glucodata.OngoingNotificationAccess

/**
 * JugglucoNGmod, watch block (part 7j): no ongoing activity for upstream's watch service - the owner's request of
 * 19.11.2026, after the white dot Wear OS draws at the bottom of every watch face for it stayed bright with our faint
 * copy of its icon (part 7c): "remove it completely", as the xDrip watch app has none.
 *
 * Upstream keeps the watch's ongoing activity in a public one-slot registry ([OngoingNotificationAccess], filled by
 * their `Specific.registerBridges` from `Applic.onCreate`) and asks it only when their foreground service starts
 * (`Notify.foregroundno` -> [attach]) and on each reading ([updateStatus]); their own contract says a caller with
 * nothing there keeps its own notification and "behaves exactly as it did before". So ours takes the slot: the service
 * notification goes as it is, without an ongoing activity, and the service runs as before; a tap on the value of our
 * face still opens their app.
 *
 * Timing, without a hook: our process-start provider ([tk.glucodata.mod.health.hs.ModHealthStarter]) runs before
 * `Applic.onCreate`, so it puts [claim] at the front of the main thread's queue - it runs right after the application
 * is bound (their registration done) and before any service creation already queued, also when the system starts the
 * process for their restarted service (critic 7l-7m, M1). [claim] again on each phone tick keeps the slot ours.
 * Should theirs still be attached (a start this order did not cover), [updateStatus] goes on to it, so its value does
 * not freeze; with nothing attached their update finds no ongoing activity and does nothing.
 */
object NoOngoingActivity : OngoingNotification {

    /** Upstream's, as it was in the slot when ours took it; only for [updateStatus]. */
    @Volatile
    private var displaced: OngoingNotification? = null

    /** The notification as upstream gives it, ongoing, with no ongoing activity attached. */
    override fun attach(context: Context, notification: Notification, notificationId: Int): Notification =
        notification.also { it.flags = it.flags or Notification.FLAG_ONGOING_EVENT }

    /** Their status update, should their ongoing activity still be attached (it does nothing otherwise). */
    override fun updateStatus(context: Context?, notificationId: Int) {
        try {
            displaced?.updateStatus(context, notificationId)
        } catch (_: Throwable) {
        }
    }

    /** Puts ours in upstream's slot unless it is there; true when it replaced another. Never throws. */
    fun claim(): Boolean = try {
        val current = OngoingNotificationAccess.get()
        if (current === this) {
            false
        } else {
            val had = current != null
            if (current != null) displaced = current
            OngoingNotificationAccess.register(this)
            ModWatchLog.i("ongoing activity: none (${if (had) "replaced upstream's" else "slot was empty"})")
            true
        }
    } catch (t: Throwable) {
        ModWatchLog.w("ongoing activity not replaced: ${t.javaClass.simpleName}")
        false
    }

    /**
     * [claim] on the main thread as the next message, ahead of any queued one (from a provider: right after
     * `Application.onCreate`, before a service creation the system queued with the process start).
     */
    fun claimAfterStart() {
        try {
            Handler(Looper.getMainLooper()).postAtFrontOfQueue { claim() }
        } catch (t: Throwable) {
            ModWatchLog.w("ongoing activity claim not posted: ${t.javaClass.simpleName}")
        }
    }
}
