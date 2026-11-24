package tk.glucodata.mod.carelink

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * JugglucoNGmod, CareLink (stage 4b): addresses and sign-in rules of the Medtronic CareLink cloud (the unofficial
 * interface of the CareLink Connect app). Own code: from other clients (xDrip+ - GPL, carelink-python-client - AGPL)
 * only the knowledge of the requests is taken.
 *
 * Order: discovery ([DISCOVERY_URL]) -> region (EU / US) -> sign-in settings (SSO, the Auth0 path) -> Medtronic
 * sign-in page -> one-time code in the return address -> session keys (access + refresh). Pure Kotlin on org.json.
 * JSON is read with has/isNull/opt and explicit casts: org.json on the phone (Android) and in plain JVM tests
 * (JSON-java) differ on JSON null.
 */
object CareLinkConfig {

    /** Discovery v13: regions, API addresses and sign-in settings. A new interface version is changed here. */
    const val DISCOVERY_URL = "https://clcloud.minimed.eu/connect/carepartner/v13/discover/android/3.6"

    /** Sign-in region: the account country is unknown before sign-in; EU worked on the live account. */
    const val LOGIN_REGION = "EU"

    /** A region from discovery: sign-in settings address, Auth0 path or the old gateway, API bases. */
    data class Region(
        val region: String,
        val ssoUrl: String,
        val auth0: Boolean,
        val baseUrlCareLink: String,
        val baseUrlCumulus: String,
    )

    /** Auth0 sign-in settings: sign-in page; code exchange and key refresh share one [tokenUrl]. */
    data class Sso(
        val authorizeUrl: String,
        val tokenUrl: String,
        val clientId: String,
        val scope: String,
        val redirectUri: String,
        val audience: String?,
    )

    /** What the access key (JWT) says: account country, user name for data requests, expiry. */
    data class Claims(val country: String?, val username: String?, val expMs: Long?)

    /** Return address of the sign-in page: [code] - one-time code, [error] - the page refused (Auth0 error). */
    data class Redirect(val code: String?, val state: String?, val error: String?) {
        override fun toString(): String = "Redirect(code=${code != null}, state=${state != null}, error=${error != null})"
    }

    /** Answer to a key request: access, refresh (may be absent on refresh - then the old one), lifetime in seconds. */
    data class Tokens(val access: String, val refresh: String?, val expiresInSec: Long?) {
        override fun toString(): String = "Tokens(refresh=${refresh != null}, expiresInSec=$expiresInSec)"
    }

    fun parse(text: String?): JSONObject? {
        if (text == null) return null
        return try {
            JSONObject(text)
        } catch (_: Exception) {
            null
        }
    }

    /** A JSON array of the top level ("[...]"); null - not an array. */
    fun parseArray(text: String?): JSONArray? {
        if (text == null) return null
        return try {
            JSONArray(text)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Region by the account country [country] (from the key; null - before sign-in, then [LOGIN_REGION]):
     * `supportedCountries` is a list like `[{"DE": {"region": "EU"}}]`, `CP` - regions with the key
     * `UseSSOConfiguration` (name of the field holding the sign-in settings address; "Auth0" in the name - the new
     * sign-in of 01.2026). A country not in the list - [LOGIN_REGION] as well.
     */
    fun region(discovery: JSONObject, country: String?): Region? {
        val wanted = country?.uppercase()?.let { c ->
            val list = discovery.optJSONArray("supportedCountries")
            var found: String? = null
            if (list != null) {
                for (i in 0 until list.length()) {
                    val item = list.optJSONObject(i) ?: continue
                    found = item.optJSONObject(c)?.clStr("region")
                    if (found != null) break
                }
            }
            found
        } ?: LOGIN_REGION
        val cp = discovery.optJSONArray("CP") ?: return null
        var c: JSONObject? = null
        for (i in 0 until cp.length()) {
            val o = cp.optJSONObject(i) ?: continue
            if (o.clStr("region").equals(wanted, ignoreCase = true)) {
                c = o
                break
            }
        }
        if (c == null) return null
        val key = c.clStr("UseSSOConfiguration") ?: return null
        return Region(
            region = c.clStr("region") ?: wanted,
            ssoUrl = c.clStr(key)?.takeIf(::isHttps) ?: return null,
            auth0 = key.contains("Auth0", ignoreCase = true),
            baseUrlCareLink = c.clStr("baseUrlCareLink")?.takeIf(::isHttps)?.trimEnd('/') ?: return null,
            baseUrlCumulus = c.clStr("baseUrlCumulus")?.takeIf(::isHttps)?.trimEnd('/') ?: return null,
        )
    }

    /** Auth0 sign-in settings: `server` (hostname, port, prefix), `system_endpoints` (paths), `client` (client_id...). */
    fun sso(config: JSONObject): Sso? {
        val server = config.optJSONObject("server") ?: return null
        val host = server.clStr("hostname") ?: return null
        val port = server.clInt("port") ?: 443
        val prefix = server.clStr("prefix").orEmpty().trim('/')
        val base = "https://$host:$port" + if (prefix.isEmpty()) "" else "/$prefix"
        val endpoints = config.optJSONObject("system_endpoints") ?: return null
        val client = config.optJSONObject("client") ?: return null
        return Sso(
            authorizeUrl = base + (endpoints.clStr("authorization_endpoint_path") ?: return null),
            tokenUrl = base + (endpoints.clStr("token_endpoint_path") ?: return null),
            clientId = client.clStr("client_id") ?: return null,
            scope = client.clStr("scope").orEmpty(),
            redirectUri = client.clStr("redirect_uri") ?: return null,
            audience = client.clStr("audience"),
        )
    }

    /** Medtronic sign-in page: the code comes to the return address; [state] - our mark, checked on return. */
    fun authorizeUrl(sso: Sso, state: String): String {
        val params = linkedMapOf(
            "client_id" to sso.clientId,
            "response_type" to "code",
            "scope" to sso.scope,
            "redirect_uri" to sso.redirectUri,
        )
        sso.audience?.let { params["audience"] = it }
        params["state"] = state
        return sso.authorizeUrl + "?" + params.entries.joinToString("&") { (k, v) -> k + "=" + encode(v) }
    }

    /** Fields of the request "code -> session keys". */
    fun codeForm(sso: Sso, code: String): Map<String, String> = codeForm(sso.clientId, sso.redirectUri, code)

    fun codeForm(clientId: String, redirectUri: String, code: String): Map<String, String> = linkedMapOf(
        "grant_type" to "authorization_code",
        "client_id" to clientId,
        "code" to code,
        "redirect_uri" to redirectUri,
    )

    /** Fields of the key refresh request (no password, by the refresh key). */
    fun refreshForm(clientId: String, refreshToken: String): Map<String, String> = linkedMapOf(
        "refresh_token" to refreshToken,
        "client_id" to clientId,
        "grant_type" to "refresh_token",
    )

    /** Only https: keys and data never go in the clear, whatever an answer of the cloud names. */
    fun isHttps(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

    /** Is the address the return from the sign-in page ([redirectUri], scheme and host in any case)? */
    fun isRedirect(url: String, redirectUri: String): Boolean {
        val u = url.substringBefore('?').substringBefore('#').trimEnd('/')
        val r = redirectUri.substringBefore('?').trimEnd('/')
        return u.equals(r, ignoreCase = true)
    }

    /**
     * Code, mark and error from the return address (query, and after "#" for some servers). Broken %-encoding
     * (URLDecoder throws IllegalArgumentException) - `Redirect(null, null, "bad_redirect")`, not a crash.
     */
    fun redirect(url: String): Redirect {
        val query = url.substringAfter('?', "").substringBefore('#')
        val fragment = url.substringAfter('#', "")
        val p = try {
            params(query) + params(fragment)
        } catch (_: IllegalArgumentException) {
            return Redirect(code = null, state = null, error = "bad_redirect")
        }
        return Redirect(code = p["code"], state = p["state"], error = p["error_description"] ?: p["error"])
    }

    /** The access key is a JWT: country (`token_details.country`), user name (`preferred_username`), expiry (`exp`, s). */
    fun claims(jwt: String): Claims {
        val payload = jwt.split('.').getOrNull(1) ?: return Claims(null, null, null)
        val o = try {
            parse(String(Base64.getUrlDecoder().decode(payload.trimEnd('=')), Charsets.UTF_8))
        } catch (_: IllegalArgumentException) {
            null
        } ?: return Claims(null, null, null)
        val details = o.optJSONObject("token_details")
        return Claims(
            country = details?.clStr("country"),
            username = details?.clStr("preferred_username") ?: o.clStr("preferred_username"),
            expMs = o.clLong("exp")?.let { it * 1000 },
        )
    }

    fun tokens(o: JSONObject): Tokens? = Tokens(
        access = o.clStr("access_token") ?: return null,
        refresh = o.clStr("refresh_token"),
        expiresInSec = o.clLong("expires_in"),
    )

    /** Error of the key server (Auth0: `error`, `error_description`); no secrets in them. */
    fun tokenError(o: JSONObject?): String? =
        o?.let { listOfNotNull(it.clStr("error"), it.clStr("error_description")).joinToString(": ").ifEmpty { null } }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun params(s: String): Map<String, String> = s.split('&').mapNotNull { part ->
        val eq = part.indexOf('=')
        if (eq <= 0) null else part.substring(0, eq) to URLDecoder.decode(part.substring(eq + 1), "UTF-8")
    }.toMap()
}

/** A string or number field as text; no field or JSON null - null (the same on Android's org.json and JSON-java). */
internal fun JSONObject.clStr(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is String -> v
        is Number, is Boolean -> v.toString()
        else -> null
    }
}

/** A whole number field (a number or a numeric string); no field, JSON null or not a number - null. */
internal fun JSONObject.clLong(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Int -> v.toLong()
        is Long -> v
        is Number -> v.toDouble().takeIf { it.isFinite() }?.toLong()
        is String -> v.trim().toLongOrNull()
        else -> null
    }
}

internal fun JSONObject.clInt(key: String): Int? =
    clLong(key)?.takeIf { it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
