package mod.carelink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.carelink.UrlConnectionCareLinkHttp
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.zip.GZIPOutputStream

/**
 * JugglucoNGmod, CareLink 4b: the HttpURLConnection client against a one-request local server (plain http on the
 * loopback, so https is not required here; the https rule has its own test). Made-up bodies only.
 */
class CareLinkHttpTest {

    /** Accepts one connection, keeps the request text, answers with [handler]. */
    private class OneShotServer(private val handler: (Socket) -> Unit) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())

        @Volatile
        var request: String = ""

        val url: String get() = "http://127.0.0.1:${server.localPort}/path"

        init {
            Thread {
                try {
                    server.accept().use { s ->
                        request = readRequest(s.getInputStream())
                        handler(s)
                    }
                } catch (_: Exception) {
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() {
            server.close()
        }

        private fun readRequest(input: InputStream): String {
            val head = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) break
                head.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
            }
            val text = head.toString("ISO-8859-1")
            val length = Regex("(?im)^content-length:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            return text + String(body, 0, read, Charsets.ISO_8859_1)
        }
    }

    private fun respond(out: OutputStream, status: Int, body: ByteArray, vararg headers: String) {
        val head = buildString {
            append("HTTP/1.1 $status X\r\n")
            headers.forEach { append(it).append("\r\n") }
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    private fun gzip(text: String): ByteArray {
        val buf = ByteArrayOutputStream()
        GZIPOutputStream(buf).use { it.write(text.toByteArray()) }
        return buf.toByteArray()
    }

    private val local = UrlConnectionCareLinkHttp(requireHttps = false)

    @Test
    fun gzipAnswerIsUnpackedAndHeadersAreTheApps() {
        OneShotServer { s -> respond(s.getOutputStream(), 200, gzip("""{"made":"up"}"""), "Content-Encoding: gzip") }.use { srv ->
            val r = local.get(srv.url, mapOf("Authorization" to "Bearer made-up"))
            assertEquals(200, r.code)
            assertEquals("""{"made":"up"}""", r.body)
            assertNull(r.error)
            val req = srv.request.lowercase()
            assertTrue(req.startsWith("get /path "))
            assertTrue(req.contains("accept-encoding: gzip"))
            assertTrue(req.contains("user-agent: dalvik/"))
            assertTrue(req.contains("authorization: bearer made-up"))
        }
    }

    @Test
    fun errorAnswerKeepsItsBody() {
        OneShotServer { s -> respond(s.getOutputStream(), 400, """{"error":"invalid_grant"}""".toByteArray()) }.use { srv ->
            val r = local.postForm(srv.url, mapOf("grant_type" to "refresh_token", "refresh_token" to "made up+1"))
            assertEquals(400, r.code)
            assertEquals("""{"error":"invalid_grant"}""", r.body)
            val req = srv.request
            assertTrue(req.startsWith("POST /path "))
            assertTrue(req.lowercase().contains("content-type: application/x-www-form-urlencoded"))
            assertTrue(req.endsWith("grant_type=refresh_token&refresh_token=made+up%2B1"))
        }
    }

    @Test
    fun aBodyThatBreaksOffKeepsTheStatus() {
        val packed = gzip("""{"error":"invalid_grant","error_description":"made-up text long enough"}""")
        val cut = packed.copyOfRange(0, packed.size / 2)
        OneShotServer { s -> respond(s.getOutputStream(), 400, cut, "Content-Encoding: gzip") }.use { srv ->
            val r = local.get(srv.url)
            assertEquals(400, r.code)
            assertNull(r.body)
            assertNotNull(r.error)
        }
    }

    @Test
    fun noContentHasNoBody() {
        OneShotServer { s -> respond(s.getOutputStream(), 204, ByteArray(0), "Content-Encoding: gzip") }.use { srv ->
            val r = local.get(srv.url)
            assertEquals(204, r.code)
            assertNull(r.body)
            assertNull(r.error)
        }
    }

    @Test
    fun plainHttpIsRefusedByDefault() {
        val r = UrlConnectionCareLinkHttp().get("http://127.0.0.1:9/never")
        assertEquals(0, r.code)
        assertTrue(r.error is IllegalArgumentException)
    }

    @Test
    fun theWholeRequestStopsAtItsTotalTimeout() {
        OneShotServer { Thread.sleep(10_000) }.use { srv ->
            // read timeout 2 s only bounds a possible silent retry of the desktop JDK's HttpURLConnection after the
            // watchdog's disconnect (the phone's client does not retry); the answer still is a timeout, well under 5 s
            val slow = UrlConnectionCareLinkHttp(readTimeoutMs = 2_000, totalTimeoutMs = 300, requireHttps = false)
            val t0 = System.nanoTime()
            val r = slow.get(srv.url)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertEquals(0, r.code)
            assertTrue("${r.error}", r.error is SocketTimeoutException)
            assertTrue("took $ms ms", ms < 5_000)
        }
    }
}
