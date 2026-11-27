package tk.glucodata.mod.pumpbt

import tk.glucodata.mod.pumpbt.core.HistoryEvent
import tk.glucodata.mod.pumpbt.core.HistoryRecord
import java.time.ZoneId
import java.util.Locale

/**
 * JugglucoNGmod, pump over Bluetooth (trial): pump history records as lines for the journal and the screen. Unlike
 * the core's HistoryTimeline it keeps records that come before any reference time (time unknown), so a short first
 * read still shows something.
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
        var ref = reference
        val out = ArrayList<PumpBtHistoryLine>(records.size)
        for (r in records.sortedBy { it.sequence }) {
            val e = r.event
            if (e is HistoryEvent.ReferenceTime) {
                val ms = e.time.atZone(zone).toInstant().toEpochMilli()
                ref = r.sequence to ms
                out += PumpBtHistoryLine(r.sequence, ms, "Reference time", "")
                continue
            }
            val time = ref?.takeIf { r.sequence > it.first }?.let { it.second + r.relativeOffset * 1000L }
            val (kind, value) = describe(e)
            out += PumpBtHistoryLine(r.sequence, time, kind, value)
        }
        return out to ref
    }

    /** Kind and value of an event, in English (journal words). */
    fun describe(e: HistoryEvent): Pair<String, String> = when (e) {
        is HistoryEvent.ReferenceTime -> "Reference time" to e.time.toString()
        is HistoryEvent.BolusDelivered -> "Bolus" to buildString {
            append(u(e.fastUnits)).append(" U")
            if (e.extendedUnits > 0) append(" + ").append(u(e.extendedUnits)).append(" U over ").append(e.durationMinutes).append(" min")
        }
        is HistoryEvent.BolusDeliveredDetails -> "Bolus details" to "activation " + (e.activationType?.let { "0x%02x".format(it) } ?: "-")
        is HistoryEvent.AutoBasal -> "Auto basal" to "${u(e.units)} U"
        is HistoryEvent.Meal -> "Meal" to "${e.grams.toInt()} g"
        is HistoryEvent.BgReading -> "BG" to "${e.mgdl.toInt()} mg/dL"
        is HistoryEvent.Calibration -> "Calibration" to "${e.mgdl.toInt()} mg/dL, " + if (e.accepted) "accepted" else "rejected"
        is HistoryEvent.BasalRateChanged -> "Basal rate" to "${u(e.oldUnitsPerHour)} → ${u(e.newUnitsPerHour)} U/h"
        is HistoryEvent.DeliveryStopped -> "Delivery stopped" to "reason 0x%02x".format(e.reason)
        is HistoryEvent.DeliveryRestarted -> "Delivery restarted" to "reason 0x%02x".format(e.reason)
        is HistoryEvent.SensorGlucose -> "Sensor glucose" to (e.mgdl?.let { "$it mg/dL" } ?: "special state")
        is HistoryEvent.Annunciation -> "Alert" to "type 0x%03x, status 0x%02x".format(e.type, e.status)
        is HistoryEvent.Other -> "Event 0x%04x".format(e.type) to ""
    }

    /** Newest first, one line per record number, at most [max]. */
    fun merge(old: List<PumpBtHistoryLine>, new: List<PumpBtHistoryLine>, max: Int = 20): List<PumpBtHistoryLine> =
        (new + old).distinctBy { it.sequence }.sortedByDescending { it.sequence }.take(max)

    private fun u(v: Double): String = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')
}
