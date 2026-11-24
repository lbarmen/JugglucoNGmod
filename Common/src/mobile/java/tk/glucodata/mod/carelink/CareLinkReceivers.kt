package tk.glucodata.mod.carelink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * JugglucoNGmod, CareLink (stage 4c): the poll alarm. It does not poll (a broadcast has ~10 s): the next alarm first,
 * so the chain never breaks, then a one-time [CareLinkWorker] in WorkManager and the "no data" check only when no poll
 * runs ([CareLinkNotifier.checkNoDataOnAlarm]: with no network WorkManager does not start the poll, but the data still
 * gets old; critic 4d, C1); the broadcast stays alive until WorkManager has stored it - as upstream's
 * LumiflexFollowerPollReceiver. No wake lock by hand: WorkManager holds its own. Not exported (manifest).
 */
class CareLinkAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CareLinkScheduler.ACTION_POLL) return
        val pending = goAsync()
        try {
            if (!CareLinkScheduler.arm(context, force = true)) {
                pending.finish()
                return
            }
            val prevAlarm = CareLinkScheduler.markAlarm(context, CareLinkAuth.clock())
            val op = CareLinkScheduler.enqueuePoll(context, PollReason.ALARM)
            CareLinkNotifier.checkNoDataOnAlarm(context, pollQueued = op != null, prevAlarmAtMs = prevAlarm)
            if (op == null) pending.finish() else op.result.addListener({ pending.finish() }, { it.run() })
        } catch (t: Throwable) {
            CareLinkLog.w("poll alarm failed (${t.javaClass.simpleName})")
            try {
                pending.finish()
            } catch (_: Throwable) {
            }
        }
    }
}

/**
 * JugglucoNGmod, CareLink (stage 4c): after a reboot (alarms are gone) and after an app update - arm the chain again.
 * Exported with its intent filter (Android 12+ wants the value stated); only the system sends these actions.
 */
class CareLinkBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            CareLinkScheduler.arm(context, force = true)
        } catch (t: Throwable) {
            CareLinkLog.w("arm after $action failed (${t.javaClass.simpleName})")
        }
    }
}
