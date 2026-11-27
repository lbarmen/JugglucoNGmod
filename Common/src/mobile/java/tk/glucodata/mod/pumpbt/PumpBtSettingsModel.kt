package tk.glucodata.mod.pumpbt

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tk.glucodata.mod.carelink.CareLinkInsulin
import tk.glucodata.mod.carelink.CareLinkNotifier
import tk.glucodata.mod.carelink.CareLinkSettings

/** One insulin type the pump window offers: upstream's id and name. */
data class PumpBtInsulinChoice(val id: Long, val name: String)

/**
 * The settings part of the pump window - no keys, no pump data (those are [PumpBtTrial.state] and
 * [PumpBtSource.state]). [warnAfterMin] is the threshold as the window shows it: one of [CareLinkSettings.WARN_CHOICES]
 * ([CareLinkSettings.shownWarnMin]: a stored 5/10/15 shows as 20, the threshold the Bluetooth check really uses).
 */
data class PumpBtSettingsState(
    val warnAfterMin: Int = CareLinkSettings.DEFAULT_WARN_MIN,
    val writeCarbs: Boolean = true,
    val sendToNightscout: Boolean = false,
    val insulinChoices: List<PumpBtInsulinChoice> = emptyList(),
    val insulinId: Long? = null,
    val batteryExempt: Boolean = true,
    val notificationsAllowed: Boolean = true,
    /** The app may notify, but our channel (`mod_carelink`) is turned off in the system. */
    val channelOff: Boolean = false,
) {
    val insulinName: String? get() = insulinChoices.firstOrNull { it.id == insulinId }?.name

    /** The threshold choices the window offers. */
    val warnChoices: List<Int> get() = CareLinkSettings.WARN_CHOICES
}

/**
 * JugglucoNGmod, pump over Bluetooth: the model of the pump window's settings (in place of the CareLink window's
 * model). Over the kept settings ([CareLinkSettings], file `mod_carelink_settings`, and [CareLinkInsulin]): the "no
 * data" threshold (20/30/60 min), the insulin type of pump boluses, recording the calculator's carbs, sending pump
 * records to Nightscout; and the phone checks (battery optimization, notifications, our channel turned off). Reads when
 * the window comes to the front and after each change it makes; the system screens of the phone checks come back
 * through the window's onResume. The insulin choice is checked against upstream's list each time (a settings import
 * may have dropped it - the window shows the type really used). No drawing here, no network, no Keystore.
 */
class PumpBtSettingsModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()

    private val _state = MutableStateFlow(PumpBtSettingsState())
    val state: StateFlow<PumpBtSettingsState> = _state.asStateFlow()

    /** Reads again (the window's onResume, a permission answer, after a change). */
    fun refresh() {
        viewModelScope.launch {
            try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                PumpBtLog.w("pump window settings not read (${t.javaClass.simpleName})")
            }
        }
    }

    /** Reads the settings as they are now into [state] (and returns them); files and the insulin list off the main thread. */
    suspend fun load(): PumpBtSettingsState = withContext(Dispatchers.IO) {
        val settings = CareLinkSettings(app)
        val choices = try {
            CareLinkInsulin.choices(app).map { PumpBtInsulinChoice(it.id, it.displayName) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            PumpBtLog.w("insulin types not read (${t.javaClass.simpleName})")
            emptyList()
        }
        val insulin = try {
            CareLinkInsulin.presetId(app)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
        PumpBtSettingsState(
            warnAfterMin = CareLinkSettings.shownWarnMin(settings.warnAfterMin),
            writeCarbs = settings.writeCarbs,
            sendToNightscout = settings.sendToNightscout,
            insulinChoices = choices,
            insulinId = insulin,
            batteryExempt = batteryExempt(),
            notificationsAllowed = notificationsAllowed(),
            channelOff = channelOff(),
        ).also { _state.value = it }
    }

    /** One of [CareLinkSettings.WARN_CHOICES]; anything else is ignored. The new threshold counts at once. */
    fun setWarnAfter(min: Int) {
        if (min !in CareLinkSettings.WARN_CHOICES) return
        CareLinkSettings(app).warnAfterMin = min
        viewModelScope.launch(Dispatchers.IO) { guard("no-data check not run") { CareLinkNotifier.checkNoData(app) } }
        refresh()
    }

    /** Only a type of the list the window showed. */
    fun setInsulin(id: Long) {
        if (_state.value.insulinChoices.none { it.id == id }) return
        CareLinkSettings(app).presetId = id
        refresh()
    }

    fun setWriteCarbs(on: Boolean) {
        CareLinkSettings(app).writeCarbs = on
        refresh()
    }

    fun setSendToNightscout(on: Boolean) {
        CareLinkSettings(app).sendToNightscout = on
        refresh()
    }

    private fun batteryExempt(): Boolean = try {
        app.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(app.packageName) ?: true
    } catch (_: Throwable) {
        true
    }

    private fun notificationsAllowed(): Boolean = try {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        granted && NotificationManagerCompat.from(app).areNotificationsEnabled()
    } catch (_: Throwable) {
        true
    }

    private fun channelOff(): Boolean = try {
        CareLinkNotifier.channelOff(app)
    } catch (_: Throwable) {
        false
    }

    private inline fun guard(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            PumpBtLog.w("$what (${t.javaClass.simpleName})")
        }
    }
}
