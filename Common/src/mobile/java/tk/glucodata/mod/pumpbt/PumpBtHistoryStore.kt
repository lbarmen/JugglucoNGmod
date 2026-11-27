package tk.glucodata.mod.pumpbt

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import tk.glucodata.mod.pumpbt.core.BolusEvent
import tk.glucodata.mod.pumpbt.core.HistoryDating
import tk.glucodata.mod.pumpbt.core.HistoryEvent
import tk.glucodata.mod.pumpbt.core.HistoryReferenceLocal
import tk.glucodata.mod.pumpbt.core.PumpCycle
import tk.glucodata.mod.pumpbt.core.PumpEvent
import tk.glucodata.mod.pumpbt.core.PumpEvents
import tk.glucodata.mod.pumpbt.core.PumpEventsResult
import tk.glucodata.mod.pumpbt.core.RawHistoryRecord
import tk.glucodata.mod.pumpbt.core.hexToBytes
import tk.glucodata.mod.pumpbt.core.toHex
import tk.glucodata.mod.carelink.CareLinkJournalWriter
import tk.glucodata.mod.carelink.ClEvent
import tk.glucodata.mod.carelink.ClKind
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TreeMap

/*
 * JugglucoNGmod, pump over Bluetooth (series part 2, part B2): the pump history memory - raw records, the number up to
 * which everything came, the moment the source was turned on, the boundary of the old, the first catch-up card and
 * what the journal intake accepted (series brief, decisions 5-7). Pure rules in [PumpHistoryRules], the file in
 * [PumpBtHistoryStore].
 */

/** The first catch-up (decision 5): not computed yet, card shown, "Write" pressed, "Do not write the old" pressed. */
internal enum class BackfillState { NOT_READY, WAITING, CONFIRMED, DECLINED }

/**
 * Counters for the window and the link journal. [gaps] - records lost since the memory began (the pump dropped them,
 * or three failed cycles); the others describe the memory as it is now (about the last 48 h and what is held longer).
 */
internal data class PumpHistoryCounters(
    val unparsed: Int = 0,
    val gaps: Long = 0,
    val unpairedMeals: Int = 0,
    val noResultBoluses: Int = 0,
    val futureEvents: Int = 0,
    val heldBoluses: Int = 0,
    val undated: Int = 0,
)

internal data class PumpHistoryMemory(
    /** [tk.glucodata.mod.pumpbt.core.PumpIdentity.hash] of the pump the records came from. */
    val pumpHash: String? = null,
    /** By sequence number, no repeats. */
    val records: List<RawHistoryRecord> = emptyList(),
    /** The last reference time; lives on after its record is pruned. */
    val reference: HistoryReferenceLocal? = null,
    /** "The number": every record up to it is in [records], was pruned, or is marked lost (decision 7). */
    val contiguousTo: Long? = null,
    /** Cycles in a row that brought records past contiguousTo + 1, but not that record. */
    val stuckCount: Int = 0,
    /**
     * Cycles in a row with the same pump hash whose newest record on the pump was below contiguousTo (a late answer
     * of the pump can give a false low newest number): the third one makes it another pump.
     */
    val newestBackCount: Int = 0,
    /** When the source was turned on (decision 5); new only on a new enable and on an empty memory. */
    val enabledAtMs: Long? = null,
    /** The boundary of the old (decision 5): a new row only for an old event later than it. */
    val boundaryMs: Long? = null,
    val backfill: BackfillState = BackfillState.NOT_READY,
    /** Keys (ClEvent.key form) of the events the card lists to write; frozen once the card is made. */
    val backfillShown: List<String> = emptyList(),
    /** sourceRecordId of the rows the first catch-up fed and the intake accepted (for a manual roll-back). */
    val backfillWritten: List<String> = emptyList(),
    /** ClEvent.key of an event -> ClEvent.key of the cloud row it matched by time and dose (first catch-up only). */
    val aliases: Map<String, String> = emptyMap(),
    /** Key (after [aliases]) -> when the intake answered WRITTEN for it (a bolus only once finished). */
    val acceptedAt: Map<String, Long> = emptyMap(),
    val counters: PumpHistoryCounters = PumpHistoryCounters(),
    /** Unknown record types already notified (one notification per type). */
    val seenUnknownTypes: Set<Int> = emptySet(),
)

/**
 * The memory file `noBackupFilesDir/mod-pumpbt/history.json` (out of upstream's settings backup and of Android's
 * backup). Written through a temporary file and a rename. A file that cannot be read whole is an empty memory and
 * [loadFailed]: half a memory could claim records it does not hold. Holds raw records (they carry doses) - only here.
 */
internal class PumpBtHistoryStore(private val file: File) {

    constructor(context: Context) : this(File(File(context.applicationContext.noBackupFilesDir, DIR), FILE))

    /** The last [load] found a file it could not read (the memory then starts empty). */
    var loadFailed: Boolean = false
        private set

    fun load(): PumpHistoryMemory {
        loadFailed = false
        if (!file.isFile) return PumpHistoryMemory()
        return try {
            fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (t: Throwable) {
            PumpBtLog.w("history memory not read (${t.javaClass.simpleName})")
            loadFailed = true
            // kept beside, the last one only, before the next save writes over it (critic B2, N6)
            try {
                file.copyTo(File(file.parentFile, "$FILE$BROKEN"), overwrite = true)
            } catch (c: Throwable) {
                PumpBtLog.w("broken history memory not kept (${c.javaClass.simpleName})")
            }
            PumpHistoryMemory()
        }
    }

    fun save(memory: PumpHistoryMemory) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "$FILE.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(toJson(memory).toString().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            file.delete()
            if (!tmp.renameTo(file)) throw IOException("history memory not moved into place")
        }
    }

    companion object {
        const val DIR = "mod-pumpbt"
        const val FILE = "history.json"

        /** Suffix of the copy of the last file that could not be read. */
        const val BROKEN = ".broken"
        private const val VERSION = 1

        internal fun toJson(m: PumpHistoryMemory): JSONObject = JSONObject().apply {
            put("v", VERSION)
            putOpt("pump", m.pumpHash)
            put("records", JSONArray().apply { m.records.forEach { put(it.toPlain().toHex()) } })
            m.reference?.let { put("ref", JSONObject().put("seq", it.sequence).put("time", it.time.toString())) }
            putOpt("contiguousTo", m.contiguousTo)
            put("stuck", m.stuckCount)
            put("newestBack", m.newestBackCount)
            putOpt("enabledAt", m.enabledAtMs)
            putOpt("boundary", m.boundaryMs)
            put("backfill", m.backfill.name)
            put("shown", JSONArray(m.backfillShown))
            put("written", JSONArray(m.backfillWritten))
            put("aliases", JSONObject().apply { m.aliases.forEach { (k, v) -> put(k, v) } })
            put("accepted", JSONObject().apply { m.acceptedAt.forEach { (k, v) -> put(k, v) } })
            put("counters", JSONObject().apply {
                val c = m.counters
                put("unparsed", c.unparsed)
                put("gaps", c.gaps)
                put("unpairedMeals", c.unpairedMeals)
                put("noResultBoluses", c.noResultBoluses)
                put("futureEvents", c.futureEvents)
                put("heldBoluses", c.heldBoluses)
                put("undated", c.undated)
            })
            put("unknown", JSONArray(m.seenUnknownTypes.sorted()))
        }

        /** Throws on anything it cannot read: the caller takes the whole file as broken. */
        internal fun fromJson(o: JSONObject): PumpHistoryMemory {
            val v = o.getInt("v")
            if (v != VERSION) throw IOException("unknown version")
            val recs = o.getJSONArray("records")
            val records = (0 until recs.length()).map { i ->
                val bytes = hexToBytes(recs.getString(i)) ?: throw IOException("bad record")
                RawHistoryRecord.fromPlain(bytes) ?: throw IOException("short record")
            }
            val sorted = records.sortedBy { it.sequence }.distinctBy { it.sequence }
            if (sorted.size != records.size) throw IOException("repeated record")
            val ref = o.optJSONObject("ref")?.let {
                HistoryReferenceLocal(it.getLong("seq"), LocalDateTime.parse(it.getString("time")))
            }
            val c = o.getJSONObject("counters")
            return PumpHistoryMemory(
                pumpHash = if (o.isNull("pump")) null else o.optString("pump").takeIf { it.isNotEmpty() },
                records = sorted,
                reference = ref,
                contiguousTo = longOrNull(o, "contiguousTo"),
                stuckCount = o.getInt("stuck"),
                newestBackCount = o.optInt("newestBack", 0),
                enabledAtMs = longOrNull(o, "enabledAt"),
                boundaryMs = longOrNull(o, "boundary"),
                backfill = BackfillState.valueOf(o.getString("backfill")),
                backfillShown = strings(o.getJSONArray("shown")),
                backfillWritten = strings(o.getJSONArray("written")),
                aliases = o.getJSONObject("aliases").let { a -> a.keys().asSequence().associateWith { a.getString(it) } },
                acceptedAt = o.getJSONObject("accepted").let { a -> a.keys().asSequence().associateWith { a.getLong(it) } },
                counters = PumpHistoryCounters(
                    unparsed = c.getInt("unparsed"),
                    gaps = c.getLong("gaps"),
                    unpairedMeals = c.getInt("unpairedMeals"),
                    noResultBoluses = c.getInt("noResultBoluses"),
                    futureEvents = c.getInt("futureEvents"),
                    heldBoluses = c.getInt("heldBoluses"),
                    undated = c.getInt("undated"),
                ),
                seenUnknownTypes = o.getJSONArray("unknown").let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() },
            )
        }

        private fun longOrNull(o: JSONObject, name: String): Long? = if (!o.has(name) || o.isNull(name)) null else o.getLong(name)

        private fun strings(a: JSONArray): List<String> = (0 until a.length()).map { a.getString(it) }
    }
}

/** What one cycle did to the memory. */
internal data class CycleApplied(
    val memory: PumpHistoryMemory,
    /** No pump hash in the cycle: nothing changed. */
    val ignored: Boolean,
    /** Another pump (other hash, or its newest record below the number): the memory started again. */
    val newPump: Boolean,
    /** Records lost in this cycle (gone from the pump, or skipped after three failures). */
    val gapRecords: Long,
    /** First number of the hole skipped after [PumpHistoryRules.STUCK_CYCLES] failed cycles; null - none. */
    val skippedStuck: Long?,
    /** The number reached the newest record of the pump in this cycle (the history is read through). */
    val firstReadComplete: Boolean,
)

/**
 * The rules of the history memory (series brief, decision 7; plan part B2, points 1, 2, 6). Pure functions, no
 * Android.
 */
internal object PumpHistoryRules {

    /** A record leaves the memory not earlier than this after its time (and only once its events are settled). */
    const val KEEP_MS = 48 * 3_600_000L

    /** Failed cycles in a row on one hole before it is marked lost. */
    const val STUCK_CYCLES = 3

    /** Cycles in a row with the newest record below the number (same hash) before it counts as another pump. */
    const val NEWEST_BACK_CYCLES = 3

    /** Fallback boundary when an empty memory gets its enable moment here (decision 5: enable minus 24 h). */
    const val FALLBACK_BOUNDARY_MS = 24 * 3_600_000L

    /** The first number to ask for: contiguousTo + 1; null - from the oldest record of the pump. */
    fun nextFrom(m: PumpHistoryMemory): Long? = m.contiguousTo?.plus(1)

    /**
     * The records of [cycle] into the memory:
     * - no pump hash - nothing changes ([CycleApplied.ignored]);
     * - another hash (both known) - another pump: the memory starts again with the enable moment and the boundary at
     *   [nowMs] (this cycle's records are dropped: they were asked from the old number); the first hash of an empty
     *   memory is simply taken;
     * - the same hash, but the pump's newest record below the number: a late answer of the pump can give a false low
     *   newest number, so the history of this cycle is ignored (number and records unchanged) and
     *   [PumpHistoryMemory.newestBackCount] goes up; the [NEWEST_BACK_CYCLES]-th such cycle in a row makes it another
     *   pump; any other cycle with a hash sets the count back to 0;
     * - the pump's oldest record past the number + 1 - the number jumps to just before it, the missing records are a
     *   gap (counted);
     * - records above the number are added (a record at or below it - pruned or lost - is never taken again; a repeat
     *   keeps the stored one); the number then goes up only through records held in the memory, never past a hole;
     * - a hole that stays while records past it keep coming for [STUCK_CYCLES] cycles is marked lost up to the next
     *   record held ([CycleApplied.skippedStuck]) and the number goes on.
     * An empty memory without an enable moment (a broken file the source did not re-enable) gets [nowMs] and the
     * fallback boundary: old events are still written only after the card is confirmed.
     */
    fun applyCycle(m: PumpHistoryMemory, cycle: PumpCycle, nowMs: Long): CycleApplied {
        val hash = cycle.serialHash
            ?: return CycleApplied(m, ignored = true, newPump = false, gapRecords = 0, skippedStuck = null, firstReadComplete = false)
        val h = cycle.history
        val otherHash = m.pumpHash != null && m.pumpHash != hash
        val numberBack = h.newestOnPump != null && m.contiguousTo != null && h.newestOnPump < m.contiguousTo
        val backCount = if (numberBack && !otherHash) m.newestBackCount + 1 else 0
        if (otherHash || backCount >= NEWEST_BACK_CYCLES) {
            val fresh = PumpHistoryMemory(pumpHash = hash, enabledAtMs = nowMs, boundaryMs = nowMs, seenUnknownTypes = m.seenUnknownTypes)
            return CycleApplied(fresh, ignored = false, newPump = true, gapRecords = 0, skippedStuck = null, firstReadComplete = false)
        }
        if (backCount > 0) {
            // suspected: this cycle's history is not believed, nothing of it is taken
            val held = m.copy(pumpHash = m.pumpHash ?: hash, newestBackCount = backCount)
            return CycleApplied(held, ignored = false, newPump = false, gapRecords = 0, skippedStuck = null, firstReadComplete = false)
        }
        var mem = if (m.pumpHash == null) m.copy(pumpHash = hash) else m
        if (mem.newestBackCount != 0) mem = mem.copy(newestBackCount = 0)
        if (mem.enabledAtMs == null) {
            mem = mem.copy(enabledAtMs = nowMs, boundaryMs = mem.boundaryMs ?: (nowMs - FALLBACK_BOUNDARY_MS))
        }

        val have = TreeMap<Long, RawHistoryRecord>()
        for (r in mem.records) have.putIfAbsent(r.sequence, r)
        var gap = 0L
        var cur = mem.contiguousTo
        val oldest = h.oldestOnPump
        if (cur != null && oldest != null && oldest > cur + 1) {
            // the pump no longer has these records: asking for them again would repeat for ever
            gap += (oldest - cur - 1) - have.subMap(cur + 1, oldest).size
            cur = oldest - 1
        }
        val floor = cur
        for (r in cycle.records) if (floor == null || r.sequence > floor) have.putIfAbsent(r.sequence, r)

        // an empty memory: the link read from the oldest record, so the records start at requestedFrom
        val base = cur ?: h.requestedFrom?.let { it - 1 }
        val prevHole = mem.contiguousTo?.plus(1)
        cur = walk(have, base) ?: cur
        var stuck = mem.stuckCount
        var skipped: Long? = null
        if (cur != null) {
            val hole = cur + 1
            if (hole != prevHole) stuck = 0
            val pastHeld = have.higherKey(hole)
            // a failure on the hole only when the link asked for it (its snapshot of the number may be ahead; critic B2, N3)
            val asked = h.requestedFrom != null && h.requestedFrom <= hole
            stuck = when {
                pastHeld == null -> 0
                asked && cycle.records.any { it.sequence > hole } -> stuck + 1
                else -> stuck
            }
            if (stuck >= STUCK_CYCLES && pastHeld != null) {
                skipped = hole
                gap += pastHeld - hole
                cur = walk(have, pastHeld - 1)
                stuck = 0
            }
        }
        val firstReadComplete = h.newestOnPump != null && cur != null && cur >= h.newestOnPump
        mem = mem.copy(
            records = have.values.toList(),
            contiguousTo = cur,
            stuckCount = stuck,
            counters = mem.counters.copy(gaps = mem.counters.gaps + gap),
        )
        return CycleApplied(mem, ignored = false, newPump = false, gapRecords = gap, skippedStuck = skipped, firstReadComplete = firstReadComplete)
    }

    /** The last number reached from [from] through records held in [have] ([from] itself when the next is missing). */
    private fun walk(have: TreeMap<Long, RawHistoryRecord>, from: Long?): Long? {
        var c = from ?: return null
        while (have.containsKey(c + 1)) c++
        return c
    }

    /**
     * Turns the source on (decision 5): a new enable moment and boundary, a new card (only for events not accepted
     * yet - the accepted ones and the aliases stay, so rows written before are found by their exact key).
     */
    fun enable(m: PumpHistoryMemory, nowMs: Long, boundaryMs: Long): PumpHistoryMemory = m.copy(
        enabledAtMs = nowMs,
        boundaryMs = minOf(boundaryMs, nowMs),
        backfill = BackfillState.NOT_READY,
        backfillShown = emptyList(),
    )

    /**
     * After WRITTEN of the intake for [events] (the ClEvents just fed): each is accepted (its key is after aliases) -
     * a bolus only once finished (a running one is not); a meal only when its bolus (at most 10 s after it, as the
     * writer pairs them) was fed with it and is accepted now or was before - the writer writes carbs only with their
     * bolus (critic B2, C1). Keys of the first catch-up go to [PumpHistoryMemory.backfillWritten].
     */
    fun markAccepted(m: PumpHistoryMemory, events: List<ClEvent>, nowMs: Long): PumpHistoryMemory {
        val accepted = LinkedHashMap(m.acceptedAt)
        val written = LinkedHashSet(m.backfillWritten)
        val boluses = events.filter { it.kind == ClKind.BOLUS }
        for (e in events) {
            val ok = when (e.kind) {
                ClKind.BOLUS -> e.completed == true
                ClKind.MEAL -> boluses
                    .filter { b -> e.atMs in (b.atMs - CareLinkJournalWriter.PAIR_WINDOW_MS)..b.atMs }
                    .minByOrNull { it.atMs }
                    ?.let { b -> b.completed == true || b.key in m.acceptedAt } == true
                else -> true
            }
            if (!ok) continue
            if (accepted.putIfAbsent(e.key, nowMs) == null && e.key in m.backfillShown) {
                PumpBtBridge.journalKey(e.kind, e.key)?.let { written += it }
            }
        }
        return m.copy(acceptedAt = accepted, backfillWritten = written.toList())
    }

    /**
     * Prunes the memory (plan part B2, point 2) - only from its start and only at a reference time, so the records
     * that stay keep their time (dating needs no missing numbers between a reference and a record). A unit is a
     * record; the parts of one bolus (0x005A, 0x0066, 0x0069, 0x0096) are one unit, its age by the newest part. A unit
     * may go when it is older than [KEEP_MS] (an undated record - when a dated record with a larger number is) and its
     * events for the journal are settled: accepted, or old ones the first catch-up did not list (declined, matched,
     * before the boundary) - or it has none. The cut is at the newest reference R at or below the number below which
     * every unit may go and no unit crosses R: everything below R goes, R stays the oldest record. [events] - built
     * from this memory. The reference is taken from its lastReference.
     */
    fun prune(m: PumpHistoryMemory, events: PumpEventsResult, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): PumpHistoryMemory {
        val reference = events.lastReference ?: m.reference
        val cTo = m.contiguousTo ?: return m.copy(reference = reference)
        val raw = m.records.sortedBy { it.sequence }
        if (raw.isEmpty()) return m.copy(reference = reference)
        val parsed = raw.map { it.parse() }
        val bySeq = parsed.associateBy { it.sequence }
        val times = HistoryDating.times(parsed, m.reference)
        val limit = nowMs - KEEP_MS
        fun ms(t: LocalDateTime): Long = t.atZone(zone).toInstant().toEpochMilli()
        val newestOldDated = parsed.filter { r -> times[r.sequence]?.let { ms(it) < limit } == true }.maxOfOrNull { it.sequence }
        fun old(seq: Long): Boolean = times[seq]?.let { ms(it) < limit } ?: (newestOldDated != null && newestOldDated > seq)

        // units: the parts of a bolus by the events and by the same pairing as PumpEvents (also undated ones)
        val parent = HashMap<Long, Long>()
        fun find(s: Long): Long {
            var x = s
            while (true) {
                val p = parent[x] ?: return x
                if (p == x) return x
                x = p
            }
        }
        fun union(a: Long, b: Long) {
            if (a !in bySeq || b !in bySeq) return
            val ra = find(a)
            val rb = find(b)
            if (ra != rb) parent[maxOf(ra, rb)] = minOf(ra, rb)
        }
        fun withDetails(s: Long, detailType: Int) {
            if (bySeq[s + 1]?.eventType == detailType) union(s, s + 1)
        }
        val programmed = parsed.filter { it.event is HistoryEvent.BolusProgrammed }
        val taken = HashSet<Long>()
        for (d in parsed) {
            val de = d.event as? HistoryEvent.BolusDelivered ?: continue
            val p = programmed.lastOrNull {
                it.sequence < d.sequence && d.sequence - it.sequence <= PumpEvents.MAX_BOLUS_DISTANCE &&
                    (it.event as HistoryEvent.BolusProgrammed).id == de.id && it.sequence !in taken
            } ?: continue
            taken += p.sequence
            union(p.sequence, d.sequence)
        }
        for (r in parsed) {
            when (r.eventType) {
                0x005A -> withDetails(r.sequence, 0x0066)
                0x0069 -> withDetails(r.sequence, 0x0096)
            }
        }
        for (e in events.events) {
            if (e !is BolusEvent) continue
            val req = e.requestSequence
            val del = e.deliveredSequence
            if (req != null && del != null) union(req, del)
        }

        val keys = PumpBtBridge.eventKeys(events.events)
        val unsettledUnits = HashSet<Long>()
        for ((e, key) in keys) {
            if (!settled(m, e, key, zone)) unsettledUnits += find(e.sequence)
        }
        val unitMax = HashMap<Long, Long>()
        val unitOld = HashMap<Long, Boolean>()
        for (r in parsed) {
            val u = find(r.sequence)
            unitMax[u] = maxOf(unitMax[u] ?: Long.MIN_VALUE, r.sequence)
            unitOld[u] = (unitOld[u] ?: true) && old(r.sequence)
        }

        var cut = -1
        var maxSeen = Long.MIN_VALUE
        for ((i, r) in parsed.withIndex()) {
            if (r.sequence > cTo) break
            if (i > 0 && r.event is HistoryEvent.ReferenceTime && maxSeen < r.sequence) cut = i
            val u = find(r.sequence)
            if (unitOld[u] != true || u in unsettledUnits) break
            maxSeen = maxOf(maxSeen, unitMax[u] ?: r.sequence)
        }
        if (cut <= 0) return m.copy(reference = reference)
        val cutSeq = parsed[cut].sequence

        // the events whose records all went: their aliases and acceptance are no longer needed
        val aliases = LinkedHashMap(m.aliases)
        val accepted = LinkedHashMap(m.acceptedAt)
        for ((e, key) in keys) {
            if (unitMax[find(e.sequence)]?.let { it < cutSeq } != true) continue
            val ek = m.aliases[key] ?: key
            aliases.remove(key)
            accepted.remove(ek)
        }
        return m.copy(
            records = raw.filter { it.sequence >= cutSeq },
            reference = reference,
            aliases = aliases,
            acceptedAt = accepted,
        )
    }

    /**
     * The event needs nothing more from the memory: accepted, or an old one (before the enable moment) the confirmed
     * card did not list or the person declined.
     */
    internal fun settled(m: PumpHistoryMemory, e: PumpEvent, key: String, zone: ZoneId): Boolean {
        if ((m.aliases[key] ?: key) in m.acceptedAt) return true
        val enabledAt = m.enabledAtMs ?: return false
        if (e.at.atZone(zone).toInstant().toEpochMilli() >= enabledAt) return false
        return when (m.backfill) {
            BackfillState.DECLINED -> true
            BackfillState.CONFIRMED -> key !in m.backfillShown
            else -> false
        }
    }
}
