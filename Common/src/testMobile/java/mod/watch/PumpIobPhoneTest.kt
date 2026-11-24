package mod.watch

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tk.glucodata.drivers.api.ApiIobSnapshot
import tk.glucodata.mod.watch.ModWatchMessages
import tk.glucodata.mod.watch.PumpIobMemory
import tk.glucodata.mod.watch.PumpIobMemory.Reading
import tk.glucodata.mod.watch.PumpIobPhone
import tk.glucodata.mod.watch.PumpIobTicks

/**
 * JugglucoNGmod, watch face (20.11): the pump's active insulin on the phone side - only the pump's number from the
 * slot, the memory past its 30 minutes, the extra tick on a change at most once a minute. Pure rules. Made-up values.
 */
class PumpIobPhoneTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L

    @Test
    fun onlyTheSlotsNumberWhileTheApiFollowerIsOff() {
        assertEquals(Reading(23, now - minute), PumpIobPhone.pumpOnly(false, 2.3f, now - minute))
        assertEquals(Reading(0, now), PumpIobPhone.pumpOnly(false, 0.04f, now))
        assertEquals(Reading(125, now), PumpIobPhone.pumpOnly(false, 12.5f, now))
        // the follower on: the slot may hold another phone's journal number - none
        assertNull(PumpIobPhone.pumpOnly(true, 2.3f, now))
        // none, broken, negative, no time
        assertNull(PumpIobPhone.pumpOnly(false, null, now))
        assertNull(PumpIobPhone.pumpOnly(false, Float.NaN, now))
        assertNull(PumpIobPhone.pumpOnly(false, -0.5f, now))
        assertNull(PumpIobPhone.pumpOnly(false, 2.3f, 0L))
        assertEquals(ModWatchMessages.IOB_MAX_TENTHS, PumpIobPhone.pumpOnly(false, 1e9f, now)!!.tenths)
    }

    @Test
    fun theSlotIsTheOnlySourceNeverTheJournal() {
        // the phone side and the watch side of the face never call upstream's journal or merged remote IOB
        val files = listOf(
            "mobile/java/tk/glucodata/mod/watch/PumpIobPhone.kt",
            "mobile/java/tk/glucodata/mod/watch/WatchTicker.kt",
        )
        for (path in files) {
            val code = withoutComments(file(path))
            for (name in listOf("JournalIobAccess", "WearJournalIob", "RemoteIobSnapshot", "CloneIobSnapshot",
                "NightscoutFollowerDeviceStatus", "JournalSnapshotAccess")) {
                assertFalse("$path uses $name", code.contains(name))
            }
        }
        assertTrue(withoutComments(file(files[0])).contains("ApiIobSnapshot.fresh("))
    }

    @Test
    fun theMemoryKeepsTheNumberPastItsThirtyMinutesWithItsAge() {
        val memory = PumpIobMemory()
        val r = Reading(23, now)
        assertEquals(r, memory.observe(r, now + minute))
        // the slot let it go after its 30 minutes: still sent, the watch shows "- U"
        val later = now + ApiIobSnapshot.FRESHNESS_WINDOW_MS + minute
        assertEquals(r, memory.observe(null, later))
        assertEquals(ModWatchMessages.PumpIob(23, 31 * 60), PumpIobMemory.toMessage(r, later))
        // and forgotten after KEEP_MS
        assertNull(memory.observe(null, now + PumpIobMemory.KEEP_MS + minute))
        assertNull(memory.last)
    }

    @Test
    fun aNumberGoneFromTheSlotEarlyIsForgottenAtOnce() {
        // CareLink signed out, the follower switched off: the next tick carries none
        val memory = PumpIobMemory()
        val r = Reading(23, now)
        memory.observe(r, now)
        assertNull(memory.observe(null, now + 10 * minute))
        assertNull(memory.last)
    }

    @Test
    fun afterARestartTheRememberedNumberStaysUntilTheSlotIsFilledAgain() {
        // the slot is empty after the phone app's restart until the next CareLink poll
        val memory = PumpIobMemory(Reading(23, now))
        assertEquals(Reading(23, now), memory.observe(null, now + 5 * minute))
        val next = Reading(18, now + 6 * minute)
        assertEquals(next, memory.observe(next, now + 6 * minute))
    }

    @Test
    fun theAgeInATickIsWholeSecondsAndNeverNegative() {
        assertEquals(ModWatchMessages.PumpIob(23, 45), PumpIobMemory.toMessage(Reading(23, now - 45_999L), now))
        assertEquals(ModWatchMessages.PumpIob(23, 0), PumpIobMemory.toMessage(Reading(23, now + 5_000L), now))
    }

    @Test
    fun aChangedNumberSendsAnExtraTickAtMostOnceAMinute() {
        val ticks = PumpIobTicks()
        val a = Reading(23, now)
        val b = Reading(31, now + 5 * minute)
        // before any tick of the process: nothing (the tick that finds a watch carries it)
        assertFalse(ticks.due(a, now, 0L))
        ticks.sent(a)
        // the same number, also with time going on: nothing
        assertFalse(ticks.due(a, now + 10 * minute, now))
        // a new value: an extra tick, but not within a minute of any tick
        assertFalse(ticks.due(b, now + 30_000L, now))
        assertTrue(ticks.due(b, now + minute, now))
        // critic 9, M1: only a new time of the same tenths (each CareLink poll) - none; the cycle tick brings it
        assertFalse(ticks.due(Reading(23, now + 5 * minute), now + minute, now))
        // gone: a tick that removes it on the watch
        assertTrue(ticks.due(null, now + minute, now))
        // claimed: not asked again every minute, even without a watch
        ticks.sent(b)
        assertFalse(ticks.due(b, now + 3 * minute, now))
        // came after none
        ticks.sent(null)
        assertTrue(ticks.due(a, now + 3 * minute, now))
    }

    @Test
    fun theDifferenceRule() {
        assertFalse(PumpIobTicks.differs(null, null))
        assertTrue(PumpIobTicks.differs(null, Reading(0, now)))
        assertTrue(PumpIobTicks.differs(Reading(0, now), null))
        assertFalse(PumpIobTicks.differs(Reading(23, now), Reading(23, now + 5 * minute)))
        assertTrue(PumpIobTicks.differs(Reading(23, now), Reading(24, now)))
    }

    private fun withoutComments(code: String) =
        code.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")

    private fun file(path: String) =
        listOf(File("src/$path"), File("Common/src/$path")).first { it.isFile }.readText()
}
