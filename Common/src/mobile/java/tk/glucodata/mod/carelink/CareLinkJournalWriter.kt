package tk.glucodata.mod.carelink

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.data.journal.JournalRepository
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.abs

/**
 * JugglucoNGmod, CareLink (stage 4d): pump boluses and calculator carbs into upstream's journal, each once (plan,
 * decisions 3-6 and "Technique").
 *
 * - Key `carelink:<type>:<pump time>:<n>:insulin|carbs` (the marker key, no pump serial). Upstream's upsert finds the
 *   row by it, so a repeated answer never makes a second row.
 * - Bolus: running - the programmed dose and the note "in progress"; finished - the delivered dose, the same row.
 *   Finished with 0 delivered (cancelled) - the row and its carbs are deleted with `deleteEntry` (it queues the
 *   deletion for Nightscout; deleting by key does not). Square and dual wave - one row with the sum and a note.
 * - Calculator carbs: a MEAL marker up to 10 s before the bolus -> a `carbs` row (only with "record calculator carbs"
 *   on; no bolus - nothing; the absorption time is upstream's default). Meter readings are not written.
 * - A row the person changed (`updatedAt` later than our last write and a content that is not ours) or deleted is
 *   never written over or brought back ([CareLinkSeenStore]); nor are the carbs of such a bolus touched.
 * - An older cloud answer does not reach this writer (the poller); still, a finished bolus never goes back to
 *   "running", and a moved key keeps a trace so its old key cannot come back as a second bolus (critic 4d, B1, C1).
 * - Source of a new row: Health Connect when "send pump records to Nightscout" is on, else API (a mirror source:
 *   upstream sends it nowhere). A row keeps the source it was made with.
 * - A running bolus whose marker time moved by up to 60 s (its old key gone from the answer, the same programmed dose)
 *   keeps its row under the new key (upstream's `retagSourceRecordId`), as Glucove's rekey.
 * - A row found by its key keeps its time (a moved running bolus moves it as far as its marker moved in pump time):
 *   the moment of a marker is counted with the phone's offset of each answer, which a zone change moves (critic of the
 *   joints 4e, C2); the move is measured by the pump times of the keys, which no offset changes (critic 4e, C1).
 *
 * The whole answer (24 h) is written every poll: the record of what was written makes it cheap, and no poll can lose
 * a bolus (critic 4c, C1). A row found by key but not in the record (the record file was lost, or the write ended
 * before the record was saved) is taken as ours as it stands. A failure throws: the poll then reports a write error
 * and the next poll writes again.
 */
object CareLinkJournalWriter {

    /** Calculator carbs belong to a bolus when their marker is at most this much earlier (Glucove: 1-3 s). */
    const val PAIR_WINDOW_MS = 10_000L

    /** A running bolus whose marker moved by at most this much keeps its row (Glucove's rekey). */
    const val REKEY_MS = 60_000L

    /** The journal calls; tests put one that fails on a key or once. */
    @Volatile
    internal var journal: (Context) -> CareLinkJournal = { CareLinkJournal(it) }

    const val KIND_INSULIN = "insulin"
    const val KIND_CARBS = "carbs"

    fun insulinKey(e: ClEvent): String = "carelink:" + e.key.replace('|', ':') + ":" + KIND_INSULIN

    fun carbsKey(meal: ClEvent): String = "carelink:" + meal.key.replace('|', ':') + ":" + KIND_CARBS

    /**
     * Writes [events] of one answer. [firstPoll] - the first poll after a sign-in; every poll takes the whole answer,
     * so it changes nothing here (kept in the contract for the log). A failure throws.
     */
    suspend fun apply(context: Context, events: List<ClEvent>, firstPoll: Boolean) {
        val boluses = events.filter { it.kind == ClKind.BOLUS && it.dose != null }
        if (boluses.isEmpty()) return
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val now = CareLinkAuth.clock()
            val seen = CareLinkSeenStore.load(app, now)
            val run = Run(app, seen, journal(app))
            try {
                run.write(boluses, events)
            } finally {
                CareLinkSeenStore.save(app, seen, now)
                // diagnostics for upstream's debug log (4e): numbers only - no doses, times or keys
                CareLinkLog.i("journal: boluses=${boluses.size} ${run.stats}")
            }
        }
    }

    /**
     * What one answer did to the journal: rows new / updated / deleted, left as the person's (changed or deleted by
     * them), kept from an older answer (a finished bolus shown as running), failed. Numbers only.
     */
    internal class JournalStats {
        var created = 0
        var updated = 0
        var deleted = 0
        var persons = 0
        var older = 0
        var failed = 0

        override fun toString(): String =
            "new=$created updated=$updated deleted=$deleted persons=$persons older=$older failed=$failed"
    }

    private class Run(val app: Context, val seen: SeenData, val j: CareLinkJournal) {
        private val settings = CareLinkSettings(app)
        private var preset: Long? = null
        private var presetRead = false

        private suspend fun presetId(): Long? {
            if (!presetRead) {
                preset = CareLinkInsulin.presetId(app)
                presetRead = true
            }
            return preset
        }

        private fun newSource(): JournalEntrySource =
            if (settings.sendToNightscout) JournalEntrySource.HEALTH_CONNECT else JournalEntrySource.API

        /**
         * The source to write: a new row - by the switch; an existing one - the source it was made with (the record's,
         * else the row's). Upstream's editor turns a Health Connect row into MANUAL without its key; our next write puts
         * both back (their upsert keeps the stored source only for mirror rows).
         */
        private fun sourceOf(row: JournalEntryEntity?, rec: SeenRecord?): JournalEntrySource =
            if (row == null) newSource() else JournalEntrySource.fromStorage(rec?.source ?: row.source)

        /** Counts of this answer for the log line (numbers only). */
        val stats = JournalStats()

        /**
         * Each bolus (with its carbs) on its own: one row upstream refuses must not stop the later boluses of the answer
         * - the cloud keeps that row for 24 h (critic 4d2, C1). The first failure is thrown after the loop (the poll
         * reports a write error); cancellation is not swallowed.
         */
        suspend fun write(boluses: List<ClEvent>, events: List<ClEvent>) {
            val keysNow = boluses.map { CareLinkJournalWriter.insulinKey(it) }.toSet()
            val meals = events.filter { it.kind == ClKind.MEAL && (it.grams ?: 0.0) > 0.0 }
            val pairedMeals = HashSet<String>()
            var failure: Throwable? = null
            for (b in boluses.sortedWith(compareBy({ it.atMs }, { it.key }))) {
                try {
                    one(b, keysNow, meals, pairedMeals)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    stats.failed++
                    if (failure == null) failure = t
                }
            }
            failure?.let { throw it }
        }

        private suspend fun one(b: ClEvent, keysNow: Set<String>, meals: List<ClEvent>, pairedMeals: HashSet<String>) {
            val key = CareLinkJournalWriter.insulinKey(b)
            val rec = seen.records[key] ?: rekey(b, key, keysNow)
            val window = (b.atMs - CareLinkJournalWriter.PAIR_WINDOW_MS)..b.atMs
            val meal = meals.filter { it.key !in pairedMeals && it.atMs in window }.maxByOrNull { it.atMs }
            if (meal != null) pairedMeals += meal.key
            val mealKey = meal?.let { CareLinkJournalWriter.carbsKey(it) }
            val cancelled = b.completed == true && (b.dose ?: 0.0) <= CareLinkMarkers.EPS
            // the person's bolus (changed or deleted) or one done with: its carbs are left alone too (critic 4d, N3)
            if (!bolus(b, key, rec, cancelled, mealKey)) return
            if (meal != null && mealKey != null) {
                carbs(meal, mealKey, cancelled)
            } else if (cancelled) {
                // the carbs of a cancelled bolus go even when their marker has left the answer
                seen.records[key]?.carbsKey?.let { removeCarbs(it) }
            }
        }

        /**
         * The row of [key] as the record knows it: null record - found by key and adopted, or nothing; a row gone -
         * deleted by the person; a later `updatedAt` with a content that is not ours ([ours]: as we wrote it last or as
         * we are about to write it) - changed by the person. By content, not by `updatedAt` alone: a write of ours whose
         * record was not saved (the work cancelled or the process killed in between), or upstream's editor saving the
         * row unchanged, is still ours (critic of the joints, C1; critic 4d, N1). Not free - the row is the person's now
         * or done with; free with [Found.row] null - not written yet.
         */
        private suspend fun find(
            key: String,
            rec: SeenRecord?,
            ours: (JournalEntryEntity) -> Boolean,
            adopt: (JournalEntryEntity) -> SeenRecord,
        ): Found {
            if (rec != null && rec.state != SeenState.LIVE) {
                if (rec.state == SeenState.EDITED || rec.state == SeenState.DELETED) stats.persons++
                return Found(null, rec, free = false)
            }
            if (rec == null) {
                val row = j.byKey(key) ?: return Found(null, null, free = true)
                val adopted = adopt(row)
                seen.records[key] = adopted
                return Found(row, adopted, free = true)
            }
            val row = j.row(rec.rowId, key)
            if (row == null) {
                if (rec.rowId == null) return Found(null, rec, free = true)
                seen.records[key] = rec.copy(state = SeenState.DELETED)
                stats.persons++
                return Found(null, rec, free = false)
            }
            if (rec.writtenAt != null && row.updatedAt > rec.writtenAt && !ours(row)) {
                seen.records[key] = rec.copy(state = SeenState.EDITED)
                stats.persons++
                return Found(null, rec, free = false)
            }
            return Found(row, rec, free = true)
        }

        private class Found(val row: JournalEntryEntity?, val rec: SeenRecord?, val free: Boolean)

        /**
         * The row holds the bolus as [rec] recorded it, or as ([dose], [note]) is about to write it - the time of a row
         * found by key is not written again (see [rekeyed]), so it is not compared here.
         */
        private fun bolusLooksOurs(row: JournalEntryEntity, rec: SeenRecord?, dose: Double?, note: String?) =
            (rec != null && row.timestamp == rec.atMs && same(row.amount, rec.dose) && row.note == rec.note) ||
                (dose != null && same(row.amount, dose) && row.note == note)

        /** The row holds the carbs as [rec] recorded them, or as [grams] is about to write them (the time - as above). */
        private fun carbsLooksOurs(row: JournalEntryEntity, rec: SeenRecord?, grams: Double?) =
            (rec != null && row.timestamp == rec.atMs && same(row.amount, rec.grams)) ||
                (grams != null && same(row.amount, grams))

        /**
         * Keys whose row came from a moved running bolus in this answer ([rekey]): their time follows the marker. Any
         * other row found by key keeps its time (critic of the joints 4e, C2): the marker key is the pump's local time,
         * but its moment is counted with the phone's offset of this answer - after a zone change (a flight, summer
         * time) every marker of the 24 h would move, the rows would jump and go to Nightscout again.
         */
        private val rekeyed = HashMap<String, Long>()

        /**
         * The time to write for [key]: a moved running bolus - its row's time moved as far as the marker moved in pump
         * time ([rekey]); a row found by key keeps its own; a new row - the marker's.
         */
        private fun timeOf(key: String, row: JournalEntryEntity?, markerAtMs: Long): Long =
            rekeyed[key] ?: row?.timestamp ?: markerAtMs

        private fun same(a: Float?, b: Double?): Boolean = a != null && b != null && abs(a - b) <= CareLinkMarkers.EPS

        /**
         * true - the bolus is ours (written, unchanged, or removed by this very answer); false - the person's now, or
         * done with (removed before, its key moved): nothing more is written for it.
         */
        private suspend fun bolus(b: ClEvent, key: String, recIn: SeenRecord?, cancelled: Boolean, mealKey: String?): Boolean {
            val dose = if (cancelled) null else b.dose
            val note = note(b)
            val f = find(key, recIn, { row -> bolusLooksOurs(row, recIn, dose, note) }) { row ->
                // the record was lost: a row of a running bolus carries the running note - one without it was written
                // finished, and an older answer must not take it back to running (critic 4d2, N8)
                val finishedRow = b.completed == false && row.note != note
                SeenRecord(
                    CareLinkJournalWriter.KIND_INSULIN, row.timestamp, row.id, row.updatedAt, row.source,
                    dose = row.amount?.toDouble(), completed = if (finishedRow) true else b.completed, note = row.note,
                    carbsKey = mealKey,
                )
            }
            if (!f.free) return false
            val row = f.row
            val rec = f.rec
            if (cancelled) {
                row?.let {
                    j.delete(it.id)
                    stats.deleted++
                }
                seen.records[key] = (rec ?: SeenRecord(CareLinkJournalWriter.KIND_INSULIN, b.atMs)).copy(
                    rowId = row?.id, dose = 0.0, completed = true, carbsKey = rec?.carbsKey ?: mealKey,
                    state = SeenState.REMOVED,
                )
                return true
            }
            if (dose == null) return true
            // an older answer showing a finished bolus as running again: the finished one stands (critic 4d, C1)
            if (rec?.completed == true && b.completed == false) {
                stats.older++
                return true
            }
            val carbsKey = rec?.carbsKey ?: mealKey
            val at = timeOf(key, row, b.atMs)
            // the row already says it (a repeated answer, or our write whose record was lost): no write - it would send
            // the row to Nightscout again; the record catches up
            if (row != null && row.sourceRecordId == key && row.timestamp == at && same(row.amount, dose) &&
                row.note == note
            ) {
                seen.records[key] = SeenRecord(
                    CareLinkJournalWriter.KIND_INSULIN, at, row.id, row.updatedAt, rec?.source ?: row.source,
                    dose = dose, completed = b.completed, note = note, carbsKey = carbsKey,
                )
                return true
            }
            val written = j.upsert(
                JournalEntryInput(
                    id = row?.id,
                    timestamp = at,
                    type = JournalEntryType.INSULIN,
                    title = app.getString(R.string.mod_carelink_journal_bolus),
                    note = note,
                    amount = dose.toFloat(),
                    insulinPresetId = if (row != null) row.insulinPresetId else presetId(),
                    source = sourceOf(row, rec),
                    sourceRecordId = key,
                )
            )
            if (row == null) stats.created++ else stats.updated++
            seen.records[key] = SeenRecord(
                CareLinkJournalWriter.KIND_INSULIN, at, written.id, written.updatedAt, written.source, dose = dose,
                completed = b.completed, note = note, carbsKey = carbsKey,
            )
            return true
        }

        private suspend fun carbs(meal: ClEvent, key: String, cancelled: Boolean) {
            val grams = meal.grams ?: return
            val recIn = seen.records[key]
            val ours = { row: JournalEntryEntity -> carbsLooksOurs(row, recIn, if (cancelled) null else grams) }
            val f = find(key, recIn, ours) { row ->
                SeenRecord(
                    CareLinkJournalWriter.KIND_CARBS, row.timestamp, row.id, row.updatedAt, row.source,
                    grams = row.amount?.toDouble(),
                )
            }
            if (!f.free) return
            val row = f.row
            val rec = f.rec
            if (cancelled) {
                removeFound(key, row, rec, meal.atMs)
                return
            }
            // new calculator carbs only with the switch on; rows already written stay ours either way
            if (row == null && !settings.writeCarbs) return
            val at = timeOf(key, row, meal.atMs)
            if (row != null && row.sourceRecordId == key && row.timestamp == at && same(row.amount, grams)) {
                seen.records[key] = SeenRecord(
                    CareLinkJournalWriter.KIND_CARBS, at, row.id, row.updatedAt, rec?.source ?: row.source,
                    grams = grams,
                )
                return
            }
            val written = j.upsert(
                JournalEntryInput(
                    id = row?.id,
                    timestamp = at,
                    type = JournalEntryType.CARBS,
                    title = app.getString(R.string.mod_carelink_journal_carbs),
                    amount = grams.toFloat(),
                    source = sourceOf(row, rec),
                    sourceRecordId = key,
                )
            )
            if (row == null) stats.created++ else stats.updated++
            seen.records[key] = SeenRecord(
                CareLinkJournalWriter.KIND_CARBS, at, written.id, written.updatedAt, written.source, grams = grams,
            )
        }

        private suspend fun removeCarbs(key: String) {
            val rec = seen.records[key] ?: return
            val f = find(key, rec, { row -> carbsLooksOurs(row, rec, null) }) { r -> rec.copy(rowId = r.id) }
            if (!f.free) return
            removeFound(key, f.row, f.rec, rec.atMs)
        }

        private suspend fun removeFound(key: String, row: JournalEntryEntity?, rec: SeenRecord?, atMs: Long) {
            row?.let {
                j.delete(it.id)
                stats.deleted++
            }
            seen.records[key] = (rec ?: SeenRecord(CareLinkJournalWriter.KIND_CARBS, atMs))
                .copy(rowId = row?.id, state = SeenState.REMOVED)
        }

        /**
         * The running bolus [b] has no record under [key]: a running one of ours within
         * [CareLinkJournalWriter.REKEY_MS] with the same programmed dose, whose key is gone from the answer, is the same
         * bolus with a moved marker - its row takes the new key, edits and all. The dose must match: a second bolus a
         * few seconds away must not take the first one's row (critic 4d, N4). The old key keeps a trace (MOVED): an
         * older answer bringing it back finds it taken instead of writing a second bolus (critic 4d, B1).
         * The distance is in pump time, read from the two keys: the record's time is its row's, counted with the offset
         * of an earlier answer, and a zone change in between would put it hours from the marker (critic 4e, C1).
         */
        private suspend fun rekey(b: ClEvent, key: String, keysNow: Set<String>): SeenRecord? {
            val programmed = b.programmedUnits ?: b.dose ?: return null
            val old = seen.records.entries
                .filter { (k, r) ->
                    k != key && k !in keysNow && r.kind == CareLinkJournalWriter.KIND_INSULIN && r.completed == false &&
                        r.state != SeenState.MOVED && abs(moveMs(k, r, key, b)) <= CareLinkJournalWriter.REKEY_MS &&
                        r.dose?.let { abs(it - programmed) <= CareLinkMarkers.EPS } == true
                }
                .minByOrNull { abs(moveMs(it.key, it.value, key, b)) } ?: return null
            if (j.byKey(key) != null) return null
            val rec = old.value
            // the old key becomes a trace (MOVED) only once the row has moved: a database failure before that leaves the
            // record as it was, and the next poll tries the move again instead of writing a second bolus (critic 4d2, C2)
            val trace = rec.copy(rowId = null, carbsKey = null, state = SeenState.MOVED)
            val row = rec.rowId?.let { j.byId(it) }
            val moved = when {
                rec.state != SeenState.LIVE -> rec
                row == null -> rec.copy(state = SeenState.DELETED)
                else -> {
                    val edited = rec.writtenAt != null && row.updatedAt > rec.writtenAt &&
                        !bolusLooksOurs(row, rec, b.dose, note(b))
                    if (!j.retag(row.id, key)) return null
                    seen.records[old.key] = trace
                    // the row moves as far as the marker did: with no zone change - to the new marker's time; after
                    // one - its own time stays in the old offset, as all rows written before (critic 4e, C1)
                    rekeyed[key] = row.timestamp + moveMs(old.key, rec, key, b)
                    val after = j.byId(row.id)
                    if (edited) rec.copy(state = SeenState.EDITED) else rec.copy(writtenAt = after?.updatedAt ?: rec.writtenAt)
                }
            }
            seen.records[old.key] = trace
            seen.records[key] = moved
            return moved
        }

        /**
         * How far the marker moved from the record [oldKey]/[rec] to [newKey]/[b], ms: by the pump times in the keys
         * (no offset in them); a key without a readable time - by the moments.
         */
        private fun moveMs(oldKey: String, rec: SeenRecord, newKey: String, b: ClEvent): Long {
            val from = CareLinkJournalWriter.pumpSec(oldKey)
            val to = CareLinkJournalWriter.pumpSec(newKey)
            return if (from != null && to != null) (to - from) * 1_000L else b.atMs - rec.atMs
        }

        private fun note(b: ClEvent): String? {
            val parts = ArrayList<String>(2)
            if (b.extended) parts += app.getString(R.string.mod_carelink_note_extended, b.bolusType ?: "")
            if (b.completed == false) {
                val programmed = CareLinkJournalWriter.units(b.programmedUnits ?: b.dose ?: 0.0)
                parts += app.getString(R.string.mod_carelink_note_running, programmed)
            } else if (b.interrupted) {
                val delivered = CareLinkJournalWriter.units(b.deliveredUnits ?: 0.0)
                val programmed = CareLinkJournalWriter.units(b.programmedUnits ?: 0.0)
                parts += app.getString(R.string.mod_carelink_note_interrupted, delivered, programmed)
            }
            return parts.joinToString("; ").takeIf { it.isNotBlank() }
        }
    }

    internal fun units(v: Double): String = DecimalFormat("0.###").format(v)

    private val PUMP_TIME = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""")

    /** The pump's local time in a journal key, seconds on its own scale (no zone); none - null. */
    internal fun pumpSec(key: String): Long? = try {
        PUMP_TIME.find(key)?.value?.let { LocalDateTime.parse(it).toEpochSecond(ZoneOffset.UTC) }
    } catch (_: Exception) {
        null
    }
}

/**
 * JugglucoNGmod, CareLink (stage 4d): the few journal calls of the writers. Writes only through upstream's
 * `JournalRepository` (its side effects: IOB, Nightscout wake, the watch, the screen); reads a row by id or key
 * straight from their DAO (the repository has no such read).
 */
internal open class CareLinkJournal(app: Context) {
    private val repo = JournalRepository()
    private val dao = HistoryDatabase.getInstance(app.applicationContext).journalDao()

    suspend fun byId(id: Long): JournalEntryEntity? = dao.getEntryById(id)

    suspend fun byKey(key: String): JournalEntryEntity? = dao.getEntryBySourceRecordId(key)

    /** By the id the record holds, else by key (the person's editor may not keep the key). */
    suspend fun row(id: Long?, key: String): JournalEntryEntity? = id?.let { dao.getEntryById(it) } ?: byKey(key)

    /** Writes and reads the row back: its id and the `updatedAt` upstream stamped. */
    open suspend fun upsert(input: JournalEntryInput): JournalEntryEntity {
        repo.upsertEntry(input)
        val key = input.sourceRecordId ?: error("no key")
        return byKey(key) ?: error("row not found after write")
    }

    suspend fun delete(id: Long) = repo.deleteEntry(id)

    open suspend fun retag(id: Long, key: String): Boolean = repo.retagSourceRecordId(id, key)
}
