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
 * window. The link lives in [PumpBtTrial] (one per process); this window only shows it and presses its buttons. Opening
 * asks nothing and starts nothing: permissions on Allow or on a start, the link on the buttons. The screen stays on
 * while the window is shown.
 *
 * Declared only in Common/src/mobileMod/AndroidManifest.xml (not exported); [intentIfDeclared] gives null in upstream's
 * builds and the CareLink row is not drawn. Closes at once while upstream's restricted mode is locked. The drawing is
 * in PumpBtScreen.kt (no preferences there: upstream's ArchitectureGateTests).
 */
class PumpBtActivity : ComponentActivity() {

    /** A start waiting for the permission answer: true - pairing, false - waiting for the pump. */
    private var startAfterPermission: Boolean? = null

    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        PumpBtTrial.refreshChecks(this)
        val pending = startAfterPermission
        startAfterPermission = null
        if (pending != null && PumpBtTrial.hasPermissions(this)) startNow(pending)
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
