package tk.glucodata.mod.aod

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.WindowManager
import java.lang.ref.WeakReference
import tk.glucodata.Log

/**
 * JugglucoNGmod, phone always-on display: puts upstream's overlay window up again, at most once in [MIN_INTERVAL_MS],
 * while a Samsung phone's screen dozes.
 *
 * Samsung's always-on display (One UI 6.1, Galaxy S24 Ultra, probe 26.11) keeps the screen in DOZE_SUSPEND and shows a
 * redrawn window only when the system wakes the screen for a moment - every one to a few minutes. A window added anew
 * is shown: xDrip+'s `AlwaysOnDisplayService` replaces its overlay window on every minute tick, and its value kept
 * changing on the owner's phone (27.11) while ours stood still. So after a changed block we remove upstream's window
 * and add the same view with the same parameters again. Only on Samsung (elsewhere the always-on screen may show a
 * redraw anyway, and a re-add would only blink), only while the phone is not interactive and the display dozes (when
 * the screen is being turned on, the display is still DOZE for a moment and a re-add would leave it without the block).
 */
object ModAodWindow {

    private const val TAG = "ModAod"

    /** Below a minute: a tick that comes late must not push the next re-add a whole minute on. */
    internal const val MIN_INTERVAL_MS = 40_000L

    private var lastReattachElapsed = 0L

    /** A view whose window we took down and could not add again; put it up at the next call, not a minute later. */
    private var notPutBack: WeakReference<View>? = null

    internal fun applies(manufacturer: String?): Boolean = manufacturer.equals("samsung", ignoreCase = true)

    internal fun dozing(state: Int): Boolean = state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND

    /** [lastElapsed] 0 - never put up again in this process. */
    internal fun due(state: Int, nowElapsed: Long, lastElapsed: Long): Boolean =
        dozing(state) && (lastElapsed == 0L || nowElapsed - lastElapsed >= MIN_INTERVAL_MS)

    /** [view] - upstream's overlay root, attached with its `WindowManager.LayoutParams`; the main thread only. */
    @JvmStatic
    fun reattachIfDozing(context: Context, view: View) {
        if (!applies(Build.MANUFACTURER)) return
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        if (notPutBack?.get() === view && !view.isAttachedToWindow && view.parent == null) {
            // upstream's service adds its window only when it has none: without this the block stays away till unlock
            if (add(wm, view, params)) notPutBack = null
            return
        }
        if (!view.isAttachedToWindow) return
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power == null || power.isInteractive) return
        val state = view.display?.state ?: return
        val now = SystemClock.elapsedRealtime()
        if (!due(state, now, lastReattachElapsed)) return
        lastReattachElapsed = now
        try {
            wm.removeViewImmediate(view)
        } catch (t: Throwable) {
            Log.w(TAG, "window not taken down: ${t.javaClass.simpleName}")
            return
        }
        notPutBack = if (add(wm, view, params)) null else WeakReference(view)
    }

    private fun add(wm: WindowManager, view: View, params: WindowManager.LayoutParams): Boolean =
        try {
            wm.addView(view, params)
            true
        } catch (t: Throwable) {
            Log.stack(TAG, "window not put back", t)
            false
        }
}
