package tk.glucodata.mod.pumpbt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tk.glucodata.Natives
import tk.glucodata.mod.carelink.CareLinkClock
import tk.glucodata.mod.carelink.CareLinkIntake
import tk.glucodata.mod.carelink.CareLinkIob
import tk.glucodata.mod.carelink.CareLinkNotifier
import tk.glucodata.mod.carelink.CareLinkSettings
import tk.glucodata.mod.carelink.ClEvent
import tk.glucodata.mod.carelink.ClKind
import tk.glucodata.mod.carelink.IntakeResult
import tk.glucodata.mod.carelink.PumpNow
import tk.glucodata.mod.carelink.IntakeOutcome
import tk.glucodata.mod.carelink.IntakeSource
import tk.glucodata.mod.carelink.PumpSourceSwitch
import tk.glucodata.mod.pumpbt.ble.PumpBtAdapterName
import tk.glucodata.mod.pumpbt.ble.PumpLink
import tk.glucodata.mod.pumpbt.ble.PumpLinkHealth
import tk.glucodata.mod.pumpbt.ble.PumpLinkRules
import tk.glucodata.mod.pumpbt.core.BolusEvent
import tk.glucodata.mod.pumpbt.core.DeliveryStopEvent
import tk.glucodata.mod.pumpbt.core.HistoryEvent
import tk.glucodata.mod.pumpbt.core.HistoryRead
import tk.glucodata.mod.pumpbt.core.HistoryReadStatus
import tk.glucodata.mod.pumpbt.core.MealEvent
import tk.glucodata.mod.pumpbt.core.PumpCycle
import tk.glucodata.mod.pumpbt.core.PumpEvents
import tk.glucodata.mod.pumpbt.core.PumpEventsResult
import java.io.File
import java.time.ZoneId

/** What the source asks the notifier (part B2c) to tell the person. */
internal enum class PumpBtAlertKind { UNKNOWN_RECORD, UNPARSED_BOLUS, CONFIRM_BACKFILL, NO_RESULT_BOLUS, CLOCK_DRIFT, BOND_LOST }

/** [recordType] - the record type of [PumpBtAlertKind.UNKNOWN_RECORD] and [PumpBtAlertKind.UNPARSED_BOLUS]. */
internal data class PumpBtAlert(val kind: PumpBtAlertKind, val recordType: Int? = null)

/** Everything the window (part B2b) shows of the source. */
internal data class PumpBtSourceState(
    /** [PumpSourceSwitch.bluetoothOn]. */
    val enabled: Boolean = false,
    val link: PumpBtLinkState = PumpBtLinkState(),
    /**
     * Since when the running link waits for the pump to come (phone clock, [PumpBtWindow.waitingSince]); null - not
     * waiting (stopped, pairing, connected). Process memory: a new process starts the wait anew.
     */
    val waitingSinceMs: Long? = null,
    /** The pump's address is among the phone's bonded devices; null - not known (Bluetooth off, no permission, no address). */
    val pumpBonded: Boolean? = null,
    /**
     * "Paired" is set, but the phone no longer has the pump among its bonded devices: pair again (the window advises,
     * by this mark only). Cleared by every complete handshake, checked again when a bond changes.
     */
    val bondLost: Boolean = false,
    /** The link journal file holds detailed lines (serial number, addresses, answers) - until it is cleared or made anew. */
    val journalHasDetail: Boolean = false,
    /** The last cycle the source got (phone clock) and whether it lacked the serial number (then nothing was kept). */
    val lastCycleAtMs: Long? = null,
    val lastCycleNoSerial: Boolean = false,
    /** The journal intake's answer to the last cycle; null - not asked (source off, no serial, stopped before). */
    val lastIntake: IntakeOutcome? = null,
    /** The history memory: up to which record everything came, how many records it holds, the counters. */
    val contiguousTo: Long? = null,
    val records: Int = 0,
    val counters: PumpHistoryCounters = PumpHistoryCounters(),
    val backfill: BackfillState = BackfillState.NOT_READY,
    /** The first catch-up card while it waits for the person; null - none. */
    val card: BackfillCard? = null,
    val warnings: Set<PumpWarning> = emptySet(),
    /** Upstream's sensor Bluetooth on (`Natives.getusebluetooth()`): without it upstream's service, and so the process, may not stay (decision 1). */
    val sensorBluetooth: Boolean = true,
    val verbose: Boolean = false,
    /** The history memory file could not be read at the start of this process: it began again. */
    val memoryLoadFailed: Boolean = false,
)

/**
 * JugglucoNGmod, pump over Bluetooth (series part 2, part B2a): the Bluetooth pump source - one per process, the only
 * owner of the link ([PumpLink]). It ties the link, the history memory ([PumpBtHistoryStore], [PumpHistoryRules]), the
 * events ([PumpEvents]), the bridge ([PumpBtBridge]) and the journal intake ([CareLinkIntake]) together (series brief,
 * decisions 1, 2, 5-7, 13-15).
 *
 * One cycle, under the source's own [Mutex] and off the main thread (the link's cycles go through one queue, in
 * order): no serial number - nothing; [PumpHistoryRules.applyCycle] -> save -> [PumpEvents.build] from the whole
 * memory -> journal rows ([PumpBtBridge.journalRows]) -> [PumpBtBridge.planCycle] -> save (aliases, card, counters,
 * events accepted without feeding - before anything is written: a death after the writer must not make a second row)
 * -> [CareLinkIntake.accept] -> WRITTEN only: [PumpHistoryRules.markAccepted], [PumpHistoryRules.prune], save. A failed
 * save stops the cycle before the intake; an exception from the intake leaves every record where it was (contract 3.2).
 * The card buttons run under the same mutex (critic B2, N5). One order of locks everywhere: the source's mutex, then
 * [CareLinkIntake.lock] (the cycle through the intake, [disable] itself; critic of the joints 1, C-2). The link asks
 * [historyFrom] at every history read: a snapshot of the memory's number, from the memory loaded before the link
 * starts, taken after every save, null at once on another pump; an empty memory takes only a read asked as for an
 * empty memory (N-10). The enable moment: the memory first, then the switch; checked against each other at the start
 * (C-3).
 *
 * While the source is off the link still serves the trial window: its cycles keep only a number in process memory (as
 * the trial did) and touch neither the history memory nor the journal.
 *
 * Starts in "waiting for the pump" (FE81), never in pairing: after an update the paired pump must come back by itself.
 * Calls are safe from any thread; link calls are moved to the main thread.
 */
internal object PumpBtSource {

    /** Record types of boluses and meals: an unparsed one may be a dose (one notification per type). */
    val DOSE_TYPES: Set<Int> = setOf(0x005A, 0x0066, 0x0069, 0x0096, 0xF005)

    private const val ANNUNCIATION = 0xF010

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cycleLock = Mutex()

    private val _state = MutableStateFlow(PumpBtSourceState())
    val state: StateFlow<PumpBtSourceState> = _state.asStateFlow()

    /**
     * Set by the notifier (part B2c) before [init]; called off the main thread. null - nobody to tell: nothing is
     * marked as told (critic B2a, C-3).
     */
    @Volatile
    internal var alert: ((Context, PumpBtAlert) -> Unit)? = null

    /** Upstream's sensor Bluetooth switch; tests put their own (the native library is not there). */
    @Volatile
    internal var sensorBluetooth: () -> Boolean = { runCatching { Natives.getusebluetooth() }.getOrDefault(true) }

    /** The journal intake; tests put one that throws (an exception from the intake itself). */
    @Volatile
    internal var intake: suspend (Context, List<ClEvent>, PumpNow?, Long) -> IntakeResult =
        { c, e, p, n -> CareLinkIntake.accept(c, IntakeSource.BLUETOOTH, e, p, n) }

    /** Saves the history memory; tests put one that fails. */
    @Volatile
    internal var saveMemory: (PumpBtHistoryStore, PumpHistoryMemory) -> Unit = { s, m -> s.save(m) }

    /** The phone's time zone for pump-local times; tests put their own. */
    @Volatile
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    /** The phone's bonded devices (address, name), null - not known; tests put their own (Robolectric's Bluetooth is off). */
    @Volatile
    internal var bondedDevices: (Context) -> List<Pair<String, String?>>? = { bondedFromAdapter(it) }

    /** The trial window's link state (until part B2b reads [state] itself). */
    @Volatile
    internal var onLinkState: ((PumpBtLinkState) -> Unit)? = null

    @Volatile private var app: Context? = null
    @Volatile private var store: PumpBtStore? = null
    @Volatile private var historyStore: PumpBtHistoryStore? = null

    // main thread
    private var link: PumpLink? = null
    private var key: PumpBtKey.Result.Ok? = null
    private var linkKeyHex: String? = null

    /** The memory as last saved (under [cycleLock]); null - not loaded yet. */
    @Volatile private var memory: PumpHistoryMemory? = null

    /** The memory in [memory] is newer than its file (a mended enable moment whose save failed): saved again. */
    @Volatile private var unsaved = false

    /** The link's last published stage: a complete handshake clears the lost bond. */
    @Volatile private var lastStage: PumpBtStage? = null

    /** A complete handshake since the last time a bond of the pump went away (point 8). */
    @Volatile private var bondTrusted = false

    private var bondReceiver: BroadcastReceiver? = null

    /** [PumpHistoryRules.nextFrom] of the memory, after every save: what the link asks for. */
    @Volatile private var nextFrom: Long? = null

    /** The trial's number while the source is off (process memory only, as before). */
    @Volatile private var trialSeq: Long? = null

    /** The last number the link was given (source on) was null - "from the oldest record" (critic of the joints 1, N-10). */
    @Volatile private var lastIssuedNull = true

    /** The warnings of the last cycle: a link journal line only when one appears (notifications - [PumpBtAlertRules]). */
    @Volatile private var lastWarnings: Set<PumpWarning> = emptySet()

    private var cycles: Channel<PumpCycle>? = null

    private val journal: (String) -> Unit = { PumpBtJournal.add(it) }

    // MARK: - Start, on and off

    /**
     * The process started (from the starter after Application.onCreate, part B2c) or the trial window opened:
     * loads the memory, checks the bond and, with the source on, starts the link waiting for the paired pump - only
     * when it is not running: a running link (a pairing from the window, a turned screen) is not touched (critic B2a,
     * C-1). Again - nothing new. The job is for tests.
     */
    fun init(context: Context): Job {
        val c = attach(context)
        val s = store(c)
        // a journal file made anew holds no detailed lines (the journal keeps its file at this path)
        if (s.journalHasDetail && !s.verbose && !journalFile(c).exists()) s.journalHasDetail = false
        if (s.verbose) s.journalHasDetail = true
        _state.update {
            it.copy(enabled = PumpSourceSwitch.bluetoothOn(c), sensorBluetooth = sensorBluetooth(), verbose = s.verbose, journalHasDetail = s.journalHasDetail)
        }
        watchBonds(c)
        return scope.launch {
            // each step on its own: a card that failed must not skip the bond check, and the journal names what failed
            // (critic of the joints 2, N-3)
            cycleLock.withLock {
                startStep("history memory not loaded") { memoryLocked(c) }
                if (memory?.backfill == BackfillState.WAITING) startStep("card not refreshed") { refreshCardLocked(c) }
                startStep("bond not checked") { checkBondedLocked(c) }
            }
            onMain {
                if (link?.isRunning == true) return@onMain
                // the phone's name of variant B, when the process died while it was changed
                PumpBtAdapterName.restore(c, store(c)) { journal(PumpLinkRules.hideQuoted(it)) }
                if (PumpSourceSwitch.bluetoothOn(c)) startLinkNow(c, pair = false)
            }
        }
    }

    /** One step of [init]: a failure is journalled as [what] and the next step runs. */
    private inline fun startStep(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            journal("source: $what (${t.javaClass.simpleName})")
        }
    }

    /**
     * "Paired" against the phone's bonded devices again (the window opened or came back; a bond changed; the watchdog,
     * while "the phone forgot the pump" was not delivered): the "phone forgot the pump" mark goes as soon as the bond
     * is there, and an undelivered one is told again. Cheap, the link is not touched. The job is for tests.
     */
    fun recheckBond(context: Context): Job {
        val c = attach(context)
        return scope.launch {
            try {
                cycleLock.withLock { checkBondedLocked(c) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                journal("source: bond not checked (${t.javaClass.simpleName})")
            }
        }
    }

    /**
     * Turns the source on (decision 5, plan B2a point 3): a new enable moment and boundary saved in the memory - only
     * when it was off, or the memory has no moment -, and only then the switch, with the same moment (critic of the
     * joints 1, C-3: a death between them leaves the source off, not on with an old moment); the link in permanent
     * operation (variant A, waiting), "paired" checked against the phone's bonded devices. The job is for tests.
     */
    fun enable(context: Context): Job {
        val c = attach(context)
        return scope.launch {
            try {
                cycleLock.withLock {
                    val now = CareLinkClock.now()
                    val wasOn = PumpSourceSwitch.bluetoothOn(c)
                    val m = memoryLocked(c)
                    if (!wasOn || m.enabledAtMs == null) {
                        val boundary = PumpBtBridge.boundary(c, now)
                        if (!saveLocked(c, PumpHistoryRules.enable(m, now, boundary))) return@withLock
                        journal("source on: old events are those before ${PumpBtJournal.fullDate(now)}; the boundary of the old ${PumpBtJournal.fullDate(minOf(boundary, now))}")
                    } else {
                        journal("source on again: it was on, the enable moment stays")
                    }
                    PumpSourceSwitch.set(c, true, now)
                    publishMemory()
                    checkBondedLocked(c)
                }
                val on = PumpSourceSwitch.bluetoothOn(c)
                _state.update { it.copy(enabled = on) }
                // a quick "off" in between: the switch decides on the main thread (critic B2a, N-4)
                if (on) onMain {
                    if (PumpSourceSwitch.bluetoothOn(c) && link?.isRunning != true) startLinkNow(c, pair = false)
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                journal("source: not turned on fully (${t.javaClass.simpleName})")
            }
        }
    }

    /**
     * Turns the source off (critic B1, N4): under the source's mutex and in it the intake's lock (the order of every
     * caller: a cycle holds the mutex while the intake takes its lock - critic of the joints 1, C-2) - the switch off,
     * the open basal segment closed at its last data, the pump's active insulin off the plate, "no data" and "not
     * saved" with their marks and clock away; then the link stops. The history memory stays. The job is for tests.
     */
    fun disable(context: Context): Job {
        val c = attach(context)
        return scope.launch {
            try {
                cycleLock.withLock {
                    CareLinkIntake.lock.withLock {
                        PumpSourceSwitch.set(c, false, CareLinkClock.now())
                        withContext(NonCancellable) {
                            try {
                                CareLinkIntake.closeBasal(c)
                            } catch (t: Throwable) {
                                journal("source off: basal not closed (${t.javaClass.simpleName})")
                            } finally {
                                CareLinkIob.clear()
                                // "no data" and "not saved" with their marks and clock: the next "on" warns afresh
                                // (joint review 3, C-1)
                                CareLinkNotifier.cancelAll(c)
                            }
                        }
                    }
                }
                journal("source off: basal closed, active insulin off the plate")
                _state.update { it.copy(enabled = PumpSourceSwitch.bluetoothOn(c), card = null, warnings = emptySet()) }
                // a quick "on" in between keeps its link (critic B2a, N-4)
                onMain { if (!PumpSourceSwitch.bluetoothOn(c)) link?.stop() }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                journal("source: not turned off fully (${t.javaClass.simpleName})")
            }
        }
    }

    /** "Write" on the card: exactly [shownKeys]; a card changed meanwhile is made again (decision 5). The job is for tests. */
    fun confirmBackfill(context: Context, shownKeys: List<String>): Job {
        val c = attach(context)
        return scope.launch {
            var read = false
            try {
                cycleLock.withLock {
                    val m = memoryLocked(c)
                    val confirmed = PumpBtBridge.confirmBackfill(m, shownKeys)
                    if (confirmed == null) {
                        journal("source: the card changed before \"Write\" - shown again")
                        refreshCardLocked(c)
                    } else if (saveLocked(c, confirmed)) {
                        journal("source: first catch-up confirmed, ${shownKeys.size} to write")
                        _state.update { it.copy(card = null) }
                        publishMemory()
                        read = true
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                journal("source: \"Write\" not taken (${t.javaClass.simpleName})")
            }
            // the listed events go with the next cycle: ask for one now
            if (read) readNow()
        }
    }

    /** "Do not write the old": the boundary becomes the enable moment (decision 5). The job is for tests. */
    fun declineBackfill(context: Context): Job {
        val c = attach(context)
        return scope.launch {
            try {
                cycleLock.withLock {
                    if (saveLocked(c, PumpBtBridge.declineBackfill(memoryLocked(c)))) {
                        journal("source: first catch-up declined, nothing old is written")
                        _state.update { it.copy(card = null) }
                        publishMemory()
                    }
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                journal("source: \"Do not write the old\" not taken (${t.javaClass.simpleName})")
            }
        }
    }

    /** The detailed link journal (decision 13), off by default. */
    fun setVerbose(context: Context, on: Boolean) {
        val c = attach(context)
        val s = store(c)
        s.verbose = on
        if (on) s.journalHasDetail = true
        journal("detailed journal " + if (on) "on" else "off")
        _state.update { it.copy(verbose = on, journalHasDetail = s.journalHasDetail) }
    }

    /** The link journal file was emptied: no detailed lines in it any more (unless the detailed journal is on). */
    fun journalCleared(context: Context) {
        val c = attach(context)
        val s = store(c)
        s.journalHasDetail = s.verbose
        _state.update { it.copy(journalHasDetail = s.journalHasDetail) }
    }

    /** The journal's file (PumpBtJournal keeps it here): missing - made anew. */
    private fun journalFile(c: Context) = File(File(c.filesDir, PumpBtStore.DIR), "journal.txt")

    // MARK: - One cycle

    /**
     * One finished cycle of the link (or of the made-up pump of part P), under the source's mutex. With the source
     * off: only the trial's number moves.
     */
    suspend fun acceptCycle(context: Context, cycle: PumpCycle) {
        val c = attach(context)
        withContext(Dispatchers.IO) { cycleLock.withLock { cycleLocked(c, cycle) } }
    }

    private suspend fun cycleLocked(c: Context, cycle: PumpCycle) {
        val on = PumpSourceSwitch.bluetoothOn(c)
        _state.update { it.copy(enabled = on, sensorBluetooth = sensorBluetooth()) }
        if (!on) {
            trialCycle(cycle)
            return
        }
        if (cycle.serialHash == null) {
            journal("source: cycle without the serial number - nothing kept or written")
            _state.update { it.copy(lastCycleAtMs = cycle.readAtMs, lastCycleNoSerial = true, lastIntake = null) }
            return
        }
        val now = cycle.readAtMs
        val zone = zone()
        val before = memoryLocked(c)
        var taken = cycle
        if (before.contiguousTo == null && before.records.isEmpty() && !askedAsEmpty(cycle)) {
            // an empty memory takes the first record asked as its start: a read asked from an older number would hide
            // the records below it for ever (critic of the joints 1, N-10) - the snapshot goes, the history waits
            journal("source: history of this cycle asked from an older number - not taken by the empty memory")
            taken = cycle.copy(records = emptyList(), history = HistoryRead(HistoryReadStatus.NONE))
        }
        val applied = PumpHistoryRules.applyCycle(before, taken, now)
        if (applied.newPump) {
            nextFrom = null
            journal("source: another pump - the history memory started again, enable moment and boundary now")
        }
        if (applied.gapRecords > 0) journal("source: ${applied.gapRecords} history records lost (gone from the pump or skipped)")
        applied.skippedStuck?.let { journal("source: record #$it skipped after ${PumpHistoryRules.STUCK_CYCLES} failed cycles") }
        if (!saveLocked(c, applied.memory)) return

        val events = PumpEvents.build(applied.memory.records, applied.memory.reference, applied.memory.contiguousTo)
        logRecords(events, taken)
        val rows = try {
            PumpBtBridge.journalRows(c, applied.memory, events, zone)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            journal("source: journal rows not read (${t.javaClass.simpleName}) - nothing written this cycle")
            return
        }
        val plan = PumpBtBridge.planCycle(
            applied.memory, events, rows, now, zone, applied.firstReadComplete, writeCarbs = writeCarbs(c),
        )
        // before anything is written (plan B2a, point 1)
        if (!saveLocked(c, plan.memory)) return

        logPlan(applied.memory, plan, events)
        val appeared = plan.warnings - lastWarnings
        lastWarnings = plan.warnings
        if (appeared.isNotEmpty()) journal("source: warning " + appeared.joinToString { it.name.lowercase() })
        notify(c, applied, plan, events, now, zone)
        _state.update {
            it.copy(
                lastCycleAtMs = now, lastCycleNoSerial = false, lastIntake = null, warnings = plan.warnings,
                card = if (plan.memory.backfill == BackfillState.WAITING) plan.card ?: it.card else null,
            )
        }
        publishMemory()

        val result = try {
            intake(c, plan.toWriter, PumpBtBridge.toPumpNow(cycle), now)
        } catch (t: Throwable) {
            // the outcome is unknown, a cancellation too: nothing is let go, no markAccepted (contract 3.2, critic B1, N2)
            journal("source: journal intake ended with ${t.javaClass.simpleName} - the records stay")
            if (t is CancellationException) throw t
            return
        }
        val boluses = plan.toWriter.count { it.kind == ClKind.BOLUS }
        val meals = plan.toWriter.count { it.kind == ClKind.MEAL }
        journal("source: fed $boluses boluses, $meals meals and the snapshot - ${result.outcome.name.lowercase()}")
        _state.update { it.copy(lastIntake = result.outcome) }
        if (result.outcome != IntakeOutcome.WRITTEN) return
        val accepted = PumpHistoryRules.markAccepted(plan.memory, plan.toWriter, now)
        val pruned = PumpHistoryRules.prune(accepted, events, now, zone)
        // a failed save here only feeds the same events again (the writer updates its rows by key)
        if (saveLocked(c, pruned)) {
            val gone = plan.memory.records.size - pruned.records.size
            if (gone > 0) journal("source: $gone old records left the history memory")
            publishMemory()
        }
    }

    /** The source is off: the trial's number moves to what came without a gap (another pump - from its oldest again). */
    private fun trialCycle(cycle: PumpCycle) {
        val h = cycle.history
        val seq = trialSeq
        if (seq != null && h.newestOnPump != null && h.newestOnPump < seq) trialSeq = null
        h.contiguousTo?.let { to -> trialSeq = maxOf(trialSeq ?: Long.MIN_VALUE, to) }
        _state.update { it.copy(lastCycleAtMs = cycle.readAtMs, lastCycleNoSerial = cycle.serialHash == null, lastIntake = null) }
    }

    /**
     * The link journal after parsing (plan B2a, point 2; decision 15): unparsed records of this cycle (type and number,
     * no body), annunciation records 0xF010 of this cycle (type and status, nothing personal).
     */
    private fun logRecords(events: PumpEventsResult, cycle: PumpCycle) {
        val newSeqs = cycle.records.mapTo(HashSet()) { it.sequence }
        for (u in events.unparsed) {
            if (u.sequence in newSeqs) journal("source: record #${u.sequence} type 0x%04X not parsed: ${u.reason}".format(u.type))
        }
        val annunciations = cycle.records.filter { it.type == ANNUNCIATION }
            .mapNotNull { it.parse().event as? HistoryEvent.Annunciation }
            .groupingBy { it.type to it.status }.eachCount()
        if (annunciations.isNotEmpty()) {
            journal("source: annunciation records " + annunciations.entries.sortedBy { it.key.first }
                .joinToString { (k, n) -> "type 0x%03X status 0x%02X ×%d".format(k.first, k.second, n) })
        }
    }

    /**
     * Notifications of one cycle (plan B2a, point 4; critic of the joints 1, N-6, N-7); what was told is kept in
     * [PumpBtStore.alerts] (a file: survives a restart and a settings import): unknown record type and unparsed bolus or
     * meal record - once per type; "bolus result not received" - once per bolus; "pump and phone clocks differ" - at
     * most once a day and again only after it was gone; the first catch-up card - when it is made.
     */
    private fun notify(c: Context, applied: CycleApplied, plan: CyclePlan, events: PumpEventsResult, nowMs: Long, zone: ZoneId) {
        val m = plan.memory
        val keys = PumpBtBridge.eventKeys(events.events)
        val boluses = events.events.filterIsInstance<BolusEvent>()
        fun key(e: BolusEvent): String? = keys[e]?.let { m.aliases[it] ?: it }
        // the bridge's rule of NO_RESULT_BOLUS, per bolus
        val noResult = if (PumpWarning.NO_RESULT_BOLUS !in plan.warnings) emptySet() else boluses.filter {
            !it.completed && nowMs - it.at.atZone(zone).toInstant().toEpochMilli() > (it.durationMinutes ?: 0) * 60_000L + PumpBtBridge.NO_RESULT_MS
        }.mapNotNull(::key).toSet()
        val s = store(c)
        val before = s.alerts
        val (alerts, decided) = PumpBtAlertRules.decide(
            before,
            unknownTypes = events.unknownTypes,
            unparsedTypes = events.unparsed.map { it.type }.filter { it in DOSE_TYPES }.toSet(),
            noResultKeys = noResult,
            bolusKeys = boluses.mapNotNull(::key).toSet(),
            // the drift is told only on a history read through (as the bridge decides it); otherwise not known
            drift = if (!applied.firstReadComplete || m.reference == null) null else PumpWarning.CLOCK_DRIFT in plan.warnings,
            nowMs = nowMs,
        )
        val unknownNew = alerts.filter { it.kind == PumpBtAlertKind.UNKNOWN_RECORD }.mapNotNull { it.recordType }
        if (unknownNew.isNotEmpty()) journal("source: unknown record types " + unknownNew.joinToString { "0x%04X".format(it) })
        val failed = alerts.filterNot { sendAlert(c, it) }
        val after = PumpBtAlertRules.keepUndelivered(before, decided, failed)
        if (after != before) s.alerts = after
        if (m.backfill == BackfillState.WAITING && applied.memory.backfill != BackfillState.WAITING) {
            sendAlert(c, PumpBtAlert(PumpBtAlertKind.CONFIRM_BACKFILL))
        }
    }

    /**
     * The history of a cycle may start an empty memory only when it was asked as for an empty memory: from the pump's
     * oldest record (its answer known), or - the oldest not known - when the last number the link was given was null.
     */
    private fun askedAsEmpty(cycle: PumpCycle): Boolean {
        val h = cycle.history
        val from = h.requestedFrom ?: return true
        val oldest = h.oldestOnPump
        return if (oldest != null) from <= oldest else lastIssuedNull
    }

    /** Events by kind (no doses), the first catch-up's matching result when the card was made now, the memory. */
    private fun logPlan(before: PumpHistoryMemory, plan: CyclePlan, events: PumpEventsResult) {
        val bolusEvents = events.events.filterIsInstance<BolusEvent>()
        journal(
            "source: memory up to #${plan.memory.contiguousTo ?: "-"}, ${plan.memory.records.size} records; events: " +
                "${bolusEvents.size} boluses (${bolusEvents.count { !it.completed }} running), " +
                "${events.events.count { it is MealEvent }} meals, ${events.events.count { it is DeliveryStopEvent }} stops" +
                (if (events.unparsed.isNotEmpty()) ", ${events.unparsed.size} not parsed" else "") +
                (if (events.undated.isNotEmpty()) ", ${events.undated.size} without time" else ""),
        )
        val madeNow = plan.memory.backfillShown != before.backfillShown ||
            (before.backfill == BackfillState.NOT_READY && plan.memory.backfill != BackfillState.NOT_READY)
        if (madeNow) {
            val card = plan.card
            journal(
                "source: first catch-up - matched by key ${plan.matchedByKey}, by time and dose ${plan.matchedByTime}, new ${plan.fresh}; " +
                    if (card == null) "nothing old to decide" else
                        "card waiting: ${card.toWrite.size} to write, ${card.alreadyInJournal} already in the journal, ${card.oldNotFound} old not found",
            )
        }
    }

    // MARK: - For the watchdog (part B2c) and the window

    fun linkHealth(): PumpLinkHealth? = link?.health

    /** Read now if there is a link with the pump (otherwise nothing). */
    fun readNow() = onMain { link?.readNow() }

    /** Drop the link and advertise again. */
    fun reconnect() = onMain { link?.reconnect() }

    /**
     * The source is on, but the link is not running - started (waiting); running, neither connected nor advertising
     * (a recovery pause or a lost advertisement) - advertising again.
     */
    fun ensureAdvertising(context: Context) {
        val c = attach(context)
        afterLoad(c) {
            if (!PumpSourceSwitch.bluetoothOn(c)) return@afterLoad
            val l = link
            if (l == null || !l.isRunning) {
                startLinkNow(c, pair = false)
            } else {
                val h = l.health
                if (!h.connected && !h.advertising) l.reconnect()
            }
        }
    }

    /** The trial window's Start buttons: [pair] - advertising for pairing (FE82), else waiting for the paired pump. */
    fun startLink(context: Context, pair: Boolean) {
        val c = attach(context)
        afterLoad(c) { startLinkNow(c, pair) }
    }

    /**
     * "Cancel pairing" of the window with permanent operation on (critic of the joints 2, N-4): the link goes back to
     * waiting for the paired pump (FE81), as after turning permanent operation on - a running link switches its mode
     * ([PumpLink.start] publishes the pair mode), a stopped one is started waiting while the source is on. Nothing else
     * changes ("paired", the lost-bond mark, the memory).
     */
    fun cancelPairing(context: Context) {
        val c = attach(context)
        afterLoad(c) {
            if (link?.isRunning != true && !PumpSourceSwitch.bluetoothOn(c)) return@afterLoad
            journal("pairing cancelled in the window: waiting for the paired pump")
            startLinkNow(c, pair = false)
        }
    }

    /** The trial window's Stop. With the source on the watchdog starts the link again (waiting). */
    fun stopLink() = onMain { link?.stop() }

    val linkRunning: Boolean get() = link?.isRunning == true

    /** "The phone forgot the pump" is marked, but its notification was not delivered (the watchdog checks again). */
    fun bondLostUntold(): Boolean = _state.value.bondLost && store?.alerts?.bondLostTold == false

    /**
     * A pump's address is kept (the starter: someone who uses the source) - read only: the process's store when there is
     * one, else the link state file or the old preferences, without making the file (critic of the joints 2, N-5).
     */
    fun pumpAddressKnown(context: Context): Boolean =
        store?.pumpAddress?.isNotBlank() ?: PumpBtStore.addressKnown(context.applicationContext)

    /** A key database loaded by the trial window: it reaches the next link start (a running link keeps its key). */
    fun keyLoaded(result: PumpBtKey.Result.Ok) = onMain { key = result }

    /** The key database (loaded from its file once); null - none. Main thread. */
    fun key(context: Context): PumpBtKey.Result.Ok? {
        val c = attach(context)
        return key ?: PumpBtKey.load(c).also { key = it }
    }

    /** The one [PumpBtStore] of the process. */
    fun store(context: Context): PumpBtStore {
        store?.let { return it }
        synchronized(this) {
            return store ?: PumpBtStore(context.applicationContext).also { store = it }
        }
    }

    /** Android 12+: BLUETOOTH_ADVERTISE and BLUETOOTH_CONNECT (in upstream's manifest); before - install-time ones. */
    fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)
        else emptyArray()

    fun hasPermissions(context: Context): Boolean =
        permissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    // MARK: - Inside

    private fun attach(context: Context): Context {
        val c = app ?: context.applicationContext.also { app = it }
        PumpBtJournal.init(c)
        store(c)
        synchronized(this) {
            if (historyStore == null) historyStore = PumpBtHistoryStore(c)
            if (cycles == null) {
                val ch = Channel<PumpCycle>(Channel.UNLIMITED)
                cycles = ch
                // the link's cycles one after the other, in the order they ended
                scope.launch {
                    for (cycle in ch) {
                        try {
                            acceptCycle(c, cycle)
                        } catch (t: Throwable) {
                            // only the queue's own end stops it; a cancellation from inside one cycle does not (critic B2a, N-1)
                            if (t is CancellationException && !isActive) throw t
                            journal("source: cycle not taken (${t.javaClass.simpleName})")
                        }
                    }
                }
            }
        }
        return c
    }

    /**
     * The memory, loaded once (at [init] or the first call). With the source on it is checked against the switch
     * before any cycle: a broken file - a new enable moment now and its boundary (plan B2a, point 6); no enable moment,
     * or one older than the switch's (a death between the switch and the memory of an older build, or the files out of
     * step) - the enable moment of the switch and its boundary (critic of the joints 1, C-3). Never otherwise: the moment
     * must not move at every start.
     */
    private suspend fun memoryLocked(c: Context): PumpHistoryMemory {
        memory?.let { m ->
            if (unsaved) saveLocked(c, m)
            return memory ?: m
        }
        val hs = historyStore ?: PumpBtHistoryStore(c).also { historyStore = it }
        var m = hs.load()
        val failed = hs.loadFailed
        memory = m
        nextFrom = PumpHistoryRules.nextFrom(m)
        if (failed) journal("source: the history memory file could not be read - it begins again (copy kept beside)")
        if (PumpSourceSwitch.bluetoothOn(c)) {
            val onSince = PumpSourceSwitch.onSinceMs(c)
            val enabledAt = m.enabledAtMs
            val at = when {
                failed -> CareLinkClock.now()
                enabledAt == null -> onSince ?: CareLinkClock.now()
                onSince != null && enabledAt < onSince -> onSince
                else -> null
            }
            if (at != null) {
                m = PumpHistoryRules.enable(m, at, PumpBtBridge.boundary(c, at))
                journal("source: enable moment set again to ${PumpBtJournal.fullDate(at)} (memory and switch were out of step)")
                if (!saveLocked(c, m)) {
                    // kept for this process all the same, saved with the next save (critic B2a, C-2)
                    memory = m
                    nextFrom = PumpHistoryRules.nextFrom(m)
                    unsaved = true
                }
            }
        }
        _state.update { it.copy(memoryLoadFailed = failed) }
        publishMemory()
        return memory ?: m
    }

    /** Saves [m]; only then it is the memory and its number the link's. false - not saved (logged). */
    private fun saveLocked(c: Context, m: PumpHistoryMemory): Boolean {
        val hs = historyStore ?: PumpBtHistoryStore(c).also { historyStore = it }
        return try {
            saveMemory(hs, m)
            memory = m
            nextFrom = PumpHistoryRules.nextFrom(m)
            unsaved = false
            true
        } catch (t: Throwable) {
            journal("source: history memory not saved (${t.javaClass.simpleName}) - this cycle stops here")
            false
        }
    }

    /** The card as the next cycle would show it (process start, a changed card), without feeding anything. */
    private suspend fun refreshCardLocked(c: Context) {
        val m = memory ?: return
        if (m.backfill != BackfillState.WAITING) return
        val zone = zone()
        val events = PumpEvents.build(m.records, m.reference, m.contiguousTo)
        val rows = PumpBtBridge.journalRows(c, m, events, zone)
        val plan = PumpBtBridge.planCycle(m, events, rows, CareLinkClock.now(), zone, firstReadComplete = false, writeCarbs = writeCarbs(c))
        if (plan.memory != m && !saveLocked(c, plan.memory)) return
        _state.update { it.copy(card = if (plan.memory.backfill == BackfillState.WAITING) plan.card else null) }
        publishMemory()
    }

    private fun publishMemory() {
        val m = memory ?: return
        _state.update { it.copy(contiguousTo = m.contiguousTo, records = m.records.size, counters = m.counters, backfill = m.backfill) }
    }

    /** What the link asks at every history read. */
    private fun historyFrom(c: Context): Long? {
        val n = if (PumpSourceSwitch.bluetoothOn(c)) nextFrom else trialSeq?.plus(1)
        lastIssuedNull = n == null
        return n
    }

    private fun startLinkNow(c: Context, pair: Boolean) {
        if (!hasPermissions(c)) {
            journal("link not started: Bluetooth permission missing")
            return
        }
        val k = key(c)
        if (k == null) {
            journal("link not started: no key database")
            return
        }
        // a key loaded since the link was made reaches a new link (a running link keeps its key until Stop)
        if (link?.isRunning != true && linkKeyHex != k.hex) link = null
        val l = link ?: PumpLink(
            c, k.db, store(c),
            journal = journal,
            logcat = { PumpBtLog.i(it) },
            publish = { ls ->
                _state.update { it.copy(link = ls, waitingSinceMs = PumpBtWindow.waitingSince(it.waitingSinceMs, ls, System.currentTimeMillis())) }
                onLinkState?.invoke(ls)
                val before = lastStage
                lastStage = ls.stage
                if (PumpBtAlertRules.handshakeDone(before, ls.stage)) handshakeDone(c)
            },
            historyFrom = { historyFrom(c) },
            onCycle = { cycle -> cycles?.trySend(cycle) },
            verbose = { store(c).verbose },
        ).also {
            link = it
            linkKeyHex = k.hex
        }
        PumpBtLog.i("link start (" + (if (pair) "pairing" else "waiting") + ")")
        l.start(pair)
    }

    /**
     * "Paired" against the phone's bonded devices (plan B2a, point 3; at [init], [enable], [recheckBond]); under the
     * source's mutex, like every write of what was told (critic B2a, N-3). The flag is never cleared here: paired, but
     * not bonded - [PumpBtSourceState.bondLost] and, with the source on and once per such case, [PumpBtAlertKind.BOND_LOST];
     * bonded again - the mark goes ([PumpBtAlertRules.bond]). Not found by the saved address counts as lost only when no
     * bonded device looks like a pump (the saved address may be a random one; critic B2a, C-4). Nothing changes while it
     * cannot be told (Bluetooth off, no permission, no address yet).
     */
    private fun checkBondedLocked(c: Context) {
        val s = store(c)
        val bonded: Boolean? = try {
            bondedDevices(c)?.let { PumpBtAlertRules.bondedFrom(s.pumpAddress, it) }
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
        // since a complete handshake the bond is there, whatever the list says, until a bond of the pump goes away
        applyBond(c, s.paired, PumpBtAlertRules.bondedAfterHandshake(bonded, bondTrusted))
    }

    /** The phone's bonded devices (address, name); null - not known (no adapter, Bluetooth off). */
    @SuppressLint("MissingPermission")
    private fun bondedFromAdapter(c: Context): List<Pair<String, String?>>? {
        val adapter = (c.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) return null
        return adapter.bondedDevices?.map { d ->
            d.address to try {
                d.name
            } catch (_: SecurityException) {
                null
            }
        } ?: emptyList()
    }

    /** [checkBondedLocked] after the phone was asked: [bonded] - null when it could not be told. */
    private fun applyBond(c: Context, paired: Boolean, bonded: Boolean?) {
        val s = store(c)
        val before = s.alerts
        val check = PumpBtAlertRules.bond(paired, bonded, before.bondLostTold)
        var told = check.told
        if (check.alert) {
            // the line once per loss in this process: the watchdog checks an undelivered loss again every round (N-2)
            val seenBefore = _state.value.bondLost
            if (!seenBefore) journal("the phone no longer has the paired pump among its bonded devices - pairing needed")
            if (!PumpSourceSwitch.bluetoothOn(c) || !sendAlert(c, PumpBtAlert(PumpBtAlertKind.BOND_LOST))) {
                told = before.bondLostTold
            } else if (seenBefore) {
                journal("the phone forgot the pump: told now")
            }
        } else if (check.lost == false && (before.bondLostTold || _state.value.bondLost)) {
            journal("the paired pump is among the phone's bonded devices again")
        }
        if (told != before.bondLostTold) s.alerts = before.copy(bondLostTold = told)
        _state.update { it.copy(pumpBonded = bonded, bondLost = check.lost ?: it.bondLost) }
    }

    internal suspend fun applyBondForTest(context: Context, paired: Boolean, bonded: Boolean?) {
        val c = attach(context)
        cycleLock.withLock { applyBond(c, paired, bonded) }
    }

    /**
     * A complete handshake: the pump is paired - the "phone forgot the pump" mark goes at once, and until a bond of the
     * pump goes away (bond state "none") a check that does not find it does not bring the mark back (point 8). The job
     * is for tests.
     */
    internal fun handshakeDone(context: Context): Job {
        val c = attach(context)
        bondTrusted = true
        return scope.launch {
            try {
                cycleLock.withLock { applyBond(c, store(c).paired, true) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                journal("source: bond not marked after the handshake (${t.javaClass.simpleName})")
            }
        }
    }

    /** A bond of the pump went away (bond state "none"): checks find the truth again. Tests. */
    internal fun bondWentAway() {
        bondTrusted = false
    }

    /**
     * Bond changes of the phone: checked again (system action, registered like upstream's receivers). A change of our
     * pump is written to the link journal while the link is not running (a running link writes it itself).
     */
    private fun watchBonds(c: Context) {
        if (bondReceiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val device: BluetoothDevice? = try {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                } catch (_: Throwable) {
                    null
                }
                val name = try {
                    device?.name
                } catch (_: SecurityException) {
                    null
                }
                val s = store(c)
                val ours = PumpLinkRules.ourPumpBond(device?.address, name, s.pumpAddress)
                PumpLinkRules.sourceBondLine(
                    linkRunning = link?.isRunning == true, ours = ours,
                    device = PumpLinkRules.deviceTag(device?.address, s.verbose),
                    before = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, -1), now = state,
                    reason = intent.getIntExtra(PumpLink.EXTRA_BOND_REASON, -1),
                )?.let { journal(it) }
                if (state == BluetoothDevice.BOND_NONE && ours) bondTrusted = false
                if (state == BluetoothDevice.BOND_NONE || state == BluetoothDevice.BOND_BONDED) recheckBond(c)
            }
        }
        try {
            c.registerReceiver(r, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            bondReceiver = r
        } catch (t: Throwable) {
            PumpBtLog.w("bond receiver not registered (${t.javaClass.simpleName})")
        }
    }

    private fun writeCarbs(c: Context): Boolean = try {
        CareLinkSettings(c).writeCarbs
    } catch (_: Throwable) {
        true
    }

    /** true - handed to the notifier without an exception; false - no notifier, or it threw (then not marked as told). */
    private fun sendAlert(c: Context, a: PumpBtAlert): Boolean {
        val to = alert ?: return false
        return try {
            to(c, a)
            true
        } catch (t: Throwable) {
            PumpBtLog.w("pump alert not shown (${t.javaClass.simpleName})")
            false
        }
    }

    /**
     * [block] on the main thread once the memory is loaded: the link's number snapshot comes from the loaded memory
     * before the link starts (critic of the joints 1, N-10).
     */
    private fun afterLoad(c: Context, block: () -> Unit) {
        if (memory != null) return onMain(block)
        scope.launch {
            try {
                cycleLock.withLock { memoryLocked(c) }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                journal("source: history memory not loaded (${t.javaClass.simpleName})")
            }
            onMain(block)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == main.looper) block() else main.post(block)
    }

    /** Back to the start (tests): the memory is read from its file again, as after the death of the process. */
    internal fun resetForTest() {
        val l = link
        main.post { l?.stop() }
        link = null
        key = null
        linkKeyHex = null
        memory = null
        unsaved = false
        nextFrom = null
        trialSeq = null
        lastIssuedNull = true
        lastWarnings = emptySet()
        historyStore = null
        store?.awaitWrites()
        store = null
        app = null
        cycles?.close()
        cycles = null
        alert = null
        intake = { c, e, p, n -> CareLinkIntake.accept(c, IntakeSource.BLUETOOTH, e, p, n) }
        saveMemory = { st, m -> st.save(m) }
        lastStage = null
        bondTrusted = false
        bondReceiver = null
        sensorBluetooth = { runCatching { Natives.getusebluetooth() }.getOrDefault(true) }
        zone = { ZoneId.systemDefault() }
        bondedDevices = { bondedFromAdapter(it) }
        onLinkState = null
        _state.value = PumpBtSourceState()
    }

    internal fun historyFromForTest(context: Context): Long? = historyFrom(attach(context))

    /** A cycle through the link's queue (tests). */
    internal fun offerCycleForTest(context: Context, cycle: PumpCycle) {
        attach(context)
        cycles?.trySend(cycle)
    }
}

/**
 * What the source has told the person (kept in [PumpBtStore.alerts]): unknown record types and unparsed bolus or meal
 * types, boluses whose result was missing, the last clock-drift notification and whether the drift was gone since.
 */
internal data class PumpBtAlertMemory(
    val unknownTypes: Set<Int> = emptySet(),
    val unparsedTypes: Set<Int> = emptySet(),
    val noResultKeys: Set<String> = emptySet(),
    val driftAtMs: Long? = null,
    /** "The phone forgot the pump" was told for the current loss of the bond. */
    val bondLostTold: Boolean = false,
)

/** [PumpBtAlertRules.bond]: [lost] - null: not known now (keep the mark as it is); [told] - the new "told" mark. */
internal data class BondCheck(val lost: Boolean?, val alert: Boolean, val told: Boolean)

/** The once-only rules of the source's notifications (critic of the joints 1, N-6, N-7). Pure functions. */
internal object PumpBtAlertRules {

    /** "Clocks differ" again not earlier than this after the last one. */
    const val DRIFT_REPEAT_MS = 24 * 3_600_000L

    /**
     * The notifications of one cycle and the memory after it: a type once ([unknownTypes], [unparsedTypes]); a bolus
     * key once ([noResultKeys] - boluses running too long now; keys no longer among [bolusKeys] are forgotten); the
     * drift ([drift]: true - there, false - gone, null - not known this cycle) once a day while it is there.
     */
    /**
     * The bond against "paired": [bonded] - the saved address among the phone's bonded devices (null - not known).
     * Paired and not bonded - lost, told once per loss; bonded - not lost, the next loss is told again; never paired -
     * not lost.
     */
    /**
     * [after] ([decide]) without what was not delivered ([failed]): a type, a bolus or the drift not told stays untold,
     * so the next cycle tries again (critic B2a, C-3).
     */
    fun keepUndelivered(before: PumpBtAlertMemory, after: PumpBtAlertMemory, failed: List<PumpBtAlert>): PumpBtAlertMemory {
        if (failed.isEmpty()) return after
        var m = after
        for (a in failed) {
            m = when (a.kind) {
                PumpBtAlertKind.UNKNOWN_RECORD -> if (a.recordType in before.unknownTypes) m else m.copy(unknownTypes = m.unknownTypes - setOfNotNull(a.recordType))
                PumpBtAlertKind.UNPARSED_BOLUS -> if (a.recordType in before.unparsedTypes) m else m.copy(unparsedTypes = m.unparsedTypes - setOfNotNull(a.recordType))
                PumpBtAlertKind.NO_RESULT_BOLUS -> m.copy(noResultKeys = m.noResultKeys intersect before.noResultKeys)
                PumpBtAlertKind.CLOCK_DRIFT -> m.copy(driftAtMs = before.driftAtMs)
                else -> m
            }
        }
        return m
    }

    /**
     * Is the pump bonded: [address] (saved at the handshake) among [bonded] (address, name), or - not found by it - any
     * bonded device with a pump-like name (the saved address may be a random one, the bond keeps the identity address;
     * critic B2a, C-4). null - no address yet (cannot be told).
     */
    fun bondedFrom(address: String, bonded: List<Pair<String, String?>>): Boolean? {
        if (address.isEmpty()) return null
        return bonded.any { it.first.equals(address, ignoreCase = true) } || bonded.any { PumpLinkRules.pumpLikeName(it.second) }
    }

    /** A check of the bonded devices ([bonded]) after a complete handshake ([trusted]): "not found" counts as bonded. */
    fun bondedAfterHandshake(bonded: Boolean?, trusted: Boolean): Boolean? = if (bonded == false && trusted) true else bonded

    /** The link reached reading right after its handshake (from HANDSHAKE): a complete handshake. */
    fun handshakeDone(before: PumpBtStage?, now: PumpBtStage): Boolean =
        before == PumpBtStage.HANDSHAKE && (now == PumpBtStage.READING || now == PumpBtStage.DATA_READ)

    fun bond(paired: Boolean, bonded: Boolean?, told: Boolean): BondCheck = when {
        bonded == null -> BondCheck(lost = null, alert = false, told = told)
        bonded -> BondCheck(lost = false, alert = false, told = false)
        paired -> BondCheck(lost = true, alert = !told, told = true)
        else -> BondCheck(lost = false, alert = false, told = told)
    }

    fun decide(
        m: PumpBtAlertMemory, unknownTypes: Set<Int>, unparsedTypes: Set<Int>, noResultKeys: Set<String>,
        bolusKeys: Set<String>, drift: Boolean?, nowMs: Long,
    ): Pair<List<PumpBtAlert>, PumpBtAlertMemory> {
        val out = ArrayList<PumpBtAlert>()
        for (t in unknownTypes.sorted()) if (t !in m.unknownTypes) out += PumpBtAlert(PumpBtAlertKind.UNKNOWN_RECORD, t)
        for (t in unparsedTypes.sorted()) if (t !in m.unparsedTypes) out += PumpBtAlert(PumpBtAlertKind.UNPARSED_BOLUS, t)
        if ((noResultKeys - m.noResultKeys).isNotEmpty()) out += PumpBtAlert(PumpBtAlertKind.NO_RESULT_BOLUS)
        var driftAt = m.driftAtMs
        // once a day while it lasts (critic B2a, N-5)
        if (drift == true && (driftAt == null || nowMs - driftAt >= DRIFT_REPEAT_MS)) {
            out += PumpBtAlert(PumpBtAlertKind.CLOCK_DRIFT)
            driftAt = nowMs
        }
        val after = PumpBtAlertMemory(
            unknownTypes = m.unknownTypes + unknownTypes,
            unparsedTypes = m.unparsedTypes + unparsedTypes,
            noResultKeys = (m.noResultKeys intersect bolusKeys) + noResultKeys,
            driftAtMs = driftAt,
            bondLostTold = m.bondLostTold,
        )
        return out to after
    }
}
