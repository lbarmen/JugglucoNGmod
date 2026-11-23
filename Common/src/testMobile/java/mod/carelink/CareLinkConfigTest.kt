package mod.carelink

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.mod.carelink.CareLinkConfig
import tk.glucodata.mod.carelink.CareLinkSession

/**
 * JugglucoNGmod, CareLink 4b: discovery, sign-in settings, return address and key parsing on made-up answers (moved
 * from Glucove's CareLinkConfigTest to org.json). Robolectric: org.json is Android's implementation here, as on the phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkConfigTest {

    private val discovery get() = CareLinkConfig.parse(CareLinkTestData.DISCOVERY)!!
    private val sso get() = CareLinkConfig.sso(CareLinkConfig.parse(CareLinkTestData.SSO)!!)!!

    @Test
    fun regionWithoutCountryIsEuAuth0AndAddressesLoseTrailingSlash() {
        val r = CareLinkConfig.region(discovery, null)!!
        assertEquals("EU", r.region)
        assertTrue(r.auth0)
        assertEquals("https://example.eu/sso.json", r.ssoUrl)
        assertEquals("https://cl.example.eu/api/carepartner/v2", r.baseUrlCareLink)
    }

    @Test
    fun regionByAccountCountryOldSignInIsNotAuth0() {
        assertEquals("EU", CareLinkConfig.region(discovery, "de")!!.region)
        val us = CareLinkConfig.region(discovery, "US")!!
        assertEquals("US", us.region)
        assertFalse(us.auth0)
        assertEquals("EU", CareLinkConfig.region(discovery, "XX")!!.region)
        assertFalse(CareLinkConfig.region(CareLinkConfig.parse(CareLinkTestData.DISCOVERY_NO_AUTH0)!!, null)!!.auth0)
        // an address in the clear from the cloud is refused: keys never go over plain http
        val plain = CareLinkTestData.DISCOVERY.replace("https://cl.example.eu", "http://cl.example.eu")
        assertNull(CareLinkConfig.region(CareLinkConfig.parse(plain)!!, null))
    }

    @Test
    fun signInSettingsGivePageAndKeyAddresses() {
        assertEquals("https://auth.example.eu:443/authorize", sso.authorizeUrl)
        assertEquals("https://auth.example.eu:443/oauth/token", sso.tokenUrl)
        assertEquals("abc123", sso.clientId)
        assertEquals("https://api.example.eu", sso.audience)
        // JSON null and a missing field are both "absent", on either org.json
        val noAudience = CareLinkConfig.sso(
            CareLinkConfig.parse(CareLinkTestData.SSO.replace("\"audience\": \"https://api.example.eu\"", "\"audience\": null"))!!,
        )!!
        assertNull(noAudience.audience)
    }

    @Test
    fun signInPageAddressIsEncodedAndCarriesTheMark() {
        val url = CareLinkConfig.authorizeUrl(sso, "st-1")
        assertTrue(url.startsWith("https://auth.example.eu:443/authorize?client_id=abc123&response_type=code"))
        assertTrue(url.contains("scope=openid%20profile%20offline_access"))
        assertTrue(url.contains("redirect_uri=com.example.app%3A%2F%2Fsso%2Fcallback"))
        assertTrue(url.contains("audience=https%3A%2F%2Fapi.example.eu"))
        assertTrue(url.endsWith("&state=st-1"))
    }

    @Test
    fun returnAddressGivesCodeAndMarkOrThePageError() {
        assertTrue(CareLinkConfig.isRedirect("com.example.app://sso/callback?code=xyz&state=st-1", sso.redirectUri))
        assertTrue(CareLinkConfig.isRedirect("COM.EXAMPLE.APP://sso/callback/?code=1", sso.redirectUri))
        assertFalse(CareLinkConfig.isRedirect("https://auth.example.eu/u/login?state=st-1", sso.redirectUri))
        val ok = CareLinkConfig.redirect("com.example.app://sso/callback?code=a%2Bb&state=st-1")
        assertEquals("a+b", ok.code)
        assertEquals("st-1", ok.state)
        assertNull(ok.error)
        val fragment = CareLinkConfig.redirect("com.example.app://sso/callback#code=f1&state=st-2")
        assertEquals("f1", fragment.code)
        assertEquals("st-2", fragment.state)
        val denied = CareLinkConfig.redirect(
            "com.example.app://sso/callback?error=access_denied&error_description=User%20cancelled&state=st-1",
        )
        assertNull(denied.code)
        assertEquals("User cancelled", denied.error)
        // broken %-encoding - not a crash but "sign-in did not finish"
        val bad = CareLinkConfig.redirect("com.example.app://sso/callback?code=%zz&state=st-1")
        assertNull(bad.code)
        assertNull(bad.state)
        assertEquals("bad_redirect", bad.error)
    }

    @Test
    fun keyGivesCountryUserAndExpiryFromJwt() {
        val c = CareLinkConfig.claims(CareLinkTestData.access(1_762_160_000L))
        assertEquals("DE", c.country)
        assertEquals("tester", c.username)
        assertEquals(1_762_160_000_000L, c.expMs)
        val top = CareLinkConfig.claims(CareLinkTestData.jwt("""{"exp": "1700000000", "preferred_username": "user-b"}"""))
        assertEquals("user-b", top.username)
        assertEquals(1_700_000_000_000L, top.expMs)
        assertNull(top.country)
        val broken = CareLinkConfig.claims("not-a-key")
        assertNull(broken.username)
        assertNull(broken.expMs)
        assertNull(CareLinkConfig.claims("a.%%%.c").username)
    }

    @Test
    fun keyAnswerAndRequestForms() {
        val t = CareLinkConfig.tokens(CareLinkConfig.parse("""{"access_token": "A", "refresh_token": "R", "expires_in": 10775}""")!!)!!
        assertEquals("A", t.access)
        assertEquals("R", t.refresh)
        assertEquals(10775L, t.expiresInSec)
        val noRefresh = CareLinkConfig.tokens(CareLinkConfig.parse("""{"access_token": "A", "refresh_token": null}""")!!)!!
        assertNull(noRefresh.refresh)
        assertNull(CareLinkConfig.tokens(CareLinkConfig.parse("""{"error": "invalid_grant"}""")!!))
        assertEquals(
            "invalid_grant: Unknown or invalid refresh token.",
            CareLinkConfig.tokenError(
                CareLinkConfig.parse("""{"error": "invalid_grant", "error_description": "Unknown or invalid refresh token."}"""),
            ),
        )
        assertEquals("refresh_token", CareLinkConfig.refreshForm("abc123", "R")["grant_type"])
        val code = CareLinkConfig.codeForm(sso, "xyz")
        assertEquals("authorization_code", code["grant_type"])
        assertEquals(sso.redirectUri, code["redirect_uri"])
        assertEquals("xyz", code["code"])
        assertNull(CareLinkConfig.parse("{"))
        assertNull(CareLinkConfig.parse(null))
    }

    @Test
    fun sessionToStringHasNoKeysAndJsonRoundTrips() {
        val s = CareLinkSession(
            accessToken = "ACCESS-SECRET", refreshToken = "REFRESH-SECRET", expiresAtMs = 1L, username = "tester",
            role = null, patientId = null, country = null, region = "EU", baseUrlCareLink = "https://cl",
            baseUrlCumulus = "https://cu", tokenUrl = sso.tokenUrl, clientId = "abc123", loggedInAtMs = 0L,
        )
        assertFalse(s.toString().contains("SECRET"))
        assertFalse(s.toString().contains("tester"))
        assertEquals(s, CareLinkSession.decode(s.toJson().toString()))
        val partner = s.copy(role = "CARE_PARTNER", patientId = "patient-a", country = "DE", refreshedAtMs = 5L)
        assertEquals(partner, CareLinkSession.decode(partner.toJson().toString()))
        assertNotNull(CareLinkSession.decode(partner.toJson().toString()))
        assertTrue(s.copy(role = "CARE_PARTNER_OUS").carePartner)
        assertFalse(s.copy(role = "PATIENT").carePartner)
        assertNull(CareLinkSession.decode("{"))
        assertNull(CareLinkSession.decode("""{"accessToken": "A"}"""))
    }
}
