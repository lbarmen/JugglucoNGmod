package tk.glucodata.mod.pumpbt

import tk.glucodata.mod.pumpbt.core.HistoryDating
import tk.glucodata.mod.pumpbt.core.HistoryEvent
import tk.glucodata.mod.pumpbt.core.HistoryRecord
import tk.glucodata.mod.pumpbt.core.HistoryReferenceLocal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * JugglucoNGmod, pump over Bluetooth (trial): pump history records as lines for the journal and the screen. The pump
 * clock (local, no time zone) is turned into epoch ms here with the phone's time zone - the only place for that on
 * the history path. Records before the first reference time of a read are dated back from the next one
 * (core HistoryDating); records without any time are kept with time unknown, so a short first read still shows
 * something.
 */
internal object PumpBtHistory {

    /**
     * Lines for [records] (any order) and the newest reference after them. [reference] is the last known one:
     * its record number and the pump clock it gave (epoch ms).
     */
    fun lines(
        records: List<HistoryRecord>,
        reference: Pair<Long, Long>?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Pair<List<PumpBtHistoryLine>, Pair<Long, Long>?> {
        val stored = reference?.let { HistoryReferenceLocal(it.first, Instant.ofEpochMilli(it.second).atZone(zone).toLocalDateTime()) }
        // one read at a time: the records before it are not here, so a missing number is not a gap
        val times = HistoryDating.times(records, stored, requireNoGaps = false)
        var ref = reference
        val out = ArrayList<PumpBtHistoryLine>(records.size)
        for (r in records.sortedBy { it.sequence }) {
            val e = r.event
            val ms = times[r.sequence]?.atZone(zone)?.toInstant()?.toEpochMilli()
            if (e is HistoryEvent.ReferenceTime && ms != null && (ref == null || r.sequence >= ref.first)) ref = r.sequence to ms
            val (kind, value) = describe(e)
            out += PumpBtHistoryLine(r.sequence, ms, kind, value)
        }
        return out to ref
    }

    /** Kind and value of an event, in English (journal words). Null numbers (special values) - "?". */
    fun describe(e: HistoryEvent): Pair<String, String> = when (e) {
        is HistoryEvent.ReferenceTime -> "Reference time" to e.time.toString()
        is HistoryEvent.BolusProgrammed -> "Bolus requested" to bolus(e.bolusType, e.fastUnits, e.extendedUnits, e.durationMinutes)
        is HistoryEvent.BolusProgrammedDetails -> "Bolus request details" to "activation " + hex(e.activationType)
        is HistoryEvent.BolusDelivered -> "Bolus delivered" to bolus(e.bolusType, e.fastUnits, e.extendedUnits, e.durationMinutes)
        is HistoryEvent.BolusDeliveredDetails -> "Bolus details" to
            "activation ${hex(e.activationType)}, end ${hex(e.endReason)}, started +${e.startOffsetSec} s"
        is HistoryEvent.TherapyContext -> "Therapy context" to buildString {
            append("flags 0x%02x".format(e.flags))
            if (e.basalRate != null || e.flags and 0x02 != 0) append(", basal ").append(u(e.basalRate)).append(" U/h")
            if (e.stopReason != null) append(", stopped ").append(hex(e.stopReason))
            if (e.tempBasalType != null) {
                append(", temp basal ").append(u(e.tempBasalValue)).append(if (e.tempBasalType == 0x3C) " %" else " U/h")
            }
        }
        is HistoryEvent.AutoBasal -> "Auto basal" to "${u(e.units)} U"
        is HistoryEvent.Meal -> "Meal" to "${e.grams?.toInt() ?: "?"} g"
        is HistoryEvent.BgReading -> "BG" to "${e.mgdl?.toInt() ?: "?"} mg/dL"
        is HistoryEvent.Calibration -> "Calibration" to "${e.mgdl?.toInt() ?: "?"} mg/dL, " + if (e.accepted) "accepted" else "rejected"
        is HistoryEvent.BasalRateChanged -> "Basal rate" to "${u(e.oldUnitsPerHour)} → ${u(e.newUnitsPerHour)} U/h"
        is HistoryEvent.DeliveryStopped -> "Delivery stopped" to "reason 0x%02x".format(e.reason)
        is HistoryEvent.DeliveryRestarted -> "Delivery restarted" to "reason 0x%02x".format(e.reason)
        is HistoryEvent.SensorGlucose -> "Sensor glucose" to (e.mgdl?.let { "$it mg/dL" } ?: "special state")
        is HistoryEvent.Annunciation -> "Alert" to "type 0x%03x, status 0x%02x".format(e.type, e.status)
        is HistoryEvent.Other -> "Event 0x%04x".format(e.type) to ""
        is HistoryEvent.Unparsed -> "Not readable 0x%04x".format(e.type) to e.reason
    }

    /** Newest first, one line per record number, at most [max]. */
    fun merge(old: List<PumpBtHistoryLine>, new: List<PumpBtHistoryLine>, max: Int = 20): List<PumpBtHistoryLine> =
        (new + old).distinctBy { it.sequence }.sortedByDescending { it.sequence }.take(max)

    private fun bolus(type: Int, fast: Double?, ext: Double?, minutes: Int): String = buildString {
        append(u(fast)).append(" U")
        if (ext == null || ext > 0) append(" + ").append(u(ext)).append(" U over ").append(minutes).append(" min")
        when (type) {
            0x3C -> append(", square")
            0xFF -> append(", dual")
            0x33 -> {}
            else -> append(", type ").append(hex(type))
        }
    }

    private fun hex(v: Int?): String = v?.let { "0x%02x".format(it) } ?: "-"

    private fun u(v: Double?): String = v?.let { String.format(Locale.US, "%.3f", it).trimEnd('0').trimEnd('.') } ?: "?"
}
