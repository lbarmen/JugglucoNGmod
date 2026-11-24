package tk.glucodata.mod.carelink

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.ZoneId

/** Why a poll runs: the alarm chain, the 15-min safety work, right after a sign-in, the app opened, a button. */
enum class PollReason { ALARM, WORKER, LOGIN, FOREGROUND, MANUAL }

/**
 * JugglucoNGmod, CareLink (stage 4c): one poll of the cloud. Moved from Glucove's CareLinkRepository.poll, without
 * its journal, basal watch and modes.
 *
 * One poll at a time ([Mutex]). Alarm and safety work poll unless the last poll was less than half an interval ago
 * (critic 4c, N1: a late poll must not make the next alarm skip); the app opening - not more often than every 2 min;
 * sign-in and the button - always. Then: a live key
 * ([CareLinkAuth.ensureFresh]) -> POST `baseUrlCumulus/display/message` (`{"username", "role": "patient"}`, a care
 * partner `carepartner` + `patientId`) -> on 401/403 [CareLinkAuth.forceRefresh] and one more try -> parsing ->
 * [CareLinkBasalWriter], [CareLinkJournalWriter], [CareLinkIob] -> state.
 *
 * `NeedLogin` (`invalid_grant`, or a session file that will never open) -> NEED_LOGIN, alarms off, the open basal
 * segment closed, the pump's IoB off the plate, notification. Network, 5xx, a broken answer, a failed parse -> ERROR:
 * nothing is wiped, the last snapshot stays and `lastDataAtMs` does not move. A failed write (journal or basal) ->
 * ERROR with the write error, but the data came: the data time, the snapshot and the other writers go on (critic of the
 * joints, C3: one bad journal row must not stop the basal, the IoB plate and bring "no data"). The empty answer (no
 * pump upload yet) is a good poll that brings no data: the data time stays where it was.
 *
 * Off the main thread (Keystore, network). Keys, the user name and the answer are never logged.
 */
object CareLinkPoller {

    /** The app opening polls not more often than this. */
    internal const val FOREGROUND_GAP_MS = 2 * 60_000L

    private val mutex = Mutex()

    /** Work that outlives its caller: closing the basal after a sign-out (the sign-out may be on the main thread). */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    internal var zone: () -> ZoneId = { ZoneId.systemDefault() }

    // The writers of stage 4d; tests put recording or failing ones here.
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
     * One poll; the state after it (unchanged when skipped: off, no session, not due). [firstPoll] - the first poll
     * after a sign-in (the journal writer takes the whole 24 h every poll anyway; kept for the log).
     */
    suspend fun poll(context: Context, reason: PollReason, firstPoll: Boolean = false): CareLinkState =
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            mutex.withLock { pollLocked(app, reason, firstPoll) }
        }

    /**
     * After a sign-out: the open basal segment and the stop note end at their last data (critic of the joints, C2), in
     * the background and after a poll running now. The returned job is for tests.
     */
    fun closeAfterSignOut(context: Context): Job {
        val app = context.applicationContext
        return background.launch {
            try {
                mutex.withLock {
                    // a new sign-in got the lock first: the open segment and the IoB are the new session's (critic of
                    // the joints 4e, N1)
                    if (CareLinkSettings(app).enabled) return@withLock
                    try {
                        withContext(NonCancellable) { closeBasal(app) }
                    } finally {
                        // a poll running at the sign-out may have put the pump's IoB back on the plate (critic 4d2, N3);
                        // cleared even when closing the basal failed (critic 4d, N3)
                        CareLinkIob.clear()
                    }
                }
            } catch (t: Throwable) {
                CareLinkLog.w("basal not closed after sign-out (${t.javaClass.simpleName})")
            }
        }
    }

    private suspend fun pollLocked(app: Context, reason: PollReason, firstPollAsked: Boolean): CareLinkState {
        val store = CareLinkStateStore(app)
        val settings = CareLinkSettings(app)
        val before = store.read()
        if (!settings.enabled || !CareLinkAuth.hasSession(app)) return before
        val now = CareLinkAuth.clock()
        val last = before.lastPollAtMs
        when (reason) {
            PollReason.ALARM, PollReason.WORKER -> {
                val interval = settings.intervalMin * 60_000L
                if (last != null && now - last in 0 until interval / 2) return before
            }
            PollReason.FOREGROUND -> if (last != null && now - last in 0 until FOREGROUND_GAP_MS) return before
            PollReason.LOGIN, PollReason.MANUAL -> {}
        }
        val firstPoll = firstPollAsked || reason == PollReason.LOGIN || before.lastDataAtMs == null || store.firstPollPending

        var session = when (val f = CareLinkAuth.ensureFresh(app)) {
            is FreshResult.Ok -> f.session
            FreshResult.NeedLogin -> return needLogin(app, store, now, reason)
            is FreshResult.Failed -> return failed(app, store, now, reason, keyError(f))
        }
        var http = displayMessage(session)
        if (http.auth) {
            // the access key was revoked before its time: renew and try once more
            session = when (val f = CareLinkAuth.forceRefresh(app)) {
                is FreshResult.Ok -> f.session
                FreshResult.NeedLogin -> return needLogin(app, store, now, reason)
                is FreshResult.Failed -> return failed(app, store, now, reason, keyError(f))
            }
            http = displayMessage(session)
            // renewed and refused again: the cloud's trouble, not the end of the session - keys are kept
        }
        if (!http.ok) return failed(app, store, now, reason, httpError(http))
        val root = CareLinkConfig.parse(http.body)
        if (root == null || !CareLinkData.looksValid(root)) {
            return failed(app, store, now, reason, CareLinkError(CareLinkErrorKind.BAD_ANSWER, http.code, "not_pump_data"))
        }

        val events: List<ClEvent>
        val pump: PumpNow?
        val dataAt: Long?
        try {
            events = CareLinkMarkers.parse(root, zone())
            val serverDataAt = CareLinkData.pumpDataAt(root)
            // no data time in the answer: the snapshot keeps the last one's, not "now" - otherwise the next normal
            // answers, stamped a little before now, would be refused as older for minutes (critic 4c, N5)
            pump = CareLinkPumpNow.parse(root, now, zone())?.let { p ->
                if (serverDataAt == null) p.copy(dataAtMs = before.pumpNow?.dataAtMs ?: p.dataAtMs) else p
            }
            // the empty answer brings no data time: it would move it back (Glucove 03.11, 38 min)
            dataAt = if (pump == null) null else serverDataAt?.let { minOf(it, now) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return failed(app, store, now, reason, CareLinkError(CareLinkErrorKind.PARSE, 0, t.javaClass.simpleName))
        }
        // an answer older than the snapshot already taken (the cloud sometimes serves an older one): the state keeps
        // the last snapshot (merge), and no writer gets it - its markers would bring back a finished bolus as running or
        // a moved one under its old key (critic 4c, C3; critic 4d, B1, C1)
        val stale = pump != null && CareLinkPumpNow.older(before.pumpNow, pump)

        // signed out while this poll ran: nothing more is written (critic 4c, N2)
        if (!CareLinkSettings(app).enabled) return store.read()
        val writeError = if (stale) null else write(app, events, pump, now, firstPoll)
        // an empty answer (no pump data, no boluses) gives the journal nothing to write: a clean run proves nothing
        // about it either (critic 4e, N5)
        val nothingToWrite = stale || (pump == null && events.none { it.kind == ClKind.BOLUS && it.dose != null })

        // checked under the lock of the sign-out's clear(): its result must not bring the state back
        val after = store.updateIf({ CareLinkSettings(app).enabled }) { cur ->
            // an older or an empty answer writes nothing, so it says nothing of the writes: a write failure stands, with
            // its clock and its notification (critic 4d, N1; 4e, N5) - also when a network or cloud failure came in
            // between and took its place in the state, while its clock still runs (critic 4d2, N1)
            val keptWriteError = if (!nothingToWrite) null else
                cur.lastError?.takeIf { cur.status == CareLinkStatus.ERROR && it.kind == CareLinkErrorKind.WRITE }
                    ?: CareLinkError(CareLinkErrorKind.WRITE).takeIf { store.writeFailSinceMs != null }
            val error = writeError ?: keptWriteError
            cur.copy(
                status = if (error == null) CareLinkStatus.OK else CareLinkStatus.ERROR,
                lastPollAtMs = now,
                lastDataAtMs = listOfNotNull(cur.lastDataAtMs, dataAt).maxOrNull(),
                lastError = error,
                pumpNow = CareLinkPumpNow.merge(cur.pumpNow, pump),
            )
        } ?: return store.read()
        if (writeError == null && !stale) store.firstPollPending = false
        if (writeError == null) {
            CareLinkLog.i("poll ok: reason=$reason first=$firstPoll events=${events.size} empty=${pump == null} stale=$stale code=${http.code}")
        } else {
            CareLinkLog.w("poll written with an error: reason=$reason events=${events.size} error=${writeError.errorClass}")
        }
        CareLinkNotifier.afterPoll(app, after, now)
        // self-repair of the chain: a "sign in again" of an older poll may have removed the alarms (critic 4c, C2)
        try {
            CareLinkScheduler.arm(app)
        } catch (t: Throwable) {
            CareLinkLog.w("arm after poll failed (${t.javaClass.simpleName})")
        }
        return after
    }

    /**
     * The writers of one fresh answer, each on its own (critic of the joints, C3): the basal, then the journal, then
     * the IoB plate. Without cancellation: WorkManager cancels running work when the network goes, and a journal row
     * written whose record was not saved would look like the person's edit next time (critic of the joints, C1). The
     * first write failure (null - none).
     */
    private suspend fun write(app: Context, events: List<ClEvent>, pump: PumpNow?, now: Long, firstPoll: Boolean): CareLinkError? =
        withContext(NonCancellable) {
            var error: CareLinkError? = null
            try {
                writeBasal(app, pump, now)
            } catch (t: Throwable) {
                CareLinkLog.w("poll: basal not written (${t.javaClass.simpleName})")
                error = CareLinkError(CareLinkErrorKind.WRITE, 0, t.javaClass.simpleName)
            }
            try {
                writeJournal(app, events, firstPoll)
            } catch (t: Throwable) {
                CareLinkLog.w("poll: journal not written (${t.javaClass.simpleName})")
                if (error == null) error = CareLinkError(CareLinkErrorKind.WRITE, 0, t.javaClass.simpleName)
            }
            try {
                // signed out while writing: the pump's number stays off the plate (critic 4d2, N3)
                if (CareLinkSettings(app).enabled) publishIob(pump)
            } catch (t: Throwable) {
                // the IoB plate is a display: a failure here is not a failed poll
                CareLinkLog.w("poll: active insulin not published (${t.javaClass.simpleName})")
            }
            error
        }

    private fun displayMessage(s: CareLinkSession): HttpResult {
        val body = JSONObject().put("username", s.username)
        if (s.carePartner) {
            body.put("role", "carepartner")
            s.patientId?.let { body.put("patientId", it) }
        } else {
            body.put("role", "patient")
        }
        return CareLinkAuth.http.postJson(
            s.baseUrlCumulus.trimEnd('/') + "/display/message", body.toString(), CareLinkHttp.bearer(s.accessToken),
        )
    }

    private suspend fun needLogin(app: Context, store: CareLinkStateStore, now: Long, reason: PollReason): CareLinkState {
        // a sign-out during this poll also ends the session: no "sign in again" for a deliberate sign-out
        if (!CareLinkSettings(app).enabled) return store.read()
        // a new sign-in during this poll (the renewal found the vault changed): the new session stands, its alarms
        // and its first poll stay (critic 4c, C2)
        if (CareLinkAuth.hasSession(app)) return store.read()
        val after = store.update { it.copy(status = CareLinkStatus.NEED_LOGIN, lastPollAtMs = now, lastError = null) }
        CareLinkLog.w("poll: sign-in needed (reason=$reason)")
        try {
            CareLinkScheduler.disarm(app)
        } catch (t: Throwable) {
            CareLinkLog.w("alarms not removed (${t.javaClass.simpleName})")
        }
        // no more data until a sign-in: the basal segment and the stop note end at their last data, the pump's number
        // leaves the IoB plate (critic of the joints, C2 and N2)
        try {
            withContext(NonCancellable) { closeBasal(app) }
        } catch (t: Throwable) {
            CareLinkLog.w("basal not closed (${t.javaClass.simpleName})")
        }
        CareLinkIob.clear()
        CareLinkNotifier.needLogin(app)
        return after
    }

    private fun failed(app: Context, store: CareLinkStateStore, now: Long, reason: PollReason, e: CareLinkError): CareLinkState {
        if (!CareLinkSettings(app).enabled) return store.read()
        val after = store.update { it.copy(status = CareLinkStatus.ERROR, lastPollAtMs = now, lastError = e) }
        CareLinkLog.w("poll failed: reason=$reason kind=${e.kind} code=${e.code} error=${e.errorClass}")
        CareLinkNotifier.afterPoll(app, after, now)
        return after
    }

    /**
     * A renewal that did not give a key. Code 0 means no HTTP answer: a network exception class - no network; the
     * renewal answer did not parse - a bad answer; anything else (a session file that did not open this time:
     * [CareLinkAuth] gives the exception class; one that never will is a sign-in needed) - storage.
     */
    private fun keyError(f: FreshResult.Failed): CareLinkError = when {
        f.code == 0 && f.errorClass in NETWORK_ERRORS -> CareLinkError(CareLinkErrorKind.NO_NETWORK, 0, f.errorClass)
        f.code == 0 && f.errorClass == "bad_answer" -> CareLinkError(CareLinkErrorKind.BAD_ANSWER, 0, f.errorClass)
        f.code == 0 -> CareLinkError(CareLinkErrorKind.STORAGE, 0, f.errorClass)
        f.code in 200..299 -> CareLinkError(CareLinkErrorKind.BAD_ANSWER, f.code, f.errorClass)
        else -> CareLinkError(CareLinkErrorKind.SERVER, f.code, f.errorClass)
    }

    /** Exception classes of [UrlConnectionCareLinkHttp] with no answer: no network, a cut or slow connection, TLS. */
    private val NETWORK_ERRORS = setOf(
        "UnknownHostException", "ConnectException", "NoRouteToHostException", "SocketException", "SocketTimeoutException",
        "InterruptedIOException", "IOException", "EOFException", "ProtocolException", "SSLException",
        "SSLHandshakeException", "SSLPeerUnverifiedException", "SSLProtocolException", "ConnectTimeoutException",
    )

    private fun httpError(r: HttpResult): CareLinkError = when {
        r.code == 0 -> CareLinkError(CareLinkErrorKind.NO_NETWORK, 0, r.errorClass)
        else -> CareLinkError(CareLinkErrorKind.SERVER, r.code, r.errorClass)
    }

    /** Back to defaults (tests). */
    internal fun resetForTest() {
        zone = { ZoneId.systemDefault() }
        writeJournal = { c, e, first -> CareLinkJournalWriter.apply(c, e, first) }
        writeBasal = { c, p, at -> CareLinkBasalWriter.apply(c, p, at) }
        publishIob = { p -> CareLinkIob.publish(p) }
        closeBasal = { c -> CareLinkBasalWriter.close(c) }
    }
}
