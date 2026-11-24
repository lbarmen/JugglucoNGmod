package tk.glucodata.mod.watch.alarm

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import tk.glucodata.UiRefreshBus
import tk.glucodata.mod.watch.ModWatchLog
import tk.glucodata.receivers.AlarmLaunchReceiver
import tk.glucodata.ui.AlarmActivity
import tk.glucodata.ui.AlarmActivityAccess
import tk.glucodata.ui.AlarmActivityHost

/**
 * JugglucoNGmod, watch block (part 8): our full-screen alarm ([ModAlarmActivity]) in place of upstream's, without a
 * hook. Upstream picks the alarm screen through a public one-slot registry, "the last one registered wins"
 * (`AlarmActivityAccess.register`); every launch asks it (`Notify.buildAlarmActivityIntent`, `AlarmLaunchReceiver`).
 * They fill it from `Specific.registerBridges` - the first statement of `Applic.onCreate` - and once more from
 * `WearVariantBootstrap.start`, which `Applic.initproc` calls (inside `Applic.onCreate` normally; later, from a
 * receiver, only when the native start failed there).
 *
 * Timing: our process-start provider ([ModAlarmStarter], created before `Application.onCreate`, in the same main
 * thread message) puts [claim] at the front of the main thread's queue: it runs right after `Applic.onCreate` (their
 * registration done) and before an alarm launch the system queued with the process start. Should theirs come back
 * later (the deferred `initproc` above), every data refresh claims the slot again ([watch]); until then their screen
 * shows - the alarm is never lost, only drawn the old way. A failure here leaves their screen in place.
 *
 * Our screen opens theirs when it fails itself ([openUpstream]); while their screen is open, our entry hands new
 * alarms to it ([AlarmScreens]).
 */
object ModAlarmScreen : AlarmActivityHost {

    /** Ours; theirs while their screen is open, so a new alarm lands on it and never opens a second one ([AlarmScreens]). */
    override fun newAlarmActivityIntent(context: Context): Intent =
        if (AlarmScreens.upstreamOpen()) Intent(context, AlarmActivity::class.java)
        else Intent(context, ModAlarmActivity::class.java)

    @Volatile
    private var watching = false

    /** The registry gives our screen now (or theirs only because their screen is open: our entry routes there). */
    fun isOurs(context: Context): Boolean {
        val name = AlarmActivityAccess.newIntent(context)?.component?.className
        return name == ModAlarmActivity::class.java.name ||
            (name == AlarmActivity::class.java.name && AlarmScreens.upstreamOpen())
    }

    /** Puts ours in upstream's slot unless it is there; true when it was set now. Any thread; never throws. */
    fun claim(context: Context): Boolean = try {
        if (isOurs(context)) {
            false
        } else {
            val had = AlarmActivityAccess.isRegistered()
            AlarmActivityAccess.register(this)
            ModWatchLog.i("alarm screen: ours (${if (had) "replaced upstream's" else "slot was empty"})")
            true
        }
    } catch (t: Throwable) {
        ModWatchLog.w("alarm screen not replaced: ${t.javaClass.simpleName}")
        false
    }

    /** From the process-start provider: [claim] as the next main thread message, then [watch] (tests: without). */
    fun claimAfterStart(context: Context, startWatch: Boolean = true) {
        val app = context.applicationContext ?: context
        try {
            Handler(Looper.getMainLooper()).postAtFrontOfQueue {
                claim(app)
                if (startWatch) watch(app)
            }
        } catch (t: Throwable) {
            ModWatchLog.w("alarm screen claim not posted: ${t.javaClass.simpleName}")
        }
    }

    /** Once per process: [claim] again on every data refresh (upstream's public revision counter). */
    private fun watch(app: Context) {
        if (watching) return
        watching = true
        reclaimOn(app, UiRefreshBus.revision, Dispatchers.Default)
    }

    /** Each value of [signals] claims the slot again; for tests with their own flow and dispatcher. */
    internal fun reclaimOn(app: Context, signals: Flow<*>, dispatcher: CoroutineContext): Job? = try {
        CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, t ->
            ModWatchLog.w("alarm screen watch failed: ${t.javaClass.simpleName}")
        }).launch { signals.collect { claim(app) } }
    } catch (t: Throwable) {
        ModWatchLog.w("alarm screen watch not started: ${t.javaClass.simpleName}")
        null
    }

    /**
     * Upstream's alarm screen with the extras of [source], as their `AlarmLaunchReceiver` opens it (same flags). True
     * when the start went through; never throws.
     */
    fun openUpstream(context: Context, source: Intent?): Boolean = try {
        val intent = Intent(context, AlarmActivity::class.java)
        if (source != null) intent.putExtras(source)
        intent.flags = AlarmLaunchReceiver.launchFlags(true)
        context.startActivity(intent)
        true
    } catch (t: Throwable) {
        ModWatchLog.w("upstream's alarm screen not opened: ${t.javaClass.simpleName}")
        false
    }

    internal fun resetForTest() {
        watching = false
    }
}
