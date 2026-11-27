package mod.pumpbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.pumpbt.BackfillCard
import tk.glucodata.mod.pumpbt.BackfillItem
import tk.glucodata.mod.pumpbt.PumpBtAnswer
import tk.glucodata.mod.pumpbt.PumpBtCount
import tk.glucodata.mod.pumpbt.PumpBtLinkState
import tk.glucodata.mod.pumpbt.PumpBtSourceState
import tk.glucodata.mod.pumpbt.PumpBtStage
import tk.glucodata.mod.pumpbt.PumpBtTurnOnHint
import tk.glucodata.mod.pumpbt.PumpBtUiState
import tk.glucodata.mod.pumpbt.PumpBtWindow
import tk.glucodata.mod.pumpbt.PumpHistoryCounters
import tk.glucodata.mod.pumpbt.PumpWarning

/** JugglucoNGmod, pump over Bluetooth (series part 2, part B2b): what the trial window shows and allows. */
class PumpBtWindowTest {

    private val ready = PumpBtUiState(
        hasBluetooth = true, bluetoothOn = true, permissionsGranted = true, advertisingSupported = true, keyCrc = "01020304",
        paired = true,
    )
    private val off = PumpBtSourceState()
    private val on = PumpBtSourceState(enabled = true)
    private val running = PumpBtLinkState(running = true, stage = PumpBtStage.WAITING)
    private val up = PumpBtLinkState(running = true, stage = PumpBtStage.DATA_READ)

    /** A running link switched to pairing ("Pair again" with the source on): the link does not publish its pair mode. */
    private val switchedToPairing = PumpBtLinkState(running = true, pairMode = false, stage = PumpBtStage.ADVERTISING_PAIR)

    private val a = BackfillItem("INSULIN|2026-11-27T09:00:00|0", 1_000, 2.0, null)
    private val b = BackfillItem("MEAL|2026-11-27T09:00:00|0", 1_000, null, 30.0)
    private val c = BackfillItem("INSULIN|2026-11-27T10:00:00|0", 2_000, 1.5, null)

    @Test
    fun sourceOffKeepsTheTrialButtons() {
        val c = PumpBtWindow.controls(ready, off)
        assertTrue(c.trialButtons)
        assertTrue(c.canStart)
        assertFalse(c.canStop)
        assertTrue(c.variantEditable)
        assertTrue(c.canTurnOn)
        assertFalse(c.pairOffered)

        val r = PumpBtWindow.controls(ready, off.copy(link = running))
        assertFalse(r.canStart)
        assertTrue(r.canStop)
        assertTrue(r.canReadAgain)
        assertFalse(r.variantEditable)
    }

    @Test
    fun sourceOnHasNoStartOrStop() {
        // Stop would end the link and the watchdog start it again: only the switch stops it
        val c = PumpBtWindow.controls(ready, on.copy(link = up))
        assertFalse(c.trialButtons)
        assertFalse(c.canStart)
        assertFalse(c.canStop)
        assertFalse(c.variantEditable)
        assertTrue(c.canReadAgain)
        assertFalse(c.canTurnOn)
        assertFalse(c.pairOffered)
    }

    @Test
    fun turningOnNeedsTheKeyAndBluetoothAndSaysTheFirstThingToDo() {
        assertFalse(PumpBtWindow.controls(ready.copy(keyCrc = null), off).canTurnOn)
        assertFalse(PumpBtWindow.controls(ready.copy(bluetoothOn = false), off).canTurnOn)
        assertFalse(PumpBtWindow.controls(ready.copy(advertisingSupported = false), off).canTurnOn)
        // only the permissions missing: the press asks for them first
        assertTrue(PumpBtWindow.controls(ready.copy(permissionsGranted = false), off).canTurnOn)
        assertNull(PumpBtWindow.turnOnHint(ready.copy(permissionsGranted = false), off))

        // the hint follows the first blocker, not always "load the key and turn Bluetooth on"
        assertEquals(PumpBtTurnOnHint.NO_KEY, PumpBtWindow.turnOnHint(ready.copy(keyCrc = null), off))
        assertEquals(PumpBtTurnOnHint.BLUETOOTH_OFF, PumpBtWindow.turnOnHint(ready.copy(bluetoothOn = false, keyCrc = null), off))
        assertEquals(PumpBtTurnOnHint.NO_BLUETOOTH, PumpBtWindow.turnOnHint(ready.copy(hasBluetooth = false, keyCrc = null), off))
        assertEquals(PumpBtTurnOnHint.NO_ADVERTISING, PumpBtWindow.turnOnHint(ready.copy(advertisingSupported = false, keyCrc = null), off))
        assertNull(PumpBtWindow.turnOnHint(ready, off))
        // on: nothing to hint
        assertNull(PumpBtWindow.turnOnHint(ready.copy(keyCrc = null), on))
    }

    @Test
    fun turningOnWaitsForTheTrialPairing() {
        val trialPairing = off.copy(link = PumpBtLinkState(running = true, pairMode = true, stage = PumpBtStage.ADVERTISING_PAIR))
        val c = PumpBtWindow.controls(ready, trialPairing)
        assertFalse(c.canTurnOn)
        assertEquals(PumpBtTurnOnHint.PAIRING, PumpBtWindow.turnOnHint(ready, trialPairing))
        // the trial's Stop ends the pairing; then turning on is possible
        assertTrue(c.canStop)
        assertTrue(PumpBtWindow.controls(ready, off.copy(link = trialPairing.link.copy(running = false, pairMode = false, stage = PumpBtStage.STOPPED))).canTurnOn)
        // a trial waiting for the paired pump (no pairing) does not stop it
        assertTrue(PumpBtWindow.controls(ready, off.copy(link = running)).canTurnOn)
    }

    @Test
    fun pairingIsSeenByTheStageToo() {
        // "running, stage ADVERTISING_PAIR, pairMode=false" - a pairing (critic B2b, B1)
        assertTrue(PumpBtWindow.pairing(switchedToPairing))
        assertTrue(PumpBtWindow.pairing(switchedToPairing.copy(stage = PumpBtStage.PAIRING)))
        assertTrue(PumpBtWindow.pairing(running.copy(pairMode = true)))
        assertFalse(PumpBtWindow.pairing(running))
        assertFalse(PumpBtWindow.pairing(up))
        assertFalse(PumpBtWindow.pairing(switchedToPairing.copy(running = false)))
        // the wait time is shown for it
        assertTrue(PumpBtWindow.pairWaitShown(switchedToPairing))
        assertFalse(PumpBtWindow.pairWaitShown(running))
        assertFalse(PumpBtWindow.pairWaitShown(switchedToPairing.copy(stage = PumpBtStage.PAIRING)))
    }

    @Test
    fun pairingWithTheSourceOn() {
        // never paired: offered until the pump is linked
        val fresh = PumpBtWindow.controls(ready.copy(paired = false), on.copy(link = running))
        assertTrue(fresh.pairOffered)
        assertTrue(fresh.canPair)
        assertFalse(PumpBtWindow.controls(ready.copy(paired = false), on.copy(link = up)).pairOffered)
        // the phone forgot the pump
        val lost = on.copy(bondLost = true, link = running)
        assertTrue(PumpBtWindow.controls(ready, lost).pairOffered)
        assertTrue(PumpBtWindow.controls(ready, lost).canPair)
        // a pairing going on - by the published mode or only by the stage: shown, not pressable
        for (link in listOf(running.copy(pairMode = true), switchedToPairing, switchedToPairing.copy(stage = PumpBtStage.PAIRING))) {
            val during = PumpBtWindow.controls(ready, lost.copy(link = link))
            assertTrue(during.pairOffered)
            assertFalse(during.canPair)
            assertFalse(PumpBtWindow.controls(ready.copy(paired = false), on.copy(link = link)).canPair)
        }
        // no key: offered, not pressable
        val noKey = PumpBtWindow.controls(ready.copy(keyCrc = null), lost)
        assertTrue(noKey.pairOffered)
        assertFalse(noKey.canPair)
        // paired and bonded: nothing
        assertFalse(PumpBtWindow.controls(ready, on.copy(link = running)).pairOffered)
    }

    @Test
    fun cancelPairingOnlyWithTheSourceOnDuringAPairing() {
        // critic of the joints 2, N-4: a pairing (by the published mode or the stage) with permanent operation on
        for (link in listOf(running.copy(pairMode = true), switchedToPairing, switchedToPairing.copy(stage = PumpBtStage.PAIRING))) {
            assertTrue(link.toString(), PumpBtWindow.controls(ready, on.copy(link = link)).canCancelPairing)
            assertTrue(link.toString(), PumpBtWindow.controls(ready, on.copy(bondLost = true, link = link)).canCancelPairing)
            // with no key or Bluetooth too: going back to waiting needs nothing new
            assertTrue(PumpBtWindow.controls(ready.copy(keyCrc = null, bluetoothOn = false), on.copy(link = link)).canCancelPairing)
            // the source off: the trial has its own Stop and Wait buttons
            assertFalse(PumpBtWindow.controls(ready, off.copy(link = link)).canCancelPairing)
        }
        // no pairing: waiting, linked, stopped
        for (link in listOf(running, up, switchedToPairing.copy(running = false), PumpBtLinkState())) {
            assertFalse(link.toString(), PumpBtWindow.controls(ready, on.copy(link = link)).canCancelPairing)
        }
    }

    @Test
    fun bondAdviceByTheSourceMarkOnly() {
        // the source's mark decides, with the source on; no guess from the link's stage
        assertFalse(PumpBtWindow.bondAdvice(off.copy(bondLost = true)))
        assertTrue(PumpBtWindow.bondAdvice(on.copy(bondLost = true, link = running)))
        assertTrue(PumpBtWindow.bondAdvice(on.copy(bondLost = true, link = up.copy(stage = PumpBtStage.LOST))))
        // linked once and lost again: the source cleared the mark at the handshake - no card
        assertFalse(PumpBtWindow.bondAdvice(on.copy(bondLost = false, link = up.copy(stage = PumpBtStage.LOST))))
        assertFalse(PumpBtWindow.bondAdvice(on.copy(bondLost = false, link = running)))
        assertFalse(PumpBtWindow.bondAdvice(on))
    }

    @Test
    fun pairingAdviceAfterAFailedPairingWhileStillAdvertising() {
        val pairing = PumpBtLinkState(running = true, pairMode = true, stage = PumpBtStage.ADVERTISING_PAIR)
        assertFalse(PumpBtWindow.pairingAdvice(pairing, null))
        assertTrue(PumpBtWindow.pairingAdvice(pairing.copy(pairingFailedAtMs = 1_000), null))
        assertTrue(PumpBtWindow.pairingAdvice(pairing.copy(pairingFailedAtMs = 1_000), 500))
        // paired since or stopped: gone
        assertFalse(PumpBtWindow.pairingAdvice(pairing.copy(pairingFailedAtMs = 1_000, pairMode = false, stage = PumpBtStage.DATA_READ), 500))
        assertFalse(PumpBtWindow.pairingAdvice(pairing.copy(pairingFailedAtMs = 1_000, running = false), 500))
    }

    @Test
    fun anOldFailureIsNotAdvisedForANewPairing() {
        // a running link switched to pairing keeps the failure mark of an earlier pairing: the wait is shown, not the advice
        val again = switchedToPairing.copy(pairingFailedAtMs = 1_000)
        assertFalse(PumpBtWindow.pairingAdvice(again, 5_000))
        assertTrue(PumpBtWindow.pairWaitShown(again))
        // a failure of this pairing
        assertTrue(PumpBtWindow.pairingAdvice(again.copy(pairingFailedAtMs = 6_000), 5_000))
        // the start not known: only a published pair mode (a fresh start cleared the mark) is trusted
        assertFalse(PumpBtWindow.pairingAdvice(again, null))
    }

    @Test
    fun shareWarnsWhileTheFileHoldsDetailedLines() {
        assertFalse(PumpBtWindow.shareWarning(off))
        assertTrue(PumpBtWindow.shareWarning(off.copy(verbose = true, journalHasDetail = true)))
        // the detailed journal turned off, the lines still in the file
        assertTrue(PumpBtWindow.shareWarning(off.copy(verbose = false, journalHasDetail = true)))
        // cleared since
        assertFalse(PumpBtWindow.shareWarning(on.copy(verbose = false, journalHasDetail = false)))
    }

    @Test
    fun theCardOnlyWithTheSourceOn() {
        val card = BackfillCard(bolusesBefore = 1, mealsBefore = 0, alreadyInJournal = 0, toWrite = listOf(a), oldNotFound = 0)
        assertEquals(card, PumpBtWindow.card(on.copy(card = card)))
        assertNull(PumpBtWindow.card(off.copy(card = card)))
        assertNull(PumpBtWindow.card(on))
    }

    @Test
    fun countersOnlyNonZeroInOrder() {
        assertEquals(emptyList<Pair<PumpBtCount, Long>>(), PumpBtWindow.counts(PumpHistoryCounters()))
        val c = PumpHistoryCounters(unparsed = 2, gaps = 5_000_000_000L, unpairedMeals = 0, noResultBoluses = 1, futureEvents = 0, heldBoluses = 3, undated = 4)
        assertEquals(
            listOf(
                PumpBtCount.UNPARSED to 2L, PumpBtCount.GAPS to 5_000_000_000L, PumpBtCount.NO_RESULT to 1L,
                PumpBtCount.HELD to 3L, PumpBtCount.UNDATED to 4L,
            ),
            PumpBtWindow.counts(c),
        )
        assertEquals(
            listOf(PumpBtCount.UNPAIRED_MEALS to 1L, PumpBtCount.FUTURE to 7L),
            PumpBtWindow.counts(PumpHistoryCounters(unpairedMeals = 1, futureEvents = 7)),
        )
    }

    @Test
    fun warningsInAFixedOrder() {
        assertEquals(
            listOf(PumpWarning.CLOCK_DRIFT, PumpWarning.FUTURE_EVENT, PumpWarning.NO_RESULT_BOLUS, PumpWarning.HELD_BOLUS, PumpWarning.UNPAIRED_MEAL),
            PumpBtWindow.warnings(PumpWarning.values().toSet()),
        )
        assertEquals(listOf(PumpWarning.HELD_BOLUS), PumpBtWindow.warnings(setOf(PumpWarning.HELD_BOLUS)))
    }

    @Test
    fun theCardListIsByTime() {
        val card = BackfillCard(bolusesBefore = 3, mealsBefore = 1, alreadyInJournal = 1, toWrite = listOf(c, b, a), oldNotFound = 2)
        assertEquals(listOf(a, c, b).sortedWith(compareBy({ it.atMs }, { it.key })), PumpBtWindow.cardItems(card))
        assertEquals(listOf(1_000L, 1_000L, 2_000L), PumpBtWindow.cardItems(card).map { it.atMs })
    }

    @Test
    fun aChangedCardKeepsTheShownListAndWriteWaitsForShowNew() {
        val shown = PumpBtWindow.cardItems(BackfillCard(2, 0, 0, listOf(a, c), 0))
        val sameList = PumpBtWindow.cardItems(BackfillCard(2, 0, 1, listOf(c, a), 0))
        val newList = PumpBtWindow.cardItems(BackfillCard(3, 1, 0, listOf(a, b, c), 0))
        // counts read again, the same list: nothing changed for the person
        assertFalse(PumpBtWindow.listChanged(shown, sameList))
        assertTrue(PumpBtWindow.canWrite(shown, sameList, PumpBtAnswer.NONE))
        // another list from the source: "Write" waits until the person shows the new one
        assertTrue(PumpBtWindow.listChanged(shown, newList))
        assertFalse(PumpBtWindow.canWrite(shown, newList, PumpBtAnswer.NONE))
        assertTrue(PumpBtWindow.canWrite(newList, newList, PumpBtAnswer.NONE))
        // a dose changed under the same key is a change too
        assertTrue(PumpBtWindow.listChanged(shown, shown.map { if (it.key == a.key) it.copy(units = 2.5) else it }))
        // while an answer waits: no second press
        assertFalse(PumpBtWindow.canWrite(shown, shown, PumpBtAnswer.WAITING))
        // "Write" passes exactly the keys of the list shown
        assertEquals(listOf(a.key, c.key), PumpBtWindow.shownKeys(shown))
    }

    @Test
    fun anAnswerTheSourceDidNotTakeIsSaid() {
        val list = listOf(a, c)
        val keys = PumpBtWindow.shownKeys(list)
        assertEquals(PumpBtAnswer.NONE, PumpBtWindow.answer(null, keys, list, 0))
        assertEquals(PumpBtAnswer.WAITING, PumpBtWindow.answer(1_000, keys, list, 2_000))
        // the source refused "Write" for another list and shows the card anew: other keys
        assertEquals(PumpBtAnswer.REJECTED, PumpBtWindow.answer(1_000, keys, listOf(a, b, c), 2_000))
        // no answer in time (a failed save): the buttons work again
        assertEquals(PumpBtAnswer.NOT_TAKEN, PumpBtWindow.answer(1_000, keys, list, 1_000 + PumpBtWindow.ANSWER_WAIT_MS))
        // "Do not write old ones" has no keys: a changed card does not reject it, only the time
        assertEquals(PumpBtAnswer.WAITING, PumpBtWindow.answer(1_000, null, listOf(b), 2_000))
        assertEquals(PumpBtAnswer.NOT_TAKEN, PumpBtWindow.answer(1_000, null, list, 1_000 + PumpBtWindow.ANSWER_WAIT_MS))
        // after a refusal the new list, once shown, can be written
        assertTrue(PumpBtWindow.canWrite(listOf(a, b, c), listOf(a, b, c), PumpBtAnswer.REJECTED))
        assertTrue(PumpBtWindow.canWrite(list, list, PumpBtAnswer.NOT_TAKEN))
    }

    @Test
    fun theShownListSurvivesATurnedScreen() {
        val list = listOf(a, b, c)
        assertEquals(list, PumpBtWindow.unpackItems(PumpBtWindow.packItems(list)))
        assertEquals(emptyList<BackfillItem>(), PumpBtWindow.unpackItems(PumpBtWindow.packItems(emptyList())))
        assertNull(PumpBtWindow.unpackItems(listOf("x")))
        assertNull(PumpBtWindow.unpackItems(listOf(arrayListOf("k"), LongArray(0), DoubleArray(1), DoubleArray(1))))
    }

    @Test
    fun dataSectionWhileThereIsSomethingToShow() {
        assertFalse(PumpBtWindow.dataShown(off))
        assertTrue(PumpBtWindow.dataShown(on))
        assertTrue(PumpBtWindow.dataShown(off.copy(records = 3)))
        assertTrue(PumpBtWindow.dataShown(off.copy(lastCycleAtMs = 1L)))
    }
}
