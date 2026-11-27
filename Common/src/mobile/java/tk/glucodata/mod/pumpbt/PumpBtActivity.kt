package tk.glucodata.mod.pumpbt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import tk.glucodata.mod.carelink.CareLinkNotifier
import tk.glucodata.mod.carelink.CareLinkSettings
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.carelink.CareLinkTheme
import tk.glucodata.mod.carelink.PumpNow
import tk.glucodata.ui.JugglucoTheme
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * JugglucoNGmod, pump over Bluetooth: the one pump window - reads a Medtronic pump over Bluetooth without MiniMed
 * Mobile, read only. Opened from our row in upstream's settings ([tk.glucodata.mod.carelink.ModCareLinkEntry], hook
 * `carelink-settings`), the launcher shortcut, our notifications and the old CareLink window
 * ([tk.glucodata.mod.carelink.CareLinkActivity] only forwards here, for pinned shortcuts and old pending intents).
 * The link lives in [PumpBtSource] (one per process; [PumpBtTrial] is the window's thin layer over it); this window
 * only shows it and presses its buttons. Opening asks nothing: permissions on Allow, on a start or on turning
 * permanent operation on; with permanent operation on the source keeps the link (opening does not start a new one).
 * The window's settings (threshold, insulin type, carbs, Nightscout, the phone checks) are [PumpBtSettingsModel];
 * the phone actions (battery optimization, notifications, our channel's system screen) are here. The pump's numbers
 * come from the kept snapshot (`pump.json`, [CareLinkStateStore]: what the status bar and the watch show, it outlives a
 * restart), read again when the window comes back and after every reading of the source.
 * The screen stays on only while a pairing goes on ([PumpBtWindow.keepScreenOn]).
 *
 * Declared only in Common/src/mobileMod/AndroidManifest.xml (not exported); [intentIfDeclared] gives null in upstream's
 * builds and the settings row is not drawn. Closes at once while upstream's restricted mode is locked. The drawing is
 * in PumpBtScreen.kt (no preferences there: upstream's ArchitectureGateTests).
 */
class PumpBtActivity : ComponentActivity() {

    /** The window's settings and phone checks; read again on every onResume (the system screens come back there). */
    internal val settings: PumpBtSettingsModel by viewModels()

    /** The kept pump snapshot (`pump.json`); null - none. */
    private val pumpNow = MutableStateFlow<PumpNow?>(null)

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        settings.refresh()
    }

    /** A start waiting for the permission answer: true - pairing, false - waiting for the pump. */
    private var startAfterPermission: Boolean? = null

    /** Turning permanent operation on waits for the permission answer. */
    private var enableAfterPermission = false

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        PumpBtTrial.refreshChecks(this)
        val pending = startAfterPermission
        val enable = enableAfterPermission
        startAfterPermission = null
        enableAfterPermission = false
        if (PumpBtTrial.hasPermissions(this)) {
            if (enable) turnOnNow() else if (pending != null) startNow(pending)
        }
    }

    private val pickKeyFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) PumpBtTrial.loadKeyFromFile(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // a turned screen during the permission request keeps what waits for its answer (critic B2b, N5)
        if (savedInstanceState != null) {
            enableAfterPermission = savedInstanceState.getBoolean(KEY_ENABLE_AFTER, false)
            startAfterPermission = when (savedInstanceState.getInt(KEY_START_AFTER, START_NONE)) {
                START_PAIR -> true
                START_WAIT -> false
                else -> null
            }
        }
        if (restricted()) {
            finish()
            return
        }
        try {
            enableEdgeToEdge()
        } catch (_: Throwable) {
        }
        PumpBtTrial.onScreenOpened(this)
        val themeMode = CareLinkTheme.mode(this)
        setContent {
            JugglucoTheme(themeMode = themeMode) {
                PumpBtScreen(
                    stateFlow = PumpBtTrial.state,
                    sourceFlow = PumpBtSource.state,
                    journalFlow = PumpBtJournal.tail,
                    settingsFlow = settings.state,
                    pumpNowFlow = pumpNow,
                    actions = PumpBtScreenActions(
                        onBack = { finish() },
                        onAllow = ::askForPermissions,
                        onAcceptWarnings = { PumpBtTrial.acceptWarnings(this) },
                        onStartPairing = { start(pair = true) },
                        onWaitForPump = { start(pair = false) },
                        onCancelPairing = { PumpBtSource.cancelPairing(this) },
                        onCancelKey = PumpBtTrial::cancelPendingStart,
                        onTryOtherName = PumpBtTrial::tryOtherName,
                        onStop = PumpBtTrial::stop,
                        onReadAgain = PumpBtTrial::readAgain,
                        onLoadKeyFile = ::pickFile,
                        onDownloadKey = PumpBtTrial::loadKeyFromInternet,
                        onShareJournal = ::shareJournal,
                        onClearJournal = { PumpBtTrial.clearJournal(this) },
                        onPermanent = ::setPermanent,
                        onWriteBackfill = { keys -> PumpBtSource.confirmBackfill(this, keys) },
                        onDeclineBackfill = { PumpBtSource.declineBackfill(this) },
                        onVerbose = ::setVerbose,
                        onKeepScreenOn = ::keepScreenOn,
                        onPumpRead = ::refreshPumpNow,
                        onWarnAfter = settings::setWarnAfter,
                        onInsulin = settings::setInsulin,
                        onWriteCarbs = settings::setWriteCarbs,
                        onNightscout = settings::setSendToNightscout,
                        onBattery = ::askBatteryExemption,
                        onNotifications = ::askNotifications,
                        onSound = ::openChannelSettings,
                    ),
                )
            }
        }
    }

    /** Only while a pairing goes on (plan G2b, point 1): the phone must not lock before the pump asks to pair. */
    private fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** The kept pump snapshot read again off the main thread. */
    private fun refreshPumpNow() {
        val app = applicationContext
        val target = pumpNow
        background.execute {
            target.value = try {
                CareLinkStateStore(app).read().pumpNow
            } catch (t: Throwable) {
                PumpBtLog.w("pump snapshot not read (${t.javaClass.simpleName})")
                null
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ENABLE_AFTER, enableAfterPermission)
        outState.putInt(
            KEY_START_AFTER,
            when (startAfterPermission) {
                true -> START_PAIR
                false -> START_WAIT
                null -> START_NONE
            },
        )
    }

    /**
     * Bluetooth turned on or off while the window is shown (a quick-settings tile does not pause the window): the phone
     * checks again (critic G2, C-3). Registered from onStart to onStop; a system broadcast, no export flag needed.
     */
    private val bluetoothState = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) PumpBtTrial.refreshChecks(this@PumpBtActivity)
        }
    }

    private var bluetoothStateOn = false

    override fun onStart() {
        super.onStart()
        try {
            registerReceiver(bluetoothState, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            bluetoothStateOn = true
        } catch (t: Throwable) {
            PumpBtLog.w("Bluetooth state receiver not registered (${t.javaClass.simpleName})")
        }
    }

    override fun onStop() {
        if (bluetoothStateOn) {
            bluetoothStateOn = false
            try {
                unregisterReceiver(bluetoothState)
            } catch (_: Throwable) {
            }
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (restricted()) {
            finish()
            return
        }
        PumpBtTrial.refreshChecks(this)
        // someone who never used the pump: nothing is made before the warnings are accepted (critic G1, N4)
        if (PumpBtTrial.attached) PumpBtSource.recheckBond(this)
        settings.refresh()
        refreshPumpNow()
    }

    private fun restricted(): Boolean = try {
        tk.glucodata.RestrictedMode.isLocked()
    } catch (t: Throwable) {
        PumpBtLog.w("restricted mode not read (${t.javaClass.simpleName})")
        false
    }

    private fun askForPermissions() {
        val wanted = PumpBtTrial.permissions()
        if (wanted.isEmpty()) {
            PumpBtTrial.refreshChecks(this)
            return
        }
        askPermissions.launch(wanted)
    }

    private fun start(pair: Boolean) {
        if (!PumpBtTrial.hasPermissions(this)) {
            startAfterPermission = pair
            askForPermissions()
            return
        }
        startNow(pair)
    }

    private fun startNow(pair: Boolean) {
        if (pair) PumpBtTrial.startPairing() else PumpBtTrial.waitForPump()
    }

    /** "Permanent operation" (the screen asked for the confirmation): on - permissions first; off - at once. */
    private fun setPermanent(on: Boolean) {
        if (!on) {
            PumpBtSource.disable(this)
            return
        }
        if (!PumpBtTrial.hasPermissions(this)) {
            enableAfterPermission = true
            askForPermissions()
            return
        }
        turnOnNow()
    }

    /** Only with the key and Bluetooth there: a source on without them would stop the cloud and read nothing. */
    private fun turnOnNow() {
        PumpBtTrial.refreshChecks(this)
        if (!PumpBtWindow.controls(PumpBtTrial.state.value, PumpBtSource.state.value).canTurnOn) return
        PumpBtSource.enable(this)
    }

    /** The detailed journal switch: the setting is saved to its file off the main thread, in the order pressed (critic B2b, N6). */
    private fun setVerbose(on: Boolean) {
        val app = applicationContext
        background.execute {
            try {
                PumpBtSource.setVerbose(app, on)
            } catch (t: Throwable) {
                PumpBtLog.w("detailed journal not switched (${t.javaClass.simpleName})")
            }
        }
    }

    /**
     * "Phone": our app freed from battery optimization (upstream's manifest has REQUEST_IGNORE_BATTERY_OPTIMIZATIONS); a
     * phone without that screen - the general list.
     */
    @SuppressLint("BatteryLife")
    internal fun askBatteryExemption() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        if (!launch { startActivity(direct) }) launch { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    /**
     * "Phone", notifications. Android 13+: the system question once ever (kept in the settings, not for the window's
     * life: after two refusals Android refuses silently and the first tap of every new window did nothing); refused
     * before (or older Android) - the app's notification screen.
     */
    internal fun askNotifications() {
        val stored = CareLinkSettings(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !stored.notificationsAsked
        ) {
            stored.notificationsAsked = true
            if (launch { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) return
        }
        launch {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /** "Phone": the system screen of our channel (sound, vibration); the channel made first. */
    internal fun openChannelSettings() {
        try {
            CareLinkNotifier.ensureChannel(this)
        } catch (t: Throwable) {
            PumpBtLog.w("channel not created (${t.javaClass.simpleName})")
        }
        val channel = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, CareLinkNotifier.CHANNEL)
        if (!launch { startActivity(channel) }) {
            launch {
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            }
        }
    }

    /** A system screen the phone may not have: false instead of a crash. */
    private inline fun launch(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: ActivityNotFoundException) {
        PumpBtLog.w("screen not available (${e.javaClass.simpleName})")
        false
    } catch (e: SecurityException) {
        PumpBtLog.w("screen not allowed (${e.javaClass.simpleName})")
        false
    }

    private fun pickFile() {
        try {
            pickKeyFile.launch(arrayOf("text/*", "*/*"))
        } catch (e: ActivityNotFoundException) {
            PumpBtLog.w("no document picker (${e.javaClass.simpleName})")
        }
    }

    /** A copy in cacheDir/exports/ through upstream's FileProvider (`exports/` of their cache-path). */
    private fun shareJournal() {
        val app = applicationContext
        Thread({
            val file = PumpBtTrial.exportJournal(app)
            runOnUiThread {
                if (file == null || isFinishing) return@runOnUiThread
                try {
                    val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, file.name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (t: Throwable) {
                    PumpBtLog.w("journal not shared (${t.javaClass.simpleName})")
                }
            }
        }, "ModPumpBtShare").start()
    }

    companion object {
        private const val KEY_ENABLE_AFTER = "mod_pumpbt_enable_after_permission"
        private const val KEY_START_AFTER = "mod_pumpbt_start_after_permission"
        private const val START_NONE = 0
        private const val START_PAIR = 1
        private const val START_WAIT = 2

        /** One thread for the window's small file writes: kept in the order pressed, not tied to one window. */
        private val background: ExecutorService by lazy {
            Executors.newSingleThreadExecutor { r -> Thread(r, "ModPumpBtWindow").apply { isDaemon = true } }
        }

        /** The intent of this window when the build declares it (the phone `mod` build), else null. */
        fun intentIfDeclared(context: Context): Intent? {
            val component = ComponentName(context, PumpBtActivity::class.java)
            return try {
                context.packageManager.getActivityInfo(component, 0)
                Intent().setComponent(component)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
