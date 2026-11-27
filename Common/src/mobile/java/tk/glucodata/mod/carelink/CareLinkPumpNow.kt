package tk.glucodata.mod.carelink

import org.json.JSONObject

/**
 * A temporary basal on the pump. [rate] - U/h; [percent] - only for a percentage temporary basal (null for an absolute
 * one); [minutesLeft] - whole minutes; [absolute] - the type says absolute: its rate is believed even at 0; type not
 * known - a 0 is not (critic 4d, C2). A percentage one may carry rate 0: then the rate is the last normal rate times [percent] (plan, decision 1; 4d).
 */
data class TempBasal(val rate: Double?, val percent: Int?, val minutesLeft: Int?, val absolute: Boolean = false)

/**
 * What the pump shows now (stage 4c, contract of the plan). [dataAtMs] - when its data was read (not later than now).
 * [iobUnits] - active insulin of the pump, [iobAtMs] - its own time (not later than now; addition to the contract for
 * CareLinkIob, plan decision 9). [basalRate] - current rate (may be missing while a temporary basal runs or the pump is
 * stopped). [basalKnown] - the basal was read; false - nothing is known about the basal: a gap, not a stop and not the
 * end of a temporary basal (critic 4c, C4: the basal writer goes by this flag, not by `basalRate == null`).
 * [suspended] - delivery stopped; null - unknown. No reservoir percentage: the pump does not give it over Bluetooth.
 */
data class PumpNow(
    val dataAtMs: Long,
    val iobUnits: Double? = null,
    val iobAtMs: Long? = null,
    val reservoirUnits: Double? = null,
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
        /** An older `pump.json` may still carry `reservoirPercent` (cloud only, gone): it is not read. */
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
 * JugglucoNGmod, CareLink (stage 4c): the order and the merging of [PumpNow] snapshots (state and the pump window).
 * The parsing of the cloud's `display/message` answer that once lived here went with the cloud.
 */
object CareLinkPumpNow {

    /** Active insulin stamped later than now + this is dropped (plan, decision 9): it would stay "fresh" for hours. */
    const val IOB_FUTURE_MS = 5 * 60_000L

    /** [new] is older than [prev]. */
    fun older(prev: PumpNow?, new: PumpNow): Boolean = prev != null && new.dataAtMs < prev.dataAtMs

    /**
     * The new snapshot over the last one (for the state and the pump window): what the new one lacks comes from the
     * last (active insulin together with its time); no new one or an older one - the last one.
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
            batteryPercent = new.batteryPercent ?: prev.batteryPercent,
            basalRate = new.basalRate ?: prev.basalRate,
            // without the basal object - the last temporary basal; with it - what came (null - none)
            tempBasal = if (basalKnown) new.tempBasal else prev.tempBasal,
            suspended = new.suspended ?: prev.suspended,
            basalKnown = basalKnown || prev.basalKnown,
        )
    }
}
