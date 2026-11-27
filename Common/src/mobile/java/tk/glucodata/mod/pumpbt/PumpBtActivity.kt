package tk.glucodata.mod.pumpbt

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import tk.glucodata.mod.carelink.CareLinkTheme
import tk.glucodata.ui.JugglucoTheme

/**
 * JugglucoNGmod, pump over Bluetooth (trial): the hidden trial window "Pump over Bluetooth (trial)" - reads a Medtronic
 * pump over Bluetooth without MiniMed Mobile, read only, for the 740G trial. Opened from the bottom of our CareLink
 * window. The link lives in [PumpBtSource] (one per process; [PumpBtTrial] is the window's thin layer over it); this
 * window only shows it and presses its buttons. Opening asks nothing: permissions on Allow, on a start or on turning
 * permanent operation on; with permanent operation on the source keeps the link (opening does not start a new one).
 * The screen stays on while the window is shown.
 *
 * Declared only in Common/src/mobileMod/AndroidManifest.xml (not exported); [intentIfDeclared] gives null in upstream's
 * builds and the CareLink row is not drawn. Closes at once while upstream's restricted mode is locked. The drawing is
 * in PumpBtScreen.kt (no preferences there: upstream's ArchitectureGateTests).
 */
class PumpBtActivity : ComponentActivity() {

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
        if (restricted()) {
            finish()
            return
        }
        try {
            enableEdgeToEdge()
        } catch (_: Throwable) {
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        PumpBtTrial.onScreenOpened(this)
        val themeMode = CareLinkTheme.mode(this)
        setContent {
            JugglucoTheme(themeMode = themeMode) {
                PumpBtScreen(
                    stateFlow = PumpBtTrial.state,
                    sourceFlow = PumpBtSource.state,
                    journalFlow = PumpBtJournal.tail,
                    actions = PumpBtScreenActions(
                        onBack = { finish() },
                        onAllow = ::askForPermissions,
                        onStartPairing = { start(pair = true) },
                        onWaitForPump = { start(pair = false) },
                        onStop = PumpBtTrial::stop,
                        onReadAgain = PumpBtTrial::readAgain,
                        onLoadKey = PumpBtTrial::loadKeyFromInternet,
                        onLoadKeyFile = ::pickFile,
                        onVariant = PumpBtTrial::setVariant,
                        onShareJournal = ::shareJournal,
                        onClearJournal = { PumpBtTrial.clearJournal(this) },
                        onPermanent = ::setPermanent,
                        onWriteBackfill = { keys -> PumpBtSource.confirmBackfill(this, keys) },
                        onDeclineBackfill = { PumpBtSource.declineBackfill(this) },
                        onVerbose = { on -> PumpBtSource.setVerbose(this, on) },
                    ),
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (restricted()) {
            finish()
            return
        }
        PumpBtTrial.refreshChecks(this)
        PumpBtSource.recheckBond(this)
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
