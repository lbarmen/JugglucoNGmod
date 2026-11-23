package tk.glucodata.mod.carelink

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream

/**
 * JugglucoNGmod, CareLink (stage 4b): one answer of the cloud. [code] 0 - no answer at all, then [error] says why
 * (only its class is ever logged). [toString] shows neither the body nor the message: bodies hold keys and personal data.
 */
data class HttpResult(val code: Int, val body: String?, val error: Throwable?) {
    val ok: Boolean get() = code in 200..299
    val auth: Boolean get() = code == 401 || code == 403
    val errorClass: String? get() = error?.javaClass?.simpleName

    override fun toString(): String = "HttpResult(code=$code, bytes=${body?.length ?: 0}, error=$errorClass)"
}

/**
 * Requests to CareLink, blocking: call them off the main thread (CareLinkAuth does, on Dispatchers.IO). An interface,
 * so tests put a fake in place of the network ([CareLinkAuth.http]).
 */
interface CareLinkHttp {
    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResult
    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): HttpResult
    fun postJson(url: String, json: String, headers: Map<String, String> = emptyMap()): HttpResult

    companion object {
        /** The real client on HttpURLConnection. */
        val system: CareLinkHttp by lazy { UrlConnectionCareLinkHttp() }

        /** Headers of an API call with the access key. */
        fun bearer(accessToken: String): Map<String, String> = mapOf(
            "Accept" to "application/json",
            "Authorization" to "Bearer $accessToken",
        )
    }
}

/**
 * [CareLinkHttp] on HttpURLConnection and no library (upstream has no OkHttp). As the CareLink app: `User-Agent` is
 * the system `http.agent` (Dalvik/2.1.0 ...), not a library name - the cloud expects the mobile app; gzip asked for by
 * hand and unpacked here (a hand-set Accept-Encoding turns off the transparent unpacking); timeouts connect 15 s,
 * read 30 s and the whole request at most 30 s (a watchdog disconnects it). Only https ([requireHttps]; tests of
 * this class talk to a local plain server). POST does not follow redirects (its body cannot be sent twice). An answer
 * whose body breaks off keeps its status code (a cut 400 is still a 400, not "no network"). Nothing is logged here.
 */
internal class UrlConnectionCareLinkHttp(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val totalTimeoutMs: Long = 30_000,
    private val requireHttps: Boolean = true,
) : CareLinkHttp {

    override fun get(url: String, headers: Map<String, String>): HttpResult = request("GET", url, headers, null, null)

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResult {
        val body = form.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
        }
        return request("POST", url, headers, body.toByteArray(Charsets.UTF_8), "application/x-www-form-urlencoded")
    }

    override fun postJson(url: String, json: String, headers: Map<String, String>): HttpResult =
        request("POST", url, headers, json.toByteArray(Charsets.UTF_8), "application/json; charset=utf-8")

    private fun request(
        method: String,
        url: String,
        headers: Map<String, String>,
        body: ByteArray?,
        contentType: String?,
    ): HttpResult {
        if (requireHttps && !CareLinkConfig.isHttps(url)) return HttpResult(0, null, IllegalArgumentException("not https"))
        val conn = AtomicReference<HttpURLConnection?>(null)
        val timedOut = AtomicBoolean(false)
        val watchdog = try {
            watchdogs.schedule(Runnable {
                timedOut.set(true)
                try {
                    conn.get()?.disconnect()
                } catch (_: Throwable) {
                }
            }, totalTimeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            return HttpResult(0, null, t)
        }
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn.set(c)
            c.connectTimeout = connectTimeoutMs
            c.readTimeout = readTimeoutMs
            c.requestMethod = method
            c.useCaches = false
            c.instanceFollowRedirects = method == "GET"
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept-Encoding", "gzip")
            if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) {
                c.setRequestProperty("Accept", "application/json")
            }
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            if (body != null) {
                c.doOutput = true
                if (contentType != null) c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body) }
            }
            val code = c.responseCode
            if (code == HttpURLConnection.HTTP_NO_CONTENT) return HttpResult(code, null, null)
            return try {
                val stream: InputStream? = if (code >= 400) c.errorStream else c.inputStream
                val text = stream?.let { s ->
                    val unpacked = if ("gzip".equals(c.contentEncoding, ignoreCase = true)) GZIPInputStream(s) else s
                    unpacked.use { readCapped(it) }
                }
                HttpResult(code, text, null)
            } catch (t: Throwable) {
                // the status is known: keep it, the body is lost
                HttpResult(code, null, if (timedOut.get()) SocketTimeoutException("total timeout") else t)
            }
        } catch (t: Throwable) {
            val error = if (timedOut.get()) SocketTimeoutException("total timeout") else t
            return HttpResult(0, null, error)
        } finally {
            watchdog.cancel(false)
            try {
                conn.get()?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    private fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BODY_BYTES) throw IOException("answer over $MAX_BODY_BYTES bytes")
        }
        return out.toString("UTF-8")
    }

    companion object {
        /** A CareLink answer (24 h of pump data) is well under a megabyte; more is not a CareLink answer. */
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024

        private val USER_AGENT: String = System.getProperty("http.agent")?.takeIf { it.isNotBlank() }
            ?: "Dalvik/2.1.0 (Linux; U; Android)"

        /** One daemon thread for every request's total timeout. */
        private val watchdogs: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "mod-carelink-http-watchdog").apply { isDaemon = true }
        }
    }
}
