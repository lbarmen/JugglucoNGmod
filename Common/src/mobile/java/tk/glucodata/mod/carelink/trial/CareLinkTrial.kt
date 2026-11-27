package tk.glucodata.mod.carelink.trial

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tk.glucodata.mod.carelink.CareLinkAuth
import tk.glucodata.mod.carelink.CareLinkClock
import tk.glucodata.mod.carelink.CareLinkPoller
import tk.glucodata.mod.carelink.CareLinkSession
import tk.glucodata.mod.carelink.CareLinkState
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.carelink.CareLinkVault
import tk.glucodata.mod.carelink.PollReason
import tk.glucodata.mod.carelink.ModCareLinkStarter
import java.time.ZoneId
import java.util.Locale

/** A trial command (`am broadcast ... --es cmd <name> [--es arg <...>]`), parsed. */
sealed class TrialCommand {
    object Login : TrialCommand()
    object Logout : TrialCommand()
    object Reset : TrialCommand()
    object Poll : TrialCommand()
    object State : TrialCommand()
    data class Step(val name: String, val args: List<Double>) : TrialCommand()
    data class Answer(val shape: CareLinkTrialPump.Shape) : TrialCommand()
    data class Fail(val kind: String) : TrialCommand()
    data class Clock(val minutes: Long) : TrialCommand()
}

/**
 * JugglucoNGmod, CareLink trial (test builds only, part 4p): the commands behind the trial receiver. Everything goes
 * through the real code of 4b-4d - the session in [CareLinkVault], [ModCareLinkStarter.afterLogin] as after a real
 * sign-in, [CareLinkPoller.poll] with parsing, journal, basal, IoB and notifications - only the network is
 * [CareLinkTrialHttp] and the pump is [CareLinkTrialPump]. A test build only: the entry point that installs it lives in
 * `Common/src/modTrial/`, compiled into `-PmodRelease=0` phone builds alone; in a candidate nothing refers to this class
 * and R8 drops it (mod/check-apk.py checks the marker strings).
 *
 * Result strings carry no personal data: the pump is made up, the user is `trial`.
 */
object CareLinkTrial {

    const val USER = "trial"
    const val CUMULUS = "https://trial.invalid/cumulus"
    const val CARELINK = "https://trial.invalid/carelink"
    const val TOKEN_URL = "https://trial.invalid/token"

    /** The trial key lives a year: no renewal unless a `fail` asks for one. */
    const val KEY_LIFE_MS = 365L * 24 * 3_600_000L

    val pump = CareLinkTrialPump()
    private val pumpLock = Any()
    private var lastDataAtMs: Long? = null

    val http = CareLinkTrialHttp { shape -> answerText(shape) }

    private val mutex = Mutex()

    @Volatile
    private var clockOffsetMs = 0L

    @Volatile
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    fun now(): Long = CareLinkClock.now()

    /** Installs the fake network (no disk, no Keystore: safe in a provider's onCreate). */
    fun installHttp() {
        CareLinkAuth.http = http
    }

    private fun answerText(shape: CareLinkTrialPump.Shape): String = synchronized(pumpLock) {
        val now = now()
        val root = pump.answer(now, zone(), shape, lastDataAtMs)
        if (shape == CareLinkTrialPump.Shape.NORMAL) lastDataAtMs = now - CareLinkTrialPump.DATA_LAG_MS
        root.toString()
    }

    /** The made-up session of a patient account, addresses on `trial.invalid`. */
    fun session(nowMs: Long): CareLinkSession = CareLinkSession(
        accessToken = CareLinkTrialHttp.jwt(nowMs + KEY_LIFE_MS), refreshToken = "trial-refresh",
        expiresAtMs = nowMs + KEY_LIFE_MS, username = USER, role = "PATIENT", patientId = null, country = null,
        region = "EU", baseUrlCareLink = CARELINK, baseUrlCumulus = CUMULUS, tokenUrl = TOKEN_URL, clientId = "trial",
        loggedInAtMs = nowMs,
    )

    /** Parses a command; a wrong one throws [IllegalArgumentException] with the reason. */
    fun parse(cmd: String?, arg: String?): TrialCommand {
        val name = cmd?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val parts = arg?.trim()?.split(Regex("[\\s:,;]+"))?.filter { it.isNotEmpty() }.orEmpty()
        return when (name) {
            "login" -> TrialCommand.Login
            "logout" -> TrialCommand.Logout
            "reset" -> TrialCommand.Reset
            "poll" -> TrialCommand.Poll
            "state" -> TrialCommand.State
            "step" -> {
                val step = parts.firstOrNull()?.lowercase(Locale.ROOT) ?: throw IllegalArgumentException("step: no name")
                if (step !in STEPS) throw IllegalArgumentException("step: unknown '$step' (${STEPS.keys.joinToString()})")
                val nums = parts.drop(1).map { it.replace(',', '.').toDoubleOrNull() ?: throw IllegalArgumentException("step: '$it' is not a number") }
                if (nums.size > STEPS.getValue(step).size) throw IllegalArgumentException("step $step: too many numbers")
                TrialCommand.Step(step, nums)
            }
            "answer" -> when (parts.firstOrNull()?.lowercase(Locale.ROOT)) {
                "empty" -> TrialCommand.Answer(CareLinkTrialPump.Shape.EMPTY)
                "old" -> TrialCommand.Answer(CareLinkTrialPump.Shape.OLD)
                else -> throw IllegalArgumentException("answer: empty or old")
            }
            "fail" -> {
                val kind = parts.firstOrNull()?.lowercase(Locale.ROOT).orEmpty()
                if (kind !in FAILS) throw IllegalArgumentException("fail: one of ${FAILS.joinToString()}")
                TrialCommand.Fail(kind)
            }
            "clock" -> {
                val m = parts.firstOrNull()?.removePrefix("+")?.toLongOrNull()
                    ?: throw IllegalArgumentException("clock: minutes (+40, -10, 0)")
                if (m !in -1440L..1440L) throw IllegalArgumentException("clock: -1440..1440 minutes")
                TrialCommand.Clock(m)
            }
            else -> throw IllegalArgumentException("unknown command '$name'")
        }
    }

    /** Runs one command (commands one at a time); the result line for the log and the broadcast answer. */
    suspend fun run(context: Context, cmd: String?, arg: String?): String {
        val c = try {
            parse(cmd, arg)
        } catch (e: IllegalArgumentException) {
            return "error: ${e.message}"
        }
        val app = context.applicationContext
        return mutex.withLock { execute(app, c) }
    }

    private suspend fun execute(app: Context, c: TrialCommand): String = when (c) {
        TrialCommand.Login -> {
            installHttp()
            val stored = CareLinkVault.saveNew(app, session(now()), CareLinkVault.generation)
            if (stored) {
                ModCareLinkStarter.afterLogin(app)
                "login ok: trial session, polling on"
            } else {
                "login refused: signed out meanwhile"
            }
        }
        TrialCommand.Logout -> {
            CareLinkAuth.signOut(app)
            "logout ok"
        }
        TrialCommand.Reset -> {
            synchronized(pumpLock) {
                pump.reset()
                lastDataAtMs = null
            }
            http.clearQueues()
            "reset ok: pump history empty, queue empty"
        }
        TrialCommand.Poll -> {
            val s = withContext(http.manual()) { CareLinkPoller.poll(app, PollReason.MANUAL) }
            "poll: " + describe(s) + " queueLeft=${http.queued()}"
        }
        TrialCommand.State -> "state: " + describe(CareLinkStateStore(app).read()) +
            " session=${CareLinkAuth.hasSession(app)} clock=${clockOffsetMs / 60_000}min queue=${http.queued()}"
        is TrialCommand.Step -> synchronized(pumpLock) { step(c.name, c.args, now()) }
        is TrialCommand.Answer -> {
            http.queueMessage(CareLinkTrialHttp.Next.Shape(c.shape))
            "answer: next poll gets ${c.shape.name.lowercase(Locale.ROOT)}"
        }
        is TrialCommand.Fail -> {
            queueFail(c.kind)
            "fail: next poll gets ${c.kind}"
        }
        is TrialCommand.Clock -> {
            val offset = c.minutes * 60_000L
            clockOffsetMs = offset
            CareLinkClock.now = if (offset == 0L) {
                { System.currentTimeMillis() }
            } else {
                { System.currentTimeMillis() + offset }
            }
            "clock: ${if (c.minutes > 0) "+" else ""}${c.minutes} min"
        }
    }

    private fun queueFail(kind: String) {
        when (kind) {
            // the key is refused, the renewal says invalid_grant: sign in again
            "401" -> {
                http.queueMessage(CareLinkTrialHttp.Next.Status(401, null))
                http.queueToken(
                    CareLinkTrialHttp.Next.Status(400, """{"error": "invalid_grant", "error_description": "trial: refresh key revoked"}"""),
                )
            }
            // the key is refused, the renewal works, the second try gets the normal answer
            "401-renew" -> http.queueMessage(CareLinkTrialHttp.Next.Status(401, null))
            "500" -> http.queueMessage(CareLinkTrialHttp.Next.Status(500, "{}"))
            "net" -> http.queueMessage(CareLinkTrialHttp.Next.NoNetwork)
            "junk" -> http.queueMessage(CareLinkTrialHttp.Next.Status(200, "<html>trial: not json</html>"))
        }
    }

    /** A step of the pump at [now]; [args] - its numbers, missing ones take the defaults of [STEPS]. */
    internal fun step(name: String, args: List<Double>, now: Long): String {
        val d = STEPS.getValue(name)
        fun a(i: Int): Double = args.getOrNull(i) ?: d[i]
        fun dose(i: Int): Double = a(i).also { require(it > 0.0 && it <= 25.0) { "$name: dose 0..25 U" } }
        fun minutes(i: Int): Int = a(i).toInt().also { require(it in 1..1440) { "$name: minutes 1..1440" } }
        val p = pump
        return try {
            when (name) {
                "bolus" -> dose(0).let { u -> p.boluses += CareLinkTrialPump.Bolus(now, u, u); "bolus $u U" }
                "bolus-run" -> dose(0).let { u ->
                    p.boluses += CareLinkTrialPump.Bolus(now, u, round(u / 2), completed = false)
                    "bolus $u U running"
                }
                "bolus-done" -> {
                    val i = p.boluses.indexOfLast { !it.completed }
                    if (i < 0) return "step bolus-done: no running bolus"
                    val b = p.boluses[i]
                    p.boluses[i] = b.copy(fastDelivered = b.fastProgrammed, extDelivered = b.extProgrammed, completed = true)
                    "bolus ${b.fastProgrammed + b.extProgrammed} U done"
                }
                "bolus-part" -> dose(0).let { u -> p.boluses += CareLinkTrialPump.Bolus(now, u, round(u / 2)); "bolus $u U stopped at ${round(u / 2)}" }
                "bolus-cancel" -> dose(0).let { u -> p.boluses += CareLinkTrialPump.Bolus(now, u, 0.0); "bolus $u U cancelled" }
                "square" -> {
                    val u = dose(0)
                    val m = minutes(1)
                    p.boluses += CareLinkTrialPump.Bolus(now, 0.0, 0.0, u, u, type = CareLinkTrialPump.TYPE_SQUARE, durationMin = m)
                    "square $u U over $m min"
                }
                "dual" -> {
                    val f = dose(0)
                    val e = dose(1)
                    val m = minutes(2)
                    p.boluses += CareLinkTrialPump.Bolus(now, f, f, e, e, type = CareLinkTrialPump.TYPE_DUAL, durationMin = m)
                    "dual $f U + $e U over $m min"
                }
                "meal" -> {
                    val g = a(0).also { require(it > 0.0 && it <= 300.0) { "meal: grams 0..300" } }
                    val u = dose(1)
                    // the calculator writes the carbs a moment before its bolus (Glucove: 1-3 s)
                    p.meals += CareLinkTrialPump.Meal(now - 2_000L, g)
                    p.boluses += CareLinkTrialPump.Bolus(now, u, u)
                    "meal $g g with bolus $u U"
                }
                "carbs" -> {
                    val g = a(0).also { require(it > 0.0 && it <= 300.0) { "carbs: grams 0..300" } }
                    p.meals += CareLinkTrialPump.Meal(now, g)
                    "carbs $g g without a bolus"
                }
                "temp" -> {
                    val r = a(0).also { require(it >= 0.0 && it <= 10.0) { "temp: rate 0..10 U/h" } }
                    val m = minutes(1)
                    p.temp = CareLinkTrialPump.Temp(now, now + m * 60_000L, r, null)
                    "temp $r U/h for $m min"
                }
                "temp-pct" -> {
                    val pct = a(0).toInt().also { require(it in 0..200) { "temp-pct: 0..200 %" } }
                    val m = minutes(1)
                    p.temp = CareLinkTrialPump.Temp(now, now + m * 60_000L, null, pct)
                    "temp $pct % for $m min"
                }
                "temp-end" -> if (p.tempAt(now) == null) "temp-end: no temp basal" else {
                    p.temp = null
                    "temp ended"
                }
                "suspend" -> {
                    if (p.suspendedSinceMs == null) p.suspendedSinceMs = now
                    "pump stopped"
                }
                "resume" -> {
                    p.suspendedSinceMs = null
                    "pump running"
                }
                "profile" -> {
                    val r = a(0).also { require(it > 0.0 && it <= 10.0) { "profile: rate 0..10 U/h" } }
                    p.normalRate = r
                    "normal rate $r U/h"
                }
                else -> "step: unknown '$name'"
            }.let { "step: $it" }
        } catch (e: IllegalArgumentException) {
            "error: ${e.message}"
        }
    }

    private fun describe(s: CareLinkState): String {
        val p = s.pumpNow
        val now = now()
        val age = s.lastDataAtMs?.let { "${(now - it) / 60_000}min" } ?: "-"
        val err = s.lastError?.let { "${it.kind}/${it.code}" } ?: "-"
        val temp = p?.tempBasal?.let { t -> "${t.rate ?: "-"}/${t.percent ?: "-"}%/${t.minutesLeft ?: "-"}min" } ?: "-"
        return "status=${s.status} error=$err dataAge=$age iob=${p?.iobUnits ?: "-"} reservoir=${p?.reservoirUnits ?: "-"} " +
            "battery=${p?.batteryPercent ?: "-"} basal=${p?.basalRate ?: "-"} temp=$temp stopped=${p?.suspended ?: "-"}"
    }

    private fun round(v: Double): Double = Math.round(v * 1000.0) / 1000.0

    /** Steps and the defaults of their numbers (README of the scenarios lists them). */
    val STEPS: Map<String, DoubleArray> = linkedMapOf(
        "bolus" to doubleArrayOf(2.0),
        "bolus-run" to doubleArrayOf(3.0),
        "bolus-done" to doubleArrayOf(),
        "bolus-part" to doubleArrayOf(2.0),
        "bolus-cancel" to doubleArrayOf(1.5),
        "square" to doubleArrayOf(2.0, 60.0),
        "dual" to doubleArrayOf(1.0, 1.0, 30.0),
        "meal" to doubleArrayOf(45.0, 4.5),
        "carbs" to doubleArrayOf(20.0),
        "temp" to doubleArrayOf(1.2, 60.0),
        "temp-pct" to doubleArrayOf(50.0, 60.0),
        "temp-end" to doubleArrayOf(),
        "suspend" to doubleArrayOf(),
        "resume" to doubleArrayOf(),
        "profile" to doubleArrayOf(1.0),
    )

    val FAILS = listOf("401", "401-renew", "500", "net", "junk")

    /** Back to a fresh process (tests). */
    internal fun resetForTest() {
        synchronized(pumpLock) {
            pump.reset()
            lastDataAtMs = null
        }
        http.clearQueues()
        clockOffsetMs = 0L
        zone = { ZoneId.systemDefault() }
    }
}
