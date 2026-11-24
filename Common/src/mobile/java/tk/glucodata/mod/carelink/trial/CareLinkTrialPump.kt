package tk.glucodata.mod.carelink.trial

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * JugglucoNGmod, CareLink trial (test builds only, part 4p): a made-up pump. Steps add events at the time they are
 * given; [answer] turns the last 24 h into a `display/message` answer of the same shape the cloud gives (field names of
 * the Glucove probe field list, as in the 4c-4d tests): pump-local marker times without a zone plus the offset in
 * `metadata.clientDateTime`, doses as sums of `programmed*Amount` / `delivered*Amount`, the basal object, banners.
 * Every value is invented. Not thread-safe by itself: [CareLinkTrial] calls it under its lock.
 */
class CareLinkTrialPump {

    /** A bolus marker: [fast]/[extended] programmed parts, delivered parts, done or still running. */
    data class Bolus(
        val atMs: Long,
        val fastProgrammed: Double,
        val fastDelivered: Double,
        val extProgrammed: Double = 0.0,
        val extDelivered: Double = 0.0,
        val completed: Boolean = true,
        val type: String = TYPE_FAST,
        val durationMin: Int? = null,
    ) {
        val delivered: Double get() = fastDelivered + extDelivered
    }

    data class Meal(val atMs: Long, val grams: Double)

    /** A temporary basal: absolute [rate] U/h, or [percent] of the normal rate; ends at [endMs]. */
    data class Temp(val startMs: Long, val endMs: Long, val rate: Double?, val percent: Int?)

    val boluses = ArrayList<Bolus>()
    val meals = ArrayList<Meal>()
    var normalRate: Double = DEFAULT_RATE
    var temp: Temp? = null
    var suspendedSinceMs: Long? = null

    fun reset() {
        boluses.clear()
        meals.clear()
        normalRate = DEFAULT_RATE
        temp = null
        suspendedSinceMs = null
    }

    /** The temporary basal running at [nowMs] (an expired one is dropped). */
    fun tempAt(nowMs: Long): Temp? {
        val t = temp ?: return null
        if (nowMs >= t.endMs) {
            temp = null
            return null
        }
        return t
    }

    /** Active insulin: delivered doses, linear over 4 h (a made-up curve; the app shows what the pump says). */
    fun iob(nowMs: Long): Double = boluses.sumOf { b ->
        val age = (nowMs - b.atMs).coerceAtLeast(0L)
        if (age >= IOB_MS) 0.0 else b.delivered * (1.0 - age.toDouble() / IOB_MS)
    }

    fun reservoir(): Double = (RESERVOIR_FULL - boluses.sumOf { it.delivered }).coerceAtLeast(0.0)

    /** How the answer looks: normal, the empty one (no pump upload yet), or older than [olderThanMs]. */
    enum class Shape { NORMAL, EMPTY, OLD }

    /**
     * The `display/message` answer at [nowMs] for [zone]. Pump data reached the "cloud" a minute ago. [Shape.EMPTY] -
     * reservoir and battery 0, active insulin -1, no basal (the cloud's empty answer; markers stay - its history);
     * [Shape.OLD] - data time [olderThanMs] minus 10 min.
     */
    fun answer(nowMs: Long, zone: ZoneId, shape: Shape = Shape.NORMAL, olderThanMs: Long? = null): JSONObject {
        val dataAt = when (shape) {
            Shape.OLD -> (olderThanMs ?: nowMs) - 10 * 60_000L
            else -> nowMs - DATA_LAG_MS
        }
        val p = JSONObject()
        p.put("lastMedicalDeviceDataUpdateServerTime", dataAt)
        p.put("medicalDeviceFamily", "NGP")
        if (shape == Shape.EMPTY) {
            p.put("activeInsulin", JSONObject().put("amount", -1))
            p.put("reservoirRemainingUnits", 0)
            p.put("reservoirLevelPercent", 0)
            p.put("pumpBatteryLevelPercent", 0)
            p.put("pumpBannerState", JSONArray())
        } else {
            val reservoir = reservoir()
            p.put("activeInsulin", JSONObject().put("amount", round2(iob(dataAt))).put("datetime", local(dataAt, zone)))
            p.put("reservoirRemainingUnits", round2(reservoir))
            p.put("reservoirLevelPercent", Math.round(reservoir * 100.0 / RESERVOIR_MAX).toInt())
            p.put("pumpBatteryLevelPercent", BATTERY)
            val banners = JSONArray()
            val stopped = suspendedSinceMs != null
            p.put("pumpSuspended", stopped)
            val basal = JSONObject()
            val t = tempAt(nowMs)
            if (stopped) {
                banners.put(JSONObject().put("type", BANNER_SUSPEND))
            } else if (t != null) {
                if (t.percent != null) {
                    basal.put("tempBasalType", "PERCENTAGE").put("tempBasalPercentage", t.percent).put("tempBasalRate", 0)
                } else {
                    basal.put("tempBasalType", "ABSOLUTE").put("tempBasalRate", t.rate ?: 0.0)
                }
                banners.put(JSONObject().put("type", BANNER_TEMP).put("timeRemaining", ((t.endMs - nowMs) / 60_000L).toInt()))
            } else {
                basal.put("basalRate", normalRate)
            }
            p.put("basal", basal)
            p.put("pumpBannerState", banners)
        }
        p.put("markers", markers(nowMs, zone))
        val metadata = JSONObject().put("kind", "BLENGP").put("version", 4).put("clientDateTime", clientDateTime(nowMs, zone))
        return JSONObject().put("metadata", metadata).put("patientData", p)
    }

    private fun markers(nowMs: Long, zone: ZoneId): JSONArray {
        val from = nowMs - WINDOW_MS
        val list = ArrayList<Pair<Long, JSONObject>>()
        for (m in meals) {
            if (m.atMs < from || m.atMs > nowMs) continue
            list += m.atMs to marker("MEAL", m.atMs, zone, JSONObject().put("amount", m.grams))
        }
        for (b in boluses) {
            if (b.atMs < from || b.atMs > nowMs) continue
            val v = JSONObject().put("bolusType", b.type).put("completed", b.completed)
            if (b.type != TYPE_SQUARE) {
                v.put("programmedFastAmount", b.fastProgrammed).put("deliveredFastAmount", b.fastDelivered)
            }
            if (b.type != TYPE_FAST) {
                v.put("programmedExtendedAmount", b.extProgrammed).put("deliveredExtendedAmount", b.extDelivered)
                b.durationMin?.let { v.put("programmedDuration", it) }
            }
            list += b.atMs to marker("INSULIN", b.atMs, zone, v)
        }
        val out = JSONArray()
        list.sortedBy { it.first }.forEach { out.put(it.second) }
        return out
    }

    private fun marker(type: String, atMs: Long, zone: ZoneId, values: JSONObject): JSONObject =
        JSONObject().put("type", type).put("timestamp", local(atMs, zone))
            .put("data", JSONObject().put("dataValues", values))

    companion object {
        const val TYPE_FAST = "FAST"
        const val TYPE_SQUARE = "SQUARE"
        const val TYPE_DUAL = "DUAL_WAVE"
        const val BANNER_TEMP = "TEMP_BASAL"
        const val BANNER_SUSPEND = "DELIVERY_SUSPEND"
        const val DEFAULT_RATE = 0.8
        const val RESERVOIR_FULL = 180.0
        const val RESERVOIR_MAX = 300.0
        const val BATTERY = 75
        const val DATA_LAG_MS = 60_000L
        const val WINDOW_MS = 24 * 3_600_000L
        const val IOB_MS = 4 * 3_600_000L

        private val LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        private val CLIENT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")

        /** Pump-local time, no zone ("2026-01-10T08:00:03"). */
        fun local(ms: Long, zone: ZoneId): String = LOCAL.format(Instant.ofEpochMilli(ms).atZone(zone))

        fun clientDateTime(ms: Long, zone: ZoneId): String = CLIENT.format(Instant.ofEpochMilli(ms).atZone(zone))

        fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
    }
}
