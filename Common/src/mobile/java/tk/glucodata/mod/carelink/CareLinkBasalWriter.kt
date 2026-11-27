package tk.glucodata.mod.carelink

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.glucodata.R
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntryInput
import tk.glucodata.data.journal.JournalEntrySource
import tk.glucodata.data.journal.JournalEntryType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * JugglucoNGmod, CareLink (stage 4d): the pump's actual basal rate as upstream's TEMP_BASAL segments (bars on the
 * chart, `Temp Basal` absolute in Nightscout) and stops as notes (plan, decision 1).
 *
 * Each snapshot gives the rate now: stopped - 0; absolute temporary - its rate (no rate - `basalRate`); a temporary
 * one of unknown type - its rate unless 0, else `basalRate` (critic 4d, C2); percentage temporary - the last normal
 * rate (remembered here, only while the data since it ran without a gap: critic 4d, C3) times the percent, no such
 * normal rate - a gap; otherwise `basal.basalRate`. A new rate is a new segment, the last one is closed at the change
 * (the boundary is the poll that saw it, up to ~7 min late). The running segment is written 30 min ahead and moved on
 * when less than 10 min are left, so its Nightscout copy changes about every 20 min, not every 5; upstream's chart cuts
 * a segment at the next one's start. A gap in the data (empty answer, no basal object, more than 30 min between
 * snapshots, a rate not known) closes the segment at its last data time: a gap, not a continuation. Duration at least 1
 * min and a finite rate: one refused record stops all of upstream's upload.
 *
 * Stop: upstream's chart draws no bar for 0 U/h, so a note "Pump stopped" with "Stop HH:MM–…" is written too (the chart chip shows a note's first 10 characters - "Stop HH:MM" fits); its end
 * is filled in when the pump runs again - or, when the data broke off meanwhile (more than 30 min, sign-out, "sign in
 * again"), with the last time it was seen stopped (critic of the joints, C2); a date when the end is another day
 * (critic 4d, N5). Unless the person changed the note.
 *
 * Times are the snapshot's data time (`dataAtMs`), never the poll time (critic 4c, C3). Keys:
 * `carelink:basal:<start ms>:0:basal`, `carelink:stop:<start ms>:0:note`. Source as for boluses: Health Connect with
 * "send pump records to Nightscout", else API; a row keeps its own.
 */
object CareLinkBasalWriter {

    /** The running segment is written this far ahead of its last data. */
    const val AHEAD_MS = 30 * 60_000L

    /** ... and moved on when less than this is left. */
    const val EXTEND_BEFORE_MS = 10 * 60_000L

    /** More than this between two snapshots is a gap. */
    const val GAP_MS = 30 * 60_000L

    /** Rates closer than this are the same (pump steps are 0.025 U/h). */
    const val SAME_RATE = 0.001

    @Volatile
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    fun basalKey(startMs: Long): String = "carelink:basal:$startMs:0:basal"

    fun stopKey(startMs: Long): String = "carelink:stop:$startMs:0:note"

    /**
     * [pumpNow] - the snapshot of this answer: null - the empty answer (a gap); `basalKnown == false` - no basal
     * object (a gap, unless the pump says it is stopped). [atMs] - the poll time (only for the 48 h record window).
     * A snapshot older than the state's is not passed at all (the poller). A failure throws.
     */
    suspend fun apply(context: Context, pumpNow: PumpNow?, atMs: Long) {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val seen = CareLinkSeenStore.load(app, atMs)
            val run = Run(app, seen, CareLinkJournal(app))
            try {
                run.step(pumpNow)
            } finally {
                CareLinkSeenStore.save(app, seen, atMs)
                // diagnostics for upstream's debug log (4e): numbers only - no rates or times
                CareLinkLog.i("basal: ${run.stats}")
            }
        }
    }

    /**
     * Bluetooth only (critic of the joints 1 fixes, P-N1): the pump runs again ([PumpNow.suspended] false) but the cycle
     * brought no basal answer, so [apply] is not called (one failed request is not a basal gap). With a stop note open,
     * the snapshot still goes the usual way: the note ends now, and the rate not being known closes the 0 U/h segment at
     * its last data; the next segment starts with the next basal answer - no rate is guessed. No stop note open - nothing
     * at all (the running segment stays as it is).
     */
    suspend fun endStop(context: Context, pumpNow: PumpNow, atMs: Long) {
        if (pumpNow.suspended != false || pumpNow.basalKnown) return
        val open = withContext(Dispatchers.IO) { CareLinkSeenStore.load(context.applicationContext, atMs).stopOpen }
        if (open != null) apply(context, pumpNow, atMs)
    }

    /**
     * The data broke off for good (sign-out, "sign in again"; critic of the joints, C2 and critic 4d, N5): the running
     * segment ends at its last data, the stop note at the last snapshot that saw the pump stopped, and the last normal
     * rate is forgotten (hours later or for another account it says nothing). The record of rows stays: the same account
     * later must not get back the rows the person deleted. A failure throws; the callers log it - the next sign-in's
     * first snapshot then closes both by the gap rule.
     */
    suspend fun close(context: Context, atMs: Long = CareLinkClock.now()) {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val seen = CareLinkSeenStore.load(app, atMs)
            if (seen.basalOpen == null && seen.stopOpen == null && seen.lastNormalRate == null) return@withContext
            try {
                if (seen.basalOpen != null || seen.stopOpen != null) {
                    val run = Run(app, seen, CareLinkJournal(app))
                    run.close()
                    CareLinkLog.i("basal closed: ${run.stats}")
                }
                seen.lastNormalRate = null
                seen.lastNormalAtMs = null
            } finally {
                CareLinkSeenStore.save(app, seen, atMs)
            }
        }
    }

    /**
     * The rate the pump runs now, U/h; null - not known (a gap). A stop counts even without the basal object: the
     * cloud says it outright. [lastNormal] - the last normal rate still good for a percentage temporary basal.
     */
    internal fun rateOf(p: PumpNow, lastNormal: Double?): Double? {
        if (p.suspended == true) return 0.0
        if (!p.basalKnown) return null
        val t = p.tempBasal ?: return p.basalRate
        val pct = t.percent
        if (pct != null) return lastNormal?.let { CareLinkMarkers.round3(it * pct / 100.0) }
        // absolute - its rate, 0 too; type not known - a 0 is not believed (a percentage one may carry 0); no rate - the
        // basal rate, which was the temporary rate in 1 of 4 live answers (Glucove's CareLinkBasalWatch, 11.11)
        return t.rate?.takeIf { it > 0.0 || t.absolute } ?: p.basalRate
    }

    /** What one snapshot did: segments opened / closed / moved on, stop notes opened / closed. Numbers only. */
    internal class BasalStats {
        var opened = 0
        var closed = 0
        var extended = 0
        var stopsOpened = 0
        var stopsClosed = 0

        override fun toString(): String =
            "opened=$opened closed=$closed extended=$extended stopsOpened=$stopsOpened stopsClosed=$stopsClosed"
    }

    private class Run(val app: Context, val seen: SeenData, val j: CareLinkJournal) {
        private val settings = CareLinkSettings(app)
        val stats = BasalStats()

        private fun newSource(): JournalEntrySource =
            if (settings.sendToNightscout) JournalEntrySource.HEALTH_CONNECT else JournalEntrySource.API

        suspend fun step(p: PumpNow?) {
            if (p == null) {
                gap()
                return
            }
            val t = p.dataAtMs
            var open = seen.basalOpen
            if (open != null && t < open.lastDataAtMs) return
            rememberNormal(p, t)
            stopNote(p, t)
            val rate = CareLinkBasalWriter.rateOf(p, normalRate(t))?.takeIf { it.isFinite() && it >= 0.0 }
            if (rate == null) {
                gap()
                return
            }
            if (open != null && t - open.lastDataAtMs > CareLinkBasalWriter.GAP_MS) {
                gap()
                open = null
            }
            if (open == null) {
                start(rate, t)
                return
            }
            if (abs(rate - open.rate) > CareLinkBasalWriter.SAME_RATE) {
                writeSegment(open.key, open.startMs, open.rate, t)
                stats.closed++
                start(rate, t)
                return
            }
            var end = open.endMs
            if (end - t < CareLinkBasalWriter.EXTEND_BEFORE_MS) {
                end = t + CareLinkBasalWriter.AHEAD_MS
                writeSegment(open.key, open.startMs, open.rate, end)
                stats.extended++
            }
            seen.basalOpen = open.copy(endMs = end, lastDataAtMs = t)
        }

        /** Sign-out, "sign in again": the segment and the stop note end at their last data. */
        suspend fun close() {
            gap()
            seen.stopOpen?.let { closeStop(it, it.lastAtMs) }
        }

        /**
         * A normal snapshot (no temporary basal, not stopped) gives the normal rate and its time; any other snapshot
         * within [CareLinkBasalWriter.GAP_MS] of that time carries the time along - a percentage temporary basal of
         * hours keeps its base, a night without data does not.
         */
        private fun rememberNormal(p: PumpNow, t: Long) {
            val rate = p.basalRate?.takeIf { it.isFinite() && it >= 0.0 }
            if (p.basalKnown && p.tempBasal == null && p.suspended != true && rate != null) {
                seen.lastNormalRate = rate
                seen.lastNormalAtMs = t
                return
            }
            val at = seen.lastNormalAtMs ?: return
            if (t >= at && t - at <= CareLinkBasalWriter.GAP_MS) seen.lastNormalAtMs = t
        }

        /** The last normal rate, if the data since it ran without a gap. */
        private fun normalRate(t: Long): Double? {
            val at = seen.lastNormalAtMs ?: return null
            return seen.lastNormalRate.takeIf { t - at in 0..CareLinkBasalWriter.GAP_MS }
        }

        /** No data: the running segment ends at its last data time. The stop note waits for the pump to run again. */
        private suspend fun gap() {
            val open = seen.basalOpen ?: return
            if (open.endMs != open.lastDataAtMs) writeSegment(open.key, open.startMs, open.rate, open.lastDataAtMs)
            seen.basalOpen = null
            stats.closed++
        }

        private suspend fun start(rate: Double, t: Long) {
            val key = CareLinkBasalWriter.basalKey(t)
            val end = t + CareLinkBasalWriter.AHEAD_MS
            writeSegment(key, t, rate, end)
            seen.basalOpen = BasalOpen(key, t, rate, end, t)
            stats.opened++
        }

        private suspend fun writeSegment(key: String, startMs: Long, rate: Double, endMs: Long) {
            val minutes = ((endMs - startMs + 59_999L) / 60_000L).coerceAtLeast(1L).toInt()
            val row = j.byKey(key)
            j.upsert(
                JournalEntryInput(
                    id = row?.id,
                    timestamp = startMs,
                    type = JournalEntryType.TEMP_BASAL,
                    title = app.getString(R.string.mod_carelink_journal_basal),
                    amount = rate.toFloat(),
                    durationMinutes = minutes,
                    source = row?.let { JournalEntrySource.fromStorage(it.source) } ?: newSource(),
                    sourceRecordId = key,
                )
            )
        }

        private suspend fun stopNote(p: PumpNow, t: Long) {
            val open = seen.stopOpen
            if (p.suspended == true) {
                if (open != null && t - open.lastAtMs <= CareLinkBasalWriter.GAP_MS) {
                    if (t > open.lastAtMs) seen.stopOpen = open.copy(lastAtMs = t)
                    return
                }
                // stopped again after a gap in the data: the last stop ended at its last snapshot, this one starts now
                if (open != null) closeStop(open, open.lastAtMs)
                val key = CareLinkBasalWriter.stopKey(t)
                val row = j.byKey(key)
                val text = app.getString(R.string.mod_carelink_note_stop_open, hhmm(t))
                val written = j.upsert(note(key, t, row, text))
                seen.stopOpen = StopOpen(key, t, written.id, written.updatedAt, t, text)
                stats.stopsOpened++
                return
            }
            if (p.suspended != false || open == null) return
            // running again: the stop ended now - or, when the data broke off meanwhile, at the last snapshot that saw it
            val end = if (t - open.lastAtMs <= CareLinkBasalWriter.GAP_MS) maxOf(t, open.lastAtMs) else open.lastAtMs
            closeStop(open, end)
        }

        /**
         * Writes the end into the stop note. The person changed the note (its time, or a text that is neither ours of
         * before nor the new one): theirs. By content, not by `updatedAt` alone: our own write whose record was not
         * saved, or upstream's editor saving the note unchanged, is still ours (critic of the joints, C1).
         */
        private suspend fun closeStop(open: StopOpen, endMs: Long) {
            val row = j.row(open.rowId, open.key)
            if (row == null) {
                seen.stopOpen = null
                return
            }
            val text = app.getString(R.string.mod_carelink_note_stop, hhmm(open.startMs), endText(open.startMs, endMs))
            val changed = open.writtenAt != null && row.updatedAt > open.writtenAt &&
                (row.timestamp != open.startMs || (row.note != text && row.note != open.text))
            if (!changed && (row.note != text || row.timestamp != open.startMs)) {
                j.upsert(note(open.key, open.startMs, row, text))
            }
            seen.stopOpen = null
            stats.stopsClosed++
        }

        private fun note(key: String, startMs: Long, row: JournalEntryEntity?, text: String) =
            JournalEntryInput(
                id = row?.id,
                timestamp = startMs,
                type = JournalEntryType.NOTE,
                title = app.getString(R.string.mod_carelink_journal_stop),
                note = text,
                source = row?.let { JournalEntrySource.fromStorage(it.source) } ?: newSource(),
                sourceRecordId = key,
            )

        private fun hhmm(ms: Long): String =
            DateTimeFormatter.ofPattern("HH:mm").withZone(CareLinkBasalWriter.zone()).format(Instant.ofEpochMilli(ms))

        /** The end of a stop: HH:MM, with the date (dd.MM HH:MM) when it is another day than the start. */
        private fun endText(startMs: Long, endMs: Long): String {
            val z = CareLinkBasalWriter.zone()
            val sameDay = Instant.ofEpochMilli(startMs).atZone(z).toLocalDate() ==
                Instant.ofEpochMilli(endMs).atZone(z).toLocalDate()
            if (sameDay) return hhmm(endMs)
            return DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(z).format(Instant.ofEpochMilli(endMs))
        }
    }
}
