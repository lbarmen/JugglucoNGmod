package tk.glucodata.mod.carelink

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import kotlinx.coroutines.launch
import tk.glucodata.R

/**
 * JugglucoNGmod, CareLink (stage 4): starts our CareLink code without a hook in upstream files.
 *
 * Declared only in Common/src/mobileMod/AndroidManifest.xml, so it runs in the phone `mod` build alone; upstream's
 * own builds compile this class but never start it, and the watch never sees it. Android creates content providers
 * on every process start, before Application.onCreate: onCreate here stays cheap, touches none of upstream's
 * singletons and never throws (a crash here would take the whole app down before any screen).
 *
 * Stage 4c: sets the sign-in and sign-out listeners of [CareLinkAuth] and posts [CareLinkScheduler.arm] to the main
 * thread (it runs after Application.onCreate, when WorkManager has upstream's configuration). No Keystore, no network
 * here; without a CareLink sign-in nothing is scheduled.
 *
 * Stage 4e: the "CareLink" launcher shortcut (long press on the icon; a background thread, cheap and repeatable) and a
 * poll when the app comes to the front ([ForegroundPoll]: at most every 2 min by the poller's rule, only with a live
 * session, through WorkManager - never a poll on the main thread).
 */
class ModCareLinkStarter : ContentProvider() {

    override fun onCreate(): Boolean {
        try {
            CareLinkAuth.onLoginSuccess = { ctx -> afterLogin(ctx) }
            CareLinkAuth.onSignOut = { ctx -> afterSignOut(ctx) }
            val app = context?.applicationContext ?: return true
            installTrial(app)
            (app as? Application)?.registerActivityLifecycleCallbacks(ForegroundPoll(app))
            Handler(Looper.getMainLooper()).post {
                try {
                    CareLinkScheduler.arm(app)
                } catch (t: Throwable) {
                    CareLinkLog.w("arm on start failed (${t.javaClass.simpleName})")
                }
                CareLinkAuth.scope.launch { installShortcut(app) }
            }
        } catch (t: Throwable) {
            // our code must not take upstream's app down
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val TAG = "ModCareLink"

        internal const val SHORTCUT_ID = "mod-carelink"

        /**
         * The "CareLink" shortcut on a long press of the launcher icon, opening the CareLink window. Only where the
         * window is declared (the `mod` build); pushed, not set - other dynamic shortcuts of the app stay. Never throws.
         */
        internal fun installShortcut(app: Context) {
            try {
                val intent = CareLinkActivity.intentIfDeclared(app) ?: return
                val shortcut = ShortcutInfoCompat.Builder(app, SHORTCUT_ID)
                    .setShortLabel(app.getString(R.string.mod_carelink_shortcut_short))
                    .setLongLabel(app.getString(R.string.mod_carelink_shortcut_long))
                    .setIcon(IconCompat.createWithResource(app, R.mipmap.ic_launcher))
                    .setIntent(intent.setAction(Intent.ACTION_VIEW))
                    .build()
                ShortcutManagerCompat.pushDynamicShortcut(app, shortcut)
            } catch (t: Throwable) {
                CareLinkLog.w("shortcut not installed (${t.javaClass.simpleName})")
            }
        }

        /** The trial entry point: only in test builds (Common/src/modTrial, -PmodRelease=0); a candidate has none. */
        internal const val TRIAL_ENTRY = "tk.glucodata.mod.carelink.trial.CareLinkTrialEntry"

        /**
         * Test builds only (part 4p): the CareLink trial - a made-up pump through the real poll, no network. A
         * candidate has no such class: silently on. Any other failure is logged (Log.println: upstream's R8 rules strip
         * Log.w in release builds), or the trial would quietly not work.
         */
        internal fun installTrial(app: Context) {
            try {
                Class.forName(TRIAL_ENTRY).getMethod("install", Context::class.java).invoke(null, app)
            } catch (_: ClassNotFoundException) {
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                Log.println(Log.WARN, TAG, "trial not installed (${cause.javaClass.simpleName})")
            }
        }

        /**
         * After a sign-in (a background thread of [CareLinkAuth]): polling on, "sign in again" away, the chain armed
         * and the first poll - it takes the whole 24 h of the cloud window. The first poll stays pending until one is
         * written (critic 4c, C1). The last snapshot and data time go: they may be of another account, and an old data
         * time would show "no data for 2000 min" at once (critic 4c, N4). A sign-out that came between the session write
         * and this call leaves nothing to turn on (critic of the joints, N3).
         *
         * With the Bluetooth pump source on (series brief, decision 17) the cloud stays silent: polling is marked on
         * and its first poll pending (for when the source is turned off), "sign in again" goes; the snapshot, the data
         * time and the write clock are the source's and stay, and nothing is armed or queued.
         */
        internal fun afterLogin(context: Context) {
            val app = context.applicationContext
            if (!CareLinkAuth.hasSession(app)) return
            CareLinkSettings(app).enabled = true
            val store = CareLinkStateStore(app)
            store.firstPollPending = true
            if (PumpSourceSwitch.bluetoothOn(app)) {
                CareLinkNotifier.cancelNeedLogin(app)
                CareLinkScheduler.arm(app, force = true) // removes whatever is armed while the source is on
                return
            }
            store.update { it.copy(status = CareLinkStatus.OK, lastError = null, lastDataAtMs = null, pumpNow = null) }
            CareLinkNotifier.loggedIn(app)
            CareLinkScheduler.arm(app, force = true)
            CareLinkScheduler.enqueuePoll(app, PollReason.LOGIN, firstPoll = true)
        }

        /**
         * After a sign-out (may be the main thread; file and preference work only): polling off, state cleared, the
         * pump's IoB off the plate; the open basal segment and stop note are closed at their last data in the
         * background (the journal is not for the main thread; critic of the joints, C2).
         *
         * With the Bluetooth pump source on (series brief, decision 17) a sign-out touches nothing of the pump data:
         * the basal segment, the IoB plate, the data time, the snapshot, "no data" and "not saved" are the source's.
         * Only polling goes off, the cloud's alarms go and "sign in again" goes.
         */
        internal fun afterSignOut(context: Context) {
            val app = context.applicationContext
            CareLinkSettings(app).enabled = false
            CareLinkScheduler.disarm(app)
            if (PumpSourceSwitch.bluetoothOn(app)) {
                CareLinkNotifier.cancelNeedLogin(app)
                return
            }
            CareLinkStateStore(app).clear()
            CareLinkNotifier.cancelAll(app)
            CareLinkIob.clear()
            CareLinkPoller.closeAfterSignOut(app)
        }
    }
}

/**
 * JugglucoNGmod, CareLink (stage 4e): a poll when the app comes to the front - the first resumed window after all
 * windows were stopped (and the first one after the process start). [CareLinkScheduler.enqueuePoll] queues nothing
 * without a session or with polling off; the poller itself skips it when the last poll was under 2 min ago (plan,
 * decision 7). WorkManager, not a poll on the main thread (critic of the joints, N5).
 */
internal class ForegroundPoll(private val app: Context) : Application.ActivityLifecycleCallbacks {
    private var started = 0
    private var inBackground = true

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0) inBackground = true
    }

    override fun onActivityResumed(activity: Activity) {
        if (!inBackground) return
        inBackground = false
        try {
            CareLinkScheduler.enqueuePoll(app, PollReason.FOREGROUND)
        } catch (t: Throwable) {
            CareLinkLog.w("foreground poll not queued (${t.javaClass.simpleName})")
        }
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
