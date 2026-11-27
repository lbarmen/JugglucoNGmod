package mod.pumpbt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tk.glucodata.mod.pumpbt.PumpBtKey
import tk.glucodata.mod.pumpbt.PumpBtNameVariant
import tk.glucodata.mod.pumpbt.PumpBtSourceState
import tk.glucodata.mod.pumpbt.PumpBtStore
import tk.glucodata.mod.pumpbt.PumpBtTrial
import tk.glucodata.mod.pumpbt.PumpBtUiState
import tk.glucodata.mod.pumpbt.PumpBtWindow
import tk.glucodata.mod.pumpbt.PumpBtWizardStep
import tk.glucodata.mod.pumpbt.core.KeyDatabase
import tk.glucodata.mod.pumpbt.core.KeyDatabaseError
import tk.glucodata.mod.pumpbt.core.SakeDeviceType
import tk.glucodata.mod.pumpbt.core.toHex
import java.io.File

/**
 * JugglucoNGmod, pump over Bluetooth: the key database and its loading - the chain of pages, another CRC, "Start
 * pairing" downloading the key first - on databases made up here (pattern bytes with a correct CRC). No real key in
 * this repository.
 */
class PumpBtKeyTest {

    private val mobilePump = fakeKeyDatabase(local = 4, remotes = listOf(1))
    private val sensorDisplay = fakeKeyDatabase(local = 8, remotes = listOf(2), seed = 2)

    @Test
    fun madeUpDatabaseWithRightCrcIsAccepted() {
        val db = KeyDatabase(mobilePump)
        assertEquals(SakeDeviceType.MOBILE_APPLICATION, db.localDeviceType)
        assertNotNull(db.remoteDevices[SakeDeviceType.INSULIN_PUMP])
        assertTrue(KeyDatabase.isMobilePump(db))

        val r = PumpBtKey.parse(mobilePump.toHex())
        assertTrue(r is PumpBtKey.Result.Ok)
        r as PumpBtKey.Result.Ok
        assertEquals(mobilePump.toHex(), r.hex)
        assertEquals(mobilePump.copyOf(4).toHex().uppercase(), r.crc)
    }

    @Test
    fun crcMismatchIsRefused() {
        val broken = mobilePump.copyOf().also { it[20] = (it[20].toInt() xor 0x01).toByte() }
        try {
            KeyDatabase(broken)
            fail("a wrong CRC must fail")
        } catch (_: KeyDatabaseError.CrcMismatch) {
        }
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.CRC), PumpBtKey.parse(broken.toHex()))
    }

    @Test
    fun wrongTypeIsRefused() {
        assertFalse(KeyDatabase.isMobilePump(KeyDatabase(sensorDisplay)))
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.WRONG_TYPE), PumpBtKey.parse(sensorDisplay.toHex()))
        // mobile application, but with keys for another device only
        val mobileSensor = fakeKeyDatabase(local = 4, remotes = listOf(2))
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.WRONG_TYPE), PumpBtKey.parse(mobileSensor.toHex()))
    }

    @Test
    fun garbageIsRefused() {
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.NOT_FOUND), PumpBtKey.parse(""))
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.NOT_FOUND), PumpBtKey.parse("hello, no key here"))
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.NOT_FOUND), PumpBtKey.parse("<html><body>404</body></html>"))
        // hex, but too short; a cut database no longer matches its CRC ("damaged")
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.NOT_FOUND), PumpBtKey.parse("0102"))
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.CRC), PumpBtKey.parse(mobilePump.toHex().dropLast(2)))
        try {
            KeyDatabase.fromHex("abc")
            fail("odd hex must fail")
        } catch (_: KeyDatabaseError.InvalidHex) {
        }
        try {
            KeyDatabase(ByteArray(5))
            fail("5 bytes must fail")
        } catch (_: KeyDatabaseError.TooShort) {
        }
    }

    @Test
    fun findsTheDatabaseOnAPageLikeKeyDatabasesMd() {
        // made-up page in the layout of OpenMinimed's key_databases.md: other database first, then ours
        val page = """
            # SAKE Key Databases

            ## Dumped Key DBs

            ### Primary Display <-> Glucose Sensor

            <code>
            ${spacedHex(sensorDisplay)}
            </code>

            ### Mobile Application <-> Insulin Pump

            <code>
            ${spacedHex(mobilePump)}
            </code>
        """.trimIndent()
        val found = KeyDatabase.findHex(page, KeyDatabase::isMobilePump)
        assertEquals(mobilePump.toHex(), found)
        val r = PumpBtKey.parse(page)
        assertTrue(r is PumpBtKey.Result.Ok)
        assertEquals(mobilePump.copyOf(4).toHex().uppercase(), (r as PumpBtKey.Result.Ok).crc)

        // a page with only the other database: the type is wrong, not "nothing found"
        val other = "<code>\n${spacedHex(sensorDisplay)}\n</code>"
        assertNull(KeyDatabase.findHex(other, KeyDatabase::isMobilePump))
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.WRONG_TYPE), PumpBtKey.parse(other))
    }

    @Test
    fun crcIsTheFirstFourBytes() {
        assertEquals("0A1B2C3D", PumpBtKey.crcOf("0a1b2c3d4e5f"))
        assertEquals("0A1B2C3D", PumpBtKey.crcOf("0a 1b 2c\n3d 4e"))
    }

    // MARK: - The chain (series brief, decision 11): the pinned page, then main

    private val ok = PumpBtKey.parse(mobilePump.toHex())

    @Test
    fun chainAsksThePinnedPageFirstAndNotMainWhenItGaveTheDatabase() {
        assertTrue(PumpBtKey.PINNED_URL.contains("/37ac341f900a0f2afa8e331fefc32ec7741fcf95/"))
        assertTrue(PumpBtKey.MAIN_URL.contains("/main/"))
        val asked = mutableListOf<String>()
        val attempts = PumpBtKey.downloadChain { url -> asked += url; ok }
        assertEquals(listOf(PumpBtKey.PINNED_URL), asked)
        assertTrue(attempts.single().result is PumpBtKey.Result.Ok)
    }

    @Test
    fun chainAsksMainWhenThePinnedPageGaveNone() {
        val asked = mutableListOf<String>()
        val attempts = PumpBtKey.downloadChain { url ->
            asked += url
            if (url == PumpBtKey.PINNED_URL) PumpBtKey.Result.Failed(PumpBtKey.Failure.HTTP, 404) else ok
        }
        assertEquals(listOf(PumpBtKey.PINNED_URL, PumpBtKey.MAIN_URL), asked)
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.HTTP, 404), attempts[0].result)
        assertTrue(attempts.last().result is PumpBtKey.Result.Ok)
    }

    @Test
    fun chainFailsWithTheReasonWhenBothPagesGaveNone() {
        val attempts = PumpBtKey.downloadChain { url ->
            if (url == PumpBtKey.PINNED_URL) PumpBtKey.Result.Failed(PumpBtKey.Failure.NOT_FOUND) else throw java.io.IOException("offline")
        }
        assertEquals(listOf(PumpBtKey.PINNED_URL, PumpBtKey.MAIN_URL), attempts.map { it.url })
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.NOT_FOUND), attempts[0].result)
        // the outcome is the last attempt's: main's reason
        assertEquals(PumpBtKey.Result.Failed(PumpBtKey.Failure.NO_NETWORK), attempts.last().result)
    }

    @Test
    fun anotherCrcIsAcceptedWithAJournalLine() {
        // the made-up database has its own CRC, not the trial's one: accepted (it checks itself)
        val r = ok as PumpBtKey.Result.Ok
        assertFalse(r.crc.equals(PumpBtKey.KNOWN_CRC, ignoreCase = true))
        val line = PumpBtKey.crcLine(r.crc)!!
        assertEquals("key database CRC ${r.crc}, tested with F75995E7", line)
        assertFalse("no key material in the line", line.contains(mobilePump.toHex().drop(8).take(16), ignoreCase = true))
        assertNull(PumpBtKey.crcLine("F75995E7"))
        assertNull(PumpBtKey.crcLine("f75995e7"))
    }

    // MARK: - "Start pairing" without a key (decision 11; plan, part G1, 2)

    @get:Rule
    val tmp = TemporaryFolder()

    private val readyUi = PumpBtUiState(hasBluetooth = true, bluetoothOn = true, permissionsGranted = true, advertisingSupported = true)

    @After
    fun backToAndroid() = PumpBtTrial.resetForTest(null)

    @Test
    fun startPairingWithoutAKeyDownloadsItThenStarts() {
        val t = FakeTrial(tmp.newFolder())
        t.pages[PumpBtKey.PINNED_URL] = ok
        PumpBtTrial.resetForTest(t.env, readyUi.copy(warningsAccepted = true))
        PumpBtTrial.startPairing()
        assertEquals("the main page is not asked", listOf(PumpBtKey.PINNED_URL), t.asked)
        assertEquals(listOf(true to PumpBtNameVariant.A), t.starts)
        val ui = PumpBtTrial.state.value
        assertEquals((ok as PumpBtKey.Result.Ok).crc, ui.keyCrc)
        assertNull(ui.pendingStart)
        assertFalse(ui.keyLoading)
        assertEquals(1_000L, ui.pairingStartedAtMs)
        assertTrue(t.journal.toString(), t.journal.any { it.startsWith("key database CRC ") && it.endsWith("tested with F75995E7") })
    }

    @Test
    fun aFailedDownloadWaitsForTheFileAndTheFileGoesOnWithThePairing() {
        val t = FakeTrial(tmp.newFolder())
        PumpBtTrial.resetForTest(t.env, readyUi.copy(warningsAccepted = true))
        PumpBtTrial.startPairing()
        assertEquals(listOf(PumpBtKey.PINNED_URL, PumpBtKey.MAIN_URL), t.asked)
        assertTrue("no pairing without a key", t.starts.isEmpty())
        val ui = PumpBtTrial.state.value
        assertEquals(PumpBtKey.Failure.HTTP, ui.keyFailure)
        assertEquals(404, ui.keyFailureCode)
        assertEquals(true, ui.pendingStart)
        assertEquals(PumpBtWizardStep.KEY, PumpBtWindow.wizardStep(ui, PumpBtSourceState()))
        assertTrue(t.journal.toString(), t.journal.any { it.contains("pinned commit") && it.contains("http 404") })

        // "Load from file": the start goes on
        PumpBtTrial.applyKey(ok, "a file")
        assertEquals(listOf(true to PumpBtNameVariant.A), t.starts)
        assertNull(PumpBtTrial.state.value.pendingStart)
        assertEquals(PumpBtWizardStep.START, PumpBtWindow.wizardStep(PumpBtTrial.state.value, PumpBtSourceState()))
    }

    @Test
    fun aKeyThatIsNotInPlaceAfterTheDownloadIsNotDownloadedAgain() {
        val t = FakeTrial(tmp.newFolder())
        t.pages[PumpBtKey.PINNED_URL] = ok
        t.keepsNoKey = true
        PumpBtTrial.resetForTest(t.env, readyUi.copy(warningsAccepted = true))
        PumpBtTrial.startPairing()
        assertEquals(1, t.asked.size)
        assertTrue(t.starts.isEmpty())
        assertNull(PumpBtTrial.state.value.pendingStart)
        assertTrue(t.journal.toString(), t.journal.any { it == "start refused: no_key" })
    }

    @Test
    fun withAKeyThePairingStartsAtOnceAndWithoutBluetoothNothingHappens() {
        val t = FakeTrial(tmp.newFolder())
        t.key = true
        PumpBtTrial.resetForTest(t.env, readyUi.copy(warningsAccepted = true, keyCrc = "01020304"))
        PumpBtTrial.startPairing()
        assertTrue(t.asked.isEmpty())
        assertEquals(listOf(true to PumpBtNameVariant.A), t.starts)

        val n = FakeTrial(tmp.newFolder())
        PumpBtTrial.resetForTest(n.env, readyUi.copy(bluetoothOn = false, warningsAccepted = true))
        PumpBtTrial.startPairing()
        assertTrue("nothing downloaded for a phone that cannot pair", n.asked.isEmpty())
        assertTrue(n.starts.isEmpty())
        assertTrue(n.journal.any { it == "start refused: bluetooth_off" })
    }
}

/**
 * The pump window's environment made up for [PumpBtTrial] (no Android, no network): pages by address (others - HTTP
 * 404), a key in place after a save, link starts with the name variant they start with.
 */
internal class FakeTrial(dir: File) {
    var key = false
    var keepsNoKey = false
    var sourceOn = false
    var running = false
    var stops = 0
    val asked = mutableListOf<String>()
    val pages = mutableMapOf<String, PumpBtKey.Result>()
    val starts = mutableListOf<Pair<Boolean, PumpBtNameVariant>>()
    val journal = mutableListOf<String>()
    val store = PumpBtStore(File(dir, PumpBtStore.FILE), null)

    val env = PumpBtTrial.Env(
        checkPhone = {},
        hasKey = { key },
        download = { url ->
            asked += url
            pages[url] ?: PumpBtKey.Result.Failed(PumpBtKey.Failure.HTTP, 404)
        },
        offMain = { _, block -> block() },
        onMain = { it() },
        saveKey = { if (!keepsNoKey) key = true },
        startLink = { pair ->
            starts += pair to store.variant
            running = true
        },
        stopLink = {
            stops++
            running = false
        },
        linkRunning = { running },
        sourceOn = { sourceOn },
        store = { store },
        journal = { journal += it },
        now = { 1_000L },
    )
}
