package mod.watch

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tk.glucodata.mod.watch.ModWatchMessages
import tk.glucodata.mod.watch.ModWatchMessages.PumpIob
import tk.glucodata.mod.watch.ModWatchMessages.Tick
import tk.glucodata.mod.watch.ModWearListener
import tk.glucodata.mod.watch.PhoneBatteryStore
import tk.glucodata.mod.watch.PumpIobStore
import tk.glucodata.mod.watch.TickOrder

/**
 * JugglucoNGmod, watch face (20.11): the pump's active insulin from `/mod/tick` on the watch - kept with its own time on
 * the watch's clock, removed by a tick without it (an older phone too), the face woken only when it changed. Made-up
 * values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PumpIobStoreTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PhoneBatteryStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun tick(iob: PumpIob?) =
        ModWatchMessages.encodeTick(Tick(64, false, now - 3_000L, ModWatchMessages.REASON_CYCLE, iob))

    @Test
    fun aTickWithTheActiveInsulinKeepsItWithItsTimeOnTheWatchsClock() {
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(23, 45)), now))
        assertEquals(PumpIobStore.PumpIob(23, now - 45_000L), PumpIobStore.read(context))
        // the phone battery as before
        assertEquals(64, PhoneBatteryStore.read(context, now))
    }

    @Test
    fun aTickWithoutItRemovesTheKeptOne() {
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(23, 45)), now)
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(null), now + 5 * minute))
        assertNull(PumpIobStore.read(context))
        assertEquals(64, PhoneBatteryStore.read(context, now + 5 * minute))
    }

    @Test
    fun anOlderPhonesTickHasNoneAndIsStillATick() {
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(23, 45)), now)
        // 12 bytes, as an older phone sends it
        val old = ModWatchMessages.encodeTick(Tick(64, false, now - 2_000L, ModWatchMessages.REASON_CYCLE))
        assertEquals(12, old.size)
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_TICK, old, now))
        assertNull(PumpIobStore.read(context))
    }

    @Test
    fun theFaceIsWokenOnlyWhenTheNumberChanged() {
        val r0 = PumpIobStore.revision.value
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(23, 45)), now)
        val r1 = PumpIobStore.revision.value
        assertTrue(r1 > r0)
        // the same number 5 minutes later, its age 5 minutes more: no wake-up
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(23, 345)), now + 5 * minute)
        assertEquals(r1, PumpIobStore.revision.value)
        // another value, the same number with a new time, gone: each wakes the face
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(31, 345)), now + 5 * minute)
        val r2 = PumpIobStore.revision.value
        assertTrue(r2 > r1)
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(PumpIob(31, 10)), now + 10 * minute)
        val r3 = PumpIobStore.revision.value
        assertTrue(r3 > r2)
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(null), now + 15 * minute)
        assertTrue(PumpIobStore.revision.value > r3)
    }

    @Test
    fun anOlderTickArrivingLaterChangesNothing() {
        // critic 9, L3: the cycle tick and an extra tick from two threads on the phone, the older one last
        val newer = ModWatchMessages.encodeTick(Tick(60, false, now, ModWatchMessages.REASON_IOB, PumpIob(31, 10)))
        val older = ModWatchMessages.encodeTick(Tick(64, false, now - 5_000L, ModWatchMessages.REASON_CYCLE,
            PumpIob(23, 300)))
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_TICK, newer, now + 1_000L))
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_TICK, older, now + 2_000L))
        assertEquals(31, PumpIobStore.read(context)!!.tenths)
        assertEquals(60, PhoneBatteryStore.read(context, now + 2_000L))
        // the phone's clock set back by more than CLOCK_JUMP_MS: taken
        val setBack = ModWatchMessages.encodeTick(Tick(58, false, now - TickOrder.CLOCK_JUMP_MS - minute,
            ModWatchMessages.REASON_CYCLE, PumpIob(20, 5)))
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, setBack, now + 3_000L)
        assertEquals(20, PumpIobStore.read(context)!!.tenths)
    }

    @Test
    fun theOrderRule() {
        assertTrue(TickOrder.inOrder(null, now))
        assertTrue(TickOrder.inOrder(now, now))
        assertTrue(TickOrder.inOrder(now, now + 1))
        assertFalse(TickOrder.inOrder(now, now - 1))
        assertFalse(TickOrder.inOrder(now, now - TickOrder.CLOCK_JUMP_MS))
        assertTrue(TickOrder.inOrder(now, now - TickOrder.CLOCK_JUMP_MS - 1))
    }

    @Test
    fun theActiveInsulinsOwnTickStartsNeitherTheHandOverNorTheHoleCheck() {
        // critic 9, M1: by the listener's code - only a tick of another reason calls them
        val code = listOf(java.io.File("src/wear/java/tk/glucodata/mod/watch/ModWearListener.kt"),
            java.io.File("Common/src/wear/java/tk/glucodata/mod/watch/ModWearListener.kt")).first { it.isFile }.readText()
        assertTrue(code.contains("val cycle = tick.reason != ModWatchMessages.REASON_IOB"))
        assertTrue(code.contains("if (cycle) HealthTick.onTick(context, nowMs)"))
        assertTrue(code.contains("if (cycle) HistoryGapFill.onTick(context, nowMs)"))
        assertEquals(1, Regex("HealthTick\\.onTick\\(").findAll(code).count())
        assertEquals(1, Regex("HistoryGapFill\\.onTick\\(").findAll(code).count())
    }

    @Test
    fun theChangeRule() {
        val a = PumpIobStore.PumpIob(23, now)
        assertFalse(PumpIobStore.changed(null, null))
        assertTrue(PumpIobStore.changed(null, a))
        assertTrue(PumpIobStore.changed(a, null))
        assertFalse(PumpIobStore.changed(a, a.copy(valueAtMs = now + PumpIobStore.SAME_TIME_MS)))
        assertTrue(PumpIobStore.changed(a, a.copy(valueAtMs = now + PumpIobStore.SAME_TIME_MS + 1)))
        assertTrue(PumpIobStore.changed(a, a.copy(tenths = 24)))
    }
}
