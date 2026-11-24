package tk.glucodata.mod.carelink

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import tk.glucodata.ui.JugglucoTheme

/**
 * JugglucoNGmod, CareLink (stage 4e): the CareLink window - sign-in and sign-out, whether pump data comes in (state,
 * data age, the pump's active insulin, reservoir, battery), poll interval, warning threshold, insulin type, the two
 * switches, and the battery / notification / sound buttons. Upstream's theme ([JugglucoTheme] with their light / dark
 * choice). Opened from upstream's settings ([ModCareLinkEntry], hook `carelink-settings`), the launcher shortcut and
 * our notifications.
 *
 * Declared only in Common/src/mobileMod/AndroidManifest.xml (not exported): upstream's own builds have no such window,
 * and [intentIfDeclared] then gives null - the settings entry is not drawn, notifications open the app. While upstream's
 * restricted mode is locked the window closes at once (as their settings screens outside the allowed list).
 *
 * The drawing is in CareLinkScreen.kt; no preferences here or there (upstream's ArchitectureGateTests).
 */
class CareLinkActivity : ComponentActivity() {

    private val model: CareLinkViewModel by viewModels()

    private val signIn = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.refresh() }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        model.refresh()
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
        val themeMode = CareLinkTheme.mode(this)
        setContent {
            JugglucoTheme(themeMode = themeMode) {
                CareLinkScreen(
                    model = model,
                    actions = CareLinkScreenActions(
                        onBack = { finish() },
                        onSignIn = ::openSignIn,
                        onBattery = ::askBatteryExemption,
                        onNotifications = ::askNotifications,
                        onSound = ::openChannelSettings,
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
        model.startTicking()
    }

    override fun onPause() {
        model.stopTicking()
        super.onPause()
    }

    private fun restricted(): Boolean = try {
        tk.glucodata.RestrictedMode.isLocked()
    } catch (t: Throwable) {
        CareLinkLog.w("restricted mode not read (${t.javaClass.simpleName})")
        false
    }

    private fun openSignIn() {
        launch { signIn.launch(CareLinkLoginActivity.intent(this)) }
    }

    /** Upstream's manifest has REQUEST_IGNORE_BATTERY_OPTIMIZATIONS; a phone without the screen - the general list. */
    @SuppressLint("BatteryLife")
    private fun askBatteryExemption() {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        if (!launch { startActivity(direct) }) launch { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    /**
     * Android 13+: the system question once ever (kept in the settings, not for the window's life: after two refusals
     * Android refuses silently and the first tap of every new window did nothing; critic 4d, N5); refused before (or
     * older Android) - the app's notification screen.
     */
    private fun askNotifications() {
        val settings = CareLinkSettings(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            !settings.notificationsAsked
        ) {
            settings.notificationsAsked = true
            if (launch { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) return
        }
        launch {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    /** The system screen of our channel (sound, vibration; the person's decision 16.11); the channel made first. */
    private fun openChannelSettings() {
        try {
            CareLinkNotifier.ensureChannel(this)
        } catch (t: Throwable) {
            CareLinkLog.w("channel not created (${t.javaClass.simpleName})")
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
        CareLinkLog.w("screen not available (${e.javaClass.simpleName})")
        false
    } catch (e: SecurityException) {
        CareLinkLog.w("screen not allowed (${e.javaClass.simpleName})")
        false
    }

    companion object {
        /**
         * The intent of this window when the build declares it (the phone `mod` build), else null: upstream's builds
         * compile the class but have no such window (no BuildConfig field tells it in debug).
         */
        fun intentIfDeclared(context: Context): Intent? {
            val component = android.content.ComponentName(context, CareLinkActivity::class.java)
            return try {
                // the manifest entry itself (not declared - NameNotFoundException); resolveActivity is no help in tests:
                // Robolectric resolves an explicit intent of any class it knows
                context.packageManager.getActivityInfo(component, 0)
                Intent().setComponent(component)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
