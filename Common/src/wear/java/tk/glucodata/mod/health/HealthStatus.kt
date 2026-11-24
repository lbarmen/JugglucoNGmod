package tk.glucodata.mod.health

import android.content.Context
import android.os.Build
import tk.glucodata.mod.health.ModHealthBatch.Sample
import tk.glucodata.mod.watch.ModBattery

/**
 * JugglucoNGmod, watch block (part 4): the service rows of a batch - the watch battery (the phone's "Watch" screen
 * waits for it, part 5) when it changed, and the status row at least every [HealthConfig.STATUS_PERIOD_MS] and at once
 * when its permanent part changes: app version, sdk, the source's permissions and subscription; then the ages (minutes)
 * of the last delivery and the last batch, the queue length, the late heart rate readings and the last send failure.
 * Never heart rate or steps.
 */
object HealthStatus {

    /** The permanent part of the status row (a change sends a new row at once). */
    fun stablePart(appVersion: String, sdk: Int, sourceFields: String): String =
        clean("app=$appVersion;sdk=$sdk;${sourceFields.ifEmpty { "src=none" }}")

    /**
     * The status row at [nowMs]: [stable] and the ages, queue length, the count of heart rate readings that came after
     * their window was closed ([late], a counter since install - the phone merges such rows, part 5) and last error.
     */
    fun line(stable: String, nowMs: Long, lastDataAt: Long, lastSentAt: Long, queued: Int, late: Long, error: String): String =
        clean(
            "$stable;data=${ageMin(nowMs, lastDataAt)};sent=${ageMin(nowMs, lastSentAt)};queue=$queued;late=$late;" +
                "err=${error.ifEmpty { "-" }}",
        )

    /** A status row is due: none yet, its permanent part changed, 30 minutes passed, or the clock went back. */
    fun isDue(nowMs: Long, lastAtMs: Long, stable: String, lastStable: String): Boolean =
        lastAtMs <= 0 || stable != lastStable || nowMs - lastAtMs !in 0 until HealthConfig.STATUS_PERIOD_MS

    /** A battery row is due: a known [reading] that differs from the last row sent. */
    fun batteryChanged(reading: ModBattery.Reading?, lastPercent: Int, lastCharging: Boolean): Boolean =
        reading != null && (reading.percent != lastPercent || reading.charging != lastCharging)

    /** Whole minutes from [atMs] to [nowMs]; "-" for never. */
    fun ageMin(nowMs: Long, atMs: Long): String = if (atMs <= 0) "-" else ((nowMs - atMs) / 60_000L).coerceAtLeast(0).toString()

    /**
     * The service rows due at [nowMs] (battery, status), remembered in the state as sent. [force] - a status row even
     * when not due (the "send now" button).
     */
    fun samples(core: HealthCore, source: HealthSource?, nowMs: Long, force: Boolean = false): List<Sample> {
        val state = core.state
        val out = mutableListOf<Sample>()
        val battery = ModBattery.read(core.context)
        if (battery != null && batteryChanged(battery, state.lastBattery, state.lastCharging)) {
            out += Sample(
                ModHealthBatch.KIND_BATTERY, nowMs, battery.percent.toFloat(),
                if (battery.charging) ModHealthBatch.CHARGING else "",
            )
            state.lastBattery = battery.percent
            state.lastCharging = battery.charging
        }
        val fields = try {
            source?.statusFields(core.context).orEmpty()
        } catch (t: Throwable) {
            "src=error:${t.javaClass.simpleName}"
        }
        val stable = stablePart(appVersion(core.context), Build.VERSION.SDK_INT, fields)
        if (force || isDue(nowMs, state.lastStatusAt, stable, state.lastStatusStable)) {
            val text = line(stable, nowMs, state.lastDataAt, state.lastSentAt, core.buffer.count(), state.hrLate, state.lastError)
            out += Sample(ModHealthBatch.KIND_STATUS, nowMs, 0f, text)
            state.lastStatusAt = nowMs
            state.lastStatusStable = stable
        }
        return out
    }

    private fun appVersion(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "-"
    } catch (_: Throwable) {
        "-"
    }

    private fun clean(text: String): String = SampleBuffer.clean(text)
}
