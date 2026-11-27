package tk.glucodata.mod.pumpbt.trial

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import tk.glucodata.R
import tk.glucodata.data.HistoryDatabase
import tk.glucodata.data.journal.JournalEntryEntity
import tk.glucodata.data.journal.JournalEntryType
import tk.glucodata.drivers.api.ApiIobSnapshot
import tk.glucodata.mod.carelink.CareLinkClock
import tk.glucodata.mod.carelink.CareLinkNotifier
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.pumpbt.BackfillState
import tk.glucodata.mod.pumpbt.PumpBtHistoryStore
import tk.glucodata.mod.pumpbt.PumpBtSource
import tk.glucodata.mod.pumpbt.PumpHistoryMemory
import tk.glucodata.mod.pumpbt.PumpWarning
import tk.glucodata.mod.pumpbt.ble.PumpLinkRules
import tk.glucodata.mod.pumpbt.core.ActiveBasalRate
import tk.glucodata.mod.pumpbt.core.HistoryRead
import tk.glucodata.mod.pumpbt.core.HistoryReadStatus
import tk.glucodata.mod.pumpbt.core.InsulinOnBoard
import tk.glucodata.mod.pumpbt.core.PumpCycle
import tk.glucodata.mod.pumpbt.core.PumpIdentity
import tk.glucodata.mod.pumpbt.core.PumpStatus
import tk.glucodata.mod.pumpbt.core.RawHistoryRecord
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * JugglucoNGmod, the made-up pump over Bluetooth (series part 2, part P). This file lives in Common/src/modTrial/java,
 * which mod/mod-init.gradle adds to the phone `mod` build only with -PmodRelease=0 (test builds, emulator only): a
 * candidate for the phone does not contain it, and mod/check-apk.py checks that by this class's type descriptor and the
 * marker strings below (not by the class name ModPumpStarter keeps as a string).
 *
 * [tk.glucodata.mod.pumpbt.ModPumpStarter] finds this class by name on every process start (before the source starts);
 * [install] registers the command receiver - exported, but only a sender holding android.permission.DUMP (adb shell)
 * gets in:
 *   adb shell am broadcast -a tk.glucodata.ng.mod.PUMPBT_TRIAL -p tk.glucodata.ng.mod --es cmd cycle
 * Each result is a line of the log (tag below) and the broadcast's result data (printed by `am broadcast`).
 *
 * The whole way but the radio: the made-up pump keeps its history as records in bytes (IDD history layout, OpenMinimed;
 * made-up values only) and its status, active insulin and active basal as the bytes the link would decrypt; a cycle is
 * parsed with the link's own parsers, read from the number the source gives the link and closed with the link's own
 * history rules ([PumpLinkRules.historyPlan], [PumpLinkRules.historyOutcome]), then handed to
 * [PumpBtSource.acceptCycle] like a cycle of the link: history memory, events, bridge, first catch-up card, journal
 * intake, basal writer, active insulin, notifications. The pump's clock follows [CareLinkClock] (the clock of the pump
 * writers) plus its own drift.
 *
 * Turning the source on: [PumpBtSource.enable], the call of the window's "Permanent operation" - only without the
 * window's own gate (key database and Bluetooth: an emulator has no pump key), which exists in test builds alone.
 *
 * Commands (`--es cmd <name> [--es arg <numbers separated by : or ,>]`):
 *   reset, on, off, clock <+-minutes>, old, bolus[:U[:g]], bolus-run[:U], bolus-done[:U], basal[:U/h],
 *   temp[:U/h[:min]], temp-pct[:%[:min]], temp-end, stop, resume, iob[:U], drift[:minutes], cycle, repeat,
 *   slow[:seconds], nodata, state, check <key=value,...> (CHECK-OK / CHECK-FAILED; README of the scenarios).
 * android.util.Log.println, not Log.i: upstream's R8 rules (Common/proguard-rules.log) strip Log.i/w/d/v in release
 * builds, `mod` included.
 */
object PumpBtTrialEntry {

    private const val ACTION = "tk.glucodata.ng.mod.PUMPBT_TRIAL"
    private const val TAG = "ModPumpBtTrial"
    private const val SENDER_PERMISSION = "android.permission.DUMP"

    /** A broadcast receiver may hold its result this long; a longer command goes on and logs its result later. */
    private const val ANSWER_WITHIN_MS = 8_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val pump = TrialPump()

    @Volatile
    private var installed = false

    /** The history memory's real save, kept when `slow` puts its delayed one in front. */
    @Volatile
    private var realSave: ((PumpBtHistoryStore, PumpHistoryMemory) -> Unit)? = null

    @JvmStatic
    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        ContextCompat.registerReceiver(app, Receiver(), IntentFilter(ACTION), SENDER_PERMISSION, null,
            ContextCompat.RECEIVER_EXPORTED)
        Log.println(Log.INFO, TAG, "pump trial installed: the made-up Bluetooth pump, commands by am broadcast")
    }

    private class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cmd = intent.getStringExtra("cmd")
            val arg = intent.getStringExtra("arg")
            val app = context.applicationContext
            val pending = goAsync()
            scope.launch {
                val work = scope.async {
                    try {
                        mutex.withLock { execute(app, cmd?.trim()?.lowercase(Locale.ROOT).orEmpty(), arg?.trim().orEmpty()) }
                    } catch (e: IllegalArgumentException) {
                        "error: ${e.message}"
                    } catch (t: Throwable) {
                        "error: ${t.javaClass.simpleName}"
                    }
                }
                val result = try {
                    withTimeout(ANSWER_WITHIN_MS) { work.await() }
                } catch (_: TimeoutCancellationException) {
                    scope.launch { Log.println(Log.INFO, TAG, "$cmd (late): ${work.await()}") }
                    "$cmd: still running, result in the log"
                }
                Log.println(Log.INFO, TAG, result)
                try {
                    pending.resultData = result
                } catch (_: Throwable) {
                    // not an ordered broadcast: the log line is the result
                }
                pending.finish()
            }
        }
    }

    /** Numbers of [arg] (separated by blanks, `:`, `,` or `;`); a wrong one throws with the reason. */
    private fun numbers(cmd: String, arg: String, max: Int): List<Double> {
        val parts = arg.split(Regex("[\\s:,;]+")).filter { it.isNotEmpty() }
        if (parts.size > max) throw IllegalArgumentException("$cmd: at most $max number(s)")
        return parts.map { it.removePrefix("+").toDoubleOrNull() ?: throw IllegalArgumentException("$cmd: '$it' is not a number") }
    }

    private suspend fun execute(app: Context, cmd: String, arg: String): String {
        val now = CareLinkClock.now()
        fun n(i: Int, default: Double, max: Int = 2) = numbers(cmd, arg, max).getOrNull(i) ?: default
        return when (cmd) {
            "reset" -> {
                pump.reset()
                "reset ok: a new made-up pump (${pump.serialNo}), history empty"
            }
            "on" -> {
                PumpBtSource.init(app).join()
                PumpBtSource.enable(app).join()
                "on: " + summary(app)
            }
            "off" -> {
                PumpBtSource.disable(app).join()
                "off: " + summary(app)
            }
            "clock" -> {
                val m = numbers(cmd, arg, 1).singleOrNull()?.roundToLong() ?: throw IllegalArgumentException("clock: minutes (+40, -60, 0)")
                if (m !in -1440L..1440L) throw IllegalArgumentException("clock: -1440..1440 minutes")
                val offset = m * 60_000L
                CareLinkClock.now = if (offset == 0L) {
                    { System.currentTimeMillis() }
                } else {
                    { System.currentTimeMillis() + offset }
                }
                "clock: ${if (m > 0) "+" else ""}$m min"
            }
            "old" -> pump.old(now)
            "bolus" -> pump.bolus(now, units = n(0, 2.0), grams = n(1, 0.0).roundToInt())
            "bolus-run" -> pump.bolusRun(now, n(0, 3.0, 1))
            "bolus-done" -> pump.bolusDone(now, numbers(cmd, arg, 1).singleOrNull())
            "basal" -> pump.basal(n(0, 0.8, 1))
            "temp" -> pump.temp(ActiveBasalRate.TBR_ABSOLUTE, n(0, 1.2), n(1, 60.0).roundToInt(), now)
            "temp-pct" -> pump.temp(ActiveBasalRate.TBR_RELATIVE, n(0, 150.0), n(1, 60.0).roundToInt(), now)
            "temp-end" -> pump.tempEnd()
            "stop" -> pump.stop(now)
            "resume" -> pump.resume(now)
            "iob" -> pump.iob(n(0, 1.5, 1))
            "drift" -> pump.drift(n(0, 120.0, 1).roundToLong())
            "cycle", "read" -> {
                val cycle = pump.cycle(PumpBtSource.historyFromForTest(app), now)
                PumpBtSource.acceptCycle(app, cycle)
                "cycle: ${describe(cycle)}; " + summary(app)
            }
            "repeat" -> {
                val cycle = pump.repeat(now) ?: return "repeat: no cycle yet"
                PumpBtSource.acceptCycle(app, cycle)
                "repeat: ${describe(cycle)}; " + summary(app)
            }
            "slow" -> {
                val ms = (n(0, 15.0, 1) * 1000).roundToLong().coerceIn(0L, 60_000L)
                slowNextSave(ms)
                "slow: the save of the next \"Write\" waits ${ms / 1000} s"
            }
            "nodata" -> {
                withContext(Dispatchers.IO) { CareLinkNotifier.checkNoData(app) }
                "nodata: " + summary(app)
            }
            "state" -> "state: " + summary(app)
            "check" -> check(app, arg)
            else -> throw IllegalArgumentException("unknown command '$cmd'")
        }
    }

    /**
     * `slow`: the save of the first catch-up's "Write" waits [ms] first (it stays unanswered that long: the window's
     * waiting state can be watched across a turned screen), then the real save is back. Only that save: the memory to
     * save is confirmed while the source still shows the card waiting (the state is published after the save) - other
     * saves meanwhile (a cycle, a load) go through at once and do not use it up (critic P, N-4).
     */
    private fun slowNextSave(ms: Long) {
        val real = realSave ?: PumpBtSource.saveMemory.also { realSave = it }
        val armed = AtomicBoolean(true)
        PumpBtSource.saveMemory = { store, m ->
            val confirmSave = m.backfill == BackfillState.CONFIRMED && PumpBtSource.state.value.backfill == BackfillState.WAITING
            if (confirmSave && armed.getAndSet(false)) {
                Log.println(Log.INFO, TAG, "slow: history memory save waits ${ms / 1000} s")
                Thread.sleep(ms)
                PumpBtSource.saveMemory = real
            }
            real(store, m)
        }
    }

    private fun describe(c: PumpCycle): String {
        val seqs = c.records.map { it.sequence }
        return "${c.records.size} records" + (if (seqs.isEmpty()) "" else " (#${seqs.min()}-#${seqs.max()})") +
            ", history ${c.history.status.name.lowercase(Locale.ROOT)}"
    }

    /**
     * What the trial checks and prints: the source, the journal rows of the pump (counts and values: the sum of the
     * doses, the rate of the newest basal row, the newest stop note), the plate, "no data".
     */
    private suspend fun facts(app: Context): LinkedHashMap<String, String> {
        val s = PumpBtSource.state.value
        // oldest first (by time, then id): the newest row of a type is its last
        val rows = withContext(Dispatchers.IO) { HistoryDatabase.getInstance(app).journalDao().getEntries() }
            .filter { it.sourceRecordId?.startsWith("carelink:") == true }
        fun count(t: JournalEntryType) = rows.count { it.entryType == t.storageValue }.toString()
        fun of(t: JournalEntryType) = rows.filter { it.entryType == t.storageValue }
        val insulinSum = of(JournalEntryType.INSULIN).sumOf { (it.amount ?: 0f).toDouble() }
        val basalLast = of(JournalEntryType.TEMP_BASAL).lastOrNull()?.amount
        // the plate reads upstream's snapshot with the phone's real clock
        val iob = ApiIobSnapshot.fresh(System.currentTimeMillis())?.iobUnits
        return linkedMapOf(
            "enabled" to if (s.enabled) "1" else "0",
            "intake" to (s.lastIntake?.name?.lowercase(Locale.ROOT) ?: "none"),
            "memory" to (s.contiguousTo?.toString() ?: "none"),
            "records" to s.records.toString(),
            "backfill" to s.backfill.name.lowercase(Locale.ROOT),
            "card" to (s.card?.toWrite?.size?.toString() ?: "none"),
            "warnings" to (s.warnings.joinToString("+") { it.name.lowercase(Locale.ROOT) }.ifEmpty { "-" }),
            "insulin" to count(JournalEntryType.INSULIN),
            "insulin-sum" to "%.2f".format(Locale.ROOT, insulinSum),
            "carbs" to count(JournalEntryType.CARBS),
            "basal" to count(JournalEntryType.TEMP_BASAL),
            "basal-last" to (basalLast?.let { "%.3f".format(Locale.ROOT, it.toDouble()) } ?: "none"),
            "notes" to count(JournalEntryType.NOTE),
            "stop" to stopNote(app, of(JournalEntryType.NOTE)),
            "iob" to (iob?.let { "%.2f".format(Locale.ROOT, it) } ?: "none"),
            "nodata" to if (withContext(Dispatchers.IO) { CareLinkStateStore(app).noDataShown }) "1" else "0",
        )
    }

    /**
     * The newest stop note of the basal writer (`carelink:stop:` key): `open` - its text is the writer's "Stop HH:MM–…",
     * `closed` - "Stop HH:MM–HH:MM" (a date before the end on another day), `other` - neither (changed by hand), `none`.
     * The patterns come from the writer's own strings, so the app's language does not matter.
     */
    private fun stopNote(app: Context, notes: List<JournalEntryEntity>): String {
        val text = notes.lastOrNull { it.sourceRecordId?.startsWith("carelink:stop:") == true }?.note ?: return "none"
        val time = "\\d\\d:\\d\\d"
        fun pattern(s: String) = Regex(s.split('\u0001').joinToString(time) { start ->
            start.split('\u0002').joinToString("(\\d\\d\\.\\d\\d )?$time") { Regex.escape(it) }
        })
        return when {
            pattern(app.getString(R.string.mod_carelink_note_stop_open, "\u0001")).matches(text) -> "open"
            pattern(app.getString(R.string.mod_carelink_note_stop, "\u0001", "\u0002")).matches(text) -> "closed"
            else -> "other"
        }
    }

    private suspend fun summary(app: Context): String = facts(app).entries.joinToString(" ") { (k, v) -> "$k=$v" }

    /**
     * `check insulin=2,carbs=1,basal-min=2,warning=clock_drift,nowarning=clock_drift`: `key=value` - equal (numbers as
     * numbers), `key-min=value` - at least, `warning=` / `nowarning=` - among the warnings or not (a name that is no
     * warning fails). Value keys: `insulin-sum` (U), `basal-last` (U/h), `stop` (open / closed / other / none). The answer starts with
     * CHECK-OK or CHECK-FAILED (a scenario pipes `am broadcast` into `grep -F CHECK-OK`).
     */
    private suspend fun check(app: Context, arg: String): String {
        val f = facts(app)
        val wants = arg.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
        if (wants.isEmpty()) throw IllegalArgumentException("check: key=value,... (${f.keys.joinToString()})")
        val warnings = f.getValue("warnings").split('+').toSet()
        // a misspelt name would pass `nowarning=` always: an unknown one fails the check
        val known = PumpWarning.entries.map { it.name.lowercase(Locale.ROOT) }
        val bad = ArrayList<String>()
        for (w in wants) {
            val (key, want) = w.split('=', limit = 2).takeIf { it.size == 2 } ?: throw IllegalArgumentException("check: '$w' is not key=value")
            when {
                (key == "warning" || key == "nowarning") && want !in known ->
                    bad += "unknown warning '$want' (${known.joinToString("/")})"
                key == "warning" -> if (want !in warnings) bad += "no warning $want"
                key == "nowarning" -> if (want in warnings) bad += "warning $want"
                key.endsWith("-min") -> {
                    val k = key.removeSuffix("-min")
                    val have = f[k] ?: throw IllegalArgumentException("check: unknown key '$k'")
                    val h = have.toDoubleOrNull()
                    val wn = want.toDoubleOrNull() ?: throw IllegalArgumentException("check: '$want' is not a number")
                    if (h == null || h < wn) bad += "$k is $have, not at least $want"
                }
                else -> {
                    val have = f[key] ?: throw IllegalArgumentException("check: unknown key '$key'")
                    val h = have.toDoubleOrNull()
                    val wn = want.toDoubleOrNull()
                    val same = if (h != null && wn != null) abs(h - wn) < 0.005 else have == want
                    if (!same) bad += "$key is $have, not $want"
                }
            }
        }
        val all = f.entries.joinToString(" ") { (k, v) -> "$k=$v" }
        return if (bad.isEmpty()) "CHECK-OK ${wants.joinToString(",")} ($all)" else "CHECK-FAILED ${bad.joinToString("; ")} ($all)"
    }
}

/**
 * The made-up pump: history records in bytes, its status. Made-up doses and a made-up serial number only. Not thread
 * safe: the commands run one at a time ([PumpBtTrialEntry]'s mutex).
 */
private class TrialPump {

    private class Running(val id: Int, val units: Double)

    private data class Temp(val type: Int, val value: Double, val minutes: Int, val startedAtMs: Long)

    var serialNo = 1
        private set

    private val records = ArrayList<RawHistoryRecord>()
    private var nextSeq = FIRST_SEQ
    private var nextBolusId = 1
    private var running: Running? = null
    private var lastRef: LocalDateTime? = null
    private var refDrift = 0L
    private var lastCycle: PumpCycle? = null

    private var therapy = PumpStatus.RUN
    private var reservoir = 150.0
    private var battery = 80
    private var iob = 0.0
    private var basal = 0.8
    private var temp: Temp? = null
    private var driftMin = 0L

    private val serialHash: String get() = PumpIdentity.hash("TRIAL-PUMP-%04d".format(Locale.ROOT, serialNo))

    fun reset() {
        serialNo++
        records.clear()
        nextSeq = FIRST_SEQ
        nextBolusId = 1
        running = null
        lastRef = null
        refDrift = 0L
        lastCycle = null
        therapy = PumpStatus.RUN
        reservoir = 150.0
        battery = 80
        iob = 0.0
        basal = 0.8
        temp = null
        driftMin = 0L
    }

    /** The pump's local clock (no zone, to the second): the phone's [CareLinkClock] in the phone's zone plus the drift. */
    private fun pumpTime(ms: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).plusMinutes(driftMin).withNano(0)

    // MARK: - History records

    private fun record(type: Int, offset: Int, body: ByteArray): RawHistoryRecord =
        RawHistoryRecord.fromPlain(u16(type) + u32(nextSeq++) + u16(offset) + body)!!

    /** A periodic reference time (0xF00E, reason 0x3C). */
    private fun reference(at: LocalDateTime) {
        records += record(0xF00E, 0, byteArrayOf(0x3C) + u16(at.year) + bytes(at.monthValue, at.dayOfMonth, at.hour, at.minute, at.second))
        lastRef = at
        refDrift = driftMin
    }

    /** A record at [at]; a new reference time first when there is none, [at] is before it or over an hour after it. */
    private fun add(type: Int, at: LocalDateTime, body: ByteArray) {
        val ref = lastRef
        if (ref == null || at.isBefore(ref) || Duration.between(ref, at).seconds > 3_600 || refDrift != driftMin) reference(at)
        records += record(type, Duration.between(lastRef!!, at).seconds.toInt(), body)
    }

    /** Bolus part 1 (0x005A programmed / 0x0069 delivered): id, type 0x33 (normal), fast dose, no extended, 0 min. */
    private fun bolusBody(id: Int, units: Double) = u16(id) + bytes(0x33) + f32(units) + f32(0.0) + u16(0)

    private fun programmed(at: LocalDateTime, id: Int, units: Double, calculator: Boolean) {
        add(0x005A, at, bolusBody(id, units))
        // part 2: flags (bit 2 - activation type), activation 0x3C by the calculator / 0x33 by hand
        add(0x0066, at, bytes(0x04, if (calculator) 0x3C else 0x33))
    }

    private fun delivered(at: LocalDateTime, id: Int, units: Double) {
        add(0x0069, at, bolusBody(id, units))
        // part 2: flags (bit 1 - end reason), start offset 0 s, end reason 0x0F (as the 740G writes it)
        add(0x0096, at, bytes(0x02) + u32(0) + bytes(0x0F))
    }

    private fun meal(at: LocalDateTime, grams: Int) = add(0xF005, at, sfloat(grams))

    /** A whole bolus: its meal 2 s before (calculator), programmed, delivered 90 s later. */
    private fun wholeBolus(start: LocalDateTime, units: Double, grams: Int): Int {
        val id = nextBolusId++
        if (grams > 0) meal(start.minusSeconds(2), grams)
        programmed(start, id, units, calculator = grams > 0)
        delivered(start.plusSeconds(90), id, units)
        return id
    }

    /** Old events, before the source is turned on: carbs 30 g with 3 U 3 h ago, 1.5 U by hand 2 h ago. */
    fun old(nowMs: Long): String {
        if (records.isNotEmpty()) throw IllegalArgumentException("old: only on an empty pump (reset first)")
        val now = pumpTime(nowMs)
        wholeBolus(now.minusHours(3), 3.0, 30)
        wholeBolus(now.minusHours(2), 1.5, 0)
        return "old: 2 boluses (3 U with 30 g, 1.5 U) 3 h and 2 h ago, records #$FIRST_SEQ-#${nextSeq - 1}"
    }

    /** A bolus delivered just now (started 90 s ago), with its meal when [grams] > 0. */
    fun bolus(nowMs: Long, units: Double, grams: Int): String {
        checkUnits(units)
        if (grams !in 0..300) throw IllegalArgumentException("bolus: 0..300 g")
        val id = wholeBolus(pumpTime(nowMs).minusSeconds(90), units, grams)
        return "bolus #$id: $units U" + (if (grams > 0) " with $grams g" else "") + ", delivered"
    }

    /** A bolus programmed now, still running: no "delivered" yet. */
    fun bolusRun(nowMs: Long, units: Double): String {
        checkUnits(units)
        if (running != null) throw IllegalArgumentException("bolus-run: a bolus runs already (bolus-done first)")
        val id = nextBolusId++
        programmed(pumpTime(nowMs), id, units, calculator = false)
        running = Running(id, units)
        return "bolus-run #$id: $units U programmed, running"
    }

    /** The running bolus ends now: delivered [units] (all of it when null). */
    fun bolusDone(nowMs: Long, units: Double?): String {
        val r = running ?: throw IllegalArgumentException("bolus-done: no bolus runs (bolus-run first)")
        val given = units ?: r.units
        if (given < 0 || given > r.units) throw IllegalArgumentException("bolus-done: 0..${r.units} U")
        delivered(pumpTime(nowMs), r.id, given)
        running = null
        return "bolus-done #${r.id}: $given of ${r.units} U delivered"
    }

    fun basal(rate: Double): String {
        if (rate !in 0.0..10.0) throw IllegalArgumentException("basal: 0..10 U/h")
        basal = rate
        return "basal: $rate U/h"
    }

    fun temp(type: Int, value: Double, minutes: Int, nowMs: Long): String {
        if (value !in 0.0..500.0 || minutes !in 1..1440) throw IllegalArgumentException("temp: value 0..500, 1..1440 min")
        temp = Temp(type, value, minutes, nowMs)
        return "temp: $value " + (if (type == ActiveBasalRate.TBR_RELATIVE) "%" else "U/h") + " for $minutes min"
    }

    fun tempEnd(): String {
        temp = null
        return "temp-end: no temporary basal"
    }

    /** Delivery stopped by the person: 0xF00A (reason 0x02) and the therapy context with the rate and the reason. */
    fun stop(nowMs: Long): String {
        val at = pumpTime(nowMs)
        add(0xF00A, at, bytes(0x02))
        add(0xF004, at, bytes(0x0A) + f32(basal) + bytes(0x02))
        therapy = PumpStatus.STOP
        return "stop: delivery stopped"
    }

    /** Delivery restarted by the person: 0xF00B (reason 0x01). */
    fun resume(nowMs: Long): String {
        add(0xF00B, pumpTime(nowMs), bytes(0x01))
        therapy = PumpStatus.RUN
        return "resume: delivery runs"
    }

    fun iob(units: Double): String {
        if (units !in 0.0..50.0) throw IllegalArgumentException("iob: 0..50 U")
        iob = units
        return "iob: $units U"
    }

    /** The pump's clock [minutes] off the phone's: the next cycle writes a reference time by that clock. */
    fun drift(minutes: Long): String {
        if (minutes !in -1440L..1440L) throw IllegalArgumentException("drift: -1440..1440 minutes")
        driftMin = minutes
        return "drift: pump clock ${if (minutes > 0) "+" else ""}$minutes min"
    }

    private fun checkUnits(units: Double) {
        if (units <= 0.0 || units > 25.0) throw IllegalArgumentException("units: above 0, at most 25")
    }

    // MARK: - A cycle

    /**
     * One read of the link: a periodic reference time when the last is over 30 min old (or the clock drift changed),
     * the history from [from] (the source's number; null - from the oldest) by the link's own rules, the status, active
     * insulin and active basal from their bytes.
     */
    fun cycle(from: Long?, nowMs: Long): PumpCycle {
        val now = pumpTime(nowMs)
        val ref = lastRef
        if (ref == null || refDrift != driftMin || Duration.between(ref, now).toMinutes() >= 30) reference(now)
        val oldest = records.firstOrNull()?.sequence
        val newest = records.lastOrNull()?.sequence
        val ok = PumpLinkRules.EndAnswer.SUCCESS
        val none = PumpLinkRules.EndAnswer.NO_RECORDS
        val plan = PumpLinkRules.historyPlan(from, if (oldest != null) ok else none, oldest, if (newest != null) ok else none, newest)
        val got: List<RawHistoryRecord>
        val history: HistoryRead
        when (plan) {
            is PumpLinkRules.HistoryPlan.Read -> {
                got = records.filter { it.sequence in plan.from..plan.to }
                history = PumpLinkRules.historyOutcome(PumpLinkRules.HistoryEnding.DONE, plan.from, plan.to, plan.oldest, plan.newest, got.map { it.sequence })
            }
            is PumpLinkRules.HistoryPlan.NothingToRead -> {
                got = emptyList()
                history = PumpLinkRules.historyOutcome(PumpLinkRules.HistoryEnding.DONE, null, null, plan.oldest, plan.newest, emptyList())
            }
            is PumpLinkRules.HistoryPlan.Failed -> {
                got = emptyList()
                history = HistoryRead(HistoryReadStatus.FAILED, oldestOnPump = plan.oldest)
            }
        }
        val t = temp?.takeIf { nowMs < it.startedAtMs + it.minutes * 60_000L }
        if (t == null) temp = null
        val status = PumpStatus.parse(bytes(therapy, PumpStatus.OPERATIONAL_READY) + f32(reservoir) + bytes(0x01, 0x01, 0x00))
        val active = InsulinOnBoard.parse(u16(0x03FC) + bytes(0x01) + f32(iob) + u16(180))
        val basalAnswer = ActiveBasalRate.parse(
            u16(0x036A) + bytes(if (t != null) 0x01 else 0x00, 1) + f32(basal) + if (t == null) ByteArray(0) else {
                val left = (t.minutes - ((nowMs - t.startedAtMs) / 60_000L).toInt()).coerceAtLeast(0)
                bytes(t.type) + f32(t.value) + u16(t.minutes) + u16(left)
            },
        )
        val cycle = PumpCycle(
            readAtMs = nowMs,
            serialHash = serialHash,
            therapyState = status.therapyControlState,
            reservoirUnits = status.reservoirUnits,
            iobUnits = active.units,
            basalRate = basalAnswer.unitsPerHour,
            basalRead = true,
            tempBasalRate = basalAnswer.tempBasalValue,
            tempBasalType = basalAnswer.tempBasalType,
            tempBasalMinutesLeft = basalAnswer.tempBasalRemainingMinutes,
            batteryPercent = battery,
            records = got,
            history = history,
        )
        lastCycle = cycle
        return cycle
    }

    /** The last cycle once more (the same records and answers), read now. */
    fun repeat(nowMs: Long): PumpCycle? = lastCycle?.copy(readAtMs = nowMs)

    companion object {
        const val FIRST_SEQ = 1000L

        fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

        fun u16(v: Int) = bytes(v and 0xFF, (v shr 8) and 0xFF)

        fun u32(v: Long) = bytes((v and 0xFF).toInt(), ((v shr 8) and 0xFF).toInt(), ((v shr 16) and 0xFF).toInt(), ((v shr 24) and 0xFF).toInt())

        /** FLOAT (IEEE-11073): 24-bit mantissa of thousandths, exponent -3, little-endian. */
        fun f32(value: Double): ByteArray {
            val m = (value * 1000).roundToInt()
            return bytes(m and 0xFF, (m shr 8) and 0xFF, (m shr 16) and 0xFF, 0xFD)
        }

        /** SFLOAT (IEEE-11073) of a whole number: 12-bit mantissa, exponent 0, little-endian. */
        fun sfloat(value: Int) = bytes(value and 0xFF, (value shr 8) and 0x0F)
    }
}
