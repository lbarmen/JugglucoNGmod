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
import tk.glucodata.mod.watch.HealthTick
import tk.glucodata.mod.watch.ModWatchMessages
import tk.glucodata.mod.watch.ModWatchMessages.Tick
import tk.glucodata.mod.watch.ModWearListener
import tk.glucodata.mod.watch.PhoneBatteryStore
import tk.glucodata.mod.watch.PhoneBatteryStore.PhoneBattery

/** JugglucoNGmod, watch block: `/mod/tick` on the watch - the phone battery kept and shown, the health tick filter. Made-up values. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PhoneBatteryStoreTest {

    private val now = 1_800_000_000_000L
    private val minute = 60_000L
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PhoneBatteryStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun tick(percent: Int?, charging: Boolean = false, reason: Int = ModWatchMessages.REASON_CYCLE) =
        ModWatchMessages.encodeTick(Tick(percent, charging, now - 3_000L, reason))

    @Test
    fun noTickYetIsNothing() {
        assertNull(PhoneBatteryStore.read(context, now))
        assertNull(PhoneBatteryStore.readFull(context, now))
    }

    @Test
    fun aFreshTickIsShownWithItsChargingState() {
        assertTrue(ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(68, charging = true), now))
        assertEquals(68, PhoneBatteryStore.read(context, now))
        assertEquals(PhoneBattery(68, true, now), PhoneBatteryStore.readFull(context, now + minute))
    }

    @Test
    fun thirtyMinutesExactlyIsStillShownOlderIsNot() {
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(41), now)
        assertEquals(41, PhoneBatteryStore.read(context, now + 30 * minute))
        assertNull(PhoneBatteryStore.read(context, now + 30 * minute + 1))
        assertNull(PhoneBatteryStore.read(context, now + 5 * 60 * minute))
    }

    @Test
    fun theWatchTimeOfReceiptCountsNotThePhoneTime() {
        // the phone's clock is an hour behind: the tick was still received now
        val bytes = ModWatchMessages.encodeTick(Tick(77, false, now - 60 * minute, ModWatchMessages.REASON_CYCLE))
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, bytes, now)
        assertEquals(77, PhoneBatteryStore.read(context, now + minute))
    }

    @Test
    fun aNewerTickReplacesTheOlderOne() {
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(50), now)
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(44, charging = true), now + 5 * minute)
        assertEquals(PhoneBattery(44, true, now + 5 * minute), PhoneBatteryStore.readFull(context, now + 6 * minute))
    }

    @Test
    fun anUnknownBatteryIsNotShown() {
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(52), now)
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(null), now + 5 * minute)
        assertNull(PhoneBatteryStore.read(context, now + 5 * minute))
    }

    @Test
    fun aTimeOfReceiptAfterNowIsNotShown() {
        // the watch clock was set back after the tick
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(90), now)
        assertNull(PhoneBatteryStore.read(context, now - minute))
    }

    @Test
    fun freshRule() {
        assertNull(PhoneBatteryStore.fresh(null, now))
        assertEquals(PhoneBattery(1, false, now), PhoneBatteryStore.fresh(PhoneBattery(1, false, now), now))
        assertNull(PhoneBatteryStore.fresh(PhoneBattery(-1, false, now), now))
        assertNull(PhoneBatteryStore.fresh(PhoneBattery(101, false, now), now))
        assertEquals(30 * minute, PhoneBatteryStore.MAX_AGE_MS)
    }

    @Test
    fun aBrokenTickChangesNothing() {
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(63), now)
        for (bad in listOf(null, ByteArray(0), byteArrayOf(1, 2), tick(63).copyOf().also { it[0] = 9 })) {
            assertFalse(ModWearListener.handle(context, ModWatchMessages.PATH_TICK, bad, now + minute))
        }
        assertEquals(PhoneBattery(63, false, now), PhoneBatteryStore.readFull(context, now + minute))
    }

    @Test
    fun healthTickFilterDropsTicksWithinAMinute() {
        assertTrue(HealthTick.shouldRun(now, null))
        assertFalse(HealthTick.shouldRun(now, now))
        assertFalse(HealthTick.shouldRun(now, now - minute + 1))
        assertTrue(HealthTick.shouldRun(now, now - minute))
        assertTrue(HealthTick.shouldRun(now, now + 10 * minute)) // the clock set back
        assertEquals(minute, HealthTick.MIN_GAP_MS)
    }

    @Test
    fun healthTickGoesThroughOnceAMinute() {
        assertTrue(HealthTick.onTick(context, now))
        assertFalse(HealthTick.onTick(context, now + 20_000L)) // a battery tick right after the cycle tick
        assertFalse(HealthTick.onTick(context, now + minute - 1))
        assertTrue(HealthTick.onTick(context, now + minute))
        assertTrue(HealthTick.onTick(context, now + 6 * minute))
    }

    @Test
    fun everyValidTickKeepsTheBatteryEvenWhenTheHealthTickIsFiltered() {
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(70), now)
        ModWearListener.handle(context, ModWatchMessages.PATH_TICK, tick(64, reason = ModWatchMessages.REASON_BATTERY),
            now + 10_000L)
        assertEquals(64, PhoneBatteryStore.read(context, now + 10_000L))
        assertFalse(HealthTick.onTick(context, now + 20_000L))
    }
}
