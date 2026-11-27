package tk.glucodata.mod.pumpbt.core

import java.security.MessageDigest
import java.time.LocalDateTime

/*
 * JugglucoNGmod, pump over Bluetooth: what one read cycle of the link gives (PumpCycle), and the pump history turned
 * into events (PumpEvents). No Android here. Times are the pump's local clock (LocalDateTime, to the second); they are
 * turned into instants only at the edges (bridge, screen) with the phone's time zone.
 */

internal enum class HistoryReadStatus { NONE, COMPLETE, GAP, TIMED_OUT, FAILED }

/** How the history read of a cycle went. The link reports it; the history memory keeps the number. */
internal data class HistoryRead(
    val status: HistoryReadStatus,
    /** First sequence number asked for (null - not asked). */
    val requestedFrom: Long? = null,
    /** Last one asked for (the pump's newest at the time of the request). */
    val requestedTo: Long? = null,
    /** Answer to "first record" (33 66 0F). */
    val oldestOnPump: Long? = null,
    /** Answer to "last record" (33 69 0F). */
    val newestOnPump: Long? = null,
    /** All numbers requestedFrom..contiguousTo came (null - not even the first one). */
    val contiguousTo: Long? = null,
    val missing: List<LongRange> = emptyList(),
)

/** One finished read cycle of the link. */
internal data class PumpCycle(
    /** Phone clock at the end of the cycle. */
    val readAtMs: Long,
    /** [PumpIdentity.hash] of the serial number; null - not read: nothing is passed on or saved. */
    val serialHash: String?,
    /** IDD Status therapy control state: 0x33 stop, 0x3C pause, 0x55 run. */
    val therapyState: Int? = null,
    val reservoirUnits: Double? = null,
    val iobUnits: Double? = null,
    val basalRate: Double? = null,
    /** The "active basal" answer came. */
    val basalRead: Boolean = false,
    /** The temporary basal's value: U/h or percent - by [tempBasalType]. */
    val tempBasalRate: Double? = null,
    /** IDD TBR type: 0x33 - U/h, 0x3C - percent (critic of the joints 1, B-1). */
    val tempBasalType: Int? = null,
    val tempBasalMinutesLeft: Int? = null,
    val batteryPercent: Int? = null,
    /** By sequence number, no repeats; there may be gaps. */
    val records: List<RawHistoryRecord> = emptyList(),
    val history: HistoryRead = HistoryRead(HistoryReadStatus.NONE),
)

/** Identifies a pump without keeping its serial number. */
internal object PumpIdentity {
    /** SHA-256 (lowercase hex) of the serial number without NUL bytes and blanks at both ends. */
    fun hash(serial: String): String {
        val clean = serial.trim { it == '\u0000' || it.isWhitespace() }
        return MessageDigest.getInstance("SHA-256").digest(clean.toByteArray(Charsets.UTF_8)).toHex()
    }
}

internal sealed class PumpEvent {
    abstract val sequence: Long
    abstract val at: LocalDateTime
}

/** A bolus from its parts: "programmed" (0x005A + 0x0066) and "delivered" (0x0069 + 0x0096). */
internal data class BolusEvent(
    /** The "programmed" record if there is one, otherwise the "delivered" record. */
    override val sequence: Long,
    /** Time of "programmed"; the time of "delivered" only when [timeFromDelivery]. */
    override val at: LocalDateTime,
    val bolusId: Int,
    val requestSequence: Long?,
    val deliveredSequence: Long?,
    val deliveredAt: LocalDateTime?,
    /** 0x33 normal, 0x3C square, 0xFF dual, 0x0F undetermined. */
    val bolusType: Int?,
    /** Fast + extended of "programmed"; null - not known. */
    val programmedUnits: Double?,
    /** Fast + extended of "delivered"; null - still running (or not known). */
    val deliveredUnits: Double?,
    val programmedExtendedUnits: Double?,
    val deliveredExtendedUnits: Double?,
    val durationMinutes: Int?,
    /** A "delivered" record is there. */
    val completed: Boolean,
    val endReason: Int?,
    val activationType: Int?,
    /** "Programmed" will not come any more: time and number are those of "delivered". */
    val timeFromDelivery: Boolean = false,
) : PumpEvent()

internal data class MealEvent(override val sequence: Long, override val at: LocalDateTime, val grams: Double) : PumpEvent()
internal data class DeliveryStopEvent(override val sequence: Long, override val at: LocalDateTime, val reason: Int) : PumpEvent()
internal data class DeliveryResumeEvent(override val sequence: Long, override val at: LocalDateTime, val reason: Int) : PumpEvent()
internal data class BasalChangeEvent(
    override val sequence: Long, override val at: LocalDateTime, val oldRate: Double?, val newRate: Double?,
) : PumpEvent()

/** A record whose body did not match its format (or held no usable value); [reason] is English, without values. */
internal data class UnparsedRecord(val sequence: Long, val type: Int, val reason: String)

internal data class PumpEventsResult(
    /** By time, then by sequence number. */
    val events: List<PumpEvent>,
    val unparsed: List<UnparsedRecord>,
    /** Types neither decoded nor in [PumpEvents.KNOWN_TYPES]. */
    val unknownTypes: Set<Int>,
    /** Sequence numbers of records that would give an event but have no time. */
    val undated: List<Long>,
    /** "Delivered" without "programmed", held back until "programmed" can no longer come. */
    val heldBoluses: Int,
    val lastReference: HistoryReferenceLocal?,
)

/** Pump-local times of history records from the reference times (0xF00E). */
internal object HistoryDating {

    private class Ref(val sequence: Long, val time: LocalDateTime, val offset: Int, val periodic: Boolean)

    /**
     * Time of each record in [records], by sequence number (records without a time are missing):
     * - a reference time - its own time;
     * - after a reference - the latest earlier reference plus the record's offset ([stored] counts as that reference
     *   when it lies between the two);
     * - before the first reference of [records] - the next reference minus its offset plus the record's offset (the
     *   reference's offset is its distance to the previous reference); only if the record's offset is not larger than
     *   the reference's (otherwise the record would land after the reference it precedes);
     * - otherwise [stored], if it is earlier than the record; else no time.
     * A reference with another reason than periodic (0x3C) counts the same. A record is dated from a reference (either
     * way) only if no record number between them is missing ([requireNoGaps]) and no record between them is an
     * unreadable reference time: a lost reference would shift the time by up to an hour. If the first way is not
     * clean, the other one is tried; if neither is - no time.
     * Back from a reference of another reason than periodic (time set, time lost, undetermined) only once every record
     * before the record came or is marked lost ([contiguousTo] ≥ its number − 1): across a clock change the two ways
     * differ by the change, and a time once given must not move when a gap before the record closes (a fed bolus would
     * get a second key; critic of the joints 1, N-2). Until then the record waits undated; the memory gets past a gap
     * (three failures), so it does not wait for ever.
     * [requireNoGaps] = false is for the trial screen only, which dates one read at a time.
     */
    fun times(
        records: List<HistoryRecord>, stored: HistoryReferenceLocal?, requireNoGaps: Boolean = true, contiguousTo: Long? = null,
    ): Map<Long, LocalDateTime> {
        val sorted = records.sortedBy { it.sequence }.distinctBy { it.sequence }
        val seqs = LongArray(sorted.size) { sorted[it].sequence }
        // brokenBefore[i]: unreadable reference times among sorted[0 until i]
        val brokenBefore = IntArray(sorted.size + 1)
        for (i in sorted.indices) {
            val r = sorted[i]
            brokenBefore[i + 1] = brokenBefore[i] + if (r.eventType == 0xF00E && r.event is HistoryEvent.Unparsed) 1 else 0
        }
        /** Records strictly between sequence numbers [a] and [b] are all there and none is an unreadable reference. */
        fun clean(a: Long, b: Long): Boolean {
            val from = lowerBound(seqs, a + 1)
            val to = lowerBound(seqs, b)
            return (!requireNoGaps || (to - from).toLong() == b - a - 1) && brokenBefore[to] == brokenBefore[from]
        }

        val refs = sorted.mapNotNull { r ->
            (r.event as? HistoryEvent.ReferenceTime)?.let {
                Ref(r.sequence, it.time, r.relativeOffset, it.reason == null || it.reason == HistoryEvent.ReferenceTime.PERIODIC)
            }
        }
        val out = HashMap<Long, LocalDateTime>(sorted.size * 2)
        for (r in sorted) {
            val e = r.event
            if (e is HistoryEvent.ReferenceTime) {
                out[r.sequence] = e.time
                continue
            }
            val prev = refs.lastOrNull { it.sequence < r.sequence }
            val next = refs.firstOrNull { it.sequence > r.sequence }
            val offset = r.relativeOffset.toLong()
            val forwardBase = when {
                stored != null && stored.sequence < r.sequence && (prev == null || stored.sequence > prev.sequence) -> stored.sequence to stored.time
                prev != null -> prev.sequence to prev.time
                else -> null
            }
            val forward = forwardBase?.takeIf { clean(it.first, r.sequence) }?.second?.plusSeconds(offset)
            val settledBefore = contiguousTo != null && contiguousTo >= r.sequence - 1
            val back = next?.takeIf { r.relativeOffset <= it.offset && clean(r.sequence, it.sequence) }
                ?.takeIf { !requireNoGaps || it.periodic || settledBefore }
                ?.let { it.time.minusSeconds(it.offset.toLong()).plusSeconds(offset) }
            // after a reference in the records: forward first; before the first one: back first, then the stored one
            val time = if (prev != null) forward ?: back else back ?: forward
            if (time != null) out[r.sequence] = time
        }
        return out
    }

    /** First index with a value ≥ [x] in the ascending [a]. */
    private fun lowerBound(a: LongArray, x: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < x) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** The newest of the references in [records] and [stored] (by sequence number). */
    fun latest(records: List<HistoryRecord>, stored: HistoryReferenceLocal?): HistoryReferenceLocal? {
        val inRecords = records.filter { it.event is HistoryEvent.ReferenceTime }.maxByOrNull { it.sequence }
            ?.let { HistoryReferenceLocal(it.sequence, (it.event as HistoryEvent.ReferenceTime).time) }
        return when {
            inRecords == null -> stored
            stored == null || inRecords.sequence >= stored.sequence -> inRecords
            else -> stored
        }
    }
}

/** Pump history records (the whole memory, not one read) → events with the pump's local time. */
internal object PumpEvents {

    /** "Delivered" is matched to "programmed" at most this many records back (the bolus id is only 2 bytes). */
    const val MAX_BOLUS_DISTANCE = 2_000L

    /** A bolus (fast + extended) above this many units is not believed: the record is reported as unparsed. */
    const val MAX_BOLUS_UNITS = 75.0

    /** A meal above this many grams is not believed: the record is reported as unparsed. */
    const val MAX_MEAL_GRAMS = 300.0

    /** Types decoded here and service types skipped on purpose; any other type is reported in unknownTypes. */
    val KNOWN_TYPES: Set<Int> = setOf(
        0x005A, 0x0066, 0x0069, 0x0096, // bolus programmed / delivered, parts 1 and 2
        0x0099, // basal rate changed
        0xF004, // therapy context
        0xF005, // meal
        0xF00A, 0xF00B, // delivery stopped / restarted
        0xF00E, // reference time
        // skipped on purpose
        0xF001, // auto basal (micro bolus)
        0xF007, 0xF008, 0xF009, // BG reading, calibration
        0xF00C, // sensor glucose
        0xF00F, // annunciation cleared
        0xF010, // annunciation
        // Seen on a 740G next to the temporary basal (build 15). Not confirmed: from memory these are IDS "TBR
        // Adjustment Started / Ended"; OpenMinimed's idd-service.md (pinned commit) lists only Medtronic's 0xF0xx
        // types. Skipped: the basal comes from the snapshot.
        0x00A5, 0x00AA,
    )

    /**
     * Events from [records] (repeats of a sequence number count once - the first one given). [stored] is the last
     * reference time kept by the memory; [contiguousTo] is the memory's number (everything up to it came or is marked
     * lost), used for a "delivered" without "programmed".
     * Not believed and not used, but reported in unparsed: a bolus part or meal with a special value instead of the
     * dose or grams, a dose below 0 or above [MAX_BOLUS_UNITS], grams below 0 or above [MAX_MEAL_GRAMS], and a second
     * "delivered" for a bolus id whose "programmed" is already taken (a dual bolus is not seen on a 740G; an extra dose
     * would be worse than a counter).
     */
    fun build(records: List<RawHistoryRecord>, stored: HistoryReferenceLocal?, contiguousTo: Long?): PumpEventsResult {
        val parsed = records.sortedBy { it.sequence }.distinctBy { it.sequence }.map { checked(it.parse()) }
        val bySeq = parsed.associateBy { it.sequence }
        val times = HistoryDating.times(parsed, stored, contiguousTo = contiguousTo)

        val events = ArrayList<PumpEvent>()
        val unparsed = ArrayList<UnparsedRecord>()
        val unknown = LinkedHashSet<Int>()
        val undated = ArrayList<Long>()

        // Bolus parts: "delivered" to the nearest earlier "programmed" with the same id not taken yet.
        val programmed = parsed.filter { it.event is HistoryEvent.BolusProgrammed }
        val takenBy = HashMap<Long, HistoryRecord>() // programmed sequence -> its delivered record
        val unmatched = ArrayList<HistoryRecord>()
        for (d in parsed) {
            val de = d.event as? HistoryEvent.BolusDelivered ?: continue
            val p = programmed.lastOrNull {
                it.sequence < d.sequence && d.sequence - it.sequence <= MAX_BOLUS_DISTANCE &&
                    (it.event as HistoryEvent.BolusProgrammed).id == de.id && it.sequence !in takenBy
            }
            val secondDelivery = p == null && programmed.any {
                it.sequence < d.sequence && d.sequence - it.sequence <= MAX_BOLUS_DISTANCE &&
                    (it.event as HistoryEvent.BolusProgrammed).id == de.id && it.sequence in takenBy
            }
            when {
                p != null -> takenBy[p.sequence] = d
                secondDelivery -> unparsed += UnparsedRecord(d.sequence, d.eventType, "second delivery of bolus")
                else -> unmatched += d
            }
        }
        var held = 0

        fun addBolus(p: HistoryRecord?, d: HistoryRecord?, fromDelivery: Boolean) {
            val pe = p?.event as HistoryEvent.BolusProgrammed?
            val de = d?.event as HistoryEvent.BolusDelivered?
            val main = p ?: d!!
            val at = times[main.sequence] ?: run {
                undated += main.sequence
                return
            }
            val pd = p?.let { bySeq[it.sequence + 1]?.event as? HistoryEvent.BolusProgrammedDetails }
            val dd = d?.let { bySeq[it.sequence + 1]?.event as? HistoryEvent.BolusDeliveredDetails }
            events += BolusEvent(
                sequence = main.sequence,
                at = at,
                bolusId = pe?.id ?: de!!.id,
                requestSequence = p?.sequence,
                deliveredSequence = d?.sequence,
                deliveredAt = d?.let { times[it.sequence] },
                bolusType = pe?.bolusType ?: de?.bolusType,
                programmedUnits = pe?.let { sum(it.fastUnits!!, it.extendedUnits!!) },
                deliveredUnits = de?.let { sum(it.fastUnits!!, it.extendedUnits!!) },
                programmedExtendedUnits = pe?.extendedUnits,
                deliveredExtendedUnits = de?.extendedUnits,
                durationMinutes = pe?.durationMinutes ?: de?.durationMinutes,
                completed = d != null,
                endReason = dd?.endReason,
                activationType = dd?.activationType ?: pd?.activationType,
                timeFromDelivery = fromDelivery,
            )
        }

        for (p in programmed) addBolus(p, takenBy[p.sequence], fromDelivery = false)
        for (d in unmatched) {
            // "Programmed" can no longer come only when everything before "delivered" came or is marked lost.
            if (contiguousTo != null && contiguousTo >= d.sequence - 1) addBolus(null, d, fromDelivery = true) else held++
        }

        for (r in parsed) {
            when (val e = r.event) {
                is HistoryEvent.Unparsed -> unparsed += UnparsedRecord(r.sequence, r.eventType, e.reason)
                is HistoryEvent.Other -> if (e.type !in KNOWN_TYPES) unknown += e.type
                is HistoryEvent.Meal -> {
                    val grams = e.grams ?: continue // checked() made it Unparsed
                    dated(r, times, undated) { MealEvent(r.sequence, it, grams) }?.let(events::add)
                }
                is HistoryEvent.DeliveryStopped -> dated(r, times, undated) { DeliveryStopEvent(r.sequence, it, e.reason) }?.let(events::add)
                is HistoryEvent.DeliveryRestarted -> dated(r, times, undated) { DeliveryResumeEvent(r.sequence, it, e.reason) }?.let(events::add)
                is HistoryEvent.BasalRateChanged ->
                    dated(r, times, undated) { BasalChangeEvent(r.sequence, it, e.oldUnitsPerHour, e.newUnitsPerHour) }?.let(events::add)
                else -> {}
            }
        }

        events.sortWith(compareBy<PumpEvent>({ it.at }, { it.sequence }))
        return PumpEventsResult(
            events = events,
            unparsed = unparsed.sortedBy { it.sequence },
            unknownTypes = unknown,
            undated = undated.sorted(),
            heldBoluses = held,
            lastReference = HistoryDating.latest(parsed, stored),
        )
    }

    /** [r], or an Unparsed copy when its dose or grams are not believable (see [build]). */
    private fun checked(r: HistoryRecord): HistoryRecord {
        val reason = when (val e = r.event) {
            is HistoryEvent.BolusProgrammed -> doseProblem(e.fastUnits, e.extendedUnits)
            is HistoryEvent.BolusDelivered -> doseProblem(e.fastUnits, e.extendedUnits)
            is HistoryEvent.Meal -> when {
                e.grams == null -> "special value instead of grams"
                e.grams < 0 || e.grams > MAX_MEAL_GRAMS -> "value out of range"
                else -> null
            }
            else -> null
        } ?: return r
        return r.copy(event = HistoryEvent.Unparsed(r.eventType, reason))
    }

    private fun doseProblem(fast: Double?, ext: Double?): String? = when {
        fast == null || ext == null -> "special value instead of dose"
        fast < 0 || ext < 0 || fast + ext > MAX_BOLUS_UNITS -> "value out of range"
        else -> null
    }

    private inline fun dated(
        r: HistoryRecord, times: Map<Long, LocalDateTime>, undated: MutableList<Long>, make: (LocalDateTime) -> PumpEvent,
    ): PumpEvent? {
        val t = times[r.sequence] ?: run {
            undated += r.sequence
            return null
        }
        return make(t)
    }

    /** Fast + extended (both known after [checked]), rounded to 6 decimals against binary noise (0.1 + 0.2). */
    private fun sum(a: Double, b: Double): Double = Math.round((a + b) * 1e6) / 1e6
}
