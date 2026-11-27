package tk.glucodata.mod.pumpbt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.mod.carelink.CareLinkJournalWriter
import tk.glucodata.mod.carelink.CareLinkMarkers
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.carelink.ClEvent
import tk.glucodata.mod.carelink.ClKind
import tk.glucodata.mod.carelink.PumpNow
import tk.glucodata.mod.carelink.TempBasal
import tk.glucodata.mod.pumpbt.core.BolusEvent
import tk.glucodata.mod.pumpbt.core.MealEvent
import tk.glucodata.mod.pumpbt.core.PumpCycle
import tk.glucodata.mod.pumpbt.core.PumpEvent
import tk.glucodata.mod.pumpbt.core.PumpEventsResult
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** A journal row as the bridge needs it: insulin ([ClKind.BOLUS]) or carbs ([ClKind.MEAL]). */
internal data class JournalRow(val id: Long, val kind: ClKind, val atMs: Long, val amount: Double?, val sourceRecordId: String?)

/** One line of the first catch-up card: the event key, its moment, the dose (a bolus) or the grams (a meal). */
internal data class BackfillItem(val key: String, val atMs: Long, val units: Double?, val grams: Double?)

/**
 * The first catch-up card (decision 5): boluses and meals found before the enable moment (not accepted yet), how many
 * of them the journal already has, the list to write (frozen) and the old ones before the boundary that the journal
 * does not have (not written).
 */
internal data class BackfillCard(
    val bolusesBefore: Int, val mealsBefore: Int, val alreadyInJournal: Int,
    val toWrite: List<BackfillItem>, val oldNotFound: Int,
)

internal enum class PumpWarning { FUTURE_EVENT, CLOCK_DRIFT, NO_RESULT_BOLUS, UNPAIRED_MEAL, HELD_BOLUS }

/**
 * What one cycle gives the journal intake. [toWriter] - the events to feed; [card] - the first catch-up card while it
 * waits for the person (null otherwise); [memory] - to save before the intake (aliases, card, counters, events marked
 * accepted without feeding). [matchedByKey], [matchedByTime], [fresh] - the matching result in the cycle the card is
 * made (0 in the others), for the link journal (no doses).
 */
internal data class CyclePlan(
    val toWriter: List<ClEvent>, val card: BackfillCard?, val memory: PumpHistoryMemory,
    val warnings: Set<PumpWarning>, val matchedByKey: Int, val matchedByTime: Int, val fresh: Int,
)

/**
 * JugglucoNGmod, pump over Bluetooth (series part 2, part B2): pump events and cycles into the models of the CareLink
 * writers ([ClEvent], [PumpNow]), and what each cycle feeds them (series brief, decisions 4-6, 9).
 *
 * - Key in the cloud's form `INSULIN|<pump local time to the second>|n` / `MEAL|…|n`: rows the cloud wrote under the
 *   same second are found by their key.
 * - Fed every cycle: events from the enable moment on within the last 24 h (the writer does not write the same twice;
 *   a running bolus gets its result into the same row); older ones from the enable moment on until accepted (a long
 *   break of the link); old ones (before the enable moment) only after "Write", only the listed ones, until accepted -
 *   and an old bolus whose row the journal already holds under its key (ours or the cloud's), so a running one gets
 *   its result, once the card is answered. Never fed: a time later than now + 10 min; an event older than 48 h whose
 *   row the journal holds (it is marked accepted instead: the writer would adopt the row and write over the person's
 *   edit, CareLinkJournalWriter `find`); a bolus still running after 48 h.
 * - Matching by time and dose (window "programmed - 90 s ... delivered + 90 s", dose = programmed or delivered within
 *   [CareLinkMarkers.EPS], grams the same) only for the first catch-up and only for events before the enable moment;
 *   first the exact key (for every event), then the nearest row by time; a row is taken once. A cloud row - the event
 *   takes its key ([PumpHistoryMemory.aliases]); a row of the person or another source - the event is not written.
 *   Old ones before the boundary that the journal does not have are counted, not written.
 * Pure functions, except [boundary] and [journalRows] (Room through [loadRows] / [loadByKeys]).
 */
internal object PumpBtBridge {

    /** Matching window around a bolus or meal (wider than the writer's 60 s re-key: the cloud's seconds are unknown). */
    const val MATCH_WINDOW_MS = 90_000L

    /** An event later than now + this is not fed (the pump's clock runs ahead). */
    const val FUTURE_MS = 10 * 60_000L

    /** Events from the enable moment within this are fed every cycle (as the cloud's 24 h answer). */
    const val DAY_MS = 24 * 3_600_000L

    /** An event older than this whose row the journal holds is not fed (see the class notes). */
    const val OLD_ROW_MS = PumpHistoryRules.KEEP_MS

    /** A bolus running longer than its duration plus this - "result not received". */
    const val NO_RESULT_MS = 30 * 60_000L

    /** The pump writes a reference time every hour: phone time minus the last one outside 0..this - clock drift. */
    const val DRIFT_MAX_MS = 75 * 60_000L

    /** Boundary: the latest `carelink:` row within this before the enable moment ... */
    const val BOUNDARY_LOOKBACK_MS = 10 * 24 * 3_600_000L

    /** ... or the cloud's last data time, minus this. */
    const val BOUNDARY_MARGIN_MS = 10 * 60_000L

    private val KEY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private const val CARELINK = "carelink:"

    /** Journal rows between two moments (upstream's `getEntriesBetween`); tests put their own. */
    @Volatile
    internal var loadRows: suspend (Context, Long, Long) -> List<JournalRow> = { c, from, to ->
        withContext(Dispatchers.IO) { dao(c).getEntriesBetween(from, to).mapNotNull(::row) }
    }

    /** Journal rows by sourceRecordId (upstream's `getEntriesBySourceRecordIds`); tests put their own. */
    @Volatile
    internal var loadByKeys: suspend (Context, List<String>) -> List<JournalRow> = { c, keys ->
        withContext(Dispatchers.IO) {
            val dao = dao(c)
            keys.distinct().chunked(500).flatMap { dao.getEntriesBySourceRecordIds(it) }.mapNotNull(::row)
        }
    }

    /**
     * Times of the journal rows of any type whose sourceRecordId starts with `carelink:` (bolus, carbs, basal, stop
     * note) between two moments - for [boundary]; tests put their own.
     */
    @Volatile
    internal var loadCloudRowTimes: suspend (Context, Long, Long) -> List<Long> = { c, from, to -> cloudRowTimes(c, from, to) }

    private suspend fun cloudRowTimes(c: Context, from: Long, to: Long): List<Long> = withContext(Dispatchers.IO) {
        cloudTimes(dao(c).getEntriesBetween(from, to))
    }

    /** Times of the rows with a `carelink:` key, whatever their type. */
    internal fun cloudTimes(rows: List<JournalEntryEntity>): List<Long> =
        rows.filter { it.sourceRecordId?.startsWith(CARELINK) == true }.map { it.timestamp }

    private fun dao(c: Context) = HistoryDatabase.getInstance(c.applicationContext).journalDao()

    private fun row(e: JournalEntryEntity): JournalRow? {
        val kind = when (JournalEntryType.fromStorage(e.entryType)) {
            JournalEntryType.INSULIN -> ClKind.BOLUS
            JournalEntryType.CARBS -> ClKind.MEAL
            else -> return null
        }
        return JournalRow(e.id, kind, e.timestamp, e.amount?.toDouble(), e.sourceRecordId)
    }

    /** Back to the Room loaders (tests). */
    internal fun resetForTest() {
        loadRows = { c, from, to -> withContext(Dispatchers.IO) { dao(c).getEntriesBetween(from, to).mapNotNull(::row) } }
        loadCloudRowTimes = { c, from, to -> cloudRowTimes(c, from, to) }
        loadByKeys = { c, keys ->
            withContext(Dispatchers.IO) {
                val dao = dao(c)
                keys.distinct().chunked(500).flatMap { dao.getEntriesBySourceRecordIds(it) }.mapNotNull(::row)
            }
        }
    }

    /**
     * Keys of boluses and meals in the cloud's form: `INSULIN|yyyy-MM-ddTHH:mm:ss|n`, `MEAL|…|n` (the time as the
     * first 19 characters of a cloud marker); n - the number among events of the same kind in the same second, by
     * record number. Other events have no key.
     */
    fun eventKeys(events: List<PumpEvent>): Map<PumpEvent, String> {
        val out = LinkedHashMap<PumpEvent, String>()
        val sameSecond = HashMap<String, Int>()
        for (e in events.sortedBy { it.sequence }) {
            val type = when (e) {
                is BolusEvent -> CareLinkMarkers.TYPE_INSULIN
                is MealEvent -> CareLinkMarkers.TYPE_MEAL
                else -> continue
            }
            val base = "$type|${KEY_TIME.format(e.at)}"
            val n = sameSecond.getOrDefault(base, 0)
            sameSecond[base] = n + 1
            out[e] = "$base|$n"
        }
        return out
    }

    /**
     * [e] as the writers' model under [key] (from [eventKeys], or the cloud key of [PumpHistoryMemory.aliases]); the
     * moment - the pump's local time in [zone]. A bolus: doses rounded as CareLinkMarkers' sums; running - not
     * completed, the programmed dose; type FAST for a normal one and for an undetermined one without an extended part,
     * else EXTENDED / DUAL (the writer adds a note). A meal: its grams. Other events - null.
     */
    fun toClEvent(e: PumpEvent, key: String, zone: ZoneId): ClEvent? {
        val at = e.at.atZone(zone).toInstant().toEpochMilli()
        return when (e) {
            is BolusEvent -> ClEvent(
                kind = ClKind.BOLUS,
                key = key,
                atMs = at,
                programmedUnits = e.programmedUnits?.let(CareLinkMarkers::round3),
                deliveredUnits = if (e.completed) e.deliveredUnits?.let(CareLinkMarkers::round3) else null,
                completed = e.completed,
                bolusType = bolusTypeName(e),
            )
            is MealEvent -> ClEvent(ClKind.MEAL, key, at, grams = e.grams)
            else -> null
        }
    }

    const val TYPE_EXTENDED = "EXTENDED"
    const val TYPE_DUAL = "DUAL"

    private fun bolusTypeName(e: BolusEvent): String {
        when (e.bolusType) {
            0x33 -> return ClEvent.BOLUS_FAST
            0x3C -> return TYPE_EXTENDED
            0xFF -> return TYPE_DUAL
        }
        // undetermined (0x0F) or unknown: by the parts
        val total = e.programmedUnits ?: e.deliveredUnits ?: 0.0
        val ext = e.programmedExtendedUnits ?: e.deliveredExtendedUnits ?: 0.0
        return when {
            ext <= CareLinkMarkers.EPS -> ClEvent.BOLUS_FAST
            total - ext > CareLinkMarkers.EPS -> TYPE_DUAL
            else -> TYPE_EXTENDED
        }
    }

    /**
     * The snapshot of [cycle] (null - no pump hash): data and active insulin at the read time; no reservoir percent;
     * a temporary basal is absolute (U/h); stopped or paused delivery - suspended; basal known - its answer came.
     */
    fun toPumpNow(cycle: PumpCycle): PumpNow? {
        if (cycle.serialHash == null) return null
        val iob = cycle.iobUnits?.takeIf { it >= 0.0 }?.let(CareLinkMarkers::round3)
        val temp = cycle.tempBasalRate?.takeIf { it >= 0.0 }?.let {
            TempBasal(CareLinkMarkers.round3(it), percent = null, minutesLeft = cycle.tempBasalMinutesLeft?.takeIf { m -> m >= 0 }, absolute = true)
        }
        return PumpNow(
            dataAtMs = cycle.readAtMs,
            iobUnits = iob,
            iobAtMs = if (iob != null) cycle.readAtMs else null,
            reservoirUnits = cycle.reservoirUnits?.takeIf { it >= 0.0 }?.let(CareLinkMarkers::round3),
            reservoirPercent = null,
            batteryPercent = cycle.batteryPercent?.takeIf { it in 0..100 },
            basalRate = cycle.basalRate?.takeIf { it >= 0.0 }?.let(CareLinkMarkers::round3),
            tempBasal = temp,
            suspended = when (cycle.therapyState) {
                THERAPY_STOP, THERAPY_PAUSE -> true
                THERAPY_RUN -> false
                else -> null
            },
            basalKnown = cycle.basalRead,
        )
    }

    const val THERAPY_STOP = 0x33
    const val THERAPY_PAUSE = 0x3C
    const val THERAPY_RUN = 0x55

    /**
     * The boundary of the old (decision 5: up to where the cloud delivered data): the latest journal row of any type
     * with a `carelink:` key (bolus, carbs, basal, stop note) within 10 days before
     * [enabledAtMs], or the cloud's last data time (`lastDataAtMs`), whichever is later, minus 10 min; neither -
     * [enabledAtMs] minus 24 h. Never later than [enabledAtMs]. A failed read counts as no rows.
     */
    suspend fun boundary(context: Context, enabledAtMs: Long): Long {
        val app = context.applicationContext
        val lastRow = try {
            loadCloudRowTimes(app, enabledAtMs - BOUNDARY_LOOKBACK_MS, enabledAtMs)
                .filter { it <= enabledAtMs }
                .maxOrNull()
        } catch (t: Throwable) {
            PumpBtLog.w("boundary: journal not read (${t.javaClass.simpleName})")
            null
        }
        val lastData = try {
            withContext(Dispatchers.IO) { CareLinkStateStore(app).read().lastDataAtMs }
        } catch (t: Throwable) {
            PumpBtLog.w("boundary: cloud state not read (${t.javaClass.simpleName})")
            null
        }
        val latest = listOfNotNull(lastRow, lastData).filter { it <= enabledAtMs }.maxOrNull()
            ?: return enabledAtMs - DAY_MS
        return minOf(latest - BOUNDARY_MARGIN_MS, enabledAtMs)
    }

    /**
     * The journal rows [planCycle] needs for [events]: those in the time span of the events (with the matching window)
     * and those under the events' keys (own or aliased, as journal sourceRecordIds). An addition to the contract for
     * the source: one call instead of two.
     */
    suspend fun journalRows(context: Context, m: PumpHistoryMemory, events: PumpEventsResult, zone: ZoneId): List<JournalRow> {
        val items = items(m, events, zone)
        if (items.isEmpty()) return emptyList()
        val app = context.applicationContext
        val inSpan = loadRows(app, items.minOf { it.atMs } - MATCH_WINDOW_MS, items.maxOf { it.endMs } + MATCH_WINDOW_MS)
        val keys = items.mapNotNull { journalKey(it.kind, m.aliases[it.key] ?: it.key) }.distinct()
        val byKey = if (keys.isEmpty()) emptyList() else loadByKeys(app, keys)
        return (inSpan + byKey).distinctBy { it.id }
    }

    /** The writer's sourceRecordId of a ClEvent key: `carelink:INSULIN:…:n:insulin` / `carelink:MEAL:…:n:carbs`. */
    fun journalKey(kind: ClKind, key: String): String? = when (kind) {
        ClKind.BOLUS -> CARELINK + key.replace('|', ':') + ":" + CareLinkJournalWriter.KIND_INSULIN
        ClKind.MEAL -> CARELINK + key.replace('|', ':') + ":" + CareLinkJournalWriter.KIND_CARBS
        else -> null
    }

    /**
     * The ClEvent key of a cloud row's sourceRecordId of [kind] (`carelink:INSULIN:2026-01-10T08:00:03:0:insulin` ->
     * `INSULIN|2026-01-10T08:00:03|0`); not a writer key of that kind - null.
     */
    fun cloudKey(sourceRecordId: String?, kind: ClKind): String? {
        val s = sourceRecordId ?: return null
        val (type, suffix) = when (kind) {
            ClKind.BOLUS -> CareLinkMarkers.TYPE_INSULIN to CareLinkJournalWriter.KIND_INSULIN
            ClKind.MEAL -> CareLinkMarkers.TYPE_MEAL to CareLinkJournalWriter.KIND_CARBS
            else -> return null
        }
        val head = "$CARELINK$type:"
        val tail = ":$suffix"
        if (!s.startsWith(head) || !s.endsWith(tail)) return null
        val mid = s.substring(head.length, s.length - tail.length)
        val cut = mid.lastIndexOf(':')
        if (cut <= 0) return null
        val time = mid.substring(0, cut)
        val n = mid.substring(cut + 1)
        if (time.length != 19 || n.isEmpty() || !n.all(Char::isDigit)) return null
        return "$type|$time|$n"
    }

    /** A keyed event: its moment, the end of its matching window, its doses or grams. */
    private class Item(
        val event: PumpEvent, val key: String, val kind: ClKind, val atMs: Long, val endMs: Long,
        val programmed: Double?, val delivered: Double?, val grams: Double?, val completed: Boolean,
        val durationMinutes: Int?,
    ) {
        val dose: Double? get() = if (!completed) programmed ?: delivered else delivered ?: programmed
    }

    private fun items(m: PumpHistoryMemory, events: PumpEventsResult, zone: ZoneId): List<Item> {
        fun ms(e: java.time.LocalDateTime) = e.atZone(zone).toInstant().toEpochMilli()
        return eventKeys(events.events).map { (e, key) ->
            when (e) {
                is BolusEvent -> {
                    val at = ms(e.at)
                    Item(e, key, ClKind.BOLUS, at, maxOf(at, e.deliveredAt?.let(::ms) ?: at),
                        e.programmedUnits, if (e.completed) e.deliveredUnits else null, null, e.completed, e.durationMinutes)
                }
                is MealEvent -> {
                    val at = ms(e.at)
                    Item(e, key, ClKind.MEAL, at, at, null, null, e.grams, true, null)
                }
                else -> error("no key for this event")
            }
        }.sortedWith(compareBy({ it.atMs }, { it.event.sequence }))
    }

    private class Matched(
        val aliases: Map<String, String>, val byKey: Int, val byTime: Int, val toWrite: List<Item>,
        val oldNotFound: Int, val boluses: Int, val meals: Int,
    )

    /**
     * Decision 5 for the old events (before [enabledAt], not accepted): first every event takes the row under its own
     * key (or its alias), then each old one left - by time - the nearest free row of its kind in its window with the
     * same dose or grams; a row is taken once. Cloud row - an alias; another row - not written. The rest: later than
     * [boundary] - to write, else old not found.
     */
    private fun match(m: PumpHistoryMemory, items: List<Item>, rows: List<JournalRow>, enabledAt: Long, boundary: Long): Matched {
        val old = items.filter { it.atMs < enabledAt && (m.aliases[it.key] ?: it.key) !in m.acceptedAt }
        val oldKeys = old.mapTo(HashSet()) { it.key }
        val byJournalKey = HashMap<String, JournalRow>()
        for (r in rows) r.sourceRecordId?.let { byJournalKey.putIfAbsent(it, r) }
        val taken = HashSet<Long>()
        val byKey = HashSet<String>()
        for (it in items) {
            val row = journalKey(it.kind, m.aliases[it.key] ?: it.key)?.let(byJournalKey::get) ?: continue
            if (!taken.add(row.id)) continue
            if (it.key in oldKeys) byKey += it.key
        }
        val aliases = LinkedHashMap<String, String>()
        val toWrite = ArrayList<Item>()
        var byTime = 0
        var oldNotFound = 0
        for (it in old) {
            if (it.key in byKey) continue
            val row = rows.asSequence()
                .filter { r -> r.id !in taken && r.kind == it.kind && r.atMs >= it.atMs - MATCH_WINDOW_MS && r.atMs <= it.endMs + MATCH_WINDOW_MS && sameAmount(r, it) }
                .minWithOrNull(compareBy<JournalRow>({ r -> abs(r.atMs - it.atMs) }, { r -> r.id }))
            if (row != null) {
                taken += row.id
                byTime++
                cloudKey(row.sourceRecordId, it.kind)?.let { ck -> aliases[it.key] = ck }
                continue
            }
            if (it.atMs > boundary) toWrite += it else oldNotFound++
        }
        return Matched(
            aliases, byKey.size, byTime, toWrite, oldNotFound,
            boluses = old.count { it.kind == ClKind.BOLUS }, meals = old.count { it.kind == ClKind.MEAL },
        )
    }

    private fun sameAmount(r: JournalRow, it: Item): Boolean {
        val a = r.amount ?: return false
        fun eq(v: Double?) = v != null && abs(a - v) <= CareLinkMarkers.EPS
        return if (it.kind == ClKind.BOLUS) eq(it.programmed) || eq(it.delivered) else eq(it.grams)
    }

    private fun card(m: Matched, toWrite: List<Item>): BackfillCard = BackfillCard(
        bolusesBefore = m.boluses,
        mealsBefore = m.meals,
        alreadyInJournal = m.byKey + m.byTime,
        toWrite = toWrite.map { BackfillItem(it.key, it.atMs, if (it.kind == ClKind.BOLUS) it.dose else null, it.grams) },
        oldNotFound = m.oldNotFound,
    )

    /**
     * One cycle (decisions 5, 6, 9): the first catch-up (once [firstReadComplete] - the history read through - the card
     * is made and frozen; nothing old to decide - confirmed at once), what to feed (see the class notes), the warnings
     * and counters. [events] - built from the whole memory [m]; [rows] - from [journalRows]; [nowMs] - the cycle's read
     * time.
     */
    fun planCycle(
        m: PumpHistoryMemory, events: PumpEventsResult, rows: List<JournalRow>, nowMs: Long,
        zone: ZoneId, firstReadComplete: Boolean,
    ): CyclePlan {
        var mem = m.copy(reference = events.lastReference ?: m.reference)
        val items = items(mem, events, zone)
        val enabledAt = mem.enabledAtMs
        var card: BackfillCard? = null
        var matchedByKey = 0
        var matchedByTime = 0
        var fresh = 0
        if (enabledAt != null) {
            val boundary = mem.boundaryMs ?: (enabledAt - DAY_MS)
            if (mem.backfill == BackfillState.NOT_READY && firstReadComplete) {
                val r = match(mem, items, rows, enabledAt, boundary)
                val anyOld = r.boluses + r.meals > 0
                mem = mem.copy(
                    aliases = mem.aliases + r.aliases,
                    backfillShown = r.toWrite.map { it.key },
                    backfill = if (anyOld) BackfillState.WAITING else BackfillState.CONFIRMED,
                )
                matchedByKey = r.byKey
                matchedByTime = r.byTime
                fresh = r.toWrite.size
                if (anyOld) card = card(r, r.toWrite)
            } else if (mem.backfill == BackfillState.WAITING) {
                // the list stays as shown; the counts are read again
                val r = match(mem, items, rows, enabledAt, boundary)
                val byKey = items.associateBy { it.key }
                card = card(r, mem.backfillShown.mapNotNull { byKey[it] })
            }
        }

        val byJournalKey = HashMap<String, JournalRow>()
        for (r in rows) r.sourceRecordId?.let { byJournalKey.putIfAbsent(it, r) }
        val accepted = LinkedHashMap(mem.acceptedAt)
        val toWriter = ArrayList<ClEvent>()
        val shown = mem.backfillShown.toHashSet()
        var future = 0
        for (it in items) {
            val ek = mem.aliases[it.key] ?: it.key
            val ce = toClEvent(it.event, ek, zone) ?: continue
            if (ce.atMs > nowMs + FUTURE_MS) {
                future++
                continue
            }
            val isAccepted = ek in accepted
            val age = nowMs - ce.atMs
            val hasRow = journalKey(it.kind, ek)?.let(byJournalKey::containsKey) == true
            if (!isAccepted && age > OLD_ROW_MS && (hasRow || (it.kind == ClKind.BOLUS && !it.completed))) {
                // the writer would adopt the row and write over the person's edit; a bolus running for two days is not
                // waited for any more
                accepted[ek] = nowMs
                continue
            }
            if (enabledAt == null) continue
            val feed = if (ce.atMs >= enabledAt) {
                age <= DAY_MS || !isAccepted
            } else {
                !isAccepted && when (mem.backfill) {
                    BackfillState.CONFIRMED -> it.key in shown || (it.kind == ClKind.BOLUS && hasRow)
                    BackfillState.DECLINED -> it.kind == ClKind.BOLUS && hasRow
                    else -> false
                }
            }
            if (feed) toWriter += ce
        }
        mem = mem.copy(acceptedAt = accepted)

        // warnings and counters
        val warnings = LinkedHashSet<PumpWarning>()
        val boluses = items.filter { it.kind == ClKind.BOLUS }
        val noResult = boluses.count { !it.completed && nowMs - it.atMs > (it.durationMinutes ?: 0) * 60_000L + NO_RESULT_MS }
        val unpaired = items.count { meal ->
            meal.kind == ClKind.MEAL && boluses.none { b -> meal.atMs in (b.atMs - CareLinkJournalWriter.PAIR_WINDOW_MS)..b.atMs }
        }
        if (future > 0) warnings += PumpWarning.FUTURE_EVENT
        if (noResult > 0) warnings += PumpWarning.NO_RESULT_BOLUS
        if (unpaired > 0) warnings += PumpWarning.UNPAIRED_MEAL
        if (events.heldBoluses > 0) warnings += PumpWarning.HELD_BOLUS
        val ref = mem.reference
        if (firstReadComplete && ref != null) {
            val drift = nowMs - ref.time.atZone(zone).toInstant().toEpochMilli()
            if (drift < 0 || drift > DRIFT_MAX_MS) warnings += PumpWarning.CLOCK_DRIFT
        }
        mem = mem.copy(
            counters = mem.counters.copy(
                unparsed = events.unparsed.size,
                unpairedMeals = unpaired,
                noResultBoluses = noResult,
                futureEvents = future,
                heldBoluses = events.heldBoluses,
                undated = events.undated.size,
            ),
        )
        return CyclePlan(toWriter, card, mem, warnings, matchedByKey, matchedByTime, fresh)
    }

    /**
     * "Write": confirms exactly [shownKeys] - the list the person saw. Another list (the card changed meanwhile) or no
     * card waiting - null (show it again). Pressed twice on the same list - the memory as it is.
     */
    fun confirmBackfill(m: PumpHistoryMemory, shownKeys: List<String>): PumpHistoryMemory? {
        val same = shownKeys.size == m.backfillShown.size && shownKeys.toSet() == m.backfillShown.toSet()
        if (!same) return null
        return when (m.backfill) {
            BackfillState.WAITING -> m.copy(backfill = BackfillState.CONFIRMED)
            BackfillState.CONFIRMED -> m
            else -> null
        }
    }

    /** "Do not write the old": the boundary becomes the enable moment (not the moment of the press); nothing old is written. */
    fun declineBackfill(m: PumpHistoryMemory): PumpHistoryMemory =
        m.copy(backfill = BackfillState.DECLINED, boundaryMs = m.enabledAtMs ?: m.boundaryMs, backfillShown = emptyList())
}
