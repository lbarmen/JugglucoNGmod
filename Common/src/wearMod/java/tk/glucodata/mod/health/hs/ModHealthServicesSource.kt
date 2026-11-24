package tk.glucodata.mod.health.hs

import android.content.Context
import android.os.Build
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import java.util.concurrent.TimeUnit
import tk.glucodata.mod.health.HealthCore
import tk.glucodata.mod.health.HealthPermissions
import tk.glucodata.mod.health.HealthSource
import tk.glucodata.mod.health.HealthSubscription
import tk.glucodata.mod.watch.ModWatchLog

/**
 * JugglucoNGmod, watch block (part 4b): Health Services as the source of heart rate and steps - the only code with
 * the library (seen by the `mod` build alone, which adds `androidx.health:health-services-client` through
 * mod/mod-init.gradle). Passive monitoring, as Glucove on the same watch: the watch's own measuring hands what it
 * collected to [ModHealthListenerService] when it decides (every 1.5-3.5 minutes on the Galaxy Watch 5 Pro, watch log
 * 19.11.2026); a `/mod/tick` asks for it at once ([flush]).
 *
 * Every decision lives in plain code the tests see ([HealthPermissions], [HealthSubscription]); here are only the
 * calls. Every call waits at most [CALL_TIMEOUT_S] and never runs on the main thread (the hand-over, the page and
 * the boot receiver call it on our background thread). Below Android 11 (API 30, the library's minimum, overridden in
 * our manifest) nothing is called.
 */
object ModHealthServicesSource : HealthSource {

    private const val CALL_TIMEOUT_S = 8L
    private val registerLock = Any()

    override fun flush(context: Context): Boolean {
        if (!HealthSubscription.supported(Build.VERSION.SDK_INT)) return false
        if (HealthCore.get(context).state.registeredTypes.isEmpty()) return false
        HealthServices.getClient(context).passiveMonitoringClient.flushAsync().get(CALL_TIMEOUT_S, TimeUnit.SECONDS)
        return true
    }

    override fun maintain(context: Context, nowMs: Long) {
        if (!HealthSubscription.supported(Build.VERSION.SDK_INT)) return
        val state = HealthCore.get(context).state
        val reason = HealthSubscription.registerReason(
            nowMs = nowMs,
            permsNow = HealthPermissions.read(context),
            permsAtRegistration = state.registeredPerms,
            registered = state.registeredTypes.isNotEmpty(),
            registeredAt = state.registeredAt,
            lastDataAt = state.lastDataAt,
            attemptAt = state.registerAttemptAt,
            failures = state.registerFailures,
        ) ?: return
        register(context, reason)
    }

    override fun register(context: Context, reason: String): Boolean = synchronized(registerLock) {
        if (!HealthSubscription.supported(Build.VERSION.SDK_INT)) return@synchronized false
        val core = HealthCore.get(context)
        val state = core.state
        val perms = HealthPermissions.read(context)
        state.registerAttemptAt = core.clock()
        try {
            val client = HealthServices.getClient(context).passiveMonitoringClient
            val supported = client.getCapabilitiesAsync().get(CALL_TIMEOUT_S, TimeUnit.SECONDS).supportedDataTypesPassiveMonitoring
            val types = buildSet<DataType<*, *>> {
                if (perms.hr && DataType.HEART_RATE_BPM in supported) add(DataType.HEART_RATE_BPM)
                if (perms.activity && DataType.STEPS_DAILY in supported) add(DataType.STEPS_DAILY)
            }
            val names = HealthSubscription.typeNames(
                hr = DataType.HEART_RATE_BPM in types,
                steps = DataType.STEPS_DAILY in types,
            )
            val missing = HealthSubscription.missingTypes(
                perms,
                offeredHr = DataType.HEART_RATE_BPM in supported,
                offeredSteps = DataType.STEPS_DAILY in supported,
            )
            if (missing.isNotEmpty()) ModWatchLog.w("health types not offered reason=$reason missing=$missing offered=${names.ifEmpty { "-" }}")
            if (types.isEmpty()) {
                // Nothing allowed or nothing the watch can give: drop an old subscription so it never runs unseen.
                if (state.registeredTypes.isNotEmpty()) {
                    client.clearPassiveListenerServiceAsync().get(CALL_TIMEOUT_S, TimeUnit.SECONDS)
                }
                state.registeredTypes = ""
                state.registeredPerms = perms.short()
                // Allowed but nothing offered (Health Services not ready yet after boot, Watch 7i): a failure, tried
                // again; nothing allowed: no error, nothing to retry.
                HealthSubscription.noteAttempt(state, HealthSubscription.errorForNoTypes(perms.any))
                ModWatchLog.i("health register skipped reason=$reason perms=${perms.short()} supported=${supported.size}")
                return@synchronized false
            }
            val config = PassiveListenerConfig.Builder().setDataTypes(types).build()
            client.setPassiveListenerServiceAsync(ModHealthListenerService::class.java, config)
                .get(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            state.registeredAt = core.clock()
            state.registeredTypes = names
            state.registeredPerms = perms.short()
            HealthSubscription.noteAttempt(state, "")
            ModWatchLog.i("health registered reason=$reason types=$names perms=${perms.short()}")
            true
        } catch (t: Throwable) {
            HealthSubscription.noteAttempt(state, t.javaClass.simpleName)
            ModWatchLog.w("health register failed reason=$reason: ${t.javaClass.simpleName}")
            false
        }
    }

    override fun statusFields(context: Context): String {
        val sdk = Build.VERSION.SDK_INT
        if (!HealthSubscription.supported(sdk)) return "hs=no-sdk"
        val state = HealthCore.get(context).state
        return HealthSubscription.statusFields(HealthPermissions.read(context), state.registeredTypes, state.registerError)
    }
}
