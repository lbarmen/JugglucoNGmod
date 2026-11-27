package tk.glucodata.mod.pumpbt

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import tk.glucodata.mod.carelink.PumpSourceSwitch

/**
 * JugglucoNGmod, pump over Bluetooth (series part 2, part B2c): starts the Bluetooth pump source with the process,
 * without a hook in upstream files (as [tk.glucodata.mod.carelink.ModCareLinkStarter]).
 *
 * Declared only in Common/src/mobileMod/AndroidManifest.xml (authority `${applicationId}.mod-pumpbt`, not exported): it
 * runs in the phone `mod` build alone. Android creates content providers on every process start - an activity, a
 * reboot (upstream's NumAlarm), a restart of upstream's service, our alarm, the update receiver - before
 * Application.onCreate: onCreate here stays cheap and never throws; the start itself is posted to the main thread,
 * after Application.onCreate. Journal of 27.11: after an update the link did not come back for 19 min - so the link
 * starts at every process start, not when the window opens.
 *
 * Order (critic of B2a, C-3): the notifier first, so that what the source tells at its start (a lost bond, an unknown
 * record) is not lost; the made-up pump of test builds by class name (contract 3.5); then [PumpBtSource.init] - only
 * with the source on (the link waiting for the paired pump) or a pump's address kept (critic B2c, N6); then the
 * watchdog follows the source's on/off.
 */
class ModPumpStarter : ContentProvider() {

    override fun onCreate(): Boolean {
        try {
            val app = context?.applicationContext ?: return true
            Handler(Looper.getMainLooper()).post { start(app) }
        } catch (_: Throwable) {
            // our code must not take upstream's app down
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val TAG = "ModPumpBt"

        /** The made-up pump (part P): only in test builds (Common/src/modTrial); a candidate has none. */
        internal const val TRIAL_ENTRY = "tk.glucodata.mod.pumpbt.trial.PumpBtTrialEntry"

        /** Tests replace the steps to see their order. */
        @Volatile internal var steps: StarterSteps = RealStarterSteps

        /** The start of the process (main thread, after Application.onCreate). Each step on its own; never throws. */
        internal fun start(app: Context) {
            val s = steps
            step("notifier") { s.notifier() }
            step("trial") { s.trial(app) }
            step("source") { s.init(app) }
            step("watchdog") { s.watchdog(app) }
        }

        /** The source is started with the process: it is on, or the link state holds a pump's address. */
        internal inline fun sourceInUse(on: Boolean, addressKnown: () -> Boolean): Boolean = on || addressKnown()

        private inline fun step(name: String, block: () -> Unit) {
            try {
                block()
            } catch (t: Throwable) {
                PumpBtLog.w("pump start: $name failed (${t.javaClass.simpleName})")
            }
        }

        /**
         * Test builds only: the made-up pump. A candidate has no such class: silently on. Any other failure is logged
         * (Log.println: upstream's R8 rules strip Log.w in release builds).
         */
        internal fun installTrial(app: Context) {
            try {
                Class.forName(TRIAL_ENTRY).getMethod("install", Context::class.java).invoke(null, app)
            } catch (_: ClassNotFoundException) {
            } catch (t: Throwable) {
                val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                Log.println(Log.WARN, TAG, "pump trial not installed (${cause.javaClass.simpleName})")
            }
        }
    }
}

/** The steps of [ModPumpStarter.start], in their order. */
internal interface StarterSteps {
    fun notifier()
    fun trial(app: Context)
    fun init(app: Context)
    fun watchdog(app: Context)
}

internal object RealStarterSteps : StarterSteps {
    override fun notifier() {
        PumpBtSource.alert = PumpBtNotifier::show
    }

    override fun trial(app: Context) = ModPumpStarter.installTrial(app)

    /**
     * Only for someone who uses the pump source: on, or a pump once paired (its address kept). Everyone else's process
     * start reads no history memory and registers no bond receiver (critic B2c, N6); the trial window calls
     * [PumpBtSource.init] itself when it opens, and turning the source on works without it. The address is only read:
     * no link state file is made for someone who never used the pump (critic of the joints 2, N-5).
     */
    override fun init(app: Context) {
        if (ModPumpStarter.sourceInUse(PumpSourceSwitch.bluetoothOn(app)) { PumpBtSource.pumpAddressKnown(app) }) {
            PumpBtSource.init(app)
        }
    }

    override fun watchdog(app: Context) {
        PumpBtWatchdog.follow(app)
        PumpBtWatchdog.sync(app)
    }
}
