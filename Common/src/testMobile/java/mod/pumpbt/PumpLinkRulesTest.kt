package mod.pumpbt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.pumpbt.PumpBtBlocker
import tk.glucodata.mod.pumpbt.PumpBtLinkState
import tk.glucodata.mod.pumpbt.PumpBtNameVariant
import tk.glucodata.mod.pumpbt.PumpBtProgress
import tk.glucodata.mod.pumpbt.PumpBtReadiness
import tk.glucodata.mod.pumpbt.PumpBtReturn
import tk.glucodata.mod.pumpbt.PumpBtStage
import tk.glucodata.mod.pumpbt.PumpBtUiState
import tk.glucodata.mod.pumpbt.ble.PumpLinkRules
import tk.glucodata.mod.pumpbt.ble.PumpLinkRules.ServerEvent
import tk.glucodata.mod.pumpbt.core.PumpUuid
import java.util.UUID

/** JugglucoNGmod, pump over Bluetooth (trial): the link's decisions without an Android device. */
class PumpLinkRulesTest {

    @Test
    fun onlyDeviceInformationReadsAndSakeWritesMakeThePump() {
        assertFalse(PumpLinkRules.countsAsPump(ServerEvent.CONNECTED))
        assertFalse(PumpLinkRules.countsAsPump(ServerEvent.DISCONNECTED))
        assertFalse(PumpLinkRules.countsAsPump(ServerEvent.READ_OTHER))
        assertFalse(PumpLinkRules.countsAsPump(ServerEvent.WRITE_OTHER))
        assertFalse(PumpLinkRules.countsAsPump(ServerEvent.WRITE_OTHER_DESCRIPTOR))
        assertTrue(PumpLinkRules.countsAsPump(ServerEvent.READ_DEVICE_INFO))
        assertTrue(PumpLinkRules.countsAsPump(ServerEvent.WRITE_SAKE_PORT))
        assertTrue(PumpLinkRules.countsAsPump(ServerEvent.WRITE_SAKE_CCCD))
    }

    @Test
    fun serverEventsAreClassifiedByUuid() {
        val dis = PumpUuid.parse(PumpUuid.APP_DEVICE_INFO_SERVICE)
        val port = PumpUuid.parse(PumpUuid.SAKE_PORT)
        val cccd = PumpUuid.parse(PumpUuid.CCCD)
        assertEquals(ServerEvent.READ_DEVICE_INFO, PumpLinkRules.classifyRead(dis))
        // the SIG device information service (a sensor's own) is not ours
        assertEquals(ServerEvent.READ_OTHER, PumpLinkRules.classifyRead(PumpUuid.parse(PumpUuid.DEVICE_INFO_SERVICE)))
        assertEquals(ServerEvent.READ_OTHER, PumpLinkRules.classifyRead(null))
        assertEquals(ServerEvent.WRITE_SAKE_PORT, PumpLinkRules.classifyWrite(port))
        assertEquals(ServerEvent.WRITE_OTHER, PumpLinkRules.classifyWrite(UUID.randomUUID()))
        assertEquals(ServerEvent.WRITE_SAKE_CCCD, PumpLinkRules.classifyDescriptorWrite(cccd, port))
        assertEquals(ServerEvent.WRITE_OTHER_DESCRIPTOR, PumpLinkRules.classifyDescriptorWrite(cccd, dis))
        assertEquals(ServerEvent.WRITE_OTHER_DESCRIPTOR, PumpLinkRules.classifyDescriptorWrite(UUID.randomUUID(), port))
    }

    @Test
    fun clientAttachesOnlyToAPumpOnOurServer() {
        assertEquals(PumpLinkRules.Attach.NO_PUMP, PumpLinkRules.clientAttach(pumpKnown = false, serverConnected = true))
        assertEquals(PumpLinkRules.Attach.NO_PUMP, PumpLinkRules.clientAttach(pumpKnown = false, serverConnected = false))
        assertEquals(PumpLinkRules.Attach.NOT_CONNECTED, PumpLinkRules.clientAttach(pumpKnown = true, serverConnected = false))
        assertEquals(PumpLinkRules.Attach.ALLOWED, PumpLinkRules.clientAttach(pumpKnown = true, serverConnected = true))
        assertTrue(PumpLinkRules.Attach.NOT_CONNECTED.text.isNotBlank())
        assertFalse(PumpLinkRules.keepClientConnection(serverConnected = false))
        assertTrue(PumpLinkRules.keepClientConnection(serverConnected = true))
    }

    @Test
    fun advertisingStopsWithThePump() {
        assertNull(PumpLinkRules.advertising(active = false, pumpPresent = false, pairMode = true))
        assertNull(PumpLinkRules.advertising(active = true, pumpPresent = true, pairMode = true))
        assertEquals(PumpLinkRules.Advertising.PAIR, PumpLinkRules.advertising(active = true, pumpPresent = false, pairMode = true))
        assertEquals(PumpLinkRules.Advertising.RECONNECT, PumpLinkRules.advertising(active = true, pumpPresent = false, pairMode = false))
    }

    @Test
    fun pairingAdvertisementFits() {
        val name = "Mobile 012345"
        assertArrayEquals(byteArrayOf(0) + name.toByteArray() + byteArrayOf(0), PumpLinkRules.manufacturerData(name))
        assertTrue(PumpLinkRules.pairAdvertisementBytes(PumpBtNameVariant.A, name) <= 31)
        assertTrue(PumpLinkRules.pairAdvertisementBytes(PumpBtNameVariant.B, name) <= 31)
    }

    @Test
    fun historyRanges() {
        // first time: the last 50
        assertEquals(951L..1000L, PumpLinkRules.historyRange(1000, null))
        assertEquals(0L..10L, PumpLinkRules.historyRange(10, null))
        // later: what is new, at most 200 (the newest)
        assertEquals(991L..1000L, PumpLinkRules.historyRange(1000, 990))
        assertEquals(801L..1000L, PumpLinkRules.historyRange(1000, 100))
        assertNull(PumpLinkRules.historyRange(1000, 1000))
        assertNull(PumpLinkRules.historyRange(1000, 1200))
        assertNull(PumpLinkRules.historyRange(-1, null))
    }

    @Test
    fun bondShownAsPairing() {
        assertTrue(PumpLinkRules.bondCountsAsPairing("A", pump = "A", onOurServer = false, pairMode = false))
        assertFalse(PumpLinkRules.bondCountsAsPairing("B", pump = "A", onOurServer = true, pairMode = true))
        assertTrue(PumpLinkRules.bondCountsAsPairing("B", pump = null, onOurServer = true, pairMode = true))
        assertFalse(PumpLinkRules.bondCountsAsPairing("B", pump = null, onOurServer = false, pairMode = true))
        assertFalse(PumpLinkRules.bondCountsAsPairing("B", pump = null, onOurServer = true, pairMode = false))
    }

    @Test
    fun sakeWriterOverridesADeviceThatOnlyReadDeviceInformation() {
        val claim = PumpLinkRules::claim
        assertEquals(PumpLinkRules.Claim.TAKE, claim(null, false, false, "A", false))
        assertEquals(PumpLinkRules.Claim.SAME, claim("A", false, true, "A", true))
        // another device known only from reading 0x0900 and still connected: a SAKE write takes over, a read does not
        assertEquals(PumpLinkRules.Claim.TAKE, claim("A", false, true, "B", true))
        assertEquals(PumpLinkRules.Claim.IGNORE, claim("A", false, true, "B", false))
        // the known device wrote to SAKE itself: it stays while connected
        assertEquals(PumpLinkRules.Claim.IGNORE, claim("A", true, true, "B", true))
        // the known device left our server: anyone may take over
        assertEquals(PumpLinkRules.Claim.TAKE, claim("A", true, false, "B", false))
    }

    @Test
    fun disconnectBeforeThePumpIsIdentifiedAdvertisesAgain() {
        val drop = PumpLinkRules::serverDisconnect
        assertEquals(PumpLinkRules.Drop.ADVERTISE_AGAIN, drop(true, false, false, false))
        assertEquals(PumpLinkRules.Drop.NOTHING, drop(false, false, false, false))
        // the pump's own address
        assertEquals(PumpLinkRules.Drop.PUMP_LOST, drop(true, true, true, true))
        // another address (identity vs random) and the pump does not look connected: check again first
        assertEquals(PumpLinkRules.Drop.RECHECK, drop(true, true, false, false))
        // another device (e.g. the Sibionics sensor) left, the pump is still there
        assertEquals(PumpLinkRules.Drop.NOTHING, drop(true, true, false, true))
        // stopped: a known pump is still cleaned up
        assertEquals(PumpLinkRules.Drop.PUMP_LOST, drop(false, true, true, false))
    }

    @Test
    fun pumpCountsAsGoneOnlyWhenTheRecheckAgrees() {
        assertTrue(PumpLinkRules.recheckLost(samePump = true, stillLinked = false))
        // back on our server after a second (a false "not connected" before): not lost
        assertFalse(PumpLinkRules.recheckLost(samePump = true, stillLinked = true))
        // meanwhile another device became the pump or the pump was handled already
        assertFalse(PumpLinkRules.recheckLost(samePump = false, stillLinked = false))
        assertEquals(1_000L, PumpLinkRules.RECHECK_MS)
    }

    @Test
    fun stageGoesBackOnlyWhenThePairingFailedOrItsDeviceLeft() {
        val back = PumpLinkRules::stageAfterDrop
        // another device left: the stage stays (BONDED can still turn "pairing" into "pump connected")
        assertNull(back(PumpBtStage.PAIRING, true, false, false))
        assertNull(back(PumpBtStage.PUMP_CONNECTED, false, false, false))
        // pairing failed (BOND_NONE after BONDING)
        assertEquals(PumpBtStage.ADVERTISING_PAIR, back(PumpBtStage.PAIRING, true, true, false))
        // the device whose pairing is shown left
        assertEquals(PumpBtStage.WAITING, back(PumpBtStage.PUMP_CONNECTED, false, false, true))
        // "connection lost", errors and advertising stay
        assertNull(back(PumpBtStage.LOST, false, true, true))
        assertNull(back(PumpBtStage.ERROR, true, true, false))
        assertNull(back(PumpBtStage.ADVERTISING_PAIR, true, true, false))
        assertEquals(500L, PumpLinkRules.ADVERTISE_AGAIN_DELAY_MS)
    }

    @Test
    fun gattCommandRetriedTenTimesThenDropped() {
        assertTrue(PumpLinkRules.gattRetry(0))
        assertTrue(PumpLinkRules.gattRetry(9))
        assertFalse(PumpLinkRules.gattRetry(10))
        assertEquals(10, (0 until 100).takeWhile { PumpLinkRules.gattRetry(it) }.size)
        assertEquals(500L, PumpLinkRules.GATT_RETRY_MS)
    }

    @Test
    fun notificationFailuresLoggedFirstAndEvery20th() {
        assertEquals(listOf(1, 20, 40, 60), (0..60).filter { PumpLinkRules.logNotifyFailure(it) })
    }

    @Test
    fun onlyPumpLikeBondedNamesAreWritten() {
        assertTrue(PumpLinkRules.pumpLikeName("Pump 123456"))
        assertTrue(PumpLinkRules.pumpLikeName("MiniMed 740G"))
        assertTrue(PumpLinkRules.pumpLikeName("Medtronic"))
        assertFalse(PumpLinkRules.pumpLikeName("Galaxy Watch5 Pro"))
        assertFalse(PumpLinkRules.pumpLikeName(null))
    }

    @Test
    fun failedPairingBeforeThePumpIsIdentifiedAdvertisesAgain() {
        assertEquals(PumpLinkRules.Drop.ADVERTISE_AGAIN, PumpLinkRules.bondFailed(running = true, pumpKnown = false, wasBonding = true, nowNone = true))
        assertEquals(PumpLinkRules.Drop.NOTHING, PumpLinkRules.bondFailed(running = true, pumpKnown = true, wasBonding = true, nowNone = true))
        assertEquals(PumpLinkRules.Drop.NOTHING, PumpLinkRules.bondFailed(running = true, pumpKnown = false, wasBonding = false, nowNone = true))
        assertEquals(PumpLinkRules.Drop.NOTHING, PumpLinkRules.bondFailed(running = true, pumpKnown = false, wasBonding = true, nowNone = false))
        assertEquals(PumpLinkRules.Drop.NOTHING, PumpLinkRules.bondFailed(running = false, pumpKnown = false, wasBonding = true, nowNone = true))
    }

    @Test
    fun dataReadOnlyWithTheSerialNumber() {
        assertEquals(PumpBtStage.DATA_READ, PumpLinkRules.cycleDoneStage(serialRead = true))
        assertEquals(PumpBtStage.READING, PumpLinkRules.cycleDoneStage(serialRead = false))
    }

    @Test
    fun furthestStageOnlyMovesForward() {
        var f: PumpBtStage? = null
        for (s in listOf(PumpBtStage.ADVERTISING_PAIR, PumpBtStage.PAIRING, PumpBtStage.PUMP_CONNECTED, PumpBtStage.HANDSHAKE)) {
            f = PumpBtProgress.furthest(f, s)
        }
        assertEquals(PumpBtStage.HANDSHAKE, f)
        // an error, a lost link or going back to advertising do not move it back
        f = PumpBtProgress.furthest(f, PumpBtStage.ERROR)
        f = PumpBtProgress.furthest(f, PumpBtStage.LOST)
        f = PumpBtProgress.furthest(f, PumpBtStage.WAITING)
        assertEquals(PumpBtStage.HANDSHAKE, f)
        assertEquals(PumpBtStage.DATA_READ, PumpBtProgress.furthest(f, PumpBtStage.DATA_READ))
        assertNull(PumpBtProgress.furthest(null, PumpBtStage.STOPPED))
    }

    @Test
    fun lastThreeReturnsNewestFirst() {
        var returns = emptyList<PumpBtReturn>()
        for (i in 1..5L) returns = PumpBtProgress.addReturn(returns, PumpBtReturn(lostAtMs = i * 1000, afterMs = i))
        assertEquals(listOf(5L, 4L, 3L), returns.map { it.afterMs })
    }

    @Test
    fun startNeedsBluetoothAdvertisingAndKey() {
        assertEquals(
            listOf(PumpBtBlocker.NO_BLUETOOTH, PumpBtBlocker.NO_PERMISSION, PumpBtBlocker.NO_KEY),
            PumpBtReadiness.blockers(hasBluetooth = false, on = false, permissions = false, advertising = null, key = false),
        )
        assertEquals(listOf(PumpBtBlocker.BLUETOOTH_OFF), PumpBtReadiness.blockers(true, false, true, null, true))
        assertEquals(listOf(PumpBtBlocker.NO_ADVERTISING), PumpBtReadiness.blockers(true, true, true, false, true))
        assertTrue(PumpBtReadiness.blockers(true, true, true, true, true).isEmpty())

        val ready = PumpBtUiState(hasBluetooth = true, bluetoothOn = true, permissionsGranted = true, advertisingSupported = true, keyCrc = "01020304")
        assertTrue(ready.canStart)
        // only the permissions missing: the start asks for them
        assertTrue(ready.copy(permissionsGranted = false).canStart)
        assertFalse(ready.copy(keyCrc = null).canStart)
        assertFalse(ready.copy(advertisingSupported = false).canStart)
        assertFalse(ready.copy(link = PumpBtLinkState(running = true)).canStart)
    }
}
