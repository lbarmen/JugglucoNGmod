package tk.glucodata.mod.carelink

import org.json.JSONObject
import java.time.ZoneId

/**
 * A temporary basal on the pump (cloud banner `TEMP_BASAL` and the `basal.tempBasal*` fields). [rate] -
 * `tempBasalRate`, U/h; [percent] - `tempBasalPercentage`, only for a percentage temporary basal (null for an absolute
 * one, by `tempBasalType`); [minutesLeft] - `timeRemaining` of the banner (whole minutes; may be stale by one poll);
 * [absolute] - the type says absolute (ABS...): its rate is believed even at 0; type not known - a 0 is not (critic 4d,
 * C2). A percentage one may carry rate 0: then the rate is the last normal rate times [percent] (plan, decision 1; 4d).
 */
data class TempBasal(val rate: Double?, val percent: Int?, val minutesLeft: Int?, val absolute: Boolean = false)

/**
 * What the pump shows now (stage 4c, contract of the plan). [dataAtMs] - when its data reached the cloud (not later
 * than now). [iobUnits] - active insulin of the pump, [iobAtMs] - its own time (not later than now; addition to the
 * contract for CareLinkIob, plan decision 9). [basalRate] - current rate (`basal.basalRate`; may be missing while a
 * temporary basal runs or the pump is stopped). [basalKnown] - the answer had the `basal` object; false - the phone was
 * reconnecting to the pump: nothing is known about the basal - a gap, not a stop and not the end of a temporary basal
 * (critic 4c, C4: the basal writer goes by this flag, not by `basalRate == null`). [suspended] - delivery stopped
 * (`pumpSuspended` or banner `DELIVERY_SUSPEND`); null - unknown.
 */
data class PumpNow(
    val dataAtMs: Long,
    val iobUnits: Double? = null,
    val iobAtMs: Long? = null,
    val reservoirUnits: Double? = null,
    val reservoirPercent: Int? = null,
    val batteryPercent: Int? = null,
    val basalRate: Double? = null,
    val tempBasal: TempBasal? = null,
    val suspended: Boolean? = null,
    val basalKnown: Boolean = basalRate != null || tempBasal != null,
) {
    /** For [CareLinkStateStore]: numbers only, no personal data. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("dataAtMs", dataAtMs)
        putOpt("iobUnits", iobUnits)
        putOpt("iobAtMs", iobAtMs)
        putOpt("reservoirUnits", reservoirUnits)
        putOpt("reservoirPercent", reservoirPercent)
        putOpt("batteryPercent", batteryPercent)
        putOpt("basalRate", basalRate)
        tempBasal?.let { t ->
            put("tempBasal", JSONObject().apply {
                putOpt("rate", t.rate)
                putOpt("percent", t.percent)
                putOpt("minutesLeft", t.minutesLeft)
                if (t.absolute) put("absolute", true)
            })
        }
        putOpt("suspended", suspended)
        put("basalKnown", basalKnown)
    }

    companion object {
        fun fromJson(o: JSONObject?): PumpNow? {
            if (o == null) return null
            val at = o.clLong("dataAtMs") ?: return null
            val t = o.clObj("tempBasal")
            val rate = o.clDouble("basalRate")
            val temp = t?.let {
                TempBasal(it.clDouble("rate"), it.clInt("percent"), it.clInt("minutesLeft"), it.clBool("absolute") == true)
            }
            return PumpNow(
                dataAtMs = at,
                iobUnits = o.clDouble("iobUnits"),
                iobAtMs = o.clLong("iobAtMs"),
                reservoirUnits = o.clDouble("reservoirUnits"),
                reservoirPercent = o.clInt("reservoirPercent"),
                batteryPercent = o.clInt("batteryPercent"),
                basalRate = rate,
                tempBasal = temp,
                suspended = o.clBool("suspended"),
                basalKnown = o.clBool("basalKnown") ?: (rate != null || temp != null),
            )
        }
    }
}

/**
 * JugglucoNGmod, CareLink (stage 4c): [PumpNow] from a `display/message` answer. Moved from Glucove's
 * CareLinkPumpNow on org.json.
 *
 * The empty answer: when the phone with MiniMed Mobile has not uploaded the pump yet, the cloud gives reservoir and
 * battery <= 0 (and active insulin -1); active insulin -1 with one of the two at 0 and the other missing is empty too.
 * That is "no data", not "empty reservoir": [parse] gives null, the last snapshot stays ([merge]), the stop flag of
 * such an answer is not believed and the data time does not move back.
 */
object CareLinkPumpNow {

    const val BANNER_TEMP_BASAL = "TEMP_BASAL"
    const val BANNER_SUSPEND = "DELIVERY_SUSPEND"

    /** Active insulin stamped later than now + this is dropped (plan, decision 9): it would stay "fresh" for hours. */
    const val IOB_FUTURE_MS = 5 * 60_000L

    /**
     * The empty answer: reservoir and battery both present and both <= 0 - or active insulin below 0 (the cloud's -1)
     * with neither of them alive (<= 0 or missing; critic 4c, N6). A real empty reservoir has a live battery.
     */
    fun noPumpData(root: JSONObject): Boolean {
        val p = CareLinkData.patient(root)
        val reservoir = p.clDouble("reservoirRemainingUnits")
        val battery = p.clDouble("pumpBatteryLevelPercent")
        if (reservoir != null && battery != null && reservoir <= 0.0 && battery <= 0.0) return true
        val iob = p.clObj("activeInsulin")?.clDouble("amount") ?: return false
        if (iob >= 0.0 || (reservoir == null && battery == null)) return false
        return (reservoir == null || reservoir <= 0.0) && (battery == null || battery <= 0.0)
    }

    /**
     * The snapshot of this answer; the empty answer or no field at all - null. A negative value counts as no field;
     * units to tenths (float noise of the answer). Active insulin without its `datetime` takes the data time of this
     * answer; its time later than now + [IOB_FUTURE_MS] - the number is dropped, otherwise not later than now.
     */
    fun parse(root: JSONObject, nowMs: Long = System.currentTimeMillis(), phoneZone: ZoneId = ZoneId.systemDefault()): PumpNow? {
        if (noPumpData(root)) return null
        val p = CareLinkData.patient(root)
        val dataAt = minOf(CareLinkData.pumpDataAt(root) ?: nowMs, nowMs)
        val iob = p.clObj("activeInsulin")
        var iobUnits = iob?.clDouble("amount")?.takeIf { it >= 0.0 }?.let(::tenths)
        var iobAt: Long? = null
        if (iobUnits != null) {
            val offset = CareLinkMarkers.offsetOf(root)
            val at = iob?.clStr("datetime")?.let { CareLinkMarkers.localMs(it, offset, phoneZone) } ?: dataAt
            if (at > nowMs + IOB_FUTURE_MS) iobUnits = null else iobAt = minOf(at, nowMs)
        }
        val reservoir = p.clDouble("reservoirRemainingUnits")?.takeIf { it >= 0.0 }?.let(::tenths)
        val reservoirPct = p.clDouble("reservoirLevelPercent")?.takeIf { it >= 0.0 }?.toInt()
        val battery = p.clDouble("pumpBatteryLevelPercent")?.takeIf { it >= 0.0 }?.toInt()
        val basal = p.clObj("basal")
        val banners = p.clArr("pumpBannerState").clObjects()
        val rate = basal?.clDouble("basalRate")?.takeIf { it >= 0.0 }?.let(CareLinkMarkers::round3)
        // Without the basal object nothing is known about a temporary basal (11.11: the phone was reconnecting to the
        // pump and the basal fields and banners were gone for 25 min) - no data, not its end.
        val temp = if (basal != null) temp(basal, banners) else null
        val suspended = p.clBool("pumpSuspended").let { flag ->
            if (banners.any { it.clStr("type") == BANNER_SUSPEND }) true else flag
        }
        if (iobUnits == null && reservoir == null && reservoirPct == null && battery == null && basal == null &&
            suspended == null
        ) return null
        return PumpNow(dataAt, iobUnits, iobAt, reservoir, reservoirPct, battery, rate, temp, suspended, basalKnown = basal != null)
    }

    /**
     * Temporary basal: banner [BANNER_TEMP_BASAL] or field `basal.tempBasalType` (they came together 4 of 4 times in
     * Glucove) - either is enough; neither - null. Percentage unless the type says absolute (ABS...).
     */
    private fun temp(basal: JSONObject, banners: List<JSONObject>): TempBasal? {
        val banner = banners.firstOrNull { it.clStr("type") == BANNER_TEMP_BASAL }
        val type = basal.clStr("tempBasalType")?.takeIf { it.isNotBlank() }
        if (banner == null && type == null) return null
        val rate = basal.clDouble("tempBasalRate")?.takeIf { it >= 0.0 }?.let(CareLinkMarkers::round3)
        val absolute = type?.uppercase()?.startsWith("ABS") == true
        // type not given (the banner alone) and 0 %: not believed - the field may just be empty; a 0 U/h segment would go to
        // Nightscout as a stop (critic 4d2, N4). A stated percentage type with 0 % stands.
        val percent = if (absolute) null else basal.clDouble("tempBasalPercentage")
            ?.takeIf { it > 0.0 || (it == 0.0 && type != null) }?.let { Math.round(it).toInt() }
        val left = banner?.clDouble("timeRemaining")?.takeIf { it >= 0.0 }?.toInt()
        return TempBasal(rate, percent, left, absolute)
    }

    /** [new] is older than [prev]: the cloud gave an older answer. */
    fun older(prev: PumpNow?, new: PumpNow): Boolean = prev != null && new.dataAtMs < prev.dataAtMs

    /**
     * The new snapshot over the last one (for the state and the CareLink window): what the new one lacks comes from the
     * last (active insulin together with its time); no new one (the empty answer) or an older one - the last one.
     */
    fun merge(prev: PumpNow?, new: PumpNow?): PumpNow? {
        if (new == null || prev == null) return new ?: prev
        if (older(prev, new)) return prev
        val iobNew = new.iobUnits != null
        val basalKnown = new.basalKnown
        return new.copy(
            iobUnits = if (iobNew) new.iobUnits else prev.iobUnits,
            iobAtMs = if (iobNew) new.iobAtMs else prev.iobAtMs,
            reservoirUnits = new.reservoirUnits ?: prev.reservoirUnits,
            reservoirPercent = new.reservoirPercent ?: prev.reservoirPercent,
            batteryPercent = new.batteryPercent ?: prev.batteryPercent,
            basalRate = new.basalRate ?: prev.basalRate,
            // without the basal object - the last temporary basal; with it - what came (null - none)
            tempBasal = if (basalKnown) new.tempBasal else prev.tempBasal,
            suspended = new.suspended ?: prev.suspended,
            basalKnown = basalKnown || prev.basalKnown,
        )
    }

    private fun tenths(v: Double): Double = Math.round(v * 10.0) / 10.0
}
