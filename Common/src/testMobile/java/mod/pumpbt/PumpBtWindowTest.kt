package mod.pumpbt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.mod.pumpbt.BackfillCard
import tk.glucodata.mod.pumpbt.BackfillItem
import tk.glucodata.mod.pumpbt.PumpBtCount
import tk.glucodata.mod.pumpbt.PumpBtLinkState
import tk.glucodata.mod.pumpbt.PumpBtSourceState
import tk.glucodata.mod.pumpbt.PumpBtStage
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
    fun turningOnNeedsTheKeyAndBluetooth() {
        assertFalse(PumpBtWindow.controls(ready.copy(keyCrc = null), off).canTurnOn)
        assertFalse(PumpBtWindow.controls(ready.copy(bluetoothOn = false), off).canTurnOn)
        assertFalse(PumpBtWindow.controls(ready.copy(advertisingSupported = false), off).canTurnOn)
        // only the permissions missing: the press asks for them first
        assertTrue(PumpBtWindow.controls(ready.copy(permissionsGranted = false), off).canTurnOn)
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
        assertTrue(PumpBtWindow.bondAdvice(lost))
        // pairing already running: no second press
        assertFalse(PumpBtWindow.controls(ready, lost.copy(link = running.copy(pairMode = true))).pairOffered)
        // no key: offered, not pressable
        val noKey = PumpBtWindow.controls(ready.copy(keyCrc = null), lost)
        assertTrue(noKey.pairOffered)
        assertFalse(noKey.canPair)
        // paired and bonded: nothing
        assertFalse(PumpBtWindow.controls(ready, on.copy(link = running)).pairOffered)
    }

    @Test
    fun bondAdviceOnlyWithTheSourceOnAndThePumpNotLinked() {
        assertFalse(PumpBtWindow.bondAdvice(off.copy(bondLost = true)))
        assertFalse(PumpBtWindow.bondAdvice(on.copy(bondLost = true, link = up)))
        assertTrue(PumpBtWindow.bondAdvice(on.copy(bondLost = true, link = up.copy(stage = PumpBtStage.LOST))))
        assertFalse(PumpBtWindow.bondAdvice(on))
    }

    @Test
    fun pairingAdviceAfterAFailedPairingWhileStillAdvertising() {
        val pairing = PumpBtLinkState(running = true, pairMode = true, stage = PumpBtStage.ADVERTISING_PAIR)
        assertFalse(PumpBtWindow.pairingAdvice(pairing))
        assertTrue(PumpBtWindow.pairingAdvice(pairing.copy(pairingFailedAtMs = 1_000)))
        // paired since (pair mode over) or stopped: gone
        assertFalse(PumpBtWindow.pairingAdvice(pairing.copy(pairingFailedAtMs = 1_000, pairMode = false)))
        assertFalse(PumpBtWindow.pairingAdvice(pairing.copy(pairingFailedAtMs = 1_000, running = false)))
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
    fun writePassesExactlyTheShownList() {
        val card = BackfillCard(
            bolusesBefore = 3, mealsBefore = 1, alreadyInJournal = 1,
            toWrite = listOf(
                BackfillItem("INSULIN|2026-11-27T10:00:00|0", 2_000, 1.5, null),
                BackfillItem("MEAL|2026-11-27T09:00:00|0", 1_000, null, 30.0),
                BackfillItem("INSULIN|2026-11-27T09:00:00|0", 1_000, 2.0, null),
            ),
            oldNotFound = 2,
        )
        val items = PumpBtWindow.cardItems(card)
        assertEquals(listOf(1_000L, 1_000L, 2_000L), items.map { it.atMs })
        val keys = PumpBtWindow.shownKeys(items)
        assertEquals(items.map { it.key }, keys)
        // the same keys the card holds, none added or dropped (the source compares them with the frozen list)
        assertEquals(card.toWrite.map { it.key }.toSet(), keys.toSet())
        assertEquals(card.toWrite.size, keys.size)
    }

    @Test
    fun dataSectionWhileThereIsSomethingToShow() {
        assertFalse(PumpBtWindow.dataShown(off))
        assertTrue(PumpBtWindow.dataShown(on))
        assertTrue(PumpBtWindow.dataShown(off.copy(records = 3)))
        assertTrue(PumpBtWindow.dataShown(off.copy(lastCycleAtMs = 1L)))
    }
}
