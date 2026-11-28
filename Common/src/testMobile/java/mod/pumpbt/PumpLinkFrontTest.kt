package mod.pumpbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.pumpbt.PumpBtFront
import tk.glucodata.mod.pumpbt.PumpBtLinkState
import tk.glucodata.mod.pumpbt.PumpBtSourceState
import tk.glucodata.mod.pumpbt.PumpBtStage
import tk.glucodata.mod.pumpbt.PumpBtUiState
import tk.glucodata.mod.pumpbt.PumpBtWindow
import tk.glucodata.mod.pumpbt.PumpBtWizardLine
import tk.glucodata.mod.pumpbt.PumpBtWizardStep
import tk.glucodata.mod.pumpbt.ble.PumpLinkRules
import tk.glucodata.mod.pumpbt.ble.PumpLinkRules.Front

/**
 * JugglucoNGmod, pump over Bluetooth (build 17): another app's GATT server in front of ours - where our SAKE stands,
 * what the phone learns of its first app handle, when the paired pump is called, the journal lines, the window's card
 * and wizard line. Made-up handles and addresses only.
 */
class PumpLinkFrontTest {

    @Test
    fun whereOurSakeStands() {
        assertEquals(Front.CLEAR, PumpLinkRules.front(134, 134, null))
        assertEquals(Front.CLEAR, PumpLinkRules.front(120, 134, null))
        assertEquals(Front.WORKED, PumpLinkRules.front(161, 134, 161))
        assertEquals(Front.WORKED, PumpLinkRules.front(150, 134, 161))
        assertEquals(Front.FOREIGN, PumpLinkRules.front(165, 134, 161))
        assertEquals(Front.FOREIGN, PumpLinkRules.front(161, 134, null))
    }

    @Test
    fun theFirstAppHandle() {
        assertEquals(140, PumpLinkRules.firstAppHandle(140, 34, 161))
        assertEquals(140, PumpLinkRules.firstAppHandle(140, 30, 161))
        assertEquals(PumpLinkRules.ANDROID14_FIRST_APP_HANDLE, PumpLinkRules.firstAppHandle(null, 34, 161))
        assertEquals(134, PumpLinkRules.firstAppHandle(null, 35, 200))
        // nothing known on older Android: counted as clear
        assertEquals(161, PumpLinkRules.firstAppHandle(null, 33, 161))
        assertEquals(Front.CLEAR, PumpLinkRules.front(161, PumpLinkRules.firstAppHandle(null, 33, 161), null))
    }

    @Test
    fun whatThePhoneLearnsOfItsFirstAppHandle() {
        // right after Bluetooth on our services come first: the smaller, and a first value even when larger
        assertEquals(134, PumpLinkRules.learnFirstAppHandle(134, 34, 161, afterBluetoothOn = true))
        assertEquals(130, PumpLinkRules.learnFirstAppHandle(134, 34, 130, afterBluetoothOn = true))
        assertEquals(161, PumpLinkRules.learnFirstAppHandle(null, 34, 161, afterBluetoothOn = true))
        assertEquals(45, PumpLinkRules.learnFirstAppHandle(null, 30, 45, afterBluetoothOn = true))
        // at other times only a handle below the one compared with teaches
        assertEquals(120, PumpLinkRules.learnFirstAppHandle(null, 34, 120, afterBluetoothOn = false))
        assertNull(PumpLinkRules.learnFirstAppHandle(null, 34, 161, afterBluetoothOn = false))
        assertNull(PumpLinkRules.learnFirstAppHandle(null, 33, 120, afterBluetoothOn = false))
        assertEquals(134, PumpLinkRules.learnFirstAppHandle(134, 34, 161, afterBluetoothOn = false))
        assertEquals(100, PumpLinkRules.learnFirstAppHandle(134, 34, 100, afterBluetoothOn = false))
        assertEquals(40, PumpLinkRules.learnFirstAppHandle(50, 30, 40, afterBluetoothOn = false))
    }

    @Test
    fun theWorkedHandleCountsOnlyInTheSameBoot() {
        assertEquals(161, PumpLinkRules.workedHandle(161, 7, 7))
        assertNull(PumpLinkRules.workedHandle(161, 7, 8))
        assertNull(PumpLinkRules.workedHandle(null, 7, 7))
        assertNull(PumpLinkRules.workedHandle(161, null, 7))
        assertNull(PumpLinkRules.workedHandle(161, 7, null))
    }

    @Test
    fun noReconnectAdvertisementWithAnotherAppInFront() {
        assertEquals(PumpLinkRules.Advertising.RECONNECT, PumpLinkRules.advertising(true, false, false))
        assertNull(PumpLinkRules.advertising(true, false, false, foreignInFront = true))
        // pairing has no bond to lose
        assertEquals(PumpLinkRules.Advertising.PAIR, PumpLinkRules.advertising(true, false, true, foreignInFront = true))
        assertNull(PumpLinkRules.advertising(true, true, true, foreignInFront = true))
        assertNull(PumpLinkRules.advertising(true, true, false, foreignInFront = false))
        assertNull(PumpLinkRules.advertising(false, false, true, foreignInFront = true))
    }

    @Test
    fun aHeldPumpAndTheBondItLost() {
        assertTrue(PumpLinkRules.pumpHeld(stillConnected = true, bonded = true, encryptedOrAsked = false))
        assertFalse(PumpLinkRules.pumpHeld(stillConnected = true, bonded = true, encryptedOrAsked = true))
        assertFalse(PumpLinkRules.pumpHeld(stillConnected = false, bonded = true, encryptedOrAsked = false))
        assertFalse(PumpLinkRules.pumpHeld(stillConnected = true, bonded = false, encryptedOrAsked = false))

        assertTrue(PumpLinkRules.heldBondLoss(1_000, 1_000))
        assertTrue(PumpLinkRules.heldBondLoss(1_000, 16_000))
        assertFalse(PumpLinkRules.heldBondLoss(1_000, 16_001))
        assertFalse(PumpLinkRules.heldBondLoss(1_000, 999))
        assertFalse(PumpLinkRules.heldBondLoss(null, 1_000))
    }

    @Test
    fun journalLines() {
        assertEquals(
            "our services are the first app services (SAKE at handle 134)",
            PumpLinkRules.frontLine(Front.CLEAR, 134, 134, null),
        )
        assertEquals(
            "services of other apps lie in front of ours (handles 134-160), no more than when the pump last came through " +
                "(SAKE then at 161): advertising",
            PumpLinkRules.frontLine(Front.WORKED, 161, 134, 161),
        )
        assertEquals(
            "services of another app lie in front of ours (handles 134-160): no advertising for the pump - connecting now " +
                "it could hang on that app's server and Android would remove the bond at 30 s; Bluetooth off and on clears it, " +
                "checked again every 5 min",
            PumpLinkRules.frontLine(Front.FOREIGN, 161, 134, null),
        )
        assertEquals(
            "another app's server still lies in front of ours (SAKE at handle 161): checked again in 5 min",
            PumpLinkRules.frontStillLine(161),
        )
        assertEquals(
            "GATT server: 00:11:22:33:44:55 link encryption on (status 0, key 16 bytes, LE) 20512 ms after it connected",
            PumpLinkRules.encryptionLine("00:11:22:33:44:55", true, 0, 16, 2, 20_512),
        )
        assertEquals(
            "GATT server: device-1234 link encryption off (status 5, key 0 bytes, BR/EDR)",
            PumpLinkRules.encryptionLine("device-1234", false, 5, 0, 1, null),
        )
        assertEquals("transport 9", PumpLinkRules.transportName(9))
        assertEquals(
            "pairing request from 00:11:22:33:44:55 (variant 2) 1200 ms after it connected - only recorded, the system asks the person",
            PumpLinkRules.pairingRequestLine("00:11:22:33:44:55", 2, 1_200),
        )
        assertEquals(
            "pairing request from device-1234 (variant 3) - only recorded, the system asks the person",
            PumpLinkRules.pairingRequestLine("device-1234", 3, null),
        )
        assertTrue(PumpLinkRules.heldLine("device-1234", 24).startsWith("the pump device-1234 has been connected 24 s without"))
        assertTrue(PumpLinkRules.heldBondLossLine("device-1234").startsWith("Android removed the bond of device-1234"))
        assertEquals(listOf("com.omronhealthcare.omronconnect"), PumpLinkRules.KNOWN_HOLDERS)
    }

    private val front = PumpBtFront(sinceMs = 1_000, ourHandle = 161, firstAppHandle = 134)
    private val waiting = PumpBtLinkState(running = true, stage = PumpBtStage.WAITING, front = front)

    @Test
    fun theCardOnlyForARunningLinkNotPairing() {
        assertTrue(PumpBtWindow.frontCard(PumpBtSourceState(enabled = true, link = waiting)))
        assertFalse(PumpBtWindow.frontCard(PumpBtSourceState(enabled = true, link = waiting.copy(front = null))))
        assertFalse(PumpBtWindow.frontCard(PumpBtSourceState(enabled = true, link = waiting.copy(running = false))))
        assertFalse(PumpBtWindow.frontCard(PumpBtSourceState(enabled = true, link = waiting.copy(pairMode = true))))
        // a running link switched to pairing does not publish its pair mode, but its stage shows it
        assertFalse(PumpBtWindow.frontCard(PumpBtSourceState(enabled = true, link = waiting.copy(stage = PumpBtStage.ADVERTISING_PAIR))))

        assertFalse(PumpBtWindow.heldBondLoss(PumpBtSourceState()))
        assertTrue(PumpBtWindow.heldBondLoss(PumpBtSourceState(link = PumpBtLinkState(heldBondLossAtMs = 5_000))))
        assertFalse(PumpBtWindow.foreignAppHint(PumpBtSourceState()))
        assertTrue(PumpBtWindow.foreignAppHint(PumpBtSourceState(foreignApps = PumpLinkRules.KNOWN_HOLDERS)))
    }

    @Test
    fun thePairingWizardSaysItFirst() {
        val ui = PumpBtUiState(
            hasBluetooth = true, bluetoothOn = true, permissionsGranted = true, advertisingSupported = true, keyCrc = "01020304",
            warningsAccepted = true, pairingStartedAtMs = 1_000,
        )
        val pairing = PumpBtLinkState(running = true, pairMode = true, stage = PumpBtStage.ADVERTISING_PAIR)
        val clear = PumpBtWindow.wizardLines(PumpBtWizardStep.PAIRING, ui, PumpBtSourceState(link = pairing))
        assertFalse(PumpBtWizardLine.FRONT_BLOCKS_PAIRING in clear)
        val blocked = PumpBtWindow.wizardLines(PumpBtWizardStep.PAIRING, ui, PumpBtSourceState(link = pairing.copy(front = front)))
        assertEquals(PumpBtWizardLine.FRONT_BLOCKS_PAIRING, blocked.first())
        assertEquals(clear, blocked.drop(1))
    }
}
