package tk.glucodata.mod.carelink

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.webkit.CookieManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the CareLink window shows about the link. */
enum class CareLinkView {
    /** No session: "Sign in to CareLink". */
    SIGNED_OUT,

    /** The session ended (refused key, a session file that will never open): sign in again. */
    NEED_LOGIN,

    /** A session, but polling is off (a failed flag write, or a build of 4c/4d before; critic 4d2, N2). */
    POLLING_OFF,

    /** Signed in, no pump data yet since the sign-in. */
    WAITING,

    /** Signed in, the last poll worked and the pump data is younger than the warning threshold. */
    OK,

    /**
     * Signed in, the last poll worked, but the newest pump data is as old as the warning threshold or older (critic
     * of the texts 4d, B1: the cloud answers, MiniMed Mobile does not upload - old numbers must not look fresh).
     */
    STALE,

    /** Signed in, the last poll failed ([CareLinkUiState.error]). */
    ERROR,
}

/** One insulin type the window offers: upstream's id and name. */
data class CareLinkInsulinChoice(val id: Long, val name: String)

/**
 * Everything the CareLink window draws - no keys, no user name ("signed in" is enough). Times are epoch ms, [nowMs] the
 * moment of reading (ages are counted from it). [warnAfterMin] - the threshold of "no data" and "records not saved".
 */
data class CareLinkUiState(
    val view: CareLinkView = CareLinkView.SIGNED_OUT,
    val error: CareLinkError? = null,
    val nowMs: Long = 0L,
    val lastPollAtMs: Long? = null,
    val lastDataAtMs: Long? = null,
    val pump: PumpNow? = null,
    val intervalMin: Int = CareLinkSettings.DEFAULT_INTERVAL_MIN,
    val warnAfterMin: Int = CareLinkSettings.DEFAULT_WARN_MIN,
    val writeCarbs: Boolean = true,
    val sendToNightscout: Boolean = false,
    val insulinChoices: List<CareLinkInsulinChoice> = emptyList(),
    val insulinId: Long? = null,
    val batteryExempt: Boolean = true,
    val notificationsAllowed: Boolean = true,
    /** The app may notify, but our channel is turned off in the system (critic 4d, N6). */
    val channelOff: Boolean = false,
) {
    val signedIn: Boolean get() = view != CareLinkView.SIGNED_OUT && view != CareLinkView.NEED_LOGIN
    val insulinName: String? get() = insulinChoices.firstOrNull { it.id == insulinId }?.name

    /**
     * The shortest threshold without warnings while all is well: the data reaches CareLink a few minutes late (the
     * MiniMed Mobile upload) and is fetched once an interval (critic 4d, C1; critic of the texts 4d, C4).
     */
    val warnMinQuiet: Int get() = intervalMin + WARN_MARGIN_MIN

    /** The threshold is below [warnMinQuiet]: the window says so under the choice, the choice stays allowed. */
    val warnTooSoon: Boolean get() = warnAfterMin < warnMinQuiet

    companion object {
        /** The upload delay of MiniMed Mobile, roughly (0-5 min, Glucove 03.11). */
        const val WARN_MARGIN_MIN = 5
    }
}

/**
 * JugglucoNGmod, CareLink (stage 4e): the model of the CareLink window. Reads the link state, settings and permissions
 * when the window shows and every [TICK_MS] while it is on screen (the state store has no change signal; critic of the
 * joints), and after every change it makes. Writes the window's settings; a new poll interval re-arms the schedule; the
 * insulin choice is checked against upstream's list each time (critic 4d2, N6: a settings import may have dropped it -
 * the window shows the type really used). Sign-out wipes the session and the Medtronic page cookies (critic of the
 * joints, C5). No Compose here (upstream's ArchitectureGateTests), no network, no Keystore.
 */
class CareLinkViewModel(application: Application) : AndroidViewModel(application) {

    private val app: Application get() = getApplication()

    private val _state = MutableStateFlow(CareLinkUiState())
    val state: StateFlow<CareLinkUiState> = _state.asStateFlow()

    private var ticking: Job? = null

    /** Reads now and then every [TICK_MS] until [stopTicking] (the window's onResume / onPause). */
    fun startTicking() {
        ticking?.cancel()
        ticking = viewModelScope.launch {
            while (isActive) {
                refreshNow()
                delay(TICK_MS)
            }
        }
    }

    fun stopTicking() {
        ticking?.cancel()
        ticking = null
    }

    /** Reads again (after a change, the sign-in window, a permission screen). */
    fun refresh() {
        viewModelScope.launch { refreshNow() }
    }

    private suspend fun refreshNow() {
        try {
            load()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            CareLinkLog.w("CareLink window not read (${t.javaClass.simpleName})")
        }
    }

    /** Reads the state as it is now into [state] (and returns it); files and the insulin list off the main thread. */
    suspend fun load(): CareLinkUiState = withContext(Dispatchers.IO) {
        val settings = CareLinkSettings(app)
        val s = CareLinkStateStore(app).read()
        val hasSession = CareLinkAuth.hasSession(app)
        val now = CareLinkClock.now()
        val view = viewOf(hasSession, settings.enabled, s, now, settings.warnAfterMs, settings.enabledSinceMs)
        val choices = try {
            CareLinkInsulin.choices(app).map { CareLinkInsulinChoice(it.id, it.displayName) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            CareLinkLog.w("insulin types not read (${t.javaClass.simpleName})")
            emptyList()
        }
        val insulin = try {
            CareLinkInsulin.presetId(app)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            null
        }
        CareLinkUiState(
            view = view,
            error = if (view == CareLinkView.ERROR) s.lastError else null,
            nowMs = now,
            lastPollAtMs = s.lastPollAtMs,
            lastDataAtMs = s.lastDataAtMs,
            pump = if (hasSession) s.pumpNow else null,
            intervalMin = settings.intervalMin,
            warnAfterMin = settings.warnAfterMin,
            writeCarbs = settings.writeCarbs,
            sendToNightscout = settings.sendToNightscout,
            insulinChoices = choices,
            insulinId = insulin,
            batteryExempt = batteryExempt(),
            notificationsAllowed = notificationsAllowed(),
            channelOff = channelOff(),
        ).also { _state.value = it }
    }

    fun setInterval(min: Int) {
        val settings = CareLinkSettings(app)
        if (min !in CareLinkSettings.INTERVALS_MIN || min == settings.intervalMin) return
        settings.intervalMin = min
        // the next alarm in the new interval (nothing when signed out or off)
        guard("schedule not changed") { CareLinkScheduler.arm(app, force = true) }
        refresh()
    }

    fun setWarnAfter(min: Int) {
        if (min !in CareLinkSettings.WARN_AFTER_MIN) return
        CareLinkSettings(app).warnAfterMin = min
        // the new threshold counts now, not at the next alarm or poll (critic 4d, N4)
        viewModelScope.launch(Dispatchers.IO) { guard("no-data check not run") { CareLinkNotifier.checkNoData(app) } }
        refresh()
    }

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

    /** "Update now": a poll in the background (WorkManager, network required). */
    fun pollNow() {
        guard("poll not queued") { CareLinkScheduler.enqueuePoll(app, PollReason.MANUAL) }
        refresh()
    }

    /** A session with polling off (critic 4d2, N2): on again, the chain armed, a poll now. */
    fun turnPollingOn() {
        if (!CareLinkAuth.hasSession(app)) return
        CareLinkSettings(app).enabled = true
        guard("polling not turned on") {
            CareLinkScheduler.arm(app, force = true)
            CareLinkScheduler.enqueuePoll(app, PollReason.MANUAL)
        }
        refresh()
    }

    /**
     * Sign-out: the session and its alarms go ([CareLinkAuth.signOut] -> the starter's listener), and the Medtronic
     * page cookies with them - otherwise the next sign-in would open the old account without a password (critic of
     * the joints, C5).
     */
    fun signOut() {
        CareLinkAuth.signOut(app)
        clearCookies()
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
            CareLinkLog.w("$what (${t.javaClass.simpleName})")
        }
    }

    override fun onCleared() {
        stopTicking()
        super.onCleared()
    }

    companion object {
        /** The window reads the state again this often while on screen. */
        const val TICK_MS = 15_000L

        /** Cookies of the sign-in page; tests put a recording one here (no WebView under Robolectric). */
        @Volatile
        internal var clearCookies: () -> Unit = {
            try {
                CookieManager.getInstance().apply {
                    removeAllCookies(null)
                    flush()
                }
            } catch (t: Throwable) {
                CareLinkLog.w("sign-in cookies not removed (${t.javaClass.simpleName})")
            }
        }

        /**
         * What the window shows for this session, polling flag and stored state at [nowMs]; data as old as
         * [warnAfterMs] (the "no data" threshold) or older is [CareLinkView.STALE] - the same moment the notification
         * comes. No data ever since polling was turned on ([enabledSinceMs]) - counted as the notification counts it,
         * from then plus [CareLinkNotifier.FIRST_DATA_MARGIN_MS] (critic 4e, N2): "waiting" before, out of date after.
         */
        internal fun viewOf(
            hasSession: Boolean, enabled: Boolean, s: CareLinkState, nowMs: Long, warnAfterMs: Long,
            enabledSinceMs: Long? = null,
        ): CareLinkView {
            val data = s.lastDataAtMs
            val firstDue = enabledSinceMs?.plus(CareLinkNotifier.FIRST_DATA_MARGIN_MS)
            return when {
                !hasSession -> if (s.status == CareLinkStatus.NEED_LOGIN) CareLinkView.NEED_LOGIN else CareLinkView.SIGNED_OUT
                !enabled -> CareLinkView.POLLING_OFF
                s.status == CareLinkStatus.ERROR -> CareLinkView.ERROR
                data == null -> if (firstDue != null && nowMs - firstDue >= warnAfterMs) CareLinkView.STALE else CareLinkView.WAITING
                nowMs - data >= warnAfterMs -> CareLinkView.STALE
                else -> CareLinkView.OK
            }
        }
    }
}
