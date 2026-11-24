package tk.glucodata.mod.health.hs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import tk.glucodata.mod.health.HealthCore
import tk.glucodata.mod.health.HealthPermissions
import tk.glucodata.mod.health.HealthRegisterRetryWorker
import tk.glucodata.mod.health.HealthSources
import tk.glucodata.mod.watch.ModWatchLog

/**
 * JugglucoNGmod, watch block (part 4b): the watch restarted or our app was updated - a passive subscription may not
 * survive either (as Glucove's BootReceiver), and without a phone near no `/mod/tick` would bring it back. The
 * subscription is marked lost and made again at once on our background thread, while the broadcast is held open
 * ([goAsync]; the calls wait a few seconds at most). When that attempt does not subscribe, a retry task follows
 * ([HealthRegisterRetryWorker], Watch 7i) - no phone needed. Never throws.
 */
class ModHealthBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> "boot"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "update"
            else -> return
        }
        try {
            val app = context.applicationContext ?: context
            val pending = goAsync()
            HealthCore.executor.execute {
                try {
                    HealthCore.get(app).state.registeredTypes = ""
                    val registered = (HealthSources.current ?: ModHealthServicesSource).register(app, reason)
                    // Not subscribed (Health Services not ready yet, critic 7k C2): try again from the watch itself.
                    HealthRegisterRetryWorker.afterBootAttempt(registered, HealthPermissions.read(app).any) {
                        HealthRegisterRetryWorker.enqueue(app)
                    }
                } catch (t: Throwable) {
                    ModWatchLog.w("health restart register failed: ${t.javaClass.simpleName}")
                } finally {
                    pending.finish()
                }
            }
        } catch (t: Throwable) {
            ModWatchLog.w("health restart not handled: ${t.javaClass.simpleName}")
        }
    }
}
