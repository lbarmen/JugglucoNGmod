package tk.glucodata.mod.watch.alarm

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.core.view.WindowCompat
import tk.glucodata.Log
import tk.glucodata.Notify
import tk.glucodata.mod.watch.ModWatchLog
import tk.glucodata.receivers.AlarmActionReceiver
import tk.glucodata.ui.AlarmActivityAccess

/**
 * JugglucoNGmod, watch block (part 8): our full-screen alarm on the watch, opened instead of upstream's
 * (`tk.glucodata.ui.AlarmActivity`, wear) through their registry ([ModAlarmScreen]). The owner's photo of 18.11: their
 * two half-width buttons at the bottom of the circle cut "Snooze" and "Dismiss"; here they are one above the other,
 * as wide as the circle allows, with whole words ([AlarmScreenLayout]).
 *
 * Everything but the look is theirs, step by step (their `AlarmActivity.kt`; the test `ModAlarmActivityTest` and the
 * source check `ModAlarmContractTest` follow it): the same extras ([AlarmContent]); edge to edge, screen on and over
 * the lock (`turnScreenOnAndKeyguard`) on create, on a new intent and on start; close at once when the alert was
 * already acknowledged (`AlarmActivityAccess.isAcknowledged` - the phone or our "Snooze alarm" button); watch for an
 * acknowledgement while shown (`observeAcknowledgement`, only for a standard alert); `Notify.setAlarmUiVisible` on
 * start and stop (their 2 s "window not shown" notification fallback reads it); the queued launch of this alert
 * cancelled once shown (`cancelQueuedAlarmActivityLaunch`); the buttons send their `AlarmActionReceiver` actions -
 * Snooze with the alert's snooze minutes, Dismiss - and close. Sound and vibration are theirs, untouched. Our
 * "Snooze alarm" button closes this screen as theirs: `OngoingAlert.snooze` -> `AlarmActivityAccess.acknowledge`.
 *
 * Any failure of ours (content, view, drawing) opens their screen with the same extras ([ModAlarmScreen.openUpstream])
 * and closes this one: the alarm must never be lost to our code. Never two alarm screens at once ([AlarmScreens]):
 * shown, ours closes an open screen of theirs; theirs opening closes ours. No dim mode.
 */
class ModAlarmActivity : Activity() {

    /** Their screen was opened in our place: without [AlarmScreens] it owns the "alarm UI visible" flag from now on. */
    private var handedOver = false
    private var handingOver = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            turnScreenOnAndKeyguard()
            showAlarmContent()
        } catch (t: Throwable) {
            fallBack("create", t)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (handingOver) {
            // already on the way to theirs: the newer alarm goes there too
            ModAlarmScreen.openUpstream(this, intent)
            return
        }
        try {
            turnScreenOnAndKeyguard()
            showAlarmContent()
        } catch (t: Throwable) {
            fallBack("new intent", t)
        }
    }

    override fun onStart() {
        super.onStart()
        if (handingOver) return
        try {
            if (closeIfAcknowledged()) return
            Notify.setAlarmUiVisible(true)
            observeAcknowledgement()
            turnScreenOnAndKeyguard()
        } catch (t: Throwable) {
            fallBack("start", t)
        }
    }

    override fun onStop() {
        try {
            AlarmActivityAccess.removeObserver(this)
            // Followed screens: cleared always - when theirs is started, the stop callback (super.onStop below) sets
            // it again; when their screen never came up after a hand-over, it stays cleared and their missed-window
            // notification can show (critic 8, finding 3). Not followed: after a hand-over the flag is theirs.
            if (AlarmScreens.isTracking || !handedOver) Notify.setAlarmUiVisible(false)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } catch (t: Throwable) {
            ModWatchLog.w("alarm screen stop failed: ${t.javaClass.simpleName}")
        }
        super.onStop()
    }

    private fun observeAcknowledgement() {
        AlarmActivityAccess.removeObserver(this)
        if (intent.getStringExtra(Notify.EXTRA_CUSTOM_ALERT_ID) == null) {
            AlarmActivityAccess.observeAcknowledgement(this, intent.getIntExtra(tk.glucodata.ui.AlarmActivity.EXTRA_ALERT_TYPE_ID, -1)) { finish() }
        }
    }

    private fun closeIfAcknowledged(): Boolean {
        if (!AlarmActivityAccess.isAcknowledged(intent)) return false
        finish()
        return true
    }

    private fun showAlarmContent() {
        if (closeIfAcknowledged()) return
        observeAcknowledgement()
        val model = AlarmContent.build(this, intent)
        Notify.cancelQueuedAlarmActivityLaunch(
            Notify.resolveAlertKind(model.alertTypeId),
            model.customAlertId,
            "mod-wear-alarm-activity-visible"
        )
        contentHook?.invoke(model)
        setContentView(
            AlarmScreenLayout(
                this,
                model.ui,
                onSnooze = {
                    sendAlarmAction(AlarmActionReceiver.ACTION_SNOOZE, model.alertTypeId, model.customAlertId, model.snoozeMinutes)
                },
                onDismiss = {
                    sendAlarmAction(AlarmActionReceiver.ACTION_DISMISS, model.alertTypeId, model.customAlertId, null)
                },
                onFailure = { fallBack("view", it) },
            )
        )
        // an older alarm on their screen (opened by an old notification of ours over it): ours is the newer one
        AlarmScreens.closeUpstream()
    }

    /** Their `sendAlarmAction`: the action to their receiver, then close (also when the send fails). */
    private fun sendAlarmAction(action: String, alertTypeId: Int, customAlertId: String?, snoozeMinutes: Int?) {
        val actionIntent = Intent(this, AlarmActionReceiver::class.java).apply {
            this.action = action
            putExtra(AlarmActionReceiver.EXTRA_ALERT_TYPE_ID, alertTypeId)
            if (!customAlertId.isNullOrBlank()) {
                putExtra(Notify.EXTRA_CUSTOM_ALERT_ID, customAlertId)
            }
            if (snoozeMinutes != null) {
                putExtra(AlarmActionReceiver.EXTRA_SNOOZE_MINUTES, snoozeMinutes)
            }
        }
        try {
            sendBroadcast(actionIntent)
        } catch (t: Throwable) {
            Log.stack(LOG_ID, "send alarm action", t)
        } finally {
            finish()
        }
    }

    private fun turnScreenOnAndKeyguard() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // as theirs; a refusal here is no failure of the screen (it shows over the lock anyway)
            try {
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                keyguardManager?.requestDismissKeyguard(this, null)
            } catch (t: Throwable) {
                ModWatchLog.w("alarm screen: keyguard dismiss refused: ${t.javaClass.simpleName}")
            }
        }
    }

    /**
     * Ours failed at [where]: their screen with this intent's extras, ours closed. Once; when their start does not go
     * through, ours still closes (and clears the visible flag on stop), so their missed-window notification shows.
     */
    internal fun fallBack(where: String, t: Throwable?) {
        if (handingOver) return
        handingOver = true
        // the exception's class and where it was thrown (code only, nothing personal)
        ModWatchLog.w("alarm screen: $where failed (${t?.javaClass?.simpleName} at ${t?.stackTrace?.firstOrNull()}), " +
            "opening upstream's")
        try {
            AlarmActivityAccess.removeObserver(this)
        } catch (_: Throwable) {
        }
        handedOver = ModAlarmScreen.openUpstream(this, intent)
        try {
            finish()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val LOG_ID = "ModWearAlarmActivity"

        /** Tests only: called with the model before the view is set; a throw here is a failure of ours. */
        @Volatile
        internal var contentHook: ((AlarmModel) -> Unit)? = null
    }
}
