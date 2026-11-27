package tk.glucodata.mod.carelink

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * JugglucoNGmod, pump data (series "pump over Bluetooth", part B1): is the Bluetooth pump source on. The source (part
 * B2a) turns it on and off with [set]. While it is on, the CareLink cloud is silent (series brief, decision 17): no poll
 * on any of its five entries, no alarms or work, no "sign in again", and a sign-out touches nothing of the pump data;
 * "no pump data" and "pump records are not saved" are the Bluetooth source's then.
 *
 * A file, not a preference (critic B1, C1): upstream's settings import clears every shared_prefs file its export does
 * not carry (SettingsExporter.importSharedPreferences) and writes back old values of those it does - either would turn
 * the source off silently or bring back an old "on". `noBackupFilesDir/mod-pumpbt/source_on`: there - on; inside - the
 * wall-clock time it was turned on (ms), as the cloud's polling flag (CareLinkSettings.enabled).
 */
object PumpSourceSwitch {
    private const val DIR = "mod-pumpbt"
    private const val FILE = "source_on"

    /** Tests: a fixed answer instead of the file. */
    @Volatile
    internal var override: ((Context) -> Boolean)? = null

    fun bluetoothOn(context: Context): Boolean {
        override?.let { return it(context) }
        return try {
            file(context).exists()
        } catch (t: Throwable) {
            CareLinkLog.w("pump source switch not read (${t.javaClass.simpleName})")
            false
        }
    }

    /**
     * When the source was turned on (wall clock, ms); null - off or unknown. The base of "no pump data" while no data
     * has come since then. The time written in the file; an unreadable one - the file's own time.
     */
    fun onSinceMs(context: Context): Long? {
        if (!bluetoothOn(context)) return null
        return try {
            val f = file(context)
            if (!f.isFile) return null
            f.readText(Charsets.UTF_8).trim().toLongOrNull()?.takeIf { it > 0L }
                ?: f.lastModified().takeIf { it > 0L }
        } catch (t: Throwable) {
            CareLinkLog.w("pump source time not read (${t.javaClass.simpleName})")
            null
        }
    }

    /**
     * On ([nowMs] - the moment, kept while it stays on: turning on again does not move it) or off. Written at once
     * (.tmp + rename); a failure is logged, not thrown.
     */
    fun set(context: Context, on: Boolean, nowMs: Long) {
        try {
            val f = file(context)
            if (!on) {
                if (f.exists() && !f.delete()) CareLinkLog.w("pump source flag not removed")
                return
            }
            if (f.isFile) return
            val dir = f.parentFile ?: return
            dir.mkdirs()
            val tmp = File(dir, "$FILE.tmp")
            FileOutputStream(tmp).use { out ->
                out.write(nowMs.toString().toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(f)) {
                f.delete()
                if (!tmp.renameTo(f)) CareLinkLog.w("pump source flag not moved into place")
            }
        } catch (t: Throwable) {
            CareLinkLog.w("pump source flag not changed (${t.javaClass.simpleName})")
        }
    }

    private fun file(context: Context) = File(File(context.applicationContext.noBackupFilesDir, DIR), FILE)
}

/** Where the pump data of one [CareLinkIntake.accept] comes from. */
enum class IntakeSource { CLOUD, BLUETOOTH }

/**
 * WRITTEN - the journal writer ran without an exception (a basal failure is WRITTEN with [IntakeResult.error]);
 * SKIPPED - nothing was written (the source went off during the cycle, or a snapshot older than the stored one);
 * FAILED - the journal writer threw.
 */
enum class IntakeOutcome { WRITTEN, SKIPPED, FAILED }

data class IntakeResult(
    val outcome: IntakeOutcome,
    /** == (outcome == WRITTEN); the Bluetooth history memory lets its records go by it (series brief, decision 7). */
    val journalAccepted: Boolean,
    /** The first write failure of this call (basal before journal); null - none. */
    val error: CareLinkError?,
    /** The state after the call (unchanged when the source was off). */
    val state: CareLinkState,
)

/**
 * JugglucoNGmod, pump data (series "pump over Bluetooth", part B1): the one way pump data goes into the journal, the
 * basal, the IoB plate and the CareLink state - for the cloud poll and for the Bluetooth source. Moved out of
 * [CareLinkPoller] (what it did after parsing the answer), unchanged for the cloud:
 * - an answer older than the snapshot already taken writes nothing (critic 4c, C3; 4d, B1, C1) - SKIPPED, but the
 *   state still records the attempt and keeps a standing write failure (4d, N1; 4e, N5);
 * - Bluetooth only (critic of the joints 1, C-1, N-3): no snapshot, or one older than the stored one - the events are
 *   written without it, the basal writer is not called and the data time does not move (a long break the basal writer
 *   closes itself by its gap rule on the next snapshot); a snapshot without the basal answer and not stopped - applied
 *   (active insulin, reservoir, data time), the basal writer not called (one failed request is not a basal gap);
 * - the source went off while the cycle ran (signed out of CareLink / the Bluetooth source turned off) - nothing at all
 *   (critic 4c, N2);
 * - the writers each on their own and without cancellation: basal, journal, IoB plate (critic of the joints, C1, C3);
 * - the state under the lock of the sign-out's clear, then [CareLinkNotifier.afterPoll].
 * Cloud only: the "first poll pending" mark and the self-repair of the alarm chain ([CareLinkScheduler.arm]). For the
 * Bluetooth source "on" is [PumpSourceSwitch.bluetoothOn] wherever the cloud asks [CareLinkSettings.enabled]: a sign-out
 * of CareLink does not stop it. The cloud counts as on only while the Bluetooth source is off (decision 17).
 *
 * One writer at a time: [lock] is the poll's lock (the writers' memory, CareLinkSeenStore, is made for one writer).
 */
object CareLinkIntake {

    /** The lock of the cloud poll and of every Bluetooth cycle. */
    internal val lock: Mutex = Mutex()

    // The writers of stage 4d; tests put recording or failing ones here (CareLinkPoller keeps properties of the same
    // names that point here).
    @Volatile
    internal var writeJournal: suspend (Context, List<ClEvent>, Boolean) -> Unit =
        { c, e, first -> CareLinkJournalWriter.apply(c, e, first) }

    @Volatile
    internal var writeBasal: suspend (Context, PumpNow?, Long) -> Unit = { c, p, at -> CareLinkBasalWriter.apply(c, p, at) }

    @Volatile
    internal var publishIob: (PumpNow?) -> Unit = { p -> CareLinkIob.publish(p) }

    @Volatile
    internal var closeBasal: suspend (Context) -> Unit = { c -> CareLinkBasalWriter.close(c) }

    /**
     * One cycle of pump data: [events] (the last 24 h and what is to be caught up), [pump] - the snapshot (null - none
     * this time: for the cloud the basal writer sees a gap, for Bluetooth it is not called), [nowMs] - when it was read.
     * Off the main thread, under [lock].
     *
     * An exception instead of a result - the outcome is unknown, do not let the records go (critic B1, N2): a caller
     * cancelled while the writers ran gets a [kotlinx.coroutines.CancellationException] although the writers, the state
     * and the notifications finished (the writers are not cancelled; `withContext` drops the result of a cancelled
     * caller). Sending the same events again is safe: the journal writer updates its rows by key.
     */
    suspend fun accept(
        context: Context, source: IntakeSource, events: List<ClEvent>, pump: PumpNow?,
        nowMs: Long, firstPoll: Boolean = false,
    ): IntakeResult = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        lock.withLock { acceptLocked(app, source, events, pump, nowMs, firstPoll) }
    }

    /**
     * [accept] with [lock] already held (the cloud poll). [dataAtMs] - the data time this cycle brings (the cloud: its
     * upload time, null for the empty answer; Bluetooth: the snapshot's time); [logDetail] - added to the log line.
     */
    internal suspend fun acceptLocked(
        app: Context, source: IntakeSource, events: List<ClEvent>, pump: PumpNow?, nowMs: Long,
        firstPoll: Boolean = false,
        dataAtMs: Long? = pump?.dataAtMs?.let { minOf(it, nowMs) },
        logDetail: String = "",
    ): IntakeResult {
        val store = CareLinkStateStore(app)
        val older = pump != null && CareLinkPumpNow.older(store.read().pumpNow, pump)
        // Bluetooth: an older snapshot drops only itself, its events are written (critic of the joints 1, N-3)
        val bluetoothOlder = older && source == IntakeSource.BLUETOOTH
        val stale = older && !bluetoothOlder
        val snapshot = if (bluetoothOlder) null else pump
        val dataAt = if (bluetoothOlder) null else dataAtMs

        // the source went off while this cycle ran: nothing more is written (critic 4c, N2)
        if (!active(app, source)) return IntakeResult(IntakeOutcome.SKIPPED, false, null, store.read())
        val writes = if (stale) null else write(app, source, events, snapshot, nowMs, firstPoll)
        val writeError = writes?.error
        val outcome = when {
            writes == null -> IntakeOutcome.SKIPPED
            writes.journalFailed -> IntakeOutcome.FAILED
            else -> IntakeOutcome.WRITTEN
        }
        // an empty answer (no pump data, no boluses) gives the journal nothing to write: a clean run proves nothing
        // about it either (critic 4e, N5)
        val nothingToWrite = stale || (snapshot == null && events.none { it.kind == ClKind.BOLUS && it.dose != null })

        // checked under the lock of the sign-out's clear(): its result must not bring the state back
        val after = store.updateIf({ active(app, source) }) { cur ->
            // an older or an empty answer writes nothing, so it says nothing of the writes: a write failure stands, with
            // its clock and its notification (critic 4d, N1; 4e, N5) - also when a network or cloud failure came in
            // between and took its place in the state, while its clock still runs (critic 4d2, N1)
            val keptWriteError = if (!nothingToWrite) null else
                cur.lastError?.takeIf { cur.status == CareLinkStatus.ERROR && it.kind == CareLinkErrorKind.WRITE }
                    ?: CareLinkError(CareLinkErrorKind.WRITE).takeIf { store.writeFailSinceMs != null }
            val error = writeError ?: keptWriteError
            cur.copy(
                status = if (error == null) CareLinkStatus.OK else CareLinkStatus.ERROR,
                lastPollAtMs = nowMs,
                lastDataAtMs = listOfNotNull(cur.lastDataAtMs, dataAt).maxOrNull(),
                lastError = error,
                pumpNow = CareLinkPumpNow.merge(cur.pumpNow, snapshot),
            )
        } ?: return IntakeResult(outcome, outcome == IntakeOutcome.WRITTEN, writeError, store.read())
        if (source == IntakeSource.CLOUD && writeError == null && !stale) store.firstPollPending = false
        val what = if (source == IntakeSource.CLOUD) "poll" else "pump cycle"
        val detail = if (logDetail.isEmpty()) "" else " $logDetail"
        if (writeError == null) {
            CareLinkLog.i("$what ok: first=$firstPoll events=${events.size} empty=${snapshot == null} stale=$older$detail")
        } else {
            CareLinkLog.w("$what written with an error: events=${events.size} error=${writeError.errorClass}$detail")
        }
        CareLinkNotifier.afterPoll(app, after, nowMs)
        if (source == IntakeSource.CLOUD) {
            // self-repair of the chain: a "sign in again" of an older poll may have removed the alarms (critic 4c, C2)
            try {
                CareLinkScheduler.arm(app)
            } catch (t: Throwable) {
                CareLinkLog.w("arm after poll failed (${t.javaClass.simpleName})")
            }
        }
        return IntakeResult(outcome, outcome == IntakeOutcome.WRITTEN, writeError, after)
    }

    /**
     * The source may write now: the Bluetooth source while it is on; the cloud while polling is on and the Bluetooth
     * source is off (decision 17).
     */
    internal fun active(app: Context, source: IntakeSource): Boolean = when (source) {
        IntakeSource.CLOUD -> CareLinkSettings(app).enabled && !PumpSourceSwitch.bluetoothOn(app)
        IntakeSource.BLUETOOTH -> PumpSourceSwitch.bluetoothOn(app)
    }

    private class Writes(val error: CareLinkError?, val journalFailed: Boolean)

    /**
     * The writers of one fresh cycle, each on its own (critic of the joints, C3): the basal, then the journal, then
     * the IoB plate. Without cancellation: WorkManager cancels running work when the network goes, and a journal row
     * written whose record was not saved would look like the person's edit next time (critic of the joints, C1).
     */
    private suspend fun write(
        app: Context, source: IntakeSource, events: List<ClEvent>, pump: PumpNow?, now: Long, firstPoll: Boolean,
    ): Writes = withContext(NonCancellable) {
        val tag = if (source == IntakeSource.CLOUD) "poll" else "pump cycle"
        var error: CareLinkError? = null
        var journalFailed = false
        try {
            if (basalCalled(source, pump)) writeBasal(app, pump, now)
        } catch (t: Throwable) {
            CareLinkLog.w("$tag: basal not written (${t.javaClass.simpleName})")
            error = CareLinkError(CareLinkErrorKind.WRITE, 0, t.javaClass.simpleName)
        }
        try {
            writeJournal(app, events, firstPoll)
        } catch (t: Throwable) {
            CareLinkLog.w("$tag: journal not written (${t.javaClass.simpleName})")
            journalFailed = true
            if (error == null) error = CareLinkError(CareLinkErrorKind.WRITE, 0, t.javaClass.simpleName)
        }
        try {
            // the source went off while writing: the pump's number stays off the plate (critic 4d2, N3)
            if (active(app, source)) publishIob(pump)
        } catch (t: Throwable) {
            // the IoB plate is a display: a failure here is not a failed cycle
            CareLinkLog.w("$tag: active insulin not published (${t.javaClass.simpleName})")
        }
        Writes(error, journalFailed)
    }

    /**
     * The basal writer gets this cycle: the cloud always (its empty answer is a gap); Bluetooth only a snapshot with the
     * basal answer or a stop (critic of the joints 1, C-1).
     */
    private fun basalCalled(source: IntakeSource, pump: PumpNow?): Boolean =
        source == IntakeSource.CLOUD || (pump != null && (pump.basalKnown || pump.suspended == true))

    /** Back to defaults (tests). */
    internal fun resetForTest() {
        writeJournal = { c, e, first -> CareLinkJournalWriter.apply(c, e, first) }
        writeBasal = { c, p, at -> CareLinkBasalWriter.apply(c, p, at) }
        publishIob = { p -> CareLinkIob.publish(p) }
        closeBasal = { c -> CareLinkBasalWriter.close(c) }
    }
}
