package mod.pumpbt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tk.glucodata.mod.carelink.CareLinkSettings
import tk.glucodata.mod.pumpbt.BackfillCard
import tk.glucodata.mod.pumpbt.BackfillItem
import tk.glucodata.mod.pumpbt.ModPumpStarter
import tk.glucodata.mod.pumpbt.PumpBtAnswer
import tk.glucodata.mod.pumpbt.PumpBtCount
import tk.glucodata.mod.pumpbt.PumpBtKey
import tk.glucodata.mod.pumpbt.PumpBtLinkState
import tk.glucodata.mod.pumpbt.PumpBtNameVariant
import tk.glucodata.mod.pumpbt.PumpBtSourceState
import tk.glucodata.mod.pumpbt.PumpBtStage
import tk.glucodata.mod.pumpbt.PumpBtStartAction
import tk.glucodata.mod.pumpbt.PumpBtStore
import tk.glucodata.mod.pumpbt.PumpBtTrial
import tk.glucodata.mod.pumpbt.PumpBtTurnOnHint
import tk.glucodata.mod.pumpbt.PumpBtUiState
import tk.glucodata.mod.pumpbt.PumpBtWindow
import tk.glucodata.mod.pumpbt.PumpBtWizardStep
import tk.glucodata.mod.pumpbt.PumpHistoryCounters
import tk.glucodata.mod.pumpbt.PumpWarning
import tk.glucodata.mod.pumpbt.core.toHex
import java.io.File

/**
 * JugglucoNGmod, pump over Bluetooth (series part 2, parts B2b and G1): what the pump window shows and allows, the
 * pairing wizard's steps, the accepted warnings, name variant B as a fallback, the threshold choices.
 */
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
        // no key: offered and pressable - the press downloads the key first (decision 11)
        val noKey = PumpBtWindow.controls(ready.copy(keyCrc = null), lost)
        assertTrue(noKey.pairOffered)
        assertTrue(noKey.canPair)
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

    // MARK: - The pairing wizard (series part 2, part G1)

    @get:Rule
    val tmp = TemporaryFolder()

    @After
    fun backToAndroid() = PumpBtTrial.resetForTest(null)

    /** A newcomer: nothing paired, no key, warnings not accepted. */
    private val newcomer = ready.copy(keyCrc = null, paired = false)
    private val pairingLink = PumpBtLinkState(running = true, pairMode = true, stage = PumpBtStage.ADVERTISING_PAIR)

    @Test
    fun wizardStepsByState() {
        assertEquals(PumpBtWizardStep.WARNINGS, PumpBtWindow.wizardStep(newcomer, off))
        val accepted = newcomer.copy(warningsAccepted = true)
        // the key does not stop the start: the press downloads it
        assertEquals(PumpBtWizardStep.START, PumpBtWindow.wizardStep(accepted, off))
        for (phone in listOf(
            accepted.copy(permissionsGranted = false), accepted.copy(bluetoothOn = false), accepted.copy(hasBluetooth = false),
            accepted.copy(advertisingSupported = false),
        )) {
            assertEquals(phone.toString(), PumpBtWizardStep.PHONE, PumpBtWindow.wizardStep(phone, off))
        }
        // pressed without a key: downloading, or it did not come
        assertEquals(PumpBtWizardStep.KEY, PumpBtWindow.wizardStep(accepted.copy(pendingStart = true, keyLoading = true), off))
        assertEquals(PumpBtWizardStep.KEY, PumpBtWindow.wizardStep(accepted.copy(pendingStart = true, keyFailure = PumpBtKey.Failure.HTTP), off))
        // a pairing - by the published mode or by the stage only
        assertEquals(PumpBtWizardStep.PAIRING, PumpBtWindow.wizardStep(accepted.copy(keyCrc = "01020304"), off.copy(link = pairingLink)))
        assertEquals(PumpBtWizardStep.PAIRING, PumpBtWindow.wizardStep(accepted.copy(keyCrc = "01020304"), off.copy(link = switchedToPairing)))
        // the handshake went through: paired, offer permanent operation
        val paired = accepted.copy(keyCrc = "01020304", paired = true)
        assertEquals(PumpBtWizardStep.PAIRED, PumpBtWindow.wizardStep(paired, off.copy(link = up)))
        assertEquals(PumpBtWizardStep.PAIRED, PumpBtWindow.wizardStep(paired, off))
        // the source on: no wizard, whatever else
        assertEquals(PumpBtWizardStep.NONE, PumpBtWindow.wizardStep(newcomer, on))
        assertEquals(PumpBtWizardStep.NONE, PumpBtWindow.wizardStep(accepted, on.copy(link = pairingLink)))
    }

    @Test
    fun anOwnerNeverSeesTheWarnings() {
        // paired once (read from the store at once) - not the warnings, even before "accepted" is known
        assertEquals(PumpBtWizardStep.PAIRED, PumpBtWindow.wizardStep(newcomer.copy(paired = true), off))
        assertEquals(PumpBtWizardStep.NONE, PumpBtWindow.wizardStep(newcomer.copy(paired = true), on))
    }

    @Test
    fun startPressedWithoutAKeyLoadsItAndWithoutTheKeyPermanentOperationStaysOff() {
        assertEquals(PumpBtStartAction.LOAD_KEY, PumpBtWindow.startAction(newcomer, hasKey = false))
        assertEquals(PumpBtStartAction.START, PumpBtWindow.startAction(newcomer, hasKey = true))
        assertEquals(PumpBtStartAction.REFUSE, PumpBtWindow.startAction(newcomer.copy(bluetoothOn = false), hasKey = false))
        assertEquals(PumpBtStartAction.REFUSE, PumpBtWindow.startAction(newcomer.copy(advertisingSupported = false), hasKey = true))
        assertTrue(PumpBtWindow.controls(newcomer, off).canStart)
        // without the key the source would not get through the handshake: permanent operation is not turned on
        assertFalse(PumpBtWindow.controls(newcomer.copy(paired = true), off).canTurnOn)
        assertEquals(PumpBtTurnOnHint.NO_KEY, PumpBtWindow.turnOnHint(newcomer.copy(paired = true), off))
    }

    @Test
    fun acceptedWarningsOutliveARestart() {
        val dir = tmp.newFolder()
        val t = FakeTrial(dir)
        PumpBtTrial.resetForTest(t.env, newcomer)
        PumpBtTrial.acceptWarnings(t.store)
        assertTrue(PumpBtTrial.state.value.warningsAccepted)
        assertEquals(PumpBtWizardStep.START, PumpBtWindow.wizardStep(PumpBtTrial.state.value, off))
        t.store.awaitWrites()
        // a new process reads the file
        assertTrue(PumpBtStore(File(dir, PumpBtStore.FILE), null).warningsAccepted)
        assertFalse(PumpBtStore(File(tmp.newFolder(), PumpBtStore.FILE), null).warningsAccepted)
    }

    @Test
    fun otherNameStopsThePairingStartsItWithBAndThePairingEndsOnA() {
        val t = FakeTrial(tmp.newFolder())
        t.key = true
        t.running = true
        PumpBtTrial.resetForTest(t.env, newcomer.copy(warningsAccepted = true, keyCrc = "01020304", link = pairingLink))
        PumpBtTrial.tryOtherName()
        assertEquals("the pairing with A stopped first", 1, t.stops)
        assertEquals(listOf(true to PumpBtNameVariant.B), t.starts)
        assertEquals(PumpBtNameVariant.B, PumpBtTrial.state.value.variant)

        // the handshake went through: back to A
        t.store.paired = true
        PumpBtTrial.onLink(up)
        assertEquals(PumpBtNameVariant.A, t.store.variant)
        assertEquals(PumpBtNameVariant.A, PumpBtTrial.state.value.variant)
        assertTrue(PumpBtTrial.state.value.paired)
        assertTrue(t.journal.toString(), t.journal.contains("name variant back to A after the pairing"))
        // a pairing still going on keeps B
        assertEquals(PumpBtNameVariant.B, PumpBtWindow.variantAfter(PumpBtNameVariant.B, pairingLink))
    }

    @Test
    fun pairAgainWithTheSourceOnIsAlwaysA() {
        val t = FakeTrial(tmp.newFolder())
        t.key = true
        t.sourceOn = true
        t.running = true
        t.store.variant = PumpBtNameVariant.B
        PumpBtTrial.resetForTest(t.env, ready.copy(variant = PumpBtNameVariant.B))
        PumpBtTrial.startPairing()
        // the running link may have started with B: stopped, then the pairing anew with A
        assertEquals(1, t.stops)
        assertEquals(listOf(true to PumpBtNameVariant.A), t.starts)
        assertEquals(PumpBtNameVariant.A, PumpBtTrial.state.value.variant)

        // the other name is not tried with the source on
        PumpBtTrial.tryOtherName()
        assertEquals(1, t.starts.size)
        assertEquals(PumpBtNameVariant.A, t.store.variant)
    }

    // MARK: - Review fixes (critic G1)

    @Test
    fun aDroppedPairingWithBLeavesNoBForTheNextPairing() {
        // C1: B in the store (a failed or stopped pairing with B, or a dead process) - the next pairing is A
        val t = FakeTrial(tmp.newFolder())
        t.key = true
        t.store.variant = PumpBtNameVariant.B
        PumpBtTrial.resetForTest(t.env, ready.copy(paired = false, warningsAccepted = true, variant = PumpBtNameVariant.B))
        PumpBtTrial.startPairing()
        assertEquals(listOf(true to PumpBtNameVariant.A), t.starts)
        assertEquals(PumpBtNameVariant.A, PumpBtTrial.state.value.variant)

        // B tried during the pairing, then Stop, then Start pairing: A again
        PumpBtTrial.onLink(pairingLink)
        PumpBtTrial.tryOtherName()
        assertEquals(true to PumpBtNameVariant.B, t.starts.last())
        PumpBtTrial.stop()
        PumpBtTrial.onLink(pairingLink.copy(running = false, pairMode = false, stage = PumpBtStage.STOPPED))
        PumpBtTrial.startPairing()
        assertEquals(true to PumpBtNameVariant.A, t.starts.last())
        assertEquals(PumpBtNameVariant.A, t.store.variant)
    }

    @Test
    fun aLinkStartedWithBIsStoppedBeforePairingAgainWithA() {
        // C1: the pairing with B went through (the store is back on A) but the link still runs with B
        val t = FakeTrial(tmp.newFolder())
        t.key = true
        PumpBtTrial.resetForTest(t.env, newcomer.copy(warningsAccepted = true, keyCrc = "01020304"))
        PumpBtTrial.onLink(pairingLink)
        t.running = true
        PumpBtTrial.tryOtherName()
        t.store.paired = true
        PumpBtTrial.onLink(up)
        assertEquals(PumpBtNameVariant.A, t.store.variant)
        val stops = t.stops
        t.sourceOn = true
        PumpBtTrial.startPairing()
        assertEquals("the link of B is stopped first", stops + 1, t.stops)
        assertEquals(true to PumpBtNameVariant.A, t.starts.last())
    }

    @Test
    fun otherNameOnlyDuringAPairing() {
        // N1: a waiting link of a paired pump is not stopped for B
        val t = FakeTrial(tmp.newFolder())
        t.key = true
        t.running = true
        PumpBtTrial.resetForTest(t.env, ready.copy(link = running))
        PumpBtTrial.tryOtherName()
        assertEquals(0, t.stops)
        assertTrue(t.starts.isEmpty())
        assertEquals(PumpBtNameVariant.A, t.store.variant)
        assertTrue(t.journal.contains("other name not tried: no pairing going on"))
    }

    @Test
    fun aCancelledWaitForTheKeyDoesNotStartWhenTheKeyComesLater() {
        // C2: Cancel (or Stop) in the KEY step - a key from a file later starts nothing
        val t = FakeTrial(tmp.newFolder())
        PumpBtTrial.resetForTest(t.env, newcomer.copy(warningsAccepted = true))
        PumpBtTrial.startPairing()
        assertEquals(PumpBtWizardStep.KEY, PumpBtWindow.wizardStep(PumpBtTrial.state.value, off))
        PumpBtTrial.cancelPendingStart()
        assertNull(PumpBtTrial.state.value.pendingStart)
        assertEquals(PumpBtWizardStep.START, PumpBtWindow.wizardStep(PumpBtTrial.state.value, off))
        PumpBtTrial.applyKey(PumpBtKey.parse(fakeKeyDatabase(local = 4, remotes = listOf(1)).toHex()), "a file")
        assertTrue("the key is kept", t.key)
        assertTrue("nothing started", t.starts.isEmpty())

        // Stop clears the wait too; "Retry" is Start pairing again
        val s = FakeTrial(tmp.newFolder())
        PumpBtTrial.resetForTest(s.env, newcomer.copy(warningsAccepted = true))
        PumpBtTrial.startPairing()
        assertEquals(true, PumpBtTrial.state.value.pendingStart)
        PumpBtTrial.stop()
        assertNull(PumpBtTrial.state.value.pendingStart)
        PumpBtTrial.startPairing()
        assertEquals("retried: both pages asked again", 4, s.asked.size)
    }

    @Test
    fun theProcessStartGivesBackTheNameVariantBLeftChanged() {
        // C3: a newcomer's process died during a pairing with B - no pump address, the name still changed
        assertTrue(ModPumpStarter.needsInit(false, { false }, { true }))
        assertFalse(ModPumpStarter.needsInit(false, { false }, { false }))
        assertTrue(ModPumpStarter.needsInit(true, { false }, { false }))
        assertTrue(ModPumpStarter.needsInit(false, { true }, { false }))

        val dir = tmp.newFolder()
        val file = File(dir, PumpBtStore.FILE)
        // never used the pump: no file, nothing made
        assertFalse(PumpBtStore.adapterNameChanged(file, null))
        assertFalse(file.exists())
        val store = PumpBtStore(file, null)
        assertFalse(PumpBtStore.adapterNameChanged(file, null))
        store.originalAdapterName = "Phone"
        assertTrue(PumpBtStore.adapterNameChanged(file, null))
        store.originalAdapterName = null
        assertFalse(PumpBtStore.adapterNameChanged(file, null))
        file.writeText("not json")
        assertFalse(PumpBtStore.adapterNameChanged(file, null))
    }

    @Test
    fun theWindowsThresholdChoices() {
        assertEquals(listOf(20, 30, 60), CareLinkSettings.WARN_CHOICES)
        // 5/10/15 act as 20 over Bluetooth: shown as 20, stored as they are
        for (old in listOf(5, 10, 15)) {
            assertEquals(20, CareLinkSettings.shownWarnMin(old))
            assertTrue(old in CareLinkSettings.WARN_AFTER_MIN)
        }
        for (choice in listOf(20, 30, 60)) {
            assertEquals(choice, CareLinkSettings.shownWarnMin(choice))
            assertTrue("stored as it is", choice in CareLinkSettings.WARN_AFTER_MIN)
        }
        assertEquals(30, CareLinkSettings.shownWarnMin(45))
        assertEquals(30, CareLinkSettings.shownWarnMin(0))
    }
}
