package tk.glucodata.mod.watch.alarm

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import java.lang.ref.WeakReference
import tk.glucodata.Notify
import tk.glucodata.mod.watch.ModWatchLog
import tk.glucodata.ui.AlarmActivity

/**
 * JugglucoNGmod, watch block (part 8, critic 8 finding 1): never two alarm screens at once, ours and upstream's.
 *
 * Their screen can still be open when an alarm comes: after our screen handed over to it, in the window before a
 * late registration of theirs is taken back ([ModAlarmScreen]), or from a full-screen notification built while their
 * screen was in the slot - and an old notification of ours can open ours over theirs. Both are `singleTask` with no
 * task affinity, so they live in two tasks, and each one's `onStop` clears upstream's one "alarm window is showing"
 * flag (`Notify.setAlarmUiVisible`) while the other is on screen - their 2 s check then posts a needless "window not
 * shown" notification - and the older screen stays under the newer one after Snooze or Dismiss.
 *
 * So, from upstream's public activity callbacks (registered by our process-start provider):
 * - while their screen is open (created, not finishing), a new alarm goes to it ([upstreamOpen]: our registry entry
 *   returns their class, their `singleTask` gets it in `onNewIntent`) - one screen, the newest alarm on it;
 * - a screen that opens closes the other one (the newer alarm wins, as their own CLEAR_TOP launch does): theirs
 *   created -> ours finishes ([onActivityCreated]); ours shown -> theirs finishes ([ModAlarmActivity]);
 * - when one stops while the other is started, the flag is set again right after the stopping one cleared it
 *   (the callback runs from `Activity.onStop`, which both call after clearing it).
 * Not registered (provider failed): ours keeps its own rule (it does not clear the flag after handing over).
 */
internal object AlarmScreens : Application.ActivityLifecycleCallbacks {

    @Volatile private var app: Application? = null
    @Volatile private var upstream: WeakReference<Activity>? = null
    @Volatile private var upstreamStarted = false
    @Volatile private var ours: WeakReference<Activity>? = null
    @Volatile private var oursStarted = false

    /** Follows the activities of [context]'s application from now on; true when it does. Never throws. */
    fun register(context: Context): Boolean = try {
        val application = context.applicationContext as? Application ?: context as? Application
        when {
            application == null -> false
            application === app -> true
            else -> {
                app?.unregisterActivityLifecycleCallbacks(this)
                reset()
                application.registerActivityLifecycleCallbacks(this)
                app = application
                true
            }
        }
    } catch (t: Throwable) {
        ModWatchLog.w("alarm screens not followed: ${t.javaClass.simpleName}")
        false
    }

    /** Activities are followed: the "visible" flag is kept right by the callbacks. */
    val isTracking: Boolean get() = app != null

    /** Their alarm screen is open (created, not finishing, not destroyed). Any thread. */
    fun upstreamOpen(): Boolean = upstreamActivity() != null

    fun upstreamActivity(): Activity? = upstream?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    /** Their alarm screen is started now (on screen or about to be). */
    fun upstreamStarted(): Boolean = upstreamStarted && upstreamOpen()

    /** Ours shows a newer alarm: their open screen goes. */
    fun closeUpstream() {
        try {
            upstreamActivity()?.let {
                ModWatchLog.i("alarm screen: closing upstream's older one")
                it.finish()
            }
        } catch (t: Throwable) {
            ModWatchLog.w("upstream's alarm screen not closed: ${t.javaClass.simpleName}")
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        when (activity) {
            is AlarmActivity -> {
                upstream = WeakReference(activity)
                // theirs shows the newer alarm (or ours handed over to it): ours goes
                ours?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }?.let {
                    ModWatchLog.i("alarm screen: upstream's opened, closing ours")
                    it.finish()
                }
            }
            is ModAlarmActivity -> ours = WeakReference(activity)
        }
    }

    override fun onActivityStarted(activity: Activity) {
        when (activity) {
            is AlarmActivity -> upstreamStarted = true
            is ModAlarmActivity -> oursStarted = true
        }
    }

    override fun onActivityStopped(activity: Activity) {
        when (activity) {
            is AlarmActivity -> {
                upstreamStarted = false
                if (oursStarted) keepVisible()
            }
            is ModAlarmActivity -> {
                oursStarted = false
                if (upstreamStarted()) keepVisible()
            }
        }
    }

    override fun onActivityDestroyed(activity: Activity) {
        if (upstream?.get() === activity) {
            upstream = null
            upstreamStarted = false
        }
        if (ours?.get() === activity) {
            ours = null
            oursStarted = false
        }
    }

    private fun keepVisible() {
        try {
            Notify.setAlarmUiVisible(true)
        } catch (t: Throwable) {
            ModWatchLog.w("alarm visible flag not set: ${t.javaClass.simpleName}")
        }
    }

    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    private fun reset() {
        upstream = null
        upstreamStarted = false
        ours = null
        oursStarted = false
    }

    internal fun resetForTest() {
        try {
            app?.unregisterActivityLifecycleCallbacks(this)
        } catch (_: Throwable) {
        }
        app = null
        reset()
    }
}
