package tk.glucodata.mod.carelink.trial

import kotlinx.coroutines.asContextElement
import org.json.JSONObject
import tk.glucodata.mod.carelink.CareLinkHttp
import tk.glucodata.mod.carelink.HttpResult
import java.io.IOException
import java.util.ArrayDeque
import java.util.Base64
import kotlin.coroutines.CoroutineContext

/**
 * JugglucoNGmod, CareLink trial (test builds only, part 4p): [CareLinkHttp] with no network. Knows only the addresses
 * of the trial session ([CareLinkTrial.CUMULUS], [CareLinkTrial.CARELINK], [CareLinkTrial.TOKEN_URL]); any other
 * address is answered "no network" at once (so sign-in and discovery simply do not work in a trial build).
 *
 * `display/message` - the made-up pump's answer; the token address - a renewed key (a parsable JWT with `exp`);
 * `users/me` - the patient role. The queue of unusual answers (`fail`, `answer` commands) is served only to requests
 * running under [manual] - a poll started by the `poll` command: the poll that a sign-in queues, WorkManager and the
 * alarm get the normal answer and leave the queue alone.
 */
class CareLinkTrialHttp(private val answer: (CareLinkTrialPump.Shape) -> String) : CareLinkHttp {

    /** One queued answer of `display/message` or of the token address. */
    sealed class Next {
        data class Status(val code: Int, val body: String?) : Next()
        data class Shape(val shape: CareLinkTrialPump.Shape) : Next()
        object NoNetwork : Next()
    }

    private val lock = Any()
    private val messages = ArrayDeque<Next>()
    private val tokens = ArrayDeque<Next>()
    private val manualFlag = ThreadLocal<Boolean?>()

    /** Every request seen (address only), for tests and the `state` command. */
    @Volatile
    var requests: Int = 0
        private set

    @Volatile
    var refused: Int = 0
        private set

    /** Coroutine context of a `poll` command: its requests take the queue. */
    fun manual(): CoroutineContext = manualFlag.asContextElement(true)

    private val isManual: Boolean get() = manualFlag.get() == true

    fun queueMessage(vararg n: Next) = synchronized(lock) { n.forEach(messages::addLast) }

    fun queueToken(vararg n: Next) = synchronized(lock) { n.forEach(tokens::addLast) }

    fun clearQueues() = synchronized(lock) {
        messages.clear()
        tokens.clear()
    }

    fun queued(): Int = synchronized(lock) { messages.size + tokens.size }

    private fun take(q: ArrayDeque<Next>): Next? = if (!isManual) null else synchronized(lock) { q.pollFirst() }

    override fun get(url: String, headers: Map<String, String>): HttpResult {
        requests++
        return when (url) {
            CareLinkTrial.CARELINK + "/users/me" -> HttpResult(200, """{"role": "PATIENT"}""", null)
            else -> noNetwork()
        }
    }

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResult {
        requests++
        if (url != CareLinkTrial.TOKEN_URL) return noNetwork()
        return when (val n = take(tokens)) {
            null -> renewed()
            is Next.Status -> HttpResult(n.code, n.body, null)
            is Next.Shape -> renewed()
            Next.NoNetwork -> noNetwork()
        }
    }

    override fun postJson(url: String, json: String, headers: Map<String, String>): HttpResult {
        requests++
        if (url != CareLinkTrial.CUMULUS + "/display/message") return noNetwork()
        return when (val n = take(messages)) {
            null -> HttpResult(200, answer(CareLinkTrialPump.Shape.NORMAL), null)
            is Next.Status -> HttpResult(n.code, n.body, null)
            is Next.Shape -> HttpResult(200, answer(n.shape), null)
            Next.NoNetwork -> noNetwork()
        }
    }

    private fun noNetwork(): HttpResult {
        refused++
        return HttpResult(0, null, IOException("trial: no network"))
    }

    private fun renewed(): HttpResult {
        val now = CareLinkTrial.now()
        val access = jwt(now + CareLinkTrial.KEY_LIFE_MS)
        val body = JSONObject().put("access_token", access).put("refresh_token", "trial-refresh-" + (now / 1000))
            .put("expires_in", CareLinkTrial.KEY_LIFE_MS / 1000)
        return HttpResult(200, body.toString(), null)
    }

    companion object {
        /** A parsable JWT (no signature) of the made-up user `trial`, expiring at [expMs]. */
        fun jwt(expMs: Long): String {
            val enc = Base64.getUrlEncoder().withoutPadding()
            val payload = JSONObject().put("exp", expMs / 1000)
                .put("token_details", JSONObject().put("preferred_username", CareLinkTrial.USER))
            return enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." +
                enc.encodeToString(payload.toString().toByteArray()) + ".trial"
        }
    }
}
