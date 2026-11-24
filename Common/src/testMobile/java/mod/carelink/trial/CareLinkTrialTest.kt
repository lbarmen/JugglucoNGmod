package mod.carelink.trial

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import mod.carelink.FakeCareLinkWork
import tk.glucodata.mod.carelink.AesGcmSessionCipher
import tk.glucodata.mod.carelink.CareLinkAuth
import tk.glucodata.mod.carelink.CareLinkConfig
import tk.glucodata.mod.carelink.CareLinkErrorKind
import tk.glucodata.mod.carelink.CareLinkPoller
import tk.glucodata.mod.carelink.CareLinkScheduler
import tk.glucodata.mod.carelink.CareLinkSettings
import tk.glucodata.mod.carelink.CareLinkStateStore
import tk.glucodata.mod.carelink.CareLinkStatus
import tk.glucodata.mod.carelink.CareLinkVault
import tk.glucodata.mod.carelink.ClEvent
import tk.glucodata.mod.carelink.FreshResult
import tk.glucodata.mod.carelink.PumpNow
import tk.glucodata.mod.carelink.ModCareLinkStarter
import tk.glucodata.mod.carelink.trial.CareLinkTrial
import tk.glucodata.mod.carelink.trial.CareLinkTrialHttp
import tk.glucodata.mod.carelink.trial.CareLinkTrialPump
import tk.glucodata.mod.carelink.trial.TrialCommand
import java.time.ZoneOffset
import javax.crypto.KeyGenerator

/**
 * JugglucoNGmod, CareLink trial (part 4p): commands, the fake network (never the real one, the `fail`/`answer` queue
 * only for a `poll` command) and the real poll behind them - `fail 401` ends in "sign in again", `401-renew` in a good
 * poll with a renewed key. Writers are recorded (their own tests are 4d's). Made-up user `trial` and doses only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class CareLinkTrialTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val work = FakeCareLinkWork()
    private var now = 1_800_000_000_000L
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val journal = mutableListOf<List<ClEvent>>()
    private val basal = mutableListOf<PumpNow?>()
    private val http get() = CareLinkTrial.http

    private fun run(cmd: String, arg: String? = null) = runBlocking { CareLinkTrial.run(app, cmd, arg) }

    private val state get() = CareLinkStateStore(app).read()

    @Before
    fun setUp() {
        CareLinkAuth.resetForTest()
        CareLinkPoller.resetForTest()
        CareLinkScheduler.resetForTest()
        CareLinkTrial.resetForTest()
        CareLinkScheduler.work = work
        CareLinkVault.cipher = AesGcmSessionCipher(keyProvider = { key })
        CareLinkVault.clear(app)
        CareLinkStateStore(app).clear()
        CareLinkAuth.clock = { now }
        CareLinkPoller.zone = { ZoneOffset.ofHours(5) }
        CareLinkTrial.zone = { ZoneOffset.ofHours(5) }
        CareLinkPoller.writeJournal = { _, e, _ -> journal += e }
        CareLinkPoller.writeBasal = { _, p, _ -> basal += p }
        CareLinkPoller.publishIob = { }
        // as the starter sets it on the phone
        CareLinkAuth.onSignOut = { c -> ModCareLinkStarter.afterSignOut(c) }
        CareLinkTrial.installHttp()
    }

    @After
    fun tearDown() {
        CareLinkVault.clear(app)
        CareLinkAuth.resetForTest()
        CareLinkPoller.resetForTest()
        CareLinkScheduler.resetForTest()
        CareLinkTrial.resetForTest()
        CareLinkVault.cipher = AesGcmSessionCipher.keystore
    }

    // ---------------------------------------------------------------- commands

    @Test
    fun commandsParse() {
        assertEquals(TrialCommand.Login, CareLinkTrial.parse("login", null))
        assertEquals(TrialCommand.Poll, CareLinkTrial.parse(" POLL ", null))
        assertEquals(TrialCommand.Step("square", listOf(2.5, 45.0)), CareLinkTrial.parse("step", "square:2.5:45"))
        assertEquals(TrialCommand.Step("meal", listOf(30.0, 3.0)), CareLinkTrial.parse("step", "meal 30 3"))
        assertEquals(TrialCommand.Step("bolus-done", emptyList()), CareLinkTrial.parse("step", "bolus-done"))
        assertEquals(TrialCommand.Answer(CareLinkTrialPump.Shape.EMPTY), CareLinkTrial.parse("answer", "empty"))
        assertEquals(TrialCommand.Fail("401-renew"), CareLinkTrial.parse("fail", "401-renew"))
        assertEquals(TrialCommand.Clock(40), CareLinkTrial.parse("clock", "+40"))
        assertEquals(TrialCommand.Clock(0), CareLinkTrial.parse("clock", "0"))
        for ((cmd, arg) in listOf(
            "jump" to null, "step" to null, "step" to "fly", "step" to "bolus:x", "step" to "bolus:1:2",
            "answer" to "late", "fail" to "404", "clock" to "soon", "clock" to "+5000", null to null,
        )) {
            val r = run(cmd ?: "", arg)
            assertTrue("$cmd $arg -> $r", r.startsWith("error:"))
        }
    }

    @Test
    fun clockShiftsTheAuthClockAndComesBack() {
        run("clock", "+40")
        val shifted = CareLinkAuth.clock() - System.currentTimeMillis()
        assertTrue("about +40 min: $shifted", shifted in 39 * 60_000L..41 * 60_000L)
        run("clock", "0")
        assertTrue(kotlin.math.abs(CareLinkAuth.clock() - System.currentTimeMillis()) < 60_000L)
    }

    @Test
    fun starterWithoutTheTrialEntryGoesOnSilently() {
        // tests (and a candidate) have no Common/src/modTrial: the class is not found, nothing throws
        CareLinkAuth.resetForTest()
        ModCareLinkStarter.installTrial(app)
        assertFalse(CareLinkAuth.http === http)
    }

    // ---------------------------------------------------------------- the fake network

    @Test
    fun otherAddressesAreNoNetworkWithoutTheNetwork() {
        val r = http.get(CareLinkConfig.DISCOVERY_URL)
        assertEquals(0, r.code)
        assertEquals("IOException", r.errorClass)
        assertEquals(0, http.postForm("https://example.invalid/token", emptyMap()).code)
        assertEquals(0, http.postJson("https://example.invalid/display/message", "{}").code)
        assertEquals(3, http.refused)
        assertEquals(200, http.get(CareLinkTrial.CARELINK + "/users/me").code)
    }

    @Test
    fun queueGoesOnlyToAManualPoll() {
        http.queueMessage(CareLinkTrialHttp.Next.Status(500, "{}"))
        val dm = CareLinkTrial.CUMULUS + "/display/message"
        assertEquals("a poll of the alarm or WorkManager: the normal answer", 200, http.postJson(dm, "{}").code)
        assertEquals(1, http.queued())
        val manual = runBlocking { withContext(http.manual()) { http.postJson(dm, "{}") } }
        assertEquals(500, manual.code)
        assertEquals(0, http.queued())
    }

    @Test
    fun renewedKeyIsAParsableJwt() {
        val r = http.postForm(CareLinkTrial.TOKEN_URL, emptyMap())
        val tokens = CareLinkConfig.tokens(CareLinkConfig.parse(r.body)!!)!!
        val claims = CareLinkConfig.claims(tokens.access)
        assertEquals("trial", claims.username)
        assertEquals((now + CareLinkTrial.KEY_LIFE_MS) / 1000 * 1000, claims.expMs)
        assertNotNull(tokens.refresh)
    }

    // ---------------------------------------------------------------- through the real poll

    private fun login() {
        assertTrue(run("login").startsWith("login ok"))
    }

    @Test
    fun loginStoresTheTrialSessionAndStartsPolling() {
        login()
        assertTrue(CareLinkAuth.hasSession(app))
        assertTrue(CareLinkSettings(app).enabled)
        assertTrue("first poll queued as after a real sign-in: ${work.log}", work.log.any { it.startsWith("poll LOGIN") })
        val s = runBlocking { CareLinkAuth.ensureFresh(app) }
        assertTrue(s is FreshResult.Ok)
        val session = (s as FreshResult.Ok).session
        assertEquals("trial", session.username)
        assertEquals("PATIENT", session.role)
        assertTrue(session.baseUrlCumulus.startsWith("https://trial.invalid/"))
    }

    @Test
    fun pollWritesTheStepsAndMovesTheDataTime() {
        login()
        run("step", "meal:40:4")
        val r = run("poll")
        assertTrue(r, r.startsWith("poll: status=OK"))
        assertEquals(CareLinkStatus.OK, state.status)
        assertEquals(now - CareLinkTrialPump.DATA_LAG_MS, state.lastDataAtMs)
        assertEquals(2, journal.last().size)
        assertNotNull(basal.last())
    }

    @Test
    fun fail401EndsInSignInNeeded() {
        login()
        run("fail", "401")
        run("poll")
        assertEquals(CareLinkStatus.NEED_LOGIN, state.status)
        assertFalse("invalid_grant wipes the session", CareLinkAuth.hasSession(app))
        assertEquals(0, http.queued())
    }

    @Test
    fun fail401RenewEndsInAGoodPollWithARenewedKey() {
        login()
        run("fail", "401-renew")
        run("poll")
        assertEquals(CareLinkStatus.OK, state.status)
        val s = (runBlocking { CareLinkAuth.ensureFresh(app) } as FreshResult.Ok).session
        assertTrue("renewed: ${s.refreshedAtMs}", s.refreshToken.startsWith("trial-refresh-"))
        assertEquals(now, s.refreshedAtMs)
    }

    @Test
    fun serverNetworkAndJunkAreErrorsThatKeepTheSession() {
        login()
        run("poll")
        val dataAt = state.lastDataAtMs
        for ((kind, expected) in listOf(
            "500" to CareLinkErrorKind.SERVER, "net" to CareLinkErrorKind.NO_NETWORK, "junk" to CareLinkErrorKind.BAD_ANSWER,
        )) {
            run("fail", kind)
            now += 60_000L
            run("poll")
            assertEquals(kind, CareLinkStatus.ERROR, state.status)
            assertEquals(kind, expected, state.lastError?.kind)
            assertEquals(dataAt, state.lastDataAtMs)
            assertTrue(CareLinkAuth.hasSession(app))
        }
    }

    @Test
    fun emptyAnswerKeepsTheDataTimeAndOldAnswerIsStale() {
        login()
        run("poll")
        val dataAt = state.lastDataAtMs
        now += 40 * 60_000L
        run("answer", "empty")
        run("poll")
        assertEquals(CareLinkStatus.OK, state.status)
        assertEquals("the empty answer brings no data time", dataAt, state.lastDataAtMs)
        assertNull(basal.last())
        val writes = basal.size
        run("answer", "old")
        run("poll")
        assertEquals("an older answer goes to no basal", writes, basal.size)
    }

    @Test
    fun logoutWipesTheSessionAndStopsPolling() {
        login()
        assertEquals("logout ok", run("logout"))
        assertFalse(CareLinkAuth.hasSession(app))
        assertFalse(CareLinkSettings(app).enabled)
        assertTrue(run("state").contains("session=false"))
    }
}
