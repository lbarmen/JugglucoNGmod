package mod.carelink

import tk.glucodata.mod.carelink.CareLinkHttp
import tk.glucodata.mod.carelink.HttpResult
import java.io.IOException
import java.util.Base64

/**
 * JugglucoNGmod, CareLink tests: made-up cloud answers only (example domains, invented names and keys). No real
 * CareLink answer is ever copied here. JWTs are built from parts at run time.
 */
internal object CareLinkTestData {

    const val SSO_URL = "https://example.eu/sso.json"
    const val TOKEN_URL = "https://auth.example.eu:443/oauth/token"
    const val CARELINK_BASE = "https://cl.example.eu/api/carepartner/v2"
    const val REDIRECT = "com.example.app://sso/callback"

    val DISCOVERY = """
        {"supportedCountries": [{"DE": {"region": "EU"}}, {"US": {"region": "US"}}],
         "CP": [
           {"region": "EU", "UseSSOConfiguration": "SSOConfigurationAuth0", "SSOConfigurationAuth0": "$SSO_URL",
            "SSOConfiguration": "https://example.eu/old.json", "baseUrlCareLink": "$CARELINK_BASE/",
            "baseUrlCumulus": "https://cumulus.example.eu/connect/carepartner/v13"},
           {"region": "US", "UseSSOConfiguration": "SSOConfiguration", "SSOConfiguration": "https://example.us/old.json",
            "baseUrlCareLink": "https://cl.example.us/api", "baseUrlCumulus": "https://cumulus.example.us/connect"}
         ]}
    """.trimIndent()

    /** Discovery where EU has only the old (captcha) sign-in. */
    val DISCOVERY_NO_AUTH0 = """
        {"supportedCountries": [],
         "CP": [{"region": "EU", "UseSSOConfiguration": "SSOConfiguration", "SSOConfiguration": "https://example.eu/old.json",
                 "baseUrlCareLink": "https://cl.example.eu/api", "baseUrlCumulus": "https://cumulus.example.eu/c"}]}
    """.trimIndent()

    val SSO = """
        {"server": {"hostname": "auth.example.eu", "port": 443, "prefix": ""},
         "system_endpoints": {"authorization_endpoint_path": "/authorize", "token_endpoint_path": "/oauth/token"},
         "client": {"client_id": "abc123", "scope": "openid profile offline_access", "redirect_uri": "$REDIRECT",
                    "audience": "https://api.example.eu"}}
    """.trimIndent()

    fun jwt(payload: String): String {
        val enc = Base64.getUrlEncoder().withoutPadding()
        return enc.encodeToString("""{"alg":"none"}""".toByteArray()) + "." +
            enc.encodeToString(payload.toByteArray()) + ".sig"
    }

    /** An access key for the invented user "tester" in DE, expiring at [expSec] (seconds). */
    fun access(expSec: Long, user: String = "tester"): String =
        jwt("""{"exp": $expSec, "token_details": {"country": "DE", "preferred_username": "$user"}}""")

    fun ok(body: String) = HttpResult(200, body, null)
    fun status(code: Int, body: String? = null) = HttpResult(code, body, null)
    val NO_NETWORK = HttpResult(0, null, IOException("made-up: no route"))
}

/** A fake network: answers by address, in order (the last answer repeats); records every call. */
internal class FakeCareLinkHttp : CareLinkHttp {
    val calls = mutableListOf<String>()
    val forms = mutableListOf<Map<String, String>>()
    private val routes = mutableMapOf<String, ArrayDeque<HttpResult>>()

    /** Runs inside a form request, before its answer (e.g. a sign-out racing a key renewal). */
    var onForm: (() -> Unit)? = null

    fun on(url: String, vararg answers: HttpResult) {
        routes[url] = ArrayDeque(answers.toList())
    }

    private fun answer(url: String): HttpResult {
        val q = routes[url] ?: return HttpResult(404, null, null)
        return if (q.size > 1) q.removeFirst() else q.first()
    }

    override fun get(url: String, headers: Map<String, String>): HttpResult {
        calls += "GET $url"
        return answer(url)
    }

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>): HttpResult {
        calls += "FORM $url ${form["grant_type"]}"
        forms += form
        onForm?.invoke()
        return answer(url)
    }

    /** Bodies of JSON requests, in order (4c: the `display/message` request). */
    val jsons = mutableListOf<String>()

    override fun postJson(url: String, json: String, headers: Map<String, String>): HttpResult {
        calls += "JSON $url"
        jsons += json
        return answer(url)
    }
}
