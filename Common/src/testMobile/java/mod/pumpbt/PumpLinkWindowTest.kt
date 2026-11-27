package mod.pumpbt

import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Looper
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import tk.glucodata.mod.carelink.CareLinkSettings
import tk.glucodata.mod.pumpbt.PumpBtLinkState
import tk.glucodata.mod.pumpbt.PumpBtStore
import tk.glucodata.mod.pumpbt.PumpBtValues
import tk.glucodata.mod.pumpbt.ble.PumpLink
import tk.glucodata.mod.pumpbt.core.ActiveBasalRate
import tk.glucodata.mod.pumpbt.core.KeyDatabase
import java.io.File

/**
 * PumpLink, what the trial window sees (critic of the joints 1, P-N5; critic B2b, second round, N-1): a temporary basal
 * in its own unit - U/h only for type 0x33, percent only for 0x3C, else its type; a start over a running link publishes
 * the mode and clears an earlier failed pairing, as a fresh start does. And the stored "no data" threshold (part G1).
 * Made-up answers and key bytes only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PumpLinkWindowTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val dir get() = File(app.noBackupFilesDir, PumpBtStore.DIR)
    private val published = mutableListOf<PumpBtLinkState>()
    private val journal = mutableListOf<String>()
    private var store: PumpBtStore? = null
    private var link: PumpLink? = null

    @Before
    fun setUp() {
        dir.deleteRecursively()
    }

    @After
    fun tearDown() {
        link?.stop()
        store?.awaitWrites()
        dir.deleteRecursively()
    }

    /** Active basal answer: profile 1, normal 0.8 U/h, a temporary basal of [type] with value [valueHex], 45 min left. */
    private fun answer(type: String, valueHex: String) =
        ActiveBasalRate.parse(hex("6a0301" + "01" + "080000ff" + type + valueHex + "7800" + "2d00"))

    @Test
    fun anAbsoluteTemporaryBasalIsShownInUnitsPerHour() {
        val v = PumpLink.withActiveBasal(PumpBtValues(), answer("33", "0c0000ff"))
        assertEquals(1.2, v.tempBasalUnitsPerHour!!, 1e-9)
        assertNull(v.tempBasalPercent)
        assertEquals(0.8, v.basalUnitsPerHour!!, 1e-9)
        assertTrue(v.basalRead)
        assertEquals(45, v.tempBasalRemainingMin)
    }

    @Test
    fun aPercentageTemporaryBasalIsShownInPercentNeverAsUnitsPerHour() {
        val v = PumpLink.withActiveBasal(PumpBtValues(), answer("3c", "96000000"))
        assertEquals(150, v.tempBasalPercent)
        assertNull("150 % is not 150 U/h", v.tempBasalUnitsPerHour)
        assertEquals(0.8, v.basalUnitsPerHour!!, 1e-9)
    }

    @Test
    fun aTemporaryBasalOfAnotherTypeOrASpecialValueIsShownInNeitherUnit() {
        val other = PumpLink.withActiveBasal(PumpBtValues(), answer("0f", "96000000"))
        assertNull(other.tempBasalUnitsPerHour)
        assertNull(other.tempBasalPercent)
        // NaN (special value): neither
        val special = PumpLink.withActiveBasal(PumpBtValues(), answer("3c", "ffff7f00"))
        assertNull(special.tempBasalUnitsPerHour)
        assertNull(special.tempBasalPercent)
        // no temporary basal: an earlier one is gone from the window
        val before = PumpBtValues(tempBasalUnitsPerHour = 1.2, tempBasalPercent = 150)
        val none = PumpLink.withActiveBasal(before, ActiveBasalRate.parse(hex("6a0300" + "01" + "080000ff")))
        assertNull(none.tempBasalUnitsPerHour)
        assertNull(none.tempBasalPercent)
        assertTrue(none.basalRead)
    }

    @Test
    fun aTemporaryBasalTheWindowCannotShowKeepsItsType() {
        // plan, part G1, 5: "running, type not recognised" is told from "none"
        assertEquals(0x0f, PumpLink.withActiveBasal(PumpBtValues(), answer("0f", "96000000")).tempBasalType)
        // a special value (NaN) of a known type: the type too
        assertEquals(0x3c, PumpLink.withActiveBasal(PumpBtValues(), answer("3c", "ffff7f00")).tempBasalType)
        assertEquals(0x33, PumpLink.withActiveBasal(PumpBtValues(), answer("33", "ffff7f00")).tempBasalType)
        // shown in its unit: no type
        assertNull(PumpLink.withActiveBasal(PumpBtValues(), answer("33", "0c0000ff")).tempBasalType)
        assertNull(PumpLink.withActiveBasal(PumpBtValues(), answer("3c", "96000000")).tempBasalType)
        // no temporary basal: an earlier type is gone
        val none = PumpLink.withActiveBasal(PumpBtValues(tempBasalType = 0x0f), ActiveBasalRate.parse(hex("6a0300" + "01" + "080000ff")))
        assertNull(none.tempBasalType)
    }

    @Test
    fun theThresholdStoresTwentyAndKeepsTheOlderValues() {
        // decisions for the window, 5: 20 is stored; 5/10/15 stay as they are (polling and intake tests); else 30
        val settings = CareLinkSettings(app)
        try {
            settings.warnAfterMin = 20
            assertEquals(20, CareLinkSettings(app).warnAfterMin)
            assertEquals(20 * 60_000L, CareLinkSettings(app).warnAfterMs)
            settings.warnAfterMin = 5
            assertEquals(5, CareLinkSettings(app).warnAfterMin)
            assertEquals(20, CareLinkSettings.shownWarnMin(CareLinkSettings(app).warnAfterMin))
            settings.warnAfterMin = 45
            assertEquals(30, CareLinkSettings(app).warnAfterMin)
        } finally {
            app.getSharedPreferences(CareLinkSettings.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun restartedStateHasTheModeAndNoFailedPairing() {
        val s = PumpLink.restarted(PumpBtLinkState(running = true, pairMode = false, pairingFailedAtMs = 1_000L), pair = true)
        assertTrue(s.pairMode)
        assertNull(s.pairingFailedAtMs)
        assertTrue(s.running)
    }

    /** The real link with Bluetooth off (it runs and shows the error stage; nothing reaches the radio). */
    private fun runningLink(): PumpLink {
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        Shadows.shadowOf(manager.adapter).setEnabled(false)
        val s = PumpBtStore(app).also { store = it }
        val l = PumpLink(
            app, KeyDatabase(fakeKeyDatabase(local = 4, remotes = listOf(1))), s,
            journal = { journal += it }, logcat = {}, publish = { published += it }, historyFrom = { null }, onCycle = {},
        )
        link = l
        l.start(pair = false)
        assertTrue(published.last().running)
        assertFalse(published.last().pairMode)
        return l
    }

    @Test
    fun aStartOverARunningLinkPublishesTheModeAndClearsAFailedPairing() {
        val l = runningLink()
        // a pairing failed earlier on this running link (as markPairingFailed leaves it)
        val field = PumpLink::class.java.getDeclaredField("state").apply { isAccessible = true }
        field.set(l, (field.get(l) as PumpBtLinkState).copy(pairingFailedAtMs = 1_000L))
        published.clear()
        l.start(pair = true)
        val shown = published.lastOrNull()
        assertNotNull("a start over a running link publishes", shown)
        assertTrue("the window sees the pairing at once, whatever the stage", shown!!.pairMode)
        assertNull("the earlier failed pairing is gone, as after a fresh start", shown.pairingFailedAtMs)
        assertTrue(shown.running)
        published.clear()
        l.start(pair = false)
        assertFalse(published.last().pairMode)
    }

    @Test
    fun aBondChangeIsJournalledWithTheSystemsReasonAndNoAddress() {
        runningLink()
        val manager = app.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter.getRemoteDevice("00:11:22:33:44:55")
        app.sendBroadcast(
            Intent(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                .putExtra(BluetoothDevice.EXTRA_DEVICE, device)
                .putExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_BONDED)
                .putExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                .putExtra(PumpLink.EXTRA_BOND_REASON, 9),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        val line = journal.single { it.startsWith("bond state of") }
        assertTrue(line, line.endsWith("bonded → none, reason 9"))
        assertFalse("no address outside the detailed journal", line.contains("00:11:22:33:44:55"))
        // no reason given by the system: -1
        assertEquals("bond state of X: none → bonding, reason -1",
            PumpLink.bondLine("X", BluetoothDevice.BOND_NONE, BluetoothDevice.BOND_BONDING, -1))
    }
}
