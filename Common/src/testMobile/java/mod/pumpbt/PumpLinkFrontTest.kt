package mod.pumpbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.pumpbt.PumpBtFront
import tk.glucodata.mod.pumpbt.PumpBtLinkState
import tk.glucodata.mod.pumpbt.PumpBtLinkStatus
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
 * when the paired pump is called (the person may let it come anyway), the journal lines, the window's card
 * and wizard line. Made-up handles and addresses only.
 */
class PumpLinkFrontTest {

    @Test
    fun whereOurSakeStands() {
        assertEquals(Front.CLEAR, PumpLinkRules.front(134, 134, null))
        assertEquals(Front.CLEAR, PumpLinkRules.front(120, 134, null))
        assertEquals(Front.CLEAR, PumpLinkRules.front(134, 134, 161))
        // the person let the pump come anyway: up to the handle accepted
        assertEquals(Front.ACCEPTED, PumpLinkRules.front(161, 134, 161))
        assertEquals(Front.ACCEPTED, PumpLinkRules.front(150, 134, 161))
        assertEquals(Front.FOREIGN, PumpLinkRules.front(165, 134, 161))
        assertEquals(Front.FOREIGN, PumpLinkRules.front(161, 134, null))
    }

    @Test
    fun theFirstAppHandle() {
        assertEquals(PumpLinkRules.ANDROID14_FIRST_APP_HANDLE, PumpLinkRules.firstAppHandle(34, 161))
        assertEquals(134, PumpLinkRules.firstAppHandle(35, 200))
        assertEquals(134, PumpLinkRules.firstAppHandle(34, 120))
        // nothing known on Android 13 and older: our own handle, counted as clear
        assertEquals(161, PumpLinkRules.firstAppHandle(33, 161))
        assertEquals(Front.CLEAR, PumpLinkRules.front(161, PumpLinkRules.firstAppHandle(33, 161), null))
        assertEquals(Front.FOREIGN, PumpLinkRules.front(161, PumpLinkRules.firstAppHandle(34, 161), null))
    }

    @Test
    fun theFirstCheckAgainComesSooner() {
        assertEquals(30_000L, PumpLinkRules.FRONT_FIRST_RECHECK_MS)
        // build 19: every minute - the pause does not keep the pump away, the other server should be seen gone soon
        assertEquals(60_000L, PumpLinkRules.FRONT_RECHECK_MS)
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
            "services of another app lie in front of ours (handles 134-160), the person let the pump come anyway " +
                "(accepted at SAKE handle 161): advertising",
            PumpLinkRules.frontLine(Front.ACCEPTED, 161, 134, 161),
        )
        assertEquals(
            "services of another app lie in front of ours (handles 134-160): no advertising for the pump - connecting now " +
                "it could hang on that app's server and Android would remove the bond at 30 s; Bluetooth off and on clears it, " +
                "checked again every 1 min",
            PumpLinkRules.frontLine(Front.FOREIGN, 161, 134, null),
        )
        assertEquals(
            "another app's server still lies in front of ours (SAKE at handle 161): checked again in 1 min",
            PumpLinkRules.frontStillLine(161),
        )
        // build 19: a long pause writes only every 10th check
        assertEquals(10, PumpLinkRules.FRONT_STILL_WRITTEN_EVERY)
        assertEquals(
            "another app's server still lies in front of ours (SAKE at handle 161): checked again in 1 min " +
                "(check 11 in a row, every 10th written)",
            PumpLinkRules.frontStillLine(161, 11),
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

        // with the bond lost one card says it ("the phone forgot the pump")
        assertFalse(PumpBtWindow.frontCard(PumpBtSourceState(enabled = true, bondLost = true, link = waiting)))
        // the source off gives no lost-bond card: the paused card stays
        assertTrue(PumpBtWindow.frontCard(PumpBtSourceState(enabled = false, bondLost = true, link = waiting)))

        assertFalse(PumpBtWindow.foreignAppHint(PumpBtSourceState()))
        assertTrue(PumpBtWindow.foreignAppHint(PumpBtSourceState(foreignApps = PumpLinkRules.KNOWN_HOLDERS)))
    }

    @Test
    fun theHeldBondLossIsToldForADay() {
        val at = 5_000L
        val lost = PumpBtSourceState(link = PumpBtLinkState(heldBondLossAtMs = at))
        assertFalse(PumpBtWindow.heldBondLoss(PumpBtSourceState(), at))
        assertTrue(PumpBtWindow.heldBondLoss(lost, at))
        assertTrue(PumpBtWindow.heldBondLoss(lost, at + 24 * 3_600_000L))
        assertFalse(PumpBtWindow.heldBondLoss(lost, at + 24 * 3_600_000L + 1))
        assertFalse(PumpBtWindow.heldBondLoss(lost, at - 1))
        assertEquals(24 * 3_600_000L, PumpBtWindow.HELD_SHOWN_MS)
    }

    @Test
    fun theLinkLineSaysPaused() {
        val ui = PumpBtUiState(hasBluetooth = true, bluetoothOn = true)
        assertEquals(PumpBtLinkStatus.PAUSED, PumpBtWindow.linkStatus(ui, PumpBtSourceState(enabled = true, link = waiting)))
        assertEquals(PumpBtLinkStatus.WAITING, PumpBtWindow.linkStatus(ui, PumpBtSourceState(enabled = true, link = waiting.copy(front = null))))
        // a pump that came anyway is connected
        assertEquals(
            PumpBtLinkStatus.CONNECTED,
            PumpBtWindow.linkStatus(ui, PumpBtSourceState(enabled = true, link = waiting.copy(stage = PumpBtStage.PUMP_CONNECTED))),
        )
        assertEquals(PumpBtLinkStatus.BLUETOOTH_OFF, PumpBtWindow.linkStatus(ui.copy(bluetoothOn = false), PumpBtSourceState(enabled = true, link = waiting)))
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
